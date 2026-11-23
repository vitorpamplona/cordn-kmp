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

import com.vitorpamplona.cordn.spec00Coordinator.AvailableKeyPackage
import com.vitorpamplona.cordn.spec00Coordinator.ConsumedJoinRequestRef
import com.vitorpamplona.cordn.spec00Coordinator.ConsumedWelcomeRef
import com.vitorpamplona.cordn.spec00Coordinator.CoordinatorException
import com.vitorpamplona.cordn.spec00Coordinator.GroupMessage
import com.vitorpamplona.cordn.spec00Coordinator.ICoordinator
import com.vitorpamplona.cordn.spec00Coordinator.JoinRequest
import com.vitorpamplona.cordn.spec00Coordinator.PendingWelcome
import com.vitorpamplona.cordn.spec00Coordinator.PostedMessage
import com.vitorpamplona.cordn.spec00Coordinator.PublishedKeyPackage
import com.vitorpamplona.cordn.spec00Coordinator.TakenKeyPackage
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.utils.concurrent.ConcurrentMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.concurrent.Volatile

/**
 * An [ICoordinator] that can be closed: calls in flight are cancelled, later ones refused.
 *
 * `CvmTransport` holds nothing open between calls, so it has nothing to close; what a closed link
 * must still stop is its callers. A subscription long-poll on a link whose coordinator was just
 * reopened elsewhere would otherwise run to its timeout against the old relays and hand its
 * messages to a manager that no longer owns the group.
 *
 * A cancelled call surfaces as [CoordinatorException], not a `CancellationException`: the caller
 * itself was not cancelled, and a sync loop that read it as its own cancellation would stop.
 */
internal class ClosableCoordinator(
    private val inner: ICoordinator,
) : ICoordinator {
    @Volatile
    private var closed = false

    private val inFlight = ConcurrentMap<Job, Unit>()

    fun close() {
        closed = true
        inFlight.snapshot().keys.forEach { it.cancel(CancellationException(CLOSED)) }
    }

    private suspend fun <T> guarded(block: suspend () -> T): T {
        if (closed) throw CoordinatorException(CLOSED)
        return try {
            coroutineScope {
                val job = coroutineContext[Job]!!
                inFlight[job] = Unit
                try {
                    if (closed) throw CoordinatorException(CLOSED)
                    block()
                } finally {
                    inFlight.remove(job)
                }
            }
        } catch (e: CancellationException) {
            if (closed && currentCoroutineContext().isActive) throw CoordinatorException(CLOSED, e)
            throw e
        }
    }

    override suspend fun publishKeyPackage(
        keyPackageRef: String,
        keyPackageBase64: String,
    ): PublishedKeyPackage = guarded { inner.publishKeyPackage(keyPackageRef, keyPackageBase64) }

    override suspend fun removeKeyPackages(keyPackageRefs: List<String>): List<String> = guarded { inner.removeKeyPackages(keyPackageRefs) }

    override suspend fun listKeyPackages(): List<AvailableKeyPackage> = guarded { inner.listKeyPackages() }

    override suspend fun takeKeyPackage(id: String): TakenKeyPackage? = guarded { inner.takeKeyPackage(id) }

    override suspend fun storeWelcome(
        targetPubKey: HexKey,
        keyPackageRef: String,
        welcomeBase64: String,
        after: Long?,
    ): Long = guarded { inner.storeWelcome(targetPubKey, keyPackageRef, welcomeBase64, after) }

    override suspend fun takeWelcomes(consumed: List<ConsumedWelcomeRef>): List<PendingWelcome> = guarded { inner.takeWelcomes(consumed) }

    override suspend fun storeJoinRequest(
        gid: String,
        keyPackageRef: String,
    ): Long = guarded { inner.storeJoinRequest(gid, keyPackageRef) }

    override suspend fun takeJoinRequests(
        gids: List<String>,
        consumed: List<ConsumedJoinRequestRef>,
    ): List<JoinRequest> = guarded { inner.takeJoinRequests(gids, consumed) }

    override suspend fun postMessage(
        gid: String,
        sealedBase64: String,
    ): PostedMessage = guarded { inner.postMessage(gid, sealedBase64) }

    override suspend fun fetchMessages(cursors: Map<String, Long?>): List<GroupMessage> = guarded { inner.fetchMessages(cursors) }

    override suspend fun subscribeMessages(
        cursors: Map<String, Long?>,
        timeoutMs: Long,
        onMessage: (GroupMessage) -> Unit,
    ) = guarded { inner.subscribeMessages(cursors, timeoutMs, onMessage) }

    override val oversizedTransfers: Int get() = inner.oversizedTransfers

    private companion object {
        const val CLOSED = "the link to this coordinator was closed"
    }
}
