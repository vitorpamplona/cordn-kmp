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
package com.vitorpamplona.cordn.spec01GroupMetadata

import com.vitorpamplona.cordn.groups.CordnGroupPolicy
import com.vitorpamplona.mls.tree.Extension
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `spec/01.md` — the `cordn_group_metadata` extension (`0xC04D`). */
class CordnGroupMetadataTest {
    private val alice = "11".repeat(32)
    private val bob = "22".repeat(32)

    @Test
    fun theLayoutIsUint16LengthsNotMlsVarints() {
        // Hand-derived from spec/01.md §3 rather than round-tripped, because a
        // round trip agrees with itself no matter which length encoding we
        // picked -- and the spec names two incompatible ones (see the codec's
        // KDoc). 14 bytes: version, then five length-prefixed fields.
        //
        //   0001  version = 1
        //   0002 4869   name = "Hi"
        //   0000  description
        //   0000  admin_pubkeys
        //   0000  icon
        //   0000  image_url
        assertEquals(
            "0001000248690000000000000000",
            CordnGroupMetadata(name = "Hi").encode().toHexKey(),
        )
    }

    @Test
    fun adminPubkeysAreConcatenatedRawKeys() {
        val encoded = CordnGroupMetadata(name = "", adminPubkeys = listOf(alice, bob)).encode().toHexKey()
        // version, empty name, empty description, then 64 bytes of admins.
        assertTrue(encoded.startsWith("0001" + "0000" + "0000" + "0040"), "admins field must declare 0x40 = 64 bytes")
        assertTrue(encoded.contains(alice + bob), "admins are raw 32-byte keys back to back, in order")
    }

    @Test
    fun anEmptyAdminListMeansEgalitarianNotBootstrap() {
        // spec/01.md §5.3. Marmot reads an empty admin set as "bootstrap, gate
        // still open"; cordn reads it as a permanent statement that everyone is
        // equal. Same bytes, different meaning -- the reason neither side's
        // authorization code can be reused for the other.
        assertTrue(CordnGroupMetadata(name = "open").isEgalitarian)
        assertTrue(!CordnGroupMetadata(name = "run", adminPubkeys = listOf(alice)).isEgalitarian)
    }

    @Test
    fun versionZeroIsRejected() {
        val v0 = CordnGroupMetadata(name = "Hi").encode().also { it[1] = 0 }
        assertFailsWith<IllegalArgumentException> { CordnGroupMetadata.decode(v0) }
    }

    /**
     * `coordinator_routing` (upstream `feat/coordinator-handoff-spec`, `coordinator-handoff.md`
     * §4): `@cordn/core` 0.6.0 appends it to every metadata it writes, empty or not, and §4 of
     * `spec/01.md` says a decoder ignores trailing fields. Built byte for byte from §4.2's layout:
     * version, the active locator (32-byte key, then its relay URLs), fallbacks, boundary tips.
     */
    private fun routingField(): ByteArray {
        fun u16(n: Int) = byteArrayOf((n shr 8).toByte(), n.toByte())

        fun field(bytes: ByteArray) = u16(bytes.size) + bytes
        val relay = field("wss://a.example".encodeToByteArray())
        val active = ByteArray(32) { 0x11 } + field(relay)
        val fallback = ByteArray(32) { 0x22 } + field(ByteArray(0))
        val tips = field("ab".repeat(32).encodeToByteArray())
        val routing = u16(1) + active + field(fallback) + field(tips)
        return field(routing)
    }

    @Test
    fun aRoutingTrailingFieldStillReadsTheAdmins() {
        // Throwing here is what used to turn an administered group egalitarian:
        // CordnGroupPolicy reads a metadata it cannot decode as "no admins", and
        // would then accept a non-admin's commit every other client rejects.
        val base = CordnGroupMetadata(name = "demo", adminPubkeys = listOf(alice)).encode()
        val decoded = CordnGroupMetadata.decode(base + routingField())
        assertEquals("demo", decoded.name)
        assertEquals(listOf(alice), decoded.adminPubkeys)
    }

    @Test
    fun thePolicyStillSeesTheAdminsBehindARoutingField() {
        val withRouting = CordnGroupMetadata(name = "demo", adminPubkeys = listOf(alice)).encode() + routingField()
        val extension = Extension(CordnGroupMetadata.EXTENSION_TYPE, withRouting)
        assertEquals(setOf(alice), CordnGroupPolicy.adminIdentitiesIn(listOf(extension)))
    }

