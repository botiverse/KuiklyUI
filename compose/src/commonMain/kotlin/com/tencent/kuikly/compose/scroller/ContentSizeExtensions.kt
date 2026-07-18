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

package com.tencent.kuikly.compose.scroller

import com.tencent.kuikly.compose.foundation.ScrollState
import com.tencent.kuikly.compose.foundation.gestures.Orientation
import com.tencent.kuikly.compose.foundation.gestures.ScrollableState
import com.tencent.kuikly.compose.foundation.layout.PaddingValues
import com.tencent.kuikly.compose.foundation.lazy.LazyListState
import com.tencent.kuikly.compose.foundation.lazy.grid.LazyGridState
import com.tencent.kuikly.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import com.tencent.kuikly.compose.foundation.drawer.DrawerInternalPagerState
import com.tencent.kuikly.compose.foundation.pager.PagerState
import com.tencent.kuikly.compose.foundation.pager.ScrollViewOffsetAlignmentCancellation
import com.tencent.kuikly.compose.gestures.DeferredScrollOffsetAlignmentCoordinator
import com.tencent.kuikly.compose.scroller.ScrollableStateConstants.DEFAULT_CONTENT_SIZE
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.LayoutDirection
import com.tencent.kuikly.compose.ui.util.fastSumBy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Calculate content size
 */
internal fun ScrollableState.calculateContentSize(): Int {
    kuiklyInfo.realContentSize = null
    val density = kuiklyInfo.getDensity()
    val minSize = (ScrollableStateConstants.DEFAULT_CONTENT_SIZE * density).toInt()

    val scrollView = kuiklyInfo.scrollView ?: return minSize

    val contentSize = if (kuiklyInfo.orientation == Orientation.Vertical) {
        scrollView.contentView?.renderView?.currentFrame?.height ?: ScrollableStateConstants.DEFAULT_CONTENT_SIZE.toFloat()
    } else {
        scrollView.contentView?.renderView?.currentFrame?.width ?: ScrollableStateConstants.DEFAULT_CONTENT_SIZE.toFloat()
    } * density

    val viewportSize = kuiklyInfo.viewportSize

    // Return exact content height if total compose container height can be calculated
    val realContentSize = totalContentSize()
    if (realContentSize != null) {
        // Compensate for Modifier padding shrinking Compose internal viewport relative to native viewport
        val composeViewport = composeViewportMainAxisSize() ?: viewportSize
        val viewportDelta = viewportSize - composeViewport
        // Compensate for contentPadding which does not affect viewportSize but is excluded from totalContentSize
        val contentPaddingCompensation = (contentPadding.totalPadding(kuiklyInfo.orientation).value * density).roundToInt()
        kuiklyInfo.realContentSize = realContentSize + viewportDelta + contentPaddingCompensation
        return kuiklyInfo.realContentSize!!
    }

    val bottomOffset = kuiklyInfo.composeOffset.toInt() + viewportSize
    // Expand buffer if bottom offset is close to content size
    if (contentSize - bottomOffset < ScrollableStateConstants.CONTENT_SIZE_BUFFER * density) {
        return (contentSize + ScrollableStateConstants.DEFAULT_EXPAND_SIZE* density).toInt()
    }

    return contentSize.toInt()
}

internal fun ScrollableState.calculateAndUpdateContentSize() {
    // 更新当前的contentSize大小
    val oldContentSize = kuiklyInfo.currentContentSize
    val newContentSize = calculateContentSize()

    // 如果contentSize变小了，需要确保composeOffset不会超出边界
    if (newContentSize < oldContentSize) {
        val newMaxScrollOffset = maxOf(0, newContentSize - kuiklyInfo.viewportSize)
        if (kuiklyInfo.composeOffset > newMaxScrollOffset) {
            // 如果composeOffset超出新的边界，增加contentSize来保持composeOffset不变
            val requiredContentSize = kuiklyInfo.composeOffset.toInt() + kuiklyInfo.viewportSize
            kuiklyInfo.currentContentSize = maxOf(newContentSize, requiredContentSize)
        } else {
            kuiklyInfo.currentContentSize = newContentSize
        }
    } else {
        kuiklyInfo.currentContentSize = newContentSize
    }
    if (oldContentSize != kuiklyInfo.currentContentSize) {
        logScrollDiagnostic(
            "content_size_update",
            "old=$oldContentSize calculated=$newContentSize committed=${kuiklyInfo.currentContentSize}"
        )
    }
    kuiklyInfo.updateContentSizeToRender()
}

