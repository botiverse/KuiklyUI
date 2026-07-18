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
import com.tencent.kuikly.compose.foundation.gestures.Orientation
import com.tencent.kuikly.compose.ui.node.StickyHeaderCacheManager
import com.tencent.kuikly.compose.ui.unit.IntOffset
import com.tencent.kuikly.core.layout.Frame
import com.tencent.kuikly.core.datetime.DateTime
import com.tencent.kuikly.core.manager.BridgeManager
import com.tencent.kuikly.core.pager.PageData
import com.tencent.kuikly.core.views.ScrollerAttr
import com.tencent.kuikly.core.views.ScrollerEvent
import com.tencent.kuikly.core.views.ScrollerView
import kotlin.math.roundToInt
import com.tencent.kuikly.core.views.NativeScrollPhase
import com.tencent.kuikly.core.views.ScrollOffsetCommitToken
import com.tencent.kuikly.core.views.ScrollWriteReplayPolicy
import com.tencent.kuikly.core.views.ScrollWriteOperationKey
import com.tencent.kuikly.core.views.ScrollWriteResourceCell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class DeferredScrollOffsetAlignmentCoordinator<T>(
    private val pendingAlignment: () -> T?,
    private val updatePendingAlignment: (T?) -> Unit
) {
    private var alignmentGeneration = 0L
    private var ownerGeneration = 0L
    private val retryOperationGenerations = mutableMapOf<String, Long>()
    private val pendingRetryOperations = mutableMapOf<String, DeferredScrollOffsetRetryOperation>()

    fun replacePendingAlignment(
        retryOperationKey: String? = null,
        cancelPendingAlignment: (T) -> Unit,
        launchAlignment: (DeferredScrollOffsetAlignmentRequest) -> T?
    ) {
        val request = DeferredScrollOffsetAlignmentRequest(++alignmentGeneration)
        retryOperationKey?.let(::clearRetryOperation)
        pendingAlignment()?.let(cancelPendingAlignment)
        if (!isCurrent(request)) return
        val launched = launchAlignment(request)
        if (!isCurrent(request)) {
            launched?.let(cancelPendingAlignment)
            return
        }
        updatePendingAlignment(launched)
    }

    fun isCurrent(request: DeferredScrollOffsetAlignmentRequest): Boolean {
        return request.generation == alignmentGeneration
    }

    fun isCurrent(request: DeferredScrollOffsetRetryRequest): Boolean {
        return request.ownerGeneration == ownerGeneration &&
            retryOperationGenerations[request.key] == request.operationGeneration
    }

    fun cancelAndInvalidate(cancelPendingAlignment: (T) -> Unit) {
        alignmentGeneration += 1
        val previous = pendingAlignment()
        updatePendingAlignment(null)
        previous?.let(cancelPendingAlignment)
        invalidateRetryOperations()
    }

    fun requestRetryAfterScrollEnd(
        key: String,
        interactionEpoch: Long? = null,
        onInvalidated: (() -> Unit)? = null,
        operation: (DeferredScrollOffsetRetryRequest) -> Unit,
    ) {
        val request = beginRetryOperation(key, interactionEpoch)
        requestRetryAfterScrollEnd(request, onInvalidated, operation)
    }

    fun beginRetryOperation(
        key: String,
        interactionEpoch: Long? = null,
    ): DeferredScrollOffsetRetryRequest {
        val operationGeneration = (retryOperationGenerations[key] ?: 0L) + 1L
        retryOperationGenerations[key] = operationGeneration
        val replaced = pendingRetryOperations.remove(key)
        replaced?.onInvalidated?.invoke()
        return DeferredScrollOffsetRetryRequest(
            key = key,
            ownerGeneration = ownerGeneration,
            operationGeneration = operationGeneration,
            interactionEpoch = interactionEpoch,
        )
    }

    fun requestRetryAfterScrollEnd(
        request: DeferredScrollOffsetRetryRequest,
        onInvalidated: (() -> Unit)? = null,
        operation: (DeferredScrollOffsetRetryRequest) -> Unit,
    ) {
        if (!isCurrent(request)) return
        pendingRetryOperations[request.key] = DeferredScrollOffsetRetryOperation(
            ownerGeneration = request.ownerGeneration,
            operationGeneration = request.operationGeneration,
            interactionEpoch = request.interactionEpoch,
            operation = operation,
            onInvalidated = onInvalidated,
        )
    }

    fun requestRetryAfterScrollEnd(
        request: DeferredScrollOffsetAlignmentRequest,
        key: String,
        interactionEpoch: Long? = null,
        onInvalidated: (() -> Unit)? = null,
        operation: (DeferredScrollOffsetRetryRequest) -> Unit,
    ) {
        if (isCurrent(request)) {
            requestRetryAfterScrollEnd(key, interactionEpoch, onInvalidated, operation)
        }
    }

    fun clearRetryOperation(key: String) {
        retryOperationGenerations[key] = (retryOperationGenerations[key] ?: 0L) + 1L
        pendingRetryOperations.remove(key)
    }

    fun invalidateRetryOperation(key: String) {
        takeRetryOperationInvalidation(key)?.invoke()
    }

    fun takeRetryOperationInvalidation(key: String): (() -> Unit)? {
        retryOperationGenerations[key] = (retryOperationGenerations[key] ?: 0L) + 1L
        val invalidated = pendingRetryOperations.remove(key)
        return invalidated?.onInvalidated
    }

    fun discardPendingRetryOperation(request: DeferredScrollOffsetRetryRequest) {
        if (isCurrent(request)) {
            pendingRetryOperations.remove(request.key)
        }
    }

    fun completeRetryOperation(request: DeferredScrollOffsetRetryRequest) {
        if (isCurrent(request)) {
            clearRetryOperation(request.key)
        }
    }

    fun invalidateRetryOperations() {
        ownerGeneration += 1
        val invalidated = pendingRetryOperations.values.toList()
        pendingRetryOperations.clear()
        retryOperationGenerations.clear()
        invalidated.forEach { it.onInvalidated?.invoke() }
    }

    fun retryAfterScrollEnd(interactionEpoch: Long? = null): Int {
        val operations = pendingRetryOperations.mapNotNull { (key, pending) ->
            if (pending.ownerGeneration != ownerGeneration) return@mapNotNull null
            if (interactionEpoch != null && pending.interactionEpoch != null &&
                pending.interactionEpoch != interactionEpoch
            ) {
                return@mapNotNull null
            }
            val request = DeferredScrollOffsetRetryRequest(
                key = key,
                ownerGeneration = pending.ownerGeneration,
                operationGeneration = pending.operationGeneration,
                interactionEpoch = pending.interactionEpoch,
            )
            request to pending.operation
        }
        operations.forEach { (request, _) -> pendingRetryOperations.remove(request.key) }
        operations.forEach { (request, operation) ->
            if (isCurrent(request)) {
                operation(request)
            }
        }
        return operations.size
    }
}

