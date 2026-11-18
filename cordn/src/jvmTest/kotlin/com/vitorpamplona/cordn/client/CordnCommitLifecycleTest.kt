/*
 * Copyright (c) 2025 Vitor Pamplona
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.vitorpamplona.cordn.client

import com.vitorpamplona.cordn.appEncryptedMedia.CordnMediaEncryption
import com.vitorpamplona.cordn.groups.CordnCredential
import com.vitorpamplona.cordn.groups.CordnGroupPolicy
import com.vitorpamplona.cordn.spec01GroupMetadata.CordnGroupMetadata
import com.vitorpamplona.cordn.spec03Payloads.SealedPayload
import com.vitorpamplona.mls.group.MlsGroup
import com.vitorpamplona.mls.messages.KeyPackageBundle
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The parts of upstream's 0.5.x resync that are about time: messages from a member one Commit
 * behind (cordn-web `0e12afb`, its `ownCommitRegression` bugs 11/11b), and our own Commits racing
 * somebody else's (`e509ac3`, `5a19cdf`; bugs 5-10 and staircase's RaceTest).
 *
 * Every manager here runs over one coordinator through [FakeCoordinator.viewAs], so the stream,
 * its cursor and the Welcome inboxes are the ones a real coordinator would share.
 */
@OptIn(ExperimentalEncodingApi::class)
class CordnCommitLifecycleTest {
    private val gid = "0d5e4c4b-6e0a-4e3f-8b8e-2c1a9f7b6d5e"
    private val coordinatorKey = "cc".repeat(32)

    private val aliceSigner = NostrSignerSync(KeyPair())
    private val bobSigner = NostrSignerSync(KeyPair())
    private val carolSigner = NostrSignerSync(KeyPair())
    private val alice: HexKey get() = aliceSigner.pubKey
    private val bob: HexKey get() = bobSigner.pubKey
    private val carol: HexKey get() = carolSigner.pubKey

    private val config =
        CoordinatorConfig(
            pubKey = coordinatorKey,
            relays = listOf(RelayUrlNormalizer.normalizeOrNull("wss://relay.example.com")!!),
            origin = CoordinatorConfig.Origin.DEFAULT,
        )

    private fun manager(
        account: HexKey,
        coordinator: FakeCoordinator,
        store: CordnGroupStore = InMemoryCordnGroupStore(),
    ) = CordnGroupManager(
        accountPubKey = account,
        config = config,
        coordinator = coordinator,
        store = store,
        clock = { 1_757_000_000L },
    )

    /** [signer]'s KeyPackage and the signed `kp_publish` that binds it to them (`spec/00.md` §7). */
    private fun publication(
        signer: NostrSignerSync,
        ref: String,
    ): Pair<KeyPackageBundle, FakeCoordinator.StoredKeyPackage> {
        val scratch = MlsGroup.create(CordnCredential.of(signer.pubKey).identity, policy = CordnGroupPolicy)
        val bundle = scratch.createKeyPackage(CordnCredential.of(signer.pubKey).identity, ByteArray(0))
        val base64 = Base64.encode(bundle.keyPackage.toTlsBytes())
        val content =
            """{"jsonrpc":"2.0","id":1,"method":"tools/call",""" +
                """"params":{"name":"kp_publish","arguments":{"kp_ref":"$ref","kp_64":"$base64"}}}"""
        val event: Event =
            signer.sign(EventTemplate(createdAt = 1_757_000_000L, kind = 25910, tags = arrayOf(arrayOf("p", coordinatorKey)), content = content))
        return bundle to FakeCoordinator.StoredKeyPackage(signer.pubKey, ref, base64, event)
    }

    /** Alice's egalitarian group with Bob in it, both caught up at epoch 1. */
    private class Pair2(
        val coordinator: FakeCoordinator,
        val aliceStore: InMemoryCordnGroupStore,
        val alice: CordnGroupManager,
        val bobCoordinator: FakeCoordinator,
        val bob: CordnGroupManager,
    )

    private suspend fun aliceAndBob(): Pair2 {
        val coordinator = FakeCoordinator(callerPubKey = alice)
        val aliceStore = InMemoryCordnGroupStore()
        val aliceManager = manager(alice, coordinator, aliceStore)
        val (bobBundle, bobStored) = publication(bobSigner, "bb".repeat(16))
        coordinator.seedKeyPackage(bobStored)

        aliceManager.createGroup(gid, CordnGroupMetadata(name = "keep me"))
        aliceManager.invite(gid, bob)

        val bobCoordinator = coordinator.viewAs(bob)
        val bobManager = manager(bob, bobCoordinator)
        bobManager.joinPendingWelcomes({ bobBundle })
        bobManager.catchUp { }
        assertEquals(1L, bobManager.group(gid)!!.epoch)
        assertEquals(1L, aliceManager.group(gid)!!.epoch)
        return Pair2(coordinator, aliceStore, aliceManager, bobCoordinator, bobManager)
    }

