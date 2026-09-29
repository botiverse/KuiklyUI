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
import kotlin.test.assertNull

class KuiklyScrollInfoTest {
    private var nowNanos = 0L

    private fun info(pending: IntOffset?, dragging: Boolean = false) =
        KuiklyScrollInfo().apply {
            nanoClock = { nowNanos }
            isDragging = dragging
            ignoreScrollOffset = pending
        }

    private fun KuiklyScrollInfo.resolve(offsetY: Float) =
        resolveNativeScrollEvent(offsetX = 0f, offsetY = offsetY, epsilon = 0.5)

    @Test
    fun exactProgrammaticEchoIsConsumed() {
        val info = info(IntOffset(x = 0, y = 120))

        assertEquals(KuiklyScrollInfo.NativeScrollEventDisposition.Consume, info.resolve(120f))
        assertNull(info.ignoreScrollOffset)
        assertEquals(120f, info.composeOffset)
    }

    // task #318: an off-target echo of a programmatic move (native clamped or
    // split it) must never be dispatched to compose as a phantom user scroll —
    // that phantom walked a bottom-anchored 50-row list to the top, serially
    // composing every row and stalling the Kotlin thread for seconds.
    @Test
    fun offTargetCallbackSyncsWithoutDispatchAndKeepsGuard() {
        val info = info(IntOffset(x = 0, y = 4200))

        assertEquals(KuiklyScrollInfo.NativeScrollEventDisposition.SyncOnly, info.resolve(118f))
        assertEquals(IntOffset(x = 0, y = 4200), info.ignoreScrollOffset)
    }

    // Android top bounce: overscroll callbacks emitted before a deferred >= 3000dp
    // top expansion is applied must not disarm the guard, or the late real echo
    // is dispatched as a +3000dp user scroll and the list lands at its end.
    @Test
    fun lateEchoAfterBounceCallbacksIsStillConsumed() {
        val info = info(IntOffset(x = 0, y = 9000))

        assertEquals(KuiklyScrollInfo.NativeScrollEventDisposition.SyncOnly, info.resolve(-24f))
        assertEquals(KuiklyScrollInfo.NativeScrollEventDisposition.SyncOnly, info.resolve(-8f))
        nowNanos += 300_000_000L
        assertEquals(KuiklyScrollInfo.NativeScrollEventDisposition.Consume, info.resolve(9000f))
        assertNull(info.ignoreScrollOffset)
        assertEquals(9000f, info.composeOffset)
        assertEquals(KuiklyScrollInfo.NativeScrollEventDisposition.Dispatch, info.resolve(9010f))
    }

    @Test
    fun userDragReleasesGuardAndDispatches() {
        val info = info(IntOffset(x = 0, y = 4200), dragging = true)

        // A finger on the screen owns the viewport: never swallow real input.
        assertEquals(KuiklyScrollInfo.NativeScrollEventDisposition.Dispatch, info.resolve(118f))
        assertNull(info.ignoreScrollOffset)
    }

    @Test
    fun lostEchoExpiresIntoOneSyncThenDispatch() {
        val info = info(IntOffset(x = 0, y = 4200))

        nowNanos += KuiklyScrollInfo.IGNORE_SCROLL_OFFSET_TIMEOUT_NANOS
        assertEquals(KuiklyScrollInfo.NativeScrollEventDisposition.SyncOnly, info.resolve(118f))
        assertNull(info.ignoreScrollOffset)
        assertEquals(KuiklyScrollInfo.NativeScrollEventDisposition.Dispatch, info.resolve(130f))
    }

    @Test
    fun rearmingRestartsTheTimeout() {
        val info = info(IntOffset(x = 0, y = 4200))

        nowNanos += KuiklyScrollInfo.IGNORE_SCROLL_OFFSET_TIMEOUT_NANOS - 1
        info.ignoreScrollOffset = IntOffset(x = 0, y = 7200)
        nowNanos += 1
        assertEquals(KuiklyScrollInfo.NativeScrollEventDisposition.SyncOnly, info.resolve(118f))
        assertEquals(IntOffset(x = 0, y = 7200), info.ignoreScrollOffset)
    }

