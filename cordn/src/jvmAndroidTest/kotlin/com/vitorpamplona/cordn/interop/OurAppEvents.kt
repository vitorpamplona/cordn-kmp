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

import com.vitorpamplona.cordn.appEncryptedMedia.CordnEncryptedMedia
import com.vitorpamplona.cordn.appEncryptedMedia.CordnMediaTag
import com.vitorpamplona.cordn.spec02Envelopes.CordnCalendar
import com.vitorpamplona.cordn.spec02Envelopes.CordnCustomEmoji
import com.vitorpamplona.cordn.spec02Envelopes.CordnDeliveredMessage
import com.vitorpamplona.cordn.spec02Envelopes.CordnEnvelope
import com.vitorpamplona.cordn.spec02Envelopes.CordnMessageKinds
import com.vitorpamplona.cordn.spec02Envelopes.CordnMessageReferences
import com.vitorpamplona.cordn.spec02Envelopes.CordnMessageReferences.PinOp
import com.vitorpamplona.cordn.spec02Envelopes.CordnMessageReferences.Target
import com.vitorpamplona.cordn.spec02Envelopes.CordnPolls
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub

/**
 * The OURS → THEM half: streams built only with cordn-kmp's own builders, for the same cases
 * cordn-web's vectors cover. `app-events.mjs` hands each one to cordn-web and records what it
 * understood in `ours-read-by-cordn-web.json`; [CordnAppEventVectorTest] checks that our builders
 * still produce exactly those envelopes and that we read them the way cordn-web did.
 *
 * Deterministic: fixed actors, fixed clock, no randomness. A change here changes the vectors, so
 * regenerate them (`app-events.sh`) and look at what cordn-web made of the new output.
 */
object OurAppEvents {
    const val PRODUCER = "cordn-kmp builders (CordnMessageReferences, CordnMediaTag, CordnPolls, CordnCustomEmoji)"

    val ALICE: HexKey = "a11ce".padEnd(64, '0')
    val BOB: HexKey = "b0b".padEnd(64, '0')
    val CAROL: HexKey = "ca201".padEnd(64, '0')
    const val T0 = 1_767_225_600L
    const val NOW = T0 + 3_600

    class Case(
        val name: String,
        val viewer: HexKey,
        val stream: List<CordnDeliveredMessage>,
        /** The coordinator's receive time per cursor, in ms, which cordn-web orders some folds by. */
        val at: Map<Long, Long>,
    )

    private class Stream {
        val messages = mutableListOf<CordnDeliveredMessage>()
        val at = mutableMapOf<Long, Long>()

        fun send(
            pubKey: HexKey,
            outbound: CordnMessageReferences.Outbound,
            createdAt: Long? = null,
            receivedAt: Long? = null,
        ): CordnEnvelope = send(pubKey, outbound.kind, outbound.tags, outbound.content, createdAt, receivedAt)

        /** [receivedAt] is the coordinator's `at` (ms); by default a moment after the cursor's minute. */
        fun send(
            pubKey: HexKey,
            kind: Int,
            tags: Array<Array<String>>,
            content: String,
            createdAt: Long? = null,
            receivedAt: Long? = null,
        ): CordnEnvelope {
            val cursor = messages.size + 1L
            val envelope = CordnEnvelope.build(pubKey, createdAt ?: (T0 + cursor * 60), kind, tags, content)
            val received = receivedAt ?: ((T0 + cursor * 60) * 1000 + 250)
            messages += CordnDeliveredMessage(envelope, cursor, receivedAt = received)
            at[cursor] = received
            return envelope
        }
    }

    private fun case(
        name: String,
        viewer: HexKey,
        build: Stream.() -> Unit,
    ): Case = Stream().apply(build).let { Case(name, viewer, it.messages, it.at) }

    private fun text(content: String) = CordnMessageReferences.outbound(content)

    private fun t(envelope: CordnEnvelope) = Target.of(envelope)

    private val HASH = "5a".repeat(32)
    private val NONCE = "0c".repeat(12)

