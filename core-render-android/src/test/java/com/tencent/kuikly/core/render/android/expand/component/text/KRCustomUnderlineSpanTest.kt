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
import android.graphics.Path
import android.graphics.Region
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
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

        assertTrue(span is KRSkipInkCustomUnderlineSpan)
        assertFalse(span is ReplacementSpan)
        val marker = span as KRSkipInkCustomUnderlineSpan
        val paint = testPaint().apply {
            isUnderlineText = true
            underlineColor = Color.RED
            underlineThickness = 1f
        }
        marker.updateDrawState(paint)

        assertFalse(paint.isUnderlineText)
        assertEquals(0, paint.underlineColor)
        assertEquals(0f, paint.underlineThickness)
        assertTrue(renderBluePixelCount(marker) > 0)
    }

    @Test
    fun colorOnlyUsesNonZeroPlatformDefaultThicknessAndActuallyDraws() {
        val span = createKRCustomUnderlineSpan(
            color = Color.BLUE,
            thickness = null,
            offset = null,
        ) as KRSkipInkCustomUnderlineSpan
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
            spannable.getSpans(0, spannable.length, KRSkipInkCustomUnderlineSpan::class.java).size,
        )
        val bitmap = render(layout)
        repeat(layout.lineCount) { line ->
            val bluePixels =
                (layout.getLineTop(line) until layout.getLineBottom(line)).sumOf { y ->
                    (0 until bitmap.width).count { x -> bitmap.getPixel(x, y) == Color.BLUE }
                }
            assertTrue("line $line must contain a custom underline segment", bluePixels > 0)
        }
    }

    @Test
    fun resolvedCurrentColorAndGlyphOutlineProduceNarrowSkipInkGap() {
        val span = createKRCustomUnderlineSpan(
            color = null,
            thickness = 4f,
            offset = 2f,
        ) as KRSkipInkCustomUnderlineSpan
        val value = "aqa"
        val text = styledText(span, value, foregroundColor = Color.BLUE)
        val layout = buildLayout(text, width = 240)
        val bitmap = render(layout)
        val underlineRows = underlineBandRows(layout, line = 0, thickness = 4f, offset = 2f)
        val glyphLeft = layout.getPrimaryHorizontal(1).toInt()
        val glyphRight = layout.getPrimaryHorizontal(2).toInt()
        val transparentGapPixels =
            underlineRows.sumOf { y ->
                (glyphLeft until glyphRight).count { x ->
                    bitmap.getPixel(x, y) == Color.TRANSPARENT
                }
            }
        val blueBeforeGap =
            underlineRows.sumOf { y ->
                (0 until glyphLeft).count { x -> bitmap.getPixel(x, y) == Color.BLUE }
            }
        val blueAfterGap =
            underlineRows.sumOf { y ->
                (glyphRight until bitmap.width).count { x -> bitmap.getPixel(x, y) == Color.BLUE }
            }
        val bluePixels = countPixels(bitmap, Color.BLUE)
        val blackPixels =
            countPixels(bitmap, Color.BLACK)

        assertTrue(bluePixels > 2)
        assertTrue("currentColor underline must exist before the q gap", blueBeforeGap > 0)
        assertTrue("currentColor underline must exist after the q gap", blueAfterGap > 0)
        assertTrue("same-color glyphs must leave a visible background skip gap", transparentGapPixels > 0)
        assertEquals("null decoration color must inherit resolved link blue", 0, blackPixels)

        val centerRow = layout.getLineBaseline(0) + 2
        val centerRowGapWidth =
            (glyphLeft until glyphRight).count { x ->
                bitmap.getPixel(x, centerRow) == Color.TRANSPARENT
            }
        assertTrue("q descender must interrupt the center underline row", centerRowGapWidth > 0)
        assertTrue("q skip halo must remain visible at production scale", centerRowGapWidth >= 2)
        assertTrue(
            "skip-ink must clear only the q outline, not its full advance",
            centerRowGapWidth < glyphRight - glyphLeft,
        )
    }

    @Test
    fun softWrapLineEndGlyphUsesSameLinePositionForSkipGap() {
        val span = createKRCustomUnderlineSpan(
            color = null,
            thickness = 4f,
            offset = null,
        ) as KRSkipInkCustomUnderlineSpan
        val value = "aaaaagx"
        val width = testPaint().measureText("aaaaag").toInt() + 1
        val text = styledText(span, value, foregroundColor = Color.BLUE)
        val layout = buildLayout(text, width = width)
        val bitmap = render(layout)

        assertEquals(6, layout.getLineVisibleEnd(0))
        assertEquals('g', value[layout.getLineVisibleEnd(0) - 1])
        val glyphLeft = layout.getPrimaryHorizontal(5).toInt()
        val glyphRight = (glyphLeft + testPaint().measureText("g")).toInt()
        val gapPixels =
            underlineBandRows(layout, line = 0, thickness = 4f).sumOf { y ->
                (glyphLeft until glyphRight).count { x ->
                    bitmap.getPixel(x, y) == Color.TRANSPARENT
                }
            }
        val trailingBlue =
            underlineBandRows(layout, line = 0, thickness = 4f).sumOf { y ->
                (layout.getLineRight(0).toInt() until bitmap.width).count { x ->
                    bitmap.getPixel(x, y) == Color.BLUE
                }
            }

        assertTrue("soft-wrap line-end g must punch a gap at its real x", gapPixels > 0)
        assertEquals("soft-wrap line must not underline unused trailing width", 0, trailingBlue)
    }

    @Test
    fun backgroundColorSpanDoesNotCoverOverlayUnderline() {
        val span = createKRCustomUnderlineSpan(
            color = null,
            thickness = 4f,
            offset = null,
        ) as KRSkipInkCustomUnderlineSpan
        val bitmap =
            render(
                span = span,
                value = "link",
                foregroundColor = Color.BLUE,
                backgroundColor = Color.YELLOW,
            )

        assertTrue(countPixels(bitmap, Color.BLUE) > 0)
    }

    @Test
    fun hardNewlineDoesNotUnderlineTrailingBlankArea() {
        val span = createKRCustomUnderlineSpan(
            color = Color.BLUE,
            thickness = 4f,
            offset = null,
        ) as KRSkipInkCustomUnderlineSpan
        val value = "abc\nx"
        val text = styledText(span, value)
        val layout = buildLayout(text, width = 240)
        val bitmap = render(layout)
        val actualEnd = testPaint().measureText("abc").toInt()
        val trailingBlue =
            underlineBandRows(layout, line = 0, thickness = 4f).sumOf { y ->
                (actualEnd until bitmap.width).count { x -> bitmap.getPixel(x, y) == Color.BLUE }
            }

        assertEquals(0, trailingBlue)
    }

    @Test
    fun mixedBidiUnderlineNeverEscapesLayoutSelectionGeometry() {
        val value = "אב12cdEF"
        val start = 1
        val end = 7
        val span = createKRCustomUnderlineSpan(
            color = Color.BLUE,
            thickness = 4f,
            offset = null,
        ) as KRSkipInkCustomUnderlineSpan
        val text = SpannableString(value).apply {
            setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val layout = buildLayout(text, width = 240)
        val bitmap = render(layout)
        val selectionPath = Path().also { layout.getSelectionPath(start, end, it) }
        val selectionRegion = Region().apply {
            setPath(selectionPath, Region(0, 0, bitmap.width, bitmap.height))
        }
        var bluePixels = 0

        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) == Color.BLUE) {
                    bluePixels++
                    assertTrue("blue pixel escaped bidi selection at $x,$y", selectionRegion.contains(x, y))
                }
            }
        }
        assertTrue("mixed bidi range must draw at least one underline pixel", bluePixels > 0)
    }

    @Test
    @Config(sdk = [28])
    fun api21To28DrawsExactCustomUnderlineWithoutNonSdkPaintFields() {
        val span = createKRCustomUnderlineSpan(
            color = Color.BLUE,
            thickness = 4f,
            offset = null,
        ) as KRSkipInkCustomUnderlineSpan

        assertTrue(renderBluePixelCount(span) > 0)
    }

    @Test
    fun explicitOffsetUsesWrapSafeDrawerPath() {
        val span = createKRCustomUnderlineSpan(
            color = Color.BLUE,
            thickness = 2f,
            offset = 3f,
        )

        assertTrue(span is KRSkipInkCustomUnderlineSpan)
        assertFalse(span is ReplacementSpan)
        val marker = span as KRSkipInkCustomUnderlineSpan
        assertEquals(3f, marker.offset)

        val text = styledText(marker, "a long link that wraps over several rows")
        val layout = buildLayout(text, width = 120)
        val bitmap = render(layout)
        assertTrue(layout.lineCount > 1)
        repeat(layout.lineCount) { line ->
            val underlineRows = underlineBandRows(layout, line, thickness = 2f, offset = 3f)
            val bluePixels =
                underlineRows.sumOf { y ->
                    (0 until bitmap.width).count { x -> bitmap.getPixel(x, y) == Color.BLUE }
                }
            assertTrue("explicit-offset underline must remain on wrapped line $line", bluePixels > 0)
        }
    }

    private fun renderBluePixelCount(span: KRSkipInkCustomUnderlineSpan): Int {
        val bitmap = render(span, "Underline")
        var bluePixels = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) == Color.BLUE) bluePixels++
            }
        }
        return bluePixels
    }

    private fun render(
        span: KRSkipInkCustomUnderlineSpan,
        value: String,
        foregroundColor: Int? = null,
        backgroundColor: Int? = null,
    ): Bitmap {
        val text = styledText(span, value, foregroundColor, backgroundColor)
        val layout = buildLayout(text, width = 240)
        return render(layout)
    }

    private fun styledText(
        span: KRSkipInkCustomUnderlineSpan,
        value: String,
        foregroundColor: Int? = null,
        backgroundColor: Int? = null,
    ): SpannableString =
        SpannableString(value).apply {
            setSpan(span, 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (foregroundColor != null) {
                setSpan(
                    ForegroundColorSpan(foregroundColor),
                    0,
                    length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            if (backgroundColor != null) {
                setSpan(
                    BackgroundColorSpan(backgroundColor),
                    0,
                    length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }

    private fun render(layout: StaticLayout): Bitmap {
        val bitmap = Bitmap.createBitmap(layout.width, maxOf(96, layout.height), Bitmap.Config.ARGB_8888)
        KRRichTextViewDrawer(layout).draw(Canvas(bitmap))
        return bitmap
    }

    private fun underlineBandRows(
        layout: StaticLayout,
        line: Int,
        thickness: Float,
        offset: Float = testPaint().underlinePosition,
    ): IntRange {
        val center = layout.getLineBaseline(line) + offset
        return (center - thickness / 2f).toInt()..(center + thickness / 2f).toInt()
    }

    private fun countPixels(bitmap: Bitmap, color: Int): Int =
        (0 until bitmap.height).sumOf { y ->
            (0 until bitmap.width).count { x -> bitmap.getPixel(x, y) == color }
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
