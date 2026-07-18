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
import com.tencent.kuikly.compose.foundation.lazy.LazyListState
import com.tencent.kuikly.compose.foundation.lazy.grid.LazyGridState
import com.tencent.kuikly.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import com.tencent.kuikly.compose.foundation.drawer.DrawerInternalPagerState
import com.tencent.kuikly.compose.foundation.pager.PagerState
import com.tencent.kuikly.compose.views.applyOffsetDelta
import com.tencent.kuikly.compose.gestures.KuiklyScrollInfo
import com.tencent.kuikly.compose.gestures.ScrollOffsetCapabilityClaim
import com.tencent.kuikly.compose.gestures.ScrollOffsetOwnerToken
import com.tencent.kuikly.compose.gestures.ScrollOffsetOperationToken
import com.tencent.kuikly.compose.gestures.ScrollOffsetWriteCapabilityKind
import com.tencent.kuikly.compose.gestures.KuiklyScrollableState
import com.tencent.kuikly.compose.ui.unit.IntOffset
import com.tencent.kuikly.core.views.NativeScrollPhase
import com.tencent.kuikly.core.views.ScrollerAttr
import com.tencent.kuikly.core.views.ScrollerEvent
import com.tencent.kuikly.core.views.ScrollerView
import com.tencent.kuikly.core.views.ScrollOffsetCommitToken
import com.tencent.kuikly.core.views.ScrollParams
import com.tencent.kuikly.core.views.ScrollWriteReplayDisposition
import com.tencent.kuikly.core.views.ScrollWriteReplayPolicy
import com.tencent.kuikly.core.views.ScrollWriteResult
import com.tencent.kuikly.core.views.ScrollWriteResultCode
import com.tencent.kuikly.core.views.SpringAnimation

private enum class ScrollOffsetIdleRequirement {
    ComposeAndNative,
    Native,
    None,
}

internal enum class ScrollOffsetWriteIntent(
    private val idleRequirement: ScrollOffsetIdleRequirement,
    internal val requiredCapability: ScrollOffsetWriteCapabilityKind? = null,
) {
    NonForcedAlignment(ScrollOffsetIdleRequirement.ComposeAndNative),
    GestureSnap(
        ScrollOffsetIdleRequirement.None,
        ScrollOffsetWriteCapabilityKind.GestureSnap,
    ),
    MutationOwnedProgrammatic(
        ScrollOffsetIdleRequirement.Native,
        ScrollOffsetWriteCapabilityKind.Mutation,
    ),
    StateRestore(ScrollOffsetIdleRequirement.Native),
    HostEmergencyCorrection(ScrollOffsetIdleRequirement.ComposeAndNative),
    ;

    internal fun idleRequirementSatisfied(
        isComposeScrolling: Boolean,
        nativeScrollPhase: NativeScrollPhase,
    ): Boolean = when (idleRequirement) {
        ScrollOffsetIdleRequirement.ComposeAndNative ->
            !isComposeScrolling && nativeScrollPhase == NativeScrollPhase.Idle
        ScrollOffsetIdleRequirement.Native -> nativeScrollPhase == NativeScrollPhase.Idle
        ScrollOffsetIdleRequirement.None -> true
    }

    internal val requiresNativeIdleCommit: Boolean
        get() = idleRequirement != ScrollOffsetIdleRequirement.None
}

internal fun scrollOffsetWriteIntentForClaim(
    claim: ScrollOffsetCapabilityClaim?,
): ScrollOffsetWriteIntent = when (claim?.kind) {
    ScrollOffsetWriteCapabilityKind.GestureSnap -> ScrollOffsetWriteIntent.GestureSnap
    ScrollOffsetWriteCapabilityKind.Mutation -> ScrollOffsetWriteIntent.MutationOwnedProgrammatic
    null -> ScrollOffsetWriteIntent.NonForcedAlignment
}

internal const val StartAlignmentRetryOperation = "start_alignment"
internal const val ViewportCorrectionRetryOperation = "viewport_correction"

