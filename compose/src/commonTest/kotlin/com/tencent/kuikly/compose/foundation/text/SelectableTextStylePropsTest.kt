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

package com.tencent.kuikly.compose.foundation.text

import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.views.SelectableTextAttr
import com.tencent.kuikly.core.views.TextConst
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The resolver is the contract between Compose style values and the native
 * prop wire format shared by all three renderers and the measuring shadow.
 * Because the backing compose node is reusable, every supported field must
 * always resolve to a concrete wire value — these teeth pin both the
 * mapping and the reset-on-reuse behavior.
 */
class SelectableTextStylePropsTest {

    private val defaultProps = SelectableTextStyleProps(
        color = Color.Black.toKuiklyColor().toString(),
        fontSize = SELECTABLE_TEXT_DEFAULT_FONT_SIZE,
        fontWeight = "400",
        lineHeight = SELECTABLE_TEXT_DEFAULT_FONT_SIZE * SELECTABLE_TEXT_DEFAULT_LINE_HEIGHT_FACTOR,
        textAlign = "left",
    )

    @Test
    fun unspecifiedStyleResolvesToConcreteDefaultsForEveryField() {
        assertEquals(
            defaultProps,
            resolveSelectableTextStyleProps(TextStyle.Default, densityScale = 1f)
        )
    }

    private val styledStyle = TextStyle(
        color = Color.Red,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 30.sp,
        textAlign = TextAlign.Right,
    )

    private fun assertAttrHoldsStyledValues(attr: SelectableTextAttr) {
        assertEquals(Color.Red.toKuiklyColor().toString(), attr.getProp(TextConst.TEXT_COLOR))
        assertEquals(20f, attr.getProp(TextConst.FONT_SIZE))
        assertEquals("700", attr.getProp(TextConst.FONT_WEIGHT))
        assertEquals(30f, attr.getProp(TextConst.LINE_HEIGHT))
        assertEquals("right", attr.getProp(TextConst.TEXT_ALIGN))
    }

    private fun assertAttrHoldsDefaultValues(attr: SelectableTextAttr) {
        assertEquals(defaultProps.color, attr.getProp(TextConst.TEXT_COLOR))
        assertEquals(defaultProps.fontSize, attr.getProp(TextConst.FONT_SIZE))
        assertEquals(defaultProps.fontWeight, attr.getProp(TextConst.FONT_WEIGHT))
        assertEquals(defaultProps.lineHeight, attr.getProp(TextConst.LINE_HEIGHT))
        assertEquals(defaultProps.textAlign, attr.getProp(TextConst.TEXT_ALIGN))
    }

    @Test
    fun applyToWritesStyledThenDefaultThenStyledThroughTheProductionAttrPath() {
        // The reusable-node sequence through the REAL write path: the same
        // SelectableTextAttr receives applyTo for a fully styled update, then
        // TextStyle.Default, then styled again. Every supported wire key must
        // be overwritten on each step — a missed key in applyTo fails here.
        val attr = SelectableTextAttr()

        resolveSelectableTextStyleProps(styledStyle, densityScale = 1f).applyTo(attr)
        assertAttrHoldsStyledValues(attr)

        resolveSelectableTextStyleProps(TextStyle.Default, densityScale = 1f).applyTo(attr)
        assertAttrHoldsDefaultValues(attr)

        resolveSelectableTextStyleProps(styledStyle, densityScale = 1f).applyTo(attr)
        assertAttrHoldsStyledValues(attr)
    }

    @Test
    fun propPairSequenceAuxiliaryCheckResetsEveryKey() {
        // Auxiliary wire-level view of the same sequence (kept in addition to,
        // not instead of, the attr-path test above).
        val propStore = mutableMapOf<String, Any>()
        resolveSelectableTextStyleProps(styledStyle, densityScale = 1f)
            .asPropPairs().forEach { (key, value) -> propStore[key] = value }
        resolveSelectableTextStyleProps(TextStyle.Default, densityScale = 1f)
            .asPropPairs().forEach { (key, value) -> propStore[key] = value }

        val expected: Map<String, Any> = mapOf(
            TextConst.TEXT_COLOR to defaultProps.color,
            TextConst.FONT_SIZE to defaultProps.fontSize,
            TextConst.FONT_WEIGHT to defaultProps.fontWeight,
            TextConst.LINE_HEIGHT to defaultProps.lineHeight,
            TextConst.TEXT_ALIGN to defaultProps.textAlign,
        )
        assertEquals(expected, propStore)
    }

    @Test
    fun propPairsAlwaysCoverTheFullWireContract() {
        val keys = resolveSelectableTextStyleProps(TextStyle.Default, densityScale = 1f)
            .asPropPairs().map { it.first }
        assertEquals(
            listOf(
                TextConst.TEXT_COLOR,
                TextConst.FONT_SIZE,
                TextConst.FONT_WEIGHT,
                TextConst.LINE_HEIGHT,
                TextConst.TEXT_ALIGN,
            ),
            keys
        )
    }

    @Test
    fun colorResolvesToKuiklyColorString() {
        val props = resolveSelectableTextStyleProps(
            TextStyle(color = Color.Red),
            densityScale = 1f
        )
        assertEquals(Color.Red.toKuiklyColor().toString(), props.color)
    }

    @Test
    fun fontSizeAndLineHeightScaleWithDensity() {
        val props = resolveSelectableTextStyleProps(
            TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            densityScale = 1.5f
        )
        assertEquals(24f, props.fontSize)
        assertEquals(36f, props.lineHeight)
    }

    @Test
    fun unspecifiedLineHeightFollowsResolvedFontSize() {
        val props = resolveSelectableTextStyleProps(
            TextStyle(fontSize = 18.sp),
            densityScale = 1f
        )
        assertEquals(18f, props.fontSize)
        assertEquals(18f * SELECTABLE_TEXT_DEFAULT_LINE_HEIGHT_FACTOR, props.lineHeight)
    }

    @Test
    fun fontWeightBucketsMatchNativeWeightStrings() {
        fun weightProp(weight: FontWeight): String = resolveSelectableTextStyleProps(
            TextStyle(fontWeight = weight),
            densityScale = 1f
        ).fontWeight

        assertEquals("400", weightProp(FontWeight.W300))
        assertEquals("400", weightProp(FontWeight.Normal))
        assertEquals("500", weightProp(FontWeight.Medium))
        assertEquals("600", weightProp(FontWeight.SemiBold))
        assertEquals("700", weightProp(FontWeight.Bold))
        assertEquals("700", weightProp(FontWeight.W900))
    }

    @Test
    fun textAlignMapsToNativeAlignKeywords() {
        fun alignProp(align: TextAlign): String = resolveSelectableTextStyleProps(
            TextStyle(textAlign = align),
            densityScale = 1f
        ).textAlign

        assertEquals("left", alignProp(TextAlign.Left))
        assertEquals("left", alignProp(TextAlign.Start))
        assertEquals("left", alignProp(TextAlign.Justify))
        assertEquals("center", alignProp(TextAlign.Center))
        assertEquals("right", alignProp(TextAlign.Right))
        assertEquals("right", alignProp(TextAlign.End))
    }
}
