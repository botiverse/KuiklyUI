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

package com.tencent.kuikly.core.render.android.expand.component.text

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.text.Layout
import android.text.Spanned
import android.text.style.ReplacementSpan
import com.tencent.kuikly.core.render.android.expand.component.KRTextProps
import com.tencent.kuikly.core.render.android.expand.component.SelectionEdge
import com.tencent.kuikly.core.render.android.expand.component.SelectionType
import java.lang.ref.WeakReference
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private const val INVALID_OFFSET = -1
// react baseline: MarkdownContent inline `code` = bg-soft-signal/40, and
// soft-signal == brutal-yellow == #FFD440 (web index.css). 0x66 == 40% alpha.
// Was 0x66FFD84D (D84D) — a drift off the brand yellow that mismatched both
// react and the app's own tag/self-mention fills (D440, below). Single source: D440.
private const val SLOCK_INLINE_CODE_FILL_COLOR = 0x66FFD440
private const val SLOCK_INLINE_CODE_BORDER_COLOR = 0xFF000000.toInt()
private const val SLOCK_INLINE_CODE_HORIZONTAL_PADDING_RATIO = 4f / 15f
private const val SLOCK_INLINE_CODE_HORIZONTAL_MARGIN_RATIO = 2f / 15f
private const val SLOCK_INLINE_CODE_VERTICAL_PADDING_RATIO = 2f / 15f
private const val SLOCK_INLINE_CODE_MIN_HEIGHT_RATIO = 24f / 15f
private const val SLOCK_INLINE_CODE_BORDER_WIDTH_DP = 1f
private const val SLOCK_INLINE_CODE_BORDER_MIN_WIDTH = 2f

/**
 * 富文本绘制器，封装 [Layout]，用于富文本视图的测量与绘制。
 */
class KRRichTextViewDrawer(val textLayout: Layout) {
    interface Callback {
        fun invalidate()
    }

    private var callback: WeakReference<Callback>? = null