internal fun PaddingValues.totalPadding(orientation: Orientation): Dp {
    return if (orientation == Orientation.Vertical) {
        calculateTopPadding() + calculateBottomPadding()
    } else {
        val layoutDirection = LayoutDirection.Ltr
        calculateLeftPadding(layoutDirection) + calculateRightPadding(layoutDirection)
    }
}

/**
 * Calculate total content size
 */
internal fun ScrollableState.totalContentSize(): Int? {
    val curOffset = kuiklyInfo.composeOffset
    return when(this) {
        is LazyListState -> calculateLazyListContentSize(curOffset)
        is PagerState -> calculatePagerContentSize(curOffset)
        is DrawerInternalPagerState -> calculateDynamicPagerContentSize(curOffset)
        is LazyGridState -> calculateLazyGridContentSize(curOffset)
        is LazyStaggeredGridState -> calculateLazyStaggeredGridContentSize(curOffset)
        is ScrollState -> calculateScrollStateContentSize()
        else -> null
    }
}

/**
 * Get the main axis size (in pixels) of the Compose internal viewport.
 * Modifier padding shrinks the Compose internal viewport, causing a delta with the native ScrollView viewport.
 */
private fun ScrollableState.composeViewportMainAxisSize(): Int? {
    return when(this) {
        is LazyListState -> {
            if (layoutInfo.orientation == Orientation.Vertical) layoutInfo.viewportSize.height
            else layoutInfo.viewportSize.width
        }
        is LazyGridState -> {
            if (layoutInfo.orientation == Orientation.Vertical) layoutInfo.viewportSize.height
            else layoutInfo.viewportSize.width
        }
        is LazyStaggeredGridState -> {
            if (layoutInfo.orientation == Orientation.Vertical) layoutInfo.viewportSize.height
            else layoutInfo.viewportSize.width
        }
        is ScrollState -> viewportSize
        else -> null
    }
}

private fun LazyListState.calculateLazyListContentSize(curOffset: Float): Int? {
    val lastItem = layoutInfo.visibleItemsInfo.lastOrNull()
    return if (lastItem != null && lastItem.index == layoutInfo.totalItemsCount - 1) {
        (curOffset + lastItem.offset + lastItem.size).toInt()
    } else null
}

private fun PagerState.calculatePagerContentSize(curOffset: Float): Int? {
    val lastItem = layoutInfo.visiblePagesInfo.lastOrNull()
    return if (lastItem != null && lastItem.index == pageCount - 1) {
        (curOffset + lastItem.offset + pageSize).toInt()
    } else null
}

private fun DrawerInternalPagerState.calculateDynamicPagerContentSize(curOffset: Float): Int? {
    val lastItem = layoutInfo.visiblePagesInfo.lastOrNull()
    return if (lastItem != null && lastItem.index == pageCount - 1) {
        val lastPageSize = pageSizeForPage(lastItem.index)
        (curOffset + lastItem.offset + lastPageSize).toInt()
    } else null
}

private fun LazyGridState.calculateLazyGridContentSize(curOffset: Float): Int? {
    val lastItem = layoutInfo.visibleItemsInfo.lastOrNull()
    return if (lastItem != null && lastItem.index == layoutInfo.totalItemsCount - 1) {
        if (layoutInfo.orientation == Orientation.Vertical) {
            (curOffset + lastItem.offset.y + lastItem.size.height).toInt()
        } else {
            (curOffset + lastItem.offset.x + lastItem.size.width).toInt()
        }
    } else null
}

