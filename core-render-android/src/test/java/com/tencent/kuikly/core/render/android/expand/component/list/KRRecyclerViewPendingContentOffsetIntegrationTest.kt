/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI.
 */

package com.tencent.kuikly.core.render.android.expand.component.list

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Production-path regression for task #990.
 *
 * This intentionally enters through [KRRecyclerView.call], lets the real [KRRecyclerView.onLayout]
 * retry while the native range is short, then grows the real content child and lays the RecyclerView
 * out again. A helper-only test cannot prove that those production entry points are wired to the
 * pending owner; deleting either the install or retry wiring must fail this test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class KRRecyclerViewPendingContentOffsetIntegrationTest {

    @Test
    fun rangeDeferredPublicWriteSurvivesLayoutAndPhysicallyAppliesWhenRangeGrows() {
        val (recyclerView, contentView) = createRecycler(INITIAL_CONTENT_HEIGHT_PX)

        recyclerView.call(
            "contentOffset",
            "0 $REQUESTED_OFFSET_PX 0",
            null,
        )
        assertNotNull(recyclerView.pendingOwner().pending)
        val installed = requireNotNull(recyclerView.pendingOwner().pending)
        assertEquals("0 $REQUESTED_OFFSET_PX 0", installed.value)
        assertEquals(0, -contentView.top)

        // The first production onLayout still has only 85px of physical range. The requested
        // 500px write must remain owned instead of being erased by clear-after-retry.
        layoutRecycler(recyclerView)
        assertNotNull(recyclerView.pendingOwner().pending)
        val afterShortLayout = requireNotNull(recyclerView.pendingOwner().pending)
        assertEquals(installed.generation, afterShortLayout.generation)
        assertEquals(0, -contentView.top)

        // A later row grows the native range. Drive another real onLayout: this is the exact hook
        // that retries pending contentOffset in production, and the physical child must move.
        contentView.layoutParams.height = READY_CONTENT_HEIGHT_PX
        layoutContent(contentView, READY_CONTENT_HEIGHT_PX)
        layoutRecycler(recyclerView)

        assertEquals(READY_CONTENT_HEIGHT_PX, contentView.height)
        assertEquals(REQUESTED_OFFSET_PX, -contentView.top)
        assertNull(recyclerView.pendingOwner().pending)
    }

    private fun createRecycler(
        contentHeightPx: Int,
    ): Pair<KRRecyclerView, KRRecyclerContentView> {
        val recyclerView = KRRecyclerView(RuntimeEnvironment.getApplication())
        val contentView = KRRecyclerContentView(RuntimeEnvironment.getApplication()).apply {
            layoutParams = RecyclerView.LayoutParams(VIEWPORT_WIDTH_PX, contentHeightPx)
        }
        recyclerView.addView(contentView)
        layoutRecycler(recyclerView)
        return recyclerView to contentView
    }

    private fun layoutRecycler(recyclerView: KRRecyclerView) {
        recyclerView.requestLayout()
        recyclerView.measure(
            View.MeasureSpec.makeMeasureSpec(VIEWPORT_WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(VIEWPORT_HEIGHT_PX, View.MeasureSpec.EXACTLY),
        )
        recyclerView.layout(0, 0, VIEWPORT_WIDTH_PX, VIEWPORT_HEIGHT_PX)
    }

    private fun layoutContent(contentView: KRRecyclerContentView, contentHeightPx: Int) {
        val currentLeft = contentView.left
        val currentTop = contentView.top
        contentView.measure(
            View.MeasureSpec.makeMeasureSpec(VIEWPORT_WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(contentHeightPx, View.MeasureSpec.EXACTLY),
        )
        contentView.layout(
            currentLeft,
            currentTop,
            currentLeft + VIEWPORT_WIDTH_PX,
            currentTop + contentHeightPx,
        )
    }

    private fun KRRecyclerView.pendingOwner(): KRPendingContentOffsetOwner =
        javaClass.getDeclaredField("pendingContentOffsetOwner").let { field ->
            field.isAccessible = true
            field.get(this) as KRPendingContentOffsetOwner
        }

    private companion object {
        const val VIEWPORT_WIDTH_PX = 300
        const val VIEWPORT_HEIGHT_PX = 315
        const val INITIAL_CONTENT_HEIGHT_PX = 400
        const val READY_CONTENT_HEIGHT_PX = 1_200
        const val REQUESTED_OFFSET_PX = 500
    }
}