internal data class ScrollOffsetWriteContext(
    val intent: ScrollOffsetWriteIntent,
    val isComposeScrolling: Boolean,
    val nativeScrollPhase: NativeScrollPhase,
    val isCurrentOwnerToken: Boolean,
    val isAnchorValid: Boolean,
    val hasRequiredCapability: Boolean = true,
)

internal fun shouldApplyScrollOffsetWrite(context: ScrollOffsetWriteContext): Boolean {
    if (!context.isCurrentOwnerToken || !context.isAnchorValid || !context.hasRequiredCapability) {
        return false
    }
    return context.intent.idleRequirementSatisfied(
        isComposeScrolling = context.isComposeScrolling,
        nativeScrollPhase = context.nativeScrollPhase,
    )
}

internal fun isValidScrollOffsetTarget(
    targetOffset: Int,
    contentSize: Int,
    viewportSize: Int,
): Boolean = viewportSize > 0 && targetOffset in 0..maxOf(0, contentSize - viewportSize)

internal fun shouldCommitScrollOffsetWriteResult(
    nativeCommitted: Boolean,
    isCurrentOwner: () -> Boolean,
    isAnchorValid: () -> Boolean,
): Boolean = nativeCommitted && isCurrentOwner() && isAnchorValid()

internal fun KuiklyScrollInfo.applyScrollViewContentOffset(
    ownerToken: ScrollOffsetOwnerToken,
    offsetX: Float,
    offsetY: Float,
    animated: Boolean,
    intent: ScrollOffsetWriteIntent,
    reason: String,
    springAnimation: SpringAnimation? = null,
    ignoredNativeOffset: IntOffset? = null,
    capabilityClaim: ScrollOffsetCapabilityClaim? = null,
    anchorValidator: () -> Boolean,
    onCommitResult: ((Boolean) -> Unit)? = null,
): Boolean {
    val targetScrollView = ownerToken.scrollView
    val initialOperation = beginScrollOffsetOperation(
        ownerToken = ownerToken,
        requiredCapability = intent.requiredCapability,
        capabilityClaim = capabilityClaim,
    ) ?: run {
        onCommitResult?.invoke(false)
        return false
    }
    var terminalDelivered = false

    fun finish(success: Boolean) {
        if (terminalDelivered) return
        terminalDelivered = true
        completeScrollWriteInvalidationTerminal(initialOperation.semanticOperationId)
        cancelScrollWriteTimers(initialOperation.semanticOperationId)
        onCommitResult?.invoke(success)
    }

    registerScrollWriteInvalidationTerminal(initialOperation.semanticOperationId) {
        finish(false)
    }

    fun dispatchAttempt(operationToken: ScrollOffsetOperationToken, attempt: Int) {
        var attemptTerminalHandled = false
        fun finishOrReplay(result: ScrollWriteResult) {
            if (attemptTerminalHandled) return
            attemptTerminalHandled = true
            val latest = isLatestScrollOffsetOperation(operationToken) && anchorValidator()
            val effectiveResult = when {
                result.committed && isCurrentScrollOffsetOperation(operationToken, anchorValidator) -> result
                !latest -> result.copy(code = ScrollWriteResultCode.Stale)
                result.committed -> result.copy(code = ScrollWriteResultCode.Stale)
                else -> result
            }
            if (effectiveResult.committed) {
                logScrollDiagnostic(
                    "content_offset_write_committed",
                    "reason=$reason intent=$intent attempt=$attempt",
                )
                finish(true)
                return
            }
            ignoredNativeOffset?.let {
                clearIgnoreScrollOffset(
                    com.tencent.kuikly.core.views.ScrollWriteOperationKey(
                        operationToken.semanticOperationId,
                        operationToken.attemptGeneration,
                    ),
                )
            }
            val decision = ScrollWriteReplayPolicy.decide(effectiveResult, attempt)
            val retry = retry@{ enforceStartAckDeadline: Boolean ->
                cancelScrollWriteTimers(operationToken.semanticOperationId)
                val next = beginScrollOffsetRetry(
                    operationToken,
                    enforceStartAckDeadline = enforceStartAckDeadline,
                ) ?: run {
                    finish(false)
                    return@retry
                }
                dispatchAttempt(next, decision.nextAttempt)
            }
            logScrollDiagnostic(
                "content_offset_write_terminal",
                "reason=$reason intent=$intent attempt=$attempt result=${effectiveResult.code} " +
                    "replay=${decision.disposition}",
            )
            when (decision.disposition) {
                ScrollWriteReplayDisposition.None -> {
                    if (effectiveResult.code == ScrollWriteResultCode.RollbackFailed) {
                        quarantineAndCanonicalResync(ownerToken)
                    }
                    finish(false)
                }
                ScrollWriteReplayDisposition.ReplanImmediately -> {
                    if (canonicalResyncScrollState(ownerToken)) retry(true) else finish(false)
                }
                ScrollWriteReplayDisposition.WaitForInteractionTerminal -> {
                    val key = "scroll_write_${operationToken.semanticOperationId}"
                    deferredScrollOffsetAlignmentCoordinator.requestRetryAfterScrollEnd(
                        key = key,
                        interactionEpoch = operationToken.nativeInteractionEpoch,
                        onInvalidated = { finish(false) },
                    ) { request ->
                        if (deferredScrollOffsetAlignmentCoordinator.isCurrent(request)) {
                            cancelScrollWriteTimers(operationToken.semanticOperationId)
                            retry(false)
                        }
                    }
                    scheduleInteractionWatchdog(operationToken) {
                        deferredScrollOffsetAlignmentCoordinator.clearRetryOperation(key)
                        if (canonicalResyncScrollState(ownerToken)) retry(false) else finish(false)
                    }
                }
                ScrollWriteReplayDisposition.WaitForRevision -> {
                    val revisionAlreadyAdvanced =
                        targetScrollView.nativeInteractionEpoch > operationToken.nativeInteractionEpoch ||
                            targetScrollView.nativeLayoutRevision > operationToken.layoutRevision ||
                            targetScrollView.nativeInsetRevision > operationToken.insetRevision
                    if (revisionAlreadyAdvanced) {
                        retry(true)
                    } else {
                        awaitScrollWriteRevision(operationToken) {
                            cancelScrollWriteTimers(operationToken.semanticOperationId)
                            retry(true)
                        }
                        scheduleRetryDeadline(operationToken) {
                            cancelScrollWriteTimers(operationToken.semanticOperationId)
                            canonicalResyncScrollState(ownerToken)
                            finish(false)
                        }
                    }
                }
            }
        }

        val context = ScrollOffsetWriteContext(
            intent = intent,
            isComposeScrolling = isComposeScrollInProgress(),
            nativeScrollPhase = targetScrollView.nativeScrollPhase,
            isCurrentOwnerToken = isCurrentScrollOffsetOwner(ownerToken),
            isAnchorValid = anchorValidator(),
            hasRequiredCapability = isLatestScrollOffsetOperation(operationToken),
        )
        if (!shouldApplyScrollOffsetWrite(context)) {
            val code = if (context.isCurrentOwnerToken && context.isAnchorValid &&
                context.hasRequiredCapability
            ) {
                ScrollWriteResultCode.Busy
            } else {
                ScrollWriteResultCode.Stale
            }
            finishOrReplay(
                ScrollWriteResult(
                    code = code,
                    nativeInteractionEpoch = targetScrollView.nativeInteractionEpoch,
                    layoutRevision = targetScrollView.nativeLayoutRevision,
                    insetRevision = targetScrollView.nativeInsetRevision,
                ),
            )
            return
        }

        logScrollDiagnostic(
            "content_offset_write_before",
            "reason=$reason intent=$intent x=$offsetX y=$offsetY animated=$animated attempt=$attempt",
        )
        ignoredNativeOffset?.let {
            installIgnoreScrollOffset(
                com.tencent.kuikly.core.views.ScrollWriteOperationKey(
                    operationToken.semanticOperationId,
                    operationToken.attemptGeneration,
                ),
                it,
            )
        }
        targetScrollView.setContentOffset(
            offsetX = offsetX,
            offsetY = offsetY,
            animated = animated,
            springAnimation = springAnimation,
            writeToken = ScrollOffsetCommitToken(
                generation = ownerToken.nativeWriteGeneration,
                requiresNativeIdle = intent.requiresNativeIdleCommit,
                operationGeneration = operationToken.attemptGeneration,
                expectedContentSize = operationToken.expectedContentSize / getDensity(),
                expectedViewportSize = operationToken.expectedViewportSize / getDensity(),
                bindingGeneration = ownerToken.bindingGeneration,
                capabilityKind = operationToken.capabilityKind?.ordinal ?: -1,
                capabilityLeaseId = operationToken.capabilityLeaseId,
                semanticOperationId = operationToken.semanticOperationId,
                attemptGeneration = operationToken.attemptGeneration,
                nativeInteractionEpoch = operationToken.nativeInteractionEpoch,
                layoutRevision = operationToken.layoutRevision,
                anchorRevision = operationToken.anchorRevision,
                rangeRevision = operationToken.rangeRevision,
                insetRevision = operationToken.insetRevision,
            ),
            onCommitResultDetailed = ::finishOrReplay,
        )
    }

    dispatchAttempt(initialOperation, attempt = 1)
    return true
}

