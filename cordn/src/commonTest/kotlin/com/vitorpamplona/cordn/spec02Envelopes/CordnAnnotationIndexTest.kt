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

import com.vitorpamplona.cordn.spec02Envelopes.CordnMessageReferences.PinOp
import com.vitorpamplona.cordn.spec02Envelopes.CordnMessageReferences.Target
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The authorization rules for annotations, which is what this index really is.
 *
 * Nothing about "fold reactions onto messages" is interesting. What is
 * interesting is who is allowed to change whose message, and those rules differ
 * per annotation — author-only for edits and deletions, any-member for pins —
 * so each one gets a test that fails loudly if it is loosened.
 */
class CordnAnnotationIndexTest {
    private val alice = "aa".repeat(32)
    private val bob = "bb".repeat(32)

    private var cursor = 0L

    private fun message(
        author: HexKey,
        kind: Int,
        content: String,
        tags: Array<Array<String>> = emptyArray(),
        createdAt: Long = 1_757_000_000L,
    ): CordnDeliveredMessage =
        CordnDeliveredMessage(
            envelope = CordnEnvelope.build(author, createdAt, kind, tags, content),
            cursor = ++cursor,
        )

    private fun CordnDeliveredMessage.asTarget() = Target(envelope.id, envelope.pubKey, envelope.kind, envelope.tags)

    @Test
    fun `reactions gather by emoji and by who sent them`() {
        val note = message(alice, CordnMessageKinds.TEXT, "ship it")
        val index =
            CordnAnnotationIndex.of(
                listOf(
                    note,
                    message(bob, CordnMessageKinds.REACTION, "+", CordnMessageReferences.reactionTags(note.asTarget())),
                    message(alice, CordnMessageKinds.REACTION, "+", CordnMessageReferences.reactionTags(note.asTarget())),
                    message(bob, CordnMessageKinds.REACTION, "🎉", CordnMessageReferences.reactionTags(note.asTarget())),
                ),
            )

        assertEquals(setOf(alice, bob), index.reactions[note.envelope.id]?.get("+"))
        assertEquals(setOf(bob), index.reactions[note.envelope.id]?.get("🎉"))
    }

    @Test
    fun `only the author may edit and the newest edit wins`() {
        val note = message(alice, CordnMessageKinds.TEXT, "original", createdAt = 100)
        val tags = CordnMessageReferences.editTags(note.asTarget())

        val index =
            CordnAnnotationIndex.of(
                listOf(
                    note,
                    message(bob, CordnMessageKinds.EDIT, "bob rewrote this", tags, createdAt = 200),
                    message(alice, CordnMessageKinds.EDIT, "first fix", tags, createdAt = 300),
                    message(alice, CordnMessageKinds.EDIT, "final fix", tags, createdAt = 400),
                ),
            )

        assertEquals("final fix", index.contentOf(note.envelope.id))
        assertTrue(index.isEdited(note.envelope.id))
    }

    @Test
    fun `an edit by someone else changes nothing at all`() {
        // Stated separately because it is the whole point: without the author
        // check, anyone in the group can rewrite anyone's words.
        val note = message(alice, CordnMessageKinds.TEXT, "original")
        val index =
            CordnAnnotationIndex.of(
                listOf(
                    note,
                    message(
                        bob,
                        CordnMessageKinds.EDIT,
                        "bob rewrote this",
                        CordnMessageReferences.editTags(note.asTarget()),
                    ),
                ),
            )

        assertEquals("original", index.contentOf(note.envelope.id))
        assertTrue(!index.isEdited(note.envelope.id))
    }

    @Test
    fun `only the author may delete`() {
        val mine = message(alice, CordnMessageKinds.TEXT, "mine")
        val theirs = message(bob, CordnMessageKinds.TEXT, "theirs")

        val index =
            CordnAnnotationIndex.of(
                listOf(
                    mine,
                    theirs,
                    message(alice, CordnMessageKinds.DELETION, "", CordnMessageReferences.deleteTags(mine.asTarget())),
                    message(alice, CordnMessageKinds.DELETION, "", CordnMessageReferences.deleteTags(theirs.asTarget())),
                ),
            )

        assertTrue(index.isDeleted(mine.envelope.id))
        assertTrue(!index.isDeleted(theirs.envelope.id), "alice must not be able to delete bob's message")
    }

