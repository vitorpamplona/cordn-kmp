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
package com.vitorpamplona.cordn.appEncryptedMedia

import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import kotlin.math.round

/**
 * How a cordn message points at an encrypted file.
 *
 * A NIP-92 `imeta` tag, which is what every Nostr client already parses, with
 * the fields a recipient needs to fetch and open the blob. It rides **inside**
 * the MLS-encrypted envelope, so the coordinator and the blob host see none of
 * it — the host sees an opaque upload, the coordinator sees a sealed payload.
 *
 * The hash is of the **plaintext**, not of the blob. That is deliberate: it is
 * what the AAD binds ([CordnMediaEncryption]), so a recipient can tell an
 * altered file from a re-encoded one, and it is useless to the blob host,
 * which only ever holds the ciphertext.
 *
 * cordn has a `v` field too, and it is required:
 * `spec/applications/encrypted-media.md` §4 fixes it at `cordn-em-v1` and says
 * a client "MUST reject tags whose `v` field is absent or names an unknown
 * version". An earlier version of this file asserted the opposite — that there
 * was no cordn equivalent of Marmot's — and omitted it. cordn.net duly
 * rejected every Amethyst attachment and drew an empty bubble.
 */
object CordnMediaTag {
    const val TAG_NAME = "imeta"

    const val URL = "url"
    const val MIME_TYPE = "m"
    const val FILENAME = "filename"
    const val HASH = "x"
    const val NONCE = "n"

    /**
     * The encryption version. Required, and only ever [VERSION_V1].
     *
     * No key field accompanies it: §3.1 derives the key from the group's
     * epoch exporter and §4 states it is "never transmitted, never stored in
     * `imeta`". See [CordnMediaEncryption.mediaKey].
     */
    const val VERSION = "v"

    const val VERSION_V1 = "cordn-em-v1"
    const val DIMENSIONS = "dim"
    const val BLURHASH = "blurhash"
    const val THUMBHASH = "thumbhash"
    const val ALT = "alt"

    /**
     * Peaks for the voice-note bars: cordn-web's format, comma-separated amplitudes from 0 to 1
     * with three decimals (`waveform 0.100,0.500,1.000`). cordn-web drops any value outside 0..1,
     * so the space-separated 0..32767 amplitudes this library wrote up to 1.5.0 gave its readers
     * no bars, and its peaks gave ours none. [build] now writes the reference format; [parseAll]
     * reads both, the old one scaled down to 0..1.
     *
     * NOT in `spec/applications/encrypted-media.md` §5, which lists no such
     * field. It is written as one more of the display hints the spec does
     * define (`dim`, `blurhash`, `thumbhash`, `alt`) and describes as "passed
     * through unchanged" — so a client that does not know it ignores it, and a
     * client that does gets the bars the recorder already measured. The
     * recorder produces them either way; without somewhere to put them they
     * were thrown away at upload and the sender's own message came back
     * bar-less.
     *
     * Kept out of the AAD deliberately: it is a hint about the file, not a
     * claim the ciphertext is bound to, and putting it in the AAD would make
     * the blob undecryptable to anyone who wrote the floats differently.
     */
    const val WAVEFORM = "waveform"

    /**
     * A voice note's length in milliseconds, cordn-web's `duration` hint. Like the waveform, a
     * display hint outside the AAD: a player shows it until the audio's own metadata loads.
     */
    const val DURATION = "duration"

    /** Builds the `imeta` tag for an uploaded [media] at [url]. */
    fun build(
        media: CordnEncryptedMedia,
        url: String,
        dimensions: String? = null,
        blurhash: String? = null,
        thumbhash: String? = null,
        alt: String? = null,
        waveform: List<Float>? = null,
        durationMs: Long? = null,
    ): Array<String> =
        buildList {
            add(TAG_NAME)
            add("$URL $url")
            add("$MIME_TYPE ${media.mimeType}")
            add("$FILENAME ${media.filename}")
            add("$HASH ${media.plaintextHash.toHexKey()}")
            add("$NONCE ${media.nonce.toHexKey()}")
            add("$VERSION $VERSION_V1")
            dimensions?.let { add("$DIMENSIONS $it") }
            blurhash?.let { add("$BLURHASH $it") }
            thumbhash?.let { add("$THUMBHASH $it") }
            // A newline in alt would split the tag field, so it is flattened.
            alt?.takeIf { it.isNotBlank() }?.let { add("$ALT ${it.replace('\n', ' ')}") }
            durationMs?.takeIf { it > 0 }?.let { add("$DURATION $it") }
            waveform?.takeIf { it.isNotEmpty() }?.let { add("$WAVEFORM ${peaks(it).joinToString(",") { peak -> threeDecimals(peak) }}") }
        }.toTypedArray()

    @Deprecated("Kept so code compiled against 1.5.0 still links.", level = DeprecationLevel.HIDDEN)
    fun build(
        media: CordnEncryptedMedia,
        url: String,
        dimensions: String?,
        blurhash: String?,
        thumbhash: String?,
        alt: String?,
        waveform: List<Float>?,
    ): Array<String> = build(media, url, dimensions, blurhash, thumbhash, alt, waveform, null)

