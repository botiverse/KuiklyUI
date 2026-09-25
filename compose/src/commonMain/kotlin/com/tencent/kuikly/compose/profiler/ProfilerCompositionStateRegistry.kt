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

package com.tencent.kuikly.compose.profiler

import com.tencent.kuikly.compose.ui.createSynchronizedObject
import com.tencent.kuikly.compose.ui.getCurrentThreadId
import com.tencent.kuikly.compose.ui.synchronized

/**
 * One atomic view of the precise CompositionObserver context for the calling thread.
 *
 * [hasPreciseMapping] is false when the global CompositionTracer is running for a
 * composition that has no matching profiler observer context on this thread. In that case the
 * tracker must fall back to its coarse frame-level state changes instead of borrowing another
 * composition's scope.
 */
internal data class ProfilerScopeSnapshot<Scope : Any>(
    val scope: Scope? = null,
    val triggerStateObjects: Set<Any>? = null,
    val hasPreciseMapping: Boolean = false,
    val isForcedRecomposition: Boolean = false
)

/** Result of starting a new observation pass for one composition. */
internal data class ProfilerCompositionBeginResult<Handle : Any>(
    val generation: Long,
    val handlesToDispose: List<Handle>
)

/**
 * Thread-safe ownership registry used by [ProfilerCompositionObserver].
 *
 * A single profiler tracker is shared by every live Compose scene, while scene recomposition can
 * execute concurrently on different Kuikly threads and the global CompositionTracer can also see
 * Android compositions on the main thread. Therefore observer state must be isolated by both:
 *
 * - composition: one pass may only replace/dispose its own scope map and handles;
 * - execution thread: a trace callback may only read the active scope from its own thread.
 *
 * All mutable state below is protected by [lock]. Handle disposal intentionally happens at the
 * caller after the handles have been detached under the lock, because dispose can synchronously
 * re-enter observer callbacks.
 */
internal class ProfilerCompositionStateRegistry<
    CompositionKey : Any,
    Scope : Any,
    Handle : Any
