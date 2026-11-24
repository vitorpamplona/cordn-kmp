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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** [CordnCalendar.rsvpTags], read back by [CordnCalendar.rsvp] and folded by [CordnCalendar.fold]. */
class CordnCalendarRsvpTest {
    private val alice = "aa".repeat(32)
    private val bob = "bb".repeat(32)
    private val carol = "cc".repeat(32)

    private val event =
        CordnEnvelope.build(
            pubKey = alice,
            createdAt = 1_767_225_600L,
            kind = CordnMessageKinds.CALENDAR_TIME,
            tags = arrayOf(arrayOf("d", "standup"), arrayOf("title", "Standup"), arrayOf("start", "1767312000")),
            content = "",
        )

    private fun rsvp(
        who: String,
        status: CordnCalendar.RsvpStatus,
        createdAt: Long,
    ) = CordnEnvelope.build(who, createdAt, CordnMessageKinds.CALENDAR_RSVP, CordnCalendar.rsvpTags(event, status), CordnCalendar.rsvpLabel(status))

    @Test
    fun anRsvpNamesTheEventAndItsAnswer() {
        val tags = CordnCalendar.rsvpTags(event, CordnCalendar.RsvpStatus.TENTATIVE)
        val read = assertNotNull(CordnCalendar.rsvp(CordnMessageKinds.CALENDAR_RSVP, tags))
        assertEquals("31923:$alice:standup", read.coordinate)
        assertEquals(event.id, read.eventId)
        assertEquals(CordnCalendar.RsvpStatus.TENTATIVE, read.status)
        assertEquals(listOf("d", "rsvp-standup"), tags.first { it[0] == "d" }.toList())
        assertEquals(listOf("p", alice), tags.first { it[0] == "p" }.toList())
    }

    @Test
    fun theNewestAnswerPerAccountCounts() {
        val fold =
            CordnCalendar.fold(
                listOf(
                    CordnDeliveredMessage(event, 1),
                    CordnDeliveredMessage(rsvp(bob, CordnCalendar.RsvpStatus.ACCEPTED, 1_767_225_700L), 2),
                    CordnDeliveredMessage(rsvp(carol, CordnCalendar.RsvpStatus.TENTATIVE, 1_767_225_710L), 3),
                    CordnDeliveredMessage(rsvp(bob, CordnCalendar.RsvpStatus.DECLINED, 1_767_225_720L), 4),
                ),
            )
        val attendees = fold.attendees.getValue(event.id)
        assertEquals(emptyList(), attendees.going)
        assertEquals(listOf(carol), attendees.maybe)
        assertEquals(listOf(bob), attendees.declined)
    }

    @Test
    fun anInvalidEventCannotBeAnswered() {
        val broken = event.copy(tags = arrayOf(arrayOf("d", "x"), arrayOf("title", "No start")))
        assertFailsWith<IllegalArgumentException> { CordnCalendar.rsvpTags(broken, CordnCalendar.RsvpStatus.ACCEPTED) }
    }
}
