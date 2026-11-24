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
import com.vitorpamplona.cordn.spec00Coordinator.CoordinatorNotSentException
import com.vitorpamplona.cordn.spec00Coordinator.CoordinatorRefusedException
import com.vitorpamplona.cordn.spec00Coordinator.ICoordinator
import com.vitorpamplona.cordn.spec00Coordinator.PublishedKeyPackage
import com.vitorpamplona.cordn.spec01GroupMetadata.CordnGroupMetadata
import com.vitorpamplona.mls.group.MlsGroup
import com.vitorpamplona.mls.messages.KeyPackageBundle
import com.vitorpamplona.mls.messages.KeyPackageBundleCodec
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The private half of a KeyPackage, from publish to the Welcome it opens. */
@OptIn(ExperimentalEncodingApi::class)
class CordnKeyPackageLifecycleTest {
    private val gid = "8a7b6c5d-4e3f-4a1b-9c8d-7e6f5a4b3c2d"
    private val coordinatorKey = "cc".repeat(32)
    private val aliceSigner = NostrSignerSync(KeyPair())
    private val bobSigner = NostrSignerSync(KeyPair())
    private val alice: HexKey get() = aliceSigner.pubKey
    private val bob: HexKey get() = bobSigner.pubKey
    private val ref = "bb".repeat(16)

    private val config =
        CoordinatorConfig(
            pubKey = coordinatorKey,
            relays = listOf(RelayUrlNormalizer.normalizeOrNull("wss://relay.example.com")!!),
            origin = CoordinatorConfig.Origin.DEFAULT,
        )

    private fun bundle(lastResort: Boolean): KeyPackageBundle {
        val identity = CordnCredential.of(bob).identity
        val scratch = MlsGroup.create(identity, policy = CordnGroupPolicy)
        return scratch.createKeyPackage(
            identity = identity,
            signingKey = ByteArray(0),
            keyPackageExtensions = if (lastResort) listOf(CordnGroupPolicy.lastResortExtension()) else emptyList(),
            capabilities = if (lastResort) CordnGroupPolicy.lastResortLeafCapabilities() else CordnGroupPolicy.defaultLeafCapabilities,
        )
    }

    private fun stored(bundle: KeyPackageBundle): FakeCoordinator.StoredKeyPackage {
        val base64 = Base64.encode(bundle.keyPackage.toTlsBytes())
        val content =
            """{"jsonrpc":"2.0","id":1,"method":"tools/call",""" +
                """"params":{"name":"kp_publish","arguments":{"kp_ref":"$ref","kp_64":"$base64"}}}"""
        val event: Event =
            bobSigner.sign(EventTemplate(createdAt = 1_757_000_000L, kind = 25910, tags = arrayOf(arrayOf("p", coordinatorKey)), content = content))
        return FakeCoordinator.StoredKeyPackage(bob, ref, base64, event, lastResort = bundle.keyPackage.isLastResort())
    }

    private class Invited(
        val bob: CordnSession,
        val keys: CordnKeyPackageStore,
        val welcome: OpenedWelcome,
    )

    /** Bob, through a registry as an app opens him, holding one Welcome from Alice. */
    private suspend fun invited(lastResort: Boolean): Invited {
        val coordinator = FakeCoordinator(callerPubKey = alice)
        val bundle = bundle(lastResort)
        coordinator.seedKeyPackage(stored(bundle))
        val keys = InMemoryCordnKeyPackageStore()
        keys.save(ref, KeyPackageBundleCodec.encode(bundle))

        val aliceManager = CordnGroupManager(alice, config, coordinator, InMemoryCordnGroupStore())
        aliceManager.createGroup(gid, CordnGroupMetadata(name = "g"))
        aliceManager.invite(gid, bob)

        val bobView = coordinator.viewAs(bob)
        val registry =
            CordnCoordinatorRegistry(
                bob,
                { _, _ ->
                    object : CordnCoordinatorScope {
                        override val coordinator: ICoordinator = bobView
                        override val groupStore: CordnGroupStore = InMemoryCordnGroupStore()
                        override val keyPackageStore: CordnKeyPackageStore = keys

                        override suspend fun close() {}
                    }
                },
            )
        val session = registry.session(config)
        val welcome =
            session.manager
                .pendingWelcomes(session.keyPackages::bundleFor)
                .pending
                .single()
        return Invited(session, keys, welcome)
    }

