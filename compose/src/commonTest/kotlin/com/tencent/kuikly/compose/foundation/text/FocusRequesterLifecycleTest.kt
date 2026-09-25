package com.tencent.kuikly.compose.foundation.text

import com.tencent.kuikly.compose.ui.focus.FocusRequester
import kotlin.test.Test
import kotlin.test.assertFalse

class FocusRequesterLifecycleTest {
    @Test
    fun detachedRequesterSilentlyRejectsLateNativeFocusIntent() {
        assertFalse(FocusRequester().focusIfAttached())
    }
}
