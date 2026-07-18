/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2026 Tencent. All rights reserved.
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
import com.tencent.kuikly.compose.gestures.DeferredScrollOffsetAlignmentCoordinator
import com.tencent.kuikly.compose.gestures.ScrollOffsetWriteCapabilityKind
import com.tencent.kuikly.compose.gestures.invalidateDeferredScrollOffsetAlignmentOwnersOnReuse
import com.tencent.kuikly.compose.ui.scaleWithDensity
import com.tencent.kuikly.core.views.NativeScrollPhase
import com.tencent.kuikly.core.views.ScrollerAttr
import com.tencent.kuikly.core.views.ScrollerEvent
import com.tencent.kuikly.core.views.ScrollerView
import com.tencent.kuikly.core.views.ScrollParams
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ContentSizeExtensionsTest {

    @Test
    fun nonForcedWriterRequiresFullyIdleCurrentAnchor() {
        val idleContext = ScrollOffsetWriteContext(
            intent = ScrollOffsetWriteIntent.NonForcedAlignment,
            isComposeScrolling = false,
            nativeScrollPhase = NativeScrollPhase.Idle,
            isCurrentOwnerToken = true,
            isAnchorValid = true,
        )

        assertTrue(shouldApplyScrollOffsetWrite(idleContext))
        assertFalse(shouldApplyScrollOffsetWrite(idleContext.copy(isComposeScrolling = true)))
        assertFalse(shouldApplyScrollOffsetWrite(idleContext.copy(nativeScrollPhase = NativeScrollPhase.Dragging)))
        assertFalse(shouldApplyScrollOffsetWrite(idleContext.copy(nativeScrollPhase = NativeScrollPhase.SettlingOrAnimating)))
        assertFalse(shouldApplyScrollOffsetWrite(idleContext.copy(isCurrentOwnerToken = false)))
        assertFalse(shouldApplyScrollOffsetWrite(idleContext.copy(isAnchorValid = false)))
    }

    @Test
    fun acquiredCapabilityIsBoundToTheExactNativeOwner() {
        val state = ScrollState(0)
        val info = state.kuiklyInfo
        val first = ScrollerView<ScrollerAttr, ScrollerEvent>()
        val second = ScrollerView<ScrollerAttr, ScrollerEvent>()
        info.bindScrollView(first)
        val owner = info.captureScrollOffsetOwnerToken()!!
        val capability = info.beginScrollOffsetWriteCapability(
            ScrollOffsetWriteCapabilityKind.GestureSnap,
        )!!

        assertTrue(
            info.hasCurrentScrollOffsetWriteCapability(
                ScrollOffsetWriteCapabilityKind.GestureSnap,
                owner,
            ),
        )

        info.bindScrollView(second)

        assertFalse(
            info.hasCurrentScrollOffsetWriteCapability(
                ScrollOffsetWriteCapabilityKind.GestureSnap,
                owner,
            ),
        )
        info.endScrollOffsetWriteCapability(capability)
    }

    @Test
    fun closingCapabilityStopsIssuanceButClaimedLeaseSurvivesUntilInvalidation() {
        val state = ScrollState(0)
        val info = state.kuiklyInfo
        val view = ScrollerView<ScrollerAttr, ScrollerEvent>()
        info.bindScrollView(view)
        val owner = info.captureScrollOffsetOwnerToken()!!
        val capability = info.beginScrollOffsetWriteCapability(
            ScrollOffsetWriteCapabilityKind.Mutation,
        )!!
        val claimed = info.beginScrollOffsetOperation(
            ownerToken = owner,
            requiredCapability = ScrollOffsetWriteCapabilityKind.Mutation,
        )!!

        info.endScrollOffsetWriteCapability(capability)

        assertFalse(
            info.hasCurrentScrollOffsetWriteCapability(
                ScrollOffsetWriteCapabilityKind.Mutation,
                owner,
            ),
        )
        assertNull(
            info.beginScrollOffsetOperation(
                ownerToken = owner,
                requiredCapability = ScrollOffsetWriteCapabilityKind.Mutation,
            ),
        )
        assertTrue(info.isCurrentScrollOffsetOperation(claimed) { true })
        val replay = checkNotNull(info.beginScrollOffsetRetry(claimed))
        assertEquals(claimed.semanticOperationId, replay.semanticOperationId)
        assertTrue(replay.attemptGeneration > claimed.attemptGeneration)
        assertTrue(info.isCurrentScrollOffsetOperation(replay) { true })

        info.detachScrollView(view, invalidateNativeWrites = true)
        assertFalse(info.isCurrentScrollOffsetOperation(claimed) { true })
        assertFalse(info.isCurrentScrollOffsetOperation(replay) { true })
    }

    @Test
    fun privilegedWriterRequiresAcquiredCapabilityAndItsPhaseContract() {
        val gestureSnap = ScrollOffsetWriteContext(
            intent = ScrollOffsetWriteIntent.GestureSnap,
            isComposeScrolling = true,
            nativeScrollPhase = NativeScrollPhase.Dragging,
            isCurrentOwnerToken = true,
            isAnchorValid = true,
            hasRequiredCapability = true,
        )
        assertTrue(shouldApplyScrollOffsetWrite(gestureSnap))
        assertFalse(shouldApplyScrollOffsetWrite(gestureSnap.copy(hasRequiredCapability = false)))
        assertFalse(shouldApplyScrollOffsetWrite(gestureSnap.copy(isCurrentOwnerToken = false)))
        assertFalse(shouldApplyScrollOffsetWrite(gestureSnap.copy(isAnchorValid = false)))

        val mutation = gestureSnap.copy(
            intent = ScrollOffsetWriteIntent.MutationOwnedProgrammatic,
            nativeScrollPhase = NativeScrollPhase.Idle,
        )
        assertTrue(shouldApplyScrollOffsetWrite(mutation))
        assertFalse(shouldApplyScrollOffsetWrite(mutation.copy(hasRequiredCapability = false)))
        assertFalse(
            shouldApplyScrollOffsetWrite(
                mutation.copy(nativeScrollPhase = NativeScrollPhase.SettlingOrAnimating),
            ),
        )

        val emergency = gestureSnap.copy(
            intent = ScrollOffsetWriteIntent.HostEmergencyCorrection,
            isComposeScrolling = false,
            nativeScrollPhase = NativeScrollPhase.Idle,
        )
        assertTrue(shouldApplyScrollOffsetWrite(emergency))
        assertFalse(shouldApplyScrollOffsetWrite(emergency.copy(isComposeScrolling = true)))
        assertFalse(
            shouldApplyScrollOffsetWrite(
                emergency.copy(nativeScrollPhase = NativeScrollPhase.SettlingOrAnimating),
            ),
        )
    }

    @Test
    fun deferredAlignmentSkipsActiveScrollUnlessForced() {
        assertFalse(
            shouldApplyDeferredScrollOffsetAlignment(
                testWriteContext(isComposeScrolling = true)
            )
        )
        assertTrue(
            shouldApplyDeferredScrollOffsetAlignment(
                testWriteContext()
            )
        )
        assertFalse(
            shouldApplyDeferredScrollOffsetAlignment(
                testWriteContext(nativeScrollPhase = NativeScrollPhase.Dragging)
            )
        )
        assertFalse(
            shouldApplyDeferredScrollOffsetAlignment(
                testWriteContext(
                    intent = ScrollOffsetWriteIntent.MutationOwnedProgrammatic,
                    isComposeScrolling = true,
                    nativeScrollPhase = NativeScrollPhase.Dragging,
                    hasRequiredCapability = true,
                )
            )
        )
    }

    @Test
    fun contentShrinkInvalidatesPreviouslyValidOffsetTarget() {
        assertTrue(isValidScrollOffsetTarget(targetOffset = 700, contentSize = 1_000, viewportSize = 200))
        assertFalse(isValidScrollOffsetTarget(targetOffset = 700, contentSize = 800, viewportSize = 200))
    }

    @Test
    fun deferredAlignmentReadsLatestStateAfterWindow() {
        val harness = DeferredAlignmentHarness()

        harness.schedule(duringWait = { harness.isScrollInProgress = true }).complete()

        assertEquals(0, harness.appliedActions)
    }

    @Test
    fun replacementCancelsPendingAlignmentBeforeLatestAction() {
        val harness = DeferredAlignmentHarness()

        val first = harness.schedule()
        val second = harness.schedule()
        first.complete()
        second.complete()

        assertEquals(1, harness.appliedActions)
        assertEquals(1, harness.cancelledAlignments)
    }

    @Test
    fun staleCompletionCannotApplyEvenWhenCancellationIsNotObserved() {
        val harness = DeferredAlignmentHarness()

        val first = harness.schedule()
        val second = harness.schedule()
        first.completeIgnoringCancellation()
        second.complete()

        assertEquals(1, harness.appliedActions)
    }

    @Test
    fun invalidationRejectsNonCooperativeLateCompletionWithoutReplacement() {
        val harness = DeferredAlignmentHarness()

        val pending = harness.schedule()
        harness.cancelAndInvalidate()
        pending.completeIgnoringCancellation()

        assertEquals(0, harness.appliedActions)
        assertEquals(1, harness.cancelledAlignments)
    }

    @Test
    fun reuseInvalidatesOldOwnerBeforeNonCooperativeLateCompletion() {
        val oldOwner = DeferredAlignmentHarness()
        val newOwner = DeferredAlignmentHarness()
        val oldPending = oldOwner.schedule()
        var cancellations = 0

        invalidateDeferredScrollOffsetAlignmentOwnersOnReuse(
            oldCoordinator = oldOwner.coordinator,
            newCoordinator = newOwner.coordinator,
            cancelPendingAlignment = {
                cancellations += 1
                it.cancel()
            }
        )
        oldPending.completeIgnoringCancellation()

        assertEquals(0, oldOwner.appliedActions)
        assertEquals(0, newOwner.appliedActions)
        assertEquals(1, cancellations)
    }

    @Test
    fun reuseInvalidatesSameOwnerOnlyOnce() {
        val owner = DeferredAlignmentHarness()
        owner.schedule()
        var cancellations = 0

        invalidateDeferredScrollOffsetAlignmentOwnersOnReuse(
            oldCoordinator = owner.coordinator,
            newCoordinator = owner.coordinator,
            cancelPendingAlignment = {
                cancellations += 1
                it.cancel()
            }
        )

        assertEquals(1, cancellations)
    }

    @Test
    fun productionOwnerTokenInvalidatesOnSameViewReuseAndRebind() {
        val info = com.tencent.kuikly.compose.gestures.KuiklyScrollInfo()
        val first = ScrollerView<ScrollerAttr, ScrollerEvent>()
        val second = ScrollerView<ScrollerAttr, ScrollerEvent>()

        info.bindScrollView(first)
        val firstToken = checkNotNull(info.captureScrollOffsetOwnerToken())
        assertTrue(info.isCurrentScrollOffsetOwner(firstToken))

        first.prepareForComposeReuse()
        assertEquals(1L, first.offsetWriteGeneration)
        assertFalse(info.isCurrentScrollOffsetOwner(firstToken))

        val reusedToken = checkNotNull(info.captureScrollOffsetOwnerToken())
        assertTrue(info.isCurrentScrollOffsetOwner(reusedToken))
        info.bindScrollView(second)
        assertEquals(2L, first.offsetWriteGeneration)
        assertFalse(info.isCurrentScrollOffsetOwner(reusedToken))
        assertTrue(info.isCurrentScrollOffsetOwner(checkNotNull(info.captureScrollOffsetOwnerToken())))
    }

    @Test
    fun productionDetachInvalidatesOwnerAndQueuedRetry() {
        val info = com.tencent.kuikly.compose.gestures.KuiklyScrollInfo()
        val view = ScrollerView<ScrollerAttr, ScrollerEvent>()
        var retries = 0
        info.bindScrollView(view)
        val token = checkNotNull(info.captureScrollOffsetOwnerToken())
        info.deferredScrollOffsetAlignmentCoordinator.requestRetryAfterScrollEnd(
            ViewportCorrectionRetryOperation
        ) { retries += 1 }

        info.detachScrollView(view, invalidateNativeWrites = true)

        assertFalse(info.isCurrentScrollOffsetOwner(token))
        assertEquals(0, info.deferredScrollOffsetAlignmentCoordinator.retryAfterScrollEnd())
        assertEquals(0, retries)
    }

    @Test
    fun nativeCommitResultRejectsLogicalMutationAfterLatePhaseOrReuseDrop() {
        assertFalse(
            shouldCommitScrollOffsetWriteResult(
                nativeCommitted = false,
                isCurrentOwner = { true },
                isAnchorValid = { true },
            )
        )
        assertFalse(
            shouldCommitScrollOffsetWriteResult(
                nativeCommitted = true,
                isCurrentOwner = { false },
                isAnchorValid = { true },
            )
        )
        assertFalse(
            shouldCommitScrollOffsetWriteResult(
                nativeCommitted = true,
                isCurrentOwner = { true },
                isAnchorValid = { false },
            )
        )
        assertTrue(
            shouldCommitScrollOffsetWriteResult(
                nativeCommitted = true,
                isCurrentOwner = { true },
                isAnchorValid = { true },
            )
        )
    }

    @Test
    fun ohosRefreshWindowRejectsFreshGesture() {
        var isScrollInProgress = false

        val shouldApply = runImmediateSuspend {
            shouldApplyDeferredScrollOffsetAlignmentAfterOhosRefresh(
                forceExpand = false,
                contextProvider = {
                    testWriteContext(isComposeScrolling = isScrollInProgress)
                },
                awaitRefreshWindow = { isScrollInProgress = true }
            )
        }

        assertFalse(shouldApply)
    }

    @Test
    fun ohosRefreshWindowDoesNotTreatForceFlagAsCapability() {
        var isScrollInProgress = false

        val shouldApply = runImmediateSuspend {
            shouldApplyDeferredScrollOffsetAlignmentAfterOhosRefresh(
                forceExpand = true,
                contextProvider = {
                    testWriteContext(
                        intent = ScrollOffsetWriteIntent.MutationOwnedProgrammatic,
                        isComposeScrolling = isScrollInProgress,
                    )
                },
                awaitRefreshWindow = { isScrollInProgress = true }
            )
        }

        assertFalse(shouldApply)
    }

    @Test
    fun ohosRefreshWindowRejectsInvalidatedRequest() {
        var isCurrentOwnerToken = true

        val shouldApply = runImmediateSuspend {
            shouldApplyDeferredScrollOffsetAlignmentAfterOhosRefresh(
                forceExpand = true,
                contextProvider = {
                    testWriteContext(
                        intent = ScrollOffsetWriteIntent.MutationOwnedProgrammatic,
                        isCurrentOwnerToken = isCurrentOwnerToken,
                    )
                },
                awaitRefreshWindow = { isCurrentOwnerToken = false }
            )
        }

        assertFalse(shouldApply)
    }

    @Test
    fun scrollEndRetriesSkippedAlignmentExactlyOnceWhenIdle() {
        val harness = DeferredAlignmentHarness(isScrollInProgress = true)

        harness.schedule().complete()
        assertEquals(0, harness.appliedActions)

        harness.isScrollInProgress = false
        assertEquals(1, harness.coordinator.retryAfterScrollEnd())

        assertEquals(1, harness.appliedActions)
    }

    @Test
    fun nativeDraggingRetriesSkippedAlignmentExactlyOnceAfterScrollEnd() {
        val harness = DeferredAlignmentHarness(nativeScrollPhase = NativeScrollPhase.Dragging)

        harness.schedule().complete()
        assertEquals(0, harness.appliedActions)

        harness.nativeScrollPhase = NativeScrollPhase.Idle
        assertEquals(1, harness.coordinator.retryAfterScrollEnd())
        assertEquals(0, harness.coordinator.retryAfterScrollEnd())

        assertEquals(1, harness.appliedActions)
    }

    @Test
    fun watchdogCanDiscardPhysicalEndWaitWithoutInvalidatingSemanticRequest() {
        val coordinator = DeferredScrollOffsetAlignmentCoordinator<PendingAlignment>(
            pendingAlignment = { null },
            updatePendingAlignment = {},
        )
        val request = coordinator.beginRetryOperation("watchdog")
        var retries = 0
        coordinator.requestRetryAfterScrollEnd(request) { retries += 1 }

        coordinator.discardPendingRetryOperation(request)

        assertTrue(coordinator.isCurrent(request))
        assertEquals(0, coordinator.retryAfterScrollEnd())
        coordinator.requestRetryAfterScrollEnd(request) { retries += 1 }
        assertEquals(1, coordinator.retryAfterScrollEnd())
        assertEquals(1, retries)
    }

    @Test
    fun replacementInvalidatesRetryRequestedByOlderGeneration() {
        val harness = DeferredAlignmentHarness(isScrollInProgress = true)

        harness.schedule().complete()
        harness.schedule()

        assertEquals(0, harness.coordinator.retryAfterScrollEnd())
        assertEquals(0, harness.appliedActions)
    }

    @Test
    fun staleOperationCompletionCannotClearNewerRetry() {
        val coordinator = DeferredScrollOffsetAlignmentCoordinator<PendingAlignment>(
            pendingAlignment = { null },
            updatePendingAlignment = {},
        )
        val first = coordinator.beginRetryOperation("inset")
        val second = coordinator.beginRetryOperation("inset")
        var applied = 0

        coordinator.requestRetryAfterScrollEnd(second) { applied += 1 }
        coordinator.completeRetryOperation(first)

        assertEquals(1, coordinator.retryAfterScrollEnd())
        assertEquals(1, applied)
    }

    @Test
    fun staleOperationCannotReplaceNewerRetry() {
        val coordinator = DeferredScrollOffsetAlignmentCoordinator<PendingAlignment>(
            pendingAlignment = { null },
            updatePendingAlignment = {},
        )
        val first = coordinator.beginRetryOperation("inset")
        val second = coordinator.beginRetryOperation("inset")
        var applied = 0

        coordinator.requestRetryAfterScrollEnd(second) { applied = 2 }
        coordinator.requestRetryAfterScrollEnd(first) { applied = 1 }

        assertEquals(1, coordinator.retryAfterScrollEnd())
        assertEquals(2, applied)
    }

    @Test
    fun reentrantReplacementWinsOverOlderLaunchFrame() {
        val old = PendingAlignment {}
        val newer = PendingAlignment {}
        var pending: PendingAlignment? = old
        lateinit var coordinator: DeferredScrollOffsetAlignmentCoordinator<PendingAlignment>
        coordinator = DeferredScrollOffsetAlignmentCoordinator(
            pendingAlignment = { pending },
            updatePendingAlignment = { pending = it },
        )

        coordinator.replacePendingAlignment(
            cancelPendingAlignment = {
                coordinator.replacePendingAlignment(
                    cancelPendingAlignment = { it.cancel() },
                    launchAlignment = { newer },
                )
            },
            launchAlignment = { PendingAlignment {} },
        )

        assertSame(newer, pending)
    }

    @Test
    fun reentrantWriteSurvivesCancelAndInvalidate() {
        val old = PendingAlignment {}
        val newer = PendingAlignment {}
        var pending: PendingAlignment? = old
        lateinit var coordinator: DeferredScrollOffsetAlignmentCoordinator<PendingAlignment>
        coordinator = DeferredScrollOffsetAlignmentCoordinator(
            pendingAlignment = { pending },
            updatePendingAlignment = { pending = it },
        )

        coordinator.cancelAndInvalidate {
            coordinator.replacePendingAlignment(
                cancelPendingAlignment = { it.cancel() },
                launchAlignment = { newer },
            )
        }

        assertSame(newer, pending)
    }

    @Test
    fun forcedReplacementAppliesOnceDuringActiveScroll() {
        val harness = DeferredAlignmentHarness(isScrollInProgress = true)

        val first = harness.schedule(forceExpand = true)
        val second = harness.schedule(forceExpand = true)
        first.complete()
        second.complete()

        assertEquals(1, harness.appliedActions)
    }

    @Test
    fun scrollEndInvokesProductionRetryExactlyOnce() {
        val state = ScrollState(0)
        var retries = 0
        val params = ScrollParams(
            offsetX = 0f,
            offsetY = 0f,
            contentWidth = 100f,
            contentHeight = 100f,
            viewWidth = 100f,
            viewHeight = 100f,
            isDragging = false
        )

        val coordinator = state.kuiklyInfo.deferredScrollOffsetAlignmentCoordinator
        coordinator.requestRetryAfterScrollEnd(ViewportCorrectionRetryOperation) { request ->
            if (coordinator.isCurrent(request)) {
                retries += 1
                coordinator.completeRetryOperation(request)
            }
        }
        state.kuiklyOnScrollEnd(params)
        state.kuiklyOnScrollEnd(params)

        assertEquals(1, retries)
    }

    @Test
    fun scaledRendererScrollEndPreservesTransactionIdentityAndDrainsMatchingRetry() {
        val state = ScrollState(0)
        val coordinator = state.kuiklyInfo.deferredScrollOffsetAlignmentCoordinator
        var retries = 0
        coordinator.requestRetryAfterScrollEnd(
            key = "scaled_renderer_scroll_end",
            interactionEpoch = 7L,
        ) { request ->
            if (coordinator.isCurrent(request)) {
                retries += 1
                coordinator.completeRetryOperation(request)
            }
        }
        val scaled = ScrollParams(
            offsetX = 1f,
            offsetY = 2f,
            contentWidth = 100f,
            contentHeight = 200f,
            viewWidth = 50f,
            viewHeight = 75f,
            isDragging = false,
            nativeInteractionEpoch = 7L,
            layoutRevision = 11L,
            insetRevision = 13L,
        ).scaleWithDensity(2f)

        state.kuiklyOnScrollEnd(scaled)

        assertEquals(1, retries)
        assertEquals(7L, scaled.nativeInteractionEpoch)
        assertEquals(11L, scaled.layoutRevision)
        assertEquals(13L, scaled.insetRevision)

        var sameEpochInvalidations = 0
        state.kuiklyInfo.beginNativeScrollInteraction(scaled.nativeInteractionEpoch)
        coordinator.requestRetryAfterScrollEnd(
            key = "same_epoch_drag_begin",
            onInvalidated = { sameEpochInvalidations += 1 },
        ) {}
        state.kuiklyInfo.beginNativeScrollInteraction(scaled.nativeInteractionEpoch)

        assertEquals(0, sameEpochInvalidations)
    }

    @Test
    fun replacementReplaysOnlyLatestOperationOfSameKind() {
        val state = ScrollState(0)
        val applied = mutableListOf<String>()
        val coordinator = state.kuiklyInfo.deferredScrollOffsetAlignmentCoordinator

        coordinator.requestRetryAfterScrollEnd(ViewportCorrectionRetryOperation) {
            applied += "old"
        }
        coordinator.requestRetryAfterScrollEnd(ViewportCorrectionRetryOperation) { request ->
            if (coordinator.isCurrent(request)) {
                applied += "new"
                coordinator.completeRetryOperation(request)
            }
        }
        state.kuiklyOnScrollEnd(testScrollParams())

        assertEquals(listOf("new"), applied)
    }

    @Test
    fun reuseInvalidatesConsumedNonCooperativeRetryBeforeLateApply() {
        val state = ScrollState(0)
        val coordinator = state.kuiklyInfo.deferredScrollOffsetAlignmentCoordinator
        var lateRequest: com.tencent.kuikly.compose.gestures.DeferredScrollOffsetRetryRequest? = null
        var applied = 0

        coordinator.requestRetryAfterScrollEnd(ViewportCorrectionRetryOperation) { request ->
            lateRequest = request
        }
        state.kuiklyOnScrollEnd(testScrollParams())
        coordinator.cancelAndInvalidate { it.cancel() }
        lateRequest?.let { request ->
            if (coordinator.isCurrent(request)) {
                applied += 1
            }
        }

        assertEquals(0, applied)
    }

    private class DeferredAlignmentHarness(
        var isScrollInProgress: Boolean = false,
        var nativeScrollPhase: NativeScrollPhase = NativeScrollPhase.Idle,
    ) {
        private var pendingAlignment: PendingAlignment? = null
        var appliedActions: Int = 0
            private set
        var cancelledAlignments: Int = 0
            private set

        val coordinator = DeferredScrollOffsetAlignmentCoordinator(
            pendingAlignment = { pendingAlignment },
            updatePendingAlignment = { pendingAlignment = it }
        )

        fun schedule(
            forceExpand: Boolean = false,
            duringWait: () -> Unit = {}
        ): PendingAlignment {
            lateinit var scheduledAlignment: PendingAlignment
            scheduleDeferredScrollOffsetAlignment(
                coordinator = coordinator,
                contextProvider = {
                    testWriteContext(
                        intent = if (forceExpand) {
                            ScrollOffsetWriteIntent.MutationOwnedProgrammatic
                        } else {
                            ScrollOffsetWriteIntent.NonForcedAlignment
                        },
                        isComposeScrolling = isScrollInProgress,
                        nativeScrollPhase = nativeScrollPhase,
                    )
                },
                cancelPendingAlignment = {
                    cancelledAlignments += 1
                    it.cancel()
                },
                launchAlignment = { alignment ->
                    PendingAlignment { runImmediateSuspend(alignment) }
                        .also { scheduledAlignment = it }
                },
                awaitAlignmentWindow = { duringWait() },
                retryAfterScrollEnd = { schedule().complete() },
                applyAlignment = { _, _ -> appliedActions += 1 }
            )
            return scheduledAlignment
        }

        fun cancelAndInvalidate() {
            coordinator.cancelAndInvalidate {
                cancelledAlignments += 1
                it.cancel()
            }
        }
    }

    private class PendingAlignment(
        private val action: () -> Unit
    ) {
        private var isCancelled = false

        fun cancel() {
            isCancelled = true
        }

        fun complete() {
            if (!isCancelled) action()
        }

        fun completeIgnoringCancellation() {
            action()
        }
    }

}

private fun testScrollParams() = ScrollParams(
    offsetX = 0f,
    offsetY = 0f,
    contentWidth = 100f,
    contentHeight = 100f,
    viewWidth = 100f,
    viewHeight = 100f,
    isDragging = false,
)

private fun testWriteContext(
    intent: ScrollOffsetWriteIntent = ScrollOffsetWriteIntent.NonForcedAlignment,
    isComposeScrolling: Boolean = false,
    nativeScrollPhase: NativeScrollPhase = NativeScrollPhase.Idle,
    isCurrentOwnerToken: Boolean = true,
    isAnchorValid: Boolean = true,
    hasRequiredCapability: Boolean = true,
) = ScrollOffsetWriteContext(
    intent = intent,
    isComposeScrolling = isComposeScrolling,
    nativeScrollPhase = nativeScrollPhase,
    isCurrentOwnerToken = isCurrentOwnerToken,
    isAnchorValid = isAnchorValid,
    hasRequiredCapability = hasRequiredCapability,
)

private fun <T> runImmediateSuspend(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext

        override fun resumeWith(result: Result<T>) {
            outcome = result
        }
    })
    return checkNotNull(outcome).getOrThrow()
}