    @Test
    fun `accepting a Welcome forgets its single-use private half`() =
        runTest {
            val i = invited(lastResort = false)
            assertTrue(i.bob.keyPackages.hasPublished())
            i.bob.manager.accept(i.welcome)
            assertNull(i.keys.load(ref), "the private half outlived its only use")
            assertFalse(i.bob.keyPackages.hasPublished())
            assertTrue(ref !in i.bob.keyPackages.published.value)
        }

    @Test
    fun `declining a Welcome forgets it too`() =
        runTest {
            val i = invited(lastResort = false)
            i.bob.manager.decline(i.welcome)
            assertNull(i.keys.load(ref))
        }

    @Test
    fun `a last-resort private half is kept, since it backs other Welcomes`() =
        runTest {
            val i = invited(lastResort = true)
            i.bob.manager.accept(i.welcome)
            assertNotNull(i.keys.load(ref))
        }

    /** A coordinator whose `kp_publish` does what [publish] says. */
    private class Publishing(
        val publish: suspend () -> PublishedKeyPackage,
    ) : ICoordinator by FakeCoordinator("aa".repeat(32)) {
        override suspend fun publishKeyPackage(
            keyPackageRef: String,
            keyPackageBase64: String,
        ): PublishedKeyPackage = publish()
    }

    @Test
    fun `a publish that timed out keeps the private half, since it may have landed`() =
        runTest {
            val store = InMemoryCordnKeyPackageStore()
            val keyPackages = CordnKeyPackages(bob, Publishing { withTimeout(10) { awaitCancellation() } }, store)
            assertFailsWith<Exception> { keyPackages.publishNew() }
            assertEquals(1, store.list().size, "a publish the coordinator may hold lost its private half")
        }

    @Test
    fun `a publish the coordinator refused drops the private half`() =
        runTest {
            val store = InMemoryCordnKeyPackageStore()
            val keyPackages = CordnKeyPackages(bob, Publishing { throw CoordinatorRefusedException("kp_publish failed: no", -32000) }, store)
            assertFailsWith<CoordinatorRefusedException> { keyPackages.publishNew() }
            assertTrue(store.list().isEmpty())
        }

    @Test
    fun `a first publish that timed out does not let maintain publish more`() =
        runTest {
            val store = InMemoryCordnKeyPackageStore()
            var publishes = 0
            val keyPackages =
                CordnKeyPackages(
                    bob,
                    Publishing {
                        publishes++
                        withTimeout(10) { awaitCancellation() }
                    },
                    store,
                )
            assertFailsWith<Exception> { keyPackages.publishNew() }
            assertTrue(keyPackages.hasPublished(), "the half stays: the publish may have landed")

            // Nothing on the coordinator says it did, so the pool is not this device's to grow.
            keyPackages.maintain()
            assertEquals(1, publishes, "maintain published on the strength of a publish nobody answered")
        }

    @Test
    fun `a publish refused by a closed link drops the private half`() =
        runTest {
            val store = InMemoryCordnKeyPackageStore()
            val closed = ClosableCoordinator(FakeCoordinator(bob)).apply { close() }
            val keyPackages = CordnKeyPackages(bob, closed, store)
            assertFailsWith<CoordinatorNotSentException> { keyPackages.publishNew() }
            assertTrue(store.list().isEmpty(), "a publish that never left the device kept its half")
            assertFalse(keyPackages.hasPublished())
        }

    @Test
    fun `maintain tops up once the coordinator lists a package of ours`() =
        runTest {
            val coordinator = FakeCoordinator(bob)
            val keyPackages = CordnKeyPackages(bob, coordinator, InMemoryCordnKeyPackageStore())
            keyPackages.publishNew()
            keyPackages.maintain()
            // The first, the pool's other four, and the last-resort one.
            assertEquals(CordnKeyPackages.DEFAULT_POOL + 1, coordinator.calls.count { it == "kp_publish" })
        }
}
