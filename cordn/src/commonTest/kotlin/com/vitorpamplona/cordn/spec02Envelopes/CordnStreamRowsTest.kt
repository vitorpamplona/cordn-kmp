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
package com.vitorpamplona.cordn.spec02Envelopes

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * cordn-web's group calls (`src/lib/chat/calls.ts`, 351a61c and follow-ups) and the other
 * addressable kinds a cordn group can carry. The fixtures are cordn-web's own (`calls.test.ts`):
 * the same call id, secret, service and start time, and tags built the way
 * `createCallAnnouncementTags` builds them.
 */
class CordnStreamRowsTest {
    private val alice = "aa".repeat(32)
    private val bob = "bb".repeat(32)
    private val callId = "11".repeat(32)
    private val identitySecret = "11".repeat(32)
    private val service = "https://hushwire.exe.xyz"
    private val startsAt = 1_762_000_000L

    /** `createCallAnnouncementTags`, field for field. */
    private fun callTags(
        status: String = "open",
        id: String = callId,
        endsAt: Long? = null,
        title: String? = null,
    ): Array<Array<String>> {
        val tags = mutableListOf(arrayOf("d", id), arrayOf("service", service), arrayOf("status", status))
        if (status == "open") tags += arrayOf("identity-secret", identitySecret)
        tags += arrayOf("starts", startsAt.toString())
        if (endsAt != null) tags += arrayOf("ends", endsAt.toString())
        if (title != null) tags += arrayOf("room", title)
        tags += arrayOf("alt", if (status == "closed") "Call ended" else "Call started")
        return tags.toTypedArray()
    }

    private fun message(
        id: String,
        at: Long,
        author: HexKey = alice,
        content: String = "",
        kind: Int = CordnCallAnnouncement.KIND,
        tags: Array<Array<String>> = callTags(),
    ) = CordnDeliveredMessage(CordnEnvelope(id = id.padEnd(64, '0'), pubKey = author, createdAt = at, kind = kind, tags = tags, content = content), at)

    private fun hidden(vararg messages: CordnDeliveredMessage) = CordnStreamRows.hidden(messages.toList()).map { it.trimEnd('0') }.toSet()

    @Test
    fun parsesOpenAndClosedAnnouncementsAndToleratesUnknownTags() {
        val open = assertNotNull(CordnCallAnnouncement.parse(CordnCallAnnouncement.KIND, callTags() + arrayOf(arrayOf("future", "ignored"))))
        assertEquals(callId, open.callId)
        assertEquals(service, open.service)
        assertTrue(open.isOpen)
        assertEquals(startsAt, open.startsAt)
        assertEquals(identitySecret, open.identitySecret)
        assertNull(open.endsAt)
        assertNull(open.title)

        val closed = assertNotNull(CordnCallAnnouncement.parse(CordnCallAnnouncement.KIND, callTags("closed", endsAt = startsAt + 100, title = "Standup")))
        assertTrue(!closed.isOpen)
        assertNull(closed.identitySecret)
        assertEquals(startsAt + 100, closed.endsAt)
        assertEquals("Standup", closed.title)

        // Any status but "open" reads as closed.
        val other = callTags().map { if (it[0] == "status") arrayOf("status", "private") else it }.toTypedArray()
        assertTrue(CordnCallAnnouncement.parse(CordnCallAnnouncement.KIND, other)?.isOpen == false)
    }

