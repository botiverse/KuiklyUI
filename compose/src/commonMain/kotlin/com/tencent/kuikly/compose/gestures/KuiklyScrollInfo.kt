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

package com.tencent.kuikly.compose.gestures

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.coroutines.internal.KuiklyContextScheduler
import com.tencent.kuikly.compose.foundation.drawer.MoveableDrawerDiagnosticClock
import com.tencent.kuikly.compose.foundation.drawer.MoveableDrawerDiagnosticEvent
import com.tencent.kuikly.compose.foundation.drawer.MoveableDrawerDiagnosticIds
import com.tencent.kuikly.compose.foundation.gestures.Orientation
import com.tencent.kuikly.compose.ui.node.StickyHeaderCacheManager
import com.tencent.kuikly.compose.ui.unit.IntOffset
import com.tencent.kuikly.core.layout.Frame
import com.tencent.kuikly.core.manager.BridgeManager
import com.tencent.kuikly.core.pager.PageData
import com.tencent.kuikly.core.views.ScrollerAttr
import com.tencent.kuikly.core.views.ScrollerEvent
import com.tencent.kuikly.core.views.ScrollerView
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

internal class ScrollViewBindingGate<T : Any> {
    internal data class Binding<T : Any>(val id: Long, val value: T)

    private val binding = MutableStateFlow<Binding<T>?>(null)

    var current: Binding<T>? = null
        private set

    fun update(value: T?) {
        val next = when {
            value == null -> null
            current?.value === value -> current
            else -> Binding(MoveableDrawerDiagnosticIds.next(), value)
        }
        current = next
        binding.value = next
    }

    suspend fun <R> withCurrentBinding(block: (T) -> R): R {
        return withCurrentBindingSnapshot { block(it.value) }
    }

    suspend fun <R> withCurrentBindingSnapshot(block: (Binding<T>) -> R): R {
        while (true) {
            val candidate = binding.filterNotNull().first()
            if (current === candidate) {
                return block(candidate)
            }
        }
    }
}

internal class DeferredScrollOffsetAlignmentCoordinator<T>(
    private val pendingAlignment: () -> T?,
    private val updatePendingAlignment: (T?) -> Unit
) {
    private var generation = 0L

    fun replacePendingAlignment(
        cancelPendingAlignment: (T) -> Unit,
        launchAlignment: (DeferredScrollOffsetAlignmentRequest) -> T?
    ) {
        val request = DeferredScrollOffsetAlignmentRequest(++generation)
        pendingAlignment()?.let(cancelPendingAlignment)
        updatePendingAlignment(launchAlignment(request))
    }

    fun isCurrent(request: DeferredScrollOffsetAlignmentRequest): Boolean {
        return request.generation == generation
    }

    fun cancelAndInvalidate(cancelPendingAlignment: (T) -> Unit) {
        generation += 1
        pendingAlignment()?.let(cancelPendingAlignment)
        updatePendingAlignment(null)
    }

    fun retryAfterScrollEnd(scheduleAlignment: () -> Unit) {
        scheduleAlignment()
    }
}

internal fun <T> invalidateDeferredScrollOffsetAlignmentOwnersOnReuse(
    oldCoordinator: DeferredScrollOffsetAlignmentCoordinator<T>?,
    newCoordinator: DeferredScrollOffsetAlignmentCoordinator<T>,
    cancelPendingAlignment: (T) -> Unit
) {
    oldCoordinator?.cancelAndInvalidate(cancelPendingAlignment)
    if (newCoordinator !== oldCoordinator) {
        newCoordinator.cancelAndInvalidate(cancelPendingAlignment)
    }
}

internal class DeferredScrollOffsetAlignmentRequest internal constructor(
    internal val generation: Long
)

/**
 * Scroll information management class, responsible for handling scroll-related state and calculations
 */
class KuiklyScrollInfo {
    companion object {
        private const val DEFAULT_CONTENT_SIZE = 3000
        private const val SCROLL_BOTTOM_THRESHOLD = 100
        private const val DEFAULT_DENSITY = 3f
    }

    private val scrollViewBinding =
        ScrollViewBindingGate<ScrollerView<ScrollerAttr, ScrollerEvent>>()

    internal val diagnosticOwnerId: Long = MoveableDrawerDiagnosticIds.next()
    internal var diagnosticPagerOwnerId: Long = 0L
    internal var diagnosticCommandGeneration: Long = 0L
    internal var diagnosticObserver: ((MoveableDrawerDiagnosticEvent) -> Unit)? = null

    internal val diagnosticBindingId: Long
        get() = scrollViewBinding.current?.id ?: 0L

    internal fun emitDrawerDiagnostic(stage: String, detail: String = "") {
        diagnosticObserver?.invoke(
            MoveableDrawerDiagnosticEvent(
                monotonicNanos = MoveableDrawerDiagnosticClock.nowNanos(),
                stage = stage,
                commandGeneration = diagnosticCommandGeneration,
                bindingId = diagnosticBindingId,
                pagerOwnerId = diagnosticPagerOwnerId,
                scrollInfoOwnerId = diagnosticOwnerId,
                detail = detail
            )
        )
    }