/**
 * Get the KuiklyScrollInfo instance corresponding to ScrollableState
 */
internal val ScrollableState.kuiklyInfo: KuiklyScrollInfo
    get() = when (this) {
        is LazyListState -> scrollableState.kuiklyInfo
        is PagerState -> scrollableState.kuiklyInfo
        is DrawerInternalPagerState -> scrollableState.kuiklyInfo
        is LazyGridState -> scrollableState.kuiklyInfo
        is LazyStaggeredGridState -> scrollableState.kuiklyInfo
        is ScrollState -> scrollableState.kuiklyInfo
        is KuiklyScrollableState -> kuiklyInfo
        else -> KuiklyScrollInfo()
    }

/**
 * Handle scroll events
 * @param delta scroll offset
 * @return actual consumed offset
 */
internal fun ScrollableState.kuiklyOnScroll(delta: Float): Float = when (this) {
    is LazyListState -> scrollableState.kuiklyOnScroll(delta)
    is PagerState -> scrollableState.kuiklyOnScroll(delta)
    is DrawerInternalPagerState -> scrollableState.kuiklyOnScroll(delta)
    is LazyGridState -> scrollableState.kuiklyOnScroll(delta)
    is LazyStaggeredGridState -> scrollableState.kuiklyOnScroll(delta)
    is ScrollState -> scrollableState.kuiklyOnScroll(delta)
    is KuiklyScrollableState -> kuiklyOnScroll(delta)
    else -> dispatchRawDelta(delta)
}

