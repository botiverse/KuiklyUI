/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2026 Tencent. All rights reserved.
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

package com.tencent.kuikly.compose.profiler

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfilerCompositionStateRegistryConcurrencyTest {

    @Test
    fun twoCompositionsOnRealThreadsNeverBorrowEachOthersScope() {
        val registry = ProfilerCompositionStateRegistry<String, String, String>()
        val bothScopesActive = CyclicBarrier(2)
        val compositionBEnded = CyclicBarrier(2)
        val failure = AtomicReference<Throwable?>(null)

        val threadA = checkedThread("profiler-composition-a", failure) {
            val pass = registry.beginComposition("composition-a", mapOf("scope-a" to emptySet()))
            assertTrue(registry.registerHandle("composition-a", pass.generation, "handle-a"))
            registry.beginScope("composition-a", pass.generation, "scope-a")
            bothScopesActive.await(10, TimeUnit.SECONDS)

            repeat(10_000) {
                val snapshot = registry.currentScopeSnapshot()
                assertTrue(snapshot.hasPreciseMapping)
                assertEquals("scope-a", snapshot.scope)
            }

            compositionBEnded.await(10, TimeUnit.SECONDS)
            repeat(1_000) {
                assertEquals("scope-a", registry.currentScopeSnapshot().scope)
            }
            registry.endScope("composition-a", pass.generation, "scope-a")
            assertEquals(listOf("handle-a"), registry.endComposition("composition-a"))
        }

        val threadB = checkedThread("profiler-composition-b", failure) {
            val pass = registry.beginComposition("composition-b", mapOf("scope-b" to emptySet()))
            assertTrue(registry.registerHandle("composition-b", pass.generation, "handle-b"))
            registry.beginScope("composition-b", pass.generation, "scope-b")
            bothScopesActive.await(10, TimeUnit.SECONDS)

            repeat(10_000) {
                val snapshot = registry.currentScopeSnapshot()
                assertTrue(snapshot.hasPreciseMapping)
                assertEquals("scope-b", snapshot.scope)
            }
            registry.endScope("composition-b", pass.generation, "scope-b")
            assertEquals(listOf("handle-b"), registry.endComposition("composition-b"))
            compositionBEnded.await(10, TimeUnit.SECONDS)
            assertFalse(registry.currentScopeSnapshot().hasPreciseMapping)
        }

        joinChecked(threadA, threadB, failure)
    }

    @Test
    fun concurrentRapidLifecycleAndProfilerDisposeNeverLeakForeignScope() {
        val registry = ProfilerCompositionStateRegistry<String, String, String>()
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)

        val threadA = checkedThread("profiler-rapid-a", failure) {
            start.await(10, TimeUnit.SECONDS)
            runRapidLifecycle(registry, "composition-a", "scope-a")
        }
        val threadB = checkedThread("profiler-rapid-b", failure) {
            start.await(10, TimeUnit.SECONDS)
            runRapidLifecycle(registry, "composition-b", "scope-b")
        }

        start.countDown()
        joinChecked(threadA, threadB, failure)
        assertFalse(registry.currentScopeSnapshot().hasPreciseMapping)
    }

    private fun runRapidLifecycle(
        registry: ProfilerCompositionStateRegistry<String, String, String>,
        composition: String,
        scope: String
    ) {
        repeat(5_000) { iteration ->
            val first = registry.beginComposition(composition, mapOf(scope to emptySet()))
            registry.beginScope(composition, first.generation, scope)

            if (iteration % 7 == 0) {
                val restarted = registry.beginComposition(composition, mapOf(scope to emptySet()))
                registry.endScope(composition, first.generation, scope)
                registry.scopeDisposed(composition, first.generation, scope)
                registry.beginScope(composition, restarted.generation, scope)
            }

            val snapshot = registry.currentScopeSnapshot()
            assertTrue(snapshot.scope == null || snapshot.scope == scope)
            if (snapshot.scope != null) {
                assertTrue(snapshot.hasPreciseMapping)
            }

            if (iteration % 31 == 0) {
                registry.disposeAll()
            } else {
                registry.endComposition(composition)
            }
            val afterEnd = registry.currentScopeSnapshot()
            assertNull(afterEnd.scope)
            assertFalse(afterEnd.hasPreciseMapping)
        }
    }

    private fun checkedThread(
        name: String,
        failure: AtomicReference<Throwable?>,
        block: () -> Unit
    ): Thread = thread(start = true, name = name) {
        try {
            block()
        } catch (throwable: Throwable) {
            failure.compareAndSet(null, throwable)
        }
    }

    private fun joinChecked(
        first: Thread,
        second: Thread,
        failure: AtomicReference<Throwable?>
    ) {
        first.join(TimeUnit.SECONDS.toMillis(30))
        second.join(TimeUnit.SECONDS.toMillis(30))
        assertFalse(first.isAlive, "${first.name} did not finish")
        assertFalse(second.isAlive, "${second.name} did not finish")
        failure.get()?.let { throw it }
    }
}
