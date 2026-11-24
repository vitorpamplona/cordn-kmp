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

import com.vitorpamplona.cordn.spec00Coordinator.CoordinatorException
import com.vitorpamplona.cordn.spec00Coordinator.GroupMessage
import com.vitorpamplona.cordn.spec00Coordinator.ICoordinator
import com.vitorpamplona.cordn.spec00Coordinator.PostedMessage
import com.vitorpamplona.cordn.spec01GroupMetadata.CordnGroupMetadata
import com.vitorpamplona.quartz.contextvm.transport.CvmRelayPool
import com.vitorpamplona.quartz.contextvm.transport.CvmSubscription
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What happens to the old manager and link when a coordinator's session is reopened or closed. */
@OptIn(ExperimentalEncodingApi::class, ExperimentalCoroutinesApi::class)
class CordnSessionLifecycleTest {
    private val gid = "2e6b1c0d-4a3f-4b5e-8c7d-9f1e0a2b3c4d"
    private val account = NostrSignerSync(KeyPair()).pubKey
    private val coordinatorKey = NostrSignerSync(KeyPair()).pubKey

    private val config =
        CoordinatorConfig(
            pubKey = coordinatorKey,
            relays = listOf(RelayUrlNormalizer.normalizeOrNull("wss://relay.example.com")!!),
            origin = CoordinatorConfig.Origin.DEFAULT,
        )

    /** A live subscription: delivers what is there, then holds the stream open. */
    private class LiveCoordinator(
        val inner: FakeCoordinator,
    ) : ICoordinator by inner {
        override suspend fun subscribeMessages(
            cursors: Map<String, Long?>,
            timeoutMs: Long,
            onMessage: (GroupMessage) -> Unit,
        ) {
            inner.fetchMessages(cursors).forEach(onMessage)
            awaitCancellation()
        }
    }

    @Test
    fun `reopening a coordinator quiesces the old manager before the new one restores`() =
        runTest {
            val fake = FakeCoordinator(callerPubKey = account)
            val live = LiveCoordinator(fake)
            val store = InMemoryCordnGroupStore()
            val keys = InMemoryCordnKeyPackageStore()
            val factory =
                CordnCoordinatorScopeFactory { _, _ ->
                    object : CordnCoordinatorScope {
                        override val coordinator: ICoordinator = live
                        override val groupStore: CordnGroupStore = store
                        override val keyPackageStore: CordnKeyPackageStore = keys

                        override suspend fun close() {}
                    }
                }
            val registry = CordnCoordinatorRegistry(account, factory)
            val old = registry.session(config).manager
            old.createGroup(gid, CordnGroupMetadata(name = "kept"))

            // Something arrives over the old manager's subscription and is held in memory only:
            // a subscription saves on its way out.
            fake.inject(gid, Base64.encode(ByteArray(64) { 7 }))
            val subscription = launch { old.subscribe(60_000) { } }
            runCurrent()
            val ingestedTo = fake.posted(gid).size.toLong()

            // The relays are corrected: a new session, a new manager over the same store.
            val moved = config.copy(relays = listOf(RelayUrlNormalizer.normalizeOrNull("wss://other.example.com")!!))
            val fresh = registry.session(moved).manager
            assertEquals(ingestedTo, store.loadCursor(gid)?.fetchCursor, "the new manager restored a cursor the old one had not saved")

            fresh.send(gid, "from the new manager")
            val afterSend = assertNotNull(store.loadGroup(gid))

            // The old subscription ends now; its NonCancellable save must not roll the group back.
            subscription.cancel()
            runCurrent()
            assertContentEquals(afterSend, store.loadGroup(gid), "the old manager wrote over the new one's state")

            assertFailsWith<CordnGroupException> { old.send(gid, "stale") }
            registry.close()
        }

    /** Records the pubkey each request listens on, and never answers. */
    private class SilentPool : CvmRelayPool {
        val listeners = mutableListOf<HexKey>()

