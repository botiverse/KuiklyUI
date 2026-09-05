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

import androidx.compose.runtime.Composition
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.CompositionObserverHandle
import androidx.compose.runtime.tooling.RecomposeScopeObserver
import androidx.compose.runtime.tooling.observe

/**
 * CompositionObserver implementation for precise recomposition reason tracking.
 *
 * Leverages [CompositionObserver.onBeginComposition]'s `invalidationMap` to determine
 * exactly which State objects triggered each RecomposeScope's invalidation.
 * Combined with [RecomposeScopeObserver] to maintain composition- and thread-partitioned scope stacks,
 * this allows [RecompositionTracker] to associate precise trigger states
 * with each Composable (via the CompositionTracer bridge).
 *
 * Data flow:
 * 1. `onBeginComposition(invalidationMap)` → save scope→states mapping
 * 2. `RecomposeScopeObserver.onBeginScopeComposition(scope)` → push to active stack
 * 3. `CompositionTracer.traceEventStart(key, info)` → tracker atomically captures
 *    [currentScopeSnapshot] with the calling thread's composable entry
 * 4. `CompositionTracer.traceEventEnd()` → tracker pops and consumes that same entry snapshot
 * 5. `RecomposeScopeObserver.onEndScopeComposition(scope)` → pop from active stack
 * 6. `onEndComposition()` → cleanup
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
internal class ProfilerCompositionObserver : CompositionObserver {

    private val stateRegistry = ProfilerCompositionStateRegistry<
        Composition,
        RecomposeScope,
        CompositionObserverHandle
    >()

    override fun onBeginComposition(
        composition: Composition,
        invalidationMap: Map<RecomposeScope, Set<Any>?>
    ) {
        val beginResult = stateRegistry.beginComposition(composition, invalidationMap)
        disposeHandles(beginResult.handlesToDispose)

        // Register RecomposeScopeObserver for each invalidated scope
        // This is necessary so that onBeginScopeComposition/onEndScopeComposition
        // are called by the runtime when each scope's compose lambda executes.
        val scopeObserver = ScopeObserver(composition, beginResult.generation)
        for ((scope, _) in invalidationMap) {
            val handle = scope.observe(scopeObserver)
            if (!stateRegistry.registerHandle(composition, beginResult.generation, handle)) {
                // The pass ended/restarted while observe() was creating the handle.
                // Dispose outside the registry lock so callbacks may safely re-enter.
                handle.dispose()
            }
        }
    }

    override fun onEndComposition(composition: Composition) {
        disposeHandles(stateRegistry.endComposition(composition))
    }

    /**
     * Returns one atomic snapshot for the calling execution thread.
     *
     * The global CompositionTracer can observe Android compositions that do not have a matching
     * precise observer context. Such calls deliberately return [hasPreciseMapping] = false so the
     * tracker falls back to frame-level state changes instead of borrowing another scene's scope.
     */
    fun currentScopeSnapshot(): CurrentScopeSnapshot {
        val snapshot = stateRegistry.currentScopeSnapshot()
        return CurrentScopeSnapshot(
            scopeKey = snapshot.scope?.hashCode(),
            triggerStateObjects = snapshot.triggerStateObjects,
            hasPreciseMapping = snapshot.hasPreciseMapping,
            isForcedRecomposition = snapshot.isForcedRecomposition
        )
    }

    /** Detach every per-scope handle during profiler stop, then dispose outside the registry lock. */
    fun dispose() {
        disposeHandles(stateRegistry.disposeAll())
    }

    private fun disposeHandles(handles: List<CompositionObserverHandle>) {
        for (handle in handles) {
            handle.dispose()
        }
    }

    /**
     * Inner RecomposeScopeObserver that tracks scope enter/exit for stack maintenance.
     */
    private inner class ScopeObserver(
        private val composition: Composition,
        private val generation: Long
    ) : RecomposeScopeObserver {

        override fun onBeginScopeComposition(scope: RecomposeScope) {
            stateRegistry.beginScope(composition, generation, scope)
        }

        override fun onEndScopeComposition(scope: RecomposeScope) {
            stateRegistry.endScope(composition, generation, scope)
        }

        override fun onScopeDisposed(scope: RecomposeScope) {
            stateRegistry.scopeDisposed(composition, generation, scope)
        }
    }

    internal data class CurrentScopeSnapshot(
        val scopeKey: Int? = null,
        val triggerStateObjects: Set<Any>? = null,
        val hasPreciseMapping: Boolean = false,
        val isForcedRecomposition: Boolean = false
    )
}
