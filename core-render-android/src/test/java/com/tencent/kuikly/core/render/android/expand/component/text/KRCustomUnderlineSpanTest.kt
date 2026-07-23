/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI;
 */

package com.tencent.kuikly.core.render.android.expand.component.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ReplacementSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KRCustomUnderlineSpanTest {

    @Test
    fun colorAndThicknessUseOneCustomUnderlineMechanism() {
        val span = createKRCustomUnderlineSpan(
            color = Color.BLUE,
            thickness = 4f,
            offset = null,
        )

        assertTrue(span is KRNativeCustomUnderlineSpan)
        assertFalse(span is ReplacementSpan)

        val paint = testPaint()
        (span as KRNativeCustomUnderlineSpan).updateDrawState(paint)

        assertFalse(paint.isUnderlineText)
        assertEquals(Color.BLUE, paint.underlineColor)
        assertEquals(4f, paint.underlineThickness)
        assertTrue(renderBluePixelCount(span) > 0)
    }

    @Test
    fun colorOnlyUsesNonZeroPlatformDefaultThicknessAndActuallyDraws() {
        val span = createKRCustomUnderlineSpan(
            color = Color.BLUE,
            thickness = null,
            offset = null,
        ) as KRNativeCustomUnderlineSpan
        val paint = testPaint()

        span.updateDrawState(paint)

        assertFalse(paint.isUnderlineText)
        assertEquals(Color.BLUE, paint.underlineColor)
        assertTrue(paint.underlineThickness > 0f)
        assertTrue(renderBluePixelCount(span) > 0)
    }

    @Test
    fun customUnderlineRemainsBreakableAcrossRealStaticLayoutLines() {
        val text = "a long decorated link label that must wrap across multiple lines"
        val span = createKRCustomUnderlineSpan(
            color = Color.BLUE,
            thickness = 2f,
            offset = null,
        )
        val spannable = SpannableString(text).apply {
            setSpan(span, 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val layout = buildLayout(spannable, width = 120)

        assertTrue(layout.lineCount > 1)
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
    @Config(sdk = [28])
    fun api21To28UsesPublicOrdinaryUnderlineFallback() {
        val span = createKRCustomUnderlineSpan(
            color = Color.BLUE,
            thickness = 4f,
            offset = null,
        ) as KRNativeCustomUnderlineSpan
        val paint = testPaint()

        span.updateDrawState(paint)

        assertTrue(paint.isUnderlineText)
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

    private fun renderBluePixelCount(span: KRNativeCustomUnderlineSpan): Int {
        val text = SpannableString("Underline").apply {
            setSpan(span, 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val layout = buildLayout(text, width = 240)
        val bitmap = Bitmap.createBitmap(240, 96, Bitmap.Config.ARGB_8888)
        layout.draw(Canvas(bitmap))
        var bluePixels = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) == Color.BLUE) bluePixels++
            }
        }
        return bluePixels
    }

    private fun buildLayout(text: CharSequence, width: Int): StaticLayout =
        StaticLayout.Builder
            .obtain(text, 0, text.length, testPaint(), width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()

    private fun testPaint(): TextPaint =
        TextPaint().apply {
            color = Color.BLACK
            textSize = 40f
            isAntiAlias = false
        }
}
