/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2026 Tencent. All rights reserved.
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

package com.tencent.kuikly.compose.material3

import kotlin.test.Test
import kotlin.test.assertEquals

class PullToRefreshStateTest {
    @Test
    fun releaseKeepsRefreshingStateWhenInsetIsHeld() {
        assertEquals(
            PullState.REFRESHING,
            pullStateAfterRefreshRelease(holdRefreshInset = true)
        )
    }

    @Test
    fun releaseReturnsIdleWhenInsetIsNotHeld() {
        assertEquals(
            PullState.IDLE,
            pullStateAfterRefreshRelease(holdRefreshInset = false)
        )
    }

    @Test
    fun thresholdCrossingDoesNotScheduleEndDragInsetWhenHoldIsDisabled() {
        assertEquals(
            0f,
            pullRefreshEndDragInset(
                holdRefreshInset = false,
                refreshThreshold = 80f
            )
        )
    }

    @Test
    fun thresholdCrossingKeepsLegacyEndDragInsetByDefault() {
        assertEquals(
            80f,
            pullRefreshEndDragInset(
                holdRefreshInset = true,
                refreshThreshold = 80f
            )
        )
    }
}
