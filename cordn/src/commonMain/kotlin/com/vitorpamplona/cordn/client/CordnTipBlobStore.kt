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

import com.vitorpamplona.crypto.sha2.sha256
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nipB7Blossom.BlossomAuthorizationEvent
import kotlin.coroutines.cancellation.CancellationException

/**
 * The HTTP half of a Blossom server, as [CordnTipBlobStore] needs it. An app implements it over
 * whatever client and network route it already uses; the privacy rules stay in the store.
 */
interface CordnBlossomHttp {
    /**
     * Stores [blob] on [serverBaseUrl] through BUD-02's `/upload` (never `/media`, which would
     * re-encode and so destroy the ciphertext), declaring [CordnTipBlobStore.CONTENT_TYPE] and
     * sending [authorization] as the `Authorization` header. True when the server stored it.
     */
    suspend fun upload(
        serverBaseUrl: String,
        blob: ByteArray,
        authorization: String,
    ): Boolean

    /** The bytes at [url], or null on any failure. */
    suspend fun download(url: String): ByteArray?
}

/**
 * [CordnBlobStore] over Blossom servers, with the §12 rules applied once for every front end.
 *
 * - The BUD-01 upload authorization is signed by a throwaway key minted per store and derived from
 *   nothing, never the owner `npub`: signing as the owner would tell the storage server, in the
 *   clear and under the account's name, that this person is uploading right now.
 * - The blob is declared [CONTENT_TYPE] with no alt text, so the server learns a size and a hash
 *   and nothing about what kind of thing this is.
 * - [get] tries the servers in the order given, most reliable first, as §6 has the reader do.
 */
class CordnTipBlobStore(
    private val servers: List<String>,
    private val http: CordnBlossomHttp,
) : CordnBlobStore {
    private val signer = NostrSignerInternal(KeyPair())

    override suspend fun put(blob: ByteArray): List<String> {
        val hash = sha256(blob).toHexKey()
        val authorization = BlossomAuthorizationEvent.createUploadAuth(hash, blob.size.toLong(), "", signer).toAuthorizationHeader()
        return servers.filter { server ->
            try {
                http.upload(server, blob, authorization)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                false
            }
        }
    }

    override suspend fun get(
        address: String,
        servers: List<String>,
    ): ByteArray? =
        servers.firstNotNullOfOrNull { server ->
            try {
                http.download("${server.trimEnd('/')}/$address")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
        }

    companion object {
        /** What every document is declared as: opaque bytes. */
        const val CONTENT_TYPE = "application/octet-stream"
    }
}