private fun LazyStaggeredGridState.calculateLazyStaggeredGridContentSize(curOffset: Float): Int? {
    val lastItem = layoutInfo.visibleItemsInfo.lastOrNull()
    return if (lastItem != null && lastItem.index == layoutInfo.totalItemsCount - 1) {
        if (layoutInfo.orientation == Orientation.Vertical) {
            (curOffset + lastItem.offset.y + lastItem.size.height).toInt()
        } else {
            (curOffset + lastItem.offset.x + lastItem.size.width).toInt()
        }
    } else null
}

private fun ScrollState.calculateScrollStateContentSize(): Int? {
    return if (maxValue != Int.MAX_VALUE) {
        maxValue + viewportSize
    } else null
}

private const val PULL_TO_REFRESH_ITEM_KEY = "pull_to_refresh"

/**
 * Whether Compose is at top for scroll-sync correction.
 * Only differs from [isAtTop] when PTR [KuiklyScrollInfo.pullToRefreshTopInsetPx] > 0.
 */
private fun ScrollableState.isComposeAtTopForScrollSync(): Boolean {
    if (this is LazyListState && kuiklyInfo.pullToRefreshTopInsetPx > 0) {
        return firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0
    }
    return isAtTop()
}

/**
 * Estimate Compose scroll offset when PTR item is taller than average (topInset case).
 */
private fun LazyListState.estimateComposeScrollOffset(avgItemSize: Int): Int {
    val index = firstVisibleItemIndex
    if (index <= 0) return firstVisibleItemScrollOffset

    val spacing = layoutInfo.mainAxisItemSpacing
    var sum = 0
    for (i in 0 until index) {
        sum += itemMainAxisSizeAt(i, avgItemSize)
        if (i < index - 1) {
            sum += spacing
        }
    }
    return sum + firstVisibleItemScrollOffset
}

private fun LazyListState.itemMainAxisSizeAt(index: Int, avgItemSize: Int): Int {
    layoutInfo.visibleItemsInfo.find { it.index == index }?.size?.let { return it }
    if (kuiklyInfo.pullToRefreshTopInsetPx > 0 && index == 0) {
        kuiklyInfo.itemMainSpaceCache[PULL_TO_REFRESH_ITEM_KEY]?.let { return it }
    }
    return avgItemSize
}

/**
 * Calculate back expansion size
 */
internal fun ScrollableState.calculateBackExpandSize(offset: Int): Int? {
    if (this !is LazyListState) return null

    val visibleItems = layoutInfo.visibleItemsInfo
    if (visibleItems.isEmpty()) return null

    val density = kuiklyInfo.getDensity()
    val estimateOffset = if (kuiklyInfo.pullToRefreshTopInsetPx > 0) {
        val itemsSum = visibleItems.fastSumBy { it.size }
        val avgItemSize = itemsSum / visibleItems.size
        estimateComposeScrollOffset(avgItemSize)
    } else {
        val itemsSum = visibleItems.fastSumBy { it.size }
        val avgSize = itemsSum / visibleItems.size + layoutInfo.mainAxisItemSpacing
        val firstItem = visibleItems.firstOrNull() ?: return null
        val pullToRefreshOffset = if (kuiklyInfo.hasPullToRefresh) 1 else 0
        val adjustedFirstItemIndex = maxOf(0, firstItem.index - pullToRefreshOffset)
        adjustedFirstItemIndex * avgSize - firstItem.offset
    }

    return if (estimateOffset - offset > ScrollableStateConstants.SCROLL_THRESHOLD * density) {
        estimateOffset - offset + (ScrollableStateConstants.MIN_EXPAND_SIZE * density).toInt()
    } else null
}

/**
 * 尝试扩展起始大小
 */
