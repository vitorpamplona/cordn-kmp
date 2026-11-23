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
package com.vitorpamplona.cordn.interop

import com.vitorpamplona.cordn.appEncryptedMedia.CordnMediaTag
import com.vitorpamplona.cordn.client.CordnMentions
import com.vitorpamplona.cordn.spec02Envelopes.CordnAnnotationIndex
import com.vitorpamplona.cordn.spec02Envelopes.CordnCalendar
import com.vitorpamplona.cordn.spec02Envelopes.CordnCallAnnouncement
import com.vitorpamplona.cordn.spec02Envelopes.CordnDeliveredMessage
import com.vitorpamplona.cordn.spec02Envelopes.CordnMessageKinds
import com.vitorpamplona.cordn.spec02Envelopes.CordnMessageReferences
import com.vitorpamplona.cordn.spec02Envelopes.CordnPolls
import com.vitorpamplona.cordn.spec02Envelopes.CordnStreamRows
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * What cordn-kmp decides about every message of a stream, in the vocabulary of
 * `cordn/tools/cordn-vector-gen/app-events.mjs`, which asks cordn-web the same questions.
 *
 * Every field comes from a library API; nothing here decides anything itself, so a difference
 * between this and the vector is a difference between cordn-kmp and cordn-web. A field is left
 * out when it has nothing to say, exactly as the generator leaves it out.
 */
object CordnAppEventView {
    fun of(
        stream: List<CordnDeliveredMessage>,
        viewer: HexKey,
        nowSeconds: Long,
    ): Map<HexKey, JsonObject> {
        val index = CordnAnnotationIndex.of(stream)
        val hiddenRows = CordnStreamRows.hidden(stream)
        val calendar = CordnCalendar.fold(stream)

        return stream.associate { message ->
            val envelope = message.envelope
            val id = envelope.id
            id to
                buildJsonObject {
                    put("row", !index.isHidden(id) && id !in hiddenRows)

                    CordnMessageReferences.thread(envelope.tags)?.let {
                        putJsonObject("reply") {
                            put("root", it.rootId)
                            put("parent", it.parentId)
                        }
                    }

                    if (index.isDeleted(id)) put("deleted", true)
                    index.edits[id]?.let { put("edit", it.envelope.content) }
                    if (index.isPinned(id)) put("pinned", true)

                    val reactions = index.reactionsOf(id)
                    if (reactions.isNotEmpty()) {
                        putJsonObject("reactions") {
                            reactions.keys.sorted().forEach { emoji ->
                                putJsonArray(emoji) { reactions.getValue(emoji).sorted().forEach { add(JsonPrimitive(it)) } }
                            }
                        }
                        val custom = index.customEmojiOf(id)
                        if (custom.isNotEmpty()) {
                            putJsonObject("custom_emoji") {
                                custom.keys.sorted().forEach { emoji ->
                                    val display = custom.getValue(emoji)
                                    putJsonObject(emoji) {
                                        put("name", display.name)
                                        display.url?.let { put("url", it) }
                                        display.media?.let { put("media_url", it.url) }
                                    }
                                }
                            }
                        }
                    }

                    val mentioned = CordnMentions.mentioned(envelope.content)
                    if (mentioned.isNotEmpty()) putJsonArray("mentions") { mentioned.forEach { add(JsonPrimitive(it)) } }

                    CordnMentions.attentionTarget(envelope, viewer)?.let { put("attention", it) }

                    media(message)?.let { put("media", it) }

                    if (envelope.kind == CordnCallAnnouncement.KIND) {
                        when (val display = CordnStreamRows.display(envelope)) {
                            is CordnStreamRows.Display.Call -> if (id !in hiddenRows) put("call", call(display.announcement))
                            else -> put("call", "unsupported")
                        }
                    }

                    if (CordnMessageKinds.isCalendarEvent(envelope.kind)) {
                        if (index.isUnsupportedEvent(id)) {
                            put("calendar", "unsupported")
                        } else if (!index.isHidden(id)) {
                            val attendees = calendar.attendees[id]
                            putJsonObject("calendar") {
                                put("title", envelope.tags.firstOrNull { it.size > 1 && it[0] == "title" }?.get(1))
                                put("revised", index.isRevised(id))
                                put("cancelled", index.isCancelled(id))
                                putJsonArray("going") { attendees?.going?.forEach { add(JsonPrimitive(it)) } }
                                putJsonArray("maybe") { attendees?.maybe?.forEach { add(JsonPrimitive(it)) } }
                                putJsonArray("declined") { attendees?.declined?.forEach { add(JsonPrimitive(it)) } }
                            }
                        }
                    }

                    if (envelope.kind == CordnMessageKinds.POLL) {
                        val tally = CordnPolls.tally(stream, id, viewer, nowSeconds)
                        if (tally == null) put("poll", "unsupported") else put("poll", poll(tally))
                    }
                }
        }
    }

    /** cordn-web shows a message's first `imeta` (`findImetaTag`); an unknown version is unsupported. */
    private fun media(message: CordnDeliveredMessage): JsonElement? {
        val tags = message.envelope.tags
        if (tags.none { it.isNotEmpty() && it[0] == CordnMediaTag.TAG_NAME }) return null
        val attachment = CordnMediaTag.parseAll(tags).firstOrNull() ?: return JsonPrimitive("unsupported")
        return buildJsonObject {
            put("url", attachment.url)
            put("mime", attachment.mimeType)
            put("filename", attachment.filename)
            put("x", attachment.plaintextHash)
            put("n", attachment.nonce)
            attachment.dimensions?.let { put("dim", it) }
            attachment.blurhash?.let { put("blurhash", it) }
            attachment.thumbhash?.let { put("thumbhash", it) }
            attachment.alt?.let { put("alt", it) }
            attachment.durationMs?.let { put("duration_ms", it) }
            attachment.waveform?.let { peaks -> putJsonArray("waveform") { peaks.forEach { add(JsonPrimitive(it)) } } }
        }
    }

    private fun call(announcement: CordnCallAnnouncement): JsonObject =
        buildJsonObject {
            put("id", announcement.callId)
            put("open", announcement.isOpen)
            put("service", announcement.service)
            put("starts", announcement.startsAt)
            announcement.endsAt?.let { put("ends", it) }
            announcement.title?.let { put("title", it) }
            announcement.identitySecret?.let { put("identity_secret", it) }
        }

    private fun poll(tally: CordnPolls.Tally): JsonObject =
        buildJsonObject {
            put("question", tally.definition.question)
            put("type", tally.definition.type.wire)
            tally.definition.endsAt?.let { put("ends_at", it) }
            put("open", tally.isOpen)
            put(
                "options",
                buildJsonArray {
                    tally.options.forEach { option ->
                        addJsonObject {
                            put("id", option.id)
                            put("label", option.label)
                            put("votes", option.votes)
                        }
                    }
                },
            )
            put("participants", tally.participants)
            putJsonArray("selection") { tally.selection.forEach { add(JsonPrimitive(it)) } }
        }

    /** Numbers compared by value: JavaScript writes `1`, Kotlin `1.0`. */
    fun canonical(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.mapValues { canonical(it.value) })
            is JsonArray -> JsonArray(element.map(::canonical))
            is JsonPrimitive -> if (element.isString) element else element.content.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: element
        }
}
