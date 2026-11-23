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

/**
 * One delivered message: the envelope, and where the coordinator put it.
 *
 * The cursor is carried only for tie-breaking (see [CordnAnnotationIndex]) and
 * is never an identity — `spec/02.md` §7 and `spec/00.md` §4-5 are both explicit
 * that a cursor is scoped to one coordinator and one group.
 */
data class CordnDeliveredMessage(
    val envelope: CordnEnvelope,
    val cursor: Long,
    /**
     * The encrypted-media key of the epoch that carried this message, or null for a message
     * stored before it was kept.
     *
     * `encrypted-media.md` §7 step 5: an attachment opens under the key of its carrying
     * message's epoch, not the group's current one. Captured at ingest, while that epoch is
     * still held, because MLS forgets an epoch's exporter a few commits later; without it,
     * every attachment older than the last Commit (an add, a rename) failed to open. Kept with
     * the message, so it is encrypted at rest with it.
     */
    val mediaKey: ByteArray? = null,
    /**
     * When the coordinator received it, in Unix milliseconds: the `at` it reported for the
     * message (`spec/00.md` §7, `msg_fetch_many`/`msg_post`), or null for a message stored
     * before it was kept.
     *
     * Unlike the envelope's `created_at`, which is the sender's claim, this is the coordinator's
     * clock, and it rises with the [cursor]. cordn-web judges a poll's deadline by it
     * ([CordnPolls.tally]), so a vote whose sender backdated it past a closed poll does not
     * count.
     */
    val receivedAt: Long? = null,
) {
    /** The 1.6 constructor, kept so code compiled against it still links. */
    @Deprecated("Use the primary constructor.", level = DeprecationLevel.HIDDEN)
    constructor(envelope: CordnEnvelope, cursor: Long, mediaKey: ByteArray?) : this(envelope, cursor, mediaKey, null)

    /** The 1.6 `copy`, kept so code compiled against it still links. */
    @Deprecated("Use copy with receivedAt.", level = DeprecationLevel.HIDDEN)
    fun copy(
        envelope: CordnEnvelope = this.envelope,
        cursor: Long = this.cursor,
        mediaKey: ByteArray? = this.mediaKey,
    ): CordnDeliveredMessage = CordnDeliveredMessage(envelope, cursor, mediaKey, receivedAt)

    /** The coordinator's receive time in Unix seconds when known, else the sender's `created_at`. */
    val receivedAtSeconds: Long get() = receivedAt?.floorDiv(1000L) ?: envelope.createdAt

    // By content: a data class compares a ByteArray by reference.
    override fun equals(other: Any?): Boolean =
        other is CordnDeliveredMessage &&
            envelope == other.envelope &&
            cursor == other.cursor &&
            receivedAt == other.receivedAt &&
            (mediaKey?.contentEquals(other.mediaKey) ?: (other.mediaKey == null))

    override fun hashCode(): Int = 31 * (31 * (31 * envelope.hashCode() + cursor.hashCode()) + (mediaKey?.contentHashCode() ?: 0)) + receivedAt.hashCode()
}

/**
 * Reactions, edits, deletions and pins folded onto the messages they modify.
 *
 * Annotations can target any message in the group, so resolving them per row
 * while rendering is quadratic in a list that is usually virtualised. This
 * folds them once.
 *
 * ## The authorization rules are here, not in the UI
 *
 * They differ per annotation and they are the whole reason this is protocol
 * code rather than view-model code:
 *
 * - **Edits and deletions are author-only.** The sender must be the sender of
 *   the target. Without that check anyone in the group can rewrite anyone's
 *   words, which is worse than no edit feature at all.
 * - **A deletion must name the target's kind.** A `k` tag that disagrees with
 *   the stored message is a mismatch, not a match with a typo.
 * - **Pins are any-member.** Deliberately, and matching the reference client:
 *   pinning is a shared act in a group where `admin_pubkeys` is usually empty
 *   (`spec/01.md` — an empty admin set means egalitarian, permanently).
 * - **Deletion beats edit.** A deleted message stops accepting edits, so a
 *   late edit cannot resurrect text its author already withdrew. That is why
 *   deletions are folded before edits and the order is load-bearing.
 *
 * Everything here is authenticated before it arrives: the envelope's `pubKey`
 * was bound to the MLS sender by [CordnApplicationMessage.open], so "same
 * author" is a real check rather than a claim comparison.
 */
