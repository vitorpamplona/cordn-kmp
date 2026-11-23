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

import com.vitorpamplona.cordn.spec00Coordinator.ICoordinator
import com.vitorpamplona.cordn.spec01GroupMetadata.CordnGroupMetadata
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A store with one group in it that no longer reads. */
class CordnRestoreTest {
    private val account = "aa".repeat(32)
    private val config = CoordinatorConfig("cc".repeat(32), listOf(RelayUrlNormalizer.normalizeOrNull("wss://relay.example.com")!!))
    private val good = "11111111-1111-4111-8111-111111111111"
    private val bad = "22222222-2222-4222-8222-222222222222"
    private val garbage = byteArrayOf(0, 1, 2, 3)

    private suspend fun storeWithABadGroup(): InMemoryCordnGroupStore {
        val store = InMemoryCordnGroupStore()
        CordnGroupManager(account, config, FakeCoordinator(account), store).createGroup(good, CordnGroupMetadata(name = "fine"))
        store.saveGroup(bad, garbage)
        return store
    }

    @Test
    fun `one unreadable group does not take the others down`() =
        runTest {
            val store = storeWithABadGroup()
            val manager = CordnGroupManager(account, config, FakeCoordinator(account), store)
            manager.restore()

            assertEquals(setOf(good), manager.gids.value)
            assertEquals(setOf(bad), manager.unrestorable.value.keys)
            // Left as it is, and nothing may be installed over it.
            assertFailsWith<CordnGroupException> { manager.createGroup(bad, CordnGroupMetadata(name = "over it")) }
            assertContentEquals(garbage, store.loadGroup(bad))
        }

    @Test
    fun `a session that fails to restore closes what it opened`() =
        runTest {
            var closed = 0
            val registry =
                CordnCoordinatorRegistry(
                    account,
                    { _, _ ->
                        object : CordnCoordinatorScope {
                            override val coordinator: ICoordinator = FakeCoordinator(account)
                            override val groupStore: CordnGroupStore = InMemoryCordnGroupStore()
                            override val keyPackageStore: CordnKeyPackageStore =
                                object : CordnKeyPackageStore by InMemoryCordnKeyPackageStore() {
                                    override suspend fun list(): List<String> = throw IllegalStateException("disk gone")
                                }

                            override suspend fun close() {
                                closed++
                            }
                        }
                    },
                )
            repeat(3) { assertFailsWith<IllegalStateException> { registry.session(config) } }
            assertEquals(3, closed, "each failed open left its transport behind")
            assertTrue(registry.coordinators.value.isEmpty())
        }
}