internal fun ScrollableState.tryExpandStartSize(offset: Int, isScrolling: Boolean) {
    if (kuiklyInfo.scrollView == null) return
    if (kuiklyInfo.skipExpandStartSize) return
    if (this is PagerState) return

    val atTopSync = isComposeAtTopForScrollSync()
    val needsTopExpand = offset <= 0 && !atTopSync && kuiklyInfo.offsetDirty
    val needsScrollViewPullBack = offset > 0 && atTopSync
    if (!needsTopExpand && !needsScrollViewPullBack) {
        return
    }

    val ownerToken = kuiklyInfo.captureScrollOffsetOwnerToken() ?: return
    val immediateWriteContext = ScrollOffsetWriteContext(
        intent = ScrollOffsetWriteIntent.NonForcedAlignment,
        isComposeScrolling = isScrolling || isScrollInProgress,
        nativeScrollPhase = ownerToken.scrollView.nativeScrollPhase,
        isCurrentOwnerToken = kuiklyInfo.isCurrentScrollOffsetOwner(ownerToken),
        isAnchorValid = needsTopExpand || needsScrollViewPullBack,
    )
    if (!shouldApplyScrollOffsetWrite(immediateWriteContext)) {
        val coordinator = kuiklyInfo.deferredScrollOffsetAlignmentCoordinator
        coordinator.requestRetryAfterScrollEnd(
            key = StartAlignmentRetryOperation,
            interactionEpoch = ownerToken.scrollView.nativeInteractionEpoch,
        ) { retryRequest ->
            if (coordinator.isCurrent(retryRequest)) {
                this@tryExpandStartSize.tryExpandStartSizeNoScroll()
            }
        }
        logScrollDiagnostic(
            "immediate_alignment_skipped",
            "nativePhase=${ownerToken.scrollView.nativeScrollPhase}"
        )
        return
    }

    val density = kuiklyInfo.getDensity()
    // scrollview 到顶了，但是compose没到顶
    if (needsTopExpand) {
        var delta = calculateBackExpandSize(offset)
        val minDelta = (ScrollableStateConstants.DEFAULT_CONTENT_SIZE * density).toInt()
        delta = max(delta ?: minDelta, minDelta)

        applyScrollViewOffsetDelta(
                delta,
                ownerToken = ownerToken,
                intent = ScrollOffsetWriteIntent.NonForcedAlignment,
                reason = "expand_start_backfill",
                anchorValidator = {
                    kuiklyInfo.contentOffset <= 0 &&
                        !isComposeAtTopForScrollSync() &&
                        kuiklyInfo.offsetDirty
                },
                onCommitted = { kuiklyInfo.offsetDirty = true },
            )
    } else if (offset > 0 && isComposeAtTopForScrollSync()) {
        // compose 到顶了，但是scrollview没到顶
        applyScrollViewOffsetDelta(
                -offset,
                ownerToken = ownerToken,
                intent = ScrollOffsetWriteIntent.NonForcedAlignment,
                reason = "expand_start_pullback",
                anchorValidator = {
                    kuiklyInfo.contentOffset > 0 && isComposeAtTopForScrollSync()
                },
                onCommitted = { kuiklyInfo.offsetDirty = false },
            )
    }
}

