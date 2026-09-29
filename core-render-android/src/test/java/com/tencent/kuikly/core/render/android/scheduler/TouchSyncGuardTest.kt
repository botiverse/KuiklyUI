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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchSyncGuardTest {

    private val guard = TouchSyncGuard()

    @Test
    fun `healthy gesture keeps every event synchronous`() {
        assertTrue(guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_DOWN))
        assertTrue(guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_MOVE))
        guard.onSyncDispatchCompleted(INSTANCE, TAG, TouchSyncGuard.TOUCH_MOVE, timedOut = false, waitMs = 3)
        assertTrue(guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_MOVE))
        assertTrue(guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_UP))
    }

    @Test
    fun `single full timeout degrades and reports once`() {
        assertTrue(guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_DOWN))

        val degrade = guard.onSyncDispatchCompleted(
            INSTANCE, TAG, TouchSyncGuard.TOUCH_MOVE, timedOut = true, waitMs = 1000,
        )
        assertEquals(TouchSyncGuard.TOUCH_MOVE, degrade?.triggerEventName)
        assertEquals(1000L, degrade?.accumulatedWaitMs)

        assertFalse(guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_MOVE))
        assertNull(
            guard.onSyncDispatchCompleted(INSTANCE, TAG, TouchSyncGuard.TOUCH_MOVE, timedOut = true, waitMs = 1000),
        )

        // Last finger UP ends the gesture; the next one is synchronous again.
        assertTrue(guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_UP, pointerCount = 1))
        assertTrue(guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_DOWN, pointerCount = 1))
        assertTrue(guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_MOVE))
    }

    @Test
    fun `repeated sub-timeout stalls degrade once the budget is spent`() {
        // The 200ms-per-frame injection scenario: no single dispatch times out,
        // but the accumulated wait crosses the budget after a few moves.
        guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_DOWN)
        repeat(3) {
            assertTrue(guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_MOVE))
            assertNull(
                guard.onSyncDispatchCompleted(INSTANCE, TAG, TouchSyncGuard.TOUCH_MOVE, timedOut = false, waitMs = 250),
            )
        }
        // 3 x 250ms = 750ms < budget: still synchronous. The 4th dispatch
        // crosses 1000ms and reports degradation.
        assertTrue(guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_MOVE))
        val degrade = guard.onSyncDispatchCompleted(
            INSTANCE, TAG, TouchSyncGuard.TOUCH_MOVE, timedOut = false, waitMs = 250,
        )
        assertEquals(1000L, degrade?.accumulatedWaitMs)
        assertFalse(guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_MOVE))
    }

    @Test
    fun `second finger joining or leaving does not reset degradation`() {
        guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_DOWN, pointerCount = 1)
        guard.onSyncDispatchCompleted(INSTANCE, TAG, TouchSyncGuard.TOUCH_MOVE, timedOut = true, waitMs = 1000)
        assertFalse(guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_MOVE))

        // POINTER_DOWN (finger 2 joins): must NOT reset the degraded gesture,
        // and must NOT block the main thread again — it goes async.
        assertFalse(guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_DOWN, pointerCount = 2))
        assertFalse(guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_MOVE))

        // POINTER_UP (finger 2 leaves): async too, and must NOT end the gesture.
        assertFalse(guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_UP, pointerCount = 2))
        assertFalse(guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_MOVE))

        // Only the last finger's UP ends it (synchronously).
        assertTrue(guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_UP, pointerCount = 1))
        assertTrue(guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_MOVE))
    }

    @Test
    fun `healthy gesture keeps secondary finger events synchronous`() {
        guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_DOWN, pointerCount = 1)
        assertTrue(guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_DOWN, pointerCount = 2))
        assertTrue(guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_UP, pointerCount = 2))
        assertTrue(guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_MOVE))
    }

    @Test
    fun `cancel always ends the gesture regardless of pointer count`() {
        guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_DOWN, pointerCount = 1)
        guard.onSyncDispatchCompleted(INSTANCE, TAG, TouchSyncGuard.TOUCH_MOVE, timedOut = true, waitMs = 1000)
        assertFalse(guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_MOVE))

        assertTrue(guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_CANCEL, pointerCount = 2))
        assertTrue(guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_MOVE))
    }

    @Test
    fun `up and cancel timeouts never degrade nor report`() {
        guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_DOWN, pointerCount = 1)
        // UP dispatch clears the gesture; its timeout must not create a new
        // degraded record (and therefore not emit a second log line).
        assertTrue(guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_UP, pointerCount = 1))
        assertNull(
            guard.onSyncDispatchCompleted(INSTANCE, TAG, TouchSyncGuard.TOUCH_UP, timedOut = true, waitMs = 1000),
        )
        assertTrue(guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_MOVE))
        assertNull(
            guard.onSyncDispatchCompleted(INSTANCE, TAG, TouchSyncGuard.TOUCH_CANCEL, timedOut = true, waitMs = 1000),
        )
    }

    @Test
    fun `down timeout degrades the gesture it starts`() {
        assertTrue(guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_DOWN, pointerCount = 1))
        val degrade = guard.onSyncDispatchCompleted(
            INSTANCE, TAG, TouchSyncGuard.TOUCH_DOWN, timedOut = true, waitMs = 1000,
        )
        assertEquals(TouchSyncGuard.TOUCH_DOWN, degrade?.triggerEventName)
        assertFalse(guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_MOVE))
    }

    @Test
    fun `degradation is scoped per view`() {
        guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_DOWN)
        guard.onSyncDispatchCompleted(INSTANCE, TAG, TouchSyncGuard.TOUCH_MOVE, timedOut = true, waitMs = 1000)

        assertFalse(guard.shouldDispatchSync(INSTANCE, TAG, TouchSyncGuard.TOUCH_MOVE))
        assertTrue(guard.shouldDispatchSync(INSTANCE, OTHER_TAG, TouchSyncGuard.TOUCH_MOVE))
        assertTrue(guard.shouldDispatchSync(OTHER_INSTANCE, TAG, TouchSyncGuard.TOUCH_MOVE))
    }

    @Test
    fun `non touch sync events are never managed`() {
        assertTrue(guard.shouldDispatchSync(INSTANCE, TAG, "willDragEnd"))
        assertNull(
            guard.onSyncDispatchCompleted(INSTANCE, TAG, "willDragEnd", timedOut = true, waitMs = 1000),
        )
        assertTrue(guard.shouldDispatchSync(INSTANCE, TAG, "willDragEnd"))
    }

    @Test
    fun `coalesce token embeds instance and tag`() {
        assertEquals("7:42", guard.coalesceToken("7", 42))
    }

    private companion object {
        const val INSTANCE = "1"
        const val OTHER_INSTANCE = "2"
        const val TAG = 100
        const val OTHER_TAG = 200
    }
}
