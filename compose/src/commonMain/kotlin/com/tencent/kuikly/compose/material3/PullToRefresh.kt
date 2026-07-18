/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tencent.kuikly.compose.material3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.lazy.LazyListScope
import com.tencent.kuikly.compose.foundation.lazy.LazyListState
import com.tencent.kuikly.compose.gestures.DeferredScrollOffsetRetryRequest
import com.tencent.kuikly.compose.gestures.ScrollOffsetOperationToken
import com.tencent.kuikly.compose.scroller.isAtTop
import com.tencent.kuikly.compose.scroller.kuiklyInfo
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.layout.layout
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.views.ScrollOffsetCommitToken
import com.tencent.kuikly.core.views.ScrollWriteReplayDisposition
import com.tencent.kuikly.core.views.ScrollWriteReplayPolicy
import com.tencent.kuikly.core.views.ScrollWriteResult
import com.tencent.kuikly.core.views.ScrollWriteResultCode
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs

// Set true to trace offset / state in Xcode console while debugging pull-to-refresh.
private const val DEBUG_PULL_TO_REFRESH = false
private const val PullToRefreshInsetRetryOperation = "pull_to_refresh_inset"

private class PullToRefreshTerminalState {
    var delivered: Boolean = false
}

private inline fun pullToRefreshLog(message: () -> String) {
    if (DEBUG_PULL_TO_REFRESH) {
        println("[PullToRefresh] ${message()}")
    }
}

private fun LazyListState.setPullToRefreshContentInset(
    top: Float,
    animated: Boolean,
) {
    val coordinator = kuiklyInfo.deferredScrollOffsetAlignmentCoordinator
    val info = kuiklyInfo
    val ownerToken = info.captureScrollOffsetOwnerToken() ?: return
    val request = coordinator.beginRetryOperation(
        PullToRefreshInsetRetryOperation,
        ownerToken.scrollView.nativeInteractionEpoch,
    )
    val operation = info.beginScrollOffsetOperation(ownerToken) ?: run {
        coordinator.completeRetryOperation(request)
        return
    }
    applyPullToRefreshContentInset(
        top,
        animated,
        request,
        operation,
        attempt = 1,
        terminalState = PullToRefreshTerminalState(),
    )
}