    /**
     * Reads every attachment in [tags], skipping any that is not usable.
     *
     * A tag missing a field, or carrying a nonce or hash of the wrong length,
     * is dropped rather than returned half-formed: the only thing a caller
     * could do with a partial descriptor is attempt a decrypt that must fail,
     * and a message with one broken attachment should still show its other
     * attachments and its text.
     */
    fun parseAll(tags: TagArray): List<CordnMediaAttachment> =
        tags.mapNotNull { tag ->
            if (tag.getOrNull(0) != TAG_NAME) return@mapNotNull null

            val fields =
                tag
                    .drop(1)
                    .mapNotNull { field ->
                        val at = field.indexOf(' ')
                        if (at <= 0) null else field.substring(0, at) to field.substring(at + 1)
                    }.toMap()

            val url = fields[URL] ?: return@mapNotNull null
            val mime = fields[MIME_TYPE] ?: return@mapNotNull null
            val filename = fields[FILENAME] ?: return@mapNotNull null
            val hash = fields[HASH]?.takeIf { it.length == HASH_HEX_LENGTH } ?: return@mapNotNull null
            val nonce = fields[NONCE]?.takeIf { it.length == NONCE_HEX_LENGTH } ?: return@mapNotNull null
            // §4: an absent or unknown version is a rejection, not something to
            // guess at — the bytes under it are a format we have not agreed on.
            if (fields[VERSION] != VERSION_V1) return@mapNotNull null

            CordnMediaAttachment(
                url = url,
                mimeType = mime,
                filename = filename,
                plaintextHash = hash,
                nonce = nonce,
                dimensions = fields[DIMENSIONS],
                blurhash = fields[BLURHASH],
                thumbhash = fields[THUMBHASH],
                alt = fields[ALT],
                // A malformed number makes the whole hint useless rather than
                // the message: drop the bars, keep the attachment.
                waveform = fields[WAVEFORM]?.let(::parseWaveform),
                durationMs = fields[DURATION]?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 },
            )
        }

    /** The recorder's scale, `MediaRecorder.maxAmplitude`'s 0..32767. */
    private const val MAX_AMPLITUDE = 32767f

    /**
     * [values] as 0..1 peaks. Values above 1 are amplitudes on the recorder's 0..32767 scale,
     * which callers written for 1.5.0 pass, and are scaled down.
     */
    private fun peaks(values: List<Float>): List<Float> {
        val scale = if (values.any { it > 1f }) MAX_AMPLITUDE else 1f
        return values.map { (it / scale).coerceIn(0f, 1f) }
    }

    /** `Number.prototype.toFixed(3)` for a value in 0..1. */
    private fun threeDecimals(value: Float): String {
        val thousandths = round(value * 1000f).toInt()
        return "${thousandths / 1000}.${(thousandths % 1000).toString().padStart(3, '0')}"
    }

    /**
     * cordn-web's `parseWaveform` (comma-separated, finite, 0..1), or the space-separated
     * amplitudes of 1.5.0 and earlier, scaled to 0..1. A malformed value is dropped, never the
     * attachment.
     */
    private fun parseWaveform(raw: String): List<Float>? {
        val legacy = !raw.contains(',') && raw.trim().contains(' ')
        val values = raw.split(if (legacy) ' ' else ',').mapNotNull { it.trim().toFloatOrNull()?.takeIf { value -> value.isFinite() } }
        val peaks = if (legacy) peaks(values.filter { it >= 0f }) else values.filter { it in 0f..1f }
        return peaks.takeIf { it.isNotEmpty() }
    }

    private const val HASH_HEX_LENGTH = 64
    private const val NONCE_HEX_LENGTH = 24
}

/** One encrypted attachment, as a message advertises it. */
data class CordnMediaAttachment(
    val url: String,
    val mimeType: String,
    val filename: String,
    val plaintextHash: String,
    val nonce: String,
    val dimensions: String? = null,
    val blurhash: String? = null,
    val thumbhash: String? = null,
    val alt: String? = null,
    val waveform: List<Float>? = null,
    /** A voice note's length, in milliseconds ([CordnMediaTag.DURATION]). */
    val durationMs: Double? = null,
) {
    @Deprecated("Kept so code compiled against 1.5.0 still links.", level = DeprecationLevel.HIDDEN)
    constructor(
        url: String,
        mimeType: String,
        filename: String,
        plaintextHash: String,
        nonce: String,
        dimensions: String?,
        blurhash: String?,
        thumbhash: String?,
        alt: String?,
        waveform: List<Float>?,
    ) : this(url, mimeType, filename, plaintextHash, nonce, dimensions, blurhash, thumbhash, alt, waveform, null)

    val hashBytes: ByteArray get() = plaintextHash.hexToByteArray()
    val nonceBytes: ByteArray get() = nonce.hexToByteArray()

    val isImage: Boolean get() = mimeType.startsWith("image/")
    val isAudio: Boolean get() = mimeType.startsWith("audio/")
}
