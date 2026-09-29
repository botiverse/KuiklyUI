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

import kotlin.math.abs

/**
 * Pure mapping between the native ScrollView offset and the Compose logical scroll offset.
 *
 * Compose keeps one logical offset ([KuiklyScrollInfo.composeOffset]) that grows away from
 * item 0. With `reverseLayout = true` item 0 sits at the native bottom (or end), so the
 * logical offset is the distance from the native maximum offset:
 * `logical = maxNative - native`. Without reverse layout both offsets are the same value and
 * every function here is the identity, so the default path is unchanged.
 *
 * The native maximum depends on the content size, which Kuikly estimates and grows while
 * scrolling. A reverse list keeps its native coordinate frame anchored to one content size;
 * when the content size changes, the native offset and the already placed children move by
 * the same amount so the logical offset, and the items on screen, stay put.
 */
internal object ReverseScrollOffsetMapping {

    /** Native scroll range for [contentSize] inside [viewportSize]. */
    fun maxNativeOffset(contentSize: Int, viewportSize: Int): Int =
        maxOf(0, contentSize - viewportSize)

    /** Logical offset reported to Compose for a native offset (unclamped, so bounces stay visible). */
    fun logicalFromNative(nativeOffset: Int, maxNativeOffset: Int, reverseLayout: Boolean): Int =
        if (reverseLayout) maxNativeOffset - nativeOffset else nativeOffset

    /** Native content coordinate offset used to place children for a logical offset. */
    fun renderFromLogical(logicalOffset: Float, maxNativeOffset: Int, reverseLayout: Boolean): Float =
        if (reverseLayout) maxNativeOffset - logicalOffset else logicalOffset

    /**
     * Native offset to scroll to for a logical offset. Reverse targets are clamped into the
     * native range: a target outside it is clamped by the platform and never echoes exactly.
     */
    fun nativeTargetFromLogical(logicalOffset: Int, maxNativeOffset: Int, reverseLayout: Boolean): Int =
        if (reverseLayout) {
            maxNativeOffset - logicalOffset.coerceIn(0, maxNativeOffset)
        } else {
            logicalOffset
        }

    /** Native delta for a logical delta. */
    fun nativeDeltaFromLogical(logicalDelta: Int, reverseLayout: Boolean): Int =
        if (reverseLayout) -logicalDelta else logicalDelta

    /**
     * Native offset the scroll handlers map. Only reverse lists without native bounce (Android)
     * clamp: an Android over-scroll translates the content view past the range, which a reverse
     * mapping would read as a jump at the list start.
     */
    fun boundedNativeOffset(nativeOffset: Int, maxNativeOffset: Int, clamp: Boolean): Int =
        if (clamp) nativeOffset.coerceIn(0, maxNativeOffset) else nativeOffset

    /** A programmatic move of a reverse list: logical result and the native move that realises it. */
    data class Move(val logicalOffset: Int, val nativeOffset: Int, val nativeDelta: Int)

    /**
     * Apply a logical delta to a reverse list at [logicalOffset]. The result stays inside the
     * native range so its echo can be matched exactly.
     */
    fun reverseLogicalMove(logicalOffset: Int, logicalDelta: Int, maxNativeOffset: Int): Move {
        val from = nativeTargetFromLogical(logicalOffset, maxNativeOffset, reverseLayout = true)
        val toLogical = (logicalOffset + logicalDelta).coerceIn(0, maxNativeOffset)
        val to = nativeTargetFromLogical(toLogical, maxNativeOffset, reverseLayout = true)
        return Move(logicalOffset = toLogical, nativeOffset = to, nativeDelta = to - from)
    }

    /**
     * Re-anchor a reverse list after its content size changed from [anchoredContentSize] to
     * [contentSize]: children shift by [Move.nativeDelta] and the native offset moves to
     * [Move.nativeOffset], keeping [logicalOffset].
     */
    fun reanchor(
        anchoredContentSize: Int,
        contentSize: Int,
        viewportSize: Int,
        logicalOffset: Int,
    ): Move {
        val oldMax = maxNativeOffset(anchoredContentSize, viewportSize)
        val newMax = maxNativeOffset(contentSize, viewportSize)
        return Move(
            logicalOffset = logicalOffset,
            nativeOffset = nativeTargetFromLogical(logicalOffset, newMax, reverseLayout = true),
            nativeDelta = newMax - oldMax,
        )
    }

    /**
     * Keep the logical offset of a reverse list across a viewport resize (for example the
     * keyboard): item 0 stays at the bottom edge. Children need no shift, because Compose
     * re-places them relative to the new viewport, which cancels the change of the native maximum.
     */
    fun reverseViewportChange(logicalOffset: Int, contentSize: Int, newViewportSize: Int): Move {
        val newMax = maxNativeOffset(contentSize, newViewportSize)
        val logical = logicalOffset.coerceIn(0, newMax)
        return Move(
            logicalOffset = logical,
            nativeOffset = nativeTargetFromLogical(logical, newMax, reverseLayout = true),
            nativeDelta = 0,
        )
    }

    /**
     * Whether a native offset move to [target] must be issued (and its echo guarded).
     * A move to where native already is, or already heading, emits no scroll callback,
     * so arming the echo guard for it would swallow user input until the guard times out.
     */
    fun needsNativeMove(target: Int, pendingTarget: Int?, reportedNativeOffset: Float, epsilon: Float): Boolean =
        if (pendingTarget != null) {
            pendingTarget != target
        } else {
            abs(reportedNativeOffset - target) > epsilon
        }
}