    private fun CordnGroupManager.name() = CordnGroupMetadata.fromExtensions(group(gid)!!.extensions)?.name

    // ---- C1: former-epoch keys ------------------------------------------

    @Test
    fun `a message sealed one epoch back still opens`() =
        runTest {
            val g = aliceAndBob()
            val mediaKeyAtOne = CordnMediaEncryption.mediaKey(g.bob.group(gid)!!)

            // Alice renames; Bob has not caught up and sends at epoch 1. The
            // coordinator sequences his message after her Commit.
            g.alice.updateGroupMetadata(gid) { it.copy(name = "renamed") }
            assertEquals(2L, g.alice.group(gid)!!.epoch)
            g.bob.send(gid, "lagging")

            val delivered = mutableListOf<CordnGroupManager.Delivery>()
            g.alice.catchUp { delivered += it }

            val message = delivered.filterIsInstance<CordnGroupManager.Delivery.Message>().singleOrNull()
            assertNotNull(message, "a message from a member one Commit behind was lost: ${delivered.map { it::class.simpleName }}")
            assertEquals("lagging", message.received.envelope.content)
            assertEquals(1L, message.received.epoch)
            assertContentEquals(mediaKeyAtOne, message.mediaKey, "its attachments were sealed under epoch 1's media key")
            assertFalse(g.alice.mayBeBehind(gid), "a readable message is not evidence of a missed Commit")
            assertEquals(listOf("lagging"), g.alice.storedMessages(gid).map { it.envelope.content })
        }

    @Test
    fun `a former-epoch read is not proof of being current`() =
        runTest {
            val g = aliceAndBob()
            g.alice.updateGroupMetadata(gid) { it.copy(name = "renamed") }
            g.bob.send(gid, "lagging")
            // Something no key of Alice's opens: the mark goes up.
            g.coordinator.inject(gid, Base64.encode(ByteArray(64) { 7 }))
            g.alice.catchUp { }
            assertTrue(g.alice.mayBeBehind(gid))

            // Bob, now current, sends at epoch 2: that is the proof.
            g.bob.catchUp { }
            g.bob.send(gid, "current")
            g.alice.catchUp { }
            assertFalse(g.alice.mayBeBehind(gid))
        }

    // ---- C7: the media key travels with the message ---------------------

    @Test
    fun `a stored message keeps the media key of its own epoch across a commit`() =
        runTest {
            val g = aliceAndBob()
            g.bob.send(gid, "photo")
            g.alice.catchUp { }
            val keyAtOne = CordnMediaEncryption.mediaKey(g.alice.group(gid)!!)

            g.alice.updateGroupMetadata(gid) { it.copy(name = "renamed") }
            val keyAtTwo = CordnMediaEncryption.mediaKey(g.alice.group(gid)!!)
            assertFalse(keyAtOne.contentEquals(keyAtTwo))

            val stored = g.alice.storedMessages(gid).single()
            assertContentEquals(keyAtOne, stored.mediaKey)
            assertContentEquals(keyAtOne, g.alice.mediaKeyFor(gid, stored))
            // Our own sends record theirs too.
            val mine = g.alice.send(gid, "mine")
            assertContentEquals(keyAtTwo, mine.mediaKey)
        }

    // ---- C2: the commit lifecycle ---------------------------------------

    @Test
    fun `a commit that loses the race is rolled back, applies the winner, and sends no Welcome`() =
        runTest {
            val g = aliceAndBob()
            val (_, carolStored) = publication(carolSigner, "ca".repeat(16))
            g.coordinator.seedKeyPackage(carolStored)

            // Bob's rename lands between Alice's catch-up and her post.
            g.coordinator.beforeNextPost = { g.bob.updateGroupMetadata(gid) { it.copy(name = "bob's") } }
            val lost = mutableListOf<CordnGroupManager.Delivery>()
            g.alice.deliveryListener = { lost += it }

            val error = assertFailsWith<CordnGroupException> { g.alice.invite(gid, carol) }
            assertEquals(CordnGroupException.Reason.COMMIT_LOST, error.reason)

            // The change did not happen; Bob's did, and Alice is on it.
            assertEquals(2L, g.alice.group(gid)!!.epoch)
            assertEquals("bob's", g.alice.name())
            assertFalse(carol in CordnCredential.membersOf(g.alice.group(gid)!!).values)
            assertTrue(g.coordinator.welcomes[carol].isNullOrEmpty(), "no Welcome into a branch nobody is on")
            assertTrue(lost.any { it is CordnGroupManager.Delivery.CommitLost })

            // Bob is unharmed: Alice's losing Commit is not a Commit he applies,
            // and it is not evidence that he is behind.
            g.bob.catchUp { }
            assertEquals(2L, g.bob.group(gid)!!.epoch)
            assertFalse(g.bob.mayBeBehind(gid))

            // And the change can be made again, on the winner's epoch.
            g.alice.invite(gid, carol)
            assertEquals(3L, g.alice.group(gid)!!.epoch)
            assertEquals(1, g.coordinator.welcomes[carol]?.size)
            g.bob.catchUp { }
            assertEquals(3L, g.bob.group(gid)!!.epoch, "the retried Commit is one Bob can apply")
        }