/**
 * Handle scroll end events
 */
internal fun ScrollableState.kuiklyOnScrollEnd(
    params: ScrollParams,
) {
    logScrollDiagnostic("scroll_end_received")
    when (this) {
        is LazyListState -> scrollableState.kuiklyOnScrollEnd(params)
        is PagerState -> {
            // NOTE: Do NOT clear isSnapAnimating here.
            // scrollEnd fires before data-load remeasure, so clearing here
            // leaves updateFromMeasureResult unprotected. isSnapAnimating is
            // cleared in the applyMeasureResult_job after FIXING decision.
            scrollableState.kuiklyOnScrollEnd(params)
        }
        is DrawerInternalPagerState -> scrollableState.kuiklyOnScrollEnd(params)
        is LazyGridState -> scrollableState.kuiklyOnScrollEnd(params)
        is LazyStaggeredGridState -> scrollableState.kuiklyOnScrollEnd(params)
        is ScrollState -> scrollableState.kuiklyOnScrollEnd(params)
        is KuiklyScrollableState -> kuiklyOnScrollEnd(params)
        else -> { /* No need to handle */ }
    }
    val scheduled = kuiklyInfo.deferredScrollOffsetAlignmentCoordinator.retryAfterScrollEnd(
        params.nativeInteractionEpoch,
    )
    logScrollDiagnostic("scroll_end_retry", "scheduled=$scheduled")
}

/**
 * Check if at top position
 * If PullToRefresh exists, need to consider the index it occupies
 */
