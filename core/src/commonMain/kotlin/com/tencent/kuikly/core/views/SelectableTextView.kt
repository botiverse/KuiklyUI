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

package com.tencent.kuikly.core.views

import com.tencent.kuikly.core.base.Attr
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.base.ViewConst
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.event.Event
import com.tencent.kuikly.core.base.toInt
import com.tencent.kuikly.core.layout.FlexAlign
import com.tencent.kuikly.core.layout.FlexDirection
import com.tencent.kuikly.core.layout.FlexNode
import com.tencent.kuikly.core.layout.FlexPositionType
import com.tencent.kuikly.core.layout.MeasureFunction
import com.tencent.kuikly.core.layout.MeasureOutput
import com.tencent.kuikly.core.layout.isUndefined
import com.tencent.kuikly.core.views.shadow.TextShadow

/**
 * System-selectable plain text surface.
 *
 * Renders immutable plain text on each platform's native text view so the OS
 * selection experience is available anchored to the selection. The baseline
 * guarantee is: word selection, drag handles, Select all and Copy. Any
 * further menu actions (for example Translate, Look Up, Share, or Android
 * PROCESS_TEXT targets) appear only when the current OS version, locale and
 * installed services provide them — they are platform-supplied extras, not
 * guarantees of this component. The surface is read-only by construction —
 * it never participates in text input, never shows an IME, and exposes no
 * way for the user or the program to mutate text except through the
 * [SelectableTextAttr.text] prop.
 *
 * Platform mapping:
 * - Android: `TextView` with `setTextIsSelectable(true)` (system ActionMode)
 * - iOS: `UITextView` with `editable = NO`, `selectable = YES` (system edit menu)
 * - OHOS: ArkUI `Text` node with the system copy option enabled; the copy
 *   option guarantees local-device copy scope, and the visible menu items are
 *   whatever the platform's selection menu offers on that OS version
 *
 * Layout: content is measured with the shared rich-text shadow (same approach
 * as [TextAreaView]); scrolling is the caller's responsibility (wrap in a
 * scroller for long content).
 */
open class SelectableTextView : DeclarativeBaseView<SelectableTextAttr, Event>(), MeasureFunction {

    companion object {
        private val NON_SHADOW_PROPS by lazy(LazyThreadSafetyMode.NONE) {
            setOf(
                Attr.StyleConst.TRANSFORM,
                Attr.StyleConst.OPACITY,
                Attr.StyleConst.VISIBILITY,
                Attr.StyleConst.BACKGROUND_COLOR,
                TextConst.TEXT_COLOR
            )
        }
    }

    private var shadow: TextShadow? = null

    override fun willInit() {
        super.willInit()
        shadow = TextShadow(pagerId, nativeRef, ViewConst.TYPE_RICH_TEXT)
        getViewAttr().fontSize(15f)
    }

    override fun createAttr(): SelectableTextAttr {
        return SelectableTextAttr()
    }

    override fun createEvent(): Event {
        return Event()
    }

    override fun viewName(): String {
        return ViewConst.TYPE_SELECTABLE_TEXT
    }

    override fun createFlexNode() {
        super.createFlexNode()
        flexNode.measureFunction = this
    }

    override fun didRemoveFromParentView() {
        super.didRemoveFromParentView()
        flexNode.measureFunction = null
        shadow?.removeFromParentComponent()
        shadow = null
    }

    override fun didSetProp(propKey: String, propValue: Any) {
        super.didSetProp(propKey, propValue)
        if (propKey !in NON_SHADOW_PROPS) {
            shadow?.setProp(propKey, propValue)
            flexNode.markDirty()
        }
    }

    override fun measure(
        node: FlexNode,
        width: Float,
        height: Float,
        measureOutput: MeasureOutput
    ) {
        node.layoutDimensions.run {
            if (!this[0].isUndefined() && !this[1].isUndefined()) {
                measureOutput.width = this[0]
                measureOutput.height = this[1]
                return
            }
        }
        val cWidth = if (width.isUndefined()) 100000f else width
        val cHeight = if (height.isUndefined()) -1f else height
        val size = shadow?.calculateRenderViewSize(cWidth, cHeight)
        var outWidth = size?.width ?: 0f
        var outHeight = size?.height ?: 0f
        if (!width.isUndefined() && outWidth < width && node.stretchWidth()) {
            outWidth = width
        }
        if (!height.isUndefined() && outHeight < height && node.stretchHeight()) {
            outHeight = height
        }
        node.styleMinWidth.also {
            if (!it.isUndefined() && outWidth < it) {
                outWidth = it
            }
        }
        node.styleMaxHeight.also {
            if (!it.isUndefined() && outHeight > it) {
                outHeight = it
            }
        }
        node.styleMinHeight.also {
            if (!it.isUndefined() && outHeight < it) {
                outHeight = it
            }
        }
        measureOutput.width = outWidth
        measureOutput.height = outHeight
    }

