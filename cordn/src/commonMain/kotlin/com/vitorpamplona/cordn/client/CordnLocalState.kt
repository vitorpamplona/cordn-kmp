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

import com.vitorpamplona.cordn.spec02Envelopes.CordnDeliveredMessage
import com.vitorpamplona.cordn.sync.EchoState
import com.vitorpamplona.cordn.sync.GroupCursor
import com.vitorpamplona.quartz.nip01Core.core.HexKey

/**
 * Everything one account holds for its coordinators, read off its stores: what a backup
 * ([CordnBackup]) and a device handoff (`CordnMigrationSnapshot`) both carry.
 *
 * Read from the stores rather than from any open session, so a coordinator whose session failed
 * to open today is still carried: a backup or a handoff that silently left out the groups the app
 * could not reach would be wrong precisely when it is needed.
 *
 * One reader and one writer, so the two carriers cannot drift apart in what they keep or in the
 * order they lay it back down.
 */
class CordnLocalState(
    val groups: List<Group>,
    val keyPackages: List<KeyPackage>,
) {
    class Group(
        val coordinatorPubKey: HexKey,
        val gid: String,
        /** The serialized MLS client state. */
        val state: ByteArray,
        val cursor: GroupCursor?,
        /** Null when the carrier has none (a backup does not keep it); nothing is written then. */
        val roomState: CordnRoomState?,
        /** Null when the carrier has none; nothing is written then. */
        val echoState: EchoState?,
        val joinedViaRequest: Boolean,
        /** The conversation, oldest first. */
        val messages: List<CordnDeliveredMessage>,
    )

    class KeyPackage(
        val coordinatorPubKey: HexKey,
        val keyPackageRef: String,
        /** The serialized KeyPackageBundle, private half included. */
        val bundle: ByteArray,
    )

    /** One coordinator's stores, as the app opens them. */
    class Stores(
        val groups: CordnGroupStore,
        val keyPackages: CordnKeyPackageStore,
    )

    companion object {
        /** The 1.6 signature, kept so code compiled against it still links. */
        @Deprecated("Use the overload with a guard.", level = DeprecationLevel.HIDDEN)
        suspend fun read(
            coordinators: List<HexKey>,
            storesFor: suspend (coordinatorPubKey: HexKey) -> Stores,
        ): CordnLocalState = read(coordinators, guard = { _, _, read -> read() }, storesFor = storesFor)

        /**
         * Reads every group and KeyPackage held for [coordinators].
         *
         * [guard] runs around each group's reads. While a manager of that coordinator is open it
         * may be saving the same group at that moment, and a read that took the state from before
         * a save and the cursor from after it restores into a device that skips the Commit in
         * between forever. Pass [CordnCoordinatorRegistry.whileIdle], which holds the group's lock
         * so no save can interleave.
         *
         * Without a lock the reads still go newest-written-last first: the cursor, the messages,
         * the state, then the echo record, the reverse of the order a save writes them in. A save
         * landing between two reads can then only leave a later item ahead of an earlier one: the
         * state ahead of its cursor (the next catch-up re-reads what it already applied, and a
         * message saved meanwhile may be missing), or the echo record ahead of the state (the safe
         * direction, see `CordnGroupManager.persist`). Never the cursor ahead of the state.
         */
        suspend fun read(
            coordinators: List<HexKey>,
            guard: suspend (coordinatorPubKey: HexKey, gid: String, read: suspend () -> Unit) -> Unit = { _, _, read -> read() },
            storesFor: suspend (coordinatorPubKey: HexKey) -> Stores,
        ): CordnLocalState {
            val groups = mutableListOf<Group>()
            val keyPackages = mutableListOf<KeyPackage>()
            coordinators.forEach { coordinator ->
                val stores = storesFor(coordinator)
                stores.groups.listGroups().forEach { gid ->
                    guard(coordinator, gid) {
                        val store = stores.groups
                        val cursor = store.loadCursor(gid)
                        val messages = store.loadMessages(gid)
                        val state = store.loadGroup(gid) ?: return@guard
                        groups +=
                            Group(
                                coordinatorPubKey = coordinator,
                                gid = gid,
                                state = state,
                                cursor = cursor,
                                roomState = store.loadRoomState(gid),
                                echoState = store.loadEchoState(gid),
                                joinedViaRequest = store.loadJoinedViaRequest(gid),
                                messages = messages,
                            )
                    }
                }
                stores.keyPackages.list().forEach { ref ->
                    val bundle = stores.keyPackages.load(ref) ?: return@forEach
                    keyPackages += KeyPackage(coordinator, ref, bundle)
                }
            }
            return CordnLocalState(groups, keyPackages)
        }

        /**
         * Writes [state] into the stores [storesFor] opens.
         *
         * Into empty stores: clearing what was there is the caller's, because only it knows
         * where its files live, and **replacing is not merging**. Two devices holding one group's
         * state and both committing fork the ratchet tree, and MLS does not recover.
         *
         * The conversation goes in before the cursor. A write that stored the cursor and then
         * failed would leave a device holding a cursor past messages it never wrote, with no way
         * to ask for them again.
         */
        suspend fun write(
            state: CordnLocalState,
            storesFor: suspend (coordinatorPubKey: HexKey) -> Stores,
        ) {
            state.groups.forEach { group ->
                val store = storesFor(group.coordinatorPubKey).groups
                store.saveGroup(group.gid, group.state)
                group.messages.forEach { store.appendMessage(group.gid, it) }
                group.cursor?.let { store.saveCursor(group.gid, it) }
                group.roomState?.let { store.saveRoomState(group.gid, it) }
                group.echoState?.let { store.saveEchoState(group.gid, it) }
                if (group.joinedViaRequest) store.saveJoinedViaRequest(group.gid)
            }
            state.keyPackages.forEach { keyPackage ->
                storesFor(keyPackage.coordinatorPubKey).keyPackages.save(keyPackage.keyPackageRef, keyPackage.bundle)
            }
        }
    }
}
