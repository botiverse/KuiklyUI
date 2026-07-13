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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KuiklyScrollInfoTest {
    @Test
    fun forceClearAllowsUserScrollAfterMismatchedProgrammaticCallback() {
        val info = KuiklyScrollInfo().apply {
            forceClearIgnoreOffset = true
            ignoreScrollOffset = IntOffset(x = 0, y = 120)
        }

        assertFalse(info.consumeIgnoredScrollOffset(offsetX = 0f, offsetY = 118f, epsilon = 0.5))
        assertNull(info.ignoreScrollOffset)
        assertFalse(info.consumeIgnoredScrollOffset(offsetX = 0f, offsetY = 220f, epsilon = 0.5))
    }

    @Test
    fun forceClearStillSkipsTheMatchingProgrammaticCallback() {
        val info = KuiklyScrollInfo().apply {
            forceClearIgnoreOffset = true
            ignoreScrollOffset = IntOffset(x = 0, y = 120)
        }

        assertTrue(info.consumeIgnoredScrollOffset(offsetX = 0f, offsetY = 120f, epsilon = 0.5))
        assertNull(info.ignoreScrollOffset)
    }

    @Test
    fun legacyModeRetainsGuardUntilMatchingCallbackArrives() {
        val info = KuiklyScrollInfo().apply {
            forceClearIgnoreOffset = false
            ignoreScrollOffset = IntOffset(x = 0, y = 120)
        }

        assertTrue(info.consumeIgnoredScrollOffset(offsetX = 0f, offsetY = 118f, epsilon = 0.5))
        assertTrue(info.consumeIgnoredScrollOffset(offsetX = 0f, offsetY = 120f, epsilon = 0.5))
        assertNull(info.ignoreScrollOffset)
        assertFalse(info.consumeIgnoredScrollOffset(offsetX = 0f, offsetY = 220f, epsilon = 0.5))
    }
}