    /**
     * Scroll offset that needs to be ignored
     */
    var ignoreScrollOffset: IntOffset? = null

    internal fun consumeIgnoredScrollOffset(
        offsetX: Float,
        offsetY: Float,
        epsilon: Double,
    ): Boolean {
        val ignoredOffset = ignoreScrollOffset ?: return false
        val matched = kotlin.math.abs(ignoredOffset.x - offsetX) <= epsilon &&
            kotlin.math.abs(ignoredOffset.y - offsetY) <= epsilon
        ignoreScrollOffset = null
        return matched
    }

    /**
     * Disposition of a native scroll callback relative to a pending programmatic
     * offset move ([ignoreScrollOffset]).
     *
     * A programmatic move ([applyOffsetDelta]) can land somewhere other than its
     * recorded target: the native scroller clamps against its own (asynchronously
     * updated) content size, or splits one move into several callbacks. Such an
     * off-target callback is still an echo of our own move — never user input.
     * Interpreting it as a user scroll dispatches a large phantom delta into
     * compose; on a bottom-anchored list whose content size is still estimated
     * this feeds the expand/align retry loop and serially composes every row up
     * to the list start, blocking the Kotlin thread for seconds (task #318).
     */
    internal enum class NativeScrollEventDisposition {
        /** Exact echo of the programmatic move: drop the event entirely. */
        Consume,
        /** Off-target echo of the programmatic move: adopt the reported offset
         *  into bookkeeping, but never dispatch a compose scroll. */
        SyncOnly,
        /** Genuine scroll: dispatch to compose. */
        Dispatch
    }

    internal fun resolveNativeScrollEvent(
        offsetX: Float,
        offsetY: Float,
        epsilon: Double,
    ): NativeScrollEventDisposition {
        val hadPendingProgrammaticMove = ignoreScrollOffset != null
        val matched = consumeIgnoredScrollOffset(offsetX, offsetY, epsilon)
        return when {
            matched -> NativeScrollEventDisposition.Consume
            hadPendingProgrammaticMove && !isDragging -> NativeScrollEventDisposition.SyncOnly
            else -> NativeScrollEventDisposition.Dispatch
        }
    }

    /**
     * Scroll view instance
     */
    var scrollView: ScrollerView<ScrollerAttr, ScrollerEvent>? = null
        set(value) {
            val oldBindingId = diagnosticBindingId
            field = value
            scrollViewBinding.update(value)
            emitDrawerDiagnostic(
                stage = if (value == null) "binding_clear" else "binding_update",
                detail = "oldBindingId=$oldBindingId newBindingId=$diagnosticBindingId"
            )
            if (hasPullToRefresh && value != null) {
                updatePullToRefreshOnScrollView(value, true)
            }
        }

    internal suspend fun <R> withCurrentScrollViewBinding(
        block: (ScrollerView<ScrollerAttr, ScrollerEvent>, Long) -> R
    ): R = scrollViewBinding.withCurrentBindingSnapshot { block(it.value, it.id) }

    /**
     * Scroll orientation
     */
    var orientation: Orientation = Orientation.Vertical

    /**
     * Offset on the Compose side, does not exceed boundaries
     */
    var composeOffset = 0f

    /**
     * Temporary native-coordinate correction used while a Pager snap animation is running.
     * When items are inserted before the snap target, this keeps the target item's native frame
     * anchored to the original snap target offset until the snap settles.
     */
    var snapAnchorOffsetCorrection = 0

    /**
     * Current contentView size, used to expand the bottom boundary
     */
    var currentContentSize by mutableStateOf((DEFAULT_CONTENT_SIZE * getDensity()).toInt())

    /**
     * Real contentSize after scrolling to the bottom
     */
    var realContentSize: Int? = null

    /**
     * Whether the offset has deviation
     */
    var offsetDirty = false

    /**
     * ScrollView's scroll offset
     */
    var contentOffset: Int by mutableStateOf(0)

    /**
     * ScrollView is dragging
     */
    var isDragging: Boolean by mutableStateOf(false)

    /**
     * List height cache
     */
    internal var itemMainSpaceCache = hashMapOf<Any, Int>()

    /**
     * Used to track delayed execution of applyScrollViewOffsetDelta tasks
     */
    internal var appleScrollViewOffsetJob: Job? = null

    internal val deferredScrollOffsetAlignmentCoordinator =
        DeferredScrollOffsetAlignmentCoordinator(
            pendingAlignment = { appleScrollViewOffsetJob },
            updatePendingAlignment = { appleScrollViewOffsetJob = it }
        )

    /**
     * Coroutine scope
     */
    internal var scope: CoroutineScope? = null

    /**
     * PageData related data
     */
    var pageData: PageData? = null

    /**
     * The key of the current sticky item, used to identify which item is in sticky state
     * In LazyList, when an item is set as sticky, its key will be stored here
     * KNode can determine if it's a sticky node by comparing its own slotId with this key
     */
    var stickyItemKey: Any? = null

