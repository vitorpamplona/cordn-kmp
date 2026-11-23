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
import kotlin.math.floor

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
 * An event that fails §3–§4 validation ([isValidEvent]) takes no part: it is not a revision, it
 * supersedes nothing, and §4 says to show an "unsupported event" placeholder for it rather than
 * project it. Two clients that disagreed on validity would show one group different events, so the
 * rules are cordn-web's (`getCalendarEventDefinition`) wherever the spec leaves room; the plan
 * lists the few places they could not be ported exactly.
 *
 * RSVPs (§7, kind 31925) fold onto the event they name: one per account, its newest (`created_at`,
 * then cursor), as cordn-web's `buildCalendarProjection` keeps them. Not here (yet): day coverage,
 * which belongs to a calendar view.
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
        /** Calendar events that fail §3–§4 validation: an "unsupported event", never a card. */
        val invalid: Set<HexKey> = emptySet(),
        /** Latest revision → who answered its RSVP, each list in cursor order (§7). */
        val attendees: Map<HexKey, Attendees> = emptyMap(),
    )

    /** The answers an RSVP may carry (§7). */
    enum class RsvpStatus(
        val wire: String,
    ) {
        ACCEPTED("accepted"),
        TENTATIVE("tentative"),
        DECLINED("declined"),
        ;

        companion object {
            fun of(wire: String?): RsvpStatus? = entries.firstOrNull { it.wire == wire }
        }
    }

    /** One RSVP: the event's coordinate (normalised, as [coordinateOf] writes it) and the answer. */
    data class Rsvp(
        val coordinate: String,
        val eventId: HexKey?,
        val status: RsvpStatus,
    )

    /** An event's current RSVPs, one per account. */
    data class Attendees(
        val going: List<HexKey>,
        val maybe: List<HexKey>,
        val declined: List<HexKey>,
    )

    /** `kind:author:d`, the address of an event (§2). */
    fun coordinate(
        kind: Int,
        author: HexKey,
        d: String,
    ): String = "$kind:$author:$d"

    /** The coordinate of [message] when it is a valid calendar event ([isValidEvent]), else null. */
    fun coordinateOf(message: CordnDeliveredMessage): String? {
        val envelope = message.envelope
        if (!isValidEvent(envelope.kind, envelope.content, envelope.tags)) return null
        val d = envelope.tags.tagValue("d") ?: return null
        return coordinate(envelope.kind, envelope.pubKey, d)
    }

    /**
     * Whether an envelope of [kind] is a calendar event every client accepts (§3–§4): `d` an
     * identifier (§2), a safe non-empty `title`, a `start` (and optional `end`) of the kind's
     * shape, at most one of each scalar tag and exactly two elements in it, `r`, `image` links
     * that are http(s), and no control or bidirectional characters in what is displayed.
     */
    fun isValidEvent(
        kind: Int,
        content: String,
        tags: TagArray,
    ): Boolean {
        if (!CordnMessageKinds.isCalendarEvent(kind)) return false

        val scalars = mutableMapOf<String, String>()
        for (tag in tags) {
            val name = tag.getOrNull(0) ?: continue
            if (name == "r") {
                if (tag.size != 2 || !isHttpUrl(tag[1])) return false
                continue
            }
            if (name !in SCALAR_TAGS) continue
            if (tag.size != 2 || name in scalars) return false
            scalars[name] = tag[1]
        }

        if (!IDENTIFIER.matches(scalars["d"] ?: "")) return false
        if (!isSafeDisplayText(scalars["title"] ?: "")) return false
        val description = content.trim()
        if (description.isNotEmpty() && !isSafeDisplayText(description.replace('\r', ' ').replace('\n', ' '))) return false
        listOf("summary", "location").forEach { name -> scalars[name]?.let { if (it.isNotEmpty() && !isSafeDisplayText(it)) return false } }
        scalars["image"]?.let { if (it.isNotEmpty() && !isHttpUrl(it)) return false }
        val startTzid = scalars["start_tzid"] ?: ""
        if (startTzid.isNotEmpty() && !looksLikeTimeZone(startTzid)) return false
        val endTzid = scalars["end_tzid"] ?: startTzid
        if (endTzid.isNotEmpty() && !looksLikeTimeZone(endTzid)) return false

        val start = scalars["start"] ?: ""
        val end = scalars["end"] ?: ""
        return if (kind == CordnMessageKinds.CALENDAR_DATE) {
            // ISO dates compare as strings, and `end` is exclusive.
            isIsoDate(start) && (end.isEmpty() || (isIsoDate(end) && end > start))
        } else {
            val startSeconds = jsInteger(start) ?: return false
            if (startSeconds <= 0 || startSeconds > MAX_TIMESTAMP) return false
            if (end.isEmpty()) return true
            val endSeconds = jsInteger(end) ?: return false
            endSeconds > startSeconds && endSeconds <= MAX_TIMESTAMP
        }
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
        return parseCoordinate(tags.tagValue("a") ?: return null)
    }

    /**
     * The RSVP [kind]/[tags] carry (§7): an `a` naming a calendar event and a `status` of
     * `accepted`, `tentative` or `declined`. cordn-web's `getCalendarRsvpReference`.
     */
    fun rsvp(
        kind: Int,
        tags: TagArray,
    ): Rsvp? {
        if (kind != CordnMessageKinds.CALENDAR_RSVP) return null
        val coordinate = parseCoordinate(tags.tagValue("a") ?: return null) ?: return null
        val status = RsvpStatus.of(tags.tagValue("status")) ?: return null
        return Rsvp(coordinate, tags.tagValue("e"), status)
    }

    /**
     * Kind 31925 tags answering [event] with [status], as cordn-web's `createCalendarRsvpTags`
     * writes them: the `a` coordinate, the revision's `e`, a `d` of `rsvp-<d>` (one RSVP per
     * account per event), `status`, the author's `p`, and an `alt`. The envelope's `content`
     * should be [rsvpLabel]. [event] must be a valid calendar event ([isValidEvent]).
     */
    fun rsvpTags(
        event: CordnEnvelope,
        status: RsvpStatus,
    ): TagArray {
        require(isValidEvent(event.kind, event.content, event.tags)) { "not a calendar event every client accepts" }
        val d = event.tags.tagValue("d")!!
        return arrayOf(
            arrayOf("a", coordinate(event.kind, event.pubKey, d)),
            arrayOf("e", event.id, "", event.pubKey),
            arrayOf("d", "rsvp-$d"),
            arrayOf("status", status.wire),
            arrayOf("p", event.pubKey),
            arrayOf("alt", "RSVP: ${rsvpLabel(status)}"),
        )
    }

    /** cordn-web's `CALENDAR_RSVP_LABELS`: what an RSVP says to a client that cannot read one. */
    fun rsvpLabel(status: RsvpStatus): String =
        when (status) {
            RsvpStatus.ACCEPTED -> "Going"
            RsvpStatus.TENTATIVE -> "Maybe"
            RsvpStatus.DECLINED -> "Can't go"
        }

    /** A `kind:author:d` naming a calendar event, normalised (lowercase author), or null. */
    private fun parseCoordinate(value: String): String? {
        val parts = value.split(':')
        // A `d` is an identifier (§2), so a coordinate has exactly three parts; an event whose
        // `d` held a ':' was never valid, and so never cancellable either.
        if (parts.size != 3) return null
        val eventKind = parts[0].toIntOrNull() ?: return null
        if (!CordnMessageKinds.isCalendarEvent(eventKind)) return null
        val author = parts[1].lowercase()
        if (author.length != 64 || author.any { it !in HEX }) return null
        if (!IDENTIFIER.matches(parts[2])) return null
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
        val invalid = mutableSetOf<HexKey>()
        // Every valid calendar revision, latest or not.
        val revisions = mutableListOf<HexKey>()
        // coordinate → account → its newest RSVP
        val rsvps = mutableMapOf<String, MutableMap<HexKey, Pair<CordnDeliveredMessage, RsvpStatus>>>()

        messages.forEach { message ->
            val rsvp = rsvp(message.envelope.kind, message.envelope.tags)
            if (rsvp != null) {
                val byAuthor = rsvps.getOrPut(rsvp.coordinate) { mutableMapOf() }
                val sender = message.envelope.pubKey.lowercase()
                val current = byAuthor[sender]
                if (current == null || isNewer(message, current.first)) byAuthor[sender] = message to rsvp.status
                return@forEach
            }

            val cancelled = cancelledCoordinate(message.envelope.kind, message.envelope.tags)
            if (cancelled != null) {
                // Author-only, as §6 says: someone else's cancellation is ignored outright, so it
                // cannot mask the author's by arriving later. cordn-web keeps the newest from
                // anyone and checks its author after; see the plan's divergence note.
                if (cancelled.split(':')[1] != message.envelope.pubKey) return@forEach
                if (message.cursor > (cancellations[cancelled] ?: Long.MIN_VALUE)) cancellations[cancelled] = message.cursor
                return@forEach
            }

            if (!CordnMessageKinds.isCalendarEvent(message.envelope.kind)) return@forEach
            val coordinate = coordinateOf(message)
            if (coordinate == null) {
                invalid += message.envelope.id
                return@forEach
            }
            revisions += message.envelope.id
            revisionCount[coordinate] = (revisionCount[coordinate] ?: 0) + 1
            val current = latest[coordinate]
            if (current == null || isNewer(message, current)) latest[coordinate] = message
        }

        val current = latest.values.mapTo(mutableSetOf()) { it.envelope.id }
        // From the first pass: re-running coordinateOf here validated every event twice.
        val superseded = revisions.filterTo(mutableSetOf()) { it !in current }
        val revised = latest.filterKeys { (revisionCount[it] ?: 0) > 1 }.values.mapTo(mutableSetOf()) { it.envelope.id }
        val cancelled =
            latest
                .filter { (coordinate, revision) -> (cancellations[coordinate] ?: Long.MIN_VALUE) > revision.cursor }
                .values
                .mapTo(mutableSetOf()) { it.envelope.id }

        val attendees =
            latest.entries.associate { (coordinate, revision) ->
                val answers = rsvps[coordinate]?.entries?.sortedBy { it.value.first.cursor } ?: emptyList()

                fun with(status: RsvpStatus) = answers.filter { it.value.second == status }.map { it.key }
                revision.envelope.id to Attendees(with(RsvpStatus.ACCEPTED), with(RsvpStatus.TENTATIVE), with(RsvpStatus.DECLINED))
            }

        return Fold(superseded, revised, cancelled, invalid, attendees)
    }

    /** §5: `created_at` first, the coordinator cursor as the tie-break. */
    private fun isNewer(
        next: CordnDeliveredMessage,
        current: CordnDeliveredMessage,
    ): Boolean =
        next.envelope.createdAt > current.envelope.createdAt ||
            (next.envelope.createdAt == current.envelope.createdAt && next.cursor > current.cursor)

    private const val HEX = "0123456789abcdef"

    /** §2: what a `d` may be. */
    private val IDENTIFIER = Regex("^[A-Za-z0-9_-]{1,64}$")

    private val ISO_DATE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")

    /** §3: the largest Unix second whose date still has a four-digit ISO year. */
    private const val MAX_TIMESTAMP = 253_402_300_799L

    private val SCALAR_TAGS = setOf("d", "title", "start", "end", "start_tzid", "end_tzid", "location", "image", "summary")

    /** cordn-web's `displayText.ts`: no C0/C1 controls, and no bidirectional overrides (a spoofing surface). */
    private val BIDI = Regex("[\u061c\u200e\u200f\u202a-\u202e\u2066-\u2069]")

    /**
     * cordn-web's `isSafeDisplayText`: trimmed, non-empty, at most [maxBytes] of UTF-8, and free of
     * control and bidirectional override characters. Shared with [CordnPolls], as upstream shares it.
     */
    internal fun isSafeDisplayText(
        value: String,
        maxBytes: Int = Int.MAX_VALUE,
    ): Boolean =
        value.isNotEmpty() &&
            value.trim() == value &&
            (maxBytes == Int.MAX_VALUE || value.encodeToByteArray().size <= maxBytes) &&
            value.none { it.code in 0x00..0x1f || it.code in 0x7f..0x9f } &&
            !BIDI.containsMatchIn(value)

    private fun isIsoDate(value: String): Boolean {
        val match = ISO_DATE.matchEntire(value) ?: return false
        val year = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        val day = match.groupValues[3].toInt()
        if (month !in 1..12 || day < 1) return false
        val leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
        val days = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        return day <= days[month - 1]
    }

    /**
     * An http(s) URL with a host, as `new URL(...)` with an http(s) protocol accepts. Approximate:
     * the WHATWG parser accepts some odd shapes this does not, which only an author crafting one
     * on purpose writes.
     */
    private fun isHttpUrl(value: String): Boolean = HTTP_URL.matches(value)

    private val HTTP_URL = Regex("^[hH][tT][tT][pP][sS]?://[^\\s/?#@]+(@[^\\s/?#]+)?([/?#]\\S*)?$")

    /**
     * An IANA zone name's shape (`Area/Location`). cordn-web asks `Intl` whether the zone exists,
     * which needs a zone database this library does not carry; a well-formed unknown name is
     * accepted here and refused there.
     */
    private fun looksLikeTimeZone(value: String): Boolean = TZID.matches(value)

    private val TZID = Regex("^[A-Za-z][A-Za-z0-9_+-]*(/[A-Za-z0-9_+-]+)*$")

    /**
     * `Number(value)` in JavaScript, when it is an integer: what cordn-web parses `start` and
     * `end` with, so a value it accepts (`" 1700000000"`, `"17e8"`, `"0x10"`) is accepted here too
     * and the two clients agree on which revision is valid.
     */
    internal fun jsInteger(value: String): Long? {
        val trimmed = value.trim()
        val number: Double =
            when {
                trimmed.isEmpty() -> 0.0
                trimmed.startsWith("0x") || trimmed.startsWith("0X") -> trimmed.substring(2).toLongOrNull(16)?.toDouble() ?: return null
                trimmed.startsWith("0o") || trimmed.startsWith("0O") -> trimmed.substring(2).toLongOrNull(8)?.toDouble() ?: return null
                trimmed.startsWith("0b") || trimmed.startsWith("0B") -> trimmed.substring(2).toLongOrNull(2)?.toDouble() ?: return null
                JS_DECIMAL.matches(trimmed) -> trimmed.toDoubleOrNull() ?: return null
                else -> return null
            }
        if (number.isNaN() || number.isInfinite() || number != floor(number)) return null
        if (number > Long.MAX_VALUE.toDouble() || number < Long.MIN_VALUE.toDouble()) return null
        return number.toLong()
    }

    private val JS_DECIMAL = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")

    private fun TagArray.tagValue(name: String): String? = fastFirstOrNull { it.size > 1 && it[0] == name }?.get(1)
}
