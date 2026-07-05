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

    // Line placement must be content-independent (Slock task #355): the same
    // font metrics resolve to the same line box no matter which string is
    // measured, so typing an ascender glyph ("as" -> "asf") can never
    // re-center the line. Ink-bounds centering (b992014) is retired; the
    // span distributes leading around ascent/descent (CSS half-leading).
    @Test
    fun sameMetricsResolveToSameLineBoxRegardlessOfText() {
        val span = HRLineHeightSpan(20)
        fun metrics() = Paint.FontMetricsInt().apply {
            top = -12; ascent = -12; descent = 4; bottom = 4
        }

        val short = metrics()
        val tall = metrics()
        span.chooseHeight("as", 0, 2, 0, 20, short)
        span.chooseHeight("asf", 0, 3, 0, 20, tall)

        assertEquals(short.top, tall.top)
        assertEquals(short.ascent, tall.ascent)
        assertEquals(short.descent, tall.descent)
        assertEquals(short.bottom, tall.bottom)
        assertEquals(20, short.bottom - short.top)
    }

    @Test
    fun exactLineHeightIsKeptForOddHeights() {
        val span = HRLineHeightSpan(17)
        val metrics = Paint.FontMetricsInt().apply {
            top = -10; ascent = -10; descent = 3; bottom = 3
        }

        span.chooseHeight("x", 0, 1, 0, 17, metrics)

        assertEquals(17, metrics.bottom - metrics.top)
        assertEquals(metrics.top, metrics.ascent)
        assertEquals(metrics.bottom, metrics.descent)
    }
}
