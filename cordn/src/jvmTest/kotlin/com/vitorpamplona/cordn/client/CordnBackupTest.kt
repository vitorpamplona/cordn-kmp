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
import com.vitorpamplona.cordn.spec02Envelopes.CordnEnvelope
import com.vitorpamplona.cordn.sync.EchoState
import com.vitorpamplona.cordn.sync.GroupCursor
import com.vitorpamplona.cordn.sync.PendingEpochOperation
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class CordnBackupTest {
    private val account = "a".repeat(64)
    private val coordinator = "b".repeat(64)

    /** A cheap cost: these tests exercise the format, not the KDF's strength. */
    private val cheap = 10

    private val archive =
        CordnBackup.Archive(
            accountPubKey = account,
            coordinators =
                listOf(
                    CoordinatorConfig(
                        coordinator,
                        listOf(RelayUrlNormalizer.normalizeOrNull("wss://one.example.com")!!),
                        CoordinatorConfig.Origin.MANUAL,
                        "Work",
                    ),
                ),
            groups =
                listOf(
                    CordnBackup.Archive.Group(
                        coordinatorPubKey = coordinator,
                        gid = "room-1",
                        state = byteArrayOf(1, 2, 3, 4),
                        cursor = GroupCursor(fetchCursor = 7, lastCursor = 9),
                        joinedViaRequest = true,
                        messages = listOf(delivered("hello", 7), delivered("again", 8)),
                    ),
                    CordnBackup.Archive.Group(
                        coordinatorPubKey = coordinator,
                        gid = "room-2",
                        state = byteArrayOf(5, 6),
                        cursor = null,
                        joinedViaRequest = false,
                    ),
                ),
            keyPackages =
                listOf(
                    CordnBackup.Archive.KeyPackage(coordinator, "kp-ref", byteArrayOf(9, 9, 9)),
                ),
        )

    @Test
    fun `an archive round-trips`() {
        val opened = CordnBackup.open(CordnBackup.seal(archive, "correct horse", cheap), "correct horse")

        assertEquals(archive, opened)
    }

    @Test
    fun `a group with no cursor stays a group with no cursor`() {
        // The optional field is the one a length-prefixed format gets wrong by
        // writing a zero and reading it back as a real position, which would
        // make a restored room resume from the start of its history.
        val opened = CordnBackup.open(CordnBackup.seal(archive, "pw", cheap), "pw")

        assertEquals(null, opened.groups.single { it.gid == "room-2" }.cursor)
        assertEquals(
            7L,
            opened.groups
                .single { it.gid == "room-1" }
                .cursor
                ?.fetchCursor,
        )
    }

    @Test
    fun `the wrong passphrase fails, and says nothing about how wrong`() {
        val sealed = CordnBackup.seal(archive, "correct horse", cheap)

        assertFailsWith<Exception> { CordnBackup.open(sealed, "correct hors") }
    }

    @Test
    fun `rewriting the KDF cost down does not weaken the file`() {
        // The attack this refuses: edit logN to something trivially cheap and
        // brute-force against that instead of the real key. It fails because
        // logN feeds the derivation, so a changed cost is a changed key --
        // not because the header is under the AEAD, which is defence in depth
        // for header fields this format does not have yet.
        val sealed = CordnBackup.seal(archive, "pw", cheap)
        val weakened = sealed.copyOf().also { it[MAGIC_LENGTH + 2] = 1 }

        assertFailsWith<Exception> { CordnBackup.open(weakened, "pw") }
    }

    @Test
    fun `a salt swapped between two files does not open either`() {
        val mine = CordnBackup.seal(archive, "pw", cheap)
        val theirs = CordnBackup.seal(archive, "pw", cheap)
        val spliced = mine.copyOf().also { theirs.copyOfRange(SALT_AT, SALT_AT + 16).copyInto(it, SALT_AT) }

        assertFailsWith<Exception> { CordnBackup.open(spliced, "pw") }
    }

    @Test
    fun `a truncated file fails rather than restoring half a group`() {
        val sealed = CordnBackup.seal(archive, "pw", cheap)

        assertFailsWith<Exception> { CordnBackup.open(sealed.copyOf(sealed.size - 8), "pw") }
    }

    @Test
    fun `something that is not a backup is refused by name`() {
        val notABackup = ByteArray(200) { it.toByte() }

        val failure = assertFailsWith<IllegalArgumentException> { CordnBackup.open(notABackup, "pw") }
        assertTrue(failure.message!!.contains("not a cordn backup"))
    }

    @Test
    fun `MLS state and key material never appear in the clear`() {
        // The state blob is a ratchet tree and the bundle is private key
        // material. A backup that leaked either is worse than no backup.
        val sealed = CordnBackup.seal(archive, "pw", cheap)

        assertFalse(sealed.asList().windowed(4).any { it == listOf<Byte>(1, 2, 3, 4) }, "group state in the clear")
        assertFalse(sealed.asList().windowed(3).any { it == listOf<Byte>(9, 9, 9) }, "key material in the clear")
        assertFalse(sealed.decodeToString().contains("room-1"), "a gid in the clear")
    }

    @Test
    fun `two seals of one archive differ, so a file never reveals a repeat`() {
        val first = CordnBackup.seal(archive, "pw", cheap)
        val second = CordnBackup.seal(archive, "pw", cheap)

        assertFalse(first.contentEquals(second), "a fresh salt and nonce per seal")
    }

    @Test
    fun `an unreasonable cost is refused instead of hanging the device`() {
        assertFailsWith<IllegalArgumentException> { CordnBackup.seal(archive, "pw", logN = 40) }
    }

    /**
     * The id has to hash the contents: `CordnEnvelope.fromJsonObject` checks
     * it, so an envelope with a made-up id decodes to null and the archive
     * appears to have lost the message.
     */
    private fun delivered(
        content: String,
        cursor: Long,
    ): CordnDeliveredMessage {
        val unsigned =
            CordnEnvelope(
                id = "",
                pubKey = account,
                createdAt = 1_700_000_000L + cursor,
                kind = 9,
                tags = arrayOf(arrayOf("h", "room-1")),
                content = content,
            )
        return CordnDeliveredMessage(unsigned.copy(id = unsigned.computedId()), cursor)
    }

    @Test
    fun `messages survive the round trip`() {
        val opened = CordnBackup.open(CordnBackup.seal(archive, "pw", cheap), "pw")

        val room1 = opened.groups.first { it.gid == "room-1" }
        assertEquals(listOf("hello", "again"), room1.messages.map { it.envelope.content })
        assertEquals(listOf(7L, 8L), room1.messages.map { it.cursor })
        // The group carrying none still round-trips as none.
        assertEquals(emptyList(), opened.groups.first { it.gid == "room-2" }.messages)
    }

    @Test
    fun `the echo record survives the round trip`() {
        val echo = EchoState(ownMessageCursors = listOf(7, 8), unopenable = 2)
        val withEcho = archive.copy(groups = archive.groups.map { if (it.gid == "room-1") it.copy(echoState = echo) else it })
        val opened = CordnBackup.open(CordnBackup.seal(withEcho, "pw", cheap), "pw")

        assertEquals(echo, opened.groups.first { it.gid == "room-1" }.echoState)
        assertEquals(null, opened.groups.first { it.gid == "room-2" }.echoState)
        assertEquals(
            echo,
            opened
                .toLocalState()
                .groups
                .first { it.gid == "room-1" }
                .echoState,
            "a restore lays it down",
        )
    }

    @Test
    fun `a version 3 file opens with its echo record`() {
        // Sealed by 1.7.0 code (version 3, logN 10, passphrase "frozen"): the archive above,
        // room-1 only, carrying V3_ECHO. Frozen: never regenerate.
        val sealed = Base64.decode(V3_FILE)
        assertEquals(3, ((sealed[MAGIC_LENGTH].toInt() and 0xff) shl 8) or (sealed[MAGIC_LENGTH + 1].toInt() and 0xff))
        val opened = CordnBackup.open(sealed, "frozen")
        val group = opened.groups.single()
        assertEquals("room-1", group.gid)
        assertEquals(GroupCursor(7, 9), group.cursor)
        assertTrue(group.joinedViaRequest)
        assertEquals(listOf("hello", "again"), group.messages.map { it.envelope.content })
        assertEquals(V3_ECHO, group.echoState)
        assertEquals(listOf("kp-ref"), opened.keyPackages.map { it.keyPackageRef })
    }

    @Test
    fun `a version 2 file still opens, with no echo record`() {
        // Sealed by 1.7.0-candidate code (version 2, logN 10, passphrase "frozen"). Frozen: never regenerate.
        val opened = CordnBackup.open(Base64.decode(V2_FILE), "frozen")
        val group = opened.groups.single()
        assertEquals("room-1", group.gid)
        assertEquals(GroupCursor(7, 9), group.cursor)
        assertEquals(null, group.echoState)
        assertEquals(listOf("kp-ref"), opened.keyPackages.map { it.keyPackageRef })
    }

    @Test
    fun `a header asking for more scrypt than any backup uses is refused before the KDF runs`() {
        // logN sits in front of the AEAD check, so an edited header used to cost the device the
        // memory it asked for (2^22: 4 GiB) before the tag could refuse it.
        val sealed = CordnBackup.seal(archive, "pw", cheap)
        sealed[MAGIC_LENGTH + 2] = 19
        val error = assertFailsWith<IllegalArgumentException> { CordnBackup.open(sealed, "pw") }
        assertTrue(error.message!!.contains("scrypt cost"), error.message)
        // The default still opens.
        assertEquals(archive, CordnBackup.open(CordnBackup.seal(archive, "pw"), "pw"))
    }

    private companion object {
        /** The echo record frozen into [V3_FILE]: one of each thing the record carries. */
        val V3_ECHO =
            EchoState(
                pendingCommits =
                    listOf(
                        PendingEpochOperation("c2VhbGVkLXBlbmRpbmc=", localStateApplied = false, fromEpoch = 3),
                        PendingEpochOperation("c2VhbGVkLWxvc3Q=", localStateApplied = false, status = PendingEpochOperation.Status.LOST, fromEpoch = 2, landedAt = 6),
                    ),
                ownMessageCursors = listOf(7, 8),
                unopenable = 2,
            )

        const val MAGIC_LENGTH = 8

        val V3_FILE =
            "Q09SRE5CQUsAAwouDLmbqMq14Kt6o3Kkrj9lUC1d/FVz7WLt972aEczFIGpzVZZa6zJcyWI6rTTmHsUigUtzYRmpDcGfTiHpvLMT" +
                "kNlEON4VDxcAEPe5n6xBKs3iKwF/tKENHtkZoutzIZmR523ETGYfTNc0t52izBzI6sZjLXtgs85mSDQAmeXbjd8HdTw0dZp5ZBnu" +
                "tpliR3udcTgP0Hzvq3EAJGtsvtpuOWraO9CZ0MtPEIIET8mVZ8AJ1jx8iwxN7labEqzBR8GVxz/SZApMXRfFN7QprIyG1ogzRrqY" +
                "ZU9Dc7YQiFA4Dm/iEkh07M/2nycq4smecRach7rGECQyvkgQZVLRUTXWvAG+yTNCV8a9vOCjrYGpiWTqbP0duVfh7dq3htKU0Tdl" +
                "bYI9baSaEI9imlxxpOTy1Ypg35OTUnp7rObEkZS0XnwN8CVcznRoQ3K5CMyErzCwv3gfa6dJ/t25Yk78mhrBn4ZtDfphMJ7OanbV" +
                "gft038SuyeMhLTzixiMx3dMkmqihaa1+CBRQZA/ewRKP/vHqP5o2GSxh8vqxt8NReTO2VQaFvEHx4HphNzxms4jlr5XxzEyc9AkH" +
                "L9QaUkFsAUODxfYL9KYy9rMuGhSNhM0NyOBZCiP8CtHZWwf+Tl6h3xDRgUMWTcvuMLfrOV6ZcRlyC6CjCblT2f1M6YXSl3YrgK2x" +
                "4S7bLMUozXFBzyfamcDNi7DB9102k487HZ8vOoPROELo3VVA2oYsSsbY6oQLgx3TvdhOfhUJAwv68TXCMvF/fxn0ZuwHFW1yihlt" +
                "jmCJN67iHdq+i5dt90VBJKJUdprIu9ufMzIV3myoSoUZqQ9xCOoStRbRVN5CoUtTPiWF1iNaMe4DuaYH3wFVXBiGun0nqNYwzyfb" +
                "CbHdFZbq0vXqW+IIrr9Pyg1paNfvu6O1IWXt6huGbvX/grcF55HyeDtmNPRnXX7ZCzdND20r5LARwc33QL2N221mmMRqYUAiE+/U" +
                "UE2vNx0T1sc/pF8+jzM0uHNRXw5gTZT3sTWc6RKe+saH6Kh/BWzNOVDWFouGa7K004LwYntQj622D3PS3ljPV10RJISM77GTbr4G" +
                "uUAg50DFz/Sp6wbNNi9gWUwXaQoREPgZ4AcMxCT2MIKg4Y4OkswFfd29fast7EfHd4H0TBvW6qLC9eDmYHsFFNE8r7bhc7lPDf9T" +
                "BhbzH4J2KDC2oj3PCfuf+S44sD/Setgud/t1g0Y07d4hB22TgQ2MCq5/xMxVCPWZLXZZMdUvm0/a5dmaiwWZ+9kKbas+3lXsOvl/" +
                "nsBInG8rEyWrOS8zLrNmfK6IOEyuWxw3hFhZjlqovf/3Mdcr9SrhHdTQo5YTr7pUe9CK06PSO8/OuUty1vRj55NEm+8zKkc5gzJO" +
                "bCa2aiZA+G/Cbrr8I4Txpf8b7Us="

        val V2_FILE =
            "Q09SRE5CQUsAAgrats/LV/DVHgpfeQlKTaCIOscr8+HCtlATomhFDunPnUzRV61IJ21kXoSKLlBE4qs9odA+c1B3xXUAuBHgVkdz" +
                "81SWjB41mXnxWUf+xAZtDyRmxGWffBMuZxmBxdQaDInpbuxJxM1bLeEkETvX3Y7RJFFUwJNCw+DWXHEP+Pze/fYhntJxVS6dA/on" +
                "xeEsBfEI85Zem18j567PFhDrA4ZDm9xBfOzV1BEA9/FqRxB2IYeeSA+k8xwya0AhJJ8kVklCwEXHp1CjgfhY4lOvKZelAc5eBoTY" +
                "BRD9w8H24IQTKsqzLWD2/QZvwIcb/6P931HkuTccV//U6zujBTnhdoxq1rcYlYT+AZSlzTKcodkAL+V3rVpEUFsTkJWqVYYLgz+b" +
                "H+A1g5cQDYhTgzpGgoecDm8WrDsLKUJbZKVkq0IHQeKNjoE+VI8mkEKTno/F1Ii0H/Eefa5tBzfg3ZKYaUF+aTG/5ih27ft09Fff" +
                "8/pkZkuMeZ+pChrvmNEqUw3xXKGdRHW+y2WkIW/w2r9A97x6Uvj5ealtMm+L"

        /** magic(8) + version(2) + logN(1). */
        const val SALT_AT = 11
    }
}