internal fun ScrollableState.tryExpandStartSizeNoScroll(forceExpand: Boolean = false) {
    if (this is PagerState || this is DrawerInternalPagerState) return
    val ownerToken = kuiklyInfo.captureScrollOffsetOwnerToken() ?: return
    logScrollDiagnostic("alignment_schedule", "force=$forceExpand")
    val scrollInProgress = {
        val inProgress = this@tryExpandStartSizeNoScroll.isScrollInProgress
        logScrollDiagnostic("alignment_progress_read", "force=$forceExpand value=$inProgress")
        inProgress
    }
    kuiklyInfo.run {
        scheduleDeferredScrollOffsetAlignment(
            coordinator = deferredScrollOffsetAlignmentCoordinator,
            contextProvider = {
                ScrollOffsetWriteContext(
                    intent = ScrollOffsetWriteIntent.NonForcedAlignment,
                    isComposeScrolling = scrollInProgress(),
                    nativeScrollPhase = ownerToken.scrollView.nativeScrollPhase,
                    isCurrentOwnerToken = isCurrentScrollOffsetOwner(ownerToken),
                    isAnchorValid = viewportSize > 0,
                )
            },
            cancelPendingAlignment = { it.cancel(ScrollViewOffsetAlignmentCancellation) },
            launchAlignment = { alignment -> scope?.launch { alignment() } },
            awaitAlignmentWindow = { delay(150) },
            retryAfterScrollEnd = {
                this@tryExpandStartSizeNoScroll.tryExpandStartSizeNoScroll()
            },
            applyAlignment = applyAlignment@{ isCurrent, requestRetry ->
                logScrollDiagnostic("alignment_apply_enter", "force=$forceExpand current=${isCurrent()}")
                val intent = ScrollOffsetWriteIntent.NonForcedAlignment
                val currentOwner = isCurrent() && isCurrentScrollOffsetOwner(ownerToken)
                val writeContext = ScrollOffsetWriteContext(
                    intent = intent,
                    isComposeScrolling = isScrollInProgress,
                    nativeScrollPhase = ownerToken.scrollView.nativeScrollPhase,
                    isCurrentOwnerToken = currentOwner,
                    isAnchorValid = viewportSize > 0,
                )
                if (!shouldApplyScrollOffsetWrite(writeContext)) {
                    if (currentOwner) {
                        requestRetry()
                    }
                    logScrollDiagnostic(
                        "alignment_apply_rejected",
                        "force=$forceExpand current=$currentOwner"
                    )
                    return@applyAlignment
                }
                val minDelta = (DEFAULT_CONTENT_SIZE * getDensity()).toInt()
                val epsilon = 0.5 * getDensity()  // 使用 0.5dp 作为误差值
                val reachBtm = contentOffset + viewportSize - currentContentSize >= -epsilon

                if (contentOffset <= 0 && !isComposeAtTopForScrollSync() && scrollView?.isDragging != true) {
                    // 整体把offset 加一下
                    var delta = calculateBackExpandSize(contentOffset)
                    delta = max(delta ?: minDelta, minDelta)
                    val previousContentSize = currentContentSize
                    val maxDelta = currentContentSize - viewportSize - contentOffset
                    if (delta > maxDelta) {
                        currentContentSize += (delta - maxDelta + minDelta)
                        updateContentSizeToRender()
                    }
                    val expandedContentSize = currentContentSize
                    if (pageData?.isOhOs == true) {
                        if (!shouldApplyDeferredScrollOffsetAlignmentAfterOhosRefresh(
                                forceExpand = forceExpand,
                                contextProvider = {
                                    writeContext.copy(
                                        isComposeScrolling = scrollInProgress(),
                                        nativeScrollPhase = ownerToken.scrollView.nativeScrollPhase,
                                        isCurrentOwnerToken = isCurrent() &&
                                            isCurrentScrollOffsetOwner(ownerToken),
                                    )
                                },
                                awaitRefreshWindow = {
                                    // 鸿蒙扩容后不会立刻刷新，也没有刷新 API，华为建议添加 delay。
                                    delay(25)
                                }
                            )
                        ) {
                            if (currentContentSize == expandedContentSize) {
                                currentContentSize = previousContentSize
                                updateContentSizeToRender()
                            }
                            if (isCurrent()) {
                                requestRetry()
                            }
                            return@applyAlignment
                        }
                    }
                    applyScrollViewOffsetDelta(
                            delta,
                            ownerToken = ownerToken,
                            intent = intent,
                            reason = "deferred_expand_start_backfill",
                            anchorValidator = {
                                contentOffset <= 0 && !isComposeAtTopForScrollSync()
                            },
                            onCommitted = { offsetDirty = true },
                            onCommitResult = { committed -> if (!committed) requestRetry() },
                            rollbackContentSize = previousContentSize,
                        )
                } else if (contentOffset > 0 && isComposeAtTopForScrollSync()) {
                    // compose 到顶了，但是scrollview没到顶
                    applyScrollViewOffsetDelta(
                            -contentOffset,
                            ownerToken = ownerToken,
                            intent = intent,
                            reason = "deferred_expand_start_pullback",
                            anchorValidator = {
                                contentOffset > 0 && isComposeAtTopForScrollSync()
                            },
                            onCommitted = { offsetDirty = false },
                            onCommitResult = { committed -> if (!committed) requestRetry() },
                        )
                } else if (isAtTop() && realContentSize == null && lastItemVisible() && scrollView?.isDragging != true) {
                    // 更新当前的contentSize大小
                    currentContentSize = calculateContentSize()
                    updateContentSizeToRender()
                } else if (canScrollForward && reachBtm) {
                    // 底部无法滑动了，扩容
                    currentContentSize += minDelta
                    updateContentSizeToRender()
                }
                logScrollDiagnostic("alignment_apply_exit", "force=$forceExpand current=${isCurrent()}")
            },
            interactionEpochProvider = { ownerToken.scrollView.nativeInteractionEpoch },
        )
    }
}

