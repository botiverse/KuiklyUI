/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI.
 */

package com.tencent.kuikly.compose.views

import com.tencent.kuikly.compose.gestures.KuiklyScrollInfo
import com.tencent.kuikly.core.views.KRNestedScrollMode
import com.tencent.kuikly.core.views.ScrollOffsetCommitToken
import com.tencent.kuikly.core.views.ScrollerAttr
import com.tencent.kuikly.core.views.ScrollerAttr.Companion.NESTED_SCROLL
import com.tencent.kuikly.core.views.ScrollerEvent
import com.tencent.kuikly.core.views.ScrollerView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ScrollViewExTest {

    @Test
    fun composeChildrenShiftOnlyWhenTargetDiffers() {
        assertFalse(shouldShiftComposeChildren(composeOffset = 120, targetOffset = 120))
        assertTrue(shouldShiftComposeChildren(composeOffset = 80, targetOffset = 120))
    }

    @Test
    fun synchronousTerminalDoesNotExposeOrRestoreTemporaryNestedScrollState() {
        val view = ScrollerView<ScrollerAttr, ScrollerEvent>()
        val info = KuiklyScrollInfo()
        view.getViewAttr().nestedScroll(
            KRNestedScrollMode.PARENT_FIRST,
            KRNestedScrollMode.PARENT_FIRST,
        )
        val originalPolicy = view.getViewAttr().getProp(NESTED_SCROLL)
        view.setContentOffset(
            offsetX = 0f,
            offsetY = 0f,
            writeToken = ScrollOffsetCommitToken(
                generation = view.offsetWriteGeneration,
                requiresNativeIdle = false,
                operationGeneration = 2L,
            ),
        )
        var policyObservedByTerminal: Any? = null

        view.applyOffsetDelta(
            delta = 10,
            kuiklyInfo = info,
            writeToken = ScrollOffsetCommitToken(
                generation = view.offsetWriteGeneration,
                requiresNativeIdle = false,
                operationGeneration = 1L,
            ),
            isStillCurrent = { true },
        ) { _, _ ->
            policyObservedByTerminal = view.getViewAttr().getProp(NESTED_SCROLL)
            view.getViewAttr().nestedScroll(
                KRNestedScrollMode.SELF_FIRST,
                KRNestedScrollMode.SELF_FIRST,
            )
        }

        assertEquals(originalPolicy, policyObservedByTerminal)
        assertNotEquals(originalPolicy, view.getViewAttr().getProp(NESTED_SCROLL))
    }
}
