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

package com.tencent.kuikly.core.render.android.expand.component.list

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OverScrollResistanceTest {

    @Test
    fun shortEndDragKeepsExistingInitialResistance() {
        val delta = calculateOverScrollDelta(
            currentTranslation = 0f,
            translationOffset = -20f,
            resistanceScalePx = 1_500f,
            maxTranslationPx = 800f
        )

        assertEquals(-10f, delta, 0.0001f)
    }

    @Test
    fun shortStartDragKeepsExistingInitialResistance() {
        val delta = calculateOverScrollDelta(
            currentTranslation = 0f,
            translationOffset = 20f,
            resistanceScalePx = 1_500f,
            maxTranslationPx = 800f
        )

        assertEquals(10f, delta, 0.0001f)
    }

    @Test
    fun longEndDragApproachesFiniteBoundary() {
        val maxTranslation = 800f
        var translation = 0f

        repeat(10_000) {
            translation += calculateOverScrollDelta(
                currentTranslation = translation,
                translationOffset = -10f,
                resistanceScalePx = 1_500f,
                maxTranslationPx = maxTranslation
            )
        }

        assertTrue("end overscroll must stay inside the finite boundary", translation >= -maxTranslation)
        assertTrue("a long drag should approach the boundary smoothly", translation < -790f)
    }

    @Test
    fun longStartDragApproachesFiniteBoundary() {
        val maxTranslation = 800f
        var translation = 0f

        repeat(10_000) {
            translation += calculateOverScrollDelta(
                currentTranslation = translation,
                translationOffset = 10f,
                resistanceScalePx = 1_500f,
                maxTranslationPx = maxTranslation
            )
        }

        assertTrue("start overscroll must stay inside the finite boundary", translation <= maxTranslation)
        assertTrue("a long drag should approach the boundary smoothly", translation > 790f)
    }

    @Test
    fun startDragCanCrossRefreshThresholdBeforeApproachingBoundary() {
        val refreshThreshold = 240f
        val maxTranslation = 480f
        var translation = 0f

        repeat(200) {
            translation += calculateOverScrollDelta(
                currentTranslation = translation,
                translationOffset = 20f,
                resistanceScalePx = 1_500f,
                maxTranslationPx = maxTranslation
            )
        }

        assertTrue("pull-to-refresh must be able to cross its threshold", translation > refreshThreshold)
        assertTrue("start overscroll must remain below the finite boundary", translation <= maxTranslation)
    }

    @Test
    fun singleLargeMoveCannotCrossFiniteBoundary() {
        val maxTranslation = 800f
        val delta = calculateOverScrollDelta(
            currentTranslation = -790f,
            translationOffset = -10_000f,
            resistanceScalePx = 1_500f,
            maxTranslationPx = maxTranslation
        )

        assertEquals(-10f, delta, 0.0001f)
    }

    @Test
    fun singleLargeStartMoveCannotCrossFiniteBoundary() {
        val maxTranslation = 800f
        val delta = calculateOverScrollDelta(
            currentTranslation = 790f,
            translationOffset = 10_000f,
            resistanceScalePx = 1_500f,
            maxTranslationPx = maxTranslation
        )

        assertEquals(10f, delta, 0.0001f)
    }

    @Test
    fun topTranslationCrossingZeroUsesBottomBoundaryForRemainder() {
        val maxTranslation = 800f
        val current = 100f
        val delta = calculateOverScrollDelta(
            currentTranslation = current,
            translationOffset = -10_000f,
            resistanceScalePx = 1_500f,
            maxTranslationPx = maxTranslation
        )

        assertEquals(-maxTranslation, current + delta, 0.0001f)
    }

    @Test
    fun bottomTranslationCrossingZeroUsesTopBoundaryForRemainder() {
        val maxTranslation = 800f
        val current = -100f
        val delta = calculateOverScrollDelta(
            currentTranslation = current,
            translationOffset = 10_000f,
            resistanceScalePx = 1_500f,
            maxTranslationPx = maxTranslation
        )

        assertEquals(maxTranslation, current + delta, 0.0001f)
    }

    @Test
    fun smallReverseMoveBeforeZeroKeepsExistingResistance() {
        val current = 100f
        val translationOffset = -100f
        val scale = 1_500f
        val expected = translationOffset / (2f + abs(current) / scale)

        val delta = calculateOverScrollDelta(
            currentTranslation = current,
            translationOffset = translationOffset,
            resistanceScalePx = scale,
            maxTranslationPx = 800f
        )

        assertEquals(expected, delta, 0.0001f)
        assertTrue("a small reverse move must not jump across zero", current + delta > 0f)
    }

    @Test
    fun nestedParentDeltaCrossingZeroUsesSameBottomBoundary() {
        val maxTranslation = 800f
        val current = 100f
        val parentDy = 10_000f
        val translationOffset = -parentDy

        val delta = calculateOverScrollDelta(
            currentTranslation = current,
            translationOffset = translationOffset,
            resistanceScalePx = 1_500f,
            maxTranslationPx = maxTranslation
        )

        assertEquals(-maxTranslation, current + delta, 0.0001f)
    }

    @Test
    fun nestedParentDeltaCrossingZeroUsesSameTopBoundary() {
        val maxTranslation = 800f
        val current = -100f
        val parentDy = -10_000f
        val translationOffset = -parentDy

        val delta = calculateOverScrollDelta(
            currentTranslation = current,
            translationOffset = translationOffset,
            resistanceScalePx = 1_500f,
            maxTranslationPx = maxTranslation
        )

        assertEquals(maxTranslation, current + delta, 0.0001f)
    }

    @Test
    fun unboundedPathsKeepExistingResistance() {
        val current = 300f
        val translationOffset = 120f
        val scale = 1_500f
        val expected = translationOffset / (2f + abs(current) / scale)

        val delta = calculateOverScrollDelta(
            currentTranslation = current,
            translationOffset = translationOffset,
            resistanceScalePx = scale
        )

        assertEquals(expected, delta, 0.0001f)
    }

    @Test
    fun movingBackFromEndKeepsExistingResistance() {
        val current = -500f
        val translationOffset = 100f
        val scale = 1_500f
        val expected = translationOffset / (2f + abs(current) / scale)

        val delta = calculateOverScrollDelta(
            currentTranslation = current,
            translationOffset = translationOffset,
            resistanceScalePx = scale
        )

        assertEquals(expected, delta, 0.0001f)
    }
}
