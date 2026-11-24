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

import com.vitorpamplona.cordn.spec00Coordinator.GroupMessage
import com.vitorpamplona.cordn.spec00Coordinator.ICoordinator
import com.vitorpamplona.cordn.sync.CordnGroupSync
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A coordinator that pads every answer with groups nobody asked about. */
class CordnUnrequestedGidTest {
    private class Padding(
        val inner: FakeCoordinator,
    ) : ICoordinator by inner {
        private var served = false

        private fun junk(): List<GroupMessage> = List(50) { GroupMessage("invented-$it", 1_000L + it, "AAAA", 0) }

        override suspend fun fetchMessages(cursors: Map<String, Long?>): List<GroupMessage> {
            val real = inner.fetchMessages(cursors)
            if (served) return real
            served = true
            return real + junk()
        }

        override suspend fun subscribeMessages(
            cursors: Map<String, Long?>,
            timeoutMs: Long,
            onMessage: (GroupMessage) -> Unit,
        ) = junk().forEach(onMessage)
    }

    @Test
    fun `messages for groups not asked about are dropped, not given an inbox`() =
        runTest {
            val fake = FakeCoordinator("aa".repeat(32))
            fake.inject("mine", "AAAA")
            val sync = CordnGroupSync(Padding(fake))
            val seen = mutableListOf<String>()

            sync.catchUp(listOf("mine")) { gid, _ -> seen += gid }
            sync.subscribe(listOf("mine"), 1_000) { gid, _ -> seen += gid }
            sync.subscribe(listOf("mine"), 1_000, guard = { _, deliver -> deliver() }) { gid, _ -> seen += gid }

            assertEquals(listOf("mine"), seen)
            assertEquals(setOf("mine"), sync.cursors().keys, "an inbox was created for a gid the coordinator invented")
        }
}
