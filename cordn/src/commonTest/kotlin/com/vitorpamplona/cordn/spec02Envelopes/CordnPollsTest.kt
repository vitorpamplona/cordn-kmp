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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * [CordnPolls.tally]'s deadline, judged as cordn-web's `buildPollTally` judges it: by when the
 * coordinator received the vote (`at`, ms), not by the `created_at` its sender wrote.
 */
class CordnPollsTest {
    private val alice = "aa".repeat(32)
    private val bob = "bb".repeat(32)
    private val carol = "cc".repeat(32)

    private val endsAt = 1_767_227_400L

    private val poll =
        CordnEnvelope.build(
            pubKey = alice,
            createdAt = endsAt - 1_800,
            kind = CordnMessageKinds.POLL,
            tags = CordnPolls.pollTags("Lunch?", listOf("Yes", "No"), endsAt = endsAt),
            content = "Lunch?",
        )

    private fun vote(
        voter: HexKey,
        option: String,
        createdAt: Long,
    ) = CordnEnvelope.build(
        pubKey = voter,
        createdAt = createdAt,
        kind = CordnMessageKinds.POLL_RESPONSE,
        tags = CordnPolls.voteTags(CordnMessageReferences.Target.of(poll), listOf(option)),
        content = "Voted",
    )

    @Test
    fun aVoteBackdatedPastTheDeadlineDoesNotCount() {
        val stream =
            listOf(
                CordnDeliveredMessage(poll, 1, receivedAt = (endsAt - 1_800) * 1000),
                // Received in time.
                CordnDeliveredMessage(vote(bob, "0", endsAt - 60), 2, receivedAt = (endsAt - 60) * 1000 + 250),
                // Received after the deadline, though its sender claims it was written before.
                CordnDeliveredMessage(vote(carol, "1", endsAt - 60), 3, receivedAt = (endsAt + 5) * 1000),
            )

        val tally = assertNotNull(CordnPolls.tally(stream, poll.id, alice, nowSeconds = endsAt + 60))
        assertEquals(listOf(1, 0), tally.options.map { it.votes })
        assertEquals(1, tally.participants)
        assertFalse(tally.isOpen)
    }

    @Test
    fun theDeadlineSecondItselfStillCounts() {
        // cordn-web compares whole seconds: floor(at / 1000) > endsAt is late.
        val stream =
            listOf(
                CordnDeliveredMessage(poll, 1, receivedAt = (endsAt - 1_800) * 1000),
                CordnDeliveredMessage(vote(bob, "0", endsAt), 2, receivedAt = endsAt * 1000 + 999),
            )
        assertEquals(1, assertNotNull(CordnPolls.tally(stream, poll.id, alice, nowSeconds = endsAt)).participants)
    }

    @Test
    fun aMessageStoredWithoutItsReceiveTimeFallsBackToCreatedAt() {
        val stream =
            listOf(
                CordnDeliveredMessage(poll, 1),
                CordnDeliveredMessage(vote(bob, "0", endsAt - 60), 2),
                CordnDeliveredMessage(vote(carol, "1", endsAt + 60), 3),
            )
        assertEquals(listOf(1, 0), assertNotNull(CordnPolls.tally(stream, poll.id, alice, nowSeconds = endsAt)).options.map { it.votes })
    }
}
