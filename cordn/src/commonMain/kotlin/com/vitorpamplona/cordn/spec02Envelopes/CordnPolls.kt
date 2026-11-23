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

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.fastFirstOrNull

/**
 * NIP-88 polls as cordn-web 0.7.0 sends them (`src/lib/chat/polls.ts`), rule for rule: no spec
 * covers them, so the reference client's rules are the convention, and two clients that disagree
 * on which votes count show one group two results.
 *
 * - **Poll**, kind 1068: the question in `content`; `option` tags `(option, id, label)`, 2 to 10,
 *   ids alphanumeric and unique; `polltype` `singlechoice` (the default) or `multiplechoice`;
 *   `endsAt` unix seconds. At most 32 tags. A known tag of the wrong shape makes it unsupported.
 * - **Vote**, kind 1018: `e` names the poll; one `response` tag per chosen option id. A vote is
 *   the voter's whole selection, never a delta; empty is not an unvote.
 * - **Tally**: one vote per account, its latest in coordinator order. A vote that names an
 *   unknown option, picks more than one in a single-choice poll, or came after the deadline
 *   does not count. Deletions do not retract votes (cordn-web, after NIP-88).
 *
 * One known difference: cordn-web judges the deadline by the coordinator's receive time, which
 * [CordnDeliveredMessage] does not keep; this uses the vote's `created_at`, so a vote whose
 * sender backdated it past a closed poll counts here and not there.
 */
object CordnPolls {
    const val MIN_OPTIONS = 2
    const val MAX_OPTIONS = 10
    const val MAX_QUESTION_BYTES = 1024
    const val MAX_OPTION_BYTES = 256
    const val MAX_TAGS = 32

    enum class Type(
        val wire: String,
    ) {
        SINGLE("singlechoice"),
        MULTIPLE("multiplechoice"),
        ;

        companion object {
            fun of(wire: String): Type? = entries.firstOrNull { it.wire == wire }
        }
    }

    @Immutable
    data class Option(
        val id: String,
        val label: String,
    )

    @Immutable
    data class Definition(
        val question: String,
        val options: List<Option>,
        val type: Type,
        val endsAt: Long?,
    )

    /** A vote: the poll it names and the option ids chosen. */
    @Immutable
    data class Response(
        val pollId: HexKey,
        val optionIds: List<String>,
    )

    @Immutable
    data class OptionResult(
        val id: String,
        val label: String,
        val votes: Int,
    )

    @Immutable
    data class Tally(
        val definition: Definition,
        val options: List<OptionResult>,
        /** Accounts whose vote counts. */
        val participants: Int,
        /** The viewer's current selection, empty when they have not voted. */
        val selection: List<String>,
        val isOpen: Boolean,
    )

    /** The poll [kind]/[content]/[tags] define, or null when it is not one every client accepts. */
    fun definition(
        kind: Int,
        content: String,
        tags: TagArray,
    ): Definition? {
        if (kind != CordnMessageKinds.POLL || tags.size > MAX_TAGS) return null

        val options = mutableListOf<Option>()
        var type: Type? = null
        var endsAt: Long? = null
        for (tag in tags) {
            when (tag.getOrNull(0)) {
                "option" -> {
                    if (tag.size != 3 || !isOptionId(tag[1]) || !CordnCalendar.isSafeDisplayText(tag[2], MAX_OPTION_BYTES)) return null
                    options += Option(tag[1], tag[2])
                }
                "polltype" -> {
                    if (tag.size != 2 || type != null) return null
                    type = Type.of(tag[1]) ?: return null
                }
                "endsAt" -> {
                    if (tag.size != 2 || endsAt != null) return null
                    endsAt = CordnCalendar.jsInteger(tag[1])?.takeIf { it > 0 } ?: return null
                }
            }
        }

        if (options.size !in MIN_OPTIONS..MAX_OPTIONS) return null
        if (options.map { it.id }.toSet().size != options.size) return null
        if (!CordnCalendar.isSafeDisplayText(content, MAX_QUESTION_BYTES)) return null

        return Definition(content, options, type ?: Type.SINGLE, endsAt)
    }

    /** The vote [kind]/[tags] cast, or null when it is not a well-formed one. */
    fun response(
        kind: Int,
        tags: TagArray,
    ): Response? {
        if (kind != CordnMessageKinds.POLL_RESPONSE || tags.size > MAX_TAGS) return null
        val pollId = tags.fastFirstOrNull { it.isNotEmpty() && it[0] == "e" }?.getOrNull(1)?.ifEmpty { null } ?: return null
        val optionIds = tags.filter { it.isNotEmpty() && it[0] == "response" }.map { it.getOrNull(1) ?: "" }
        if (!isSelectionShape(optionIds)) return null
        return Response(pollId, optionIds)
    }