    /**
     * Flag indicating whether the current list uses PullToRefresh
     * When PullToRefresh is used, the isAtTop judgment logic needs to be adjusted
     */
    var hasPullToRefresh: Boolean = false
        set(value) {
            field = value
            scrollView?.let { updatePullToRefreshOnScrollView(it, value) }
        }

    private fun updatePullToRefreshOnScrollView(
        targetScrollView: ScrollerView<ScrollerAttr, ScrollerEvent>,
        enabled: Boolean
    ) {
        val pagerId = targetScrollView.pagerId.ifEmpty { BridgeManager.currentPageId }
        fun applyIfCurrent() {
            if (scrollView === targetScrollView && hasPullToRefresh == enabled) {
                targetScrollView.setHasPullToRefresh(enabled)
            }
        }
        if (KuiklyContextScheduler.isOnKuiklyThread(pagerId)) {
            applyIfCurrent()
            return
        }
        if (pagerId.isEmpty()) {
            return
        }
        KuiklyContextScheduler.runOnKuiklyThread(pagerId) { cancel ->
            if (!cancel) {
                applyIfCurrent()
            }
        }
    }

    /**
     * Extra top inset on the pull-to-refresh lazy item in pixels,
     * from [com.tencent.kuikly.compose.material3.pullToRefreshItem.topInset].
     */
    var pullToRefreshTopInsetPx: Int = 0

    /**
     * Cached total number of items, used to detect changes in item count
     */
    var cachedTotalItems: Int = 0

    /**
     * When true, [tryExpandStartSize] is skipped. Used by [ScrollableTabRow] whose content
     * size is already exact via [ScrollState.maxValue] + viewport.
     */
    var skipExpandStartSize: Boolean = false

    /**
     * Sticky Header Position Cache Manager
     */
    val stickyHeaderCacheManager = StickyHeaderCacheManager()

    /**
     * Scroll to top event callback.
     * If set, the callback will be invoked instead of the default scroll to top behavior.
     * This aligns with iOS behavior where scrollToTop event can be intercepted.
     */
    var scrollToTopCallback: (() -> Unit)? = null

    /**
     * Update content size to render view
     */
    fun updateContentSizeToRender() {
        val frame = createContentFrame()
        scrollView?.contentView?.setFrameToRenderView(frame)
    }

    /**
     * Reset scroll-related state when binding to a new ScrollView (e.g., when LazyColumn's key changes and causes rebuild)
     * Note: This depends on scrollView to get density, so it should be called after setting scrollView
     */
    fun resetForNewScrollView() {
        // Cancel and clear any pending tasks
        deferredScrollOffsetAlignmentCoordinator.cancelAndInvalidate { it.cancel() }

        // Reset basic offset and scroll state
        ignoreScrollOffset = null
        composeOffset = 0f
        contentOffset = 0
        isDragging = false
        offsetDirty = false

        // Reset content size related (reinitialize based on current density)
        currentContentSize = (DEFAULT_CONTENT_SIZE * getDensity()).toInt()
        realContentSize = null

        // Clear list items and pagination caches
        itemMainSpaceCache.clear()
        stickyItemKey = null
        cachedTotalItems = 0
        pullToRefreshTopInsetPx = 0
    }

    /**
     * Create content Frame
     */
    private fun createContentFrame(): Frame {
        return if (isVertical()) {
            Frame(
                x = 0f,
                y = 0f,
                width = scrollView?.renderView?.currentFrame?.width ?: 0f,
                height = currentContentSize / getDensity()
            )
        } else {
            Frame(
                x = 0f,
                y = 0f,
                width = currentContentSize / getDensity(),
                height = scrollView?.renderView?.currentFrame?.height ?: 0f
            )
        }
    }

    /**
     * Get viewport size
     */
    val viewportSize: Int
        get() {
            val size = if (isVertical()) {
                scrollView?.renderView?.currentFrame?.height ?: 0f
            } else {
                scrollView?.renderView?.currentFrame?.width ?: 0f
            }
            // Use roundToInt instead of toInt to avoid truncating the dp→px conversion.
            // A non-integer density (e.g. 2.625) makes the truncated viewportSize lose ~1px,
            // which keeps toButtomDelta at 1 instead of 0 and breaks the bottom overscroll
            // bounce handling (lastScrolledBackward wrongly set to true).
            return (size * getDensity()).roundToInt()
        }

    /**
     * Get density
     */
    fun getDensity(): Float {
        return scrollView?.getPager()?.pagerDensity() ?: DEFAULT_DENSITY
    }

    /**
     * Check if it's vertical scrolling
     */
    fun isVertical(): Boolean = orientation == Orientation.Vertical

    /**
     * Check if it's near the bottom of scrolling
     */
    fun nearScrollBottom(): Boolean {
        val threshold = SCROLL_BOTTOM_THRESHOLD * getDensity()
        return contentOffset + viewportSize + threshold > currentContentSize
    }
}
