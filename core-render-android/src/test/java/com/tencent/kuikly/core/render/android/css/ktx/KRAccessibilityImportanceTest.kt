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
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class KRAccessibilityImportanceTest {
    @Test
    fun hiddenRoleExcludesTheEntireNativeSubtree() {
        assertEquals(
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
            resolveAccessibilityImportance(description = "", role = "hidden")
        )
    }

    @Test
    fun testTagDoesNotExposeAHiddenNativeSubtree() {
        assertEquals(
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
            resolveTestTagAccessibilityImportance(role = "hidden")
        )
    }

    @Test
    fun testTagRestoresAContainerAfterHiddenRoleIsCleared() {
        assertEquals(
            View.IMPORTANT_FOR_ACCESSIBILITY_YES,
            resolveTestTagAccessibilityImportance(role = "none")
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

    @Test
    fun hiddenNodeInfoExposesNoFocusableOrActionableSemantics() {
        val info = AccessibilityNodeInfo.obtain().apply {
            isVisibleToUser = true
            isFocusable = true
            isClickable = true
            isLongClickable = true
            text = "Search"
            contentDescription = "Search"
            addAction(AccessibilityNodeInfo.ACTION_CLICK)
            addAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        }

        configureHiddenAccessibilityNodeInfo(info)

        assertFalse(info.isVisibleToUser)
        assertFalse(info.isFocusable)
        assertFalse(info.isClickable)
        assertFalse(info.isLongClickable)
        assertNull(info.text)
        assertNull(info.contentDescription)
        assertEquals(0, info.actions and AccessibilityNodeInfo.ACTION_CLICK)
        assertEquals(0, info.actions and AccessibilityNodeInfo.ACTION_LONG_CLICK)
    }

    private companion object {
        const val TextViewRole = "android.widget.TextView"
    }
}
