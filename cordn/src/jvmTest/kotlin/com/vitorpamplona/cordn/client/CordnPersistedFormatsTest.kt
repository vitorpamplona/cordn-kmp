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

import com.vitorpamplona.cordn.spec02Envelopes.CordnDeliveredMessage
import com.vitorpamplona.cordn.spec02Envelopes.CordnDeliveredMessageCodec
import com.vitorpamplona.cordn.spec02Envelopes.CordnEnvelope
import com.vitorpamplona.cordn.sync.DeferredWelcome
import com.vitorpamplona.cordn.sync.EchoState
import com.vitorpamplona.cordn.sync.PendingEpochOperation
import com.vitorpamplona.mls.codec.TlsReader
import com.vitorpamplona.mls.codec.TlsWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Persisted formats are API: what this version adds must load what the last one wrote, and the
 * last one must still read what this one writes.
 */
class CordnPersistedFormatsTest {
    /** The v1 echo state exactly as the previous release wrote it: no trailing section. */
    private fun previousReleaseEchoState(): ByteArray {
        val writer = TlsWriter()
        writer.putUint16(1)
        val commits = TlsWriter()
        commits.putOpaque2("c2VhbGVk".encodeToByteArray())
        commits.putUint8(1)
        writer.putOpaque2(commits.toByteArray())
        val cursors = TlsWriter()
        cursors.putUint64(7)
        writer.putOpaque2(cursors.toByteArray())
        return writer.toByteArray()
    }

    @Test
    fun `an echo state written by the previous release still loads`() {
        val loaded = EchoStateCodec.decode(previousReleaseEchoState())
        assertEquals(listOf(PendingEpochOperation("c2VhbGVk", localStateApplied = true)), loaded.pendingCommits)
        assertEquals(listOf(7L), loaded.ownMessageCursors)
        assertEquals(0, loaded.unopenable)
    }

    @Test
    fun `a staged commit's welcomes and the unopenable count round-trip`() {
        val state =
            EchoState(
                pendingCommits =
                    listOf(
                        PendingEpochOperation("b25l", localStateApplied = false, welcomes = listOf(DeferredWelcome("aa".repeat(32), "ref", "d2VsY29tZQ=="))),
                        PendingEpochOperation("dHdv", localStateApplied = true),
                    ),
                ownMessageCursors = listOf(3, 4),
                unopenable = 2,
            )
        assertEquals(state, EchoStateCodec.decode(EchoStateCodec.encode(state)))
    }

    @Test
    fun `the previous release reads what this one writes, minus the new parts`() {
        // The old decoder read two vectors after the version and stopped, so
        // the trailing section must come after them and leave them unchanged.
        val bytes = EchoStateCodec.encode(EchoState(ownMessageCursors = listOf(7), unopenable = 3))
        val reader = TlsReader(bytes)
        assertEquals(1, reader.readUint16())
        reader.readOpaque2()
        assertEquals(7L, TlsReader(reader.readOpaque2()).readUint64())
    }

    private val envelope = CordnEnvelope.build("aa".repeat(32), 1_757_000_000L, 9, emptyArray(), "hi")

    @Test
    fun `a stored message keeps its media key`() {
        val key = ByteArray(32) { it.toByte() }
        val decoded = CordnDeliveredMessageCodec.decode(CordnDeliveredMessageCodec.encode(CordnDeliveredMessage(envelope, 5, key)))
        assertContentEquals(key, decoded.mediaKey)
        assertEquals(CordnDeliveredMessage(envelope, 5, key), decoded)
    }

    @Test
    fun `a message stored before media keys were kept loads without one`() {
        val legacy = CordnDeliveredMessageCodec.encode(CordnDeliveredMessage(envelope, 5))
        assertEquals(false, legacy.contains("\"mk\""))
        assertNull(CordnDeliveredMessageCodec.decode(legacy).mediaKey)
    }

    @Test
    fun `a stored message keeps the coordinator's receive time`() {
        val message = CordnDeliveredMessage(envelope, 5, ByteArray(32) { 1 }, receivedAt = 1_757_000_000_250L)
        val entry = CordnDeliveredMessageCodec.encode(message)
        assertEquals(message, CordnDeliveredMessageCodec.decode(entry))
        // Still version 1, so 1.6's decoder, which reads only `v`, `c`, `e` and `mk`, loads it.
        assertTrue(entry.contains("\"v\":1,"), entry)
        assertTrue(entry.contains("\"r\":1757000000250"), entry)
    }

    @Test
    fun `an entry written before the receive time was kept loads without one`() {
        // Exactly what 1.6.1 wrote: no `r`.
        val legacy = """{"v":1,"c":5,"e":${envelope.toJsonObject()}}"""
        val decoded = CordnDeliveredMessageCodec.decode(legacy)
        assertNull(decoded.receivedAt)
        assertEquals(envelope.createdAt, decoded.receivedAtSeconds)
        assertEquals(false, CordnDeliveredMessageCodec.encode(CordnDeliveredMessage(envelope, 5)).contains("\"r\""))
    }