    private var selectionStart = -1
    private var selectionEnd = -1
    internal val hasSelection: Boolean get() = 0 <= selectionStart && selectionStart < selectionEnd
    private val slockInlineCodeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = SLOCK_INLINE_CODE_FILL_COLOR
    }
    private val slockInlineCodeBorderPaint = Paint().apply {
        style = Paint.Style.FILL
        color = SLOCK_INLINE_CODE_BORDER_COLOR
        isAntiAlias = false
    }
    private val slockInlineCodeRect = RectF()
    private val inlineBoxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val inlineBoxBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val inlineBoxRect = RectF()
    private val inlineBoxSelectionPath = Path()
    private val inlineBoxLineClipPath = Path()
    private val inlineBoxSelectionBounds = RectF()

    private val wordIterator by lazy(LazyThreadSafetyMode.NONE) {
        WordIterator(textLayout.text, 0, textLayout.text.length, Locale.getDefault())
    }

    private val sentenceIterator by lazy(LazyThreadSafetyMode.NONE) {
        BreakIterator.getSentenceInstance(Locale.getDefault()).apply {
            text = CharSequenceCharacterIterator(textLayout.text)
        }
    }

    internal fun setCallback(callback: Callback?) {
        this.callback = if (callback != null) WeakReference(callback) else null
    }

    private fun invalidate() {
        callback?.get()?.invalidate()
    }

    /**
     * 将文本内容绘制到 [canvas]，对接到 [Layout.draw]。
     */
    fun draw(canvas: Canvas) {
        drawInlineBoxChrome(canvas, drawFill = true, drawBorder = false)
        drawSlockInlineCodeChrome(canvas, drawFill = true, drawBorder = false)
        textLayout.draw(canvas)
        drawSlockInlineCodeChrome(canvas, drawFill = false, drawBorder = true)
        drawInlineBoxChrome(canvas, drawFill = false, drawBorder = true)
    }

    private fun drawInlineBoxChrome(canvas: Canvas, drawFill: Boolean, drawBorder: Boolean) {
        val spanned = textLayout.text as? Spanned ?: return
        val spans = spanned.getSpans(0, spanned.length, KRInlineBoxSpan::class.java)
        if (spans.isEmpty()) return

        val layoutLeft = 0f
        val layoutRight = textLayout.width.toFloat()
        val metrics = textLayout.paint.fontMetrics
        spans.forEach { span ->
            val start = spanned.getSpanStart(span)
            val end = spanned.getSpanEnd(span)
            if (start < 0 || end <= start) return@forEach
            val style = span.style
            val atomicSpan =
                spanned.getSpans(start, end, KRInlineBoxAtomicTextSpan::class.java)
                    .firstOrNull { atomicSpan ->
                        spanned.getSpanStart(atomicSpan) == start &&
                            spanned.getSpanEnd(atomicSpan) == end
                    }
            val startLine = textLayout.getLineForOffset((start + 1).coerceAtMost(end - 1))
            val endLine = textLayout.getLineForOffset((end - 1).coerceAtLeast(start))
            for (line in startLine..endLine) {
                val lineStart = textLayout.getLineStart(line)
                val lineVisibleEnd = textLayout.slockInlineCodeVisibleEnd(line)
                val segmentStart = max(start, lineStart)
                val segmentEnd = min(end, lineVisibleEnd)
                if (segmentEnd <= segmentStart) continue
                val atomicBounds =
                    atomicSpan?.let { atomicInlineBoxBounds(start, end, line, it) }
                val segmentLeft: Float
                val segmentRight: Float
                if (atomicBounds != null) {
                    // ReplacementSpan caret affinity can still resolve to adjacent
                    // text. The selection path keeps the visual anchor, while the
                    // span's measured width avoids line-end selection expansion.
                    segmentLeft = atomicBounds.left
                    segmentRight = atomicBounds.right
                } else {
                    val startX = max(
                        textLayout.getPrimaryHorizontal(segmentStart),
                        textLayout.getSecondaryHorizontal(segmentStart),
                    )
                    // At a run boundary Android's primary caret may use downstream
                    // affinity and jump across the following span. The upstream
                    // caret is the actual visual end of this inline group.
                    val endX = min(
                        textLayout.getPrimaryHorizontal(segmentEnd),
                        textLayout.getSecondaryHorizontal(segmentEnd),
                    )
                    segmentLeft = min(startX, endX)
                    segmentRight = max(startX, endX)
                }
                val left = (
                    segmentLeft + if (segmentStart == start) style.marginStart else 0f
                    )
                    .coerceAtLeast(layoutLeft)
                val right = (
                    segmentRight - if (segmentEnd == end) style.marginEnd else 0f
                    )
                    .coerceAtMost(layoutRight)
                if (right <= left) continue

                val baseline = textLayout.getLineBaseline(line).toFloat()
                val top = baseline + metrics.ascent - style.paddingTop - style.borderWidth
                val bottom = baseline + metrics.descent + style.paddingBottom + style.borderWidth
                if (bottom <= top) continue
                inlineBoxRect.set(left, top, right, bottom)
                if (drawFill && style.backgroundColor != null) {
                    inlineBoxFillPaint.color = style.backgroundColor
                    canvas.drawRoundRect(
                        inlineBoxRect,
                        style.cornerRadius,
                        style.cornerRadius,
                        inlineBoxFillPaint
                    )
                }
                if (drawBorder && style.borderColor != null && style.borderWidth > 0f) {
                    inlineBoxBorderPaint.color = style.borderColor
                    inlineBoxBorderPaint.strokeWidth = style.borderWidth
                    val inset = style.borderWidth / 2f
                    inlineBoxRect.inset(inset, inset)
                    canvas.drawRoundRect(
                        inlineBoxRect,
                        max(0f, style.cornerRadius - inset),
                        max(0f, style.cornerRadius - inset),
                        inlineBoxBorderPaint
                    )
                }
            }
        }
    }

    private fun atomicInlineBoxBounds(
        start: Int,
        end: Int,
        line: Int,
        atomicSpan: KRInlineBoxAtomicTextSpan,
    ): RectF? {
        val measuredWidth = atomicSpan.measuredWidth.toFloat()
        if (measuredWidth <= 0f) return null
        inlineBoxSelectionPath.reset()
        textLayout.getSelectionPath(start, end, inlineBoxSelectionPath)
        inlineBoxLineClipPath.reset()
        inlineBoxLineClipPath.addRect(
            0f,
            textLayout.getLineTop(line).toFloat(),
            textLayout.width.toFloat(),
            textLayout.getLineBottom(line).toFloat(),
            Path.Direction.CW,
        )
        if (!inlineBoxSelectionPath.op(inlineBoxLineClipPath, Path.Op.INTERSECT)) {
            return null
        }
        inlineBoxSelectionPath.computeBounds(inlineBoxSelectionBounds, true)
        if (inlineBoxSelectionBounds.isEmpty) return null
        if (textLayout.getParagraphDirection(line) >= 0) {
            inlineBoxSelectionBounds.right =
                min(textLayout.width.toFloat(), inlineBoxSelectionBounds.left + measuredWidth)
        } else {
            inlineBoxSelectionBounds.left =
                max(0f, inlineBoxSelectionBounds.right - measuredWidth)
        }
        return inlineBoxSelectionBounds
    }

    private fun drawSlockInlineCodeChrome(canvas: Canvas, drawFill: Boolean, drawBorder: Boolean) {
        val spanned = textLayout.text as? Spanned ?: return
        val spans = spanned.getSpans(0, spanned.length, KRSlockInlineCodeSpan::class.java)
        if (spans.isEmpty()) return

        val paint = textLayout.paint
        val horizontalPadding = paint.textSize * SLOCK_INLINE_CODE_HORIZONTAL_PADDING_RATIO
        val horizontalMargin = paint.textSize * SLOCK_INLINE_CODE_HORIZONTAL_MARGIN_RATIO
        val verticalPadding = paint.textSize * SLOCK_INLINE_CODE_VERTICAL_PADDING_RATIO
        val minHeight = paint.textSize * SLOCK_INLINE_CODE_MIN_HEIGHT_RATIO
        val fontMetrics = paint.fontMetrics
        val layoutLeft = 0f

        spans.forEach { span ->
            val start = spanned.getSpanStart(span)
            val end = spanned.getSpanEnd(span)
            if (start < 0 || end <= start) return@forEach

            val startLine = textLayout.getLineForOffset(start)
            val endLine = textLayout.getLineForOffset((end - 1).coerceAtLeast(start))
            for (line in startLine..endLine) {
                val lineStart = textLayout.getLineStart(line)
                val lineVisibleEnd = textLayout.slockInlineCodeVisibleEnd(line)
                val segmentStart = max(start, lineStart)
                val segmentEnd = min(end, lineVisibleEnd)
                if (segmentEnd <= segmentStart) continue

                val startX =
                    if (segmentStart <= lineStart) {
                        layoutLeft
                    } else {
                        textLayout.getPrimaryHorizontal(segmentStart)
                    }
                val endX =
                    if (segmentEnd >= lineVisibleEnd) {
                        textLayout.getLineRight(line)
                    } else {
                        textLayout.getPrimaryHorizontal(segmentEnd)
                    }
                val segmentLeft = min(startX, endX)
                val segmentRight = max(startX, endX)
                val left = if (segmentStart == start) {
                    segmentLeft + horizontalMargin
                } else {
                    segmentLeft - horizontalPadding
                }
                val right = if (segmentEnd == end) {
                    // Mirror the leading edge. The final atom reserves edgePadding
                    // (padding + margin) after its glyphs, so pull the border IN by
                    // horizontalMargin to land it exactly horizontalPadding past the
                    // last glyph — same inner padding as the start side — instead of
                    // pushing OUT by horizontalPadding (which put the border ~10/15
                    // past the glyphs, the "right side too big" regression, #394/#54).
                    segmentRight - horizontalMargin
                } else {
                    segmentRight + horizontalPadding
                }
                if (right <= left) continue

                val baseline = textLayout.getLineBaseline(line).toFloat()
                val textTop = baseline + fontMetrics.ascent - verticalPadding
                val textBottom = baseline + fontMetrics.descent + verticalPadding
                val height = max(textBottom - textTop, minHeight)
                val centerY = (textTop + textBottom) / 2f
                val top = centerY - height / 2f
                val bottom = centerY + height / 2f
                if (bottom <= top) continue

                slockInlineCodeRect.set(left, top, right, bottom)
                if (drawFill) {
                    canvas.drawRect(slockInlineCodeRect, slockInlineCodeFillPaint)
                }
                if (drawBorder) {
                    canvas.drawSlockInlineCodeBorder(left, top, right, bottom)
                }
            }
        }
    }

    // Paint.density is a bitmap-scaling field that defaults to 1 (it is NOT the
    // display density), so `paint.density * 1dp` collapsed to 1 and the
    // MIN_WIDTH clamp left every chip border at 2 physical px — thinner than
    // react's 1 css px (= 3 px @3x). Use the real display density instead
    // (task #407 follow-up, artin's border-width report).
    private val slockChipBorderWidthPx: Float =
        max(
            SLOCK_INLINE_CODE_BORDER_MIN_WIDTH,
            android.content.res.Resources.getSystem().displayMetrics.density * SLOCK_INLINE_CODE_BORDER_WIDTH_DP
        )

    private fun Canvas.drawSlockInlineCodeBorder(left: Float, top: Float, right: Float, bottom: Float) {
        val borderWidth = slockChipBorderWidthPx
        val borderLeft = floor(left)
        val borderTop = floor(top)
        val borderRight = ceil(right)
        val borderBottom = ceil(bottom)
        drawRect(borderLeft, borderTop, borderRight, borderTop + borderWidth, slockInlineCodeBorderPaint)
        drawRect(borderLeft, borderBottom - borderWidth, borderRight, borderBottom, slockInlineCodeBorderPaint)
        drawRect(borderLeft, borderTop, borderLeft + borderWidth, borderBottom, slockInlineCodeBorderPaint)
        drawRect(borderRight - borderWidth, borderTop, borderRight, borderBottom, slockInlineCodeBorderPaint)
    }

    private fun Layout.slockInlineCodeVisibleEnd(line: Int): Int {
        val lineStart = getLineStart(line)
        val ellipsisCount = getEllipsisCount(line)
        if (ellipsisCount > 0) {
            return (lineStart + getEllipsisStart(line)).coerceAtLeast(lineStart)
        }
        return getLineVisibleEnd(line)
    }

    internal fun setSelectionByCoordinate(
        x: Float,
        y: Float,
        type: SelectionType,
        force: Boolean
    ): Boolean {
        val layout = textLayout
        val size = layout.text.length
        if (size > 0) {
            val position = layout.getOffsetForPosition(x, y)
            if (0 <= position && position <= size) {
                val (start, end) = when (type) {
                    SelectionType.CHARACTER -> layout.expandSelectionToCharacter(position, x, y)

                    SelectionType.WORD -> layout.expandSelectionToWord(position, wordIterator, x, y)

                    SelectionType.SENTENCE -> layout.expandSelectionToSentence(position, sentenceIterator)

                    SelectionType.PARAGRAPH -> layout.expandSelectionToParagraph(position)

                    SelectionType.SPAN -> layout.expandSelectionToSpan(position, x, y)
                }
                setTextSelection(start, end)
                return true
            }
        }
        setTextSelection(INVALID_OFFSET, INVALID_OFFSET)
        return false
    }

    internal fun setSelectionByCoordinates(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        force: Boolean
    ): Boolean {
        val layout = textLayout
        var pos1: Int
        var pos2: Int
        val size = layout.text.length
        if (size == 0) {
            pos1 = INVALID_OFFSET
            pos2 = INVALID_OFFSET
        } else {
            pos1 = layout.getOffsetForPosition(x1, y1)
            pos2 = layout.getOffsetForPosition(x2, y2)
            if (pos1 == pos2) {
                if (force) {
                    if (layout.shouldExpandSelectionBackward(x1, y1, x2, y2, pos2)) {
                        pos1 = layout.prevOffset(pos2)
                    } else {
                        pos2 = layout.nextOffset(pos1)
                    }
                } else {
                    pos1 = INVALID_OFFSET
                    pos2 = INVALID_OFFSET
                }
            } else if (pos1 > pos2) {
                val temp = pos1
                pos1 = pos2
                pos2 = temp
            }
        }
        setTextSelection(pos1, pos2)
        return hasSelection
    }

    internal fun setSelectAll(): Boolean {
        val size = textLayout.text.length
        if (size > 0) {
            setTextSelection(0, size)
            return true
        }
        return false
    }

    internal fun clearSelection() {
        setTextSelection(INVALID_OFFSET, INVALID_OFFSET)
    }

    private fun setTextSelection(start: Int, end: Int) {
        if (selectionStart != start || selectionEnd != end) {
            selectionStart = start
            selectionEnd = end
            invalidate()
        }
    }

    internal fun getStartSelectionEdge() = textLayout.getPositionForOffset(selectionStart)

    internal fun getEndSelectionEdge() = textLayout.getPositionForOffset(selectionEnd, true)

    internal fun getSelectionRect(dest: RectF): Boolean {
        return if (hasSelection) {
            val layout = textLayout
            val startline: Int = layout.getLineForOffset(selectionStart)
            val endline: Int = layout.getLineForOffset(selectionEnd)
            dest.top = layout.getLineTop(startline).toFloat()
            dest.bottom = layout.getLineBottom(endline).toFloat()
            if (startline == endline) {
                dest.left = layout.getPrimaryHorizontal(selectionStart)
                dest.right = layout.getPrimaryHorizontal(selectionEnd)
            } else {
                dest.left = 0f
                dest.right = layout.width.toFloat()
            }
            true
        } else {
            false
        }
    }

    internal fun getSelectionPath(dest: Path): Boolean {
        return if (hasSelection) {
            textLayout.getSelectionPath(selectionStart, selectionEnd, dest)
            true
        } else {
            false
        }
    }

    internal fun getSelectionText(): String? {
        return if (hasSelection) {
            textLayout.text.inlineBoxSemanticSubstring(selectionStart, selectionEnd)
        } else {
            null
        }
    }

    internal fun getPreSelectionText(): String? {
        return if (hasSelection && selectionStart > 0) {
            textLayout.text.inlineBoxSemanticSubstring(0, selectionStart)
        } else {
            null
        }
    }

    internal fun getPostSelectionText(): String? {
        val length = textLayout.text.length
        return if (hasSelection && selectionEnd < length) {
            textLayout.text.inlineBoxSemanticSubstring(selectionEnd, length)
        } else {
            null
        }
    }

    private companion object {

        private fun Layout.expandSelectionToCharacter(
            position: Int,
            x: Float,
            y: Float
        ): Pair<Int, Int> {
            val (cX, cY) = getPositionForOffset(position)
            return if (shouldExpandSelectionBackward(cX, cY, x, y, position)) {
                Pair(prevOffset(position), position)
            } else {
                Pair(position, nextOffset(position))
            }
        }

        private fun Layout.expandSelectionToWord(
            position: Int,
            wordIterator: WordIterator,
            x: Float,
            y: Float
        ): Pair<Int, Int> {
            var start = wordIterator.prevBoundary(position)
            start = if (wordIterator.isOnPunctuation(start)) {
                // On punctuation boundary or within group of punctuation, find punctuation start.
                wordIterator.getPunctuationBeginning(position)
            } else {
                // Not on a punctuation boundary, find the word start.
                wordIterator.getPrevWordBeginningOnTwoWordsBoundary(position)
            }

            if (start != BreakIterator.DONE) {
                var end = wordIterator.nextBoundary(position)
                end = if (wordIterator.isAfterPunctuation(end)) {
                    // On punctuation boundary or within group of punctuation, find punctuation end.
                    wordIterator.getPunctuationEnd(position)
                } else { // Not on a punctuation boundary, find the word end.
                    wordIterator.getNextWordEndOnTwoWordBoundary(position)
                }

                if (end != BreakIterator.DONE && start < end) {
                    return Pair(getUnitStart(start), getUnitEnd(end))
                }
            }

            return expandSelectionToCharacter(position, x, y)
        }

        private fun Layout.expandSelectionToSentence(
            position: Int,
            sentenceIterator: BreakIterator
        ): Pair<Int, Int> {
            val start = sentenceIterator.preceding(position).let { prev ->
                if (prev == BreakIterator.DONE) 0 else prev
            }

            val end = sentenceIterator.following(position).let { next ->
                if (next == BreakIterator.DONE) text.length else next
            }
            return Pair(getUnitStart(start), getUnitEnd(end))
        }

        private fun Layout.expandSelectionToParagraph(position: Int): Pair<Int, Int> {
            val text = text
            var end = position
            while (end < text.length) {
                val c = Character.codePointAt(text, end)
                end += Character.charCount(c)
                if (c == '\n'.toInt()) {
                    break
                }
            }
            var start = if (position < text.length) {
                position
            } else {
                position - Character.charCount(Character.codePointBefore(text, position))
            }
            while (start > 0) {
                val c = Character.codePointBefore(text, start)
                if (c == '\n'.toInt()) {
                    break
                }
                start -= Character.charCount(c)
            }
            return Pair(getUnitStart(start), getUnitEnd(end))
        }

        /**
         * 根据触摸位置定位到富文本 Span，选中整个 Span 的文字范围。
         *
         * 富文本中每个 Span 都会被附加 [FontWeightSpan]（携带 spanIndex），
         * 因此通过 [Spanned.getSpans] 查找该位置上的 FontWeightSpan，
         * 再用 [Spanned.getSpanStart] / [Spanned.getSpanEnd] 获取 Span 的起止偏移。
         *
         * 如果当前位置不在任何 Span 范围内（如普通文本模式），回退到字符级选择。
         */
        private fun Layout.expandSelectionToSpan(
            position: Int,
            x: Float,
            y: Float
        ): Pair<Int, Int> {
            val text = text
            if (text is Spanned) {
                val spans = text.getSpans(position, position, FontWeightSpan::class.java)
                // 优先选取真正覆盖 position 的 Span（start <= position < end），
                // 避免在两个 Span 交界处（position 恰好等于前一个 Span 的 end）误取相邻 Span。
                for (span in spans) {
                    val start = text.getSpanStart(span)
                    val end = text.getSpanEnd(span)
                    if (start <= position && position < end) {
                        return Pair(start, end)
                    }
                }
                // 边界兜底：position 恰好落在末尾等特殊位置时，选取 end 最接近 position 的有效 Span。
                var bestSpan: FontWeightSpan? = null
                var bestDist = Int.MAX_VALUE
                for (span in spans) {
                    val start = text.getSpanStart(span)
                    val end = text.getSpanEnd(span)
                    if (start < end) {
                        val dist = kotlin.math.abs(end - position)
                        if (dist < bestDist) {
                            bestDist = dist
                            bestSpan = span
                        }
                    }
                }
                if (bestSpan != null) {
                    return Pair(text.getSpanStart(bestSpan), text.getSpanEnd(bestSpan))
                }
            }
            return expandSelectionToCharacter(position, x, y)
        }

        private fun Layout.getPositionForOffset(
            offset: Int,
            isEnd: Boolean = false
        ): SelectionEdge {
            var line: Int = getLineForOffset(offset)
            val x: Float
            if (isEnd && line > 0 && offset == getLineStart(line) && text[offset - 1] != '\n') {
                // end-offset is at the beginning of line, use end of previous line
                line -= 1
                x = if (getParagraphDirection(line) == Layout.DIR_LEFT_TO_RIGHT) {
                    width + 0.1f // add 0.1f to stay out of the previous character's bounds
                } else {
                    -0.1f
                }
            } else {
                x = getPrimaryHorizontal(offset)
            }
            val bottom: Float = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                getLineBottom(line, false)
            } else {
                getLineBottom(line)
            }).toFloat()
            val top = getLineTop(line).toFloat()
            return SelectionEdge(x, top, bottom)
        }

        private fun Layout.shouldExpandSelectionBackward(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            position: Int
        ): Boolean {
            val line = getLineForOffset(position)
            return (/*end of previous line*/line > 0 && y2 < getLineTop(line)) ||
                    (/*end of text*/position == text.length) ||
                    (/*left side and not line start*/x2 < x1 && position != getLineStart(line))
        }

        private fun Layout.getUnitStart(offset: Int): Int {
            if (offset <= 0) {
                return 0
            }
            val text = text
            // 检查是否在ReplacementSpan范围内
            if (text is Spanned) {
                val spans = text.getSpans(offset, offset, ReplacementSpan::class.java)
                if (spans.isNotEmpty()) {
                    var unitStart = offset
                    for (span in spans) {
                        val spanStart = text.getSpanStart(span)
                        val spanEnd = text.getSpanEnd(span)
                        if (spanStart < unitStart && offset < spanEnd) {
                            unitStart = spanStart
                        }
                    }
                    return unitStart
                }
            }
            // 检查是否是低位代理字符（emoji等）
            if (Character.isLowSurrogate(text[offset]) &&
                Character.isHighSurrogate(text[offset - 1])
            ) {
                return offset - 1
            }
            return offset
        }

        private fun Layout.getUnitEnd(offset: Int): Int {
            if (offset >= text.length) {
                return text.length
            }
            val text = text
            // 检查是否在ReplacementSpan范围内
            if (text is Spanned) {
                val spans = text.getSpans(offset, offset, ReplacementSpan::class.java)
                if (spans.isNotEmpty()) {
                    var unitEnd = offset
                    for (span in spans) {
                        val spanStart = text.getSpanStart(span)
                        val spanEnd = text.getSpanEnd(span)
                        if (spanStart < offset && unitEnd < spanEnd) {
                            unitEnd = spanEnd
                        }
                    }
                    return unitEnd
                }
            }
            // 检查是否是高位代理字符（emoji等）
            if (offset > 0 && Character.isLowSurrogate(text[offset]) &&
                Character.isHighSurrogate(text[offset - 1])
            ) {
                return offset + 1
            }
            return offset
        }

        private inline fun Layout.nextOffset(offset: Int) = getUnitEnd(offset + 1)

        private inline fun Layout.prevOffset(offset: Int) = getUnitStart(offset - 1)

        private fun Layout.getOffsetForPosition(x: Float, y: Float): Int {
            val line: Int = getLineForVertical(y.toInt())
            if ((x > width && getParagraphDirection(line) == Layout.DIR_LEFT_TO_RIGHT) ||
                (x < 0 && getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT)) {
                return getLineEnd(line)
            }
            return getOffsetForHorizontal(line, x)
        }

    }

}

