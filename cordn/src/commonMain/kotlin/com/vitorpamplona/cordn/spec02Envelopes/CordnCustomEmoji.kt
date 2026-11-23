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
import com.vitorpamplona.cordn.appEncryptedMedia.CordnMediaAttachment
import com.vitorpamplona.quartz.nip01Core.core.TagArray

/**
 * Custom emoji in a cordn message, as cordn-web 0.7.0 sends them (`src/lib/chat/emoji.ts`): a
 * `:shortcode:` in `content`, resolved by a tag of the same message.
 *
 * - `["emoji", name, url]`: NIP-30, a public picture.
 * - `["emoji-media", "name <name>", "url …", "m …", "filename …", "x …", "n …", "v …"]`: the
 *   picture re-encrypted into the group, `imeta`'s fields (`encrypted-media.md` §5) plus the
 *   shortcode's name, opened with the carrying message's media key. Only PNG, JPEG, GIF and WebP.
 *
 * A name is 1 to 32 of `[A-Za-z0-9_+-]`; the first valid tag per name wins, and an `emoji-media`
 * joins the `emoji` of the same name rather than replacing its URL. A reaction shows a custom
 * emoji when its whole `content` is one shortcode.
 */
@Immutable
data class CordnCustomEmoji(
    val name: String,
    /** The public picture, from an `emoji` tag. */
    val url: String? = null,
    /** The group-encrypted picture, from an `emoji-media` tag. */
    val media: CordnMediaAttachment? = null,
) {
    companion object {
        const val TAG = "emoji"
        const val MEDIA_TAG = "emoji-media"

        private val NAME = Regex("^[A-Za-z0-9_+-]{1,32}$")
        private val WHOLE_SHORTCODE = Regex("^:([A-Za-z0-9_+-]{1,32}):$")
        private val HASH = Regex("^[0-9a-fA-F]{64}$")
        private val NONCE = Regex("^[0-9a-fA-F]{24}$")
        private val MEDIA_MIMES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

        /** Every custom emoji [tags] define, by name. */
        fun fromTags(tags: TagArray): Map<String, CordnCustomEmoji> {
            val out = linkedMapOf<String, CordnCustomEmoji>()
            tags.forEach { tag ->
                if (tag.getOrNull(0) != TAG) return@forEach
                val name = tag.getOrNull(1)?.trim() ?: ""
                val url = tag.getOrNull(2)?.trim() ?: ""
                if (!NAME.matches(name) || !isUrl(url) || name in out) return@forEach
                out[name] = CordnCustomEmoji(name, url)
            }
            tags.forEach { tag ->
                if (tag.getOrNull(0) != MEDIA_TAG) return@forEach
                val (name, media) = media(tag) ?: return@forEach
                if (out[name]?.media != null) return@forEach
                out[name] = CordnCustomEmoji(name, out[name]?.url, media)
            }
            return out
        }

        /** The name in [text] when all of it is one `:shortcode:`. */
        fun wholeShortcode(text: String): String? = WHOLE_SHORTCODE.matchEntire(text.trim())?.groupValues?.get(1)

        /** The custom emoji a reaction with [content] and [tags] shows, or null for a plain one. */
        fun ofReaction(
            content: String,
            tags: TagArray,
        ): CordnCustomEmoji? = wholeShortcode(content)?.let { fromTags(tags)[it] }

        /** A NIP-30 `emoji` tag. */
        fun tag(
            name: String,
            url: String,
        ): Array<String> = arrayOf(TAG, name, url)

        private fun media(tag: Array<String>): Pair<String, CordnMediaAttachment>? {
            val fields =
                tag
                    .drop(1)
                    .mapNotNull { field ->
                        val at = field.indexOf(' ')
                        if (at <= 0) null else field.substring(0, at) to field.substring(at + 1)
                    }.toMap()
            val name = fields["name"]?.takeIf { NAME.matches(it) } ?: return null
            val url = fields["url"]?.takeIf { isUrl(it) } ?: return null
            val mime = fields["m"]?.takeIf { it in MEDIA_MIMES } ?: return null
            val filename = fields["filename"]?.ifEmpty { null } ?: return null
            val hash = fields["x"]?.takeIf { HASH.matches(it) } ?: return null
            val nonce = fields["n"]?.takeIf { NONCE.matches(it) } ?: return null
            if (fields["v"].isNullOrEmpty()) return null
            return name to CordnMediaAttachment(url, mime, filename, hash.lowercase(), nonce.lowercase())
        }

        /** cordn-web's `isEmojiUrl`. */
        private fun isUrl(url: String): Boolean =
            (url.startsWith("https://") || url.startsWith("http://")) &&
                url.length in 8..2048 &&
                url.none { it.isWhitespace() || it == '<' || it == '>' }
    }
}