    @Test
    fun rejectsMalformedRequiredFields() {
        val tags = callTags()
        for (required in listOf("d", "service", "status", "starts", "identity-secret")) {
            assertNull(CordnCallAnnouncement.parse(CordnCallAnnouncement.KIND, tags.filter { it[0] != required }.toTypedArray()), "without $required")
        }
        assertNull(CordnCallAnnouncement.parse(CordnMessageKinds.TEXT, tags))
        assertNull(CordnCallAnnouncement.parse(CordnCallAnnouncement.KIND, callTags(id = "AB".repeat(32))), "an uppercase call id")
        assertNull(CordnCallAnnouncement.parse(CordnCallAnnouncement.KIND, tags + Array(32) { arrayOf("x", "$it") }), "more than 32 tags")
        assertNull(CordnCallAnnouncement.parse(CordnCallAnnouncement.KIND, callTags().map { if (it[0] == "starts") arrayOf("starts", "9007199254740992") else it }.toTypedArray()), "a start past MAX_SAFE_INTEGER")
        // A room title over 256 UTF-8 bytes is dropped, not the call.
        assertNull(CordnCallAnnouncement.parse(CordnCallAnnouncement.KIND, callTags(title = "é".repeat(129)))?.title)
    }

    @Test
    fun aCallsRevisionsFoldByDAloneAcrossAuthors() {
        // calls.test.ts: "uses greatest created_at and then greatest id across authors".
        val opened = message("b", at = 100)
        val closed = message("a", at = 200, author = bob, tags = callTags("closed", endsAt = 300))
        assertEquals(setOf("b"), hidden(opened, closed))
        assertEquals(setOf("b"), hidden(closed, opened))
    }

    @Test
    fun aCreatedAtTieGoesToTheGreaterId() {
        val a = message("a", at = 200, tags = callTags("closed"))
        val f = message("f", at = 200)
        assertEquals(setOf("a"), hidden(a, f))
        assertEquals(setOf("a"), hidden(f, a))
    }

    @Test
    fun differentCallsStayApart() {
        assertEquals(
            setOf("1"),
            hidden(message("1", at = 100), message("2", at = 110, tags = callTags(id = "22".repeat(32))), message("3", at = 120, tags = callTags("closed"))),
        )
    }

    @Test
    fun aMalformedAnnouncementKeepsItsRowAndSaysItIsUnsupported() {
        val broken = message("2", at = 200, tags = callTags().filter { it[0] != "starts" }.toTypedArray())
        assertEquals(emptySet(), hidden(message("1", at = 100), broken))
        assertIs<CordnStreamRows.Display.UnsupportedCall>(CordnStreamRows.display(broken.envelope))
        assertIs<CordnStreamRows.Display.Call>(CordnStreamRows.display(message("1", at = 100).envelope))
    }

    @Test
    fun otherAddressableKindsFoldByKindAuthorAndDWithTheLowerIdOnATie() {
        val kind = 30023

        fun article(
            id: String,
            at: Long,
            author: HexKey = alice,
            d: String = "x",
        ) = message(id, at, author, content = "text-$id", kind = kind, tags = arrayOf(arrayOf("d", d)))

        assertEquals(setOf("1"), hidden(article("1", at = 100), article("2", at = 200)))
        // Another author's revision of the same d is another address.
        assertEquals(emptySet(), hidden(article("1", at = 100), article("2", at = 200, author = bob)))
        assertEquals(emptySet(), hidden(article("1", at = 100), article("2", at = 200, d = "y")))
        // NIP-01: the newest created_at, then the lower id.
        assertEquals(setOf("b"), hidden(article("b", at = 100), article("a", at = 100)))
    }

    @Test
    fun anUndrawnKindShowsItsAltOrIsNoRow() {
        val withAlt = message("1", at = 100, kind = 1234, tags = arrayOf(arrayOf("alt", "  A thing  ")))
        assertEquals(CordnStreamRows.Display.Alt("A thing"), CordnStreamRows.display(withAlt.envelope))
        val blank = message("2", at = 100, kind = 1234, tags = emptyArray())
        assertEquals(setOf("2"), hidden(withAlt, blank))
        assertEquals(CordnStreamRows.Display.Regular, CordnStreamRows.display(message("3", at = 100, content = "hi", kind = CordnMessageKinds.TEXT, tags = emptyArray()).envelope))
    }
}