>(
    private val currentThreadId: () -> Long = ::getCurrentThreadId
) {

    private data class CompositionState<Scope : Any, Handle : Any>(
        val generation: Long,
        val scopeToStates: MutableMap<Scope, Set<Any>?>,
        val handles: MutableList<Handle> = mutableListOf()
    )

    private data class ActiveScope<CompositionKey : Any, Scope : Any>(
        val composition: CompositionKey,
        val generation: Long,
        val scope: Scope
    )

    private val lock = createSynchronizedObject()
    private val statesByComposition = mutableMapOf<CompositionKey, CompositionState<Scope, Handle>>()
    private val activeScopesByThread = mutableMapOf<Long, MutableList<ActiveScope<CompositionKey, Scope>>>()
    private var nextGeneration = 0L

    fun beginComposition(
        composition: CompositionKey,
        invalidationMap: Map<Scope, Set<Any>?>
    ): ProfilerCompositionBeginResult<Handle> = synchronized(lock) {
        val handlesToDispose = statesByComposition.remove(composition)?.handles?.toList().orEmpty()
        removeCompositionScopesLocked(composition)

        nextGeneration += 1
        val generation = nextGeneration
        val scopeSnapshot = mutableMapOf<Scope, Set<Any>?>()
        for ((scope, states) in invalidationMap) {
            scopeSnapshot[scope] = states?.toSet()
        }
        statesByComposition[composition] = CompositionState(
            generation = generation,
            scopeToStates = scopeSnapshot
        )
        ProfilerCompositionBeginResult(generation, handlesToDispose)
    }

    /**
     * Registers a handle only if its observation pass is still current.
     *
     * A false result means the composition ended or restarted while `scope.observe(...)` was
     * creating the handle; the caller must dispose that rejected handle outside the lock.
     */
    fun registerHandle(composition: CompositionKey, generation: Long, handle: Handle): Boolean =
        synchronized(lock) {
            val state = statesByComposition[composition]
            if (state == null || state.generation != generation) {
                false
            } else {
                state.handles.add(handle)
                true
            }
        }

    /** Ends only [composition]'s current pass and returns detached handles for lock-free disposal. */
    fun endComposition(composition: CompositionKey): List<Handle> = synchronized(lock) {
        val state = statesByComposition.remove(composition)
        removeCompositionScopesLocked(composition)
        state?.handles?.toList().orEmpty()
    }

    /** Detaches every live pass during profiler stop; callers dispose the returned handles. */
    fun disposeAll(): List<Handle> = synchronized(lock) {
        val handles = statesByComposition.values.flatMap { it.handles }
        statesByComposition.clear()
        activeScopesByThread.clear()
        handles
    }

    fun beginScope(composition: CompositionKey, generation: Long, scope: Scope) {
        synchronized(lock) {
            val state = statesByComposition[composition]
            if (state == null || state.generation != generation) return
            activeScopesByThread
                .getOrPut(currentThreadId()) { mutableListOf() }
                .add(ActiveScope(composition, generation, scope))
        }
    }

    fun endScope(composition: CompositionKey, generation: Long, scope: Scope) {
        synchronized(lock) {
            val threadId = currentThreadId()
            val stack = activeScopesByThread[threadId] ?: return
            val index = stack.indexOfLast {
                it.composition == composition && it.generation == generation && it.scope == scope
            }
            if (index >= 0) {
                stack.removeAt(index)
            }
            if (stack.isEmpty()) {
                activeScopesByThread.remove(threadId)
            }
        }
    }

    fun scopeDisposed(composition: CompositionKey, generation: Long, scope: Scope) {
        synchronized(lock) {
            val state = statesByComposition[composition]
            if (state != null && state.generation == generation) {
                state.scopeToStates.remove(scope)
            }
            removeScopeLocked(composition, generation, scope)
        }
    }

    /** Returns one atomic current-thread snapshot, never another thread's active scope. */
    fun currentScopeSnapshot(): ProfilerScopeSnapshot<Scope> = synchronized(lock) {
        val activeScope = activeScopesByThread[currentThreadId()]?.lastOrNull()
            ?: return@synchronized ProfilerScopeSnapshot()
        val state = statesByComposition[activeScope.composition]
        if (state == null || state.generation != activeScope.generation) {
            return@synchronized ProfilerScopeSnapshot()
        }

        val hasScopeMapping = state.scopeToStates.containsKey(activeScope.scope)
        // Values were defensively copied when the pass began and are never mutated afterwards.
        // Returning that immutable snapshot avoids allocating another Set for every trace callback.
        val triggerStateObjects = state.scopeToStates[activeScope.scope]
        ProfilerScopeSnapshot(
            scope = activeScope.scope,
            triggerStateObjects = triggerStateObjects,
            hasPreciseMapping = true,
            isForcedRecomposition = hasScopeMapping && triggerStateObjects == null
        )
    }

    private fun removeCompositionScopesLocked(composition: CompositionKey) {
        val emptyThreads = mutableListOf<Long>()
        for ((threadId, stack) in activeScopesByThread) {
            stack.removeAll { it.composition == composition }
            if (stack.isEmpty()) emptyThreads.add(threadId)
        }
        for (threadId in emptyThreads) {
            activeScopesByThread.remove(threadId)
        }
    }

    private fun removeScopeLocked(composition: CompositionKey, generation: Long, scope: Scope) {
        val emptyThreads = mutableListOf<Long>()
        for ((threadId, stack) in activeScopesByThread) {
            stack.removeAll {
                it.composition == composition && it.generation == generation && it.scope == scope
            }
            if (stack.isEmpty()) emptyThreads.add(threadId)
        }
        for (threadId in emptyThreads) {
            activeScopesByThread.remove(threadId)
        }
    }
}