private data class DeferredScrollOffsetRetryOperation(
    val ownerGeneration: Long,
    val operationGeneration: Long,
    val interactionEpoch: Long?,
    val operation: (DeferredScrollOffsetRetryRequest) -> Unit,
    val onInvalidated: (() -> Unit)?,
)

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

internal class DeferredScrollOffsetRetryRequest internal constructor(
    internal val key: String,
    internal val ownerGeneration: Long,
    internal val operationGeneration: Long,
    internal val interactionEpoch: Long? = null,
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

    /**
     * Scroll offset that needs to be ignored
     */
    var ignoreScrollOffset: IntOffset? = null
        set(value) {
            field = value
            ignoreScrollOffsetWriter = null
        }
    private var ignoreScrollOffsetWriter: ScrollWriteOperationKey? = null
    private data class ChildFrameWriteResource(
        val cell: ScrollWriteResourceCell<Frame>,
        val currentFrame: () -> Frame,
        val applyFrame: (Frame) -> Unit,
    )

    private val childFrameWriteResources = mutableMapOf<Any, ChildFrameWriteResource>()
    private var provisionalContentSizeWriter: ScrollWriteOperationKey? = null
    private var scrollWriteQuarantined = false
    private val interactionWatchdogJobs = mutableMapOf<Long, Job>()
    private val retryDeadlineJobs = mutableMapOf<Long, Job>()
    private val revisionWaiters = mutableMapOf<Long, Pair<ScrollerView<ScrollerAttr, ScrollerEvent>, Long>>()
    private val scrollWriteInvalidationTerminals = mutableMapOf<Long, () -> Unit>()

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

    internal fun installIgnoreScrollOffset(
        operation: ScrollWriteOperationKey,
        offset: IntOffset,
    ) {
        ignoreScrollOffset = offset
        ignoreScrollOffsetWriter = operation
    }

    internal fun clearIgnoreScrollOffset(operation: ScrollWriteOperationKey) {
        if (ignoreScrollOffsetWriter == operation) {
            ignoreScrollOffset = null
        }
    }

    internal fun childFrameWriteCell(
        resource: Any,
        currentFrame: Frame,
        currentFrameProvider: () -> Frame,
        applyFrame: (Frame) -> Unit,
    ): ScrollWriteResourceCell<Frame> {
        val writeResource = childFrameWriteResources.getOrPut(resource) {
            ChildFrameWriteResource(
                cell = ScrollWriteResourceCell(currentFrame),
                currentFrame = currentFrameProvider,
                applyFrame = applyFrame,
            )
        }
        writeResource.cell.refreshCommittedIfIdle(currentFrame)
        return writeResource.cell
    }

    internal fun commitOrdinaryChildFrameWrite(
        resource: Any,
        previousFrame: Frame,
        newFrame: Frame,
        currentFrameProvider: () -> Frame,
        applyFrame: (Frame) -> Unit,
    ) {
        val writeResource = childFrameWriteResources.getOrPut(resource) {
            ChildFrameWriteResource(
                cell = ScrollWriteResourceCell(previousFrame),
                currentFrame = currentFrameProvider,
                applyFrame = applyFrame,
            )
        }
        writeResource.cell.commitExternal(newFrame)
    }

    private fun resetChildFrameWritesToCommitted(): Boolean {
        var restored = true
        childFrameWriteResources.values.forEach { resource ->
            val committedFrame = resource.cell.resetToCommitted()
            if (resource.currentFrame() != committedFrame) {
                resource.applyFrame(committedFrame)
            }
            if (resource.currentFrame() != committedFrame) {
                restored = false
            }
        }
        if (restored) childFrameWriteResources.clear()
        return restored
    }

    internal fun installProvisionalContentSize(
        operation: ScrollWriteOperationKey,
        value: Int,
    ): Int {
        val previous = currentContentSizeState
        provisionalContentSizeWriter = operation
        if (currentContentSizeState != value) {
            currentContentSizeState = value
            rangeRevision += 1L
        }
        updateContentSizeToRenderPreservingWriter()
        return previous
    }

    internal fun ownsProvisionalContentSize(operation: ScrollWriteOperationKey): Boolean =
        provisionalContentSizeWriter == operation

    internal fun finalizeProvisionalContentSize(operation: ScrollWriteOperationKey): Boolean {
        if (provisionalContentSizeWriter != operation) return false
        provisionalContentSizeWriter = null
        return true
    }

    internal fun rollbackProvisionalContentSize(
        operation: ScrollWriteOperationKey,
        previous: Int,
    ): Boolean {
        if (provisionalContentSizeWriter != operation) return false
        provisionalContentSizeWriter = null
        if (currentContentSizeState != previous) {
            currentContentSizeState = previous
            rangeRevision += 1L
        }
        updateContentSizeToRenderPreservingWriter()
        return true
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
        private set

    private var scrollViewBindingGeneration = 0L
    private var scrollOffsetSemanticOperationId = 0L
    private var scrollOffsetAttemptGeneration = 0L
    private var scrollOffsetCapabilityGeneration = 0L
    private var lastNativeInteractionEpoch = -1L
    private var activeScrollOffsetCapability: ScrollOffsetWriteCapability? = null
    private val scrollOffsetCapabilityLeases = mutableMapOf<Long, ScrollOffsetCapabilityLease>()
    private var anchorRevision = 0L
    private var rangeRevision = 0L
    private var sourceEventCursor = 0L
    private var endDragInsetArmGeneration = 0L
    private var hostEmergencySourceActive = false
    private val hostEmergencySourceQueue = ArrayDeque<HostEmergencySourceEvent>()

    internal fun bindScrollView(value: ScrollerView<ScrollerAttr, ScrollerEvent>) {
        if (scrollView !== value) {
            var invalidationTerminals: List<() -> Unit> = emptyList()
            val publishReplacement = {
                invalidationTerminals = publishScrollViewBinding(value)
            }
            val previous = scrollView
            if (previous == null) {
                publishReplacement()
            } else {
                previous.prepareForComposeReuse(publishReplacement)
            }
            invalidationTerminals.forEach { it() }
        }
        if (hasPullToRefresh) {
            updatePullToRefreshOnScrollView(value, true)
        }
    }

    internal fun detachScrollView(
        expected: ScrollerView<ScrollerAttr, ScrollerEvent>,
        invalidateNativeWrites: Boolean,
    ) {
        if (scrollView !== expected) return
        var invalidationTerminals: List<() -> Unit> = emptyList()
        val publishDetached = {
            invalidationTerminals = publishScrollViewBinding(
                value = null,
                resetComposeScrollState = true,
            )
        }
        if (invalidateNativeWrites) {
            expected.prepareForComposeReuse(publishDetached)
        } else {
            publishDetached()
        }
        invalidationTerminals.forEach { it() }
    }

    internal fun prepareBoundScrollViewForComposeReuse(
        expected: ScrollerView<ScrollerAttr, ScrollerEvent>,
    ) {
        if (scrollView !== expected) return
        var invalidationTerminals: List<() -> Unit> = emptyList()
        expected.prepareForComposeReuse(beforeNativePrepare = {
            invalidationTerminals = publishScrollViewBinding(expected)
        })
        invalidationTerminals.forEach { it() }
    }

    private fun publishScrollViewBinding(
        value: ScrollerView<ScrollerAttr, ScrollerEvent>?,
        resetComposeScrollState: Boolean = false,
    ): List<() -> Unit> {
        scrollViewBindingGeneration += 1L
        val invalidationTerminals = invalidateScrollWriteOwnership()
        scrollView = value
        if (resetComposeScrollState) {
            isComposeScrollInProgress = { false }
        }
        deferredScrollOffsetAlignmentCoordinator.cancelAndInvalidate { it.cancel() }
        return invalidationTerminals
    }

    internal fun captureScrollOffsetOwnerToken(): ScrollOffsetOwnerToken? {
        val owner = scrollView ?: return null
        return ScrollOffsetOwnerToken(
            scrollView = owner,
            bindingGeneration = scrollViewBindingGeneration,
            nativeWriteGeneration = owner.offsetWriteGeneration,
        )
    }

    internal fun isCurrentScrollOffsetOwner(token: ScrollOffsetOwnerToken): Boolean {
        return scrollView === token.scrollView &&
            scrollViewBindingGeneration == token.bindingGeneration &&
            token.scrollView.offsetWriteGeneration == token.nativeWriteGeneration
    }

    internal fun beginScrollOffsetWriteCapability(
        kind: ScrollOffsetWriteCapabilityKind,
    ): ScrollOffsetWriteCapability? {
        val ownerToken = captureScrollOffsetOwnerToken() ?: return null
        val invalidationTerminals = collectCapabilityReplacementTerminals()
        invalidateScrollOffsetCapabilityLeases()
        val capability = ScrollOffsetWriteCapability(
            kind = kind,
            ownerToken = ownerToken,
            generation = ++scrollOffsetCapabilityGeneration,
        )
        activeScrollOffsetCapability = capability
        scrollOffsetCapabilityLeases[capability.generation] = ScrollOffsetCapabilityLease(
            capability = capability,
            issuanceOpen = true,
            valid = true,
        )
        invalidationTerminals.forEach { it() }
        return capability
    }

    internal fun endScrollOffsetWriteCapability(capability: ScrollOffsetWriteCapability?) {
        if (capability == null || activeScrollOffsetCapability !== capability) return
        scrollOffsetCapabilityLeases[capability.generation]?.issuanceOpen = false
        activeScrollOffsetCapability = null
    }

    internal fun claimScrollOffsetWriteCapability(
        capability: ScrollOffsetWriteCapability?,
    ): ScrollOffsetCapabilityClaim? {
        if (capability == null || activeScrollOffsetCapability !== capability) return null
        return claimActiveScrollOffsetWriteCapability(capability.kind, capability.ownerToken)
    }

    internal fun claimCurrentScrollOffsetWriteCapability(
        kind: ScrollOffsetWriteCapabilityKind,
        ownerToken: ScrollOffsetOwnerToken,
    ): ScrollOffsetCapabilityClaim? {
        val capability = activeScrollOffsetCapability ?: return null
        if (capability.kind != kind || capability.ownerToken != ownerToken) return null
        return claimActiveScrollOffsetWriteCapability(kind, ownerToken)
    }

    private fun claimActiveScrollOffsetWriteCapability(
        kind: ScrollOffsetWriteCapabilityKind,
        ownerToken: ScrollOffsetOwnerToken,
    ): ScrollOffsetCapabilityClaim? {
        val capability = activeScrollOffsetCapability ?: return null
        val lease = scrollOffsetCapabilityLeases[capability.generation] ?: return null
        if (capability.kind != kind || capability.ownerToken != ownerToken ||
            !lease.issuanceOpen || !lease.valid || !isCurrentScrollOffsetOwner(ownerToken)
        ) {
            return null
        }
        lease.claimed = true
        return ScrollOffsetCapabilityClaim(
            kind = kind,
            ownerToken = ownerToken,
            leaseId = capability.generation,
        )
    }

    internal fun releaseScrollOffsetCapabilityClaim(claim: ScrollOffsetCapabilityClaim?) {
        if (claim == null) return
        val lease = scrollOffsetCapabilityLeases[claim.leaseId] ?: return
        if (lease.capability.kind != claim.kind || lease.capability.ownerToken != claim.ownerToken) return
        lease.valid = false
        if (activeScrollOffsetCapability !== lease.capability) {
            scrollOffsetCapabilityLeases.remove(claim.leaseId)
        }
    }

    internal fun isCurrentScrollOffsetCapabilityClaim(
        claim: ScrollOffsetCapabilityClaim?,
    ): Boolean {
        claim ?: return false
        val lease = scrollOffsetCapabilityLeases[claim.leaseId] ?: return false
        return lease.claimed && lease.valid && lease.capability.kind == claim.kind &&
            lease.capability.ownerToken == claim.ownerToken &&
            isCurrentScrollOffsetOwner(claim.ownerToken)
    }

    internal fun hasCurrentScrollOffsetWriteCapability(
        kind: ScrollOffsetWriteCapabilityKind,
        ownerToken: ScrollOffsetOwnerToken,
    ): Boolean {
        val capability = activeScrollOffsetCapability ?: return false
        val lease = scrollOffsetCapabilityLeases[capability.generation] ?: return false
        return capability.kind == kind &&
            lease.issuanceOpen && lease.valid &&
            capability.ownerToken == ownerToken &&
            isCurrentScrollOffsetOwner(ownerToken)
    }

    internal fun beginScrollOffsetOperation(
        ownerToken: ScrollOffsetOwnerToken,
        requiredCapability: ScrollOffsetWriteCapabilityKind? = null,
        capabilityClaim: ScrollOffsetCapabilityClaim? = null,
        semanticOperationId: Long? = null,
    ): ScrollOffsetOperationToken? {
        if (scrollWriteQuarantined || !isCurrentScrollOffsetOwner(ownerToken)) return null
        val capabilityLeaseId = requiredCapability?.let { kind ->
            if (capabilityClaim != null) {
                val lease = scrollOffsetCapabilityLeases[capabilityClaim.leaseId] ?: return null
                if (!lease.claimed || !lease.valid || capabilityClaim.kind != kind ||
                    capabilityClaim.ownerToken != ownerToken || lease.capability.kind != kind ||
                    lease.capability.ownerToken != ownerToken
                ) {
                    return null
                }
                capabilityClaim.leaseId
            } else {
                val capability = activeScrollOffsetCapability ?: return null
                val lease = scrollOffsetCapabilityLeases[capability.generation] ?: return null
                if (capability.kind != kind || capability.ownerToken != ownerToken ||
                    !lease.issuanceOpen || !lease.valid) {
                    return null
                }
                lease.claimed = true
                capability.generation
            }
        } ?: 0L
        val operationId = semanticOperationId ?: run {
            val previousSemanticOperation = scrollOffsetSemanticOperationId
            val invalidationTerminal = invalidateScrollWriteOperation(previousSemanticOperation)
            val nextSemanticOperation = ++scrollOffsetSemanticOperationId
            invalidationTerminal?.invoke()
            if (nextSemanticOperation != scrollOffsetSemanticOperationId) return null
            nextSemanticOperation
        }
        if (capabilityLeaseId != 0L) {
            scrollOffsetCapabilityLeases[capabilityLeaseId]?.semanticOperationId = operationId
        }
        return ScrollOffsetOperationToken(
            ownerToken = ownerToken,
            semanticOperationId = operationId,
            attemptGeneration = ++scrollOffsetAttemptGeneration,
            capabilityKind = requiredCapability,
            capabilityLeaseId = capabilityLeaseId,
            nativeInteractionEpoch = ownerToken.scrollView.nativeInteractionEpoch,
            layoutRevision = ownerToken.scrollView.nativeLayoutRevision,
            anchorRevision = anchorRevision,
            rangeRevision = rangeRevision,
            insetRevision = ownerToken.scrollView.nativeInsetRevision,
            expectedContentSize = currentContentSize,
            expectedViewportSize = viewportSize,
            startedAtNanos = DateTime.nanoTime(),
        )
    }

    internal fun beginScrollOffsetRetry(
        previous: ScrollOffsetOperationToken,
        enforceStartAckDeadline: Boolean = true,
    ): ScrollOffsetOperationToken? {
        if (!isCurrentScrollOffsetOwner(previous.ownerToken) ||
            !isCurrentCapabilityLease(previous) ||
            previous.semanticOperationId != scrollOffsetSemanticOperationId ||
            (enforceStartAckDeadline && !ScrollWriteReplayPolicy.isWithinStartAckDeadline(
                previous.startedAtNanos,
                DateTime.nanoTime(),
            ))
        ) {
            return null
        }
        return ScrollOffsetOperationToken(
            ownerToken = previous.ownerToken,
            semanticOperationId = previous.semanticOperationId,
            attemptGeneration = ++scrollOffsetAttemptGeneration,
            capabilityKind = previous.capabilityKind,
            capabilityLeaseId = previous.capabilityLeaseId,
            nativeInteractionEpoch = previous.ownerToken.scrollView.nativeInteractionEpoch,
            layoutRevision = previous.ownerToken.scrollView.nativeLayoutRevision,
            anchorRevision = anchorRevision,
            rangeRevision = rangeRevision,
            insetRevision = previous.ownerToken.scrollView.nativeInsetRevision,
            expectedContentSize = currentContentSize,
            expectedViewportSize = viewportSize,
            startedAtNanos = previous.startedAtNanos,
        )
    }

    internal fun beginEndDragInsetArm(
        ownerToken: ScrollOffsetOwnerToken,
    ): ScrollOffsetCommitToken? {
        if (!isCurrentScrollOffsetOwner(ownerToken)) return null
        val armGeneration = ++endDragInsetArmGeneration
        return ScrollOffsetCommitToken(
            generation = ownerToken.nativeWriteGeneration,
            requiresNativeIdle = false,
            operationGeneration = 0L,
            expectedContentSize = currentContentSize / getDensity(),
            expectedViewportSize = viewportSize / getDensity(),
            bindingGeneration = ownerToken.bindingGeneration,
            semanticOperationId = armGeneration,
            attemptGeneration = armGeneration,
            nativeInteractionEpoch = ownerToken.scrollView.nativeInteractionEpoch,
            layoutRevision = ownerToken.scrollView.nativeLayoutRevision,
            anchorRevision = anchorRevision,
            rangeRevision = rangeRevision,
            insetRevision = ownerToken.scrollView.nativeInsetRevision,
        )
    }

    internal fun refreshScrollOffsetOperation(
        token: ScrollOffsetOperationToken,
    ): ScrollOffsetOperationToken? {
        if (!isLatestScrollOffsetOperation(token)) return null
        return token.copy(
            nativeInteractionEpoch = token.ownerToken.scrollView.nativeInteractionEpoch,
            layoutRevision = token.ownerToken.scrollView.nativeLayoutRevision,
            anchorRevision = anchorRevision,
            rangeRevision = rangeRevision,
            insetRevision = token.ownerToken.scrollView.nativeInsetRevision,
            expectedContentSize = currentContentSize,
            expectedViewportSize = viewportSize,
        )
    }

    internal fun isCurrentScrollOffsetOperation(
        token: ScrollOffsetOperationToken,
        anchorValidator: () -> Boolean,
    ): Boolean {
        return token.semanticOperationId == scrollOffsetSemanticOperationId &&
            token.attemptGeneration == scrollOffsetAttemptGeneration &&
            isCurrentScrollOffsetOwner(token.ownerToken) &&
            isCurrentCapabilityLease(token) &&
            token.ownerToken.scrollView.nativeInteractionEpoch == token.nativeInteractionEpoch &&
            token.ownerToken.scrollView.nativeLayoutRevision == token.layoutRevision &&
            anchorRevision == token.anchorRevision &&
            rangeRevision == token.rangeRevision &&
            token.ownerToken.scrollView.nativeInsetRevision == token.insetRevision &&
            currentContentSize == token.expectedContentSize &&
            viewportSize == token.expectedViewportSize &&
            anchorValidator()
    }

    internal fun isLatestScrollOffsetOperation(token: ScrollOffsetOperationToken): Boolean {
        return token.semanticOperationId == scrollOffsetSemanticOperationId &&
            token.attemptGeneration == scrollOffsetAttemptGeneration &&
            isCurrentScrollOffsetOwner(token.ownerToken) && isCurrentCapabilityLease(token)
    }

    internal fun invalidateScrollAnchor() {
        anchorRevision += 1L
    }

    internal fun beginNativeScrollInteraction(interactionEpoch: Long) {
        if (interactionEpoch > 0L && interactionEpoch <= lastNativeInteractionEpoch) return
        lastNativeInteractionEpoch = maxOf(lastNativeInteractionEpoch, interactionEpoch)
        val previousSemanticOperation = scrollOffsetSemanticOperationId
        val invalidationTerminal = invalidateScrollWriteOperation(previousSemanticOperation)
        scrollOffsetSemanticOperationId += 1L
        scrollOffsetAttemptGeneration += 1L
        anchorRevision += 1L
        invalidateScrollOffsetCapabilityLeases()
        deferredScrollOffsetAlignmentCoordinator.invalidateRetryOperations()
        ignoreScrollOffset = null
        ignoreScrollOffsetWriter = null
        invalidationTerminal?.invoke()
    }

    internal fun scheduleInteractionWatchdog(
        token: ScrollOffsetOperationToken,
        onInactive: () -> Unit,
    ) {
        interactionWatchdogJobs.remove(token.semanticOperationId)?.cancel()
        val coroutineScope = scope ?: return
        interactionWatchdogJobs[token.semanticOperationId] = coroutineScope.launch {
            while (isLatestScrollOffsetOperation(token)) {
                delay(ScrollWriteReplayPolicy.USER_INTERACTION_WATCHDOG_MS)
                if (!isLatestScrollOffsetOperation(token)) break
                val target = token.ownerToken.scrollView
                if (!target.isDragging && target.nativeScrollPhase == NativeScrollPhase.Idle) {
                    interactionWatchdogJobs.remove(token.semanticOperationId)
                    onInactive()
                    break
                }
            }
        }
    }

    internal fun scheduleRetryDeadline(
        token: ScrollOffsetOperationToken,
        onTimeout: () -> Unit,
    ) {
        retryDeadlineJobs.remove(token.semanticOperationId)?.cancel()
        val coroutineScope = scope ?: return
        val elapsedNanos = DateTime.nanoTime() - token.startedAtNanos
        val remainingMillis = ScrollWriteReplayPolicy.START_ACK_DEADLINE_MS -
            (elapsedNanos / 1_000_000L)
        retryDeadlineJobs[token.semanticOperationId] = coroutineScope.launch {
            if (remainingMillis > 0L) delay(remainingMillis)
            if (isLatestScrollOffsetOperation(token)) {
                retryDeadlineJobs.remove(token.semanticOperationId)
                onTimeout()
            }
        }
    }

    internal fun cancelScrollWriteTimers(semanticOperationId: Long) {
        interactionWatchdogJobs.remove(semanticOperationId)?.cancel()
        retryDeadlineJobs.remove(semanticOperationId)?.cancel()
        revisionWaiters.remove(semanticOperationId)?.let { (view, waiterId) ->
            view.cancelNativeRevisionWaiter(waiterId)
        }
    }

    internal fun registerScrollWriteInvalidationTerminal(
        semanticOperationId: Long,
        terminal: () -> Unit,
    ) {
        scrollWriteInvalidationTerminals[semanticOperationId] = terminal
    }

    internal fun completeScrollWriteInvalidationTerminal(semanticOperationId: Long) {
        scrollWriteInvalidationTerminals.remove(semanticOperationId)
    }

    private fun invalidateScrollWriteOperation(semanticOperationId: Long): (() -> Unit)? {
        cancelScrollWriteTimers(semanticOperationId)
        val callbacks = listOfNotNull(
            scrollWriteInvalidationTerminals.remove(semanticOperationId),
            deferredScrollOffsetAlignmentCoordinator.takeRetryOperationInvalidation(
                "scroll_write_$semanticOperationId",
            ),
            deferredScrollOffsetAlignmentCoordinator.takeRetryOperationInvalidation(
                "offset_delta_$semanticOperationId",
            ),
        )
        if (callbacks.isEmpty()) return null
        return { callbacks.forEach { it() } }
    }

    internal fun awaitScrollWriteRevision(
        token: ScrollOffsetOperationToken,
        callback: () -> Unit,
    ): Long {
        revisionWaiters.remove(token.semanticOperationId)?.let { (view, waiterId) ->
            view.cancelNativeRevisionWaiter(waiterId)
        }
        val view = token.ownerToken.scrollView
        var waiterId = 0L
        waiterId = view.awaitNativeRevisionAdvance(
            interactionEpoch = token.nativeInteractionEpoch,
            layoutRevision = token.layoutRevision,
            insetRevision = token.insetRevision,
        ) {
            revisionWaiters.remove(token.semanticOperationId)
            callback()
        }
        revisionWaiters[token.semanticOperationId] = view to waiterId
        return waiterId
    }

    internal fun canonicalResyncScrollState(ownerToken: ScrollOffsetOwnerToken): Boolean {
        if (!isCurrentScrollOffsetOwner(ownerToken)) return false
        val target = ownerToken.scrollView
        val density = getDensity()
        val physicalOffset = if (isVertical()) {
            (target.curOffsetY * density).toInt()
        } else {
            (target.curOffsetX * density).toInt()
        }
        ignoreScrollOffset = null
        ignoreScrollOffsetWriter = null
        if (!resetChildFrameWritesToCommitted()) return false
        provisionalContentSizeWriter = null
        contentOffset = physicalOffset
        composeOffset = physicalOffset.toFloat()
        anchorRevision += 1L
        rangeRevision += 1L
        return true
    }

    internal fun quarantineAndCanonicalResync(ownerToken: ScrollOffsetOwnerToken): Boolean {
        scrollWriteQuarantined = true
        cancelScrollWriteTimers(scrollOffsetSemanticOperationId)
        val resynced = canonicalResyncScrollState(ownerToken)
        if (resynced) scrollWriteQuarantined = false
        return resynced
    }

    internal fun isScrollWriteQuarantined(): Boolean = scrollWriteQuarantined

    private fun isCurrentCapabilityLease(token: ScrollOffsetOperationToken): Boolean {
        if (token.capabilityKind == null) return true
        val lease = scrollOffsetCapabilityLeases[token.capabilityLeaseId] ?: return false
        return lease.claimed && lease.valid && lease.capability.kind == token.capabilityKind &&
            lease.capability.ownerToken == token.ownerToken
    }

    private fun invalidateScrollOffsetCapabilityLeases() {
        activeScrollOffsetCapability = null
        scrollOffsetCapabilityLeases.values.forEach { it.valid = false }
        scrollOffsetCapabilityLeases.clear()
    }

    private fun collectCapabilityReplacementTerminals(): List<() -> Unit> {
        return scrollOffsetCapabilityLeases.values.asSequence()
            .filter { it.claimed && it.valid }
            .mapNotNull { it.semanticOperationId }
            .distinct()
            .mapNotNull(::invalidateScrollWriteOperation)
            .toList()
    }

    private fun invalidateScrollWriteOwnership(): List<() -> Unit> {
        scrollOffsetSemanticOperationId += 1L
        scrollOffsetAttemptGeneration += 1L
        scrollOffsetCapabilityGeneration += 1L
        endDragInsetArmGeneration += 1L
        anchorRevision += 1L
        rangeRevision += 1L
        lastNativeInteractionEpoch = -1L
        invalidateScrollOffsetCapabilityLeases()
        ignoreScrollOffset = null
        childFrameWriteResources.clear()
        provisionalContentSizeWriter = null
        scrollWriteQuarantined = false
        interactionWatchdogJobs.values.forEach { it.cancel() }
        interactionWatchdogJobs.clear()
        retryDeadlineJobs.values.forEach { it.cancel() }
        retryDeadlineJobs.clear()
        revisionWaiters.values.forEach { (view, waiterId) ->
            view.cancelNativeRevisionWaiter(waiterId)
        }
        revisionWaiters.clear()
        val invalidationTerminals = scrollWriteInvalidationTerminals.values.toList()
        scrollWriteInvalidationTerminals.clear()
        hostEmergencySourceQueue.clear()
        hostEmergencySourceActive = false
        return invalidationTerminals
    }

    internal fun enqueueHostEmergencySourceEvent(
        correction: ((Boolean) -> Unit) -> Boolean,
        applyNormalPath: () -> Unit,
    ) {
        hostEmergencySourceQueue.addLast(
            HostEmergencySourceEvent(
                cursor = ++sourceEventCursor,
                correction = correction,
                applyNormalPath = applyNormalPath,
            ),
        )
        drainHostEmergencySourceQueue()
    }

    private fun drainHostEmergencySourceQueue() {
        if (hostEmergencySourceActive) return
        val event = hostEmergencySourceQueue.firstOrNull() ?: return
        hostEmergencySourceActive = true
        var terminalDelivered = false
        fun complete(committed: Boolean) {
            if (terminalDelivered) return
            terminalDelivered = true
            val current = hostEmergencySourceQueue.firstOrNull()
            if (current?.cursor != event.cursor) return
            hostEmergencySourceQueue.removeFirst()
            hostEmergencySourceActive = false
            if (!committed) {
                event.applyNormalPath()
            }
            drainHostEmergencySourceQueue()
        }
        val accepted = event.correction(::complete)
        if (!accepted) complete(false)
    }

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
    private var currentContentSizeState by
        mutableStateOf((DEFAULT_CONTENT_SIZE * getDensity()).toInt())

    var currentContentSize: Int
        get() = currentContentSizeState
        internal set(value) {
            provisionalContentSizeWriter = null
            if (currentContentSizeState != value) {
                currentContentSizeState = value
                rangeRevision += 1L
            }
        }

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

    /** Latest Compose scroll state for execution-time native offset ownership checks. */
    internal var isComposeScrollInProgress: () -> Boolean = { false }

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
        provisionalContentSizeWriter = null
        updateContentSizeToRenderPreservingWriter()
    }

    private fun updateContentSizeToRenderPreservingWriter() {
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
        isComposeScrollInProgress = { false }
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
        return runCatching { scrollView?.getPager()?.pagerDensity() }
            .getOrNull() ?: DEFAULT_DENSITY
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

internal data class ScrollOffsetOwnerToken(
    val scrollView: ScrollerView<ScrollerAttr, ScrollerEvent>,
    val bindingGeneration: Long,
    val nativeWriteGeneration: Long,
)

internal enum class ScrollOffsetWriteCapabilityKind {
    GestureSnap,
    Mutation,
}

internal data class ScrollOffsetWriteCapability(
    val kind: ScrollOffsetWriteCapabilityKind,
    val ownerToken: ScrollOffsetOwnerToken,
    val generation: Long,
)

internal data class ScrollOffsetCapabilityClaim(
    val kind: ScrollOffsetWriteCapabilityKind,
    val ownerToken: ScrollOffsetOwnerToken,
    val leaseId: Long,
)

private data class ScrollOffsetCapabilityLease(
    val capability: ScrollOffsetWriteCapability,
    var issuanceOpen: Boolean,
    var valid: Boolean,
    var claimed: Boolean = false,
    var semanticOperationId: Long? = null,
)

private data class HostEmergencySourceEvent(
    val cursor: Long,
    val correction: ((Boolean) -> Unit) -> Boolean,
    val applyNormalPath: () -> Unit,
)

internal data class ScrollOffsetOperationToken(
    val ownerToken: ScrollOffsetOwnerToken,
    val semanticOperationId: Long,
    val attemptGeneration: Long,
    val capabilityKind: ScrollOffsetWriteCapabilityKind?,
    val capabilityLeaseId: Long,
    val nativeInteractionEpoch: Long,
    val layoutRevision: Long,
    val anchorRevision: Long,
    val rangeRevision: Long,
    val insetRevision: Long,
    val expectedContentSize: Int,
    val expectedViewportSize: Int,
    val startedAtNanos: Long,
)