    @Test
    fun `a receive time that is not a number drops only the receive time`() {
        val good = CordnDeliveredMessageCodec.encode(CordnDeliveredMessage(envelope, 5, receivedAt = 42))
        listOf("\"r\":\"42\"", "\"r\":{}", "\"r\":-1", "\"r\":1.5").forEach { bad ->
            val decoded = CordnDeliveredMessageCodec.decode(good.replace("\"r\":42", bad))
            assertNull(decoded.receivedAt, bad)
            assertEquals(envelope, decoded.envelope)
        }
    }

    /** What 1.3.0's decoder returned for [bytes]: the two vectors, nothing after them. */
    private fun previousReleaseDecode(bytes: ByteArray): Pair<List<Pair<String, Boolean>>, List<Long>> {
        val reader = TlsReader(bytes)
        assertEquals(1, reader.readUint16())
        val commits = TlsReader(reader.readOpaque2())
        val pending = mutableListOf<Pair<String, Boolean>>()
        while (commits.hasRemaining) pending += commits.readOpaque2().decodeToString() to (commits.readUint8() == 1)
        val cursors = TlsReader(reader.readOpaque2())
        val own = mutableListOf<Long>()
        while (cursors.hasRemaining) own += cursors.readUint64()
        return pending to own
    }

    @Test
    fun `a record with nothing new is written exactly as the previous release wrote it`() {
        val state = EchoState(listOf(PendingEpochOperation("c2VhbGVk", localStateApplied = true)), listOf(7))
        assertContentEquals(previousReleaseEchoState(), EchoStateCodec.encode(state))
    }

    @Test
    fun `settled commits and where they landed round-trip, and the previous release sees only the outstanding ones`() {
        val state =
            EchoState(
                pendingCommits =
                    listOf(
                        PendingEpochOperation("cGVuZGluZw==", localStateApplied = false, fromEpoch = 4),
                        PendingEpochOperation("bG9zdA==", localStateApplied = false, status = PendingEpochOperation.Status.LOST, fromEpoch = 3, landedAt = 9),
                        PendingEpochOperation(
                            "Y29uZmlybWVk",
                            localStateApplied = false,
                            welcomes = listOf(DeferredWelcome("aa".repeat(32), "ref", "d2VsY29tZQ==")),
                            status = PendingEpochOperation.Status.CONFIRMED,
                            fromEpoch = 2,
                            landedAt = 8,
                        ),
                    ),
                ownMessageCursors = listOf(10),
            )
        val bytes = EchoStateCodec.encode(state)
        assertEquals(state, EchoStateCodec.decode(bytes))
        assertEquals(listOf("cGVuZGluZw==" to false) to listOf(10L), previousReleaseDecode(bytes))
    }

    @Test
    fun `a commit larger than an opaque2 encodes, round-trips, and stores its Welcome once`() {
        val sealed = "A".repeat(70_000)
        val welcome = "W".repeat(10_000)
        val state =
            EchoState(
                pendingCommits =
                    listOf(
                        PendingEpochOperation(
                            sealed,
                            localStateApplied = false,
                            welcomes = List(30) { DeferredWelcome(it.toString(16).padStart(64, '0'), "ref$it", welcome) },
                            fromEpoch = 1,
                        ),
                    ),
                ownMessageCursors = List(9_000) { it.toLong() + 1 },
            )
        val bytes = EchoStateCodec.encode(state)
        assertEquals(state, EchoStateCodec.decode(bytes))
        assertTrue(bytes.size < sealed.length + 2 * welcome.length + 9_000 * 8 + 4_000, "the Welcome is stored once, not per invitee: ${bytes.size}")
        // The previous release still reads the rest, without the Commit it could not have held.
        val (pending, own) = previousReleaseDecode(bytes)
        assertTrue(pending.isEmpty())
        assertEquals(65_535 / 8, own.size)
    }

    @Test
    fun `an unreadable trailing section costs only itself`() {
        val writer = TlsWriter()
        writer.putBytes(previousReleaseEchoState())
        writer.putUint32(2)
        writer.putOpaque4(byteArrayOf(0, 0, 0, 9, 1))
        val loaded = EchoStateCodec.decode(writer.toByteArray())
        assertEquals(listOf(PendingEpochOperation("c2VhbGVk", localStateApplied = true)), loaded.pendingCommits)
        assertEquals(listOf(7L), loaded.ownMessageCursors)
    }

    @Test
    fun `a media key that is not a 32-byte hex string drops only the key`() {
        val good = CordnDeliveredMessageCodec.encode(CordnDeliveredMessage(envelope, 5, ByteArray(32) { 1 }))
        val asObject = good.replace(Regex("\"mk\":\"[0-9a-f]+\""), "\"mk\":{\"x\":1}")
        val asArray = good.replace(Regex("\"mk\":\"[0-9a-f]+\""), "\"mk\":[1,2]")
        val short = good.replace(Regex("\"mk\":\"[0-9a-f]+\""), "\"mk\":\"abcd\"")
        listOf(asObject, asArray, short).forEach { entry ->
            val decoded = CordnDeliveredMessageCodec.decode(entry)
            assertNull(decoded.mediaKey, entry)
            assertEquals(5, decoded.cursor)
            assertEquals(envelope, decoded.envelope)
        }
    }
}