    private fun media(
        mime: String,
        filename: String,
        hash: String = HASH,
        nonce: String = NONCE,
    ) = CordnEncryptedMedia(ByteArray(0), nonce.hexToByteArray(), hash.hexToByteArray(), mime, filename)

    val cases: List<Case> =
        listOf(
            case("text", BOB) {
                send(ALICE, text("hello, group"))
            },
            case("reply, then a reply to the reply keeps the root", BOB) {
                val root = send(ALICE, text("lunch?"))
                val reply = send(BOB, CordnMessageReferences.outbound("yes", replyTo = t(root)))
                send(CAROL, CordnMessageReferences.outbound("me too", replyTo = t(reply)))
            },
            case("mention: a nostr:npub in content", BOB) {
                send(ALICE, text("thanks nostr:${NPub.create(BOB)}!"))
            },
            case("edit: newest of the author's edits wins", BOB) {
                val original = send(ALICE, text("teh plan"))
                send(ALICE, CordnMessageReferences.outbound("the plan", editTo = t(original)))
                send(ALICE, CordnMessageReferences.outbound("the plan, v2 with nostr:${NPub.create(CAROL)}", editTo = t(original)))
            },
            case("deletion by the author", BOB) {
                val gone = send(ALICE, text("oops"))
                send(BOB, CordnMessageReferences.outbound("👍", reactionTo = t(gone)))
                send(ALICE, CordnMessageReferences.outbound("", deleteTo = t(gone)))
            },
            case("reactions, and one taken back", ALICE) {
                val a = send(ALICE, text("ship it?"))
                send(BOB, CordnMessageReferences.outbound("👍", reactionTo = t(a)))
                send(CAROL, CordnMessageReferences.outbound("+", reactionTo = t(a)))
                val undo = send(BOB, CordnMessageReferences.outbound("🎉", reactionTo = t(a)))
                send(BOB, CordnMessageReferences.outbound("", deleteTo = t(undo)))
            },
            case("custom emoji reaction", BOB) {
                val a = send(ALICE, text("new logo"))
                send(
                    BOB,
                    CordnMessageReferences.outbound(
                        ":catjam:",
                        reactionTo = t(a),
                        extraTags = arrayOf(CordnCustomEmoji.tag("catjam", "https://cdn.example/catjam.gif")),
                    ),
                )
            },
            case("custom emoji reaction: encrypted emoji-media", BOB) {
                val a = send(ALICE, text("new logo"))
                val picture = CordnMediaTag.build(media("image/png", "party.png"), url = "https://blossom.example/$HASH")
                send(
                    BOB,
                    CordnMessageReferences.outbound(":party:", reactionTo = t(a), extraTags = arrayOf(CordnCustomEmoji.mediaTag("party", picture))),
                )
            },
            case("pin and unpin", BOB) {
                val a = send(ALICE, text("rules: be kind"))
                val b = send(ALICE, text("wifi: hunter2"))
                send(BOB, CordnMessageReferences.outbound("", pinTo = t(a)))
                send(CAROL, CordnMessageReferences.outbound("", pinTo = t(b)))
                send(ALICE, CordnMessageReferences.outbound("", pinTo = t(b), pinOp = PinOp.REMOVE))
            },
            case("poll and votes", ALICE) {
                val poll = send(ALICE, CordnMessageKinds.POLL, CordnPolls.pollTags("Where to?", listOf("Pizza", "Sushi")), "Where to?")
                val definition = CordnPolls.definition(poll.kind, poll.content, poll.tags)
                listOf(BOB to "0", CAROL to "1", BOB to "1").forEach { (voter, option) ->
                    send(voter, CordnMessageKinds.POLL_RESPONSE, CordnPolls.voteTags(t(poll), listOf(option)), CordnPolls.describe(definition, listOf(option)))
                }
            },
            case("multiple-choice poll with a deadline", BOB) {
                val poll =
                    send(
                        ALICE,
                        CordnMessageKinds.POLL,
                        CordnPolls.pollTags("Which days?", listOf("Mon", "Tue", "Wed"), CordnPolls.Type.MULTIPLE, endsAt = T0 + 7_200),
                        "Which days?",
                    )
                send(BOB, CordnMessageKinds.POLL_RESPONSE, CordnPolls.voteTags(t(poll), listOf("0", "2")), "Voted: Mon, Wed")
            },
            case("poll: a vote received after the deadline, backdated by its sender", BOB) {
                val poll = send(ALICE, CordnMessageKinds.POLL, CordnPolls.pollTags("Lunch?", listOf("Yes", "No"), endsAt = T0 + 1_800), "Lunch?")
                send(BOB, CordnMessageKinds.POLL_RESPONSE, CordnPolls.voteTags(t(poll), listOf("0")), "Voted: Yes", createdAt = T0 + 1_700, receivedAt = (T0 + 1_700) * 1000)
                send(CAROL, CordnMessageKinds.POLL_RESPONSE, CordnPolls.voteTags(t(poll), listOf("1")), "Voted: No", createdAt = T0 + 1_750, receivedAt = (T0 + 1_801) * 1000)
            },
            case("edit: two edits the coordinator received in the same millisecond", BOB) {
                val original = send(ALICE, text("draft"))
                val at = (T0 + 600) * 1000 + 7
                send(ALICE, CordnMessageReferences.outbound("first fix", editTo = t(original)), receivedAt = at)
                send(ALICE, CordnMessageReferences.outbound("second fix", editTo = t(original)), receivedAt = at)
            },
            case("calendar: RSVPs from our builder", BOB) {
                val event =
                    send(
                        ALICE,
                        CordnMessageKinds.CALENDAR_TIME,
                        arrayOf(arrayOf("d", "standup"), arrayOf("title", "Standup"), arrayOf("start", (T0 + 86_400).toString()), arrayOf("end", (T0 + 88_200).toString())),
                        "",
                    )

                fun rsvp(
                    who: HexKey,
                    status: CordnCalendar.RsvpStatus,
                ) = send(who, CordnMessageKinds.CALENDAR_RSVP, CordnCalendar.rsvpTags(event, status), CordnCalendar.rsvpLabel(status))
                rsvp(BOB, CordnCalendar.RsvpStatus.ACCEPTED)
                rsvp(CAROL, CordnCalendar.RsvpStatus.TENTATIVE)
                // Changed their mind: the newest RSVP per account counts.
                rsvp(BOB, CordnCalendar.RsvpStatus.DECLINED)
            },
            case("media: two attachments in one message", BOB) {
                send(
                    ALICE,
                    CordnMessageReferences.outbound(
                        "two photos",
                        extraTags =
                            arrayOf(1, 2)
                                .map { n ->
                                    val hash = n.toString().repeat(64)
                                    CordnMediaTag.build(media("image/jpeg", "photo-$n.jpg", hash = hash), url = "https://blossom.example/$hash", dimensions = "800x600")
                                }.toTypedArray(),
                    ),
                )
            },
            case("media: an image and a voice note", BOB) {
                send(
                    ALICE,
                    CordnMessageReferences.outbound(
                        "look",
                        extraTags =
                            arrayOf(
                                CordnMediaTag.build(
                                    media("image/jpeg", "sunset.jpg"),
                                    url = "https://blossom.example/$HASH",
                                    dimensions = "1200x800",
                                    blurhash = "LKO2?U%2Tw=w]~RBVZRi};RPxuwH",
                                    thumbhash = "1QcSHQRnh493V4dIh4eXh1h4kJUI",
                                    alt = "a sunset over the bay",
                                ),
                            ),
                    ),
                )
                // The recorder's amplitudes, on MediaRecorder.maxAmplitude's 0..32767 scale.
                send(
                    ALICE,
                    CordnMessageKinds.TEXT,
                    arrayOf(
                        CordnMediaTag.build(
                            media("audio/mp4", "voice.m4a", hash = "7b".repeat(32), nonce = "1d".repeat(12)),
                            url = "https://blossom.example/" + "7b".repeat(32),
                            waveform = listOf(3277f, 16384f, 32767f, 8192f),
                            durationMs = 4200,
                        ),
                    ),
                    "",
                )
            },
        )
}
