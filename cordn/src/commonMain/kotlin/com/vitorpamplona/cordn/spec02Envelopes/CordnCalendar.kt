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
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.fastFirstOrNull

/**
 * The parts of upstream's group calendar (`spec/applications/calendar-events.md`, cordn-web
 * 0.7.0's `calendar.ts`) every client must agree on for the chat stream to look the same: which
 * revision of an event is current, and whether its author cancelled it.
 *
 * NIP-52's kinds carried as cordn envelopes. An event is addressable by `(kind, author, d)`,
 * written `kind:author:d`, where the author is the MLS-authenticated sender. A newer revision
 * replaces the older (§5: envelope `created_at`, then the coordinator cursor), and a kind 5 whose
 * `a` names the coordinate cancels it (§6) when its sender is the author and its cursor is past the
 * latest revision's. A later revision revives it.
 *
 * Not here (yet): the §3–§4 validation of an event's fields, RSVP tallies and day coverage. Those
 * belong to a calendar view, which is upstream's P2; the stream needs only this.
 */
object CordnCalendar {
    /** What the stream needs to know about the group's calendar events. */
    class Fold(
        /** Revisions a newer one replaced. */
        val superseded: Set<HexKey>,
        /** Latest revisions of events that had more than one. */
        val revised: Set<HexKey>,
        /** Latest revisions their author cancelled. */
        val cancelled: Set<HexKey>,
    )

    /** `kind:author:d`, the address of an event (§2). */
    fun coordinate(
        kind: Int,
        author: HexKey,
        d: String,
    ): String = "$kind:$author:$d"

    /** The coordinate of [message] when it is a calendar event with a `d` tag, else null. */
    fun coordinateOf(message: CordnDeliveredMessage): String? {
        val envelope = message.envelope
        if (!CordnMessageKinds.isCalendarEvent(envelope.kind)) return null
        val d = envelope.tags.tagValue("d")?.takeIf { it.isNotEmpty() } ?: return null
        return coordinate(envelope.kind, envelope.pubKey, d)
    }

    /**
     * The coordinate a kind 5 cancels, or null when it is not a calendar cancellation.
     *
     * Normalised (lowercase author) so it compares equal to [coordinateOf]. The author named in
     * it is a claim; [fold] checks it against who actually sent the cancellation.
     */
    fun cancelledCoordinate(
        kind: Int,
        tags: TagArray,
    ): String? {
        if (kind != CordnMessageKinds.DELETION) return null
        val value = tags.tagValue("a") ?: return null
        val parts = value.split(':')
        if (parts.size != 3) return null
        val eventKind = parts[0].toIntOrNull() ?: return null
        if (!CordnMessageKinds.isCalendarEvent(eventKind)) return null
        val author = parts[1].lowercase()
        if (author.length != 64 || author.any { it !in HEX }) return null
        if (parts[2].isEmpty()) return null
        return coordinate(eventKind, author, parts[2])
    }

    /**
     * What a chat row says for a calendar event: its `title`, else its NIP-31 `alt`, else its
     * `content` (§3: the description). Never blank for an event that has any of them, which a
     * row rendering `content` alone was for every cordn-web event.
     */
    fun titleOf(envelope: CordnEnvelope): String =
        envelope.tags.tagValue("title")?.takeIf { it.isNotBlank() }
            ?: envelope.tags.tagValue("alt")?.takeIf { it.isNotBlank() }
            ?: envelope.content

    /** Folds a group's whole stream. Order-independent: ranks come from `created_at` and cursors. */
    fun fold(messages: List<CordnDeliveredMessage>): Fold {
        val latest = mutableMapOf<String, CordnDeliveredMessage>()
        val revisionCount = mutableMapOf<String, Int>()
        val cancellations = mutableMapOf<String, Long>()

        messages.forEach { message ->
            val cancelled = cancelledCoordinate(message.envelope.kind, message.envelope.tags)
            if (cancelled != null) {
                // Author-only: someone else's cancellation is ignored outright,
                // so it cannot mask the author's by arriving later.
                if (cancelled.split(':')[1] != message.envelope.pubKey) return@forEach
                if (message.cursor > (cancellations[cancelled] ?: Long.MIN_VALUE)) cancellations[cancelled] = message.cursor
                return@forEach
            }

            val coordinate = coordinateOf(message) ?: return@forEach
            revisionCount[coordinate] = (revisionCount[coordinate] ?: 0) + 1
            val current = latest[coordinate]
            if (current == null || isNewer(message, current)) latest[coordinate] = message
        }

        val current = latest.values.mapTo(mutableSetOf()) { it.envelope.id }
        val superseded =
            messages
                .filter { message -> coordinateOf(message) != null && message.envelope.id !in current }
                .mapTo(mutableSetOf()) { it.envelope.id }
        val revised = latest.filterKeys { (revisionCount[it] ?: 0) > 1 }.values.mapTo(mutableSetOf()) { it.envelope.id }
        val cancelled =
            latest
                .filter { (coordinate, revision) -> (cancellations[coordinate] ?: Long.MIN_VALUE) > revision.cursor }
                .values
                .mapTo(mutableSetOf()) { it.envelope.id }

        return Fold(superseded, revised, cancelled)
    }

    /** §5: `created_at` first, the coordinator cursor as the tie-break. */
    private fun isNewer(
        next: CordnDeliveredMessage,
        current: CordnDeliveredMessage,
    ): Boolean =
        next.envelope.createdAt > current.envelope.createdAt ||
            (next.envelope.createdAt == current.envelope.createdAt && next.cursor > current.cursor)

    private const val HEX = "0123456789abcdef"

    private fun TagArray.tagValue(name: String): String? = fastFirstOrNull { it.size > 1 && it[0] == name }?.get(1)
}