    /**
     * Measures the native selectable text through the same [TextShadow] used
     * by the core flex path. Compose wrappers call this when their parent uses
     * an unbounded main-axis constraint (for example a vertical LazyColumn),
     * where the generic native-node measure policy cannot use maxHeight as an
     * actual layout dimension.
     */
    open fun calculateContentSize(maxWidth: Float, maxHeight: Float) =
        shadow?.calculateRenderViewSize(maxWidth, maxHeight)

    private fun FlexNode.stretchWidth(): Boolean {
        if (positionType != FlexPositionType.RELATIVE) {
            return false
        }
        val direction = parent?.flexDirection
        return if (direction == FlexDirection.ROW || direction == FlexDirection.ROW_REVERSE) {
            stretchMainAxis
        } else {
            parent?.layoutWidth?.isUndefined() == false && stretchCrossAxis
        }
    }

    private fun FlexNode.stretchHeight(): Boolean {
        if (positionType != FlexPositionType.RELATIVE) {
            return false
        }
        val direction = parent?.flexDirection
        return if (direction == FlexDirection.ROW || direction == FlexDirection.ROW_REVERSE) {
            parent?.layoutHeight?.isUndefined() == false && stretchCrossAxis
        } else {
            stretchMainAxis
        }
    }

    private inline val FlexNode.stretchMainAxis: Boolean get() = flex != 0f

    private inline val FlexNode.stretchCrossAxis: Boolean
        get() = alignSelf == FlexAlign.STRETCH ||
                (alignSelf == FlexAlign.AUTO && parent?.alignItems == FlexAlign.STRETCH)
}

/**
 * Attributes for [SelectableTextView]. Prop keys reuse [TextConst] so the
 * shared rich-text shadow measures with exactly the same values the native
 * view renders.
 */
open class SelectableTextAttr : Attr() {

    open fun text(text: String): SelectableTextAttr {
        TextConst.VALUE with text
        return this
    }

    open fun color(color: Color): SelectableTextAttr {
        TextConst.TEXT_COLOR with color.toString()
        return this
    }

    open fun color(color: Long): SelectableTextAttr {
        TextConst.TEXT_COLOR with Color(color).toString()
        return this
    }

    open fun fontSize(size: Float): SelectableTextAttr {
        TextConst.FONT_SIZE with size
        return this
    }

    open fun fontWeightNormal(): SelectableTextAttr {
        TextConst.FONT_WEIGHT with FontWeight.NORMAL.value
        return this
    }

    open fun fontWeightMedium(): SelectableTextAttr {
        TextConst.FONT_WEIGHT with FontWeight.MEDIUM.value
        return this
    }

    open fun fontWeightSemiBold(): SelectableTextAttr {
        TextConst.FONT_WEIGHT with FontWeight.SEMIBOLD.value
        return this
    }

    open fun fontWeightBold(): SelectableTextAttr {
        TextConst.FONT_WEIGHT with FontWeight.BOLD.value
        return this
    }

    open fun lineHeight(lineHeight: Float): SelectableTextAttr {
        TextConst.LINE_HEIGHT with lineHeight
        return this
    }

    open fun useDpFontSizeDim(useDp: Boolean = true): SelectableTextAttr {
        TextConst.TEXT_USE_DP_FONT_SIZE_DIM with useDp.toInt()
        return this
    }

    open fun textAlignLeft(): SelectableTextAttr {
        TextConst.TEXT_ALIGN with "left"
        return this
    }

    open fun textAlignCenter(): SelectableTextAttr {
        TextConst.TEXT_ALIGN with "center"
        return this
    }

    open fun textAlignRight(): SelectableTextAttr {
        TextConst.TEXT_ALIGN with "right"
        return this
    }
}

/**
 * Adds a system-selectable plain text view. See [SelectableTextView].
 */
fun ViewContainer<*, *>.SelectableText(init: SelectableTextView.() -> Unit) {
    addChild(SelectableTextView(), init)
}
