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

    @Test
    fun singleTextChildUsesAtomicInlineBoxLayout() {
        assertEquals(
            true,
            shouldAppendInlineBoxGroupAtomically(
                childCount = 1,
                onlyChildIsText = true,
                onlyChildAdjustsNewline = false,
            ),
        )
        assertEquals(
            false,
            shouldAppendInlineBoxGroupAtomically(
                childCount = 2,
                onlyChildIsText = true,
                onlyChildAdjustsNewline = false,
            ),
        )
        assertEquals(
            false,
            shouldAppendInlineBoxGroupAtomically(
                childCount = 1,
                onlyChildIsText = false,
                onlyChildAdjustsNewline = false,
            ),
        )
        assertEquals(
            false,
            shouldAppendInlineBoxGroupAtomically(
                childCount = 1,
                onlyChildIsText = true,
                onlyChildAdjustsNewline = true,
            ),
        )
    }

    @Test
    fun atomicInlineBoxHitKeepsClickableIndexAcrossBothHalves() {
        val precedingNormalSpanIndex = 0
        val chip = KRInlineBoxAtomicHitRange(line = 1, left = 100f, right = 200f, spanIndex = 1)
        val followingNormalSpanIndex = 2

        assertEquals(
            1,
            resolveKRInlineBoxBoundaryHit(
                touchedLine = chip.line,
                touchX = 110f,
                ranges = listOf(chip),
                fallbackSpanIndices = listOf(precedingNormalSpanIndex, chip.spanIndex),
            ),
        )
        assertEquals(
            precedingNormalSpanIndex,
            resolveKRInlineBoxBoundaryHit(
                touchedLine = chip.line,
                touchX = 90f,
                ranges = listOf(chip),
                fallbackSpanIndices = listOf(precedingNormalSpanIndex, chip.spanIndex),
            ),
        )
        assertEquals(
            1,
            resolveKRInlineBoxBoundaryHit(
                touchedLine = chip.line,
                touchX = 190f,
                ranges = listOf(chip),
                fallbackSpanIndices = listOf(chip.spanIndex, followingNormalSpanIndex),
            ),
        )
        assertEquals(
            followingNormalSpanIndex,
            resolveKRInlineBoxBoundaryHit(
                touchedLine = chip.line,
                touchX = 210f,
                ranges = listOf(chip),
                fallbackSpanIndices = listOf(chip.spanIndex, followingNormalSpanIndex),
            ),
        )
    }

    @Test
    fun wrappedAtomicInlineBoxDoesNotStealPreviousLineBoundary() {
        val precedingNormalSpanIndex = 0
        val chip = KRInlineBoxAtomicHitRange(line = 1, left = 0f, right = 100f, spanIndex = 1)

        assertEquals(
            precedingNormalSpanIndex,
            resolveKRInlineBoxBoundaryHit(
                touchedLine = 0,
                touchX = 300f,
                ranges = listOf(chip),
                fallbackSpanIndices = listOf(precedingNormalSpanIndex, chip.spanIndex),
            ),
        )
    }

    @Test
    fun adjacentAtomicInlineBoxesResolveTheTouchedSideOfSharedBoundary() {
        val left = KRInlineBoxAtomicHitRange(line = 0, left = 0f, right = 100f, spanIndex = 0)
        val right = KRInlineBoxAtomicHitRange(line = 0, left = 100f, right = 200f, spanIndex = 1)

        assertEquals(
            0,
            resolveKRInlineBoxBoundaryHit(
                touchedLine = 0,
                touchX = 95f,
                ranges = listOf(left, right),
                fallbackSpanIndices = listOf(left.spanIndex, right.spanIndex),
            ),
        )
        assertEquals(
            1,
            resolveKRInlineBoxBoundaryHit(
                touchedLine = 0,
                touchX = 105f,
                ranges = listOf(left, right),
                fallbackSpanIndices = listOf(left.spanIndex, right.spanIndex),
            ),
        )
    }
}