    @Test
    fun `a deletion whose k disagrees with the target is not a deletion`() {
        val note = message(alice, CordnMessageKinds.TEXT, "mine")
        val wrongKind =
            arrayOf(
                arrayOf("e", note.envelope.id, "", alice),
                arrayOf("k", CordnMessageKinds.THREAD_REPLY.toString()),
            )

        val index = CordnAnnotationIndex.of(listOf(note, message(alice, CordnMessageKinds.DELETION, "", wrongKind)))

        assertTrue(!index.isDeleted(note.envelope.id), "a mismatched k is a mismatch, not a typo to forgive")
    }

    @Test
    fun `a deleted message stops accepting edits`() {
        // Order is load-bearing in the fold: deletions are resolved before
        // edits so a late edit cannot resurrect withdrawn text.
        val note = message(alice, CordnMessageKinds.TEXT, "regrettable", createdAt = 100)
        val index =
            CordnAnnotationIndex.of(
                listOf(
                    note,
                    message(
                        alice,
                        CordnMessageKinds.DELETION,
                        "",
                        CordnMessageReferences.deleteTags(note.asTarget()),
                        createdAt = 200,
                    ),
                    message(
                        alice,
                        CordnMessageKinds.EDIT,
                        "back again",
                        CordnMessageReferences.editTags(note.asTarget()),
                        createdAt = 300,
                    ),
                ),
            )

        assertTrue(index.isDeleted(note.envelope.id))
        assertTrue(!index.isEdited(note.envelope.id), "an edit must not undo a deletion")
    }

    @Test
    fun `any member may pin and the last write wins`() {
        val note = message(alice, CordnMessageKinds.TEXT, "important", createdAt = 100)
        val t = note.asTarget()

        val pinnedByBob =
            CordnAnnotationIndex.of(
                listOf(note, message(bob, CordnMessageKinds.PIN, "", CordnMessageReferences.pinTags(t, PinOp.ADD), 200)),
            )
        assertTrue(pinnedByBob.isPinned(note.envelope.id), "pinning is any-member, unlike editing")
        assertEquals(bob, pinnedByBob.pins[note.envelope.id]?.pinnedBy)

        val thenUnpinnedByAlice =
            CordnAnnotationIndex.of(
                listOf(
                    note,
                    message(bob, CordnMessageKinds.PIN, "", CordnMessageReferences.pinTags(t, PinOp.ADD), 200),
                    message(alice, CordnMessageKinds.PIN, "", CordnMessageReferences.pinTags(t, PinOp.REMOVE), 300),
                ),
            )
        assertTrue(!thenUnpinnedByAlice.isPinned(note.envelope.id), "the newest op wins")
    }

    @Test
    fun `same-second pins break the tie on the cursor`() {
        // Two clients acting in the same second must agree on the outcome. The
        // coordinator's cursor is the only total order both can see.
        val note = message(alice, CordnMessageKinds.TEXT, "important", createdAt = 100)
        val t = note.asTarget()

        val index =
            CordnAnnotationIndex.of(
                listOf(
                    note,
                    message(bob, CordnMessageKinds.PIN, "", CordnMessageReferences.pinTags(t, PinOp.ADD), 200),
                    message(alice, CordnMessageKinds.PIN, "", CordnMessageReferences.pinTags(t, PinOp.REMOVE), 200),
                ),
            )

        assertTrue(!index.isPinned(note.envelope.id), "the later cursor decides when the timestamps match")
    }

    @Test
    fun `pinned messages come back newest first`() {
        val first = message(alice, CordnMessageKinds.TEXT, "one", createdAt = 100)
        val second = message(alice, CordnMessageKinds.TEXT, "two", createdAt = 110)

        val index =
            CordnAnnotationIndex.of(
                listOf(
                    first,
                    second,
                    message(alice, CordnMessageKinds.PIN, "", CordnMessageReferences.pinTags(first.asTarget(), PinOp.ADD), 200),
                    message(bob, CordnMessageKinds.PIN, "", CordnMessageReferences.pinTags(second.asTarget(), PinOp.ADD), 300),
                ),
            )

        assertEquals(listOf(second.envelope.id, first.envelope.id), index.pinnedIds())
    }

    @Test
    fun `an annotation pointing at nothing is ignored`() {
        // Real case: catch-up starts after the target, so the annotation
        // arrives without it. Dropping it beats rendering an orphan row.
        val absent = Target("f".repeat(64), alice, CordnMessageKinds.TEXT)
        val index =
            CordnAnnotationIndex.of(
                listOf(
                    message(alice, CordnMessageKinds.EDIT, "x", CordnMessageReferences.editTags(absent)),
                    message(alice, CordnMessageKinds.DELETION, "", CordnMessageReferences.deleteTags(absent)),
                ),
            )

        assertTrue(index.edits.isEmpty())
        assertTrue(index.deleted.isEmpty())
    }

