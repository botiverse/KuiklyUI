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

package com.tencent.kuikly.core.render.android.expand.component.text

import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Test

class HRLineHeightSpanGlyphTest {

    @Test
    fun lineHeightCanCenterAroundActualGlyphBounds() {
        val metrics = Paint.FontMetricsInt().apply {
            top = -12
            ascent = -12
            descent = 4
            bottom = 4
        }

        HRLineHeightSpan.applyCenteredLineHeight(
            height = 20,
            fm = metrics,
            center = -5
        )

        assertEquals(-15, metrics.top)
        assertEquals(-15, metrics.ascent)
        assertEquals(5, metrics.bottom)
        assertEquals(5, metrics.descent)
        assertEquals(20, metrics.bottom - metrics.top)
    }

    @Test
    fun centeredLineHeightKeepsExactOddHeight() {
        val metrics = Paint.FontMetricsInt().apply {
            top = -10
            ascent = -10
            descent = 3
            bottom = 3
        }

        HRLineHeightSpan.applyCenteredLineHeight(
            height = 17,
            fm = metrics,
            center = -4
        )

        assertEquals(-12, metrics.top)
        assertEquals(-12, metrics.ascent)
        assertEquals(5, metrics.bottom)
        assertEquals(5, metrics.descent)
        assertEquals(17, metrics.bottom - metrics.top)
    }

    @Test
    fun stableCenteringIgnoresGlyphBoundsSoTypingNeverShiftsTheLine() {
        // Editable fields center on font metrics only (task #355): the chosen
        // center must be identical no matter what text is measured, so
        // appending an ascender glyph ("as" -> "asf") cannot re-center the
        // line. paint=null exercises the same guard the stable flag uses.
        val span = HRLineHeightSpan(20, centerOnGlyphBounds = false)
        val metrics = Paint.FontMetricsInt().apply {
            top = -12; ascent = -12; descent = 4; bottom = 4
        }

        val centerShort = span.resolveLineCenter("as", 0, 2, null, metrics)
        val centerTall = span.resolveLineCenter("asf", 0, 3, null, metrics)

        assertEquals((-12 + 4) / 2, centerShort)
        assertEquals(centerShort, centerTall)

        HRLineHeightSpan.applyCenteredLineHeight(20, metrics, centerTall)
        assertEquals(20, metrics.bottom - metrics.top)
    }

}
