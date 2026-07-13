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
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.text.style.UpdateAppearance
import android.util.SizeF
import com.tencent.kuikly.core.render.android.IKuiklyRenderContext
import com.tencent.kuikly.core.render.android.const.KRCssConst
import com.tencent.kuikly.core.render.android.css.decoration.BoxShadow
import com.tencent.kuikly.core.render.android.css.drawable.KRCSSBackgroundDrawable
import com.tencent.kuikly.core.render.android.css.ktx.buildSpannedString
import com.tencent.kuikly.core.render.android.css.ktx.inSpans
import com.tencent.kuikly.core.render.android.css.ktx.spToPxI
import com.tencent.kuikly.core.render.android.css.ktx.toColor
import com.tencent.kuikly.core.render.android.css.ktx.toPxF
import com.tencent.kuikly.core.render.android.css.ktx.toPxI
import com.tencent.kuikly.core.render.android.expand.component.KRTextProps
import com.tencent.kuikly.core.views.TextConst
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.max

private const val SLOCK_INLINE_CODE_EDGE_PADDING_RATIO = 4f / 15f
private const val SLOCK_INLINE_CODE_EDGE_MARGIN_RATIO = 2f / 15f
private const val SLOCK_INLINE_CODE_TRAILING_MARGIN_RATIO = 1f / 15f

// Each inline-code atom is an atomic ReplacementSpan, so a long run with no
// whitespace/separator (e.g. `realtimeMessageUpdatedAppliesReactionPayload`)
// can't line-break and overflows/clips off the right edge (#58). A standard
// layout engine char-wraps a long word; to get that, a run longer than this
// threshold is emitted as per-character atoms so the layout can break at any
// character. Those atoms are marked seamless (no per-atom stroke padding) so the
// run looks identical on one line and only wraps when it must. Short runs stay a
// single atom, unchanged.
private const val SLOCK_INLINE_CODE_LONG_RUN_THRESHOLD = 16
internal const val INLINE_BOX_LAYOUT_JOINER = '\u2060'
private const val INLINE_BOX_LAYOUT_EDGE = '\uFFFC'

/**
 * 富文本构造器
 */
class KRRichTextBuilder(private val kuiklyContext: IKuiklyRenderContext?) {

    /**
     * 构建富文本
     *
     * @param textProps 文本属性，用作 TextSpan 解析的默认值
     * @param spanTextRanges 用于记录每个 Span 的文本范围
     * @param layoutSizeGetter 用于获取文本组件布局 Size
     */
    fun build(
        textProps: KRTextProps,
        spanTextRanges: MutableList<SpanTextRange>,
        layoutSizeGetter: () -> SizeF
    ): SpannableStringBuilder? {
        val spanValues = textProps.values
        if (spanValues == null || spanValues.length() == 0) {
            return null
        }
        val spannedBuilder = SpannableStringBuilder()
        for (index in 0 until spanValues.length()) {
            val isStart =
                spannedBuilder.isEmpty() || spannedBuilder[spannedBuilder.lastIndex] == '\n'
            val spanValue = spanValues.optJSONObject(index) ?: JSONObject()
            val spanProps = parseSpanProps(spanValue, textProps, isStart)
            if (spanProps is InlineBoxGroupSpanProps) {
                spannedBuilder.appendInlineBoxGroup(
                    groupProps = spanProps,
                    index = index,
                    defaultTextProps = textProps,
                    spanTextRanges = spanTextRanges,
                    layoutSizeGetter = layoutSizeGetter,
                )
            } else {
                spannedBuilder.appendSpan(
                    spanProps = spanProps,
                    index = index,
                    childIndex = null,
                    spanTextRanges = spanTextRanges,
                    layoutSizeGetter = layoutSizeGetter,
                )
            }
        }
        if (textProps.richTextHeadIndent != 0) {
            spannedBuilder.setSpan(
                LeadingMarginSpan.Standard(textProps.richTextHeadIndent, 0),
                0,
                spannedBuilder.length,
                Spannable.SPAN_INCLUSIVE_EXCLUSIVE
            )
        }
        return spannedBuilder
    }

    /**
     * 解析 Span 参数
     */
    private fun parseSpanProps(
        spanValue: JSONObject,
        defaultTextProps: KRTextProps,
        isStart: Boolean
    ): SpanProps {
        if (spanValue.has(InlineBoxGroupSpanProps.PROP_KEY_CHILDREN)) {
            return InlineBoxGroupSpanProps(spanValue, defaultTextProps, kuiklyContext)
        }
        if (isPlaceHolderSpan(spanValue)) {
            return PlaceholderSpanProps(spanValue, kuiklyContext)
        }
        return TextSpanProps(spanValue, defaultTextProps, isStart, kuiklyContext)
    }

    /**
     * 判断是否为 PlaceholderSpan
     */
    private fun isPlaceHolderSpan(spanValue: JSONObject): Boolean {
        return spanValue.opt(PlaceholderSpanProps.PROP_KEY_PLACEHOLDER_WIDTH) != null && spanValue.opt(
            PlaceholderSpanProps.PROP_KEY_PLACEHOLDER_HEIGHT
        ) != null
    }

