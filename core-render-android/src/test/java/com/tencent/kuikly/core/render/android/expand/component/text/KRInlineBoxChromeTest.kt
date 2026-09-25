/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI.
 */

package com.tencent.kuikly.core.render.android.expand.component.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ReplacementSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KRInlineBoxChromeTest {

    @Test
    fun wrappedGroupChromeDoesNotPaintAdjacentPlainText() {
        val prefix = "Long path without ellipsis: "
        val code = "reply/channel-markdown/message-inline-visual/owner-key-consumes-thread-route"
        val suffix = " suffix"
        val leadingEdge = '\uFFFC'
        val joiner = INLINE_BOX_LAYOUT_JOINER
        val value = prefix + leadingEdge + joiner + code + joiner + leadingEdge + suffix
        val groupStart = prefix.length
        val groupEnd = value.length - suffix.length
        val text = SpannableString(value).apply {
            setSpan(FixedAdvanceSpan(8), groupStart, groupStart + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(FixedAdvanceSpan(8), groupEnd - 1, groupEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(
                KRInlineBoxSpan(
                    KRInlineBoxSpanStyle(
                        backgroundColor = Color.RED,
                        borderColor = null,
                        borderWidth = 0f,
                        paddingStart = 8f,
                        paddingEnd = 8f,
                        paddingTop = 0f,
                        paddingBottom = 0f,
                        marginStart = 0f,
                        marginEnd = 0f,
                        cornerRadius = 0f,
                    )
                ),
                groupStart,
                groupEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        val layout = buildLayout(text, width = 320)
        val bitmap = render(layout)

        assertTrue("fixture must start the group after plain text", groupStart > layout.getLineStart(0))
        assertTrue("fixture must wrap the inline group", layout.getLineForOffset(groupEnd - 1) > 0)
        for (line in layout.getLineForOffset(groupStart)..layout.getLineForOffset(groupEnd - 1)) {
            val segmentStart = maxOf(groupStart, layout.getLineStart(line))
            val segmentEnd = minOf(groupEnd, layout.getLineVisibleEnd(line))
            if (segmentEnd <= segmentStart) continue
            val bounds = selectionBounds(layout, segmentStart, segmentEnd, line)
            var redPixels = 0
            for (y in layout.getLineTop(line) until layout.getLineBottom(line)) {
                for (x in 0 until bitmap.width) {
                    if (bitmap.getPixel(x, y) != Color.RED) continue
                    redPixels++
                    assertTrue(
                        "inline-box fill escaped line $line selection at $x,$y; bounds=$bounds",
                        x >= bounds.left.toInt() && x < kotlin.math.ceil(bounds.right).toInt(),
                    )
                }
            }
            assertTrue("line $line must contain inline-box fill", redPixels > 0)
        }

        val firstLine = layout.getLineForOffset(groupStart)
        val prefixRight = layout.getPrimaryHorizontal(groupStart).toInt()
        val redOverPrefix =
            (layout.getLineTop(firstLine) until layout.getLineBottom(firstLine)).sumOf { y ->
                (0 until prefixRight).count { x -> bitmap.getPixel(x, y) == Color.RED }
            }
        assertEquals("plain prefix must never receive inline-box fill", 0, redOverPrefix)
    }

    @Test
    fun mixedBidirectionalGroupChromeClipsDisjointSelection() {
        val prefix = "prefix "
        val code = "abc אב"
        val suffix = "ג xyz suffix"
        val value = prefix + code + suffix
        val groupStart = prefix.length
        val groupEnd = groupStart + code.length
        val text = SpannableString(value).apply {
            setSpan(
                KRInlineBoxSpan(
                    KRInlineBoxSpanStyle(
                        backgroundColor = Color.RED,
                        borderColor = null,
                        borderWidth = 0f,
                        paddingStart = 0f,
                        paddingEnd = 0f,
                        paddingTop = 0f,
                        paddingBottom = 0f,
                        marginStart = 0f,
                        marginEnd = 0f,
                        cornerRadius = 0f,
                    )
                ),
                groupStart,
                groupEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        val layout = buildLayout(text, width = 640)
        val line = layout.getLineForOffset(groupStart)
        assertEquals(line, layout.getLineForOffset(groupEnd - 1))
        val selection = selectionPath(layout, groupStart, groupEnd, line)
        val selectionRegion = selectionRegion(layout, selection, line)
        val sampleY = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2
        assertTrue(
            "fixture must produce a disjoint bidi selection",
            horizontalRunCount(selectionRegion, sampleY, layout.width) > 1,
        )

        val bitmap = render(layout)
        var redPixels = 0
        for (y in layout.getLineTop(line) until layout.getLineBottom(line)) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) != Color.RED) continue
                redPixels++
                assertTrue(
                    "inline-box fill escaped bidi selection at $x,$y",
                    selectionRegion.contains(x, y),
                )
            }
        }
        assertTrue("bidi selection must contain inline-box fill", redPixels > 0)
    }

    @Test
    fun wrappedGroupChromeStopsAtShortFirstFragment() {
        val prefix = "Command: "
        val code = "./\u200Bgradlew\u200B:shared:testDebugUnitTest"
        val value = prefix + code
        val groupStart = prefix.length
        val groupEnd = value.length
        val text = SpannableString(value).apply {
            setSpan(
                KRInlineBoxSpan(
                    KRInlineBoxSpanStyle(
                        backgroundColor = Color.RED,
                        borderColor = null,
                        borderWidth = 0f,
                        paddingStart = 0f,
                        paddingEnd = 0f,
                        paddingTop = 0f,
                        paddingBottom = 0f,
                        marginStart = 0f,
                        marginEnd = 0f,
                        cornerRadius = 0f,
                    )
                ),
                groupStart,
                groupEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        val paint = testPaint()
        val width = kotlin.math.ceil(paint.measureText(prefix + "./") + 2f).toInt()
        val layout = buildLayout(text, width = width, paint = paint)
        val firstLine = layout.getLineForOffset(groupStart)
        assertTrue("fixture must wrap after the short ./ fragment", layout.lineCount > firstLine + 1)
        assertTrue(
            "first line must end before gradlew",
            layout.getLineEnd(firstLine) <= groupStart + "./\u200B".length,
        )

        val bitmap = render(layout)
        val contentRight = kotlin.math.ceil(layout.getLineRight(firstLine).toDouble()).toInt()
        assertTrue("fixture must leave an unused first-line tail", contentRight < bitmap.width)
        val redTailPixels =
            (layout.getLineTop(firstLine) until layout.getLineBottom(firstLine)).sumOf { y ->
                (contentRight until bitmap.width).count { x -> bitmap.getPixel(x, y) == Color.RED }
            }
        assertEquals("chrome after ./ must not fill the unused line tail", 0, redTailPixels)
    }

    private fun selectionBounds(layout: Layout, start: Int, end: Int, line: Int): RectF {
        val selection = selectionPath(layout, start, end, line)
        return RectF().also { selection.computeBounds(it, true) }
    }

    private fun selectionPath(layout: Layout, start: Int, end: Int, line: Int): Path {
        val selection = Path().also { layout.getSelectionPath(start, end, it) }
        val lineLeft = minOf(layout.getLineLeft(line), layout.getLineRight(line))
        val lineRight = maxOf(layout.getLineLeft(line), layout.getLineRight(line))
        val lineClip = Path().apply {
            addRect(
                lineLeft,
                layout.getLineTop(line).toFloat(),
                lineRight,
                layout.getLineBottom(line).toFloat(),
                Path.Direction.CW,
            )
        }
        assertTrue(selection.op(lineClip, Path.Op.INTERSECT))
        return selection
    }

    private fun selectionRegion(layout: Layout, selection: Path, line: Int): Region =
        Region().apply {
            setPath(
                selection,
                Region(0, layout.getLineTop(line), layout.width, layout.getLineBottom(line)),
            )
        }

    private fun horizontalRunCount(region: Region, y: Int, width: Int): Int {
        var runs = 0
        var inside = false
        for (x in 0 until width) {
            val nextInside = region.contains(x, y)
            if (nextInside && !inside) runs++
            inside = nextInside
        }
        return runs
    }

    private fun buildLayout(
        text: CharSequence,
        width: Int,
        paint: TextPaint = testPaint(),
    ): StaticLayout =
        StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()

    private fun render(layout: StaticLayout): Bitmap =
        Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888).also { bitmap ->
            KRRichTextViewDrawer(layout).draw(Canvas(bitmap))
        }

    private fun testPaint(): TextPaint =
        TextPaint().apply {
            color = Color.BLACK
            textSize = 40f
            isAntiAlias = false
        }

    private class FixedAdvanceSpan(private val width: Int) : ReplacementSpan() {
        override fun getSize(
            paint: android.graphics.Paint,
            text: CharSequence?,
            start: Int,
            end: Int,
            fm: android.graphics.Paint.FontMetricsInt?,
        ): Int = width

        override fun draw(
            canvas: Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: android.graphics.Paint,
        ) = Unit
    }
}
