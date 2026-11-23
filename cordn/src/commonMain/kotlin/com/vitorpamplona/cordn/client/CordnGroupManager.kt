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
package com.vitorpamplona.cordn.client

import com.vitorpamplona.cordn.appEncryptedMedia.CordnMediaEncryption
import com.vitorpamplona.cordn.appGroupRef.CordnGroupRef
import com.vitorpamplona.cordn.groups.CordnCredential
import com.vitorpamplona.cordn.groups.CordnGroupPolicy
import com.vitorpamplona.cordn.spec00Coordinator.ConsumedJoinRequestRef
import com.vitorpamplona.cordn.spec00Coordinator.ConsumedWelcomeRef
import com.vitorpamplona.cordn.spec00Coordinator.ICoordinator
import com.vitorpamplona.cordn.spec00Coordinator.JoinRequest
import com.vitorpamplona.cordn.spec00Coordinator.KeyPackagePublication
import com.vitorpamplona.cordn.spec00Coordinator.TakenKeyPackage
import com.vitorpamplona.cordn.spec01GroupMetadata.CordnGroupMetadata
import com.vitorpamplona.cordn.spec02Envelopes.CordnApplicationMessage
import com.vitorpamplona.cordn.spec02Envelopes.CordnDeliveredMessage
import com.vitorpamplona.cordn.spec02Envelopes.CordnEnvelope
import com.vitorpamplona.cordn.spec02Envelopes.CordnMessageReferences
import com.vitorpamplona.cordn.spec02Envelopes.ReceivedMessage
import com.vitorpamplona.cordn.spec03Payloads.SealedPayload
import com.vitorpamplona.cordn.sync.CordnGroupSync
import com.vitorpamplona.cordn.sync.DeferredWelcome
import com.vitorpamplona.cordn.sync.EchoState
import com.vitorpamplona.cordn.sync.GroupCursor
import com.vitorpamplona.cordn.sync.Ingestion
import com.vitorpamplona.cordn.sync.PendingEpochOperation
import com.vitorpamplona.mls.codec.TlsReader
import com.vitorpamplona.mls.framing.ContentType
import com.vitorpamplona.mls.framing.MlsMessage
import com.vitorpamplona.mls.framing.PrivateMessage
import com.vitorpamplona.mls.framing.WireFormat
import com.vitorpamplona.mls.group.MlsGroup
import com.vitorpamplona.mls.group.MlsGroupState
import com.vitorpamplona.mls.group.StagedCommitState
import com.vitorpamplona.mls.messages.CommitResult
import com.vitorpamplona.mls.messages.KeyPackageBundle
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.utils.Log
import com.vitorpamplona.quartz.utils.TimeUtils
import com.vitorpamplona.quartz.utils.concurrent.ConcurrentMap
import com.vitorpamplona.quartz.utils.concurrent.PlatformLock
import com.vitorpamplona.quartz.utils.concurrent.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The cordn groups this account holds on one coordinator.
 *
 * The cordn counterpart of `MarmotManager`, and deliberately **not** a reuse of
 * it. Marmot's manager is keyed on the Nostr group id throughout and its whole
 * delivery model — publish kinds 443/444/445 to relays, subscribe by `h` tag,
 * carry publish obligations — has no cordn analogue: here there is one
 * coordinator, one ordered stream per `gid`, and a cursor. What the two share
 * is the RFC 9420 engine underneath, which is what Stage 1 of the interop plan
 * made possible.
 *
 * ## One manager per coordinator
 *
 * Not per account. A `gid` is scoped to the coordinator that issued it
 * (`spec/00.md` §4-5), so the same string can name two unrelated groups on two
 * coordinators, and a cursor from one is meaningless to the other. Keying
 * groups by `gid` alone is only safe because the coordinator is fixed here.
 *
 * It also matches the privacy model: §8.2's ephemeral identity is per session
 * per coordinator, so the set of groups one manager touches is exactly the set
 * that coordinator can link together. [exposure] reports that from the real
 * count rather than from a guess.
 *
 * ## One thing at a time per group
 *
 * Every operation on a group runs under that group's lock: a Commit from the catch-up that
 * precedes it to the Welcomes that follow it, a send from its seal to its record, each message a
 * sync ingests, each save. MLS state is a sequence, and two of them interleaving is how a second
 * Commit's catch-up discarded a first one still in flight (forking the group) and how a save could
 * read the state halfway through an ingest. Different groups do not wait for each other.
 * cordn-web does the same (`runGroupOperation`, a promise chain per group).
 */
