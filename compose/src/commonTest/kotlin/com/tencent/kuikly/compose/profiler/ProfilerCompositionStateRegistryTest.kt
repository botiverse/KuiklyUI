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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProfilerCompositionStateRegistryTest {

    private var currentThreadId = 1L

    private fun <Handle : Any> registry(): ProfilerCompositionStateRegistry<String, String, Handle> =
        ProfilerCompositionStateRegistry { currentThreadId }

    @Test
    fun noObserverContextRequiresCoarseFallback() {
        val snapshot = registry<String>().currentScopeSnapshot()

        assertFalse(snapshot.hasPreciseMapping)
        assertNull(snapshot.scope)
        assertNull(snapshot.triggerStateObjects)
        assertFalse(snapshot.isForcedRecomposition)
    }

    @Test
    fun endingCompositionBDoesNotClearCompositionA() {
        val registry = registry<String>()
        val stateA = Any()
        val stateB = Any()
        val passA = registry.beginComposition("composition-a", mapOf("scope-a" to setOf(stateA)))
        assertTrue(registry.registerHandle("composition-a", passA.generation, "handle-a"))
        registry.beginScope("composition-a", passA.generation, "scope-a")

        currentThreadId = 2L
        val passB = registry.beginComposition("composition-b", mapOf("scope-b" to setOf(stateB)))
        assertTrue(registry.registerHandle("composition-b", passB.generation, "handle-b"))
        registry.beginScope("composition-b", passB.generation, "scope-b")
        assertEquals("scope-b", registry.currentScopeSnapshot().scope)

        assertEquals(listOf("handle-b"), registry.endComposition("composition-b"))
        assertFalse(registry.currentScopeSnapshot().hasPreciseMapping)

        currentThreadId = 1L
        val snapshotA = registry.currentScopeSnapshot()
        assertEquals("scope-a", snapshotA.scope)
        assertEquals(setOf(stateA), snapshotA.triggerStateObjects)
        assertTrue(snapshotA.hasPreciseMapping)
        assertEquals(listOf("handle-a"), registry.endComposition("composition-a"))
    }

    @Test
    fun activeScopesArePartitionedByExecutionThread() {
        val registry = registry<String>()
        val pass = registry.beginComposition(
            "composition",
            mapOf("scope-a" to emptySet(), "scope-b" to emptySet())
        )

        registry.beginScope("composition", pass.generation, "scope-a")
        assertEquals("scope-a", registry.currentScopeSnapshot().scope)

        currentThreadId = 2L
        registry.beginScope("composition", pass.generation, "scope-b")
        assertEquals("scope-b", registry.currentScopeSnapshot().scope)
        registry.endScope("composition", pass.generation, "scope-b")
        assertFalse(registry.currentScopeSnapshot().hasPreciseMapping)

        currentThreadId = 1L
        assertEquals("scope-a", registry.currentScopeSnapshot().scope)
    }

    @Test
    fun nestedAndOutOfOrderScopeEndsKeepTheCurrentScopeStable() {
        val registry = registry<String>()
        val pass = registry.beginComposition(
            "composition",
            mapOf("outer" to emptySet(), "inner" to emptySet())
        )
        registry.beginScope("composition", pass.generation, "outer")
        registry.beginScope("composition", pass.generation, "inner")

        registry.endScope("composition", pass.generation, "outer")
        assertEquals("inner", registry.currentScopeSnapshot().scope)

        registry.endScope("composition", pass.generation, "inner")
        assertFalse(registry.currentScopeSnapshot().hasPreciseMapping)
    }

    @Test
    fun staleGenerationCallbacksAndHandlesCannotTouchRestartedPass() {
        val registry = registry<String>()
        val first = registry.beginComposition("composition", mapOf("old" to emptySet()))
        assertTrue(registry.registerHandle("composition", first.generation, "old-handle"))
        registry.beginScope("composition", first.generation, "old")

        val second = registry.beginComposition("composition", mapOf("new" to emptySet()))
        assertEquals(listOf("old-handle"), second.handlesToDispose)
        assertFalse(registry.registerHandle("composition", first.generation, "late-old-handle"))
        registry.beginScope("composition", first.generation, "old")
        registry.beginScope("composition", second.generation, "new")

        registry.endScope("composition", first.generation, "new")
        registry.scopeDisposed("composition", first.generation, "new")
        assertEquals("new", registry.currentScopeSnapshot().scope)

        assertTrue(registry.registerHandle("composition", second.generation, "new-handle"))
        assertEquals(listOf("new-handle"), registry.endComposition("composition"))
    }

    @Test
    fun forcedRecompositionIsDistinctFromAnUnmappedObservedScope() {
        val registry = registry<String>()
        val pass = registry.beginComposition("composition", mapOf("forced" to null))
        registry.beginScope("composition", pass.generation, "forced")

        val forced = registry.currentScopeSnapshot()
        assertTrue(forced.hasPreciseMapping)
        assertTrue(forced.isForcedRecomposition)
        assertNull(forced.triggerStateObjects)

        registry.endScope("composition", pass.generation, "forced")
        registry.beginScope("composition", pass.generation, "unmapped-child")
        val unmapped = registry.currentScopeSnapshot()
        assertTrue(unmapped.hasPreciseMapping)
        assertFalse(unmapped.isForcedRecomposition)
        assertNull(unmapped.triggerStateObjects)
    }

    @Test
    fun invalidationStatesAreDefensivelySnapshotted() {
        val registry = registry<String>()
        val mutableStates = mutableSetOf<Any>(Any())
        val originalState = mutableStates.single()
        val pass = registry.beginComposition("composition", mapOf("scope" to mutableStates))
        mutableStates.clear()
        mutableStates.add(Any())
        registry.beginScope("composition", pass.generation, "scope")

        val snapshot = registry.currentScopeSnapshot()
        assertEquals(1, snapshot.triggerStateObjects?.size)
        assertSame(originalState, snapshot.triggerStateObjects?.single())
    }

    @Test
    fun disposingScopeRemovesOnlyThatScopeFromEveryThread() {
        val registry = registry<String>()
        val pass = registry.beginComposition(
            "composition",
            mapOf("scope-a" to emptySet(), "scope-b" to emptySet())
        )
        registry.beginScope("composition", pass.generation, "scope-a")

        currentThreadId = 2L
        registry.beginScope("composition", pass.generation, "scope-b")
        registry.scopeDisposed("composition", pass.generation, "scope-a")
        assertEquals("scope-b", registry.currentScopeSnapshot().scope)

        currentThreadId = 1L
        assertFalse(registry.currentScopeSnapshot().hasPreciseMapping)
    }

    @Test
    fun disposeAllDetachesAllHandlesAndRejectsLateCallbacks() {
        val registry = registry<String>()
        val passA = registry.beginComposition("composition-a", mapOf("scope-a" to emptySet()))
        assertTrue(registry.registerHandle("composition-a", passA.generation, "handle-a"))
        registry.beginScope("composition-a", passA.generation, "scope-a")

        currentThreadId = 2L
        val passB = registry.beginComposition("composition-b", mapOf("scope-b" to emptySet()))
        assertTrue(registry.registerHandle("composition-b", passB.generation, "handle-b"))
        registry.beginScope("composition-b", passB.generation, "scope-b")

        assertEquals(setOf("handle-a", "handle-b"), registry.disposeAll().toSet())
        assertFalse(registry.currentScopeSnapshot().hasPreciseMapping)
        assertFalse(registry.registerHandle("composition-a", passA.generation, "late-handle"))
        registry.beginScope("composition-b", passB.generation, "scope-b")
        assertFalse(registry.currentScopeSnapshot().hasPreciseMapping)
    }

    @Test
    fun detachedHandleCanReenterRegistryDuringLockFreeDisposal() {
        val registry = registry<ReentrantHandle>()
        var reentered = false
        val handle = ReentrantHandle {
            reentered = true
            val passB = registry.beginComposition("composition-b", mapOf("scope-b" to emptySet()))
            registry.beginScope("composition-b", passB.generation, "scope-b")
        }
        val first = registry.beginComposition("composition-a", mapOf("scope-a" to emptySet()))
        assertTrue(registry.registerHandle("composition-a", first.generation, handle))

        val restarted = registry.beginComposition("composition-a", mapOf("scope-a" to emptySet()))
        assertEquals(1, restarted.handlesToDispose.size)
        restarted.handlesToDispose.single().dispose()

        assertTrue(reentered)
        assertEquals("scope-b", registry.currentScopeSnapshot().scope)
    }

    @Test
    fun rapidBeginEndRestartLifecycleLeavesNoBorrowedContext() {
        val registry = registry<String>()

        repeat(1_000) { index ->
            currentThreadId = (index % 3).toLong() + 1L
            val scope = "scope-$index"
            val pass = registry.beginComposition("composition", mapOf(scope to emptySet()))
            registry.beginScope("composition", pass.generation, scope)
            assertEquals(scope, registry.currentScopeSnapshot().scope)
            registry.endScope("composition", pass.generation, scope)
            assertTrue(registry.endComposition("composition").isEmpty())
            assertFalse(registry.currentScopeSnapshot().hasPreciseMapping)
        }
    }

    private class ReentrantHandle(private val onDispose: () -> Unit) {
        fun dispose() = onDispose()
    }
}
