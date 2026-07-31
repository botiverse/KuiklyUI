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

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.extension.MakeKuiklyComposeNode
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.isSpecified
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.unit.isSpecified
import com.tencent.kuikly.core.views.SelectableTextAttr
import com.tencent.kuikly.core.views.SelectableTextView
import com.tencent.kuikly.core.views.TextConst

/**
 * System-selectable plain text.
 *
 * Renders [text] on the platform's native selectable text surface
 * (Android `TextView.setTextIsSelectable`, iOS `UITextView` with
 * `editable = false` / `selectable = true`, OHOS `Text` with the system copy
 * option). The OS supplies the selection experience anchored to the
 * selection: the baseline guarantee is word selection, drag handles,
 * Select all and Copy. Any further actions (e.g. Translate, Look Up, Share,
 * or PROCESS_TEXT targets on Android) appear only if the current OS version,
 * locale and installed services provide them — they are platform-supplied
 * extras, not guarantees of this component. The surface is strictly
 * read-only: it never opens an IME and text can only change via [text].
 *
 * Scrolling is not built in; wrap in a scrollable container for long content.
 *
 * Supported [style] fields: color, fontSize, fontWeight, lineHeight,
 * textAlign. Other fields are ignored by this minimal surface. Unspecified
 * fields resolve to deterministic defaults (black, 15f, 400, fontSize*4/3,
 * left) so style changes on a reused node always reset prior values.
 */
@Composable
fun SelectableText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
) {
    val density = LocalDensity.current
    MakeKuiklyComposeNode<SelectableTextView>(
        factory = { SelectableTextView() },
        modifier = modifier,
        viewUpdate = { view ->
            view.getViewAttr().run {
                text(text)
                val densityScale = density.density / getPager().pagerDensity()
                resolveSelectableTextStyleProps(style, densityScale).applyTo(this)
            }
        }
    )
}

/**
 * The resolved native prop values for a [SelectableText] style. Kept as plain
 * data so the mapping from Compose types is unit-testable.
 *
 * Every field is always concrete (never null): the compose node backing
 * SelectableText is reusable, so a `specified -> TextStyle.Default` update
 * must actively overwrite every previously written prop on both the native
 * renderer and the measuring TextShadow. Unspecified style fields therefore
 * resolve to deterministic defaults instead of "don't write".
 */
internal data class SelectableTextStyleProps(
    val color: String,
    val fontSize: Float,
    val fontWeight: String,
    val lineHeight: Float,
    val textAlign: String,
) {
    fun asPropPairs(): List<Pair<String, Any>> = listOf(
        TextConst.TEXT_COLOR to color,
        TextConst.FONT_SIZE to fontSize,
        TextConst.FONT_WEIGHT to fontWeight,
        TextConst.LINE_HEIGHT to lineHeight,
        TextConst.TEXT_ALIGN to textAlign,
    )
}

internal const val SELECTABLE_TEXT_DEFAULT_FONT_SIZE = 15f
internal const val SELECTABLE_TEXT_DEFAULT_LINE_HEIGHT_FACTOR = 4f / 3f

internal fun resolveSelectableTextStyleProps(
    style: TextStyle,
    densityScale: Float,
): SelectableTextStyleProps {
    val color = if (style.color.isSpecified) {
        style.color.toKuiklyColor().toString()
    } else {
        Color.Black.toKuiklyColor().toString()
    }
    val fontSize = if (style.fontSize.isSpecified) {
        style.fontSize.value * densityScale
    } else {
        SELECTABLE_TEXT_DEFAULT_FONT_SIZE
    }
    val fontWeight = style.fontWeight?.let { weight ->
        when {
            weight.weight >= 700 -> "700"
            weight.weight >= 600 -> "600"
            weight.weight >= 500 -> "500"
            else -> "400"
        }
    } ?: "400"
    // No wire value reliably means "auto" on every consumer (the shared
    // rich-text shadow converts before comparing to its unset sentinel), so
    // unspecified lineHeight resolves to a deterministic default derived from
    // the resolved fontSize.
    val lineHeight = if (style.lineHeight.isSpecified) {
        style.lineHeight.value * densityScale
    } else {
        fontSize * SELECTABLE_TEXT_DEFAULT_LINE_HEIGHT_FACTOR
    }
    // textAlign is non-null in this fork; Unspecified is a sentinel value.
    val textAlign = when (style.textAlign.value) {
        TextAlign.Center.value -> "center"
        TextAlign.Right.value, TextAlign.End.value -> "right"
        else -> "left"
    }
    return SelectableTextStyleProps(
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        lineHeight = lineHeight,
        textAlign = textAlign,
    )
}

internal fun SelectableTextStyleProps.applyTo(attr: SelectableTextAttr) {
    asPropPairs().forEach { (key, value) -> attr.setProp(key, value) }
}