    @Test
    fun eventWithoutPendingProgrammaticMoveDispatches() {
        val info = info(null)

        assertEquals(KuiklyScrollInfo.NativeScrollEventDisposition.Dispatch, info.resolve(118f))
    }

    // Reverse layout (no bound ScrollView: viewport 0, so the native range is the content size).
    private fun reverseInfo(pending: IntOffset?, contentSize: Int = 9_000, dragging: Boolean = false) =
        KuiklyScrollInfo().apply {
            nanoClock = { nowNanos }
            reverseLayout = true
            currentContentSize = contentSize
            isDragging = dragging
            ignoreScrollOffset = pending
        }

    @Test
    fun reverseMappingIsDerivedFromTheComposeOffset() {
        val info = reverseInfo(null).apply { composeOffset = 1_500f }

        assertEquals(7_500, info.nativeTargetFromLogical(info.composeOffset))
        assertEquals(1_500, info.logicalOffsetFromNative(7_500))
        assertEquals(7_500f, info.renderOffset())
        assertEquals(1_500, info.logicalContentOffset)
        // No page data: not Android, so no clamp (an iOS bounce stays visible).
        assertEquals(9_030, info.boundedNativeScrollOffset(9_030))
    }

    @Test
    fun normalLayoutHelpersAreTheIdentity() {
        val info = info(null).apply {
            composeOffset = 1_500f
            contentOffset = 1_480
        }

        assertEquals(1_500, info.nativeTargetFromLogical(info.composeOffset))
        assertEquals(7_500, info.logicalOffsetFromNative(7_500))
        assertEquals(1_500f, info.renderOffset())
        assertEquals(1_480, info.logicalContentOffset)
        assertEquals(-30, info.boundedNativeScrollOffset(-30))
    }

    @Test
    fun reverseEchoRestoresTheLogicalTarget() {
        // Target 7500 in a 9000 frame is logical 1500.
        val info = reverseInfo(IntOffset(x = 0, y = 7_500)).apply { composeOffset = 20f }

        assertEquals(KuiklyScrollInfo.NativeScrollEventDisposition.Consume, info.resolve(7_500f))
        assertNull(info.ignoreScrollOffset)
        assertEquals(1_500f, info.composeOffset)
    }

    // A re-anchor moves the reverse native frame; a callback emitted before native applied it
    // is in the old frame. Dispatching it under a finger was the fast-drag index jump.
    @Test
    fun reverseOffTargetCallbackIsDroppedEvenWhileDragging() {
        val info = reverseInfo(IntOffset(x = 0, y = 7_500), dragging = true).apply { composeOffset = 1_500f }

        assertEquals(KuiklyScrollInfo.NativeScrollEventDisposition.Drop, info.resolve(6_000f))
        assertEquals(IntOffset(x = 0, y = 7_500), info.ignoreScrollOffset)
        assertEquals(1_500f, info.composeOffset)
        assertEquals(KuiklyScrollInfo.NativeScrollEventDisposition.Consume, info.resolve(7_500f))
        assertEquals(KuiklyScrollInfo.NativeScrollEventDisposition.Dispatch, info.resolve(7_400f))
    }

    @Test
    fun reverseLostEchoStillExpiresIntoSync() {
        val info = reverseInfo(IntOffset(x = 0, y = 7_500), dragging = true)

        nowNanos += KuiklyScrollInfo.IGNORE_SCROLL_OFFSET_TIMEOUT_NANOS
        assertEquals(KuiklyScrollInfo.NativeScrollEventDisposition.SyncOnly, info.resolve(6_000f))
        assertNull(info.ignoreScrollOffset)
    }
}
