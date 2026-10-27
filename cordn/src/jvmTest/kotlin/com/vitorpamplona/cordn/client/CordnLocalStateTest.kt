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

import com.vitorpamplona.cordn.appMultiDevice.CordnMigrationSnapshot
import com.vitorpamplona.cordn.spec02Envelopes.CordnDeliveredMessage
import com.vitorpamplona.cordn.spec02Envelopes.CordnEnvelope
import com.vitorpamplona.cordn.sync.EchoState
import com.vitorpamplona.cordn.sync.GroupCursor
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a backup and a handoff carry, read once and written once, for both. */
class CordnLocalStateTest {
    private val coordinator = "cc".repeat(32)
    private val config = CoordinatorConfig(coordinator, listOf(RelayUrlNormalizer.normalizeOrNull("wss://relay.example.com")!!))

    private fun message(
        cursor: Long,
        content: String = "m$cursor",
    ) = CordnDeliveredMessage(CordnEnvelope.build("aa".repeat(32), 1_757_000_000L + cursor, 9, content = content), cursor)

    /** Records the order calls arrive in, so "messages before the cursor" is checkable. */
    private class RecordingStore(
        val log: MutableList<String>,
    ) : CordnGroupStore by InMemoryCordnGroupStore() {
        private val inner = InMemoryCordnGroupStore()

        override suspend fun saveGroup(
            gid: String,
            state: ByteArray,
        ) = inner.saveGroup(gid, state).also { log += "group" }

        override suspend fun loadGroup(gid: String) = inner.loadGroup(gid)

        override suspend fun listGroups() = inner.listGroups()

        override suspend fun appendMessage(
            gid: String,
            message: CordnDeliveredMessage,
        ) = inner.appendMessage(gid, message).also { log += "message" }

        override suspend fun loadMessages(gid: String) = inner.loadMessages(gid)

        override suspend fun saveCursor(
            gid: String,
            cursor: GroupCursor,
        ) = inner.saveCursor(gid, cursor).also { log += "cursor" }

        override suspend fun loadCursor(gid: String) = inner.loadCursor(gid)
    }

    private suspend fun seeded(): Map<HexKey, CordnLocalState.Stores> {
        val groups = InMemoryCordnGroupStore()
        val keyPackages = InMemoryCordnKeyPackageStore()
        groups.saveGroup("g1", byteArrayOf(1, 2, 3))
        groups.appendMessage("g1", message(1))
        groups.appendMessage("g1", message(2))
        groups.saveCursor("g1", GroupCursor(fetchCursor = 2, lastCursor = 2))
        groups.saveRoomState("g1", CordnRoomState(draft = "half a thought", lastReadCursor = 1))
        groups.saveEchoState("g1", EchoState(ownMessageCursors = listOf(2)))
        groups.saveJoinedViaRequest("g1")
        keyPackages.save("kp1", byteArrayOf(9, 9))
        return mapOf(coordinator to CordnLocalState.Stores(groups, keyPackages))
    }

    @Test
    fun `what is read is what is written back`() =
        runTest {
            val source = seeded()
            val state = CordnLocalState.read(listOf(coordinator)) { source.getValue(it) }

            val target = CordnLocalState.Stores(InMemoryCordnGroupStore(), InMemoryCordnKeyPackageStore())
            CordnLocalState.write(state) { target }

            assertContentEquals(byteArrayOf(1, 2, 3), target.groups.loadGroup("g1"))
            assertEquals(listOf(message(1), message(2)), target.groups.loadMessages("g1"))
            assertEquals(GroupCursor(2, 2), target.groups.loadCursor("g1"))
            assertEquals("half a thought", target.groups.loadRoomState("g1").draft)
            assertEquals(listOf(2L), target.groups.loadEchoState("g1").ownMessageCursors)
            assertTrue(target.groups.loadJoinedViaRequest("g1"))
            assertContentEquals(byteArrayOf(9, 9), target.keyPackages.load("kp1"))
        }

    @Test
    fun `the conversation is written before the cursor`() =
        runTest {
            val state = CordnLocalState.read(listOf(coordinator)) { seeded().getValue(it) }
            val log = mutableListOf<String>()
            val target = CordnLocalState.Stores(RecordingStore(log), InMemoryCordnKeyPackageStore())

            CordnLocalState.write(state) { target }

            assertEquals(listOf("group", "message", "message", "cursor"), log)
        }

    @Test
    fun `a handoff snapshot round-trips through the local state`() =
        runTest {
            val state = CordnLocalState.read(listOf(coordinator)) { seeded().getValue(it) }

            val snapshot = CordnMigrationSnapshot.of("ab".repeat(32), listOf(config), state)
            val back = snapshot.toLocalState().groups.single()

            assertEquals(listOf("wss://relay.example.com/"), snapshot.groups.single().coordinatorRelays)
            assertContentEquals(byteArrayOf(1, 2, 3), back.state)
            assertEquals(GroupCursor(2, 2), back.cursor)
            assertEquals(listOf(message(1), message(2)), back.messages)
            assertEquals("half a thought", back.roomState?.draft)
            assertEquals(listOf(config.pubKey), snapshot.coordinatorConfigs().map { it.pubKey })
        }

    @Test
    fun `a handoff carries the newest conversation that fits its budget`() =
        runTest {
            val big = "x".repeat(200 * 1024)
            val group =
                CordnLocalState.Group(coordinator, "g1", byteArrayOf(1), GroupCursor(3, 3), null, null, false, listOf(message(1, big), message(2, big), message(3, big)))

            val carried =
                CordnMigrationSnapshot
                    .of("ab".repeat(32), listOf(config), CordnLocalState(listOf(group), emptyList()))
                    .toLocalState()
                    .groups
                    .single()
                    .messages

            assertEquals(listOf(2L, 3L), carried.map { it.cursor }, "the oldest is what gets left behind")
        }

    @Test
    fun `a backup round-trips the parts it carries`() =
        runTest {
            val state = CordnLocalState.read(listOf(coordinator)) { seeded().getValue(it) }

            val back =
                CordnBackup.Archive
                    .of("ab".repeat(32), listOf(config), state)
                    .toLocalState()
                    .groups
                    .single()

            assertEquals(listOf(message(1), message(2)), back.messages)
            assertEquals(GroupCursor(2, 2), back.cursor)
            assertEquals(null, back.roomState, "a backup keeps no room state, so a restore writes none")
        }
}
