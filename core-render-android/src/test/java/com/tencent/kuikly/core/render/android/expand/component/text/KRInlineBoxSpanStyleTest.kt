/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI;
 */

package com.tencent.kuikly.core.render.android.expand.component.text

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class KRInlineBoxSpanStyleTest {

    @Test
    fun absentStyleDoesNotCreateRendererDecoration() {
        assertNull(KRInlineBoxSpanStyle.from(JSONObject(), null))
    }

    @Test
    fun rendererReadsStyleValuesWithoutSemanticKind() {
        val value = JSONObject()
            .put("inlineBoxBorderWidth", 0)
            .put("inlineBoxPaddingStart", 0)
            .put("inlineBoxPaddingEnd", 0)

        val style = KRInlineBoxSpanStyle.from(value, null)

        assertNotNull(style)
        assertEquals(0f, style!!.borderWidth)
        assertEquals(0f, style.paddingStart)
        assertEquals(0f, style.paddingEnd)
    }
}
