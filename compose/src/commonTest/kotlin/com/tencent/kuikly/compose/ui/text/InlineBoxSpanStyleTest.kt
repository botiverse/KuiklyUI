/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.tencent.kuikly.compose.ui.text

import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class InlineBoxSpanStyleTest {

    @Test
    fun mergeCarriesInlineBoxStyleOnExistingSpanStyle() {
        val box = InlineBoxSpanStyle(
            backgroundColor = Color.Yellow,
            borderColor = Color.Black,
            borderWidth = 1.dp,
            paddingStart = 4.dp,
            paddingEnd = 5.dp,
        )

        val merged = SpanStyle(color = Color.Red).merge(SpanStyle(inlineBoxStyle = box))

        assertEquals(Color.Red, merged.color)
        assertEquals(box, merged.inlineBoxStyle)
        assertEquals(box, merged.copy().inlineBoxStyle)
    }
}