    /**
     * 根据 span 类型创建对应的 span
     */
    private fun createSpans(
        spanProps: SpanProps,
        index: Int,
        layoutSizeGetter: () -> SizeF
    ): List<Any> {
        val spans = mutableListOf<Any>()
        when (spanProps) {
            is TextSpanProps -> {
                spans.addAll(createTextSpan(spanProps, index, layoutSizeGetter))
            }
            is PlaceholderSpanProps -> {
                spans.add(KRPlaceholderSpan(spanProps))
            }
        }
        return spans
    }

    /**
     * 创建富文本 span
     *
     * @param spanProps span 属性
     * @param index span 的位置
     * @param layoutSizeGetter 获取 TextLayout Size 的方法
     */
    private fun createTextSpan(
        spanProps: TextSpanProps,
        index: Int,
        layoutSizeGetter: () -> SizeF
    ): List<Any> {
        val textSpans = mutableListOf<Any>()

        // 字体相关
        if (spanProps.fontSize > 0) {
            textSpans.add(AbsoluteSizeSpan(if (spanProps.useDpFontSizeDim) kuiklyContext.toPxI(spanProps.fontSize) else {
                kuiklyContext.spToPxI(spanProps.fontSize)
            }))
        }
        textSpans.add(StyleSpan(spanProps.fontStyle))
        if (spanProps.fontVariant.isNotEmpty()) {
            textSpans.add(FontVariantSpan(spanProps.fontVariant))
        }
        if (spanProps.fontFamily.isNotEmpty()) {
            textSpans.add(FontFamilySpan(spanProps.fontFamily, kuiklyContext?.getTypeFaceLoader()))
        }
        val fontWeightSpan = FontWeightSpan(spanProps.fontWeight, index)
        textSpans.add(fontWeightSpan)

        // 修饰相关
        textSpans.add(ForegroundColorSpan(spanProps.color))
        if (spanProps.backgroundColor != Color.TRANSPARENT &&
            !spanProps.slockInlineCode &&
            spanProps.inlineBoxStyle == null
        ) {
            textSpans.add(BackgroundColorSpan(spanProps.backgroundColor))
        }
        if (spanProps.textDecoration.isNotEmpty()) {
            if (spanProps.textDecoration == KRTextProps.TEXT_DECORATION_LINE_THROUGH) {
                textSpans.add(StrikethroughSpan())
            } else if (
                spanProps.textDecorationColor != null ||
                spanProps.textDecorationThickness != null ||
                spanProps.textDecorationOffset != null
            ) {
                textSpans.add(
                    KRCustomUnderlineSpan(
                        color = spanProps.textDecorationColor,
                        thickness = spanProps.textDecorationThickness,
                        offset = spanProps.textDecorationOffset
                    )
                )
            } else {
                textSpans.add(UnderlineSpan())
            }
        }
        if (spanProps.backgroundImage.isNotEmpty()) {
            textSpans.add(LinearGradientForegroundSpan(spanProps.backgroundImage, layoutSizeGetter))
        }
        if (spanProps.slockInlineCode) {
            textSpans.add(KRSlockInlineCodeSpan())
        }
        if (spanProps.slockInlineCodeTrailingMargin) {
            textSpans.add(KRSlockInlineCodeTrailingMarginSpan())
        }
        spanProps.inlineBoxStyle?.let { style ->
            textSpans.add(KRInlineBoxSpan(style))
        }

        spanProps.textShadow?.let {
            if (!it.isEmpty()) {
                textSpans.add(
                    TextShadowSpan(
                        it.shadowOffsetX,
                        it.shadowOffsetY,
                        it.shadowRadius,
                        it.shadowColor
                    )
                )
            }
        }

        // 段落相关
        if (spanProps.letterSpacing != 0f) {
            textSpans.add(LetterSpacingSpan(spanProps.letterSpacing))
        }
        if (spanProps.lineHeight != KRTextProps.UNSET_LINE_HEIGHT) {
            textSpans.add(HRLineHeightSpan(spanProps.lineHeight.toInt()))
        }
        return textSpans
    }

