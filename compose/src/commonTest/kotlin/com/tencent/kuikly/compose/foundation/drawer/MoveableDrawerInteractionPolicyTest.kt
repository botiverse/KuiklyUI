package com.tencent.kuikly.compose.foundation.drawer

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MoveableDrawerInteractionPolicyTest {
    @Test
    fun defaultPolicyPreservesExistingPagerInteraction() {
        val policy = moveableDrawerInteractionPolicy(userScrollEnabled = true)

        assertTrue(policy.pagerUserScrollEnabled)
    }

    @Test
    fun disabledPolicyRemovesOnlyUserGestureAndScrollSemantics() {
        val policy = moveableDrawerInteractionPolicy(userScrollEnabled = false)

        assertFalse(policy.pagerUserScrollEnabled)
    }
}
