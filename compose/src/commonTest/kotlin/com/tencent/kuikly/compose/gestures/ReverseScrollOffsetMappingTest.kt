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

import com.tencent.kuikly.compose.gestures.ReverseScrollOffsetMapping.Move
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReverseScrollOffsetMappingTest {
    private val mapping = ReverseScrollOffsetMapping

    // A 9000px list in a 2400px viewport: native range 0..6600.
    private val contentSize = 9_000
    private val viewportSize = 2_400
    private val max = 6_600

    @Test
    fun normalLayoutMapsEveryOffsetUnchanged() {
        for (native in listOf(-120, 0, 1_234, max, max + 90)) {
            assertEquals(native, mapping.logicalFromNative(native, max, reverseLayout = false))
            assertEquals(native, mapping.nativeTargetFromLogical(native, max, reverseLayout = false))
            assertEquals(native.toFloat(), mapping.renderFromLogical(native.toFloat(), max, reverseLayout = false))
            assertEquals(native, mapping.boundedNativeOffset(native, max, clamp = false))
        }
        assertEquals(37, mapping.nativeDeltaFromLogical(37, reverseLayout = false))
    }

    @Test
    fun reverseLayoutMirrorsOffsetsAroundTheNativeMaximum() {
        assertEquals(max, mapping.maxNativeOffset(contentSize, viewportSize))
        // Item 0 at the native bottom: logical start is the native maximum.
        assertEquals(0, mapping.logicalFromNative(max, max, reverseLayout = true))
        assertEquals(max, mapping.logicalFromNative(0, max, reverseLayout = true))
        assertEquals(max, mapping.nativeTargetFromLogical(0, max, reverseLayout = true))
        assertEquals(6_100, mapping.nativeTargetFromLogical(500, max, reverseLayout = true))
        assertEquals(6_100f, mapping.renderFromLogical(500f, max, reverseLayout = true))
        assertEquals(-37, mapping.nativeDeltaFromLogical(37, reverseLayout = true))
        for (logical in listOf(0, 1, 2_500, max)) {
            val native = mapping.nativeTargetFromLogical(logical, max, reverseLayout = true)
            assertEquals(logical, mapping.logicalFromNative(native, max, reverseLayout = true))
        }
    }

    @Test
    fun reverseBounceStaysVisibleButTargetsAreClamped() {
        // An iOS bounce past the list start reads as a negative logical offset (ignored at top).
        assertEquals(-30, mapping.logicalFromNative(max + 30, max, reverseLayout = true))
        assertEquals(max + 30, mapping.logicalFromNative(-30, max, reverseLayout = true))
        // Programmatic targets never leave the native range, so their echo can match.
        assertEquals(max, mapping.nativeTargetFromLogical(-30, max, reverseLayout = true))
        assertEquals(0, mapping.nativeTargetFromLogical(max + 30, max, reverseLayout = true))
        // Android reverse lists (bounce off) clamp the reported offset.
        assertEquals(max, mapping.boundedNativeOffset(max + 30, max, clamp = true))
        assertEquals(0, mapping.boundedNativeOffset(-30, max, clamp = true))
    }

    @Test
    fun reverseTopExpansionMovesNativeUpByTheLogicalDelta() {
        // Native reached the list start (logical 0) while Compose is not at item 0: the top
        // expansion adds logical offset, i.e. moves native away from the bottom edge.
        assertEquals(
            Move(logicalOffset = 3_000, nativeOffset = 3_600, nativeDelta = -3_000),
            mapping.reverseLogicalMove(logicalOffset = 0, logicalDelta = 3_000, maxNativeOffset = max),
        )
        // Pull-back when Compose reached item 0 but native did not: back to the native end.
        assertEquals(
            Move(logicalOffset = 0, nativeOffset = max, nativeDelta = 1_200),
            mapping.reverseLogicalMove(logicalOffset = 1_200, logicalDelta = -1_200, maxNativeOffset = max),
        )
    }

    @Test
    fun reverseMoveIsClampedToTheNativeRange() {
        assertEquals(
            Move(logicalOffset = max, nativeOffset = 0, nativeDelta = -1_600),
            mapping.reverseLogicalMove(logicalOffset = 5_000, logicalDelta = 4_000, maxNativeOffset = max),
        )
        assertEquals(
            Move(logicalOffset = 0, nativeOffset = max, nativeDelta = 0),
            mapping.reverseLogicalMove(logicalOffset = 0, logicalDelta = -50, maxNativeOffset = max),
        )
    }

    @Test
    fun contentGrowthAtTheLogicalStartKeepsItemZeroAtTheBottom() {
        // Index 0 at the bottom (logical 0) and the estimate grows by 1500px: native and the
        // placed children move down together, native stays at the new maximum.
        val move = mapping.reanchor(
            anchoredContentSize = contentSize,
            contentSize = contentSize + 1_500,
            viewportSize = viewportSize,
            logicalOffset = 0,
        )
        assertEquals(Move(logicalOffset = 0, nativeOffset = max + 1_500, nativeDelta = 1_500), move)
        assertEquals(0, mapping.logicalFromNative(move.nativeOffset, max + 1_500, reverseLayout = true))
    }

    @Test
    fun contentChangeMidListKeepsTheLogicalOffset() {
        val grown = mapping.reanchor(contentSize, contentSize + 4_500, viewportSize, logicalOffset = 2_000)
        assertEquals(Move(logicalOffset = 2_000, nativeOffset = 9_100, nativeDelta = 4_500), grown)
        // The exact size pin can shrink the estimate: everything moves up by the same amount.
        val shrunk = mapping.reanchor(contentSize, 7_000, viewportSize, logicalOffset = 2_000)
        assertEquals(Move(logicalOffset = 2_000, nativeOffset = 2_600, nativeDelta = -2_000), shrunk)
        assertEquals(Move(2_000, 4_600, 0), mapping.reanchor(contentSize, contentSize, viewportSize, 2_000))
    }

    @Test
    fun shortReverseListHasNoNativeRange() {
        val move = mapping.reanchor(anchoredContentSize = 800, contentSize = 1_600, viewportSize = viewportSize, logicalOffset = 0)
        assertEquals(Move(logicalOffset = 0, nativeOffset = 0, nativeDelta = 0), move)
        // Growing past the viewport opens the range below the anchored children.
        assertEquals(
            Move(logicalOffset = 0, nativeOffset = 600, nativeDelta = 600),
            mapping.reanchor(anchoredContentSize = 1_600, contentSize = 3_000, viewportSize = viewportSize, logicalOffset = 0),
        )
    }

    @Test
    fun viewportShrinkKeepsTheLogicalOffsetAndMovesNativeDown() {
        // Keyboard opens (2400 -> 1500): item 0 stays at the bottom edge.
        assertEquals(
            Move(logicalOffset = 0, nativeOffset = 7_500, nativeDelta = 0),
            mapping.reverseViewportChange(logicalOffset = 0, contentSize = contentSize, newViewportSize = 1_500),
        )
        assertEquals(
            Move(logicalOffset = 1_000, nativeOffset = 6_500, nativeDelta = 0),
            mapping.reverseViewportChange(logicalOffset = 1_000, contentSize = contentSize, newViewportSize = 1_500),
        )
    }

    @Test
    fun viewportGrowthClampsTheLogicalOffsetToTheNewRange() {
        assertEquals(
            Move(logicalOffset = 5_000, nativeOffset = 0, nativeDelta = 0),
            mapping.reverseViewportChange(logicalOffset = 6_000, contentSize = contentSize, newViewportSize = 4_000),
        )
    }

    @Test
    fun nativeMoveIsSkippedWhenNoEchoWouldFollow() {
        // Already there: no callback would arrive to release the echo guard.
        assertFalse(mapping.needsNativeMove(target = 600, pendingTarget = null, reportedNativeOffset = 600.9f, epsilon = 1.5f))
        assertTrue(mapping.needsNativeMove(target = 600, pendingTarget = null, reportedNativeOffset = 540f, epsilon = 1.5f))
        // Already heading there: the pending echo settles it.
        assertFalse(mapping.needsNativeMove(target = 600, pendingTarget = 600, reportedNativeOffset = 0f, epsilon = 1.5f))
        assertTrue(mapping.needsNativeMove(target = 600, pendingTarget = 900, reportedNativeOffset = 600f, epsilon = 1.5f))
    }
}
