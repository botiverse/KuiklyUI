/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI;
 */

package com.tencent.kuikly.core.render.android.expand.component.text

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.TextPaint
import android.text.style.ReplacementSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class KRCustomUnderlineSpanTest {

    @Test
    fun colorAndThicknessUseBreakableNativeSpan() {
        val span = createKRCustomUnderlineSpan(
            color = Color.BLUE,
            thickness = 2f,
            offset = null,
        )

        assertTrue(span is KRNativeCustomUnderlineSpan)
        assertFalse(span is ReplacementSpan)

        val paint = TextPaint().apply {
            color = Color.BLACK
            textSize = 16f
        }
        (span as KRNativeCustomUnderlineSpan).updateDrawState(paint)

        assertTrue(paint.isUnderlineText)
        assertEquals(Color.BLUE, paint.underlineColor)
        assertEquals(2f, paint.underlineThickness)
    }

    @Test
    fun customThicknessDoesNotInstallAtomicReplacementSpan() {
        val text = "a long decorated link label that must wrap"
        val span = createKRCustomUnderlineSpan(
            color = null,
            thickness = 2f,
            offset = null,
        )
        val spannable = SpannableString(text).apply {
            setSpan(span, 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        assertEquals(
            0,
            spannable.getSpans(0, spannable.length, ReplacementSpan::class.java).size,
        )
        assertEquals(
            1,
            spannable.getSpans(0, spannable.length, KRNativeCustomUnderlineSpan::class.java).size,
        )
    }

    @Test
    fun explicitOffsetKeepsLegacyCustomDrawingPath() {
        val span = createKRCustomUnderlineSpan(
            color = Color.BLUE,
            thickness = 2f,
            offset = 3f,
        )

        assertTrue(span is ReplacementSpan)
    }
}
