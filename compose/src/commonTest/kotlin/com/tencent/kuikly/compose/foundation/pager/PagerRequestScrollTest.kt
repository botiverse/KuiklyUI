/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI.
 */

package com.tencent.kuikly.compose.foundation.pager

import com.tencent.kuikly.compose.foundation.gestures.Orientation
import com.tencent.kuikly.compose.gestures.ScrollOffsetWriteCapabilityKind
import com.tencent.kuikly.compose.foundation.gestures.snapping.SnapPosition
import com.tencent.kuikly.compose.scroller.ScrollOffsetWriteIntent
import com.tencent.kuikly.compose.scroller.applyScrollViewContentOffset
import com.tencent.kuikly.compose.scroller.kuiklyInfo
import com.tencent.kuikly.compose.ui.layout.AlignmentLine
import com.tencent.kuikly.compose.ui.layout.MeasureResult
import com.tencent.kuikly.compose.ui.unit.IntOffset
import com.tencent.kuikly.compose.ui.unit.LayoutDirection
import com.tencent.kuikly.core.views.ScrollerAttr
import com.tencent.kuikly.core.views.ScrollerEvent
import com.tencent.kuikly.core.views.ScrollerView
import com.tencent.kuikly.core.views.ScrollOffsetCommitToken
import com.tencent.kuikly.core.views.ScrollWriteResult
import com.tencent.kuikly.core.views.ScrollWriteResultCode
import com.tencent.kuikly.core.views.SpringAnimation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PagerRequestScrollTest {

    @Test
    fun requestScrollToPageAcquiresMutationAuthority() {
        val state = PagerState(pageCount = { 3 })
        val page = MeasuredPage(
            index = 0,
            size = 100,
            placeables = emptyList(),
            visualOffset = IntOffset.Zero,
            key = 0,
            orientation = Orientation.Horizontal,
            horizontalAlignment = null,
            verticalAlignment = null,
            layoutDirection = LayoutDirection.Ltr,
            reverseLayout = false,
        ).also { it.position(offset = 0, layoutWidth = 100, layoutHeight = 100) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val result = PagerMeasureResult(
                visiblePagesInfo = listOf(page),
                pageSize = 100,
                pageSpacing = 0,
                afterContentPadding = 0,
                orientation = Orientation.Horizontal,
                viewportStartOffset = 0,
                viewportEndOffset = 100,
                positionedPages = listOf(page),
                reverseLayout = false,
                beyondViewportPageCount = 0,
                firstVisiblePage = page,
                currentPage = page,
                currentPageOffsetFraction = 0f,
                firstVisiblePageScrollOffset = 0,
                canScrollForward = true,
                snapPosition = SnapPosition.Start,
                measureResult = object : MeasureResult {
                    override val width: Int = 100
                    override val height: Int = 100
                    override val alignmentLines: Map<AlignmentLine, Int> = emptyMap()
                    override fun placeChildren() {}
                },
                remeasureNeeded = false,
                coroutineScope = scope,
            )
            state.applyMeasureResult(result)
            state.kuiklyInfo.bindScrollView(ScrollerView<ScrollerAttr, ScrollerEvent>())
            state.kuiklyInfo.offsetDirty = true
            val before = state.numMeasurePasses

            state.requestScrollToPage(2)

            assertTrue(state.numMeasurePasses > before)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun delayedAlignmentCannotMutateNewerSnapClaim() = runBlocking {
        val state = PagerState(pageCount = { 3 })
        state.kuiklyInfo.bindScrollView(ScrollerView<ScrollerAttr, ScrollerEvent>())
        state.kuiklyInfo.scope = this
        try {
            val firstCapability = checkNotNull(
                state.kuiklyInfo.beginScrollOffsetWriteCapability(
                    ScrollOffsetWriteCapabilityKind.GestureSnap,
                ),
            )
            val firstClaim = checkNotNull(
                state.kuiklyInfo.claimScrollOffsetWriteCapability(firstCapability),
            )
            state.kuiklyInfo.endScrollOffsetWriteCapability(firstCapability)
            state.markSnapAnimationStarted(
                targetContentOffset = 0,
                targetPage = 0,
                capabilityClaim = firstClaim,
            )
            state.onNativeContentOffsetChanged(0)

            val secondCapability = checkNotNull(
                state.kuiklyInfo.beginScrollOffsetWriteCapability(
                    ScrollOffsetWriteCapabilityKind.GestureSnap,
                ),
            )
            val secondClaim = checkNotNull(
                state.kuiklyInfo.claimScrollOffsetWriteCapability(secondCapability),
            )
            state.markSnapAnimationStarted(
                targetContentOffset = 200,
                targetPage = 2,
                capabilityClaim = secondClaim,
            )
            state.kuiklyInfo.endScrollOffsetWriteCapability(secondCapability)

            delay(75L)

            assertTrue(state.isSnapAnimating)
            assertEquals(200, state.snapTargetContentOffset)
            assertFalse(state.kuiklyInfo.appleScrollViewOffsetJob?.isActive == true)
        } finally {
            state.clearSnapAnimationState()
            state.kuiklyInfo.scope = null
        }
    }

    @Test
    fun delayedAlignmentWithoutClaimCannotMutateNewerSnapClaim() = runBlocking {
        val state = PagerState(pageCount = { 3 })
        state.kuiklyInfo.bindScrollView(ScrollerView<ScrollerAttr, ScrollerEvent>())
        state.kuiklyInfo.scope = this
        val page = MeasuredPage(
            index = 0,
            size = 100,
            placeables = emptyList(),
            visualOffset = IntOffset.Zero,
            key = 0,
            orientation = Orientation.Horizontal,
            horizontalAlignment = null,
            verticalAlignment = null,
            layoutDirection = LayoutDirection.Ltr,
            reverseLayout = false,
        ).also { it.position(offset = 0, layoutWidth = 100, layoutHeight = 100) }
        try {
            state.applyMeasureResult(
                PagerMeasureResult(
                    visiblePagesInfo = listOf(page),
                    pageSize = 100,
                    pageSpacing = 0,
                    afterContentPadding = 0,
                    orientation = Orientation.Horizontal,
                    viewportStartOffset = 0,
                    viewportEndOffset = 100,
                    positionedPages = listOf(page),
                    reverseLayout = false,
                    beyondViewportPageCount = 0,
                    firstVisiblePage = page,
                    currentPage = page,
                    currentPageOffsetFraction = 0f,
                    firstVisiblePageScrollOffset = 0,
                    canScrollForward = true,
                    snapPosition = SnapPosition.Start,
                    measureResult = object : MeasureResult {
                        override val width: Int = 100
                        override val height: Int = 100
                        override val alignmentLines: Map<AlignmentLine, Int> = emptyMap()
                        override fun placeChildren() {}
                    },
                    remeasureNeeded = false,
                    coroutineScope = this,
                ),
            )
            val capability = checkNotNull(
                state.kuiklyInfo.beginScrollOffsetWriteCapability(
                    ScrollOffsetWriteCapabilityKind.GestureSnap,
                ),
            )
            val claim = checkNotNull(
                state.kuiklyInfo.claimScrollOffsetWriteCapability(capability),
            )
            state.markSnapAnimationStarted(
                targetContentOffset = 200,
                targetPage = 2,
                capabilityClaim = claim,
            )
            state.kuiklyInfo.endScrollOffsetWriteCapability(capability)

            delay(75L)

            assertTrue(state.isSnapAnimating)
            assertEquals(200, state.snapTargetContentOffset)
            assertFalse(state.kuiklyInfo.appleScrollViewOffsetJob?.isActive == true)
        } finally {
            state.clearSnapAnimationState()
            state.kuiklyInfo.scope = null
        }
    }

    @Test
    fun requestCurrentPageTerminalizesStateHeldRevisionWaitExactlyOnce() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val view = ResultScrollerView(ScrollWriteResultCode.NotReady)
        val state = measuredPagerState(scope, view)
        try {
            val terminals = beginStateHeldSnapWrite(state, view)

            state.requestScrollToPage(state.currentPage, 0f)

            assertEquals(listOf(false), terminals)
            view.deliverLateRevision()
            assertEquals(listOf(false), terminals)
            assertEquals(1, view.callCount)
        } finally {
            state.clearSnapAnimationState()
            scope.cancel()
        }
    }

    @Test
    fun requestCurrentPageTerminalizesStateHeldInteractionWaitExactlyOnce() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val view = ResultScrollerView(ScrollWriteResultCode.Busy)
        val state = measuredPagerState(scope, view)
        try {
            val terminals = beginStateHeldSnapWrite(state, view)

            state.requestScrollToPage(state.currentPage, 0f)

            assertEquals(listOf(false), terminals)
            assertEquals(0, state.kuiklyInfo.deferredScrollOffsetAlignmentCoordinator.retryAfterScrollEnd())
            assertEquals(listOf(false), terminals)
            assertEquals(1, view.callCount)
        } finally {
            state.clearSnapAnimationState()
            scope.cancel()
        }
    }

    private fun measuredPagerState(
        scope: CoroutineScope,
        view: ScrollerView<ScrollerAttr, ScrollerEvent>,
    ): PagerState {
        val state = PagerState(pageCount = { 3 })
        val page = measuredPage()
        state.applyMeasureResult(
            PagerMeasureResult(
                visiblePagesInfo = listOf(page),
                pageSize = 100,
                pageSpacing = 0,
                afterContentPadding = 0,
                orientation = Orientation.Horizontal,
                viewportStartOffset = 0,
                viewportEndOffset = 100,
                positionedPages = listOf(page),
                reverseLayout = false,
                beyondViewportPageCount = 0,
                firstVisiblePage = page,
                currentPage = page,
                currentPageOffsetFraction = 0f,
                firstVisiblePageScrollOffset = 0,
                canScrollForward = true,
                snapPosition = SnapPosition.Start,
                measureResult = object : MeasureResult {
                    override val width: Int = 100
                    override val height: Int = 100
                    override val alignmentLines: Map<AlignmentLine, Int> = emptyMap()
                    override fun placeChildren() {}
                },
                remeasureNeeded = false,
                coroutineScope = scope,
            ),
        )
        state.kuiklyInfo.bindScrollView(view)
        return state
    }

    private fun measuredPage() = MeasuredPage(
        index = 0,
        size = 100,
        placeables = emptyList(),
        visualOffset = IntOffset.Zero,
        key = 0,
        orientation = Orientation.Horizontal,
        horizontalAlignment = null,
        verticalAlignment = null,
        layoutDirection = LayoutDirection.Ltr,
        reverseLayout = false,
    ).also { it.position(offset = 0, layoutWidth = 100, layoutHeight = 100) }

    private fun beginStateHeldSnapWrite(
        state: PagerState,
        view: ResultScrollerView,
    ): MutableList<Boolean> {
        val owner = checkNotNull(state.kuiklyInfo.captureScrollOffsetOwnerToken())
        val capability = checkNotNull(
            state.kuiklyInfo.beginScrollOffsetWriteCapability(
                ScrollOffsetWriteCapabilityKind.GestureSnap,
            ),
        )
        val claim = checkNotNull(state.kuiklyInfo.claimScrollOffsetWriteCapability(capability))
        state.markSnapAnimationStarted(
            targetContentOffset = 0,
            targetPage = 0,
            capabilityClaim = claim,
        )
        val terminals = mutableListOf<Boolean>()
        assertTrue(
            state.kuiklyInfo.applyScrollViewContentOffset(
                ownerToken = owner,
                offsetX = 10f,
                offsetY = 0f,
                animated = false,
                intent = ScrollOffsetWriteIntent.GestureSnap,
                reason = "pager_state_held_replacement_test",
                capabilityClaim = claim,
                anchorValidator = { true },
                onCommitResult = terminals::add,
            ),
        )
        state.kuiklyInfo.endScrollOffsetWriteCapability(capability)
        view.performRenderViewLazyTasks()
        assertEquals(1, view.callCount)
        assertTrue(terminals.isEmpty())
        return terminals
    }

    private class ResultScrollerView(
        private val code: ScrollWriteResultCode,
    ) : ScrollerView<ScrollerAttr, ScrollerEvent>() {
        var callCount = 0
        private var lastCallback: ((ScrollWriteResult) -> Unit)? = null

        override fun callContentOffset(
            offsetX: Float,
            offsetY: Float,
            animated: Boolean,
            springAnimation: SpringAnimation?,
            writeToken: ScrollOffsetCommitToken?,
            onCommitResult: ((ScrollWriteResult) -> Unit)?,
        ) {
            callCount += 1
            lastCallback = onCommitResult
            onCommitResult?.invoke(result(code, nativeLayoutRevision))
        }

        fun deliverLateRevision() {
            lastCallback?.invoke(result(code, nativeLayoutRevision + 1L))
        }

        private fun result(code: ScrollWriteResultCode, layoutRevision: Long) = ScrollWriteResult(
            code = code,
            nativeInteractionEpoch = nativeInteractionEpoch,
            layoutRevision = layoutRevision,
            insetRevision = nativeInsetRevision,
        )
    }
}