internal fun ScrollableState.isAtTop(): Boolean = when(this) {
    is LazyListState -> {
        if (kuiklyInfo.hasPullToRefresh && kuiklyInfo.pullToRefreshTopInsetPx > 0) {
            // With top inset, index=1 offset=0 means PTR padding scrolled away — not at top.
            firstVisibleItemIndex == 0
        } else {
            val pullToRefreshOffset = if (kuiklyInfo.hasPullToRefresh) 1 else 0
            firstVisibleItemIndex <= pullToRefreshOffset && firstVisibleItemScrollOffset == 0
        }
    }
    is PagerState -> firstVisiblePage == 0 && firstVisiblePageOffset == 0
    is DrawerInternalPagerState -> firstVisiblePage == 0 && firstVisiblePageOffset == 0
    is LazyGridState -> {
        val pullToRefreshOffset = if (kuiklyInfo.hasPullToRefresh) 1 else 0
        firstVisibleItemIndex <= pullToRefreshOffset && firstVisibleItemScrollOffset == 0
    }
    is LazyStaggeredGridState -> {
        val pullToRefreshOffset = if (kuiklyInfo.hasPullToRefresh) 1 else 0
        firstVisibleItemIndex <= pullToRefreshOffset && firstVisibleItemScrollOffset == 0
    }
    is ScrollState -> value == 0
    else -> false
}

/**
 * Check if the last index is visible
 */
internal fun ScrollableState.lastItemVisible(): Boolean = when(this) {
    is LazyListState -> layoutInfo.visibleItemsInfo.lastOrNull()?.index == layoutInfo.totalItemsCount - 1
    is PagerState -> currentPage == pageCount - 1
    is DrawerInternalPagerState -> currentPage == pageCount - 1
    is LazyGridState -> layoutInfo.visibleItemsInfo.lastOrNull()?.index == layoutInfo.totalItemsCount - 1
    is LazyStaggeredGridState -> layoutInfo.visibleItemsInfo.lastOrNull()?.index == layoutInfo.totalItemsCount - 1
    is ScrollState -> value >= maxValue
    else -> false
}

/**
 * Check if the offset is valid
 */
internal fun ScrollableState.isValidOffsetDelta(delta: Int): Boolean {
    if (kuiklyInfo.scrollView?.renderView == null || delta == 0) return false
    val newOffset = kuiklyInfo.contentOffset + delta
    return newOffset >= 0 && newOffset <= (kuiklyInfo.currentContentSize - kuiklyInfo.viewportSize)
}

/**
 * Animate scroll to the top (item index 0) for supported lazy containers.
 * Keep the API style consistent with [isAtTop].
 */
internal suspend fun ScrollableState.animateScrollToTop() {
    when (this) {
        is LazyListState -> this.animateScrollToItem(0)
        is LazyGridState -> this.animateScrollToItem(0)
        is LazyStaggeredGridState -> this.animateScrollToItem(0)
        is DrawerInternalPagerState -> this.animateScrollToPage(0)
        is PagerState -> this.animateScrollToPage(0)
        else -> {}
    }
}

/**
 * Check if the native scroll offset should be rejected.
 * Currently only DrawerInternalPagerState can reject offsets (to guard against
 * platform-level offset resets such as HarmonyOS HandleCrashTop()).
 * Returns true if the offset should be ignored and the native side corrected back.
 */
internal fun ScrollableState.shouldRejectNativeScrollOffset(newOffset: Int): Boolean = when (this) {
    is DrawerInternalPagerState -> shouldRejectNativeScrollOffset(newOffset)
    else -> false
}

/**
 * Apply scroll view offset delta
 */
