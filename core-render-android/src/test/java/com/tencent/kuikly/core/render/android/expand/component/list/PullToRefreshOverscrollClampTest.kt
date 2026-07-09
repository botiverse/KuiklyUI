package com.tencent.kuikly.core.render.android.expand.component.list

import org.junit.Assert.assertEquals
import org.junit.Test

class PullToRefreshOverscrollClampTest {
    @Test
    fun clampsPositiveTopOverscrollToRefreshThreshold() {
        assertEquals(
            240f,
            clampPullToRefreshTranslation(value = 440f, enabled = true, maxTranslation = 240f),
            0f
        )
    }

    @Test
    fun keepsTranslationWithinThreshold() {
        assertEquals(
            180f,
            clampPullToRefreshTranslation(value = 180f, enabled = true, maxTranslation = 240f),
            0f
        )
    }

    @Test
    fun leavesNonPullToRefreshAndBottomOverscrollUnchanged() {
        assertEquals(
            440f,
            clampPullToRefreshTranslation(value = 440f, enabled = false, maxTranslation = 240f),
            0f
        )
        assertEquals(
            -440f,
            clampPullToRefreshTranslation(value = -440f, enabled = true, maxTranslation = 240f),
            0f
        )
        assertEquals(
            440f,
            clampPullToRefreshTranslation(value = 440f, enabled = true, maxTranslation = 0f),
            0f
        )
    }
}