    // ---- reaction removal (cordn-web 0.5.2, `references.ts`) ----------------

    @Test
    fun `deleting your own reaction removes it`() {
        val note = message(alice, CordnMessageKinds.TEXT, "ship it")
        val reaction = message(bob, CordnMessageKinds.REACTION, "+", CordnMessageReferences.reactionTags(note.asTarget()))
        val removal = message(bob, CordnMessageKinds.DELETION, "", CordnMessageReferences.deleteTags(reaction.asTarget()))

        val index = CordnAnnotationIndex.of(listOf(note, reaction, removal))

        assertEquals(null, index.reactions[note.envelope.id]?.get("+"), "a removed reaction must not keep its chip")
        assertTrue(index.isDeleted(reaction.envelope.id))
    }

    @Test
    fun `removing one of two reactions from the same account keeps the chip`() {
        // Two devices of one account each reacted: membership is "at least one
        // live reaction event", so removing one of them keeps the reactor.
        val note = message(alice, CordnMessageKinds.TEXT, "ship it")
        val fromPhone = message(bob, CordnMessageKinds.REACTION, "+", CordnMessageReferences.reactionTags(note.asTarget()))
        val fromLaptop = message(bob, CordnMessageKinds.REACTION, "+", CordnMessageReferences.reactionTags(note.asTarget()), createdAt = 1_757_000_001L)
        val removal = message(bob, CordnMessageKinds.DELETION, "", CordnMessageReferences.deleteTags(fromPhone.asTarget()))

        val index = CordnAnnotationIndex.of(listOf(note, fromPhone, fromLaptop, removal))

        assertEquals(setOf(bob), index.reactions[note.envelope.id]?.get("+"))
    }

    @Test
    fun `nobody can remove somebody else's reaction`() {
        val note = message(alice, CordnMessageKinds.TEXT, "ship it")
        val reaction = message(bob, CordnMessageKinds.REACTION, "+", CordnMessageReferences.reactionTags(note.asTarget()))
        val removal = message(alice, CordnMessageKinds.DELETION, "", CordnMessageReferences.deleteTags(reaction.asTarget()))

        val index = CordnAnnotationIndex.of(listOf(note, reaction, removal))

        assertEquals(setOf(bob), index.reactions[note.envelope.id]?.get("+"))
    }

    // ---- polls and calendar (cordn-web 0.6.0, calendar-events.md) -----------

    @Test
    fun `poll votes and RSVPs are never rows, polls are`() {
        assertTrue(CordnMessageKinds.isStreamHidden(CordnMessageKinds.POLL_RESPONSE))
        assertTrue(CordnMessageKinds.isStreamHidden(CordnMessageKinds.CALENDAR_RSVP))
        assertTrue(CordnMessageKinds.isStreamHidden(CordnMessageKinds.REACTION))
        assertTrue(!CordnMessageKinds.isStreamHidden(CordnMessageKinds.POLL))
        assertTrue(!CordnMessageKinds.isStreamHidden(CordnMessageKinds.CALENDAR_TIME))
        assertTrue(!CordnMessageKinds.isStreamHidden(CordnMessageKinds.TEXT))
    }

    private fun calendarEvent(
        author: HexKey,
        d: String,
        title: String,
        createdAt: Long,
        kind: Int = CordnMessageKinds.CALENDAR_TIME,
    ) = message(
        author,
        kind,
        "",
        arrayOf(arrayOf("d", d), arrayOf("title", title), arrayOf("start", "1757000000"), arrayOf("alt", "Calendar event: $title")),
        createdAt = createdAt,
    )

    /** `calendar-events.md` §6: `a` = coordinate, `e` = latest revision, `k`, `alt`. */
    private fun cancellation(
        sender: HexKey,
        event: CordnDeliveredMessage,
    ) = message(
        sender,
        CordnMessageKinds.DELETION,
        "",
        arrayOf(
            arrayOf("a", "${event.envelope.kind}:${event.envelope.pubKey}:d1"),
            arrayOf("e", event.envelope.id, "", event.envelope.pubKey),
            arrayOf("k", event.envelope.kind.toString()),
            arrayOf("alt", "Calendar event cancelled"),
        ),
    )

