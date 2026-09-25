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

/**
 * Bounds the main-thread stall that synchronous touch events can cause.
 *
 * Kuikly Compose registers touchDown/Move/Up/Cancel as *sync* events so the
 * context (frame) thread can synchronously report `consumed` and drive
 * `preventTouch`/parent interception. Each sync dispatch blocks the main
 * thread until the context thread answers; against a busy context thread, a
 * MOVE stream therefore accumulates unbounded main-thread stalls (ANR).
 *
 * This guard keeps the healthy path unchanged and caps the worst case per
 * gesture: it accumulates the main-thread wait of every sync touch dispatch
 * (DOWN/MOVE) and, once the total exceeds [degradationBudgetMs], delivers the
 * rest of that gesture asynchronously (MOVEs coalesced by
 * [KuiklyRenderCoreContextScheduler.scheduleReplaceableTask]). The first
 * finger's DOWN, the last finger's UP, and CANCEL always stay synchronous:
 * they start/end the gesture and carry consumption semantics that must not be
 * reordered. Secondary fingers' DOWN/UP stay synchronous while the gesture is
 * healthy, but go async once it has degraded — a stuck context thread would
 * otherwise stack another full timeout per finger change (a two-finger pinch
 * could reach ~5s of main-thread wait). Per-gesture main-thread wait is
 * thereby bounded to roughly one budget.
 *
 * Gesture lifecycle with multi-touch: only the *first* finger's DOWN
 * (pointerCount == 1) starts a fresh gesture and only the *last* finger's UP
 * (pointerCount == 1) or a CANCEL ends it. Additional fingers joining or
 * leaving (POINTER_DOWN/POINTER_UP, surfaced as touchDown/touchUp with
 * pointerCount >= 2) must not reset an in-progress gesture's budget or
 * degradation.
 *
 * Semantics of a degraded gesture: `consumed`/`preventTouch` decisions may
 * arrive late, so a parent container can scroll a few frames before a child
 * drag engages. The next gesture self-heals; no state is corrupted. OHOS
 * already runs moves asynchronously, so this mode has in-tree precedent.
 *
 * Threading: main-thread confined (touch callbacks and main-queue tasks both
 * run on the main thread). Not synchronized on purpose.
 */
internal class TouchSyncGuard(
    private val degradationBudgetMs: Long = DEFAULT_DEGRADATION_BUDGET_MS,
) {

    internal data class DegradeEvent(
        val instanceId: String,
        val tag: Int,
        val triggerEventName: String,
        val accumulatedWaitMs: Long,
    )

    private class GestureState(
        var accumulatedWaitMs: Long,
        var degraded: Boolean,
    )

    private val gestures = HashMap<String, GestureState>()

    /** Token shared by all coalescing/replaceable dispatches of one view's moves. */
    fun coalesceToken(instanceId: String, tag: Int): String = "$instanceId:$tag"

    fun isManagedTouchEvent(eventName: String): Boolean =
        eventName == TOUCH_DOWN || eventName == TOUCH_MOVE ||
            eventName == TOUCH_UP || eventName == TOUCH_CANCEL

    /**
     * Decide whether [eventName] for (instanceId, tag) keeps the synchronous
     * context-thread round-trip. Non-touch events are unmanaged (always sync).
     * [pointerCount] is the number of active pointers carried by the event
     * (ACTION_DOWN/UP carry 1; POINTER_DOWN/UP carry >= 2).
     */
    fun shouldDispatchSync(instanceId: String, tag: Int, eventName: String, pointerCount: Int = 1): Boolean {
        if (!isManagedTouchEvent(eventName)) {
            return true
        }
        val key = coalesceToken(instanceId, tag)
        return when (eventName) {
            TOUCH_DOWN -> {
                if (pointerCount <= 1) {
                    // First finger starts a fresh gesture: always synchronous.
                    gestures.remove(key)
                    true
                } else {
                    // A secondary finger joining an already-degraded gesture gains
                    // nothing from blocking the main thread: go async (posted, never
                    // coalesced). Healthy gestures keep the synchronous round-trip.
                    gestures[key]?.degraded != true
                }
            }
            TOUCH_MOVE -> gestures[key]?.degraded != true
            TOUCH_UP -> {
                if (pointerCount <= 1) {
                    // Last finger ends the gesture: always synchronous.
                    gestures.remove(key)
                    true
                } else {
                    gestures[key]?.degraded != true
                }
            }
            else -> { // TOUCH_CANCEL: always the whole gesture
                gestures.remove(key)
                true
            }
        }
    }

    /**
     * Record the outcome of one synchronous touch dispatch. Only DOWN and
     * MOVE accumulate budget and may degrade: UP/CANCEL end the gesture, so
     * their stalls neither degrade nor get reported. Returns a [DegradeEvent]
     * exactly once per degraded gesture (for tracing); null otherwise.
     */
    fun onSyncDispatchCompleted(
        instanceId: String,
        tag: Int,
        eventName: String,
        timedOut: Boolean,
        waitMs: Long,
    ): DegradeEvent? {
        if (eventName != TOUCH_DOWN && eventName != TOUCH_MOVE) {
            return null
        }
        if (waitMs <= 0L && !timedOut) {
            return null
        }
        val key = coalesceToken(instanceId, tag)
        val state = gestures.getOrPut(key) {
            GestureState(accumulatedWaitMs = 0L, degraded = false)
        }
        state.accumulatedWaitMs += waitMs
        if (state.degraded || state.accumulatedWaitMs < degradationBudgetMs) {
            return null
        }
        state.degraded = true
        return DegradeEvent(instanceId, tag, eventName, state.accumulatedWaitMs)
    }

    companion object {
        const val TOUCH_DOWN = "touchDown"
        const val TOUCH_MOVE = "touchMove"
        const val TOUCH_UP = "touchUp"
        const val TOUCH_CANCEL = "touchCancel"

        /**
         * Per-gesture main-thread wait budget. Matches the per-dispatch
         * timeout (1000ms): a single full timeout degrades immediately, and
         * repeated shorter stalls (e.g. 200ms per frame) degrade after a few
         * dispatches instead of accumulating without bound.
         */
        const val DEFAULT_DEGRADATION_BUDGET_MS = 1000L
    }
}
