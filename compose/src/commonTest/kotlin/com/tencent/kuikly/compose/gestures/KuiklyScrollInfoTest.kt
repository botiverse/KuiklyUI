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

package com.tencent.kuikly.compose.gestures

import com.tencent.kuikly.compose.ui.unit.IntOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KuiklyScrollInfoTest {
    @Test
    fun diagnosticGestureEpochIsAllocatedAtAdmissionAndRetainedThroughTerminal() {
        val events = mutableListOf<String>()
        val info = KuiklyScrollInfo().apply {
            diagnosticObserver = { events += "${it.stage}:${it.detail}" }
        }

        val epoch = info.admitDiagnosticGesture("app_edge_pointer")
        info.emitDiagnosticGestureTerminal("app_edge_drag_end")

        assertTrue(epoch > 0L)
        assertEquals(epoch, info.diagnosticGestureEpoch)
        assertTrue(events.all { "gestureEpoch=$epoch" in it })
        assertTrue(events.last().startsWith("app_edge_drag_end:"))
    }

    @Test
    fun setContentOffsetTraceAttributesPublisherAndBracketsCall() {
        val events = mutableListOf<String>()
        val info = KuiklyScrollInfo().apply {
            diagnosticCommandGeneration = 3L
            diagnosticObserver = { events += "${it.stage}:${it.detail}" }
        }

        var called = false
        info.traceSetContentOffset(
            publisher = "native_will_drag_end_snap",
            callSite = "test.callSite",
            targetOffsetX = 540f,
            targetOffsetY = 0f,
            animated = true,
            spring = true
        ) { called = true }

        assertTrue(called)
        assertEquals(2, events.size)
        assertTrue(events[0].startsWith("set_content_offset_enter:"))
        assertTrue(events[1].startsWith("set_content_offset_return:"))
        assertTrue(events.all { "publisher=native_will_drag_end_snap" in it })
        assertTrue(events.all { "callSite=test.callSite" in it })
        assertTrue(events.all { "targetOffsetX=540.0" in it })
        assertTrue(events.all { "programmaticGeneration=3" in it })
    }

    @Test
    fun mismatchedProgrammaticCallbackClearsGuardAndProceeds() {
        val info = KuiklyScrollInfo().apply {
            ignoreScrollOffset = IntOffset(x = 0, y = 120)
        }

        assertFalse(info.consumeIgnoredScrollOffset(offsetX = 0f, offsetY = 118f, epsilon = 0.5))
        assertNull(info.ignoreScrollOffset)
        assertFalse(info.consumeIgnoredScrollOffset(offsetX = 0f, offsetY = 220f, epsilon = 0.5))
    }

    @Test
    fun matchingProgrammaticCallbackClearsGuardAndIsSkipped() {
        val info = KuiklyScrollInfo().apply {
            ignoreScrollOffset = IntOffset(x = 0, y = 120)
        }

        assertTrue(info.consumeIgnoredScrollOffset(offsetX = 0f, offsetY = 120f, epsilon = 0.5))
        assertNull(info.ignoreScrollOffset)
    }

    // task #318: an off-target echo of a programmatic move (native clamped or
    // split it) must never be dispatched to compose as a phantom user scroll —
    // that phantom walked a bottom-anchored 50-row list to the top, serially
    // composing every row and stalling the Kotlin thread for seconds.
    @Test
    fun exactProgrammaticEchoIsConsumed() {
        val info = KuiklyScrollInfo().apply {
            ignoreScrollOffset = IntOffset(x = 0, y = 120)
        }

        assertEquals(
            KuiklyScrollInfo.NativeScrollEventDisposition.Consume,
            info.resolveNativeScrollEvent(offsetX = 0f, offsetY = 120f, epsilon = 0.5)
        )
        assertNull(info.ignoreScrollOffset)
    }

    @Test
    fun offTargetProgrammaticEchoSyncsWithoutDispatch() {
        val info = KuiklyScrollInfo().apply {
            ignoreScrollOffset = IntOffset(x = 0, y = 4200)
            isDragging = false
        }

        // Native clamped the applied 4200 down to 118: still our own move's
        // echo, so bookkeeping may sync but compose must not scroll.
        assertEquals(
            KuiklyScrollInfo.NativeScrollEventDisposition.SyncOnly,
            info.resolveNativeScrollEvent(offsetX = 0f, offsetY = 118f, epsilon = 0.5)
        )
        assertNull(info.ignoreScrollOffset)
    }

    @Test
    fun offTargetEchoWhileUserDragsStillDispatches() {
        val info = KuiklyScrollInfo().apply {
            ignoreScrollOffset = IntOffset(x = 0, y = 4200)
            isDragging = true
        }

        // A finger on the screen owns the viewport: never swallow real input.
        assertEquals(
            KuiklyScrollInfo.NativeScrollEventDisposition.Dispatch,
            info.resolveNativeScrollEvent(offsetX = 0f, offsetY = 118f, epsilon = 0.5)
        )
    }

    @Test
    fun eventWithoutPendingProgrammaticMoveDispatches() {
        val info = KuiklyScrollInfo().apply { isDragging = false }

        assertEquals(
            KuiklyScrollInfo.NativeScrollEventDisposition.Dispatch,
            info.resolveNativeScrollEvent(offsetX = 0f, offsetY = 118f, epsilon = 0.5)
        )
    }

    @Test
    fun programmaticEchoGuardIsSingleShot() {
        val info = KuiklyScrollInfo().apply {
            ignoreScrollOffset = IntOffset(x = 0, y = 4200)
            isDragging = false
        }

        assertEquals(
            KuiklyScrollInfo.NativeScrollEventDisposition.SyncOnly,
            info.resolveNativeScrollEvent(offsetX = 0f, offsetY = 118f, epsilon = 0.5)
        )
        // The follow-up event has no pending move recorded: genuine scroll.
        assertEquals(
            KuiklyScrollInfo.NativeScrollEventDisposition.Dispatch,
            info.resolveNativeScrollEvent(offsetX = 0f, offsetY = 130f, epsilon = 0.5)
        )
    }
}