        override fun subscribe(
            pubKey: HexKey,
            kinds: IntArray,
            onEvent: (Event) -> Unit,
        ): CvmSubscription {
            listeners += pubKey
            return object : CvmSubscription {
                override fun close() {}
            }
        }

        override suspend fun publish(event: Event) {}
    }

    @Test
    fun `each coordinator gets its own throwaway key`() =
        runTest {
            val pool = SilentPool()
            val links = CordnLinks.over(NostrSignerInternal(KeyPair())) { pool }
            val other = config.copy(pubKey = NostrSignerSync(KeyPair()).pubKey)

            val first = links.connect(account, config)
            val second = links.connect(account, other)
            val calls =
                listOf(first, second).map { link ->
                    launch { runCatching { link.coordinator.fetchMessages(mapOf(gid to null)) } }
                }
            runCurrent()
            assertEquals(2, pool.listeners.size)
            assertNotEquals(pool.listeners[0], pool.listeners[1], "two coordinators saw the same ephemeral pubkey")

            first.close()
            second.close()
            runCurrent()
            calls.forEach { assertTrue(it.isCompleted, "closing the link left a call running") }
        }

    @Test
    fun `closing a link ends the calls in flight and refuses new ones`() =
        runTest {
            val pool = SilentPool()
            val link = CordnLinks.over(NostrSignerInternal(KeyPair())) { pool }.connect(account, config)
            var failure: Throwable? = null
            val call = launch { failure = runCatching { link.coordinator.fetchMessages(mapOf(gid to null)) }.exceptionOrNull() }
            runCurrent()
            assertTrue(call.isActive)

            link.close()
            runCurrent()
            assertTrue(call.isCompleted)
            assertIs<CoordinatorException>(failure, "a closed link's call must fail as a coordinator failure, not cancel its caller")
            assertFailsWith<CoordinatorException> { link.coordinator.fetchMessages(mapOf(gid to null)) }
        }

    /** A coordinator whose posts hang until its transport closes, then fail as a closed link's do. */
    private class HangingCoordinator(
        val inner: FakeCoordinator,
    ) : ICoordinator by inner {
        val closed = CompletableDeferred<Unit>()
        var hangPosts = false

        override suspend fun postMessage(
            gid: String,
            sealedBase64: String,
        ): PostedMessage {
            if (hangPosts) {
                closed.await()
                throw CoordinatorException("the link was closed")
            }
            return inner.postMessage(gid, sealedBase64)
        }
    }

    @Test
    fun `forgetting a coordinator does not wait on a call only its transport close ends`() =
        runTest {
            val hanging = HangingCoordinator(FakeCoordinator(callerPubKey = account))
            val store = InMemoryCordnGroupStore()
            val factory =
                CordnCoordinatorScopeFactory { _, _ ->
                    object : CordnCoordinatorScope {
                        override val coordinator: ICoordinator = hanging
                        override val groupStore: CordnGroupStore = store
                        override val keyPackageStore: CordnKeyPackageStore = InMemoryCordnKeyPackageStore()

                        override suspend fun close() {
                            hanging.closed.complete(Unit)
                        }
                    }
                }
            val registry = CordnCoordinatorRegistry(account, factory)
            val manager = registry.session(config).manager
            manager.createGroup(gid, CordnGroupMetadata(name = "kept"))

            // A send holds the group's lock across its post, which never answers.
            hanging.hangPosts = true
            var sendFailure: Throwable? = null
            val send = launch { sendFailure = runCatching { manager.send(gid, "stuck") }.exceptionOrNull() }
            runCurrent()
            assertTrue(send.isActive)

            val forget = launch { registry.forget(coordinatorKey) }
            runCurrent()
            assertTrue(forget.isCompleted, "forget waited on a group lock held across a call only the transport close ends")
            assertTrue(send.isCompleted)
            assertIs<CoordinatorException>(sendFailure)
            assertNotNull(store.loadGroup(gid), "the group was saved on the way out")
            assertFailsWith<CordnGroupException> { manager.send(gid, "after") }
        }
}
