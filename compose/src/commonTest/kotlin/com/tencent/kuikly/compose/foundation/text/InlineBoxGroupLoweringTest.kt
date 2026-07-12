/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI.
 */

package com.tencent.kuikly.compose.foundation.text

import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.text.AnnotatedString
import com.tencent.kuikly.compose.ui.text.InlineBoxSpanStyle
import com.tencent.kuikly.compose.ui.text.LinkAnnotation
import com.tencent.kuikly.compose.ui.text.SpanStyle
import com.tencent.kuikly.compose.ui.text.TextLinkStyles
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.withLink
import com.tencent.kuikly.compose.ui.text.withStyle
import com.tencent.kuikly.compose.ui.unit.Density
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.core.base.Attr
import com.tencent.kuikly.core.views.InlineBoxGroupSpan
import com.tencent.kuikly.core.views.InlineBoxSpanStyle as CoreInlineBoxSpanStyle
import com.tencent.kuikly.core.views.PlaceholderSpan
import com.tencent.kuikly.core.views.RichTextAttr
import com.tencent.kuikly.core.views.TextConst
import com.tencent.kuikly.core.views.TextSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class InlineBoxGroupLoweringTest {

    @Test
    fun inlineBoxLinkStyleStillAppliesChildTypography() {
        val box = InlineBoxSpanStyle(
            backgroundColor = Color.Yellow,
            borderColor = Color.Black,
            borderWidth = 1.dp,
            paddingStart = 4.dp,
            paddingEnd = 4.dp,
        )
        val builder = AnnotatedString.Builder()
        builder.withLink(
            LinkAnnotation.Url(
                url = "https://example.test/channel",
                styles = TextLinkStyles(
                    style = SpanStyle(
                        background = Color.Yellow,
                        fontWeight = FontWeight.Bold,
                        inlineBoxStyle = box,
                    ),
                ),
            ),
        ) {
            append("#channel")
        }

        val attr = RichTextAttr()
        attr.applyAnnotatedString(
            annoText = builder.toAnnotatedString(),
            density = Density(1f),
        )

        val group = assertIs<InlineBoxGroupSpan>(attr.getSpans().single())
        val child = assertIs<TextSpan>(group.childrenForLayout().single())
        assertEquals("#channel", child.getText())
        assertEquals("700", child.spanPropsMap()[TextConst.FONT_WEIGHT])
        assertEquals(null, child.spanPropsMap()[Attr.StyleConst.BACKGROUND_COLOR])
    }

    @Test
    fun linkStyleRangeLowersToOneGroupWithStyledChildren() {
        val box = InlineBoxSpanStyle(
            backgroundColor = Color.Yellow,
            borderColor = Color.Black,
            borderWidth = 1.dp,
            paddingStart = 4.dp,
            paddingEnd = 4.dp,
            paddingTop = 1.dp,
            paddingBottom = 1.dp,
            marginStart = 2.dp,
            marginEnd = 2.dp,
        )
        val builder = AnnotatedString.Builder()
        builder.append("before ")
        builder.withLink(
            LinkAnnotation.Url(
                url = "https://example.test/message",
                styles = TextLinkStyles(style = SpanStyle(inlineBoxStyle = box)),
            )
        ) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append("#proj")
            }
            append(" ")
            withStyle(SpanStyle(color = Color.Gray)) {
                append("msg")
            }
        }

        val attr = RichTextAttr()
        attr.applyAnnotatedString(
            annoText = builder.toAnnotatedString(),
            density = Density(1f),
        )

        val spans = attr.getSpans()
        assertEquals(2, spans.size)
        assertEquals("before ", assertIs<TextSpan>(spans[0]).getText())
        val group = assertIs<InlineBoxGroupSpan>(spans[1])
        val children = group.childrenForLayout()
        assertEquals(3, children.size)
        val label = assertIs<TextSpan>(children[0])
        val suffix = assertIs<TextSpan>(children[2])

        assertEquals("#proj", label.getText())
        assertEquals("700", label.spanPropsMap()[TextConst.FONT_WEIGHT])
        assertEquals("msg", suffix.getText())

        val props = group.spanPropsMap()
        assertEquals("#proj msg", props[InlineBoxGroupSpan.PROP_KEY_SEMANTIC_TEXT])
    }

    @Test
    fun groupSerializationKeepsNestedPlaceholderPathsAndChildTypography() {
        val group = InlineBoxGroupSpan(CoreInlineBoxSpanStyle(borderWidth = 1f)).apply {
            semanticText("#proj msg")
            addChild(PlaceholderSpan().apply { placeholderSize(12f, 12f) })
            addChild(TextSpan().apply {
                text("#proj")
                setProp(TextConst.FONT_SIZE, 14f)
                fontWeightBold()
            })
            addChild(PlaceholderSpan().apply { placeholderSize(6f, 1f) })
            addChild(TextSpan().apply {
                text("msg")
                setProp(TextConst.FONT_SIZE, 10f)
            })
        }

        @Suppress("UNCHECKED_CAST")
        val children = group.spanPropsMap()[InlineBoxGroupSpan.PROP_KEY_CHILDREN] as List<Map<String, Any>>
        assertEquals(12f, children[0][PlaceholderSpan.PROP_KEY_PLACEHOLDER_WIDTH])
        assertEquals(14f, children[1][TextConst.FONT_SIZE])
        assertEquals("700", children[1][TextConst.FONT_WEIGHT])
        assertEquals(6f, children[2][PlaceholderSpan.PROP_KEY_PLACEHOLDER_WIDTH])
        assertEquals(10f, children[3][TextConst.FONT_SIZE])
    }

    @Test
    fun overlappingInlineBoxRangesFailFast() {
        val box = InlineBoxSpanStyle(backgroundColor = Color.Yellow)
        val text = AnnotatedString.Builder("abcdef").apply {
            addStyle(SpanStyle(inlineBoxStyle = box), 0, 4)
            addStyle(SpanStyle(inlineBoxStyle = box), 2, 6)
        }.toAnnotatedString()

        assertFailsWith<IllegalArgumentException> {
            RichTextAttr().applyAnnotatedString(text, density = Density(1f))
        }
    }

    @Test
    fun conflictingInlineBoxStylesOnSameRangeFailFast() {
        val text = AnnotatedString.Builder("chip").apply {
            addStyle(
                SpanStyle(inlineBoxStyle = InlineBoxSpanStyle(backgroundColor = Color.Yellow)),
                0,
                4,
            )
            addStyle(
                SpanStyle(inlineBoxStyle = InlineBoxSpanStyle(backgroundColor = Color.Red)),
                0,
                4,
            )
        }.toAnnotatedString()

        assertFailsWith<IllegalArgumentException> {
            RichTextAttr().applyAnnotatedString(text, density = Density(1f))
        }
    }
}