private fun String.withoutInlineBoxLayoutCharacters(): String =
    replace("\uFFFC", "").replace(INLINE_BOX_LAYOUT_JOINER.toString(), "")

private fun CharSequence.inlineBoxSemanticSubstring(start: Int, end: Int): String {
    if (start >= end) return ""
    val spanned = this as? Spanned
        ?: return substring(start, end).withoutInlineBoxLayoutCharacters()
    val semanticSpans = spanned.getSpans(start, end, KRInlineBoxSemanticSpan::class.java)
    if (semanticSpans.isEmpty()) return substring(start, end).withoutInlineBoxLayoutCharacters()

    val result = StringBuilder()
    var cursor = start
    semanticSpans.sortedBy(spanned::getSpanStart).forEach { span ->
        val spanStart = spanned.getSpanStart(span)
        val spanEnd = spanned.getSpanEnd(span)
        if (spanStart > cursor) {
            result.append(substring(cursor, min(spanStart, end)).withoutInlineBoxLayoutCharacters())
        }
        val overlapStart = max(cursor, spanStart)
        val overlapEnd = min(end, spanEnd)
        if (overlapEnd > overlapStart) {
            if (overlapStart == spanStart && overlapEnd == spanEnd && span.text.isNotEmpty()) {
                result.append(span.text)
            } else {
                result.append(substring(overlapStart, overlapEnd).withoutInlineBoxLayoutCharacters())
            }
            cursor = overlapEnd
        }
    }
    if (cursor < end) result.append(substring(cursor, end).withoutInlineBoxLayoutCharacters())
    return result.toString()
}
