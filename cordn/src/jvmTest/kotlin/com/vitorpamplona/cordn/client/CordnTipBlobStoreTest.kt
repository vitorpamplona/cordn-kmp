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

import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** The §12 rules of the migration blob store, which every front end now shares. */
class CordnTipBlobStoreTest {
    private class FakeHttp(
        val accepts: Set<String> = emptySet(),
        val serves: Map<String, ByteArray> = emptyMap(),
    ) : CordnBlossomHttp {
        val authorizations = mutableListOf<String>()
        val downloads = mutableListOf<String>()

        override suspend fun upload(
            serverBaseUrl: String,
            blob: ByteArray,
            authorization: String,
        ): Boolean {
            authorizations += authorization
            if (serverBaseUrl == "https://down.example") throw IllegalStateException("connection refused")
            return serverBaseUrl in accepts
        }

        override suspend fun download(url: String): ByteArray? {
            downloads += url
            return serves[url]
        }
    }

    private fun authorPubKey(header: String): String {
        val json = Base64.decode(header.removePrefix("Nostr ")).decodeToString()
        return Regex("\"pubkey\":\"([0-9a-f]{64})\"").find(json)!!.groupValues[1]
    }

    @Test
    fun `put reports the servers that took it, and a failing one is just left out`() =
        runTest {
            val http = FakeHttp(accepts = setOf("https://a.example"))
            val store = CordnTipBlobStore(listOf("https://a.example", "https://down.example", "https://b.example"), http)

            assertEquals(listOf("https://a.example"), store.put(byteArrayOf(1, 2, 3)))
        }

    @Test
    fun `uploads are authorized by a throwaway key, a new one per store`() =
        runTest {
            val first = FakeHttp(accepts = setOf("https://a.example"))
            val second = FakeHttp(accepts = setOf("https://a.example"))
            CordnTipBlobStore(listOf("https://a.example"), first).put(byteArrayOf(1))
            CordnTipBlobStore(listOf("https://a.example"), second).put(byteArrayOf(1))

            assertNotEquals(authorPubKey(first.authorizations.single()), authorPubKey(second.authorizations.single()))
        }

    @Test
    fun `get tries the servers in the order given and stops at the first that serves it`() =
        runTest {
            val http = FakeHttp(serves = mapOf("https://b.example/abc" to byteArrayOf(7)))
            val store = CordnTipBlobStore(emptyList(), http)

            assertContentEquals(byteArrayOf(7), store.get("abc", listOf("https://a.example/", "https://b.example", "https://c.example")))
            assertEquals(listOf("https://a.example/abc", "https://b.example/abc"), http.downloads)
            assertNull(store.get("zzz", listOf("https://a.example")))
        }
}
