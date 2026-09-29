/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI;
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://github.com/Tencent-TDS/KuiklyUI/blob/main/LICENSE
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tencent.kuikly.core.render.android.scheduler

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplaceableTaskRegistryTest {

    private val removed = mutableListOf<Runnable>()
    private val registry = ReplaceableTaskRegistry { removed += it }

    @Test
    fun `same token replaces pending task last wins`() {
        val executed = mutableListOf<String>()
        val stale = registry.register("move:1:100") { executed += "stale" }
        val latest = registry.register("move:1:100") { executed += "latest" }

        // The pending stale task was evicted (removeCallbacks called with it).
        assertEquals(listOf(stale), removed)

        // Whatever the handler still runs, only the latest task body executes:
        // the evicted stale runnable must never be posted again, and even if a
        // racy poster ran it, supersession is by token, not by run.
        latest.run()
        assertEquals(listOf("latest"), executed)
    }

    @Test
    fun `different tokens coexist`() {
        val executed = mutableListOf<String>()
        registry.register("move:1:100") { executed += "a" }.run()
        registry.register("move:1:200") { executed += "b" }.run()
        assertEquals(listOf("a", "b"), executed)
        assertEquals(emptyList<Runnable>(), removed)
    }

    @Test
    fun `evict drops pending task without running it`() {
        val executed = mutableListOf<String>()
        val pending = registry.register("move:1:100") { executed += "x" }
        registry.evict("move:1:100")
        assertEquals(listOf(pending), removed)
        assertEquals(emptyList<String>(), executed)
    }

    @Test
    fun `evict by prefix clears page scoped tokens`() {
        val a = registry.register("7:100") {}
        val b = registry.register("7:200") {}
        registry.register("8:100") {}

        registry.evictByPrefix("7:")
        assertEquals(setOf(a, b), removed.toSet())

        // Instance 8's token survives and still runs.
        var ran = false
        registry.register("8:100") { ran = true }.run()
        assertEquals(true, ran)
    }

    @Test
    fun `unregisterIfCurrent only removes the same runnable`() {
        val first = registry.register("move:1:100") {}
        val second = registry.register("move:1:100") {}
        registry.unregisterIfCurrent("move:1:100", first) // stale identity: no-op
        registry.unregisterIfCurrent("move:1:100", second)
        // After current removal, a new registration must not evict anything.
        removed.clear()
        registry.register("move:1:100") {}
        assertEquals(emptyList<Runnable>(), removed)
    }

    @Test
    fun `registerRunnable keeps built runnable evictable`() {
        val executed = mutableListOf<String>()
        val blockingLike = Runnable { executed += "sync" }
        registry.registerRunnable("move:1:100", blockingLike)
        // A later async dispatch supersedes the timed-out sync task.
        registry.register("move:1:100") { executed += "async" }.run()
        assertEquals(listOf(blockingLike), removed)
        assertEquals(listOf("async"), executed)
    }
}
