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
}