    @Test
    fun anEmptyRoutingFieldIsWhatEveryNewEncoderWrites() {
        // "Encoders MUST always write the field (empty when no routing state is present)".
        val decoded = CordnGroupMetadata.decode(CordnGroupMetadata(name = "Hi").encode() + byteArrayOf(0, 0))
        assertEquals("Hi", decoded.name)
    }

    @Test
    fun unknownTrailingFieldsFromFutureVersionsAreIgnored() {
        // Upstream's own test: a field appended after routing.
        val future = CordnGroupMetadata(name = "egalitarian").encode() + byteArrayOf(0, 0) + byteArrayOf(0, 3, 9, 9, 9)
        assertEquals("egalitarian", CordnGroupMetadata.decode(future).name)
    }

    @Test
    fun trailingFieldsSurviveAnEditOfTheOthers() {
        // A rename re-encodes the extension. Stripping what follows image_url
        // would erase another client's coordinator routing for everyone.
        val trailing = routingField()
        val decoded = CordnGroupMetadata.decode(CordnGroupMetadata(name = "old", adminPubkeys = listOf(alice)).encode() + trailing)
        val renamed = decoded.copy(name = "new").encode()
        assertEquals(
            CordnGroupMetadata(name = "new", adminPubkeys = listOf(alice)).encode().toHexKey() + trailing.toHexKey(),
            renamed.toHexKey(),
        )
        assertEquals(decoded.copy(name = "new"), CordnGroupMetadata.decode(renamed))
    }

    @Test
    fun theSameMetadataWithoutTrailingFieldsIsADifferentValue() {
        val plain = CordnGroupMetadata(name = "demo")
        assertNotEquals(plain, CordnGroupMetadata.decode(plain.encode() + routingField()))
        assertEquals(plain, CordnGroupMetadata.decode(plain.encode()))
    }

    @Test
    fun aTruncatedFieldIsStillRejected() {
        // Tolerating trailing fields is not tolerating a short payload: a field
        // that claims more bytes than there are is malformed.
        val short = CordnGroupMetadata(name = "Hi").encode().dropLast(1).toByteArray()
        assertFailsWith<IllegalArgumentException> { CordnGroupMetadata.decode(short) }
    }

    @Test
    fun repeatedAdminsFromAPeerAreDedupedNotFatal() {
        // Upstream's decode dedupes (`decodeAdminPubkeys`): a peer's Commit must
        // not fail to read, and failing here read the group as egalitarian.
        val once = CordnGroupMetadata(name = "", adminPubkeys = listOf(alice)).encode().toHexKey()
        val twice = once.replace("0020$alice", "0040$alice$alice")
        assertEquals(listOf(alice), CordnGroupMetadata.decode(twice.hexToByteArray()).adminPubkeys)
    }

    @Test
    fun adminPubkeysNotAMultipleOf32AreRejected() {
        // §9. Silently truncating would drop an admin, which is the direction
        // that fails open.
        val bad = CordnGroupMetadata(name = "", adminPubkeys = listOf(alice)).encode().dropLast(3).toByteArray()
        assertFailsWith<IllegalArgumentException> { CordnGroupMetadata.decode(bad) }
    }

    @Test
    fun invalidUtf8IsRejectedRatherThanReplaced() {
        // §9 says reject. Kotlin's decodeToString() would substitute U+FFFD and
        // hand back a group named something nobody chose.
        // version=1, name length=2, name = C3 28 (a truncated 2-byte sequence),
        // then four empty fields. Structurally valid, so the only thing that can
        // reject it is the UTF-8 check.
        val broken = byteArrayOf(0, 1, 0, 2) + byteArrayOf(0xC3.toByte(), 0x28) + ByteArray(8)
        val error = assertFailsWith<IllegalArgumentException> { CordnGroupMetadata.decode(broken) }
        assertTrue(
            error.message?.contains("UTF-8") == true,
            "must fail on the encoding, not incidentally on length: got '${error.message}'",
        )
    }

    @Test
    fun duplicateAdminsAreRejected() {
        assertFailsWith<IllegalArgumentException> { CordnGroupMetadata(name = "", adminPubkeys = listOf(alice, alice)) }
    }

    @Test
    fun aFullPayloadRoundTrips() {
        val full =
            CordnGroupMetadata(
                name = "Design 🎨",
                description = "where the work happens",
                adminPubkeys = listOf(alice, bob),
                icon = "🎨",
                imageUrl = "https://example.com/a.png",
            )
        assertEquals(full, CordnGroupMetadata.decode(full.encode()))
    }

    @Test
    fun aGroupWithNoMetadataExtensionReadsAsNull() {
        // §6: omitting it entirely is valid, so this must not throw.
        assertNull(CordnGroupMetadata.fromExtensions(emptyList()))
    }
}