internal fun <T> scheduleDeferredScrollOffsetAlignment(
    coordinator: DeferredScrollOffsetAlignmentCoordinator<T>,
    contextProvider: () -> ScrollOffsetWriteContext,
    cancelPendingAlignment: (T) -> Unit,
    launchAlignment: (suspend () -> Unit) -> T?,
    awaitAlignmentWindow: suspend () -> Unit,
    retryAfterScrollEnd: () -> Unit,
    applyAlignment: suspend (
        isCurrent: () -> Boolean,
        requestRetryAfterScrollEnd: () -> Unit,
    ) -> Unit,
    interactionEpochProvider: () -> Long? = { null },
) {
    coordinator.replacePendingAlignment(
        retryOperationKey = StartAlignmentRetryOperation,
        cancelPendingAlignment = cancelPendingAlignment,
        launchAlignment = { request ->
            launchAlignment {
                val shouldApply = shouldApplyDeferredScrollOffsetAlignmentAfterWindow(
                    contextProvider = contextProvider,
                    awaitAlignmentWindow = awaitAlignmentWindow,
                )
                if (!coordinator.isCurrent(request)) return@launchAlignment
                if (!shouldApply) {
                    if (contextProvider().intent == ScrollOffsetWriteIntent.NonForcedAlignment) {
                        coordinator.requestRetryAfterScrollEnd(
                            request = request,
                            key = StartAlignmentRetryOperation,
                            interactionEpoch = interactionEpochProvider(),
                        ) { retryRequest ->
                            if (coordinator.isCurrent(retryRequest)) {
                                retryAfterScrollEnd()
                            }
                        }
                    }
                    return@launchAlignment
                }
                applyAlignment(
                    { coordinator.isCurrent(request) },
                    {
                        coordinator.requestRetryAfterScrollEnd(
                            request = request,
                            key = StartAlignmentRetryOperation,
                            interactionEpoch = interactionEpochProvider(),
                        ) { retryRequest ->
                            if (coordinator.isCurrent(retryRequest)) {
                                retryAfterScrollEnd()
                            }
                        }
                    },
                )
            }
        }
    )
}

internal suspend fun shouldApplyDeferredScrollOffsetAlignmentAfterWindow(
    contextProvider: () -> ScrollOffsetWriteContext,
    awaitAlignmentWindow: suspend () -> Unit,
): Boolean {
    awaitAlignmentWindow()
    return shouldApplyDeferredScrollOffsetAlignment(contextProvider())
}

internal fun shouldApplyDeferredScrollOffsetAlignment(
    context: ScrollOffsetWriteContext,
): Boolean = shouldApplyScrollOffsetWrite(context)

internal suspend fun shouldApplyDeferredScrollOffsetAlignmentAfterOhosRefresh(
    forceExpand: Boolean,
    contextProvider: () -> ScrollOffsetWriteContext,
    awaitRefreshWindow: suspend () -> Unit
): Boolean {
    awaitRefreshWindow()
    val context = contextProvider()
    return context.isCurrentOwnerToken &&
        context.intent == ScrollOffsetWriteIntent.NonForcedAlignment &&
        shouldApplyDeferredScrollOffsetAlignment(context)
}