class CordnAnnotationIndex private constructor(
    /** Every message by envelope id, annotations included. */
    val byId: Map<HexKey, CordnDeliveredMessage>,
    /** target id → emoji → the senders who reacted with it. */
    val reactions: Map<HexKey, Map<String, Set<HexKey>>>,
    /** target id → the winning edit. */
    val edits: Map<HexKey, CordnDeliveredMessage>,
    /** Targets whose deletion passed every check. */
    val deleted: Set<HexKey>,
    /** target id → the winning pin op. Only `ADD` entries are actually pinned. */
    val pins: Map<HexKey, PinState>,
    /** Calendar revisions a newer one replaced: suppressed, not deleted (`calendar-events.md` §5). */
    val supersededRevisions: Set<HexKey>,
    /** Latest calendar revisions that replaced an earlier one, for an "edited" marker. */
    val revisedEvents: Set<HexKey>,
    /** Latest calendar revisions their author cancelled (§6): a cancelled card, not a deleted row. */
    val cancelledEvents: Set<HexKey>,
    /** Calendar events that fail §3–§4 validation (§4: an "unsupported event" placeholder). */
    val unsupportedEvents: Set<HexKey> = emptySet(),
    /**
     * target id → reaction → the custom emoji it shows ([CordnCustomEmoji.ofReaction]): the first
     * live reaction that resolves one, or a later one that brings the encrypted picture the first
     * lacked, as cordn-web's annotation index picks it.
     */
    val reactionEmoji: Map<HexKey, Map<String, CordnCustomEmoji>> = emptyMap(),
) {
    /** Who pinned a message and when, kept so a pin list can be ordered. */
    data class PinState(
        val targetId: HexKey,
        val op: CordnMessageReferences.PinOp,
        val pinnedBy: HexKey,
        val pinnedAt: Long,
        val cursor: Long,
    )

    /** The text to show for [id]: its newest accepted edit, or the original. */
    fun contentOf(id: HexKey): String? = edits[id]?.envelope?.content ?: byId[id]?.envelope?.content

    fun isEdited(id: HexKey): Boolean = edits.containsKey(id)

    /**
     * The reactions [id] shows: emoji → who reacted with it. None on a deleted message, whose
     * chips go with its text (cordn-web's row fold).
     */
    fun reactionsOf(id: HexKey): Map<String, Set<HexKey>> = if (isDeleted(id)) emptyMap() else reactions[id].orEmpty()

    /** The custom emoji among [id]'s reactions, by reaction. */
    fun customEmojiOf(id: HexKey): Map<String, CordnCustomEmoji> = if (isDeleted(id)) emptyMap() else reactionEmoji[id].orEmpty()

    fun isDeleted(id: HexKey): Boolean = deleted.contains(id)

    /** Pinned and not deleted: a withdrawn message leaves the pin list with its text (cordn-web's row fold). */
    fun isPinned(id: HexKey): Boolean = pins[id]?.op == CordnMessageReferences.PinOp.ADD && !isDeleted(id)

    /**
     * Whether [id] gets no row of its own: a stream-hidden kind (an annotation, a poll vote, an
     * RSVP; see [CordnMessageKinds.isStreamHidden]) or a calendar revision a newer one replaced.
     */
    fun isHidden(id: HexKey): Boolean = id in supersededRevisions || byId[id]?.let { CordnMessageKinds.isStreamHidden(it.envelope.kind) } == true

    /** Whether [id] is the latest revision of a calendar event that had earlier ones. */
    fun isRevised(id: HexKey): Boolean = id in revisedEvents

    /** Whether [id] is a calendar event its author cancelled. Not [isDeleted]: §6 shows it. */
    fun isCancelled(id: HexKey): Boolean = id in cancelledEvents

    /** Whether [id] is a calendar event no client may project (§4): show "unsupported event". */
    fun isUnsupportedEvent(id: HexKey): Boolean = id in unsupportedEvents

    /** Currently pinned targets that are not deleted, newest pin first (coordinator order). */
    fun pinnedIds(): List<HexKey> =
        pins.values
            .filter { it.op == CordnMessageReferences.PinOp.ADD && !isDeleted(it.targetId) }
            .sortedByDescending { it.cursor }
            .map { it.targetId }

    companion object {
        /**
         * Folds [messages] — a whole group's stream, annotations included.
         *
         * Deletions come first and the order is load-bearing twice: a withdrawn
         * message cannot be edited back into existence, and a deleted reaction
         * (cordn-web's "remove your reaction": a kind 5 naming the kind 7) no
         * longer counts. The rest are independent.
         */
        fun of(messages: List<CordnDeliveredMessage>): CordnAnnotationIndex {
            val byId = messages.associateBy { it.envelope.id }

            val deleted = mutableSetOf<HexKey>()
            messages.forEach { message ->
                val ref = CordnMessageReferences.delete(message.envelope.kind, message.envelope.tags) ?: return@forEach
                val target = byId[ref.targetId] ?: return@forEach
                // A calendar cancellation's `e` names the revision it cancels, and §6 shows a
                // cancelled card for that rather than "message deleted": the calendar fold
                // decides it. Any other `e` it carries is an ordinary deletion, as cordn-web's
                // fold makes it.
                if (CordnCalendar.cancelledCoordinate(message.envelope.kind, message.envelope.tags) != null &&
                    CordnCalendar.coordinateOf(target) != null
                ) {
                    return@forEach
                }
                // A `k` that disagrees with the stored message is a mismatch.
                if (ref.targetKind != target.envelope.kind) return@forEach
                // Author-only. The pubkeys are MLS-authenticated, not claimed.
                if (target.envelope.pubKey != message.envelope.pubKey) return@forEach
                deleted.add(ref.targetId)
            }

            // A set of senders per emoji, built from the reactions still live:
            // an account that reacted from two devices and removed one of them
            // still has the other, so it keeps its place under the chip.
            val reactions = mutableMapOf<HexKey, MutableMap<String, MutableSet<HexKey>>>()
            val reactionEmoji = mutableMapOf<HexKey, MutableMap<String, CordnCustomEmoji>>()
            messages.forEach { message ->
                if (message.envelope.id in deleted) return@forEach
                val ref =
                    CordnMessageReferences.reaction(
                        message.envelope.kind,
                        message.envelope.content,
                        message.envelope.tags,
                    ) ?: return@forEach
                reactions
                    .getOrPut(ref.targetId) { mutableMapOf() }
                    .getOrPut(ref.reaction) { mutableSetOf() }
                    .add(message.envelope.pubKey)

                val custom = CordnCustomEmoji.ofReaction(ref.reaction, message.envelope.tags) ?: return@forEach
                val shown = reactionEmoji.getOrPut(ref.targetId) { mutableMapOf() }
                val current = shown[ref.reaction]
                if (current == null || (custom.media != null && current.media == null)) shown[ref.reaction] = custom
            }

            val edits = mutableMapOf<HexKey, CordnDeliveredMessage>()
            messages.forEach { message ->
                val ref =
                    CordnMessageReferences.edit(
                        message.envelope.kind,
                        message.envelope.content,
                        message.envelope.tags,
                    ) ?: return@forEach
                if (ref.targetId in deleted) return@forEach
                val target = byId[ref.targetId] ?: return@forEach
                if (target.envelope.pubKey != message.envelope.pubKey) return@forEach

                val current = edits[ref.targetId]
                // The latest the coordinator sequenced wins, not the newest `created_at`: that is
                // the sender's clock, and two devices of one account need not agree on it. cordn-web
                // orders edits by the coordinator's receive time, which rises with the cursor
                // (spec/00.md §4); the vectors' "clock is behind" case pins it.
                if (current == null || message.cursor > current.cursor) {
                    edits[ref.targetId] = message
                }
            }

            val pins = mutableMapOf<HexKey, PinState>()
            messages.forEach { message ->
                val ref = CordnMessageReferences.pin(message.envelope.kind, message.envelope.tags) ?: return@forEach
                // No author check: pinning is any-member. See the class KDoc. Last write wins in
                // coordinator order, as cordn-web's (receive time, then cursor) does.
                val current = pins[ref.targetId]
                if (current == null || message.cursor > current.cursor) {
                    pins[ref.targetId] =
                        PinState(
                            targetId = ref.targetId,
                            op = ref.op,
                            pinnedBy = message.envelope.pubKey,
                            pinnedAt = message.envelope.createdAt,
                            cursor = message.cursor,
                        )
                }
            }

            val calendar = CordnCalendar.fold(messages)

            return CordnAnnotationIndex(
                byId = byId,
                reactions = reactions,
                edits = edits,
                deleted = deleted,
                pins = pins,
                supersededRevisions = calendar.superseded,
                revisedEvents = calendar.revised,
                cancelledEvents = calendar.cancelled,
                unsupportedEvents = calendar.invalid,
                reactionEmoji = reactionEmoji,
            )
        }
    }
}
