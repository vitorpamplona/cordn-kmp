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
import com.vitorpamplona.cordn.spec00Coordinator.ConsumedWelcomeRef
import com.vitorpamplona.cordn.spec00Coordinator.ICoordinator
import com.vitorpamplona.cordn.spec00Coordinator.PendingWelcome
import com.vitorpamplona.cordn.spec01GroupMetadata.CordnGroupMetadata
import com.vitorpamplona.mls.group.MlsGroup
import com.vitorpamplona.mls.messages.KeyPackageBundle
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalEncodingApi::class, ExperimentalCoroutinesApi::class)
class CordnManagerEdgesTest {
    private val gid = "3c2b1a09-8f7e-4d6c-9b5a-4f3e2d1c0b9a"
    private val coordinatorKey = "cc".repeat(32)
    private val aliceSigner = NostrSignerSync(KeyPair())
    private val bobSigner = NostrSignerSync(KeyPair())
    private val alice: HexKey get() = aliceSigner.pubKey
    private val bob: HexKey get() = bobSigner.pubKey
    private val config = CoordinatorConfig(coordinatorKey, listOf(RelayUrlNormalizer.normalizeOrNull("wss://relay.example.com")!!))

    private fun publication(ref: String): Pair<KeyPackageBundle, FakeCoordinator.StoredKeyPackage> {
        val identity = CordnCredential.of(bob).identity
        val bundle = MlsGroup.create(identity, policy = CordnGroupPolicy).createKeyPackage(identity, ByteArray(0))
        val base64 = Base64.encode(bundle.keyPackage.toTlsBytes())
        val content =
            """{"jsonrpc":"2.0","id":1,"method":"tools/call",""" +
                """"params":{"name":"kp_publish","arguments":{"kp_ref":"$ref","kp_64":"$base64"}}}"""
        val event: Event = bobSigner.sign(EventTemplate(1_757_000_000L, 25910, arrayOf(arrayOf("p", coordinatorKey)), content))
        return bundle to FakeCoordinator.StoredKeyPackage(bob, ref, base64, event)
    }

    @Test
    fun `two Welcomes to one group join it once and do not abort the batch`() =
        runTest {
            val coordinator = FakeCoordinator(alice)
            val first = publication("b1".repeat(16))
            val second = publication("b2".repeat(16))
            coordinator.seedKeyPackage(first.second)
            coordinator.seedKeyPackage(second.second)
            val aliceManager = CordnGroupManager(alice, config, coordinator, InMemoryCordnGroupStore())
            aliceManager.createGroup(gid, CordnGroupMetadata(name = "twice"))
            aliceManager.invite(gid, bob, first.second.keyPackageRef)
            aliceManager.invite(gid, bob, second.second.keyPackageRef)

            val bundles = mapOf(first.second.keyPackageRef to first.first, second.second.keyPackageRef to second.first)
            val bobManager = CordnGroupManager(bob, config, coordinator.viewAs(bob), InMemoryCordnGroupStore())
            val result = bobManager.joinPendingWelcomes({ bundles[it] })

            assertEquals(listOf(gid), result.joined)
            assertEquals(listOf(CordnGroupManager.ALREADY_A_MEMBER), result.skipped.map { it.reason })
            // The newest invitation is the one joined: it reads the stream from where it was issued.
            assertEquals(2L, bobManager.group(gid)!!.epoch)
        }

    /** `welcome_take` that never answers, or answers with a timeout of its own. */
    private class Hanging(
        val timesOut: Boolean,
    ) : ICoordinator by FakeCoordinator("aa".repeat(32)) {
        override suspend fun takeWelcomes(consumed: List<ConsumedWelcomeRef>): List<PendingWelcome> = if (timesOut) withTimeout(10) { awaitCancellation() } else awaitCancellation()
    }

    @Test
    fun `cancelling a call is not a coordinator failure, a timeout is`() =
        runTest {
            val cancelled = CordnGroupManager(alice, config, Hanging(timesOut = false), InMemoryCordnGroupStore())
            val job = launch { cancelled.pendingWelcomes({ null }) }
            runCurrent()
            job.cancel()
            runCurrent()
            assertEquals(0, cancelled.health.state.value.consecutiveFailures, "our own cancellation was counted against the coordinator")

            val timedOut = CordnGroupManager(alice, config, Hanging(timesOut = true), InMemoryCordnGroupStore())
            runCatching { timedOut.pendingWelcomes({ null }) }
            assertEquals(1, timedOut.health.state.value.consecutiveFailures)
        }
}
