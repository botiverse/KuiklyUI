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

package com.tencent.kuikly.core.render.android.css.ktx

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Test

class KRAccessibilityImportanceTest {
    @Test
    fun hiddenRoleExcludesTheEntireNativeSubtree() {
        assertEquals(
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
            resolveAccessibilityImportance(description = "", role = "hidden")
        )
    }

    @Test
    fun noneRoleRestoresDescendantTraversal() {
        assertEquals(
            View.IMPORTANT_FOR_ACCESSIBILITY_NO,
            resolveAccessibilityImportance(description = "", role = "none")
        )
    }

    @Test
    fun describedRoleRemainsAccessible() {
        assertEquals(
            View.IMPORTANT_FOR_ACCESSIBILITY_YES,
            resolveAccessibilityImportance(description = "Search", role = TextViewRole)
        )
    }

    private companion object {
        const val TextViewRole = "android.widget.TextView"
    }
}