    private fun SpannableStringBuilder.appendSpan(
        spanProps: SpanProps,
        index: Int,
        childIndex: Int?,
        spanTextRanges: MutableList<SpanTextRange>,
        layoutSizeGetter: () -> SizeF,
    ) {
        val spans = createSpans(spanProps, index, layoutSizeGetter)
        if (spans.isEmpty()) return
        if (spanProps is TextSpanProps && spanProps.adjustNewline) {
            append("\n", AbsoluteSizeSpan(1), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val spanStart = length
        val spanText = spanProps.text
        val spanEnd = spanStart + spanText.length
        spanTextRanges.add(SpanTextRange(index, childIndex, spanStart, spanEnd))
        append(buildSpannedString {
            inSpans(spans) { append(spanText) }
        })
        if (spanProps is TextSpanProps && spanProps.slockInlineCode) {
            applySlockInlineCodeAtomicTextSpans(spanStart, spanEnd)
        }
        if (spanProps is TextSpanProps && spanProps.inlineBoxStyle != null) {
            applyInlineBoxAtomicTextSpan(spanStart, spanEnd, spanProps.inlineBoxStyle)
        }
    }

    private fun SpannableStringBuilder.appendInlineBoxGroup(
        groupProps: InlineBoxGroupSpanProps,
        index: Int,
        defaultTextProps: KRTextProps,
        spanTextRanges: MutableList<SpanTextRange>,
        layoutSizeGetter: () -> SizeF,
    ) {
        val children = buildList {
            for (childIndex in 0 until groupProps.children.length()) {
                val childValue = groupProps.children.optJSONObject(childIndex) ?: continue
                val childProps = parseSpanProps(
                    childValue,
                    defaultTextProps,
                    isStart = isEmpty() || this@appendInlineBoxGroup[lastIndex] == '\n',
                )
                if (childProps.text.isNotEmpty()) add(childIndex to childProps)
            }
        }
        val atomicChild = children.singleOrNull()
        if (
            shouldAppendInlineBoxGroupAtomically(
                childCount = children.size,
                onlyChildIsText = atomicChild?.second is TextSpanProps,
                onlyChildAdjustsNewline = (atomicChild?.second as? TextSpanProps)?.adjustNewline == true,
            )
        ) {
            val (childIndex, childProps) = checkNotNull(atomicChild)
            val groupStart = length
            appendSpan(
                spanProps = childProps,
                index = index,
                childIndex = childIndex,
                spanTextRanges = spanTextRanges,
                layoutSizeGetter = layoutSizeGetter,
            )
            val groupEnd = length
            if (groupEnd > groupStart) {
                // A one-run inline box is an inline-block. Keeping it as one
                // ReplacementSpan makes Android move the whole token to the next
                // line instead of splitting the invisible group edges away from
                // its text and painting chrome over adjacent content.
                applyInlineBoxAtomicTextSpan(groupStart, groupEnd, groupProps.style)
                setSpan(
                    KRInlineBoxSpan(groupProps.style),
                    groupStart,
                    groupEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                setSpan(
                    KRInlineBoxSemanticSpan(groupProps.semanticText),
                    groupStart,
                    groupEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                spanTextRanges.add(SpanTextRange(index, null, groupStart, groupEnd))
            }
            return
        }
        val groupStart = length
        append(
            INLINE_BOX_LAYOUT_EDGE.toString(),
            KRInlineBoxEdgeAdvanceSpan(
                advance = groupProps.style.leadingAdvance,
                paddingTop = groupProps.style.paddingTop,
                paddingBottom = groupProps.style.paddingBottom,
            ),
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        children.forEachIndexed { position, (childIndex, childProps) ->
            append(INLINE_BOX_LAYOUT_JOINER)
            appendSpan(
                spanProps = childProps,
                index = index,
                childIndex = childIndex,
                spanTextRanges = spanTextRanges,
                layoutSizeGetter = layoutSizeGetter,
            )
        }
        append(INLINE_BOX_LAYOUT_JOINER)
        append(
            INLINE_BOX_LAYOUT_EDGE.toString(),
            KRInlineBoxEdgeAdvanceSpan(
                advance = groupProps.style.trailingAdvance,
                paddingTop = groupProps.style.paddingTop,
                paddingBottom = groupProps.style.paddingBottom,
            ),
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        val groupEnd = length
        if (groupEnd > groupStart) {
            setSpan(
                KRInlineBoxSpan(groupProps.style),
                groupStart,
                groupEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            setSpan(
                KRInlineBoxSemanticSpan(groupProps.semanticText),
                groupStart,
                groupEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            spanTextRanges.add(SpanTextRange(index, null, groupStart, groupEnd))
        }
    }

}

internal fun shouldAppendInlineBoxGroupAtomically(
    childCount: Int,
    onlyChildIsText: Boolean,
    onlyChildAdjustsNewline: Boolean,
): Boolean = childCount == 1 && onlyChildIsText && !onlyChildAdjustsNewline

abstract class SpanProps(spanValue: JSONObject) {
    protected val _text: String = spanValue.optString(KRTextProps.PROP_KEY_TEXT, "")
    open val text: String get() = _text
}

class TextSpanProps(
    spanValue: JSONObject,
    defaultProps: KRTextProps,
    isStart: Boolean,
    kuiklyContext: IKuiklyRenderContext?
) : SpanProps(spanValue) {

    val color: Int
    val fontSize: Float
    val fontFamily: String
    val fontWeight: String
    var fontVariant: String
    val fontStyle: Int
    val letterSpacing: Float
    val textDecoration: String
    val textDecorationColor: Int?
    val textDecorationThickness: Float?
    val textDecorationOffset: Float?
    val lineHeight: Float
    val backgroundImage: String
    val backgroundColor: Int
    val slockInlineCode: Boolean
    val slockInlineCodeTrailingMargin: Boolean
    val inlineBoxStyle: KRInlineBoxSpanStyle?
    var textShadow: BoxShadow? = null
    var useDpFontSizeDim = false

    val adjustNewline by lazy(LazyThreadSafetyMode.NONE) {
        !isStart && _text.isNotEmpty() && _text[0] == '\n'
    }
    private val _trimText by lazy(LazyThreadSafetyMode.NONE) {
        if (adjustNewline) {
            _text.substring(1)
        } else {
            _text
        }
    }

    override val text: String get() = _trimText

    init {
        color = spanValue.optString(KRTextProps.PROP_KEY_COLOR).let { colorStr ->
            if (colorStr.isNotEmpty()) {
                colorStr.toColor()
            } else {
                defaultProps.color
            }
        }
        fontSize = spanValue.optDouble(KRTextProps.PROP_KEY_FONT_SIZE, defaultProps.fontSize * 1.0).toFloat()
        fontFamily = spanValue.optString(KRTextProps.PROP_KEY_FONT_FAMILY, defaultProps.fontFamily)
        fontWeight = spanValue.optString(KRTextProps.PROP_KEY_FONT_WEIGHT, defaultProps.fontWeight)
        val fontStyleStr = spanValue.optString(KRTextProps.PROP_KEY_FONT_STYLE)
        fontStyle = if (fontStyleStr == KRTextProps.FONT_STYLE_ITALIC) {
            Typeface.ITALIC
        } else {
            defaultProps.fontStyle
        }
        fontVariant = spanValue.optString(KRTextProps.PROP_KEY_FONT_VARIANT)
        letterSpacing = if (spanValue.has(KRTextProps.PROP_KEY_LETTER_SPACING)) {
            spanValue.optDouble(KRTextProps.PROP_KEY_LETTER_SPACING).toFloat() / max(fontSize, 1f)
        } else {
            defaultProps.letterSpacing
        }
        textDecoration = spanValue.optString(KRTextProps.PROP_KEY_TEXT_DECORATION, defaultProps.textDecoration)
        textDecorationColor =
            spanValue.optString(TextConst.TEXT_DECORATION_COLOR)
                .takeIf { it.isNotEmpty() }
                ?.toColor()
        textDecorationThickness =
            spanValue.optDouble(TextConst.TEXT_DECORATION_THICKNESS, 0.0)
                .toFloat()
                .takeIf { it > 0f }
                ?.let { kuiklyContext.toPxF(it) }
        textDecorationOffset =
            spanValue.optDouble(TextConst.TEXT_DECORATION_OFFSET, 0.0)
                .toFloat()
                .takeIf { it != 0f }
                ?.let { kuiklyContext.toPxF(it) }
        lineHeight = if (spanValue.has(KRTextProps.PROP_KEY_LINE_HEIGHT)) {
            kuiklyContext.toPxF(spanValue.optDouble(KRTextProps.PROP_KEY_LINE_HEIGHT).toFloat())
        } else {
            defaultProps.lineHeight
        }
        backgroundImage = spanValue.optString(KRTextProps.PROP_KEY_BACKGROUND_IMAGE, defaultProps.backgroundImage)
        backgroundColor =
            spanValue.optString(KRCssConst.BACKGROUND_COLOR)
                .takeIf { it.isNotEmpty() }
                ?.toColor()
                ?: Color.TRANSPARENT
        slockInlineCode = spanValue.optInt(TextConst.SLOCK_INLINE_CODE, 0) == 1 ||
            spanValue.optBoolean(TextConst.SLOCK_INLINE_CODE, false)
        slockInlineCodeTrailingMargin = spanValue.optInt(TextConst.SLOCK_INLINE_CODE_TRAILING_MARGIN, 0) == 1 ||
            spanValue.optBoolean(TextConst.SLOCK_INLINE_CODE_TRAILING_MARGIN, false)
        inlineBoxStyle = KRInlineBoxSpanStyle.from(spanValue, kuiklyContext)
        val textShadowStr = spanValue.optString(KRTextProps.PROP_KEY_TEXT_SHADOW, "")
        textShadow = BoxShadow(textShadowStr, kuiklyContext)
        useDpFontSizeDim = spanValue.optInt(KRTextProps.PROP_KEY_TEXT_USE_DP_FONT_SIZE_DIM) == 1
    }

}

class PlaceholderSpanProps(spanValue: JSONObject, private val kuiklyContext: IKuiklyRenderContext?) : SpanProps(spanValue) {

    companion object {
        const val PROP_KEY_PLACEHOLDER_WIDTH = "placeholderWidth"
        const val PROP_KEY_PLACEHOLDER_HEIGHT = "placeholderHeight"
    }

    val width: Int
    val height: Int

    init {
        width = kuiklyContext.toPxI(spanValue.optDouble(PROP_KEY_PLACEHOLDER_WIDTH, 0.0).toFloat())
        height = kuiklyContext.toPxI(spanValue.optDouble(PROP_KEY_PLACEHOLDER_HEIGHT, 0.0).toFloat())
    }

}

/**
 * 用于记录 DSL Span 对应的 Text Range
 */
data class SpanTextRange(
    val index: Int,
    val childIndex: Int?,
    val start: Int,
    val end: Int,
) {
    override fun toString(): String {
        return "{$index, $start, $end}"
    }
}

class InlineBoxGroupSpanProps(
    spanValue: JSONObject,
    defaultProps: KRTextProps,
    kuiklyContext: IKuiklyRenderContext?,
) : SpanProps(spanValue) {
    companion object {
        const val PROP_KEY_CHILDREN = "inlineBoxChildren"
        private const val PROP_KEY_SEMANTIC_TEXT = "inlineBoxSemanticText"
    }

    val children = spanValue.optJSONArray(PROP_KEY_CHILDREN) ?: org.json.JSONArray()
    val semanticText = spanValue.optString(PROP_KEY_SEMANTIC_TEXT, "")
    val style = checkNotNull(KRInlineBoxSpanStyle.from(spanValue, kuiklyContext))
}

class KRSlockInlineCodeSpan

data class KRInlineBoxSpanStyle(
    val backgroundColor: Int?,
    val borderColor: Int?,
    val borderWidth: Float,
    val paddingStart: Float,
    val paddingEnd: Float,
    val paddingTop: Float,
    val paddingBottom: Float,
    val marginStart: Float,
    val marginEnd: Float,
    val cornerRadius: Float,
) {
    val leadingAdvance: Float
        get() = marginStart + borderWidth + paddingStart

    val trailingAdvance: Float
        get() = paddingEnd + borderWidth + marginEnd

    companion object {
        fun from(value: JSONObject, context: IKuiklyRenderContext?): KRInlineBoxSpanStyle? {
            val hasStyle = value.has(TextConst.INLINE_BOX_BACKGROUND_COLOR) ||
                value.has(TextConst.INLINE_BOX_BORDER_COLOR) ||
                value.has(TextConst.INLINE_BOX_BORDER_WIDTH) ||
                value.has(TextConst.INLINE_BOX_PADDING_START) ||
                value.has(TextConst.INLINE_BOX_PADDING_END) ||
                value.has(TextConst.INLINE_BOX_PADDING_TOP) ||
                value.has(TextConst.INLINE_BOX_PADDING_BOTTOM) ||
                value.has(TextConst.INLINE_BOX_MARGIN_START) ||
                value.has(TextConst.INLINE_BOX_MARGIN_END) ||
                value.has(TextConst.INLINE_BOX_CORNER_RADIUS)
            if (!hasStyle) return null
            fun color(key: String): Int? = value.optString(key).takeIf { it.isNotEmpty() }?.toColor()
            fun dimension(key: String): Float {
                val logicalPx = value.optDouble(key, 0.0).toFloat()
                return if (logicalPx == 0f) 0f else context.toPxF(logicalPx)
            }
            return KRInlineBoxSpanStyle(
                backgroundColor = color(TextConst.INLINE_BOX_BACKGROUND_COLOR),
                borderColor = color(TextConst.INLINE_BOX_BORDER_COLOR),
                borderWidth = dimension(TextConst.INLINE_BOX_BORDER_WIDTH),
                paddingStart = dimension(TextConst.INLINE_BOX_PADDING_START),
                paddingEnd = dimension(TextConst.INLINE_BOX_PADDING_END),
                paddingTop = dimension(TextConst.INLINE_BOX_PADDING_TOP),
                paddingBottom = dimension(TextConst.INLINE_BOX_PADDING_BOTTOM),
                marginStart = dimension(TextConst.INLINE_BOX_MARGIN_START),
                marginEnd = dimension(TextConst.INLINE_BOX_MARGIN_END),
                cornerRadius = dimension(TextConst.INLINE_BOX_CORNER_RADIUS),
            )
        }
    }
}

class KRInlineBoxSpan(val style: KRInlineBoxSpanStyle)
class KRInlineBoxSemanticSpan(val text: String)

private class KRInlineBoxEdgeAdvanceSpan(
    private val advance: Float,
    private val paddingTop: Float = 0f,
    private val paddingBottom: Float = 0f,
) : ReplacementSpan() {
    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        fm?.let {
            it.ascent -= ceil(paddingTop).toInt()
            it.top -= ceil(paddingTop).toInt()
            it.descent += ceil(paddingBottom).toInt()
            it.bottom += ceil(paddingBottom).toInt()
        }
        return ceil(advance).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        Unit
    }
}

private class KRSlockInlineCodeTrailingMarginSpan : ReplacementSpan() {

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int = ceil((paint.textSize * SLOCK_INLINE_CODE_TRAILING_MARGIN_RATIO).toDouble()).toInt()

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) = Unit
}

private class KRCustomUnderlineSpan(
    private val color: Int?,
    private val thickness: Float?,
    private val offset: Float?
) : ReplacementSpan() {

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int =
        if (text == null || start >= end) {
            0
        } else {
            ceil(paint.measureText(text, start, end).toDouble()).toInt()
        }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        if (text == null || start >= end) return
        canvas.drawText(text, start, end, x, y.toFloat(), paint)

        val lineWidth = paint.measureText(text, start, end)
        val previousColor = paint.color
        val previousStrokeWidth = paint.strokeWidth
        val previousStyle = paint.style
        val previousAntiAlias = paint.isAntiAlias
        paint.color = color ?: previousColor
        paint.strokeWidth = thickness ?: max(1f, previousStrokeWidth)
        paint.style = Paint.Style.STROKE
        paint.isAntiAlias = true
        val underlineY = y.toFloat() + (offset ?: max(1f, paint.strokeWidth))
        canvas.drawLine(x, underlineY, x + lineWidth, underlineY, paint)
        paint.color = previousColor
        paint.strokeWidth = previousStrokeWidth
        paint.style = previousStyle
        paint.isAntiAlias = previousAntiAlias
    }
}

private fun SpannableStringBuilder.applyInlineBoxAtomicTextSpan(
    start: Int,
    end: Int,
    style: KRInlineBoxSpanStyle,
) {
    if (start < end) {
        setSpan(
            KRInlineBoxAtomicTextSpan(style),
            start,
            end,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
}

internal class KRInlineBoxAtomicTextSpan(
    private val style: KRInlineBoxSpanStyle,
) : ReplacementSpan() {
    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        if (text == null || start >= end) return 0
        fm?.let {
            it.ascent -= ceil(style.paddingTop).toInt()
            it.top -= ceil(style.paddingTop).toInt()
            it.descent += ceil(style.paddingBottom).toInt()
            it.bottom += ceil(style.paddingBottom).toInt()
        }
        val edgeStart = style.marginStart + style.borderWidth + style.paddingStart
        val edgeEnd = style.paddingEnd + style.borderWidth + style.marginEnd
        return ceil((paint.measureText(text, start, end) + edgeStart + edgeEnd).toDouble()).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        if (text != null && start < end) {
            val textX = x + style.marginStart + style.borderWidth + style.paddingStart
            canvas.drawText(text, start, end, textX, y.toFloat(), paint)
        }
    }
}

internal data class KRInlineBoxAtomicHitRange(
    val line: Int,
    val left: Float,
    val right: Float,
    val spanIndex: Int,
)

internal fun resolveKRInlineBoxAtomicHit(
    touchedLine: Int,
    touchX: Float,
    ranges: List<KRInlineBoxAtomicHitRange>,
): Int? =
    ranges.firstOrNull { range ->
        range.line == touchedLine &&
            touchX >= minOf(range.left, range.right) &&
            touchX <= maxOf(range.left, range.right)
    }?.spanIndex

internal fun resolveKRInlineBoxBoundaryHit(
    touchedLine: Int,
    touchX: Float,
    ranges: List<KRInlineBoxAtomicHitRange>,
    fallbackSpanIndices: List<Int>,
): Int? {
    resolveKRInlineBoxAtomicHit(touchedLine, touchX, ranges)?.let { return it }
    val atomicOwnerIndices = ranges.mapTo(mutableSetOf(), KRInlineBoxAtomicHitRange::spanIndex)
    return fallbackSpanIndices.firstOrNull { it !in atomicOwnerIndices }
}

private fun SpannableStringBuilder.applySlockInlineCodeAtomicTextSpans(start: Int, end: Int) {
    var index = start
    var firstAtom = true
    while (index < end) {
        if (this[index].isSlockInlineCodeAtomBoundaryWhitespace()) {
            index++
            continue
        }
        val rangeStart = index
        while (index < end && this[index].isSlockInlineCodeBreakSeparator()) {
            index++
        }
        val textStart = index
        while (index < end &&
            !this[index].isSlockInlineCodeAtomBoundaryWhitespace() &&
            !this[index].isSlockInlineCodeBreakSeparator()
        ) {
            index++
        }
        // #58: a long no-break run would be one atomic ReplacementSpan and would
        // overflow the line. Emit it as per-character seamless atoms so the
        // layout char-wraps it, the way a standard engine wraps a long word.
        if (index - textStart > SLOCK_INLINE_CODE_LONG_RUN_THRESHOLD) {
            var charIndex = rangeStart
            while (charIndex < index) {
                val padStart = firstAtom && charIndex == rangeStart
                val padEnd = charIndex == index - 1 && !hasSlockInlineCodeAtomAfter(index, end)
                setSpan(
                    KRSlockInlineCodeAtomicTextSpan(padStart, padEnd, seamless = true),
                    charIndex,
                    charIndex + 1,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                firstAtom = false
                charIndex++
            }
            continue
        }
        var textLength = index - textStart
        while (textLength in 1..2 &&
            index < end &&
            this[index].isSlockInlineCodeBreakSeparator()
        ) {
            val separatorStart = index
            while (index < end && this[index].isSlockInlineCodeBreakSeparator()) {
                index++
            }
            val nextTextStart = index
            while (index < end &&
                !this[index].isSlockInlineCodeAtomBoundaryWhitespace() &&
                !this[index].isSlockInlineCodeBreakSeparator()
            ) {
                index++
            }
            if (index <= nextTextStart) {
                index = separatorStart
                break
            }
            textLength = index - textStart
        }
        if (textLength == 0 &&
            rangeStart < index &&
            !hasSlockInlineCodeAtomAfter(index, end)
        ) {
            textLength = index - rangeStart
        }
        if (textLength > 0) {
            val padStart = firstAtom
            val padEnd = !hasSlockInlineCodeAtomAfter(index, end)
            setSpan(
                KRSlockInlineCodeAtomicTextSpan(padStart, padEnd),
                rangeStart,
                index,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            firstAtom = false
        }
    }
}

private fun Char.isSlockInlineCodeBreakSeparator(): Boolean =
    this == '/' || this == '\\' || this == '.' || this == '-' || this == ':'

private fun Char.isSlockInlineCodeAtomBoundaryWhitespace(): Boolean =
    isWhitespace() || this == '\u00A0'

private fun CharSequence.hasSlockInlineCodeAtomAfter(start: Int, end: Int): Boolean {
    var index = start
    while (index < end) {
        if (this[index].isSlockInlineCodeAtomBoundaryWhitespace()) {
            index++
            continue
        }
        while (index < end && this[index].isSlockInlineCodeBreakSeparator()) {
            index++
        }
        val textStart = index
        while (index < end &&
            !this[index].isSlockInlineCodeAtomBoundaryWhitespace() &&
            !this[index].isSlockInlineCodeBreakSeparator()
        ) {
            index++
        }
        if (index > textStart) return true
    }
    return false
}

private class KRSlockInlineCodeAtomicTextSpan(
    private val padStart: Boolean,
    private val padEnd: Boolean,
    // #58: per-character atoms of a char-wrapped long run. They must not each add
    // stroke padding, or the run would spread out; the chrome/border is drawn
    // per line-segment by the drawer, so interior atoms need none.
    private val seamless: Boolean = false
) : ReplacementSpan() {

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int = if (text == null || start >= end) {
        0
    } else {
        val textWidth = paint.measureText(text, start, end)
        val strokePadding = if (seamless) 0f else max(1f, paint.strokeWidth * 2f)
        ceil((textWidth + strokePadding + startPadding(paint) + endPadding(paint)).toDouble()).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        if (text != null && start < end) {
            canvas.drawText(text, start, end, x + startPadding(paint), y.toFloat(), paint)
        }
    }

    private fun startPadding(paint: Paint): Float {
        return if (padStart) edgePadding(paint) else 0f
    }

    private fun endPadding(paint: Paint): Float {
        return if (padEnd) edgePadding(paint) else 0f
    }

    private fun edgePadding(paint: Paint): Float {
        return paint.textSize * (SLOCK_INLINE_CODE_EDGE_PADDING_RATIO + SLOCK_INLINE_CODE_EDGE_MARGIN_RATIO)
    }
}

/**
 * 字重span
 * @param fontWeight 字重
 * @param index 标识是第几个span
 */
class FontWeightSpan(fontWeight: String, val index: Int = -1) : CharacterStyle() {

    private val requestedWeight = fontWeight.toIntOrNull() ?: FONT_WEIGHT_NORMAL.toInt()
    private val strokeWidth = getFontWeight(fontWeight)
    private val fakeBold = isBoldWeight(fontWeight)

    override fun updateDrawState(tp: TextPaint) {
        val nativeTypefaceSatisfiesWeight =
            requestedWeight == FONT_WEIGHT_BOLD.toInt() && tp.typeface?.isBold == true
        if (fakeBold && !nativeTypefaceSatisfiesWeight) {
            tp.isFakeBoldText = true
        }
        if (strokeWidth != 0f && !nativeTypefaceSatisfiesWeight) {
            tp.style = Paint.Style.FILL_AND_STROKE
            tp.strokeWidth = strokeWidth * tp.textSize
        }
    }

    companion object {
        private const val FONT_WEIGHT_NORMAL = "400"
        private const val FONT_WEIGHT_MEDIUM = "500"
        private const val FONT_WEIGHT_MEDIUM_BOLD = "600"
        private const val FONT_WEIGHT_BOLD = "700"
        private const val FONT_WEIGHT_EXTRA_BOLD = "800"
        private const val FONT_WEIGHT_BLACK = "900"

        private const val SCALE = 50f
        private const val FONT_WEIGHT_NORMAL_VALUE = 0f
        private const val FONT_WEIGHT_MEDIUM_VALUE = 0.5f / SCALE
        private const val FONT_WEIGHT_MEDIUM_BOLD_VALUE = 1f / SCALE
        private const val FONT_WEIGHT_BOLD_VALUE = 1.5f / SCALE
        private const val FONT_WEIGHT_EXTRA_BOLD_VALUE = 2f / SCALE
        private const val FONT_WEIGHT_BLACK_VALUE = 2.5f / SCALE

        fun getFontWeight(fontWeight: String): Float {
            return when (fontWeight) {
                FONT_WEIGHT_NORMAL -> FONT_WEIGHT_NORMAL_VALUE
                FONT_WEIGHT_MEDIUM -> FONT_WEIGHT_MEDIUM_VALUE
                FONT_WEIGHT_MEDIUM_BOLD -> FONT_WEIGHT_MEDIUM_BOLD_VALUE
                FONT_WEIGHT_BOLD -> FONT_WEIGHT_BOLD_VALUE
                FONT_WEIGHT_EXTRA_BOLD -> FONT_WEIGHT_EXTRA_BOLD_VALUE
                FONT_WEIGHT_BLACK -> FONT_WEIGHT_BLACK_VALUE
                else -> FONT_WEIGHT_NORMAL_VALUE
            }
        }

        private fun isBoldWeight(fontWeight: String): Boolean =
            fontWeight == FONT_WEIGHT_BOLD ||
                fontWeight == FONT_WEIGHT_EXTRA_BOLD ||
                fontWeight == FONT_WEIGHT_BLACK
    }
}

/**
 * 异形字体span
 */
class FontVariantSpan(private val fontVariant: String) : CharacterStyle() {

    override fun updateDrawState(tp: TextPaint) {
        tp.fontFeatureSettings = fontVariant
    }

}

/**
 * 字母间距span
 */
class LetterSpacingSpan(private val letterSpacing: Float) : MetricAffectingSpan() {

    override fun updateDrawState(tp: TextPaint) {
        apply(tp)
    }

    override fun updateMeasureState(textPaint: TextPaint) {
        apply(textPaint)
    }

    private fun apply(tp: TextPaint) {
        if (letterSpacing != 0f) {
            tp.letterSpacing = letterSpacing
        }
    }
}

/**
 * 字体span
 */
class FontFamilySpan(fontFamily: String, typeFaceLoader: TypeFaceLoader?) : TypefaceSpan(KRCssConst.EMPTY_STRING) {

    private var tfe: Typeface? = null

    init {
        tfe = typeFaceLoader?.getTypeface(fontFamily, false)
    }

    override fun updateDrawState(ds: TextPaint) {
        tfe?.also {
            applyCustomTypeFace(ds, it)
        } ?: also {
            super.updateDrawState(ds)
        }
    }

    override fun updateMeasureState(paint: TextPaint) {
        tfe?.also {
            applyCustomTypeFace(paint, it)
        } ?: also {
            super.updateMeasureState(paint)
        }
    }

    private fun applyCustomTypeFace(paint: Paint, tf: Typeface) {
        paint.typeface = tf
    }
}

class HRLineHeightSpan(internal val height: Int) : LineHeightSpan {

    // CSS line-height distributes extra leading around the font's ascent and
    // descent. Android top/bottom include font-padding extents even when
    // StaticLayout.setIncludePad(false), which pushes custom fonts such as
    // Space Grotesk below the equivalent browser baseline. Keep this strictly
    // metrics-based: glyph-bounds centering makes placement depend on the text
    // itself and causes editable content to jump while typing.
    override fun chooseHeight(
        text: CharSequence?,
        start: Int,
        end: Int,
        spanstartv: Int,
        lineHeight: Int,
        fm: Paint.FontMetricsInt
    ) {
        val additional: Int = height - (fm.descent - fm.ascent)
        val topExtra = additional / 2
        fm.ascent -= topExtra
        fm.descent += additional - topExtra
        fm.top = fm.ascent
        fm.bottom = fm.descent
    }
}

class LinearGradientForegroundSpan(
    backgroundImage: String,
    private val sizeGetter: () -> SizeF
) : CharacterStyle(), UpdateAppearance {

    private val backgroundImageParseTriple = KRCSSBackgroundDrawable.parseBackgroundImage(backgroundImage)

    override fun updateDrawState(tp: TextPaint) {
        val x0: Float
        val x1: Float
        val y0: Float
        val y1: Float
        val r = RectF().apply {
            val sizeF = sizeGetter.invoke()
            left = 0f
            top = 0f
            right = sizeF.width
            bottom = sizeF.height
        }

        when (backgroundImageParseTriple.first) {
            GradientDrawable.Orientation.TOP_BOTTOM -> {
                x0 = r.left
                y0 = r.top
                x1 = x0
                y1 = r.bottom
            }
            GradientDrawable.Orientation.TR_BL -> {
                x0 = r.right
                y0 = r.top
                x1 = r.left
                y1 = r.bottom
            }
            GradientDrawable.Orientation.RIGHT_LEFT -> {
                x0 = r.right
                y0 = r.top
                x1 = r.left
                y1 = y0
            }
            GradientDrawable.Orientation.BR_TL -> {
                x0 = r.right
                y0 = r.bottom
                x1 = r.left
                y1 = r.top
            }
            GradientDrawable.Orientation.BOTTOM_TOP -> {
                x0 = r.left
                y0 = r.bottom
                x1 = x0
                y1 = r.top
            }
            GradientDrawable.Orientation.BL_TR -> {
                x0 = r.left
                y0 = r.bottom
                x1 = r.right
                y1 = r.top
            }
            GradientDrawable.Orientation.LEFT_RIGHT -> {
                x0 = r.left
                y0 = r.top
                x1 = r.right
                y1 = y0
            }
            else -> {
                x0 = r.left
                y0 = r.top
                x1 = r.right
                y1 = r.bottom
            }
        }

        tp.shader = LinearGradient(
            x0,
            y0,
            x1,
            y1,
            backgroundImageParseTriple.second,
            backgroundImageParseTriple.third,
            Shader.TileMode.REPEAT
        )
    }
}

/**
 * 字体阴影
 */
class TextShadowSpan(
    private val dx: Float,
    private val dy: Float,
    private val radius: Float,
    private val color: Int
) : CharacterStyle() {

    override fun updateDrawState(textPaint: TextPaint) {
        textPaint.setShadowLayer(radius, dx, dy, color)
    }

}

/**
 * PlaceHolderSpan，用于实现空白区域占位
 */
class KRPlaceholderSpan(private val spanProps: PlaceholderSpanProps): ReplacementSpan() {

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        if (fm != null) {
            val diff = spanProps.height - (fm.bottom - fm.top)
            fm.bottom = maxOf(0, fm.bottom + diff / 2)
            fm.top = fm.bottom - spanProps.height
            fm.ascent = fm.top
            fm.descent = fm.bottom
        }
        return spanProps.width
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {}

    fun width(): Int {
        return spanProps.width
    }

    fun height(): Int {
        return spanProps.height
    }

}