internal fun ScrollableState.applyScrollViewOffsetDelta(
    delta: Int,
    ownerToken: ScrollOffsetOwnerToken,
    intent: ScrollOffsetWriteIntent,
    reason: String,
    anchorValidator: () -> Boolean,
    onCommitted: (() -> Unit)? = null,
    onCommitResult: ((Boolean) -> Unit)? = null,
    rollbackContentSize: Int? = null,
    capabilityClaim: ScrollOffsetCapabilityClaim? = null,
): Boolean {
    val scrollView = ownerToken.scrollView
    if (delta == 0) return false
    val density = kuiklyInfo.getDensity()
    fun currentNativeOffset(): Int = if (kuiklyInfo.orientation == Orientation.Vertical) {
        (scrollView.curOffsetY * density).toInt()
    } else {
        (scrollView.curOffsetX * density).toInt()
    }
    val targetOffset = currentNativeOffset() + delta
    val requestedContentSize = kuiklyInfo.currentContentSize
    val requiredContentSize = if (targetOffset + kuiklyInfo.viewportSize > requestedContentSize) {
        requestedContentSize + (2000 * density + delta).toInt()
    } else {
        requestedContentSize
    }
    val initialOperation = kuiklyInfo.beginScrollOffsetOperation(
        ownerToken = ownerToken,
        requiredCapability = intent.requiredCapability,
        capabilityClaim = capabilityClaim,
    ) ?: run {
        onCommitResult?.invoke(false)
        return false
    }
    var terminalDelivered = false

    fun freezeTerminal(): Boolean {
        if (terminalDelivered) return false
        terminalDelivered = true
        kuiklyInfo.completeScrollWriteInvalidationTerminal(initialOperation.semanticOperationId)
        kuiklyInfo.cancelScrollWriteTimers(initialOperation.semanticOperationId)
        return true
    }

    fun finish(success: Boolean) {
        if (!freezeTerminal()) return
        onCommitResult?.invoke(success)
    }

    kuiklyInfo.registerScrollWriteInvalidationTerminal(initialOperation.semanticOperationId) {
        finish(false)
    }

    fun dispatchAttempt(operation: ScrollOffsetOperationToken, attempt: Int) {
        var attemptTerminalHandled = false
        var operationToken = operation
        val resourceOperation = com.tencent.kuikly.core.views.ScrollWriteOperationKey(
            semanticOperationId = operationToken.semanticOperationId,
            attemptGeneration = operationToken.attemptGeneration,
        )
        var provisionalContentSizePrevious: Int? = null
        if (attempt == 1 && rollbackContentSize != null &&
            rollbackContentSize != kuiklyInfo.currentContentSize
        ) {
            kuiklyInfo.installProvisionalContentSize(
                operation = resourceOperation,
                value = kuiklyInfo.currentContentSize,
            )
            provisionalContentSizePrevious = rollbackContentSize
        } else if (kuiklyInfo.currentContentSize != requiredContentSize) {
            provisionalContentSizePrevious = kuiklyInfo.installProvisionalContentSize(
                operation = resourceOperation,
                value = requiredContentSize,
            )
            operationToken = kuiklyInfo.refreshScrollOffsetOperation(operationToken) ?: run {
                val rolledBack = kuiklyInfo.rollbackProvisionalContentSize(
                    resourceOperation,
                    provisionalContentSizePrevious!!,
                )
                if (!rolledBack) kuiklyInfo.quarantineAndCanonicalResync(ownerToken)
                finish(false)
                return
            }
        }

        fun rollbackProvisionalContentSize(): Boolean {
            val previous = provisionalContentSizePrevious ?: return true
            return kuiklyInfo.rollbackProvisionalContentSize(resourceOperation, previous)
        }

        fun finishOrReplay(nativeResult: ScrollWriteResult, committedOffset: IntOffset? = null) {
            if (attemptTerminalHandled) return
            attemptTerminalHandled = true
            val latest = kuiklyInfo.isLatestScrollOffsetOperation(operationToken) && anchorValidator()
            var effectiveResult = when {
                nativeResult.committed &&
                    kuiklyInfo.isCurrentScrollOffsetOperation(operationToken, anchorValidator) -> nativeResult
                !latest -> nativeResult.copy(code = ScrollWriteResultCode.Stale)
                nativeResult.committed -> nativeResult.copy(code = ScrollWriteResultCode.Stale)
                else -> nativeResult
            }
            if (effectiveResult.committed) {
                if (provisionalContentSizePrevious != null &&
                    !kuiklyInfo.finalizeProvisionalContentSize(resourceOperation)
                ) {
                    effectiveResult = effectiveResult.copy(code = ScrollWriteResultCode.RollbackFailed)
                } else {
                    val appliedOffset = committedOffset ?: if (kuiklyInfo.isVertical()) {
                        IntOffset(0, targetOffset)
                    } else {
                        IntOffset(targetOffset, 0)
                    }
                    kuiklyInfo.composeOffset = if (kuiklyInfo.orientation == Orientation.Vertical) {
                        appliedOffset.y.toFloat()
                    } else {
                        appliedOffset.x.toFloat()
                    }
                    logScrollDiagnostic(
                        "offset_write_after",
                        "reason=$reason intent=$intent attempt=$attempt applied=${kuiklyInfo.composeOffset}",
                    )
                    if (!freezeTerminal()) return
                    onCommitted?.invoke()
                    onCommitResult?.invoke(true)
                    return
                }
            } else if (!rollbackProvisionalContentSize() && latest) {
                effectiveResult = effectiveResult.copy(code = ScrollWriteResultCode.RollbackFailed)
            }

            logScrollDiagnostic(
                "offset_write_terminal",
                "reason=$reason intent=$intent attempt=$attempt result=${effectiveResult.code}",
            )
            if (effectiveResult.code == ScrollWriteResultCode.RollbackFailed) {
                kuiklyInfo.quarantineAndCanonicalResync(ownerToken)
                finish(false)
                return
            }
            val decision = ScrollWriteReplayPolicy.decide(effectiveResult, attempt)
            fun retry(enforceStartAckDeadline: Boolean = true) {
                kuiklyInfo.cancelScrollWriteTimers(operationToken.semanticOperationId)
                val next = kuiklyInfo.beginScrollOffsetRetry(
                    operationToken,
                    enforceStartAckDeadline = enforceStartAckDeadline,
                ) ?: run {
                    finish(false)
                    return
                }
                dispatchAttempt(next, decision.nextAttempt)
            }
            when (decision.disposition) {
                ScrollWriteReplayDisposition.None -> finish(false)
                ScrollWriteReplayDisposition.ReplanImmediately -> {
                    if (kuiklyInfo.canonicalResyncScrollState(ownerToken)) retry()
                    else finish(false)
                }
                ScrollWriteReplayDisposition.WaitForInteractionTerminal -> {
                    val key = "offset_delta_${operationToken.semanticOperationId}"
                    val coordinator = kuiklyInfo.deferredScrollOffsetAlignmentCoordinator
                    coordinator.requestRetryAfterScrollEnd(
                        key = key,
                        interactionEpoch = operationToken.nativeInteractionEpoch,
                        onInvalidated = { finish(false) },
                    ) { request ->
                        if (coordinator.isCurrent(request)) {
                            kuiklyInfo.cancelScrollWriteTimers(operationToken.semanticOperationId)
                            retry(enforceStartAckDeadline = false)
                        }
                    }
                    kuiklyInfo.scheduleInteractionWatchdog(operationToken) {
                        coordinator.clearRetryOperation(key)
                        if (kuiklyInfo.canonicalResyncScrollState(ownerToken)) {
                            retry(enforceStartAckDeadline = false)
                        } else {
                            finish(false)
                        }
                    }
                }
                ScrollWriteReplayDisposition.WaitForRevision -> {
                    val revisionAlreadyAdvanced =
                        scrollView.nativeInteractionEpoch > operationToken.nativeInteractionEpoch ||
                            scrollView.nativeLayoutRevision > operationToken.layoutRevision ||
                            scrollView.nativeInsetRevision > operationToken.insetRevision
                    if (revisionAlreadyAdvanced) {
                        retry()
                    } else {
                        kuiklyInfo.awaitScrollWriteRevision(operationToken) {
                            kuiklyInfo.cancelScrollWriteTimers(operationToken.semanticOperationId)
                            retry()
                        }
                        kuiklyInfo.scheduleRetryDeadline(operationToken) {
                            kuiklyInfo.cancelScrollWriteTimers(operationToken.semanticOperationId)
                            kuiklyInfo.canonicalResyncScrollState(ownerToken)
                            finish(false)
                        }
                    }
                }
            }
        }

        val context = ScrollOffsetWriteContext(
            intent = intent,
            isComposeScrolling = isScrollInProgress,
            nativeScrollPhase = scrollView.nativeScrollPhase,
            isCurrentOwnerToken = kuiklyInfo.isCurrentScrollOffsetOwner(ownerToken),
            isAnchorValid = anchorValidator(),
            hasRequiredCapability = kuiklyInfo.isLatestScrollOffsetOperation(operationToken),
        )
        if (!shouldApplyScrollOffsetWrite(context)) {
            finishOrReplay(
                ScrollWriteResult(
                    code = if (context.isCurrentOwnerToken && context.isAnchorValid &&
                        context.hasRequiredCapability
                    ) {
                        ScrollWriteResultCode.Busy
                    } else {
                        ScrollWriteResultCode.Stale
                    },
                    nativeInteractionEpoch = scrollView.nativeInteractionEpoch,
                    layoutRevision = scrollView.nativeLayoutRevision,
                    insetRevision = scrollView.nativeInsetRevision,
                ),
            )
            return
        }

        if (!isValidScrollOffsetTarget(
                targetOffset = targetOffset,
                contentSize = requiredContentSize,
                viewportSize = kuiklyInfo.viewportSize,
            )
        ) {
            finishOrReplay(ScrollWriteResult(ScrollWriteResultCode.OutOfRange))
            return
        }
        val remainingDelta = targetOffset - currentNativeOffset()
        if (remainingDelta == 0) {
            finishOrReplay(ScrollWriteResult.AlreadySatisfied)
            return
        }

        logScrollDiagnostic(
            "offset_write_before",
            "reason=$reason intent=$intent attempt=$attempt delta=$remainingDelta",
        )
        scrollView.applyOffsetDelta(
            delta = remainingDelta,
            kuiklyInfo = kuiklyInfo,
            writeToken = ScrollOffsetCommitToken(
                generation = ownerToken.nativeWriteGeneration,
                requiresNativeIdle = intent.requiresNativeIdleCommit,
                operationGeneration = operationToken.attemptGeneration,
                expectedContentSize = operationToken.expectedContentSize / kuiklyInfo.getDensity(),
                expectedViewportSize = operationToken.expectedViewportSize / kuiklyInfo.getDensity(),
                bindingGeneration = ownerToken.bindingGeneration,
                capabilityKind = operationToken.capabilityKind?.ordinal ?: -1,
                capabilityLeaseId = operationToken.capabilityLeaseId,
                semanticOperationId = operationToken.semanticOperationId,
                attemptGeneration = operationToken.attemptGeneration,
                nativeInteractionEpoch = operationToken.nativeInteractionEpoch,
                layoutRevision = operationToken.layoutRevision,
                anchorRevision = operationToken.anchorRevision,
                rangeRevision = operationToken.rangeRevision,
                insetRevision = operationToken.insetRevision,
            ),
            isStillCurrent = {
                kuiklyInfo.isCurrentScrollOffsetOperation(operationToken, anchorValidator) &&
                    (provisionalContentSizePrevious == null ||
                        kuiklyInfo.ownsProvisionalContentSize(resourceOperation))
            },
            onCommitResult = ::finishOrReplay,
        )
    }

    dispatchAttempt(initialOperation, attempt = 1)
    return true
}

/**
 * Request scroll to top in a non-suspending way. This defers the jump to when layout is ready,
 * avoiding timing issues right after ScrollView recreation.
 */
internal fun ScrollableState.requestScrollToTop() {
    when (this) {
        is LazyListState -> requestScrollToItem(0)
        is LazyGridState -> requestScrollToItem(0)
        is LazyStaggeredGridState -> requestScrollToItem(0)
        is DrawerInternalPagerState -> requestScrollToPage(0)
        is PagerState -> requestScrollToPage(0)
        // ScrollState does not have request API; skip for now
        else -> {}
    }
}

/**
 * Check if scroll to top callback is set.
 * If callback is set, invoke it and return true; otherwise return false.
 */
internal fun ScrollableState.handleScrollToTopCallback(): Boolean {
    val callback = kuiklyInfo.scrollToTopCallback
    return if (callback != null) {
        callback.invoke()
        true
    } else {
        false
    }
}