    @Test
    fun `a commit that never landed is dropped by the next one, and never posted twice`() =
        runTest {
            val coordinator = FakeCoordinator(callerPubKey = alice)
            val aliceManager = manager(alice, coordinator)
            aliceManager.createGroup(gid, CordnGroupMetadata(name = "keep me"))

            coordinator.failNextPost = true
            val error = assertFailsWith<CordnGroupException> { aliceManager.updateGroupMetadata(gid) { it.copy(name = "first") } }
            assertEquals(CordnGroupException.Reason.COMMIT_UNCONFIRMED, error.reason)
            assertEquals(0L, aliceManager.group(gid)!!.epoch, "staged, not applied: the group did not move")
            assertTrue(aliceManager.group(gid)!!.hasPendingCommit)

            aliceManager.updateGroupMetadata(gid) { it.copy(name = "second") }
            assertEquals(1L, aliceManager.group(gid)!!.epoch)
            assertEquals("second", aliceManager.name())
            assertEquals(1, coordinator.posted(gid).size, "the lost Commit was never re-posted")
        }

    @Test
    fun `a commit whose answer was lost is confirmed by its echo, even after a restart`() =
        runTest {
            val coordinator = FakeCoordinator(callerPubKey = alice)
            val store = InMemoryCordnGroupStore()
            val (bobBundle, bobStored) = publication(bobSigner, "bb".repeat(16))
            coordinator.seedKeyPackage(bobStored)

            val first = manager(alice, coordinator, store)
            first.createGroup(gid, CordnGroupMetadata(name = "keep me"))
            coordinator.storeThenFailNextPost = true
            val error = assertFailsWith<CordnGroupException> { first.invite(gid, bob) }
            assertEquals(CordnGroupException.Reason.COMMIT_UNCONFIRMED, error.reason)
            assertTrue(coordinator.welcomes[bob].isNullOrEmpty(), "no Welcome before the Commit is confirmed")

            // The process dies here. The staged Commit and its Welcome were
            // written before the post, so the next run can finish the job.
            val restarted = manager(alice, coordinator, store)
            restarted.restore()
            assertTrue(restarted.group(gid)!!.hasPendingCommit)
            val delivered = mutableListOf<CordnGroupManager.Delivery>()
            restarted.catchUp { delivered += it }

            assertEquals(listOf("EpochAdvanced"), delivered.map { it::class.simpleName })
            assertEquals(1L, restarted.group(gid)!!.epoch)
            assertEquals(1, coordinator.welcomes[bob]?.size, "the Welcome goes out once the Commit is ours")

            val bobManager = manager(bob, coordinator.viewAs(bob))
            assertEquals(listOf(gid), bobManager.joinPendingWelcomes({ bobBundle }).joined)
        }

    @Test
    fun `an unopenable payload refuses changes but not messages`() =
        runTest {
            val g = aliceAndBob()
            g.coordinator.inject(gid, Base64.encode(ByteArray(64) { 7 }))

            val error = assertFailsWith<CordnGroupException> { g.alice.updateGroupMetadata(gid) { it.copy(name = "nope") } }
            assertEquals(CordnGroupException.Reason.BEHIND, error.reason)
            assertEquals("keep me", g.alice.name())
            assertEquals(1L, g.alice.group(gid)!!.epoch)

            // One stray payload must not block chat.
            g.alice.send(gid, "still fine")

            // A message sealed for the current epoch proves we are on the line.
            g.bob.send(gid, "readable")
            g.alice.updateGroupMetadata(gid) { it.copy(name = "back to normal") }
            assertEquals("back to normal", g.alice.name())
        }

    @Test
    fun `a commit staged from a stale view is never built`() =
        runTest {
            // Catch-up comes first: Bob's rename is applied before Alice
            // builds hers, so both land and nothing is lost.
            val g = aliceAndBob()
            g.bob.updateGroupMetadata(gid) { it.copy(name = "bob's") }
            g.alice.updateGroupMetadata(gid) { it.copy(description = "alice's") }

            assertEquals(3L, g.alice.group(gid)!!.epoch)
            assertEquals("bob's", g.alice.name())
            g.bob.catchUp { }
            assertEquals(3L, g.bob.group(gid)!!.epoch)
            assertEquals("alice's", CordnGroupMetadata.fromExtensions(g.bob.group(gid)!!.extensions)?.description)
        }

    @Test
    fun `the payload keys of retained epochs are what MLS retains`() =
        runTest {
            val g = aliceAndBob()
            repeat(5) { i -> g.alice.updateGroupMetadata(gid) { it.copy(name = "n$i") } }
            val keys = SealedPayload.formerApplicationKeys(g.alice.group(gid)!!)
            assertEquals(MlsGroup.RETAIN_EPOCHS, keys.size)
            assertEquals(keys.map { it.first }.sortedDescending(), keys.map { it.first }, "newest first")
        }
}