    @Test
    fun `only the latest revision of a calendar event is a row`() {
        val first = calendarEvent(alice, "d1", "Standup", createdAt = 100)
        val second = calendarEvent(alice, "d1", "Standup, moved", createdAt = 200)
        val other = calendarEvent(alice, "d2", "Retro", createdAt = 150)

        val index = CordnAnnotationIndex.of(listOf(first, second, other))

        assertTrue(index.isHidden(first.envelope.id), "an earlier revision is suppressed")
        assertTrue(!index.isHidden(second.envelope.id))
        assertTrue(!index.isHidden(other.envelope.id), "another d is another event")
        assertTrue(!index.isDeleted(first.envelope.id), "suppressed, not deleted (§5)")
        assertTrue(index.isRevised(second.envelope.id))
        assertTrue(!index.isRevised(other.envelope.id))
    }

    @Test
    fun `revision order is created_at, then the cursor`() {
        val later = calendarEvent(alice, "d1", "later", createdAt = 300)
        val earlierButAfter = calendarEvent(alice, "d1", "earlier", createdAt = 200)
        val index = CordnAnnotationIndex.of(listOf(later, earlierButAfter))
        assertTrue(!index.isHidden(later.envelope.id))
        assertTrue(index.isHidden(earlierButAfter.envelope.id))

        val a = calendarEvent(alice, "d1", "a", createdAt = 400)
        val b = calendarEvent(alice, "d1", "b", createdAt = 400)
        val tie = CordnAnnotationIndex.of(listOf(a, b))
        assertTrue(!tie.isHidden(b.envelope.id), "same second: the higher cursor wins")
        assertTrue(tie.isHidden(a.envelope.id))
    }

    @Test
    fun `the kind is part of a calendar event's identity, and so is its author`() {
        val timed = calendarEvent(alice, "d1", "timed", createdAt = 100)
        val allDay = calendarEvent(alice, "d1", "all day", createdAt = 200, kind = CordnMessageKinds.CALENDAR_DATE)
        val bobs = calendarEvent(bob, "d1", "bob's", createdAt = 300)

        val index = CordnAnnotationIndex.of(listOf(timed, allDay, bobs))

        assertTrue(listOf(timed, allDay, bobs).none { index.isHidden(it.envelope.id) })
    }

    @Test
    fun `a cancelled event is cancelled, not deleted`() {
        val event = calendarEvent(alice, "d1", "Standup", createdAt = 100)
        val index = CordnAnnotationIndex.of(listOf(event, cancellation(alice, event)))

        assertTrue(index.isCancelled(event.envelope.id))
        assertTrue(!index.isDeleted(event.envelope.id), "§6: the row renders the cancelled card, not 'message deleted'")
        assertTrue(!index.isHidden(event.envelope.id))
    }

    @Test
    fun `only the author can cancel`() {
        val event = calendarEvent(alice, "d1", "Standup", createdAt = 100)
        val index = CordnAnnotationIndex.of(listOf(event, cancellation(bob, event)))

        assertTrue(!index.isCancelled(event.envelope.id))
        assertTrue(!index.isDeleted(event.envelope.id))
    }

    @Test
    fun `a revision after the cancellation revives the event`() {
        val event = calendarEvent(alice, "d1", "Standup", createdAt = 100)
        val cancel = cancellation(alice, event)
        val revived = calendarEvent(alice, "d1", "Standup, back on", createdAt = 200)

        val index = CordnAnnotationIndex.of(listOf(event, cancel, revived))

        assertTrue(!index.isCancelled(revived.envelope.id), "§6: a later revision (higher cursor) revives it")
        assertTrue(index.isHidden(event.envelope.id))
    }

    @Test
    fun `a cancellation cursor older than the latest revision cancels nothing`() {
        val event = calendarEvent(alice, "d1", "Standup", createdAt = 100)
        val cancel = cancellation(alice, event)
        val revision = calendarEvent(alice, "d1", "Standup v2", createdAt = 200)

        val index = CordnAnnotationIndex.of(listOf(event, cancel, revision))

        assertTrue(!index.isCancelled(revision.envelope.id))
        assertTrue(index.isHidden(event.envelope.id))
    }

    @Test
    fun `a calendar row reads its title, then its alt`() {
        assertEquals("Standup", CordnCalendar.titleOf(calendarEvent(alice, "d1", "Standup", createdAt = 1).envelope))
        val untitled = message(alice, CordnMessageKinds.CALENDAR_DATE, "desc", arrayOf(arrayOf("d", "x"), arrayOf("alt", "Calendar event: Trip")))
        assertEquals("Calendar event: Trip", CordnCalendar.titleOf(untitled.envelope))
    }
}