@OptIn(ExperimentalEncodingApi::class)
class CordnGroupManager(
    /** The account these groups belong to, as lowercase hex. */
    val accountPubKey: HexKey,
    val config: CoordinatorConfig,
    private val coordinator: ICoordinator,
    private val store: CordnGroupStore,
    val health: CoordinatorHealth = CoordinatorHealth(),
    /** Seconds. Injected so tests are not at the mercy of the wall clock. */
    private val clock: () -> Long = { TimeUtils.now() },
) : CordnSyncSource {
    /** Each group this manager holds, with what is cached and saved of it. */
    private val groups = ConcurrentMap<String, Slot>()

    /** One lock per group. Never removed, so two holders can never hold two different locks for one gid. */
    private val groupLocks = ConcurrentMap<String, Mutex>()
    private val sync = CordnGroupSync(coordinator)

    private val _gids = MutableStateFlow<Set<String>>(emptySet())

    /** The groups this manager holds, for a UI to observe. */
    override val gids: StateFlow<Set<String>> = _gids.asStateFlow()

    @Volatile
    private var publishedKeyPackage = false

    /** Set by [close]: every group operation refuses from then on. */
    @Volatile
    private var closed = false

    /** Guards [pendingRetirements] and [pendingRequestRetirements], which every group shares. */
    private val retirementLock = PlatformLock()

    /** Acknowledgements a failed [retire] left to ride the next `welcome_take`. */
    private val pendingRetirements = mutableListOf<ConsumedWelcomeRef>()

    /** As [pendingRetirements], for join requests. */
    private val pendingRequestRetirements = mutableListOf<ConsumedJoinRequestRef>()

    /** What happened to one delivered payload. */
    sealed interface Delivery {
        /**
         * The group it belongs to.
         *
         * On the interface because every outcome has one and callers route on
         * it — a delivery that cannot be filed under a group is not something
         * this type can represent.
         */
        val gid: String

        /** An application message that passed every §5 check. */
        data class Message(
            override val gid: String,
            val cursor: Long,
            val received: ReceivedMessage,
            /**
             * The media key of the epoch that carried it ([ReceivedMessage.epoch]), which is not
             * always ours: a sender one Commit behind seals under the epoch we left. See
             * [CordnDeliveredMessage.mediaKey].
             */
            val mediaKey: ByteArray? = null,
            /** When the coordinator received it, Unix ms. See [CordnDeliveredMessage.receivedAt]. */
            val receivedAt: Long? = null,
        ) : Delivery {
            /** The 1.6 constructor, kept so code compiled against it still links. */
            @Deprecated("Use the primary constructor.", level = DeprecationLevel.HIDDEN)
            constructor(gid: String, cursor: Long, received: ReceivedMessage, mediaKey: ByteArray?) : this(gid, cursor, received, mediaKey, null)

            /** The message as the store and a room hold it. */
            val delivered: CordnDeliveredMessage get() = CordnDeliveredMessage(received.envelope, cursor, mediaKey, receivedAt)

            // By content: a data class compares a ByteArray by reference.
            override fun equals(other: Any?): Boolean =
                other is Message &&
                    gid == other.gid &&
                    cursor == other.cursor &&
                    received == other.received &&
                    receivedAt == other.receivedAt &&
                    (mediaKey?.contentEquals(other.mediaKey) ?: (other.mediaKey == null))

            override fun hashCode(): Int = 31 * (31 * (31 * (31 * gid.hashCode() + cursor.hashCode()) + received.hashCode()) + (mediaKey?.contentHashCode() ?: 0)) + receivedAt.hashCode()
        }

        /** A handshake message; the group has advanced to [epoch]. */
        data class EpochAdvanced(
            override val gid: String,
            val cursor: Long,
            val epoch: Long,
        ) : Delivery

        /** Our own message coming back, already accounted for. */
        data class Echo(
            override val gid: String,
            val cursor: Long,
        ) : Delivery

        /**
         * A payload this group could not open.
         *
         * Not fatal and not silent: the cursor moves past it (the alternative
         * is a stream that never advances), but a UI that shows nothing here is
         * hiding a gap in a conversation.
         */
        data class Undecryptable(
            override val gid: String,
            val cursor: Long,
            val reason: String,
        ) : Delivery

        /**
         * A Commit of ours that did not happen: somebody else's Commit from the same epoch was
         * sequenced first, or ours never reached the coordinator. The group is on the winner's
         * epoch and the change (an invite, a removal, a rename) must be made again.
         *
         * Reported here, once, for a Commit settled after the call that made it had returned
         * (its post or its confirmation failed); a call still waiting throws
         * [CordnGroupException.Reason.COMMIT_LOST] instead, and nothing is reported here.
         */
        data class CommitLost(
            override val gid: String,
            val cursor: Long,
        ) : Delivery
    }

    /**
     * Where deliveries go that no `catchUp`/`subscribe` caller asked for.
     *
     * A Commit is confirmed by reading the stream up to its echo (see [invite]), and that read
     * delivers whatever else is on the stream too. Set this to the same sink a [CordnSyncLoop]
     * feeds, or those messages are stored but never shown until the room reloads.
     */
    var deliveryListener: (Delivery) -> Unit = {}

    /**
     * Told the ref of each KeyPackage whose Welcome was accepted, declined or found stale, so the
     * private half can be forgotten ([CordnKeyPackages.spent], which keeps a last-resort one).
     * [CordnCoordinatorRegistry] wires it; a failure here never fails the join it follows.
     */
    var onKeyPackageSpent: suspend (keyPackageRef: String) -> Unit = {}

    /** What became of an own Commit whose call is still waiting on it. */
    private enum class CommitOutcome { WAITING, APPLIED, LOST }

    /**
     * Own Commits a call is waiting on, by sealed payload. Written by the ingest that settles one,
     * read and removed by the call: entries live exactly as long as the call.
     */
    private val inFlight = ConcurrentMap<String, CommitOutcome>()

    /**
     * Staged Commits dropped because a stream read to the end did not hold them, by sealed
     * payload, kept so that a late echo (a `msg_post` the coordinator sequenced after its answer
     * timed out) can still be merged while the group is on the epoch it was built at. Memory only:
     * after a restart a late echo is recognised and reported, as cordn-web does, but not merged.
     */
    private val lostStaged = ConcurrentMap<String, LostCommit>()

    private class LostCommit(
        val gid: String,
        val staged: StagedCommitState,
        val welcomes: List<DeferredWelcome>,
    )

    /**
     * One group: its MLS state and what this manager knows about it beside that.
     *
     * Everything here is read and written under the group's lock, except [keys], which
     * [mediaKeyFor] reads without it.
     */
    private class Slot(
        group: MlsGroup,
    ) {
        var group: MlsGroup = group
            set(value) {
                field = value
                keys = null
            }

        /** The MLS state changed since it was last saved. */
        var dirty = true

        var savedCursor: GroupCursor? = null
        var savedEcho: EchoState? = null

        /** The keys of the epoch the group is on, derived once per epoch rather than per payload. */
        @Volatile
        var keys: EpochKeys? = null

        /**
         * Messages delivered but not yet written. [ingest] is not a suspend function -- it runs
         * inside the sync's own delivery callback -- so it cannot write. It queues here instead,
         * and [persist] drains the queue immediately before the cursor. Held in memory only for
         * as long as the cursor is: neither is on disk until a run ends, so a crash mid-run loses
         * both together and the next catch-up refetches from the last cursor that *was* written.
         */
        val pendingMessages = ArrayDeque<CordnDeliveredMessage>()
    }

    /**
     * The payload and media keys of one epoch, and of the former epochs MLS retains beside it.
     *
     * Each is an HKDF exporter derivation, and the former ones were derived again for every
     * payload the current key did not open: four exporters per message around every Commit.
     */
    private class EpochKeys(
        val epoch: Long,
        val payload: ByteArray,
        val media: ByteArray,
    ) {
        var former: List<Pair<Long, ByteArray>>? = null
        val formerMedia = mutableMapOf<Long, ByteArray?>()

        /** The account each leaf's credential names at this epoch, by leaf index. */
        var identities: Map<Int, HexKey>? = null

        /** As [identities], for the retained former epochs, decoded once each. */
        val formerIdentities = mutableMapOf<Long, Map<Int, HexKey>?>()
    }

    // ---- membership ------------------------------------------------------

    /** The group behind [gid], if this manager holds it. */
    fun group(gid: String): MlsGroup? = groups[gid]?.group

    private val _unrestorable = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * Groups [restore] found in the store and could not read, by `gid`, with why.
     *
     * Left on disk as they are (a later version may read them) and held out of everything else:
     * not synced, not shown in [gids], and no Welcome or new group may take their `gid`, which
     * would write over them.
     */
    val unrestorable: StateFlow<Map<String, String>> = _unrestorable.asStateFlow()

    /**
     * Restores every group and cursor the store holds. Call once at startup.
     *
     * One group at a time: a group whose saved state does not decode is reported in
     * [unrestorable] and the others are restored all the same, rather than one bad blob taking
     * every group on the coordinator down with it.
     */
    suspend fun restore() {
        store.listGroups().forEach { gid ->
            try {
                withGroup(gid) {
                    val blob = store.loadGroup(gid) ?: return@withGroup
                    val slot = Slot(MlsGroup.restore(MlsGroupState.decodeTls(blob), CordnGroupPolicy)).apply { dirty = false }
                    store.loadCursor(gid)?.let { cursor ->
                        val echoes = store.loadEchoState(gid)
                        sync.restore(gid, cursor, echoes)
                        slot.savedCursor = cursor
                        slot.savedEcho = echoes
                    }
                    groups[gid] = slot
                }
                if (gid in _unrestorable.value) _unrestorable.value -= gid
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "could not restore $gid; left in the store as it is", e)
                _unrestorable.value += gid to (e.message ?: e::class.simpleName ?: "unreadable")
            }
        }
        publishGids()
    }

    /** Refuses to install a group over one [restore] could not read. */
    private fun requireRestorable(gid: String) {
        if (gid in _unrestorable.value) throw CordnGroupException("a group with gid $gid is saved here but could not be read; it is left as it is")
    }

    private fun publishGids() {
        _gids.value = groups.snapshot().keys
    }

    /**
     * Creates a group this account administers.
     *
     * [gid] is the caller's to choose and the coordinator never interprets it
     * (`spec/00.md` §4). The reference client uses a random UUID; anything
     * unique on this coordinator works, and it must not be derived from the MLS
     * `group_id`, which is secret.
     */
    suspend fun createGroup(
        gid: String,
        metadata: CordnGroupMetadata,
    ): MlsGroup {
        val group =
            withGroup(gid) {
                requireRestorable(gid)
                require(groups[gid] == null) { "already in a group with gid $gid" }
                val group =
                    MlsGroup.create(
                        identity = CordnCredential.of(accountPubKey).identity,
                        policy = CordnGroupPolicy,
                        initialExtensions = listOf(metadata.toExtension()),
                        // See [gidFrom]: this is what lets a joiner -- ours or theirs --
                        // learn the delivery id from the Welcome alone.
                        groupId = gid.encodeToByteArray(),
                    )
                groups[gid] = Slot(group)
                persist(gid)
                group
            }
        publishGids()
        return group
    }

    /**
     * Adds [targetPubKey] to [gid], taking their KeyPackage from the
     * coordinator and leaving them a Welcome.
     *
     * The order is load-bearing:
     *
     * 1. Take and **verify** the publication payload. §9/§10 require the client
     *    to check it even though §8 makes the coordinator check too — a
     *    coordinator that skipped the check could otherwise hand us any
     *    account's name over any account's key material, and we would invite
     *    the wrong person.
     * 2. Seal the Commit under the **pre-commit** epoch key (`spec/03.md` §5).
     *    The engine hands that key back on [CommitResult.preCommitExporterSecret]
     *    precisely because it is unobtainable afterwards: sealing under the
     *    epoch the Commit *creates* produces a payload only the sender can
     *    open, and every other member silently stops advancing.
     * 3. Post the Commit and read the stream to its echo ([stageAndPost]); only
     *    a Commit the coordinator ordered first gets its Welcome, stored with
     *    the cursor it landed at, so the joiner starts from the epoch they can
     *    actually decrypt rather than replaying history that predates them.
     *    A Commit that lost the race throws
     *    [CordnGroupException.Reason.COMMIT_LOST] and sends nothing.
     *
     * ## Wire format
     *
     * The Commit goes out **public-framed** — `MlsMessage(PublicMessage)`,
     * which is what this engine produces. cordn's reference client emits
     * private-framed handshake messages instead, but accepts either: its
     * `processMessageBase64` admits wireformat 1 and 2 and hands both to
     * ts-mls's `processMessage`. Nothing is weakened by the choice, because the
     * coordinator sees only the outer ChaCha seal either way (§8) — the framing
     * is visible only to members, who can already tell a Commit from a message.
     * [ingest] reads both.
     */
    suspend fun invite(
        gid: String,
        targetPubKey: HexKey,
        keyPackageRef: String? = null,
    ): InviteResult =
        withGroup(gid) {
            // Before the KeyPackage: a group this device cannot change must not
            // spend somebody's one-time KeyPackage on the attempt.
            prepareToCommit(gid)

            // By ref when we have one: a join request names the exact KeyPackage
            // its sender published for this purpose, and taking a different one of
            // theirs would spend a package they meant for someone else.
            val taken =
                call { coordinator.takeKeyPackage(keyPackageRef ?: targetPubKey) }
                    ?: throw CordnGroupException(
                        "the coordinator holds no KeyPackage for $targetPubKey",
                        CordnGroupException.Reason.NO_KEY_PACKAGE,
                    )
            val verified = KeyPackagePublication.verify(taken.publicationEvent)
            if (verified.pubKey != targetPubKey) {
                throw CordnGroupException(
                    "the KeyPackage served for $targetPubKey belongs to ${verified.pubKey}",
                    CordnGroupException.Reason.WRONG_KEY_PACKAGE_OWNER,
                )
            }

            val committed =
                stageAndPost(gid, welcomeTo = listOf(targetPubKey to taken.keyPackageRef)) { group ->
                    group.proposeAdd(verified.bytes)
                    group.stageCommit()
                }
            val welcomeAt = committed.welcomes.getValue(targetPubKey).getOrThrow()
            InviteResult(gid, targetPubKey, taken.keyPackageRef, committed.cursor, welcomeAt)
        }

    /**
     * Invites several people in ONE commit.
     *
     * Not a loop over [invite]: one epoch rather than N, which is what the
     * reference client does, and one round of the commit lifecycle (see
     * [stageAndPost]) rather than N chances to lose a race. See
     * [MlsGroup.addMembers].
     *
     * Every KeyPackage is taken and verified BEFORE the commit, so a target the
     * coordinator holds nothing for fails while nothing has changed; those come
     * back in [BatchInviteResult.refused]. A Welcome that fails to store after
     * the commit is per-target and harmless to the group, and comes back in
     * [BatchInviteResult.undelivered]; it stays owed on disk, and a later sync stores it.
     */
    suspend fun inviteAll(
        gid: String,
        targetPubKeys: List<HexKey>,
    ): BatchInviteResult {
        if (targetPubKeys.isEmpty()) return BatchInviteResult(gid, emptyList(), emptyMap(), emptyMap())
        return withGroup(gid) {
            prepareToCommit(gid)

            // Phase 1, before anything is committed: take and verify every
            // KeyPackage. A failure here spends a KeyPackage for the targets
            // already taken and nothing else -- the group has not moved.
            val taken = mutableMapOf<HexKey, TakenKeyPackage>()
            val verified = mutableMapOf<HexKey, ByteArray>()
            val refused = mutableMapOf<HexKey, CordnGroupException>()

            targetPubKeys.distinct().forEach { target ->
                val held = call { coordinator.takeKeyPackage(target) }
                if (held == null) {
                    refused[target] =
                        CordnGroupException(
                            "the coordinator holds no KeyPackage for $target",
                            CordnGroupException.Reason.NO_KEY_PACKAGE,
                        )
                    return@forEach
                }
                val owner = KeyPackagePublication.verify(held.publicationEvent)
                if (owner.pubKey != target) {
                    refused[target] =
                        CordnGroupException(
                            "the KeyPackage served for $target belongs to ${owner.pubKey}",
                            CordnGroupException.Reason.WRONG_KEY_PACKAGE_OWNER,
                        )
                    return@forEach
                }
                taken[target] = held
                verified[target] = owner.bytes
            }

            if (verified.isEmpty()) return@withGroup BatchInviteResult(gid, emptyList(), emptyMap(), refused)

            // Phase 2: one commit, one post, confirmed. The Welcome is the same
            // for every joiner: RFC 9420 §12.4.3.1 puts a separate
            // EncryptedGroupSecrets per added member inside it, keyed by KeyPackage
            // reference, so each one finds its own.
            val committed =
                stageAndPost(gid, welcomeTo = verified.keys.map { it to taken.getValue(it).keyPackageRef }) { group ->
                    verified.values.forEach { group.proposeAdd(it) }
                    group.stageCommit()
                }

            // Phase 3: a Welcome that failed to store is per-target and harmless to
            // the group. The Commit is confirmed, so the group already counts them a
            // member -- they simply have no Welcome to join with. Reported rather
            // than thrown, because the others succeeded.
            val invited = mutableListOf<InviteResult>()
            val undelivered = mutableMapOf<HexKey, Throwable>()
            verified.keys.forEach { target ->
                committed.welcomes.getValue(target).fold(
                    onSuccess = { at -> invited += InviteResult(gid, target, taken.getValue(target).keyPackageRef, committed.cursor, at) },
                    onFailure = { undelivered[target] = it },
                )
            }

            BatchInviteResult(gid, invited, undelivered, refused)
        }
    }

    /**
     * Removes [targetPubKey] from [gid].
     *
     * Admin-gated, and not by this function: [CordnGroupPolicy.authorizeCommit]
     * refuses to build the Remove commit when the local member is not named in
     * `admin_pubkeys`, so the check cannot be forgotten here or bypassed by
     * another call site. A group in egalitarian mode lets any member do it.
     *
     * Refuses to remove YOU, as the reference client does. cordn has no
     * self-removal: MLS wants a SelfRemove proposal an admin then commits, and
     * committing your own Remove would advance the group into an epoch whose
     * keys you no longer hold — you would be the only member unable to read
     * what happened next.
     *
     * Sealed under the PRE-commit epoch key like every other commit here
     * (`spec/03.md` §5), which is also what lets the person being removed read
     * the commit that removes them: they still hold that epoch.
     */
    suspend fun removeMember(
        gid: String,
        targetPubKey: HexKey,
    ): RemovalResult {
        require(targetPubKey != accountPubKey) {
            "cordn has no self-removal: committing your own Remove would leave you unable to read the group"
        }
        return withGroup(gid) {
            prepareToCommit(gid)
            val group = requireGroup(gid)

            val leafIndex =
                CordnCredential
                    .membersOf(group)
                    .entries
                    .firstOrNull { it.value == targetPubKey }
                    ?.key
                    ?: throw CordnGroupException(
                        "$targetPubKey is not a member of $gid",
                        CordnGroupException.Reason.NOT_A_MEMBER,
                    )

            val committed =
                stageAndPost(gid) {
                    it.proposeRemove(leafIndex)
                    it.stageCommit()
                }
            RemovalResult(gid, targetPubKey, committed.cursor)
        }
    }

    /**
     * Replaces [gid]'s `cordn_group_metadata` — its name, description, icon,
     * image and admin list — with [metadata].
     *
     * Admin-gated the same way [removeMember] is, and through the same hook:
     * changing metadata is a GroupContextExtensions commit, which is the third
     * proposal type `admin_pubkeys` covers. Which means this is also how the
     * admin list itself changes, and why an egalitarian group can be given
     * admins by any member while an administered one cannot.
     *
     * A GroupContextExtensions proposal replaces the WHOLE extension list, so
     * every other extension the group carries is kept and only `0xC04D` is
     * swapped. Sending the metadata alone would silently drop
     * `required_capabilities` and the app-data dictionary, and a group whose
     * required capabilities vanished is one whose next commit peers reject.
     */
    suspend fun updateGroupMetadata(
        gid: String,
        metadata: CordnGroupMetadata,
    ): MetadataUpdateResult =
        updateGroupMetadata(gid) { current ->
            // Trailing fields this caller did not build (another client's
            // coordinator routing) ride along, so replacing the metadata does
            // not erase them for everyone. See CordnGroupMetadata.trailingFields.
            if (metadata.trailingFields.isEmpty()) metadata.copy(trailingFields = current.trailingFields) else metadata
        }

    /**
     * Changes [gid]'s metadata by [change], applied to what the group carries now.
     *
     * The safe way to edit one field. The extension is replaced whole, so a caller that built a
     * fresh [CordnGroupMetadata] from the fields it was editing (a rename: name and description)
     * erased the rest (the icon, the image URL) on every save. [change] receives the current
     * metadata, or an empty one when the group has none, read after the catch-up that every
     * Commit starts with, so it is the metadata the group really has.
     */
    suspend fun updateGroupMetadata(
        gid: String,
        change: (CordnGroupMetadata) -> CordnGroupMetadata,
    ): MetadataUpdateResult =
        withGroup(gid) {
            prepareToCommit(gid)
            val committed =
                stageAndPost(gid) { group ->
                    val current = CordnGroupMetadata.fromExtensions(group.extensions) ?: CordnGroupMetadata(name = "")
                    val kept = group.extensions.filterNot { it.extensionType == CordnGroupMetadata.EXTENSION_TYPE }
                    group.proposeGroupContextExtensions(kept + change(current).toExtension())
                    group.stageCommit()
                }
            MetadataUpdateResult(gid, committed.cursor)
        }

    // ---- the commit lifecycle -------------------------------------------

    /**
     * What every Commit of ours does before it is built (`multi-device.md` §10.5, cordn-web
     * 0.5.2/0.5.3). Runs under [gid]'s lock, which the caller holds until its Commit is settled.
     *
     * 1. **Catch up, to the end.** A Commit built on an epoch the group has left is one nobody
     *    can apply. "The end" is an empty page, never a page budget running out: an earlier
     *    Commit of ours whose echo sits past that budget would otherwise be taken for one that
     *    never landed, and dropping it forks the group the moment its echo is read.
     * 2. **Refuse while behind.** A payload none of our keys opened, or a Commit at our epoch that
     *    would not apply, is evidence of a Commit we missed. A Commit staged from that view splits
     *    the group (seen live upstream: a join approved from ten epochs back), so it is refused
     *    until a payload sealed for our epoch is read successfully.
     * 3. **Settle an earlier Commit of ours.** Its echo, if it is on the stream, was applied by
     *    step 1. Still outstanding after a read to the end, it never landed: it is dropped
     *    ([Delivery.CommitLost]) instead of left in the way, but stays recognisable, so an echo
     *    that comes late after all is still known as ours (and merged, while the group has not
     *    moved).
     * 4. **Deliver what is owed.** Welcomes of Commits confirmed by a sync rather than by their
     *    call.
     *
     * A failed catch-up throws: a device that cannot read the stream cannot know it is current.
     */
    private suspend fun prepareToCommit(gid: String) {
        requireGroup(gid)
        readToTheEnd(gid)
        flushOwedWelcomes(gid)

        val inbox = sync.inbox(gid)
        if (inbox.unopenable > 0) {
            throw CordnGroupException(
                "$gid has ${inbox.unopenable} payloads this device could not open or apply; it may have missed a change",
                CordnGroupException.Reason.BEHIND,
            )
        }

        inbox.pending().filter { !it.localStateApplied }.forEach { settleLost(gid, it, keepForLateEcho = true, cursor = inbox.cursor.lastCursor, emit = deliveryListener) }
        if (requireGroup(gid).hasPendingCommit) {
            // Staged with no record of it (a store that lost the record): nothing can confirm it.
            discardStagedCommit(gid)
            deliveryListener(Delivery.CommitLost(gid, inbox.cursor.lastCursor))
        }
        persist(gid)
    }

    /** What [stageAndPost] confirmed: where the Commit landed, and each Welcome's fate. */
    private class Committed(
        val cursor: Long,
        val welcomes: Map<HexKey, Result<Long>>,
    )

    /**
     * Builds a Commit with [stage], posts it, and returns once the stream says it happened.
     * Runs under [gid]'s lock.
     *
     * The Commit is **staged, not applied** ([MlsGroup.stageCommit]): the group stays on its
     * epoch until the coordinator's order says ours came first. That is the whole difference
     * from applying first and posting after, which left a device whose Commit lost the race on
     * a branch nobody else follows, its later messages unreadable to everyone.
     *
     * 1. Stage the Commit and record it as expected, and **persist both before posting**: a
     *    crash after the post must not leave a Commit on the stream this device can neither
     *    recognise nor apply. The staged Commit is part of the saved MLS state. A save that
     *    fails undoes the staging: nothing was sent.
     * 2. Post it, sealed under the epoch it leaves (`spec/03.md` §5).
     * 3. Read the stream until its echo. Ours first: the echo merges the staged Commit. Someone
     *    else's Commit from the same epoch first: applying theirs discards ours, and this throws
     *    [CordnGroupException.Reason.COMMIT_LOST]. Nothing changed; try again.
     * 4. Only then store the Welcomes, at the Commit's cursor. A Welcome into a branch nobody is
     *    on is an invitation nobody can use. One that fails to store stays owed, on disk, and a
     *    later sync stores it.
     *
     * A post or a confirmation that fails is **ambiguous**: the Commit may or may not be on the
     * stream. It stays staged, with its Welcomes, and this throws
     * [CordnGroupException.Reason.COMMIT_UNCONFIRMED]. It is never posted again (`msg_post`
     * has no dedup, so a blind retry is a second Commit): its echo settles it on a later sync,
     * or the next [prepareToCommit] drops it.
     */
    private suspend fun stageAndPost(
        gid: String,
        welcomeTo: List<Pair<HexKey, String>> = emptyList(),
        stage: (MlsGroup) -> CommitResult,
    ): Committed {
        val slot = requireSlot(gid)
        val fromEpoch = slot.group.epoch
        val result =
            try {
                stage(slot.group)
            } catch (e: Exception) {
                // stageCommit puts the group back, but the proposals made for
                // it stay in the pool, where the next Commit would pick them up.
                dropLocalProposals(gid)
                throw e
            }
        val welcomeBytes = result.welcomeBytes
        if (welcomeTo.isNotEmpty() && welcomeBytes == null) {
            dropLocalProposals(gid)
            throw CordnGroupException("adding members produced no Welcome", CordnGroupException.Reason.NO_WELCOME)
        }

        // framedCommitBytes, not commitBytes: the latter is the bare RFC 9420
        // Commit struct with no MLSMessage around it, which no receiver can
        // parse. preCommitExporterSecret is the key of the epoch it leaves.
        val sealed = SealedPayload.seal(result.framedCommitBytes, result.preCommitExporterSecret)
        // One Welcome serves every joiner (RFC 9420 §12.4.3.1), so it is encoded and kept once.
        val welcomeBase64 = welcomeBytes?.let { Base64.encode(it) }
        val welcomes = welcomeTo.map { (target, ref) -> DeferredWelcome(target, ref, checkNotNull(welcomeBase64)) }
        val inbox = sync.inbox(gid)
        inbox.expectEcho(PendingEpochOperation(sealed, localStateApplied = false, welcomes = welcomes, fromEpoch = fromEpoch))
        slot.dirty = true
        try {
            persist(gid)
        } catch (e: Exception) {
            // Durability before visibility: what was not saved is not posted, and nothing of it
            // stays to be confirmed or retried by accident.
            inbox.forget(sealed)
            discardStagedCommit(gid)
            if (e is CancellationException) throw e
            throw CordnGroupException("the commit to $gid could not be saved, so it was not sent: ${e.message}", cause = e)
        }

        inFlight[sealed] = CommitOutcome.WAITING
        try {
            val posted =
                try {
                    call { sync.postRegisteredCommit(gid, sealed) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw CordnGroupException(
                        "posting the commit to $gid failed; it may still land: ${e.message}",
                        CordnGroupException.Reason.COMMIT_UNCONFIRMED,
                        e,
                    )
                }

            try {
                readToTheEnd(gid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw CordnGroupException(
                    "the commit to $gid was posted at ${posted.cursor} and could not be confirmed: ${e.message}",
                    CordnGroupException.Reason.COMMIT_UNCONFIRMED,
                    e,
                )
            }

            when (inFlight[sealed]) {
                CommitOutcome.APPLIED -> {
                    // Ours: its Welcomes are this call's to store, with per-target results.
                    val stored = storeOwedWelcomes(gid, sealed)
                    persistQuietly(gid)
                    return Committed(posted.cursor, stored)
                }

                CommitOutcome.LOST -> {
                    throw CordnGroupException(
                        "another commit to $gid was sequenced first; this change did not happen",
                        CordnGroupException.Reason.COMMIT_LOST,
                    )
                }

                else -> {
                    throw CordnGroupException(
                        "the commit to $gid was posted at ${posted.cursor} and its echo has not come back",
                        CordnGroupException.Reason.COMMIT_UNCONFIRMED,
                    )
                }
            }
        } finally {
            inFlight.remove(sealed)
        }
    }

    /**
     * Reads [gid]'s stream until a page comes back empty, delivering to [deliveryListener], and
     * persists. Runs under [gid]'s lock.
     *
     * No page budget: a decision made on "read to the end" (that a Commit of ours never landed,
     * that the group is current enough to commit on) is wrong when the end was not reached.
     * Throws when the coordinator stops serving progress before an empty page.
     */
    private suspend fun readToTheEnd(gid: String) {
        val drained =
            call { sync.drain(listOf(gid), maxPages = Int.MAX_VALUE) { g, ingestion -> ingest(g, ingestion, deliveryListener) } }
        persistQuietly(gid)
        if (!drained.current) {
            throw CordnGroupException("the coordinator stopped serving $gid's stream before its end")
        }
    }

    private suspend fun storeWelcome(
        welcome: DeferredWelcome,
        after: Long,
    ): Result<Long> =
        try {
            Result.success(
                call {
                    coordinator.storeWelcome(
                        targetPubKey = welcome.targetPubKey,
                        keyPackageRef = welcome.keyPackageRef,
                        welcomeBase64 = welcome.welcomeBase64,
                        after = after,
                    )
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    /**
     * Stores the Welcomes the confirmed Commit [sealed] still owes, at the cursor it landed at.
     * Each one stored leaves the record; one that fails stays, on disk, for the next attempt.
     * The record is only ever touched under the group's lock, so no Welcome is stored twice.
     */
    private suspend fun storeOwedWelcomes(
        gid: String,
        sealed: String,
    ): Map<HexKey, Result<Long>> {
        val inbox = sync.inbox(gid)
        val op = inbox.operation(sealed) ?: return emptyMap()
        val after = op.landedAt ?: return emptyMap()
        if (op.status != PendingEpochOperation.Status.CONFIRMED || op.welcomes.isEmpty()) return emptyMap()
        val results = op.welcomes.associate { it.targetPubKey to storeWelcome(it, after) }
        inbox.update(op.copy(welcomes = op.welcomes.filter { results[it.targetPubKey]?.isFailure == true }))
        return results
    }

    /**
     * Stores the Welcomes of [gid]'s Commits confirmed by a sync rather than by the call that made
     * them, or whose store failed before. Runs under [gid]'s lock.
     */
    private suspend fun flushOwedWelcomes(gid: String) {
        val owed =
            sync.inbox(gid).operations().filter {
                it.status == PendingEpochOperation.Status.CONFIRMED && it.welcomes.isNotEmpty() && inFlight[it.sealedBase64] == null
            }
        if (owed.isEmpty()) return
        owed.forEach { storeOwedWelcomes(gid, it.sealedBase64) }
        persistQuietly(gid)
    }

    /**
     * Settles our Commit [op] as one that did not happen: the group drops it if it is still
     * staged, the record keeps only what recognises its echo, and the loss is reported once, to
     * the call waiting on it or else to [emit].
     *
     * [keepForLateEcho]: the stream was read to the end without it, so it may yet land late. Its
     * staged state is kept in memory to merge it then, while the group is still on its epoch.
     */
    private fun settleLost(
        gid: String,
        op: PendingEpochOperation,
        keepForLateEcho: Boolean,
        cursor: Long,
        emit: (Delivery) -> Unit,
    ) {
        val slot = requireSlot(gid)
        if (slot.group.hasPendingCommit) {
            if (keepForLateEcho && op.fromEpoch == slot.group.epoch) {
                stagedFor(slot, op.sealedBase64)?.let { lostStaged[op.sealedBase64] = LostCommit(gid, it, op.welcomes) }
            }
            discardStagedCommit(gid)
        }
        sync.inbox(gid).update(op.copy(status = PendingEpochOperation.Status.LOST, welcomes = emptyList()))
        reportLost(gid, op.sealedBase64, cursor, emit)
    }

    /** The group's staged Commit, when it is the one sealed as [sealed]. */
    private fun stagedFor(
        slot: Slot,
        sealed: String,
    ): StagedCommitState? {
        val staged = slot.group.saveState().pendingCommit ?: return null
        val opened = runCatching { SealedPayload.open(sealed, keysOf(slot).payload) }.getOrNull() ?: return null
        return staged.takeIf { it.result.framedCommitBytes.contentEquals(opened) }
    }

    /** One "change lost" per Commit: to the call still waiting on it, or else to [emit]. */
    private fun reportLost(
        gid: String,
        sealed: String,
        cursor: Long,
        emit: (Delivery) -> Unit,
    ) {
        if (inFlight[sealed] != null) {
            inFlight[sealed] = CommitOutcome.LOST
        } else {
            emit(Delivery.CommitLost(gid, cursor))
        }
    }

    /**
     * Forgets a staged Commit that never landed, and the proposals made for it. In memory: the
     * caller persists.
     *
     * [MlsGroup.clearPendingCommit] alone would leave our Add or Remove in the proposal pool,
     * where the next Commit would carry it along unasked. Proposals made here and never sent
     * have no reference; somebody else's have one and stay.
     */
    private fun discardStagedCommit(gid: String) {
        val slot = requireSlot(gid)
        val state = slot.group.saveState()
        slot.group =
            MlsGroup.restore(
                state.copy(pendingCommit = null, pendingProposals = state.pendingProposals.filter { it.authenticatedContentBytes != null }),
                CordnGroupPolicy,
            )
        slot.dirty = true
        keysOf(slot)
    }

    /** As [discardStagedCommit], for proposals a Commit that failed to build left in the pool. */
    private fun dropLocalProposals(gid: String) {
        val slot = groups[gid] ?: return
        if (slot.group.hasPendingCommit) return
        val state = slot.group.saveState()
        slot.group = MlsGroup.restore(state.copy(pendingProposals = state.pendingProposals.filter { it.authenticatedContentBytes != null }), CordnGroupPolicy)
        slot.dirty = true
    }

    /** Whether [gid] has seen payloads it could not open, or Commits it could not apply, since the last one it read at its epoch. */
    fun mayBeBehind(gid: String): Boolean = groups[gid] != null && sync.inbox(gid).unopenable > 0

    /**
     * The media key for [message]'s attachments: the one captured with it, or, for a message
     * stored before keys were kept, the key of [gid]'s current epoch (cordn-web's legacy
     * fallback, `b461264`), which opens it only if no Commit happened since.
     */
    fun mediaKeyFor(
        gid: String,
        message: CordnDeliveredMessage,
    ): ByteArray? = message.mediaKey ?: groups[gid]?.let { slot -> slot.keys?.media ?: CordnMediaEncryption.mediaKey(slot.group) }

    /**
     * Opens every pending Welcome without joining anything.
     *
     * Splitting "open" from "join" is what makes an accept/decline surface
     * possible at all. A Welcome is opaque until it is processed — the `gid`,
     * the group's name and who is already in it all live inside it — so a user
     * cannot be asked about an invitation that has not been opened, and
     * opening must therefore not be the same act as accepting.
     *
     * [bundleFor] resolves a `kp_ref` to the KeyPackageBundle we published
     * under it. A Welcome we have no private half for is skipped and left on
     * the coordinator: it belongs to another device of this account, and
     * retiring it here would destroy it.
     *
     * A Welcome for a group this manager already holds is skipped **and**
     * retired. That is the stale record [accept] leaves behind when its
     * acknowledgement does not reach the coordinator, and letting it pile up
     * is what made a second drain destructive.
     *
     * [gidFor] overrides where the delivery id comes from — see [gidFrom] for
     * why it can be missing and what to pass when it is.
     */
    suspend fun pendingWelcomes(
        bundleFor: suspend (String) -> KeyPackageBundle?,
        gidFor: (MlsGroup) -> String? = ::gidFrom,
    ): WelcomeInbox {
        // Drained into a local first: passing the drain inline emptied the
        // queue before the call, so a failing takeWelcomes dropped those acks
        // for good and the coordinator kept re-serving what we had consumed.
        // The join-request path already re-queues on failure; this matches it.
        val acks = drainRetirements()
        val pending =
            try {
                call { coordinator.takeWelcomes(acks) }
            } catch (e: Exception) {
                retirementLock.withLock { pendingRetirements += acks }
                throw e
            }.filterNot { welcome -> acks.any { it.keyPackageRef == welcome.keyPackageRef && it.at == welcome.at } }
        val opened = mutableListOf<OpenedWelcome>()
        val skipped = mutableListOf<SkippedWelcome>()
        val stale = mutableListOf<ConsumedWelcomeRef>()

        pending.forEach { welcome ->
            val bundle = bundleFor(welcome.keyPackageRef)
            if (bundle == null) {
                skipped += SkippedWelcome(welcome.keyPackageRef, NO_PRIVATE_HALF)
                return@forEach
            }
            val group =
                try {
                    MlsGroup.processWelcome(Base64.decode(welcome.welcomeBase64), bundle, CordnGroupPolicy)
                } catch (e: Exception) {
                    skipped += SkippedWelcome(welcome.keyPackageRef, e.message ?: "the Welcome did not open")
                    return@forEach
                }
            val gid = gidFor(group)
            if (gid == null) {
                skipped += SkippedWelcome(welcome.keyPackageRef, UNKNOWN_GID)
                return@forEach
            }
            if (gid in _unrestorable.value) {
                // Not retired: the group it names is unreadable here, not known to be ours.
                skipped += SkippedWelcome(welcome.keyPackageRef, UNRESTORABLE_GROUP)
                return@forEach
            }
            if (groups[gid] != null) {
                skipped += SkippedWelcome(welcome.keyPackageRef, ALREADY_A_MEMBER)
                stale += ConsumedWelcomeRef(welcome.keyPackageRef, welcome.at)
                return@forEach
            }

            opened +=
                OpenedWelcome(
                    gid = gid,
                    keyPackageRef = welcome.keyPackageRef,
                    at = welcome.at,
                    metadata = CordnGroupMetadata.fromExtensions(group.extensions),
                    // Who is in the group, which is NOT the same as who
                    // invited us: a Welcome carries the ratchet tree, not the
                    // identity of whoever signed the Commit that created it.
                    // Naming an inviter here would be a guess dressed as a
                    // fact, and in a group of three it would usually be wrong.
                    members = CordnCredential.memberIdentities(group),
                    epoch = group.epoch,
                    group = group,
                    after = welcome.after,
                )
        }

        retire(stale)
        stale.forEach { spend(it.keyPackageRef) }
        return WelcomeInbox(opened, skipped)
    }

    /**
     * Joins the group [welcome] opens, and retires it.
     *
     * Refuses a `gid` this manager already holds rather than replacing it.
     * Installing a Welcome over a live group rolls its epoch back to the one
     * it was issued at, and MLS does not recover from that: every message
     * after the rolled-back epoch stops decrypting, silently and permanently.
     */
    suspend fun accept(welcome: OpenedWelcome): String {
        withGroup(welcome.gid) {
            requireRestorable(welcome.gid)
            require(groups[welcome.gid] == null) { "already in a group with gid ${welcome.gid}" }

            groups[welcome.gid] = Slot(welcome.group)
            // `after` is the inviter saying where this member's history starts.
            // Without it a joiner replays epochs from before it existed and
            // every one lands as Undecryptable.
            welcome.after?.let { sync.restore(welcome.gid, sync.inbox(welcome.gid).cursor.advancedTo(it)) }
            persist(welcome.gid)
        }
        publishGids()

        retire(listOf(ConsumedWelcomeRef(welcome.keyPackageRef, welcome.at)))
        spend(welcome.keyPackageRef)
        return welcome.gid
    }

    /**
     * Retires [welcome] without joining it.
     *
     * Declining is permanent and there is no undo, because there is no undo to
     * build: a retired Welcome is gone from the coordinator, and the KeyPackage
     * it was addressed to has been spent. Re-joining means being invited again.
     */
    suspend fun decline(welcome: OpenedWelcome) {
        retire(listOf(ConsumedWelcomeRef(welcome.keyPackageRef, welcome.at)))
        spend(welcome.keyPackageRef)
    }

    /** [onKeyPackageSpent], where a failure is logged and nothing more. */
    private suspend fun spend(keyPackageRef: String) {
        try {
            onKeyPackageSpent(keyPackageRef)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not forget the private half of KeyPackage $keyPackageRef", e)
        }
    }

    /**
     * Opens every pending Welcome and accepts all of them.
     *
     * The unattended path — `amy`, tests, anything with no one to ask. A UI
     * uses [pendingWelcomes] and [accept]/[decline] instead, because accepting
     * an invitation on someone's behalf is a decision, not a sync step.
     */
    suspend fun joinPendingWelcomes(
        bundleFor: suspend (String) -> KeyPackageBundle?,
        gidFor: (MlsGroup) -> String? = ::gidFrom,
    ): JoinResults {
        val inbox = pendingWelcomes(bundleFor, gidFor)
        val joined = inbox.pending.map { accept(it) }
        return JoinResults(joined, inbox.skipped)
    }

    /**
     * Acknowledges [refs] so the coordinator stops serving them.
     *
     * `welcome_take` carries acknowledgements for the *previous* round rather
     * than taking them as their own call, so a retirement can only ride the
     * next take. Sending one immediately keeps the common case prompt; a
     * failure parks it in [pendingRetirements] instead of being lost, and
     * [pendingWelcomes] flushes it on its next call. Failing to retire must
     * never fail the join it follows — the group is already installed and
     * persisted, and a Welcome served twice is now merely redundant rather
     * than destructive.
     */
    private suspend fun retire(refs: List<ConsumedWelcomeRef>) {
        if (refs.isEmpty()) return
        try {
            call { coordinator.takeWelcomes(refs) }
        } catch (e: Exception) {
            retirementLock.withLock { pendingRetirements += refs }
            currentCoroutineContext().ensureActive()
        }
    }

    private fun drainRetirements(): List<ConsumedWelcomeRef> =
        retirementLock.withLock {
            val queued = pendingRetirements.toList()
            pendingRetirements.clear()
            queued
        }

    // ---- join requests --------------------------------------------------

    /**
     * Asks to be added to [gid], offering [keyPackageRef] as the way in.
     *
     * The requester side of a share link. It tells the coordinator, and
     * through it the group's members, that this account wants in and which
     * KeyPackage to use — nothing more. Only a member can actually add anyone,
     * and nothing here obliges them to.
     *
     * This is an attributable call: the coordinator learns this account is
     * interested in this group whether or not anyone ever accepts. That is
     * unavoidable — there is no way to ask to join without asking — and it is
     * the cost the exposure disclosure names.
     */
    suspend fun requestToJoin(
        gid: String,
        keyPackageRef: String,
    ): Long =
        call { coordinator.storeJoinRequest(gid, keyPackageRef) }
            // Written whether or not anyone ever answers. The exposure is the
            // asking, not the joining -- the coordinator has the npub either
            // way -- so recording it only on success would understate it in
            // exactly the case where nothing else on screen mentions it.
            .also { store.saveJoinedViaRequest(gid) }

    /**
     * Everyone asking to join a group this manager holds.
     *
     * Only this manager's own `gid`s are asked about, because those are the
     * only ones it could act on. Like Welcomes, acknowledgements ride the next
     * call, so a previous round's decisions are flushed here.
     *
     * **Any member can answer these, not only an admin.** `spec/01.md` §5.3
     * makes `admin_pubkeys` presentation metadata and neither the spec nor the
     * reference coordinator restricts who may commit — see
     * [CordnGroupPolicy]'s "why there is no authorization hook". A UI that
     * hid this behind an admin check would be inventing a boundary cordn does
     * not have.
     */
    suspend fun pendingJoinRequests(): List<JoinRequest> {
        // Only groups this account can actually admit someone to, as both
        // reference clients do. Accepting a request is an Add commit, so in a
        // group that names admins a non-admin asking for the list would be
        // shown people it can only fail to let in. Egalitarian groups name no
        // admins and so are all still here.
        val administered = gidList().filter { gid -> withGroup(gid) { groups[gid]?.group?.let { CordnGroupPolicy.isLocalAdmin(it.view()) } == true } }
        if (administered.isEmpty()) return emptyList()
        return call { coordinator.takeJoinRequests(administered, drainRequestRetirements()) }
    }

    /**
     * Adds the account behind [request] to its group, and retires the request.
     *
     * Retiring only after the invite lands: a request dropped before the
     * Welcome exists is a person who asked, was told nothing, and has no way
     * to ask again without a fresh link.
     */
    suspend fun acceptJoinRequest(request: JoinRequest): InviteResult {
        val result = invite(request.gid, request.pubKey, request.keyPackageRef)
        retireRequests(listOf(ConsumedJoinRequestRef(request.gid, request.pubKey, request.at)))
        return result
    }

    /** Retires [request] without adding anyone. */
    suspend fun declineJoinRequest(request: JoinRequest) {
        retireRequests(listOf(ConsumedJoinRequestRef(request.gid, request.pubKey, request.at)))
    }

    /** As [retire], for join requests: `join_request_take_many` carries the acks. */
    private suspend fun retireRequests(refs: List<ConsumedJoinRequestRef>) {
        val gids = gidList()
        if (refs.isEmpty() || gids.isEmpty()) return
        try {
            call { coordinator.takeJoinRequests(gids, refs) }
        } catch (e: Exception) {
            retirementLock.withLock { pendingRequestRetirements += refs }
            currentCoroutineContext().ensureActive()
        }
    }

    private fun drainRequestRetirements(): List<ConsumedJoinRequestRef> =
        retirementLock.withLock {
            val queued = pendingRequestRetirements.toList()
            pendingRequestRetirements.clear()
            queued
        }

    // ---- messages --------------------------------------------------------

    /**
     * Sends a message that refers to another one — a reply, reaction, edit,
     * deletion or pin.
     *
     * The kind and the tags are decided by
     * [CordnMessageReferences.outbound], not here: a kind switch beside a tag
     * switch is how the two stop agreeing, and that file holds both halves
     * with tests over the round trip.
     *
     * ## It refuses what the fold would ignore
     *
     * `CordnAnnotationIndex` enforces cordn's authorization rules when folding
     * annotations onto their targets, and edits and deletions are author-only
     * there. Without the same check here, editing someone else's message would
     * appear to work — the message goes out, the coordinator stores it, and
     * every client including ours silently drops it. Failing at the call is
     * the difference between a bug someone can report and one nobody can see.
     *
     * Reactions and pins are deliberately not checked: §5.1 makes a reaction
     * anyone's and a pin any member's.
     */
    suspend fun post(
        gid: String,
        content: String = "",
        replyTo: CordnMessageReferences.Target? = null,
        reactionTo: CordnMessageReferences.Target? = null,
        editTo: CordnMessageReferences.Target? = null,
        deleteTo: CordnMessageReferences.Target? = null,
        pinTo: CordnMessageReferences.Target? = null,
        pinOp: CordnMessageReferences.PinOp = CordnMessageReferences.PinOp.ADD,
    ): CordnDeliveredMessage {
        editTo?.let { require(it.pubKey == accountPubKey) { "only a message's author can edit it" } }
        deleteTo?.let { require(it.pubKey == accountPubKey) { "only a message's author can delete it" } }

        val outbound =
            CordnMessageReferences.outbound(
                content = content,
                replyTo = replyTo,
                reactionTo = reactionTo,
                editTo = editTo,
                deleteTo = deleteTo,
                pinTo = pinTo,
                pinOp = pinOp,
            )
        return send(gid, outbound.content, outbound.kind, outbound.tags)
    }

    /**
     * Sends [content] to [gid] as a cordn application message.
     *
     * Returns the message as the room will hold it, cursor included. The
     * cursor is the coordinator's, taken from the post's own response, so a
     * caller that shows the message immediately places it in the same order
     * the echo would have — [CordnGroupChatroom.ORDER] sorts on it, and a
     * guessed one would put your own message in the wrong place until the
     * echo corrected it.
     *
     * The ratchet step that sealed it is saved **before** the post: a restart that had not
     * saved it would seal the next message under the same key and nonce. After a successful
     * post nothing throws: the message is out, and a failed save of its record is retried by the
     * next one rather than turned into an error that invites sending it twice.
     */
    suspend fun send(
        gid: String,
        content: String,
        kind: Int = CHAT_KIND,
        tags: Array<Array<String>> = emptyArray(),
    ): CordnDeliveredMessage =
        withGroup(gid) {
            val slot = requireSlot(gid)
            val envelope =
                CordnEnvelope.build(
                    pubKey = accountPubKey,
                    createdAt = clock(),
                    kind = kind,
                    tags = tags,
                    content = content,
                )
            val sealed = CordnApplicationMessage.seal(slot.group, accountPubKey, envelope)
            // The epoch it is sealed at is the epoch its attachments were sealed
            // for, so this is the key that opens them later.
            val mediaKey = keysOf(slot).media
            slot.dirty = true
            persist(gid)

            val posted = call { sync.postMessage(gid, sealed) }
            // Our own message never arrives as a Delivery.Message — it comes back
            // as an Echo, which by design carries nothing — so the only place it
            // can be recorded is here, where we still hold the plaintext.
            val delivered = CordnDeliveredMessage(envelope, posted.cursor, mediaKey, posted.at)
            slot.pendingMessages.addLast(delivered)
            persistQuietly(gid)
            delivered
        }

    /** Drains history for every group this manager holds. */
    override suspend fun catchUp(onDelivery: (Delivery) -> Unit): Int {
        val gids = gidList()
        if (gids.isEmpty()) return 0
        val drained =
            call {
                sync.drain(gids, guard = { gid, deliver -> withGroup(gid) { deliver() } }) { gid, ingestion -> ingest(gid, ingestion, onDelivery) }
            }
        gids.forEach { gid ->
            withGroup(gid) {
                persistQuietly(gid)
                flushOwedWelcomes(gid)
            }
        }
        return drained.delivered
    }

    /**
     * Subscribes to live delivery for every group. Suspends until the
     * coordinator closes the stream, so give it its own coroutine.
     *
     * Persists on the way out, like [catchUp], and for the same reason:
     * delivering a message advances the ratchet and the cursor, so a
     * subscription that ended without writing would leave that progress only
     * in memory. A long-lived host survives that; a process-per-command client
     * does not, and neither does a crash.
     */
    override suspend fun subscribe(
        timeoutMs: Long,
        onDelivery: (Delivery) -> Unit,
    ) {
        val gids = gidList()
        if (gids.isEmpty()) return
        try {
            call {
                sync.subscribe(gids, timeoutMs, guard = { gid, deliver -> withGroup(gid) { deliver() } }) { gid, ingestion -> ingest(gid, ingestion, onDelivery) }
            }
        } finally {
            // finally, not after: a subscription normally ends by timing out or
            // being cancelled, and those are the cases with progress to keep.
            //
            // NonCancellable because the cancelled case is the one this exists
            // for and the one it could not serve: persistAll suspends, and a
            // suspend call in a cancelled coroutine throws before it writes
            // anything. Without this the `finally` looked like it saved on
            // cancellation and silently did not.
            withContext(NonCancellable) { persistAll() }
        }
    }

    /** Ingests one message of [gid]'s stream, under [gid]'s lock, reporting each outcome to [emit]. */
    private fun ingest(
        gid: String,
        ingestion: Ingestion,
        emit: (Delivery) -> Unit,
    ) {
        val slot = groups[gid] ?: return emit(Delivery.Undecryptable(gid, ingestion.cursor, "no such group"))

        when (ingestion) {
            is Ingestion.OwnMessage -> {
                emit(Delivery.Echo(gid, ingestion.cursor))
            }

            // Our own Commit, already applied locally when we posted it (what
            // this manager did before commits were staged).
            is Ingestion.SelfEchoConfirmed -> {
                emit(Delivery.Echo(gid, ingestion.cursor))
            }

            // Our own Commit, staged and not yet applied: the echo is what
            // tells us it came first. Also how a client that died between the
            // post and the confirmation recovers.
            is Ingestion.SelfEchoUnapplied -> {
                slot.dirty = true
                ingestOwnCommit(gid, slot, ingestion, emit)
            }

            is Ingestion.Process -> {
                slot.dirty = true
                ingestPayload(gid, slot, ingestion.cursor, ingestion.sealedBase64, ingestion.at, emit)
            }
        }
    }

    /**
     * Our own Commit, back from the stream. What it means depends on what we already decided
     * about it:
     *
     * - **Pending**: processing it merges the staged Commit ([MlsGroup.processFramedCommit]
     *   recognises its own bytes). When somebody else's Commit from the same epoch came first,
     *   applying theirs already discarded ours: this echo then is the evidence that our change
     *   lost. Neither case is a gap in the conversation, and neither is evidence that we are
     *   behind.
     * - **Lost**, after a read to the end did not hold it: it landed late after all. While the
     *   group is still on the epoch it was built at, the kept staged state is put back and
     *   merged, which is what every other member does with it; otherwise it was already reported.
     * - **Confirmed**: a crash between saves delivered it again. Merged again if the saved group
     *   predates it.
     */
    private fun ingestOwnCommit(
        gid: String,
        slot: Slot,
        ingestion: Ingestion.SelfEchoUnapplied,
        emit: (Delivery) -> Unit,
    ) {
        val inbox = sync.inbox(gid)
        val sealed = ingestion.sealedBase64
        val op = inbox.operation(sealed) ?: PendingEpochOperation(sealed, localStateApplied = false, welcomes = ingestion.welcomes)

        when (op.status) {
            PendingEpochOperation.Status.PENDING -> {
                if (applyOwnCommit(gid, slot, sealed)) {
                    confirm(gid, slot, op, ingestion.cursor, emit)
                } else {
                    lostStaged.remove(sealed)
                    inbox.update(op.copy(status = PendingEpochOperation.Status.LOST, welcomes = emptyList(), landedAt = ingestion.cursor))
                    reportLost(gid, sealed, ingestion.cursor, emit)
                }
            }

            PendingEpochOperation.Status.LOST -> {
                val late = lostStaged.remove(sealed)
                inbox.update(op.copy(landedAt = ingestion.cursor))
                if (late != null && late.staged.fromEpoch == slot.group.epoch && mergeLate(gid, slot, sealed, late)) {
                    confirm(gid, slot, op.copy(welcomes = late.welcomes), ingestion.cursor, emit)
                } else {
                    emit(Delivery.Echo(gid, ingestion.cursor))
                }
            }

            PendingEpochOperation.Status.CONFIRMED -> {
                if (slot.group.hasPendingCommit && applyOwnCommit(gid, slot, sealed)) {
                    inbox.noteCurrentEpochReadable()
                    afterEpochChange(gid, slot, ingestion.cursor, emit)
                    emit(Delivery.EpochAdvanced(gid, ingestion.cursor, slot.group.epoch))
                } else {
                    emit(Delivery.Echo(gid, ingestion.cursor))
                }
            }
        }
    }

    /** Merges our staged Commit [sealed] by processing its echo; false when it does not apply. */
    private fun applyOwnCommit(
        gid: String,
        slot: Slot,
        sealed: String,
    ): Boolean {
        val group = slot.group
        if (!group.hasPendingCommit) return false
        val before = group.epoch
        return try {
            applyHandshake(group, SealedPayload.open(sealed, keysOf(slot).payload)) && group.epoch == before + 1
        } catch (e: Exception) {
            // The one place a fork shows itself: our own Commit, back from the stream, refused.
            Log.w(TAG, "our own commit to $gid did not apply at epoch $before", e)
            false
        }
    }

    /** Puts [late]'s staged Commit back and merges it. The group is left as it was when it does not apply. */
    private fun mergeLate(
        gid: String,
        slot: Slot,
        sealed: String,
        late: LostCommit,
    ): Boolean {
        val current = slot.group.saveState()
        slot.group = MlsGroup.restore(current.copy(pendingCommit = late.staged), CordnGroupPolicy)
        if (applyOwnCommit(gid, slot, sealed)) return true
        slot.group = MlsGroup.restore(current, CordnGroupPolicy)
        return false
    }

    /** Our Commit [op] was applied: record it, with the Welcomes it now owes, and tell its call. */
    private fun confirm(
        gid: String,
        slot: Slot,
        op: PendingEpochOperation,
        cursor: Long,
        emit: (Delivery) -> Unit,
    ) {
        val inbox = sync.inbox(gid)
        inbox.update(op.copy(status = PendingEpochOperation.Status.CONFIRMED, landedAt = cursor))
        inbox.noteCurrentEpochReadable()
        if (inFlight[op.sealedBase64] != null) inFlight[op.sealedBase64] = CommitOutcome.APPLIED
        afterEpochChange(gid, slot, cursor, emit)
        emit(Delivery.EpochAdvanced(gid, cursor, slot.group.epoch))
    }

    /**
     * After the group moved to a new epoch: a Commit of ours still waiting from an older epoch
     * can never apply now (another Commit took its epoch), so it is settled as lost here, once,
     * instead of when or if its echo shows up. Records too old to matter are dropped.
     */
    private fun afterEpochChange(
        gid: String,
        slot: Slot,
        cursor: Long,
        emit: (Delivery) -> Unit,
    ) {
        val epoch = slot.group.epoch
        val inbox = sync.inbox(gid)
        inbox.pending().filter { !it.localStateApplied && it.fromEpoch != null && it.fromEpoch < epoch }.forEach {
            settleLost(gid, it, keepForLateEcho = false, cursor = cursor, emit = emit)
        }
        lostStaged.snapshot().forEach { (sealed, late) -> if (late.gid == gid && late.staged.fromEpoch < epoch) lostStaged.remove(sealed) }
        inbox.pruneLost(epoch, MlsGroup.RETAIN_EPOCHS)
        // Derived now, under the lock, so [mediaKeyFor] reads the new epoch's key without one.
        keysOf(slot)
    }

    /** Applies a handshake [opened] at the current epoch; true when it was a Commit and applied. */
    private fun applyHandshake(
        group: MlsGroup,
        opened: ByteArray,
    ): Boolean =
        when (MlsMessage.decodeTls(TlsReader(opened)).wireFormat) {
            WireFormat.PUBLIC_MESSAGE -> {
                group.processFramedCommit(opened)
                true
            }

            else -> {
                group.decrypt(opened).contentType == ContentType.COMMIT
            }
        }

    /** Whether [opened] is a handshake (a Commit or a proposal) rather than an application message. */
    private fun isHandshake(opened: ByteArray): Boolean =
        try {
            val message = MlsMessage.decodeTls(TlsReader(opened))
            when (message.wireFormat) {
                WireFormat.PUBLIC_MESSAGE -> true
                WireFormat.PRIVATE_MESSAGE -> PrivateMessage.decodeTls(TlsReader(message.payload)).contentType != ContentType.APPLICATION
                else -> false
            }
        } catch (e: Exception) {
            false
        }

    private fun ingestPayload(
        gid: String,
        slot: Slot,
        cursor: Long,
        sealed: String,
        receivedAt: Long?,
        emit: (Delivery) -> Unit,
    ) {
        val inbox = sync.inbox(gid)
        val payload = runCatching { SealedPayload.decode(sealed) }.getOrNull()
        if (payload == null) {
            inbox.noteUnopenable()
            return emit(Delivery.Undecryptable(gid, cursor, "not a sealed payload"))
        }

        // Our current epoch key opens both an application message sent at
        // this epoch and a Commit leaving it, which is the same key by
        // construction (spec/03.md §5).
        val keys = keysOf(slot)
        val opened = runCatching { SealedPayload.open(payload, keys.payload) }.getOrNull()
        if (opened == null) return ingestFromFormerEpoch(gid, slot, cursor, payload, receivedAt, emit)

        val group = slot.group
        val epoch = group.epoch
        try {
            when (MlsMessage.decodeTls(TlsReader(opened)).wireFormat) {
                // Public-framed handshake: what this engine emits, and what
                // Marmot uses throughout.
                WireFormat.PUBLIC_MESSAGE -> {
                    group.processFramedCommit(opened)
                    // Read and applied at our epoch: proof that this device is on the group's line.
                    inbox.noteCurrentEpochReadable()
                    afterEpochChange(gid, slot, cursor, emit)
                    emit(Delivery.EpochAdvanced(gid, cursor, group.epoch))
                }
                // Private-framed: what cordn's reference client emits, for both
                // application messages and handshake traffic.
                else -> {
                    val decrypted = group.decrypt(opened)
                    inbox.noteCurrentEpochReadable()
                    when (decrypted.contentType) {
                        ContentType.APPLICATION -> {
                            // spec/02.md §5: the sender is who the leaf's credential names, not
                            // whatever the AAD claims. A leaf that read at this epoch is a member.
                            val received = CordnApplicationMessage.open(decrypted, identitiesOf(slot)[decrypted.senderLeafIndex])
                            // The key that sealed its attachments is the epoch's it was read at.
                            val delivered = CordnDeliveredMessage(received.envelope, cursor, keys.media, receivedAt)
                            slot.pendingMessages.addLast(delivered)
                            emit(Delivery.Message(gid, cursor, received, keys.media, receivedAt))
                        }
                        // decrypt() applies a Commit, so the epoch has moved already.
                        else -> {
                            if (group.epoch != epoch) afterEpochChange(gid, slot, cursor, emit)
                            emit(Delivery.EpochAdvanced(gid, cursor, group.epoch))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Sealed for our epoch, so from a member who was on it, and still refused: a Commit
            // like that is the strongest evidence of a change we missed (cordn-web clears its
            // staleMark only on a successful read). An application message that fails says
            // nothing about the epoch.
            if (isHandshake(opened)) {
                inbox.noteUnopenable()
                Log.w(TAG, "a commit sealed for $gid's epoch $epoch did not apply", e)
            }
            // The cursor has already advanced past it. Reporting rather than
            // throwing is what keeps one bad payload from stalling every other
            // group in the same page.
            emit(Delivery.Undecryptable(gid, cursor, e.message ?: "could not open payload"))
        }
    }

    /**
     * A payload our current epoch key did not open: tried under the payload keys of the epochs
     * MLS still retains, newest first (cordn-web 0.5.2 `0e12afb`; see
     * [SealedPayload.formerApplicationKeys]).
     *
     * The ordinary case is a member who had not yet seen the latest Commit when they sent, which
     * happens around every Commit, ours or theirs. Before, the cursor moved past those messages
     * and they were lost for good.
     *
     * Opening one is **not** liveness: it proves the sender was behind, not that we are current,
     * so it leaves the [GroupInbox.unopenable] count alone. A payload no key opens raises it.
     *
     * A sender a later epoch removed is refused: they still hold the epoch they were removed from,
     * and would otherwise keep posting into the group until it is [MlsGroup.RETAIN_EPOCHS]
     * Commits further on. cordn-web shows these; see the plan's divergence note.
     */
    private fun ingestFromFormerEpoch(
        gid: String,
        slot: Slot,
        cursor: Long,
        payload: ByteArray,
        receivedAt: Long?,
        emit: (Delivery) -> Unit,
    ) {
        val group = slot.group
        val keys = keysOf(slot)
        val formerKeys = keys.former ?: SealedPayload.formerApplicationKeys(group).also { keys.former = it }
        val former =
            formerKeys.firstNotNullOfOrNull { (epoch, key) ->
                runCatching { SealedPayload.open(payload, key) }.getOrNull()?.let { epoch to it }
            }
        if (former == null) {
            sync.inbox(gid).noteUnopenable()
            return emit(Delivery.Undecryptable(gid, cursor, "no epoch key this device holds opens it"))
        }

        val (epoch, opened) = former
        try {
            // Application messages only: a Commit from a former epoch is one
            // that lost to the Commit we applied, and MLS refuses it.
            val decrypted = group.decryptFormerEpoch(opened)
            // A member of the epoch it was sealed at, by that epoch's tree; and still holding that
            // leaf now, or it is someone a later Commit removed (or whose leaf went to someone else).
            val sealedBy = keys.formerIdentities.getOrPut(epoch) { CordnCredential.membersAt(group, epoch) }?.get(decrypted.senderLeafIndex)
            val received = CordnApplicationMessage.open(decrypted, sealedBy)
            if (identitiesOf(slot)[received.senderLeafIndex] != received.sender) {
                return emit(Delivery.Undecryptable(gid, cursor, "sealed at former epoch $epoch by a sender no longer in the group"))
            }
            val mediaKey = keys.formerMedia.getOrPut(epoch) { CordnMediaEncryption.mediaKeyAt(group, epoch) }
            slot.pendingMessages.addLast(CordnDeliveredMessage(received.envelope, cursor, mediaKey, receivedAt))
            emit(Delivery.Message(gid, cursor, received, mediaKey, receivedAt))
        } catch (e: Exception) {
            emit(Delivery.Undecryptable(gid, cursor, "sealed at former epoch $epoch: ${e.message ?: "could not open payload"}"))
        }
    }

    /** Who holds each leaf at [slot]'s current epoch, read once per epoch. */
    private fun identitiesOf(slot: Slot): Map<Int, HexKey> {
        val keys = keysOf(slot)
        return keys.identities ?: CordnCredential.membersOf(slot.group).also { keys.identities = it }
    }

    /** [slot]'s keys for the epoch it is on, derived once per epoch. */
    private fun keysOf(slot: Slot): EpochKeys {
        val group = slot.group
        slot.keys?.takeIf { it.epoch == group.epoch }?.let { return it }
        return EpochKeys(group.epoch, SealedPayload.applicationKey(group), CordnMediaEncryption.mediaKey(group)).also { slot.keys = it }
    }

    // ---- sharing and disclosure -----------------------------------------

    /** The `cordn1…` ref to share this group, pointing at this coordinator. */
    fun shareRef(gid: String): CordnGroupRef {
        requireGroup(gid)
        return CordnGroupRef(
            gid = gid,
            coordinatorPubKey = config.pubKey,
            relays = config.relays.map { it.url },
        )
    }

    /**
     * What this coordinator learns about [gid]. See [GroupExposure].
     *
     * Every field is read back from durable state rather than from what this
     * process happens to have watched. The distinction is the whole point of
     * the disclosure: a coordinator does not forget at app restart, so an
     * exposure surface that resets to a cheerful default at launch is not a
     * softer version of the truth, it is the wrong answer.
     *
     * @param publishedKeyPackage whether this account has a KeyPackage
     *   published here, which only whoever holds the KeyPackage store can say
     *   (§8.4). `CordnSession.exposure` supplies it; the default is the
     *   weaker "at least what this session did".
     */
    suspend fun exposure(
        gid: String,
        publishedKeyPackage: Boolean = this.publishedKeyPackage,
    ): GroupExposure {
        requireGroup(gid)
        return GroupExposure(
            coordinator = config.pubKey,
            linkedGroupCount = groups.size(),
            // §8.1: a join request names the asker's real npub against a
            // specific group. Persisted at the moment it happens, because it
            // is a thing the coordinator now knows permanently.
            joinedFromShareLink = store.loadJoinedViaRequest(gid),
            publishedKeyPackage = publishedKeyPackage,
            // :contextvm's CvmGiftWrap defaults to REQUIRED and CoordinatorClient
            // does not undo it, so this is a fact about our transport, not a
            // setting. It is reported rather than assumed so that a future
            // configurable transport cannot quietly make it false (§8.6).
            encryptionPinned = true,
        )
    }

    /** Publishes a KeyPackage so others can add this account. §8.4 applies. */
    suspend fun publishKeyPackage(
        keyPackageRef: String,
        keyPackageBase64: String,
    ) = call { coordinator.publishKeyPackage(keyPackageRef, keyPackageBase64) }
        .also { publishedKeyPackage = true }

    /**
     * The screen-side state saved for [gid] — an unsent draft and a read
     * position. Neither is protocol; both are encrypted anyway, because a
     * draft is the plaintext of a message that was about to be sealed.
     */
    suspend fun roomState(gid: String): CordnRoomState = store.loadRoomState(gid)

    suspend fun saveRoomState(
        gid: String,
        state: CordnRoomState,
    ) = store.saveRoomState(gid, state)

    // ---- plumbing --------------------------------------------------------

    private fun requireSlot(gid: String): Slot = groups[gid] ?: throw CordnGroupException("not a member of $gid")

    private fun requireGroup(gid: String): MlsGroup = requireSlot(gid).group

    private fun gidList(): List<String> = groups.snapshot().keys.toList()

    /** Runs [block] holding [gid]'s lock. Not reentrant: nothing called under it takes it again. */
    private suspend fun <T> withGroup(
        gid: String,
        block: suspend () -> T,
    ): T =
        lockOf(gid).withLock {
            if (closed) throw CordnGroupException("this manager was closed; use the coordinator's current session")
            block()
        }

    private fun lockOf(gid: String): Mutex = groupLocks.getOrPut(gid) { Mutex() }

    /**
     * Runs [block] while nothing on this manager touches [gid]: no save, no ingest, no Commit.
     *
     * For a reader of [gid]'s stores outside this manager (a backup, a handoff:
     * [CordnLocalState.read]), so it never sees one save half done. [block] must not call back
     * into this manager for [gid]; the lock is not reentrant.
     */
    suspend fun <T> whileIdle(
        gid: String,
        block: suspend () -> T,
    ): T = lockOf(gid).withLock { block() }

    /**
     * Stops this manager for good, leaving everything it held saved.
     *
     * A second manager over the same store (what [CordnCoordinatorRegistry] builds when a
     * coordinator's relays change) must not start while this one can still write: its restore
     * would read state this one had not saved yet, and this one's last save (a subscription's
     * `finally`, which survives cancellation) would then land over the newer one's, rolling a
     * group's ratchet back. So each group's lock is taken in turn: an operation already running
     * finishes first, one still waiting refuses, and what changed is saved. Every later call that
     * touches a group throws, and the save at the end of a subscription does nothing.
     */
    suspend fun close() {
        closed = true
        gidList().forEach { gid -> lockOf(gid).withLock { persistQuietly(gid) } }
    }

    /**
     * Writes what changed of [gid] since the last write. Runs under [gid]'s lock.
     *
     * The order is the crash story:
     *
     * 1. **The echo record first.** It can only run ahead of the rest, which is the safe
     *    direction: a Commit it calls confirmed while the saved group still holds it staged is
     *    merged again when its echo is delivered again, and a lost one is still recognised.
     *    Records it drops are only those the *saved* cursor has passed.
     * 2. **The MLS state**, only when it changed: it is the largest write by far, and most saves
     *    (a catch-up that found nothing, a send's record) do not touch it.
     * 3. **Messages before the cursor.** Crash between the two and the message is on disk while
     *    the cursor still points before it: the next catch-up re-delivers it and
     *    appendMessage's dedup drops it, costing nothing. The other order loses the message
     *    permanently — both cordn seal keys are epoch-derived, so the copy the coordinator still
     *    holds can no longer be opened, and the cursor would not offer it again anyway.
     * 4. **The cursor last**, never ahead of the state that read up to it.
     */
    private suspend fun persist(gid: String) {
        val slot = groups[gid] ?: return
        val inbox = sync.inbox(gid)

        val echoes = inbox.echoes()
        if (echoes != slot.savedEcho) {
            store.saveEchoState(gid, echoes)
            slot.savedEcho = echoes
        }

        if (slot.dirty) {
            store.saveGroup(gid, slot.group.saveState().encodeTls())
            slot.dirty = false
        }

        while (slot.pendingMessages.isNotEmpty()) {
            store.appendMessage(gid, slot.pendingMessages.first())
            slot.pendingMessages.removeFirst()
        }

        val cursor = inbox.cursor
        if (cursor != slot.savedCursor) {
            store.saveCursor(gid, cursor)
            slot.savedCursor = cursor
        }
        inbox.markPersisted(cursor)
    }

    /**
     * [persist] where a failure must not fail the caller: what did not save stays marked and the
     * next save writes it. Logged, because a store that keeps failing is a device losing progress.
     */
    private suspend fun persistQuietly(gid: String) {
        try {
            persist(gid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not save $gid; the next save retries", e)
        }
    }

    /** Every message this group has stored, oldest first. */
    suspend fun storedMessages(gid: String): List<CordnDeliveredMessage> = store.loadMessages(gid)

    /** The newest stored message and the count, without reading the log. */
    suspend fun storedMessageSummary(gid: String): CordnMessageSummary? = store.loadMessageSummary(gid)

    /** Saves every group, each under its own lock; one that fails does not stop the others. */
    private suspend fun persistAll() {
        // A closed manager saved everything at close, and the store may already be another's.
        if (closed) return
        gidList().forEach { gid -> withGroup(gid) { persistQuietly(gid) } }
    }

    /** Runs a coordinator call, recording the outcome against [health]. */
    private suspend fun <T> call(block: suspend () -> T): T =
        try {
            block().also { health.recordSuccess(clock()) }
        } catch (e: Exception) {
            health.recordFailure(clock(), e.message)
            throw e
        }

    companion object {
        private const val TAG = "CordnGroupManager"

        /** `spec/02.md` §6: a cordn chat message is a NIP-C7 kind 9. */
        const val CHAT_KIND = 9

        /**
         * A fresh `gid`: a random UUID, which is what cordn's own client uses.
         *
         * The `gid` is the creator's to choose (`spec/00.md` §4) and the coordinator never
         * interprets it. The one hard rule is that it must not be derived from the MLS
         * `group_id`, which is secret.
         */
        @OptIn(ExperimentalUuidApi::class)
        fun newGid(): String = Uuid.random().toString()

        // Why a Welcome was left in the inbox. Each is actionable and each
        // reaches a user, so they are constants rather than strings written at
        // the throw site: a reason nobody can act on is just an error message.

        /** The Welcome is for another device of this account. Leave it alone. */
        const val NO_PRIVATE_HALF = "no KeyPackage private half for this ref"

        /** Opened, but it does not say where to fetch this group's stream from. */
        const val UNKNOWN_GID = "cannot tell which delivery group this Welcome is for"

        /** A stale invitation to a group we are already in. Nothing to decide. */
        const val ALREADY_A_MEMBER = "already a member of this group"

        /** A Welcome to a group saved on this device that could not be read ([unrestorable]). */
        const val UNRESTORABLE_GROUP = "this group is saved here but could not be read"

        /**
         * The delivery `gid` a Welcome implies, or null when it implies none.
         *
         * **A Welcome does not carry the `gid`.** It carries the MLS
         * `group_id`, and `spec/03.md` §2 is explicit that the two are
         * decoupled — the coordinator's delivery id is not the MLS group id and
         * must not be assumed to be. So in principle a joiner has no way to
         * learn where to fetch from, and needs the `cordn1…` ref out of band.
         *
         * In practice the reference client closes that gap by convention:
         * cordn-web sets `group_id = utf8(gid)` when it creates a group and
         * reads the `gid` straight back out of the group context on join
         * (`chatGroupLifecycle.svelte.ts`). Verified against staircase's
         * fixtures, whose `group_id` decodes to exactly their published `gid`.
         *
         * This follows that convention — [createGroup] writes it and this reads
         * it — while treating it as what it is: an observation about one
         * implementation, not a guarantee. A group id that is not valid UTF-8
         * cannot be a `gid`, and a conformant peer is free to produce one, so
         * the answer is null and the caller is told rather than handed a
         * mojibake key that would quietly fetch nothing forever. Pass `gidFor`
         * to supply the id from a share ref instead.
         */
        fun gidFrom(group: MlsGroup): String? =
            try {
                group.groupId.decodeToString(throwOnInvalidSequence = true).takeIf { it.isNotEmpty() }
            } catch (e: CharacterCodingException) {
                null
            }
    }
}

/**
 * Something went wrong that is this manager's to explain, not MLS's.
 *
 * [reason] exists so a screen can say what happened in its own words. The
 * [message] is written for a log — it names pubkeys and gids in full, which is
 * what you want when reading one and never what you want in a chat — so a UI
 * that rendered `e.message` showed a person who had just tapped a face a
 * 64-character hex string. Branching on the reason lets it name the person
 * instead, without matching on English that is free to change.
 */
class CordnGroupException(
    message: String,
    val reason: Reason = Reason.OTHER,
    cause: Throwable? = null,
) : IllegalStateException(message, cause) {
    enum class Reason {
        /** Nobody has published a KeyPackage for this person to this coordinator. */
        NO_KEY_PACKAGE,

        /** The coordinator served a KeyPackage belonging to somebody else. */
        WRONG_KEY_PACKAGE_OWNER,

        /** The add produced no Welcome, so the invitee could never open the group. */
        NO_WELCOME,

        /** The person named is not in this group. */
        NOT_A_MEMBER,

        /**
         * This device could not open recent traffic, or a Commit at its epoch would not apply, so
         * it may have missed a change, and a change made from here could split the group. Nothing
         * was changed. It clears once a payload sealed for the group's current epoch is read.
         */
        BEHIND,

        /**
         * Somebody else changed the group at the same moment and the coordinator ordered their
         * change first. Ours did not happen and nothing was sent for it (no Welcome); the group
         * is on their change now. Retryable.
         */
        COMMIT_LOST,

        /**
         * The change was sent but the coordinator has not confirmed it, so it may or may not
         * happen. Never re-sent blindly: the stream settles it, and the next change waits for
         * that.
         */
        COMMIT_UNCONFIRMED,

        /** Anything with no better wording than the message itself. */
        OTHER,
    }
}

/**
 * A Welcome that has been opened but not joined.
 *
 * Everything on it was read out of the Welcome itself, so it is what can be
 * shown before a decision is made. [group] is the MLS group the Welcome
 * produces; holding it is why [CordnGroupManager.accept] does not have to
 * re-open the Welcome and spend the KeyPackage twice.
 */
class OpenedWelcome internal constructor(
    val gid: String,
    val keyPackageRef: String,
    val at: Long,
    val metadata: CordnGroupMetadata?,
    /** Who is already in the group. Not the inviter — see [CordnGroupManager.pendingWelcomes]. */
    val members: Set<HexKey>,
    val epoch: Long,
    internal val group: MlsGroup,
    internal val after: Long?,
)

/** Every pending Welcome, split into the ones that opened and the ones that did not. */
data class WelcomeInbox(
    val pending: List<OpenedWelcome>,
    val skipped: List<SkippedWelcome>,
)

/** What [CordnGroupManager.joinPendingWelcomes] did with each pending Welcome. */
data class JoinResults(
    val joined: List<String>,
    /**
     * Welcomes left pending, with why. Not an error list: a Welcome for a
     * KeyPackage this device never held belongs to another device of the same
     * account, and draining it here would destroy it.
     */
    val skipped: List<SkippedWelcome>,
)

/** One Welcome that stayed in the inbox, and the reason. */
data class SkippedWelcome(
    val keyPackageRef: String,
    val reason: String,
)

/** The result of adding a member: where the Commit and the Welcome landed. */
data class InviteResult(
    val gid: String,
    val invited: HexKey,
    /**
     * The KeyPackage this invite consumed.
     *
     * Worth reporting rather than swallowing: a KeyPackage is one-time, so
     * this names something the invitee can no longer be invited with by
     * anyone else. It is also the handle their client needs to find the
     * Welcome we just left them.
     */
    val keyPackageRef: String,
    val commitCursor: Long,
    val welcomeAt: Long,
)

/** What [CordnGroupManager.removeMember] did. */
data class RemovalResult(
    val gid: String,
    val removed: HexKey,
    /** Where the coordinator filed the Remove commit. */
    val cursor: Long,
)

/** What [CordnGroupManager.updateGroupMetadata] did. */
data class MetadataUpdateResult(
    val gid: String,
    /** Where the coordinator filed the metadata commit. */
    val cursor: Long,
)

/**
 * What one [CordnGroupManager.inviteAll] did, per person.
 *
 * Three outcomes rather than success-or-throw, because each needs a different
 * answer: [refused] never reached the group and can be retried or replaced with
 * a share link; [undelivered] are in the group already and need only the Welcome,
 * which the manager keeps and stores on a later sync; [invited] are done.
 */
data class BatchInviteResult(
    val gid: String,
    val invited: List<InviteResult>,
    val undelivered: Map<HexKey, Throwable>,
    val refused: Map<HexKey, CordnGroupException>,
) {
    /** One row per person in [roster], in its order: what to show for "three of five went out". */
    fun outcomes(roster: List<HexKey>): List<CordnInviteOutcome> =
        roster.map { target ->
            val undelivered = undelivered[target]
            CordnInviteOutcome(
                pubKey = target,
                failure = refused[target] ?: undelivered,
                joinedWithoutWelcome = undelivered != null,
            )
        }
}

/**
 * One invitation attempt, in one of three states.
 *
 * [sent] is done. A failure with [joinedWithoutWelcome] means the commit that added them went
 * through and only the Welcome did not, so they are a member who cannot join yet: resending
 * reaches them. A failure without it never touched the group, so there is nothing to resend and a
 * share link is the only way to them.
 */
data class CordnInviteOutcome(
    val pubKey: HexKey,
    val failure: Throwable?,
    val joinedWithoutWelcome: Boolean = false,
) {
    val sent: Boolean get() = failure == null
}
