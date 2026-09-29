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

package com.tencent.kuikly.core.render.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutSizeFormatterTest {

    @Test
    fun preservesZeroIntegersAndExactHundredths() {
        assertEquals("0.00|10.00", formatLayoutSizeForReport(0f, 10f))
        assertEquals("10.01|10.02", formatLayoutSizeForReport(10.01f, 10.02f))
        assertEquals("0.07|1.10", formatLayoutSizeForReport(0.07f, 1.10f))
    }

    @Test
    fun roundsOnlyValuesBeyondAHundredthBoundaryUpward() {
        assertEquals("0.01|0.01", formatLayoutSizeForReport(0.001f, 0.004f))
        assertEquals("0.01|0.01", formatLayoutSizeForReport(0.005f, 0.009f))
        assertEquals("10.01|10.01", formatLayoutSizeForReport(10.0001f, 10.009f))
        assertEquals("10.02|-10.00", formatLayoutSizeForReport(10.0101f, -10.001f))
    }

    @Test
    fun doesNotLoopOrPerturbNonFiniteDefensiveInputs() {
        assertTrue(ceilLayoutSizeToHundredth(Float.NaN).isNaN())
        assertEquals(
            Double.POSITIVE_INFINITY,
            ceilLayoutSizeToHundredth(Float.POSITIVE_INFINITY),
            0.0,
        )
    }

    @Test
    fun preservesLegacyNullSerialization() {
        assertEquals("0.00|0.00", formatLayoutSizeForReport(null, null))
        assertEquals("0.00|0.00", formatLayoutSizeForReport(1f, null))
    }
}
