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

import com.vitorpamplona.cordn.groups.CordnCredential
import com.vitorpamplona.cordn.groups.CordnGroupPolicy
import com.vitorpamplona.cordn.spec01GroupMetadata.CordnGroupMetadata
import com.vitorpamplona.cordn.spec02Envelopes.CordnAnnotationIndex
import com.vitorpamplona.cordn.spec02Envelopes.CordnApplicationMessage
import com.vitorpamplona.cordn.spec02Envelopes.CordnEnvelope
import com.vitorpamplona.cordn.spec02Envelopes.CordnMessageReferences
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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `spec/02.md` §5: the envelope's `pubkey` is "the authenticated sender identity derived from the
 * sender's MLS credential". The account pubkey also rides in `authenticated_data`, which the
 * SENDER writes: authenticated by the AEAD, but chosen by whoever holds the leaf. Taken alone it
 * let any member post as anyone, member or not, and the author-only rules of
 * [CordnAnnotationIndex] (edits, deletions, votes, RSVPs, cancellations) then judged the forgery
 * as the victim's.
 *
 * Devices of one account share one leaf (`multi-device.md` §2), and an account may also hold
 * several leaves of its own; either way every leaf it holds carries its account pubkey, so the
 * credential is the identity and the AAD has to agree with it.
 */
@OptIn(ExperimentalEncodingApi::class)
class CordnSenderBindingTest {
    private val gid = "6f0a3c1e-2b4d-4e8f-9a1c-7d5e3b2a1f0e"
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
    ) = CordnGroupManager(account, config, coordinator, InMemoryCordnGroupStore(), clock = { 1_757_000_000L })

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

    private class Room(
        val coordinator: FakeCoordinator,
        val alice: CordnGroupManager,
        val bob: CordnGroupManager,
        val carol: CordnGroupManager,
    )

    /** Alice's egalitarian group with Bob and Carol, everyone caught up. */
    private suspend fun room(): Room {
        val coordinator = FakeCoordinator(callerPubKey = alice)
        val aliceManager = manager(alice, coordinator)
        val (bobBundle, bobStored) = publication(bobSigner, "bb".repeat(16))
        val (carolBundle, carolStored) = publication(carolSigner, "ca".repeat(16))
        coordinator.seedKeyPackage(bobStored)
        coordinator.seedKeyPackage(carolStored)

        aliceManager.createGroup(gid, CordnGroupMetadata(name = "room"))
        aliceManager.inviteAll(gid, listOf(bob, carol))

        val bobManager = manager(bob, coordinator.viewAs(bob))
        bobManager.joinPendingWelcomes({ bobBundle })
        val carolManager = manager(carol, coordinator.viewAs(carol))
        carolManager.joinPendingWelcomes({ carolBundle })
        listOf(aliceManager, bobManager, carolManager).forEach { it.catchUp { } }
        return Room(coordinator, aliceManager, bobManager, carolManager)
    }

    /** What a member's own client cannot be stopped from doing: AAD and envelope both claiming [claimed]. */
    private fun forge(
        group: MlsGroup,
        claimed: HexKey,
        envelope: CordnEnvelope,
    ): String {
        val framed = group.encrypt(envelope.encode(), authenticatedData = claimed.encodeToByteArray())
        return SealedPayload.seal(framed, SealedPayload.applicationKey(group))
    }

    private fun chat(
        author: HexKey,
        text: String,
    ) = CordnEnvelope.build(author, 1_757_000_100L, CordnGroupManager.CHAT_KIND, emptyArray(), text)

    private suspend fun CordnGroupManager.messages(): List<CordnGroupManager.Delivery.Message> {
        val delivered = mutableListOf<CordnGroupManager.Delivery>()
        catchUp { delivered += it }
        return delivered.filterIsInstance<CordnGroupManager.Delivery.Message>()
    }

    @Test
    fun `a member cannot post as another member`() =
        runTest {
            val r = room()
            r.coordinator.inject(gid, forge(r.bob.group(gid)!!, carol, chat(carol, "carol says hi")))

            val shown = r.alice.messages()
            assertTrue(shown.none { it.received.sender == carol }, "Bob's leaf posted as Carol: ${shown.map { it.received.sender }}")
            assertTrue(r.alice.storedMessages(gid).isEmpty())
        }

    @Test
    fun `a member cannot post as someone outside the group`() =
        runTest {
            val r = room()
            val stranger = NostrSignerSync(KeyPair()).pubKey
            r.coordinator.inject(gid, forge(r.bob.group(gid)!!, stranger, chat(stranger, "who am I")))

            assertTrue(r.alice.messages().isEmpty(), "a non-member's name was shown")
        }

    @Test
    fun `a forged deletion does not withdraw the victim's message`() =
        runTest {
            val r = room()
            val original = r.carol.send(gid, "keep me")
            val target = CordnMessageReferences.Target(original.envelope.id, carol, original.envelope.kind)
            val outbound = CordnMessageReferences.outbound(content = "", deleteTo = target)
            r.coordinator.inject(gid, forge(r.bob.group(gid)!!, carol, CordnEnvelope.build(carol, 1_757_000_200L, outbound.kind, outbound.tags, outbound.content)))

            r.alice.catchUp { }
            val index = CordnAnnotationIndex.of(r.alice.storedMessages(gid))
            assertTrue(!index.isDeleted(original.envelope.id), "Bob deleted Carol's message by claiming to be her")
        }

    @Test
    fun `a forged sender at a former epoch is refused too`() =
        runTest {
            val r = room()
            // Bob is still on epoch 1 when Alice moves to 2; his forgery is sealed at 1.
            r.alice.updateGroupMetadata(gid) { it.copy(name = "renamed") }
            r.coordinator.inject(gid, forge(r.bob.group(gid)!!, carol, chat(carol, "from the past")))

            assertTrue(r.alice.messages().none { it.received.sender == carol }, "a former-epoch forgery was shown")
        }

    @Test
    fun `a second device on the same leaf is its account`() =
        runTest {
            val r = room()
            // multi-device.md §2: devices share one leaf, one ClientState. A copy of Bob's state is
            // his other device.
            val laptop = MlsGroup.restore(r.bob.group(gid)!!.saveState(), CordnGroupPolicy)
            r.coordinator.inject(gid, CordnApplicationMessage.seal(laptop, bob, chat(bob, "from the laptop")))

            val shown = r.alice.messages()
            assertEquals(listOf(bob), shown.map { it.received.sender })
            assertEquals(
                "from the laptop",
                shown
                    .single()
                    .received.envelope.content,
            )
        }

    @Test
    fun `an honest message still opens, at the current and a former epoch`() =
        runTest {
            val r = room()
            r.bob.send(gid, "now")
            assertEquals(listOf(bob), r.alice.messages().map { it.received.sender })

            r.alice.updateGroupMetadata(gid) { it.copy(name = "renamed") }
            r.carol.send(gid, "one behind")
            val late = r.alice.messages()
            assertEquals(listOf(carol), late.map { it.received.sender })
            assertEquals(1L, late.single().received.epoch)
        }

    @Test
    fun `open with the group binds the sender to the leaf credential`() =
        runTest {
            val r = room()
            val forged = forge(r.bob.group(gid)!!, carol, chat(carol, "x"))
            val error = assertFailsWith<IllegalArgumentException> { CordnApplicationMessage.open(r.alice.group(gid)!!, forged) }
            assertTrue(error.message!!.contains("credential"), error.message)
        }
}