private fun LazyListState.applyPullToRefreshContentInset(
    top: Float,
    animated: Boolean,
    request: DeferredScrollOffsetRetryRequest,
    operationToken: ScrollOffsetOperationToken,
    attempt: Int,
    terminalState: PullToRefreshTerminalState,
) {
    val info = kuiklyInfo
    val coordinator = info.deferredScrollOffsetAlignmentCoordinator
    fun finish() {
        if (terminalState.delivered) return
        terminalState.delivered = true
        info.completeScrollWriteInvalidationTerminal(operationToken.semanticOperationId)
        info.cancelScrollWriteTimers(operationToken.semanticOperationId)
        coordinator.completeRetryOperation(request)
    }

    info.registerScrollWriteInvalidationTerminal(operationToken.semanticOperationId, ::finish)
    if (!coordinator.isCurrent(request) || !info.isLatestScrollOffsetOperation(operationToken)) {
        finish()
        return
    }
    val ownerToken = operationToken.ownerToken
    var attemptTerminalHandled = false

    fun finishOrReplay(result: ScrollWriteResult) {
        if (attemptTerminalHandled) return
        attemptTerminalHandled = true
        if (!coordinator.isCurrent(request)) {
            finish()
            return
        }
        val latest = info.isLatestScrollOffsetOperation(operationToken)
        val effectiveResult = when {
            result.committed && info.isCurrentScrollOffsetOperation(operationToken) { true } -> result
            !latest || result.committed -> result.copy(code = ScrollWriteResultCode.Stale)
            else -> result
        }
        if (effectiveResult.committed) {
            finish()
            return
        }
        val decision = ScrollWriteReplayPolicy.decide(effectiveResult, attempt)
        val retry = retry@{ enforceStartAckDeadline: Boolean ->
            if (!coordinator.isCurrent(request)) return@retry
            info.cancelScrollWriteTimers(operationToken.semanticOperationId)
            val next = info.beginScrollOffsetRetry(
                operationToken,
                enforceStartAckDeadline = enforceStartAckDeadline,
            ) ?: run {
                finish()
                return@retry
            }
            applyPullToRefreshContentInset(
                top = top,
                animated = animated,
                request = request,
                operationToken = next,
                attempt = decision.nextAttempt,
                terminalState = terminalState,
            )
        }
        when (decision.disposition) {
            ScrollWriteReplayDisposition.None -> {
                if (effectiveResult.code == ScrollWriteResultCode.RollbackFailed) {
                    info.quarantineAndCanonicalResync(ownerToken)
                }
                finish()
            }
            ScrollWriteReplayDisposition.ReplanImmediately -> {
                if (info.canonicalResyncScrollState(ownerToken)) retry(true) else finish()
            }
            ScrollWriteReplayDisposition.WaitForInteractionTerminal -> {
                coordinator.requestRetryAfterScrollEnd(
                    request = request,
                    onInvalidated = ::finish,
                ) { retryRequest ->
                    if (coordinator.isCurrent(retryRequest)) retry(false)
                }
                info.scheduleInteractionWatchdog(operationToken) {
                    coordinator.discardPendingRetryOperation(request)
                    if (info.canonicalResyncScrollState(ownerToken)) retry(false) else finish()
                }
            }
            ScrollWriteReplayDisposition.WaitForRevision -> {
                val view = ownerToken.scrollView
                val revisionAlreadyAdvanced =
                    view.nativeInteractionEpoch > operationToken.nativeInteractionEpoch ||
                        view.nativeLayoutRevision > operationToken.layoutRevision ||
                        view.nativeInsetRevision > operationToken.insetRevision
                if (revisionAlreadyAdvanced) {
                    retry(true)
                } else {
                    info.awaitScrollWriteRevision(operationToken) {
                        info.cancelScrollWriteTimers(operationToken.semanticOperationId)
                        retry(true)
                    }
                    info.scheduleRetryDeadline(operationToken) {
                        finish()
                    }
                }
            }
        }
    }

    ownerToken.scrollView.setContentInset(
        top = top,
        animated = animated,
        writeToken = ScrollOffsetCommitToken(
            generation = ownerToken.nativeWriteGeneration,
            requiresNativeIdle = true,
            operationGeneration = operationToken.attemptGeneration,
            expectedContentSize = operationToken.expectedContentSize / info.getDensity(),
            expectedViewportSize = operationToken.expectedViewportSize / info.getDensity(),
            bindingGeneration = ownerToken.bindingGeneration,
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

private fun LazyListState.setPullToRefreshContentInsetWhenEndDrag(top: Float) {
    val info = kuiklyInfo
    val ownerToken = info.captureScrollOffsetOwnerToken() ?: return
    val armToken = info.beginEndDragInsetArm(ownerToken) ?: return
    ownerToken.scrollView.setContentInsetWhenEndDrag(
        top = top,
        writeToken = armToken,
    )
}

/**
 * Custom offset modifier to adjust child position
 */
private fun Modifier.offsetWithParentAdjustment(
    x: Dp = 0.dp,
    y: Dp = 0.dp
) = this.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val xPx = x.roundToPx()
    val yPx = y.roundToPx()

    // Calculate actual required width and height
    val width = placeable.width + xPx
    val height = placeable.height + yPx

    layout(width, height) {
        placeable.placeRelative(xPx, yPx)
    }
}

internal data class PullToRefreshRuntimeConfig(
    val holdRefreshInset: Boolean,
    val refreshThresholdPx: Float,
    val refreshThresholdLogical: Float
)

internal data class PullToRefreshSnapshot(
    val contentOffset: Int,
    val isAtTop: Boolean,
    val isDragging: Boolean,
    val isRefreshing: Boolean,
    val holdRefreshInset: Boolean,
    val refreshThresholdPx: Float,
    val refreshThresholdLogical: Float
) {
    val pullDistance: Float
        get() = if (contentOffset < 0) abs(contentOffset.toFloat()) else 0f

    val progress: Float
        get() = (pullDistance / refreshThresholdPx).coerceIn(0f, 1f)

    val isThresholdReached: Boolean
        get() = pullDistance >= refreshThresholdPx

    val endDragInset: Float
        get() = pullRefreshEndDragInset(
            holdRefreshInset = holdRefreshInset,
            refreshThreshold = refreshThresholdLogical
        )
}

/**
 * Keeps the long-lived scroll collector connected to configuration updated by recomposition.
 * The provider instance stays attached to the same scroll state while [configState] changes.
 */
internal class PullToRefreshSnapshotProvider(
    private val configState: State<PullToRefreshRuntimeConfig>
) {
    fun snapshot(
        contentOffset: Int,
        isAtTop: Boolean,
        isDragging: Boolean,
        isRefreshing: Boolean
    ): PullToRefreshSnapshot {
        val config = configState.value
        return PullToRefreshSnapshot(
            contentOffset = contentOffset,
            isAtTop = isAtTop,
            isDragging = isDragging,
            isRefreshing = isRefreshing,
            holdRefreshInset = config.holdRefreshInset,
            refreshThresholdPx = config.refreshThresholdPx,
            refreshThresholdLogical = config.refreshThresholdLogical
        )
    }
}

/**
 * Creates a [PullToRefreshState] that is remembered across compositions.
 *
 * @param isRefreshing the value for [PullToRefreshState.isRefreshing]
 */
@Composable
fun rememberPullToRefreshState(
    isRefreshing: Boolean
): PullToRefreshState = remember {
    PullToRefreshState(isRefreshing)
}.apply { this.isRefreshing = isRefreshing }

/**
 * Pull-to-refresh state enum, inspired by RefreshView design
 */
enum class PullState {
    IDLE,        // Normal idle state
    PULLING,     // Ready to refresh when released
    REFRESHING,  // Currently refreshing
}

/**
 * A state object that can be hoisted to control and observe pull-to-refresh behavior.
 *
 * @param isRefreshing the initial refreshing state
 */
@Stable
class PullToRefreshState(
    isRefreshing: Boolean
) {
    /**
     * Whether this [PullToRefreshState] is currently refreshing or not.
     */
    var isRefreshing by mutableStateOf(isRefreshing)

    /**
     * Internal pull state
     */
    internal var pullState: PullState by mutableStateOf(if (isRefreshing) PullState.REFRESHING else PullState.IDLE)

    /**
     * Pull progress from 0.0 to 1.0
     */
    var pullProgress: Float by mutableStateOf(0f)
        internal set

    /**
     * Whether a pull is currently in progress
     */
    val isPullInProgress: Boolean
        get() = pullState != PullState.IDLE

    internal fun updateProgress(progress: Float) {
        pullProgress = progress.coerceIn(0f, 1f)
    }

    internal fun updatePullState(newState: PullState) {
        pullState = newState
    }
}

/**
 * Pull-to-refresh component that should be placed as the first item in LazyColumn.
 * 
 * Usage:
 * ```
 * val pullToRefreshState = rememberPullToRefreshState(isRefreshing)
 * val lazyListState = rememberLazyListState()
 * 
 * LazyColumn(state = lazyListState) {
 *     pullToRefreshItem(
 *         state = pullToRefreshState,
 *         onRefresh = { /* refresh logic */ },
 *         scrollState = lazyListState
 *     )
 *     
 *     items(data) { item ->
 *         // Business content
 *     }
 * }
 * ```
 * 
 * @param state Pull-to-refresh state
 * @param onRefresh Refresh callback
 * @param scrollState LazyListState for monitoring scroll state
 * @param modifier Modifier
 * @param topInset Extra top inset for overlay header (e.g. collapsing HeaderBar).
 *   Pass the header's maximum height, not its animated height.
 * @param refreshThreshold Threshold to trigger refresh
 * @param holdRefreshInset Whether the list should keep [refreshThreshold] top inset while
 *   [state] is refreshing. Disable this when refresh progress is rendered outside the list and
 *   existing content must return to its resting position immediately after the pull is released.
 *   Runtime changes to this value and [refreshThreshold] take effect without replacing
 *   [scrollState].
 * @param content Custom refresh indicator content
 */
fun LazyListScope.pullToRefreshItem(
    state: PullToRefreshState,
    onRefresh: () -> Unit,
    scrollState: LazyListState,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    refreshThreshold: Dp = 80.dp,
    holdRefreshInset: Boolean = true,
    content: @Composable (
        pullProgress: Float,
        isRefreshing: Boolean,
        refreshThreshold: Dp
    ) -> Unit = { progress, refreshing, threshold ->
        DefaultRefreshIndicator(progress, refreshing, threshold)
    }
) {
    // Mark that the current list uses PullToRefresh
    scrollState.kuiklyInfo.hasPullToRefresh = true
    
    item(key = "pull_to_refresh") {
        PullToRefreshItem(
            state = state,
            onRefresh = onRefresh,
            scrollState = scrollState,
            modifier = modifier,
            topInset = topInset,
            refreshThreshold = refreshThreshold,
            holdRefreshInset = holdRefreshInset,
            content = content
        )
    }
}

/**
 * Internal pull-to-refresh component implementation.
 * Use [LazyListScope.pullToRefreshItem] instead for easier usage.
 */
@Composable
internal fun PullToRefreshItem(
    state: PullToRefreshState,
    onRefresh: () -> Unit,
    scrollState: LazyListState,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    refreshThreshold: Dp = 80.dp,
    holdRefreshInset: Boolean = true,
    content: @Composable (
        pullProgress: Float,
        isRefreshing: Boolean,
        refreshThreshold: Dp
    ) -> Unit = { progress, refreshing, threshold ->
        DefaultRefreshIndicator(progress, refreshing, threshold)
    }
) {
    val density = LocalDensity.current
    val refreshThresholdPx = with(density) { refreshThreshold.toPx() }
    val refreshThresholdLogical = refreshThresholdPx / density.density
    val updatedOnRefresh by rememberUpdatedState(onRefresh)
    val updatedRuntimeConfig = rememberUpdatedState(
        PullToRefreshRuntimeConfig(
            holdRefreshInset = holdRefreshInset,
            refreshThresholdPx = refreshThresholdPx,
            refreshThresholdLogical = refreshThresholdLogical
        )
    )
    val snapshotProvider = remember(scrollState) {
        PullToRefreshSnapshotProvider(updatedRuntimeConfig)
    }

    scrollState.kuiklyInfo.pullToRefreshTopInsetPx = with(density) { topInset.roundToPx() }

    // Monitor scroll state changes inspired by RefreshView logic
    LaunchedEffect(scrollState) {
        snapshotFlow {
            val kuiklyInfo = scrollState.kuiklyInfo
            val isAtTop = scrollState.isAtTop()
            val contentOffset = if (isAtTop) kuiklyInfo.contentOffset else 0
            val isDragging = scrollState.kuiklyInfo.isDragging
            snapshotProvider.snapshot(
                contentOffset = contentOffset,
                isAtTop = isAtTop,
                isDragging = isDragging,
                isRefreshing = state.isRefreshing
            )
        }
        .distinctUntilChanged()
        .collectLatest { snapshot ->
            val contentOffset = snapshot.contentOffset
            val isAtTop = snapshot.isAtTop
            val isDragging = snapshot.isDragging
            val currentHoldRefreshInset = snapshot.holdRefreshInset
            val currentRefreshThresholdPx = snapshot.refreshThresholdPx
            val previousPullState = state.pullState
            if (!isAtTop) {
                // Reset state when not at top
                if (state.pullState != PullState.IDLE) {
                    pullToRefreshLog {
                        "leave top: contentOffset=$contentOffset isDragging=$isDragging " +
                            "pullState $previousPullState -> IDLE"
                    }
                    state.updatePullState(PullState.IDLE)
                    state.updateProgress(0f)
                    val scrollViewOnLeave = scrollState.kuiklyInfo.scrollView
                    scrollState.setPullToRefreshContentInsetWhenEndDrag(top = 0f)
                    if (scrollViewOnLeave?.isDragging != true) {
                        scrollState.setPullToRefreshContentInset(top = 0f, animated = false)
                    }
                }
                return@collectLatest
            }

            // Handle pull logic when at top
            val scrollView = scrollState.kuiklyInfo.scrollView
            val pullDistance = snapshot.pullDistance
            val progress = snapshot.progress
            
            state.updateProgress(progress)

            when (state.pullState) {
                PullState.REFRESHING -> {
                    // Failsafe for StateFlow conflation: when isRefreshing round-trips
                    // (true -> false) within a single frame interval, Compose may never
                    // observe the intermediate true value. This leaves pullState stuck
                    // in REFRESHING. Detect and force reset to IDLE.
                    if (!state.isRefreshing) {
                        state.updatePullState(PullState.IDLE)
                        state.updateProgress(0f)
                    }
                }
                PullState.IDLE -> {
                    val pullStarted = state.startPullToRefresh(
                        snapshot = snapshot,
                        setEndDragInset = { inset ->
                            scrollState.setPullToRefreshContentInsetWhenEndDrag(top = inset)
                        }
                    )
                    if (pullStarted) {
                        pullToRefreshLog {
                            "IDLE -> PULLING: offset=$contentOffset pullDistance=$pullDistance " +
                                "progress=$progress thresholdPx=$currentRefreshThresholdPx"
                        }
                    }
                }
                PullState.PULLING -> {
                    if (isDragging) {
                        if (!snapshot.isThresholdReached) {
                            pullToRefreshLog {
                                "PULLING -> IDLE (drag, below threshold): offset=$contentOffset " +
                                    "pullDistance=$pullDistance progress=$progress"
                            }
                            state.updatePullState(PullState.IDLE)
                            scrollState.setPullToRefreshContentInsetWhenEndDrag(top = 0f)
                        } else {
                            scrollState.setPullToRefreshContentInsetWhenEndDrag(
                                top = snapshot.endDragInset
                            )
                        }
                    } else {
                        // Released while pulling, start refresh
                        val releasedState = pullStateAfterRefreshRelease(currentHoldRefreshInset)
                        pullToRefreshLog {
                            "PULLING -> $releasedState (release): offset=$contentOffset " +
                                "pullDistance=$pullDistance holdRefreshInset=$currentHoldRefreshInset"
                        }
                        state.releasePullToRefresh(
                            snapshot = snapshot,
                            clearEndDragInset = {
                                scrollState.setPullToRefreshContentInsetWhenEndDrag(top = 0f)
                            },
                            clearCurrentInset = {
                                scrollState.setPullToRefreshContentInset(top = 0f, animated = false)
                            },
                            onRefresh = updatedOnRefresh
                        )
                    }
                }
            }
            if (state.pullState != previousPullState) {
                pullToRefreshLog {
                    "state=$previousPullState -> ${state.pullState}: offset=$contentOffset " +
                        "pullDistance=$pullDistance progress=$progress isDragging=$isDragging"
                }
            }
        }
    }

    // Handle inset changes based on pull state
    LaunchedEffect(state.pullState, holdRefreshInset, refreshThresholdLogical) {
        val scrollView = scrollState.kuiklyInfo.scrollView
        val isDragging = scrollView?.isDragging == true
        when (state.pullState) {
            PullState.REFRESHING -> {
                if (holdRefreshInset) {
                    pullToRefreshLog { "apply inset REFRESHING top=$refreshThresholdLogical animated=true" }
                    scrollState.setPullToRefreshContentInset(
                        top = refreshThresholdLogical,
                        animated = true,
                    )
                } else {
                    pullToRefreshLog { "skip inset REFRESHING holdRefreshInset=false" }
                    scrollState.setPullToRefreshContentInsetWhenEndDrag(top = 0f)
                    scrollState.setPullToRefreshContentInset(top = 0f, animated = false)
                }
            }
            PullState.IDLE -> {
                // Never apply contentInset while dragging:
                // - iOS: animated inset also animates contentOffset back to bounds
                // - Android: non-animated inset calls setFinalTranslation and snaps overscroll to 0
                scrollState.setPullToRefreshContentInsetWhenEndDrag(top = 0f)
                if (!isDragging) {
                    pullToRefreshLog { "apply inset IDLE top=0 animated=true isDragging=false" }
                    scrollState.setPullToRefreshContentInset(top = 0f, animated = true)
                } else {
                    pullToRefreshLog { "defer inset IDLE reset until drag end" }
                }
            }
            PullState.PULLING -> {
                // Handled by EndDragInset
            }
        }
    }

    // Android/iOS: apply deferred inset reset after drag ends in IDLE
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.kuiklyInfo.scrollView?.isDragging ?: false }
            .distinctUntilChanged()
            .collect { isDragging ->
                if (!isDragging && state.pullState == PullState.IDLE) {
                    pullToRefreshLog { "drag end: apply inset IDLE top=0 animated=true" }
                    scrollState.setPullToRefreshContentInset(top = 0f, animated = true)
                }
            }
    }

    // Sync external refresh state
    LaunchedEffect(state.isRefreshing, holdRefreshInset) {
        if (state.isRefreshing && holdRefreshInset) {
            if (state.pullState != PullState.REFRESHING) {
                state.updatePullState(PullState.REFRESHING)
            }
        } else {
            if (state.pullState == PullState.REFRESHING) {
                state.updatePullState(PullState.IDLE)
                state.updateProgress(0f)
            }
        }
    }

    // Refresh indicator UI
    Box(
        modifier = modifier
            .then(if (topInset > 0.dp) Modifier.padding(top = topInset) else Modifier)
            .offsetWithParentAdjustment(y = -refreshThreshold)
            .fillMaxWidth()
            .height(refreshThreshold),
        contentAlignment = Alignment.Center
    ) {
        content(state.pullProgress, state.isRefreshing, refreshThreshold)
    }
}

internal fun pullStateAfterRefreshRelease(holdRefreshInset: Boolean): PullState =
    if (holdRefreshInset) PullState.REFRESHING else PullState.IDLE

internal fun pullRefreshEndDragInset(
    holdRefreshInset: Boolean,
    refreshThreshold: Float
): Float =
    if (holdRefreshInset) refreshThreshold.coerceAtLeast(0f) else 0f

internal fun PullToRefreshState.startPullToRefresh(
    snapshot: PullToRefreshSnapshot,
    setEndDragInset: (Float) -> Unit
): Boolean {
    if (pullState != PullState.IDLE || !snapshot.isDragging || !snapshot.isThresholdReached) {
        return false
    }
    updatePullState(PullState.PULLING)
    setEndDragInset(snapshot.endDragInset)
    return true
}

internal fun PullToRefreshState.releasePullToRefresh(
    snapshot: PullToRefreshSnapshot,
    clearEndDragInset: () -> Unit,
    clearCurrentInset: () -> Unit,
    onRefresh: () -> Unit
): Boolean {
    if (pullState != PullState.PULLING) {
        return false
    }
    updatePullState(pullStateAfterRefreshRelease(snapshot.holdRefreshInset))
    if (!snapshot.holdRefreshInset) {
        updateProgress(0f)
        clearEndDragInset()
        clearCurrentInset()
    }
    onRefresh()
    return true
}

/**
 * Default refresh indicator
 */
@Composable
private fun DefaultRefreshIndicator(
    pullProgress: Float,
    isRefreshing: Boolean,
    refreshThreshold: Dp
) {
    if (isRefreshing) {
        Text(
            text = "Refreshing...",
            fontSize = 16.sp,
            color = Color.Blue,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(16.dp)
        )
    } else {
        val icon = if (pullProgress >= 1f) "↑" else "↓"
        val text = if (pullProgress >= 1f) "Release to refresh" else "Pull to refresh"
        
        Text(
            text = "$icon $text",
            fontSize = 14.sp,
            color = Color.Gray,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(16.dp)
        )
    }
}
