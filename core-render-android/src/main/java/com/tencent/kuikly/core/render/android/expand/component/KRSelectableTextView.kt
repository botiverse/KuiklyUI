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

package com.tencent.kuikly.core.render.android.expand.component

import android.content.Context
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.util.TypedValue
import android.view.Gravity
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.tencent.kuikly.core.render.android.const.KRCssConst
import com.tencent.kuikly.core.render.android.css.ktx.spToPxI
import com.tencent.kuikly.core.render.android.css.ktx.toColor
import com.tencent.kuikly.core.render.android.css.ktx.toPxF
import com.tencent.kuikly.core.render.android.css.ktx.toPxI
import com.tencent.kuikly.core.render.android.expand.component.text.FontWeightSpan
import com.tencent.kuikly.core.render.android.expand.component.text.HRLineHeightSpan
import com.tencent.kuikly.core.render.android.export.IKuiklyRenderViewExport

/**
 * System-selectable plain text: a read-only [TextView] with
 * [setTextIsSelectable] enabled so the platform ActionMode appears anchored
 * to the selection. Baseline guarantee: Select all / Copy. Additional items
 * (e.g. Translate or other PROCESS_TEXT targets) appear only when the OS
 * version, locale and installed services provide them. Never editable,
 * never shows an IME; text changes only through the "text" prop.
 */
class KRSelectableTextView(context: Context) : TextView(context), IKuiklyRenderViewExport {

    private var rawText: String = ""
    private var lineHeightPx: Int? = null
    private var useDpFontSizeDim = false
    private var rawFontSize: Float? = null
    private var rawLineHeight: Float? = null

    init {
        setTextIsSelectable(true)
        gravity = Gravity.LEFT or Gravity.TOP
        includeFontPadding = false
        setTextColor(Color.BLACK)
        background = null
        setPadding(0, 0, 0, 0)
    }

    // Selection state must never leak across cells/pages.
    override val reusable: Boolean
        get() = false

    override fun setProp(propKey: String, propValue: Any): Boolean {
        return when (propKey) {
            PROP_TEXT -> {
                rawText = propValue as? String ?: ""
                applyText()
                true
            }
            PROP_FONT_SIZE -> {
                rawFontSize = (propValue as Number).toFloat()
                applyFontSize()
                true
            }
            PROP_FONT_WEIGHT -> {
                FontWeightSpan(propValue as String).updateDrawState(paint)
                applyText()
                true
            }
            PROP_COLOR -> {
                setTextColor((propValue as String).toColor())
                true
            }
            PROP_LINE_HEIGHT -> {
                rawLineHeight = (propValue as Number).toFloat()
                applyLineHeight()
                true
            }
            PROP_TEXT_ALIGN -> {
                applyTextAlign(propValue as String)
                true
            }
            PROP_USE_DP_FONT_SIZE_DIM -> {
                useDpFontSizeDim = (propValue as Int) == 1
                applyFontSize()
                applyLineHeight()
                true
            }
            // View capability: this surface's clickable/long-clickable a11y
            // truth comes from the system TextView selection semantics. The
            // compose semantics bridge derives its boolean mask from compose
            // click semantics (absent here) and would report the view as not
            // long-clickable, breaking a11y ACTION_LONG_CLICK and automation
            // readouts. Decline only this mask; every other a11y prop (role,
            // testTag, plain text, state description) still applies normally.
            KRCssConst.ACCESSIBILITY_INFO -> true
            else -> super.setProp(propKey, propValue)
        }
    }

    /**
     * Final-layer accessibility truth: whatever delegates or masked props ran
     * upstream, the exposed node info derives clickable/long-clickable from
     * the real view flags and advertises the system selection actions.
     */
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isClickable = isClickable
        info.isLongClickable = isLongClickable
        if (isLongClickable) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
        }
        if (isTextSelectable) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION)
        }
    }

    private fun applyFontSize() {
        val fontSize = rawFontSize ?: return
        setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            if (useDpFontSizeDim) {
                kuiklyRenderContext.toPxF(fontSize)
            } else {
                kuiklyRenderContext.spToPxI(fontSize).toFloat()
            }
        )
    }

    private fun applyLineHeight() {
        val lineHeight = rawLineHeight ?: return
        lineHeightPx = if (useDpFontSizeDim) {
            kuiklyRenderContext.toPxI(lineHeight)
        } else {
            kuiklyRenderContext.spToPxI(lineHeight)
        }
        applyText()
    }

    private fun applyTextAlign(align: String) {
        val horizontal = when (align) {
            "center" -> Gravity.CENTER_HORIZONTAL
            "right" -> Gravity.RIGHT
            else -> Gravity.LEFT
        }
        gravity = (gravity and Gravity.HORIZONTAL_GRAVITY_MASK.inv()) or horizontal
    }

    private fun applyText() {
        val content = SpannableString(rawText)
        lineHeightPx?.takeIf { it > 0 }?.also { height ->
            if (content.isNotEmpty()) {
                content.setSpan(
                    HRLineHeightSpan(height),
                    0,
                    content.length,
                    Spannable.SPAN_INCLUSIVE_INCLUSIVE
                )
            }
        }
        setText(content, BufferType.SPANNABLE)
    }

    companion object {
        const val VIEW_NAME = "KRSelectableTextView"

        internal const val PROP_TEXT = "text"
        internal const val PROP_FONT_SIZE = "fontSize"
        internal const val PROP_FONT_WEIGHT = "fontWeight"
        internal const val PROP_COLOR = "color"
        internal const val PROP_LINE_HEIGHT = "lineHeight"
        internal const val PROP_TEXT_ALIGN = "textAlign"
        internal const val PROP_USE_DP_FONT_SIZE_DIM = "useDpFontSizeDim"

        internal val HANDLED_PROPS = setOf(
            PROP_TEXT,
            PROP_FONT_SIZE,
            PROP_FONT_WEIGHT,
            PROP_COLOR,
            PROP_LINE_HEIGHT,
            PROP_TEXT_ALIGN,
            PROP_USE_DP_FONT_SIZE_DIM
        )
    }
}
