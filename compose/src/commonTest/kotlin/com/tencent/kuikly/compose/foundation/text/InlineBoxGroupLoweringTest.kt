/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI.
 */

package com.tencent.kuikly.compose.foundation.text

import com.tencent.kuikly.compose.ui.text.AnnotatedString
import com.tencent.kuikly.compose.ui.text.LinkAnnotation
import com.tencent.kuikly.compose.ui.text.SpanStyle
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.withStyle
import com.tencent.kuikly.compose.ui.unit.Density
import com.tencent.kuikly.core.views.RichTextAttr
import com.tencent.kuikly.core.views.TextConst
import com.tencent.kuikly.core.views.TextSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class InlineBoxGroupLoweringTest {

    @Test
    fun stylelessClickablePreservesInheritedFontFamilyAndWeight() {
        val builder = AnnotatedString.Builder()
        builder.withStyle(SpanStyle(fontFamily = FontFamily.SansSerif)) {
            append("body ")
            withStyle(SpanStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)) {
                append("bold")
            }
        }
        val text = builder.toAnnotatedString()
        val clickable = LinkAnnotation.Clickable(tag = "body", linkInteractionListener = {})
        val linked = AnnotatedString.Builder(text.length).apply {
            append(text)
            addLink(clickable, 0, text.length)
        }.toAnnotatedString()

        val attr = RichTextAttr()
        attr.applyAnnotatedString(linked, density = Density(1f))

        val spans = attr.getSpans().map { assertIs<TextSpan>(it) }
        assertEquals("sans-serif", spans[0].spanPropsMap()[TextConst.FONT_FAMILY])
        assertEquals("serif", spans[1].spanPropsMap()[TextConst.FONT_FAMILY])
        assertEquals("700", spans[1].spanPropsMap()[TextConst.FONT_WEIGHT])
    }
}