    /**
     * The tally of the poll [pollId] over a group's [messages], as [viewer] sees it at
     * [nowSeconds], or null when the poll is not there or does not parse.
     */
    fun tally(
        messages: Collection<CordnDeliveredMessage>,
        pollId: HexKey,
        viewer: HexKey,
        nowSeconds: Long,
    ): Tally? {
        val poll = messages.firstOrNull { it.envelope.id == pollId } ?: return null
        val definition = definition(poll.envelope.kind, poll.envelope.content, poll.envelope.tags) ?: return null
        val known = definition.options.mapTo(mutableSetOf()) { it.id }

        val effective = mutableMapOf<HexKey, Pair<CordnDeliveredMessage, Response>>()
        messages.forEach { message ->
            val response = response(message.envelope.kind, message.envelope.tags) ?: return@forEach
            if (response.pollId != pollId) return@forEach
            // A vote the coordinator sequenced before the poll cannot be for it.
            if (message.cursor < poll.cursor) return@forEach
            if (definition.endsAt != null && message.envelope.createdAt > definition.endsAt) return@forEach
            if (definition.type == Type.SINGLE && response.optionIds.size != 1) return@forEach
            if (!response.optionIds.all { it in known }) return@forEach

            val voter = message.envelope.pubKey.lowercase()
            val current = effective[voter]
            if (current == null || message.cursor > current.first.cursor) effective[voter] = message to response
        }

        val votes = effective.values.map { it.second.optionIds }
        return Tally(
            definition = definition,
            options = definition.options.map { option -> OptionResult(option.id, option.label, votes.count { option.id in it }) },
            participants = votes.size,
            selection = effective[viewer.lowercase()]?.second?.optionIds ?: emptyList(),
            isOpen = definition.endsAt == null || nowSeconds < definition.endsAt,
        )
    }

    /** Kind 1068 tags for a poll whose options get the ids `0`, `1`, … in order, as cordn-web's do. */
    fun pollTags(
        question: String,
        optionLabels: List<String>,
        type: Type = Type.SINGLE,
        endsAt: Long? = null,
    ): TagArray {
        require(CordnCalendar.isSafeDisplayText(question, MAX_QUESTION_BYTES)) { "invalid poll question" }
        require(optionLabels.size in MIN_OPTIONS..MAX_OPTIONS) { "a poll needs $MIN_OPTIONS to $MAX_OPTIONS options" }
        require(optionLabels.all { CordnCalendar.isSafeDisplayText(it, MAX_OPTION_BYTES) }) { "invalid poll option" }
        return buildList {
            optionLabels.forEachIndexed { index, label -> add(arrayOf("option", index.toString(), label)) }
            add(arrayOf("polltype", type.wire))
            endsAt?.let { add(arrayOf("endsAt", it.toString())) }
            add(arrayOf("alt", "Poll: $question"))
        }.toTypedArray()
    }

    /**
     * Kind 1018 tags for a vote on [poll]: the `e`/`p`/`k` triple a reaction carries, one
     * `response` per option. The envelope's `content` should be [describe]'s fallback text.
     */
    fun voteTags(
        poll: CordnMessageReferences.Target,
        optionIds: List<String>,
    ): TagArray {
        require(isSelectionShape(optionIds)) { "invalid poll selection" }
        return arrayOf(
            arrayOf("e", poll.id, "", poll.pubKey),
            arrayOf("p", poll.pubKey),
            arrayOf("k", poll.kind.toString()),
            *optionIds.map { arrayOf("response", it) }.toTypedArray(),
            arrayOf("alt", "Poll vote"),
        )
    }

    /** "Voted: Pizza", the text a client without polls shows for a vote. */
    fun describe(
        definition: Definition?,
        optionIds: List<String>,
    ): String {
        val labels = optionIds.mapNotNull { id -> definition?.options?.firstOrNull { it.id == id }?.label }
        return if (labels.isEmpty()) "Voted" else "Voted: ${labels.joinToString(", ")}"
    }

    private val OPTION_ID = Regex("^[a-zA-Z0-9]{1,64}$")

    private fun isOptionId(value: String): Boolean = OPTION_ID.matches(value)

    private fun isSelectionShape(optionIds: List<String>): Boolean = optionIds.size in 1..MAX_OPTIONS && optionIds.all(::isOptionId) && optionIds.toSet().size == optionIds.size
}
