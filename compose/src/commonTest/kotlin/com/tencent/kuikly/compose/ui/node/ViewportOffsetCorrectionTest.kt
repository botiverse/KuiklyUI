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

package com.tencent.kuikly.compose.ui.node

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ViewportOffsetCorrectionTest {

    @Test
    fun shrinkingViewportAdoptsNativeBottomWhenComposeOffsetIsStale() {
        assertEquals(
            expected = 12_768,
            actual = correctedComposeOffsetForViewportChange(
                composeOffset = 11_869,
                nativeOffset = 12_768,
                contentSize = 13_667,
                previousViewportSize = 1_774,
                newViewportSize = 899,
                programmaticOffsetPending = false,
            ),
        )
    }

    @Test
    fun shrinkingViewportSkipsWriteBeforeNativeBottomEcho() {
        assertNull(
            actual = correctedComposeOffsetForViewportChange(
                composeOffset = 11_869,
                nativeOffset = 11_869,
                contentSize = 13_643,
                previousViewportSize = 1_774,
                newViewportSize = 899,
                programmaticOffsetPending = false,
            ),
        )
    }

    @Test
    fun shrinkingViewportSkipsWriteAtMidList() {
        assertNull(
            actual = correctedComposeOffsetForViewportChange(
                composeOffset = 4_000,
                nativeOffset = 4_000,
                contentSize = 13_643,
                previousViewportSize = 1_774,
                newViewportSize = 899,
                programmaticOffsetPending = false,
            ),
        )
    }

    @Test
    fun expandingViewportClampsOffsetToNewBottom() {
        assertEquals(
            expected = 11_869,
            actual = correctedComposeOffsetForViewportChange(
                composeOffset = 12_744,
                nativeOffset = 12_744,
                contentSize = 13_643,
                previousViewportSize = 899,
                newViewportSize = 1_774,
                programmaticOffsetPending = false,
            ),
        )
    }

    @Test
    fun pendingProgrammaticOwnerPreservesComposeTargetBeforeNativeEcho() {
        assertNull(
            actual = correctedComposeOffsetForViewportChange(
                composeOffset = 4_200,
                nativeOffset = 0,
                contentSize = 13_643,
                previousViewportSize = 1_774,
                newViewportSize = 899,
                programmaticOffsetPending = true,
            ),
        )
    }

    @Test
    fun pendingProgrammaticOwnerWinsEvenBeforeContentSizeArrives() {
        assertNull(
            actual = correctedComposeOffsetForViewportChange(
                composeOffset = 4_200,
                nativeOffset = 0,
                contentSize = 0,
                previousViewportSize = 0,
                newViewportSize = 899,
                programmaticOffsetPending = true,
            ),
        )
    }

    @Test
    fun equalRoundedViewportSkipsCorrection() {
        assertNull(
            actual = correctedComposeOffsetForViewportChange(
                composeOffset = 400,
                nativeOffset = 450,
                contentSize = 500,
                previousViewportSize = 100,
                newViewportSize = 100,
                programmaticOffsetPending = false,
            ),
        )
    }

    @Test
    fun emptyContentResetsComposeOffsetWhenNativeOwnsState() {
        assertEquals(
            expected = 0,
            actual = correctedComposeOffsetForViewportChange(
                composeOffset = 120,
                nativeOffset = 120,
                contentSize = 0,
                previousViewportSize = 899,
                newViewportSize = 1_774,
                programmaticOffsetPending = false,
            ),
        )
    }

    @Test
    fun negativeNativeOffsetClampsToZero() {
        assertEquals(
            expected = 0,
            actual = correctedComposeOffsetForViewportChange(
                composeOffset = 120,
                nativeOffset = -20,
                contentSize = 500,
                previousViewportSize = 899,
                newViewportSize = 1_774,
                programmaticOffsetPending = false,
            ),
        )
    }

    @Test
    fun expansionWithNoScrollableRangeResetsToZero() {
        assertEquals(
            expected = 0,
            actual = correctedComposeOffsetForViewportChange(
                composeOffset = 120,
                nativeOffset = 120,
                contentSize = 500,
                previousViewportSize = 899,
                newViewportSize = 600,
                programmaticOffsetPending = false,
            ),
        )
    }
}
