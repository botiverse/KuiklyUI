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

import com.tencent.kuikly.core.base.RenderView
import com.tencent.kuikly.compose.ui.unit.IntOffset
import com.tencent.kuikly.compose.views.ChildFrameMutation
import com.tencent.kuikly.compose.scroller.ScrollOffsetWriteIntent
import com.tencent.kuikly.compose.scroller.applyScrollViewContentOffset
import com.tencent.kuikly.compose.scroller.applyScrollViewOffsetDelta
import com.tencent.kuikly.core.layout.Frame
import com.tencent.kuikly.core.views.ScrollOffsetCommitToken
import com.tencent.kuikly.core.views.ScrollWriteOperationKey
import com.tencent.kuikly.core.views.ScrollWriteResult
import com.tencent.kuikly.core.views.ScrollWriteResultCode
import com.tencent.kuikly.core.views.ScrollerAttr
import com.tencent.kuikly.core.views.ScrollerEvent
import com.tencent.kuikly.core.views.ScrollerView
import com.tencent.kuikly.core.views.SpringAnimation
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KuiklyScrollInfoTest {

    @Test
    fun sameValueContentSizeReplacementPreventsOlderRollback() {
        val info = KuiklyScrollInfo()
        val operation = ScrollWriteOperationKey(1, 1)
        val original = info.currentContentSize

        info.installProvisionalContentSize(operation, original + 100)
        info.currentContentSize = original + 100

        assertFalse(info.rollbackProvisionalContentSize(operation, original))
        assertEquals(original + 100, info.currentContentSize)
    }

    @Test
    fun endDragInsetArmDoesNotReplacePhysicalOffsetOperation() {
        val info = KuiklyScrollInfo()
        val view = ScrollerView<ScrollerAttr, ScrollerEvent>()
        info.bindScrollView(view)
        val owner = checkNotNull(info.captureScrollOffsetOwnerToken())
        val operation = checkNotNull(info.beginScrollOffsetOperation(owner))

        val arm = checkNotNull(info.beginEndDragInsetArm(owner))

        assertEquals(0L, arm.operationGeneration)
        assertTrue(info.isLatestScrollOffsetOperation(operation))
    }

    @Test
    fun interactionTerminalRetryIsIndependentFromStartAckDeadline() {
        val info = KuiklyScrollInfo()
        val view = ScrollerView<ScrollerAttr, ScrollerEvent>()
        info.bindScrollView(view)
        val owner = checkNotNull(info.captureScrollOffsetOwnerToken())
        val expired = checkNotNull(info.beginScrollOffsetOperation(owner)).copy(startedAtNanos = 0L)

        assertNull(info.beginScrollOffsetRetry(expired, enforceStartAckDeadline = true))
        assertTrue(info.beginScrollOffsetRetry(expired, enforceStartAckDeadline = false) != null)
    }

    @Test
    fun claimedCapabilitySupportsDelayedOperationAfterIssuanceCloses() {
        val info = KuiklyScrollInfo()
        val view = ScrollerView<ScrollerAttr, ScrollerEvent>()
        info.bindScrollView(view)
        val owner = checkNotNull(info.captureScrollOffsetOwnerToken())
        val capability = checkNotNull(
            info.beginScrollOffsetWriteCapability(ScrollOffsetWriteCapabilityKind.GestureSnap),
        )
        val claim = checkNotNull(info.claimScrollOffsetWriteCapability(capability))

        info.endScrollOffsetWriteCapability(capability)

        assertTrue(
            info.beginScrollOffsetOperation(
                ownerToken = owner,
                requiredCapability = ScrollOffsetWriteCapabilityKind.GestureSnap,
                capabilityClaim = claim,
            ) != null,
        )
    }

    @Test
    fun newGestureCapabilityInvalidatesOlderClaimedLease() {
        val info = KuiklyScrollInfo()
        val view = ScrollerView<ScrollerAttr, ScrollerEvent>()
        info.bindScrollView(view)
        val owner = checkNotNull(info.captureScrollOffsetOwnerToken())
        val mutationCapability = checkNotNull(
            info.beginScrollOffsetWriteCapability(ScrollOffsetWriteCapabilityKind.Mutation),
        )
        val claimed = checkNotNull(
            info.beginScrollOffsetOperation(
                ownerToken = owner,
                requiredCapability = ScrollOffsetWriteCapabilityKind.Mutation,
            ),
        )
        info.endScrollOffsetWriteCapability(mutationCapability)

        val gestureCapability = checkNotNull(
            info.beginScrollOffsetWriteCapability(ScrollOffsetWriteCapabilityKind.GestureSnap),
        )

        assertNull(info.beginScrollOffsetRetry(claimed))
        info.endScrollOffsetWriteCapability(gestureCapability)
    }

    @Test
    fun dispatchRawDeltaRequiresActiveMutationCapability() {
        var consumedDelta = 0f
        val state = KuiklyScrollableState { delta ->
            consumedDelta += delta
            delta
        }
        state.kuiklyInfo.bindScrollView(ScrollerView<ScrollerAttr, ScrollerEvent>())

        assertEquals(0f, state.dispatchRawDelta(24f))
        assertEquals(0f, consumedDelta)

        val capability = checkNotNull(
            state.kuiklyInfo.beginScrollOffsetWriteCapability(
                ScrollOffsetWriteCapabilityKind.Mutation,
            ),
        )
        assertEquals(24f, state.dispatchRawDelta(24f))
        assertEquals(24f, consumedDelta)
        state.kuiklyInfo.endScrollOffsetWriteCapability(capability)
    }

    @Test
    fun newNativeInteractionInvalidatesPendingRetryWithTerminal() {
        val info = KuiklyScrollInfo()
        val view = ScrollerView<ScrollerAttr, ScrollerEvent>()
        info.bindScrollView(view)
        val owner = checkNotNull(info.captureScrollOffsetOwnerToken())
        val capability = checkNotNull(
            info.beginScrollOffsetWriteCapability(ScrollOffsetWriteCapabilityKind.Mutation),
        )
        val operation = checkNotNull(
            info.beginScrollOffsetOperation(
                ownerToken = owner,
                requiredCapability = ScrollOffsetWriteCapabilityKind.Mutation,
            ),
        )
        info.endScrollOffsetWriteCapability(capability)
        var invalidated = 0
        var operationWasCurrentDuringInvalidation = true
        info.deferredScrollOffsetAlignmentCoordinator.requestRetryAfterScrollEnd(
            key = "operation",
            interactionEpoch = operation.nativeInteractionEpoch,
            onInvalidated = {
                invalidated += 1
                operationWasCurrentDuringInvalidation =
                    info.beginScrollOffsetRetry(operation, enforceStartAckDeadline = false) != null
            },
        ) {}

        info.beginNativeScrollInteraction(operation.nativeInteractionEpoch + 1L)

        assertEquals(1, invalidated)
        assertFalse(operationWasCurrentDuringInvalidation)
        assertNull(info.beginScrollOffsetRetry(operation, enforceStartAckDeadline = false))
    }

    @Test
    fun bindingReplacementInvalidatesOperationBeforeRetryTerminal() {
        val info = KuiklyScrollInfo()
        val oldView = ScrollerView<ScrollerAttr, ScrollerEvent>()
        info.bindScrollView(oldView)
        val owner = checkNotNull(info.captureScrollOffsetOwnerToken())
        val operation = checkNotNull(info.beginScrollOffsetOperation(owner))
        var operationWasCurrentDuringInvalidation = true
        info.deferredScrollOffsetAlignmentCoordinator.requestRetryAfterScrollEnd(
            key = "operation",
            onInvalidated = {
                operationWasCurrentDuringInvalidation =
                    info.beginScrollOffsetRetry(operation, enforceStartAckDeadline = false) != null
            },
        ) {}

        info.bindScrollView(ScrollerView<ScrollerAttr, ScrollerEvent>())

        assertFalse(operationWasCurrentDuringInvalidation)
        assertNull(info.beginScrollOffsetRetry(operation, enforceStartAckDeadline = false))
    }

    @Test
    fun bindingReplacementPublishesNewOwnerBeforeRetryInvalidationCallback() {
        val info = KuiklyScrollInfo()
        val oldView = ScrollerView<ScrollerAttr, ScrollerEvent>()
        val newView = ScrollerView<ScrollerAttr, ScrollerEvent>()
        info.bindScrollView(oldView)
        var callbackOwner: ScrollOffsetOwnerToken? = null
        var callbackOperation: ScrollOffsetOperationToken? = null
        info.deferredScrollOffsetAlignmentCoordinator.requestRetryAfterScrollEnd(
            key = "operation",
            onInvalidated = {
                callbackOwner = info.captureScrollOffsetOwnerToken()
                callbackOperation = callbackOwner?.let(info::beginScrollOffsetOperation)
            },
        ) {}

        info.bindScrollView(newView)

        assertTrue(callbackOwner?.scrollView === newView)
        assertTrue(callbackOperation?.ownerToken?.scrollView === newView)
    }

    @Test
    fun bindingReplacementPreservesReentrantNewOwnerAlignment() = runBlocking {
        val info = KuiklyScrollInfo()
        val oldView = ScrollerView<ScrollerAttr, ScrollerEvent>()
        val newView = ScrollerView<ScrollerAttr, ScrollerEvent>()
        info.bindScrollView(oldView)
        val oldJob = launch { awaitCancellation() }
        info.appleScrollViewOffsetJob = oldJob
        lateinit var newJob: kotlinx.coroutines.Job
        info.deferredScrollOffsetAlignmentCoordinator.requestRetryAfterScrollEnd(
            key = "operation",
            onInvalidated = {
                info.deferredScrollOffsetAlignmentCoordinator.replacePendingAlignment(
                    cancelPendingAlignment = { it.cancel() },
                    launchAlignment = {
                        launch { awaitCancellation() }.also { newJob = it }
                    },
                )
            },
        ) {}

        try {
            info.bindScrollView(newView)

            assertSame(newJob, info.appleScrollViewOffsetJob)
            assertTrue(newJob.isActive)
        } finally {
            info.appleScrollViewOffsetJob?.cancel()
            info.appleScrollViewOffsetJob = null
        }
    }

    @Test
    fun detachClearsOwnerBeforeRetryInvalidationCallback() {
        val info = KuiklyScrollInfo()
        val oldView = ScrollerView<ScrollerAttr, ScrollerEvent>()
        info.bindScrollView(oldView)
        var callbackOwner: ScrollOffsetOwnerToken? = null
        var callbackOperation: ScrollOffsetOperationToken? = null
        info.deferredScrollOffsetAlignmentCoordinator.requestRetryAfterScrollEnd(
            key = "operation",
            onInvalidated = {
                callbackOwner = info.captureScrollOffsetOwnerToken()
                callbackOperation = callbackOwner?.let(info::beginScrollOffsetOperation)
            },
        ) {}

        info.detachScrollView(oldView, invalidateNativeWrites = true)

        assertNull(callbackOwner)
        assertNull(callbackOperation)
    }

    @Test
    fun bindingReplacementAllowsNativeInteractionEpochToRestart() {
        val info = KuiklyScrollInfo()
        info.bindScrollView(ScrollerView<ScrollerAttr, ScrollerEvent>())
        info.beginNativeScrollInteraction(5L)

        info.bindScrollView(ScrollerView<ScrollerAttr, ScrollerEvent>())
        val owner = checkNotNull(info.captureScrollOffsetOwnerToken())
        val operation = checkNotNull(info.beginScrollOffsetOperation(owner))
        info.beginNativeScrollInteraction(1L)

        assertNull(info.beginScrollOffsetRetry(operation, enforceStartAckDeadline = false))
    }

    @Test
    fun replacingRetryOperationInvalidatesPreviousTerminal() {
        var pending: Any? = null
        val coordinator = DeferredScrollOffsetAlignmentCoordinator(
            pendingAlignment = { pending },
            updatePendingAlignment = { pending = it },
        )
        var invalidated = 0
        coordinator.requestRetryAfterScrollEnd(
            key = "operation",
            onInvalidated = { invalidated += 1 },
        ) {}

        coordinator.beginRetryOperation("operation")

        assertEquals(1, invalidated)
    }

    @Test
    fun newerSemanticOperationInvalidatesOlderRetryTerminal() {
        val info = KuiklyScrollInfo()
        info.bindScrollView(ScrollerView<ScrollerAttr, ScrollerEvent>())
        val owner = checkNotNull(info.captureScrollOffsetOwnerToken())
        val first = checkNotNull(info.beginScrollOffsetOperation(owner))
        var invalidated = 0
        var operationWasCurrentDuringInvalidation = true
        info.deferredScrollOffsetAlignmentCoordinator.requestRetryAfterScrollEnd(
            key = "scroll_write_${first.semanticOperationId}",
            onInvalidated = {
                invalidated += 1
                operationWasCurrentDuringInvalidation =
                    info.beginScrollOffsetRetry(first, enforceStartAckDeadline = false) != null
            },
        ) {}

        assertTrue(info.beginScrollOffsetOperation(owner) != null)

        assertEquals(1, invalidated)
        assertFalse(operationWasCurrentDuringInvalidation)
        assertNull(info.beginScrollOffsetRetry(first, enforceStartAckDeadline = false))
    }

    @Test
    fun scrollEndOnlyDrainsRetriesFromItsInteraction() {
        var pending: Any? = null
        val coordinator = DeferredScrollOffsetAlignmentCoordinator(
            pendingAlignment = { pending },
            updatePendingAlignment = { pending = it },
        )
        var first = 0
        var second = 0
        coordinator.requestRetryAfterScrollEnd("first", interactionEpoch = 11L) { first += 1 }
        coordinator.requestRetryAfterScrollEnd("second", interactionEpoch = 12L) { second += 1 }

        assertEquals(1, coordinator.retryAfterScrollEnd(11L))
        assertEquals(1, first)
        assertEquals(0, second)
        assertEquals(1, coordinator.retryAfterScrollEnd(12L))
        assertEquals(1, second)
    }

    @Test
    fun failedCanonicalResyncQuarantinesUntilFreshBinding() {
        val info = KuiklyScrollInfo()
        val oldView = ScrollerView<ScrollerAttr, ScrollerEvent>()
        info.bindScrollView(oldView)
        val oldOwner = checkNotNull(info.captureScrollOffsetOwnerToken())
        info.detachScrollView(oldView, invalidateNativeWrites = false)

        assertFalse(info.quarantineAndCanonicalResync(oldOwner))
        assertTrue(info.isScrollWriteQuarantined())

        val replacement = ScrollerView<ScrollerAttr, ScrollerEvent>()
        info.bindScrollView(replacement)
        assertFalse(info.isScrollWriteQuarantined())
        assertTrue(info.beginScrollOffsetOperation(checkNotNull(info.captureScrollOffsetOwnerToken())) != null)
    }

    @Test
    fun newerSemanticOperationCancelsOlderRevisionWaiter() {
        val info = KuiklyScrollInfo()
        val view = ScrollerView<ScrollerAttr, ScrollerEvent>()
        info.bindScrollView(view)
        val owner = checkNotNull(info.captureScrollOffsetOwnerToken())
        val first = checkNotNull(info.beginScrollOffsetOperation(owner))
        var wakeCount = 0
        info.awaitScrollWriteRevision(first) { wakeCount += 1 }

        assertTrue(info.beginScrollOffsetOperation(owner) != null)
        view.layoutFrameDidChanged(Frame(0f, 0f, 100f, 200f))

        assertEquals(0, wakeCount)
    }

    @Test
    fun canonicalResyncRestoresCommittedChildFrameBeforeUnquarantining() {
        val info = KuiklyScrollInfo()
        val view = ScrollerView<ScrollerAttr, ScrollerEvent>()
        info.bindScrollView(view)
        val owner = checkNotNull(info.captureScrollOffsetOwnerToken())
        val original = Frame(0f, 0f, 100f, 100f)
        val shifted = Frame(0f, 40f, 100f, 100f)
        var physicalFrame = original
        val operation = ScrollWriteOperationKey(1, 1)
        val cell = info.childFrameWriteCell(
            resource = Any(),
            currentFrame = original,
            currentFrameProvider = { physicalFrame },
            applyFrame = { physicalFrame = it },
        )
        val revision = checkNotNull(cell.begin(operation, 0L, shifted))
        physicalFrame = shifted

        assertTrue(info.quarantineAndCanonicalResync(owner))
        assertFalse(info.isScrollWriteQuarantined())
        assertEquals(original, physicalFrame)
        assertFalse(cell.rollback(operation, revision))
    }

    @Test
    fun childFrameRollbackAcceptsFinalizedSuccessorCoverage() {
        val original = Frame(0f, 0f, 100f, 100f)
        val firstTarget = Frame(0f, 40f, 100f, 100f)
        val secondTarget = Frame(0f, 80f, 100f, 100f)
        var physicalFrame = original
        val cell = com.tencent.kuikly.core.views.ScrollWriteResourceCell(original)
        val first = ScrollWriteOperationKey(1, 1)
        val second = ScrollWriteOperationKey(2, 1)
        val firstRevision = checkNotNull(cell.begin(first, 0L, firstTarget))
        val mutation = ChildFrameMutation(
            cell = cell,
            operation = first,
            provisionalRevision = firstRevision,
            baseCommittedRevision = 0L,
            targetFrame = firstTarget,
            currentFrame = { physicalFrame },
            applyFrame = { physicalFrame = it },
        )
        mutation.apply()
        val secondRevision = checkNotNull(
            cell.inherit(first, second, firstRevision, secondTarget),
        )
        physicalFrame = secondTarget
        assertTrue(cell.finalize(second, secondRevision))

        assertTrue(mutation.rollback())
        assertEquals(secondTarget, physicalFrame)
    }

    @Test
    fun childFrameRollbackRejectsUntrackedPhysicalWriter() {
        val original = Frame(0f, 0f, 100f, 100f)
        val target = Frame(0f, 40f, 100f, 100f)
        val rawWrite = Frame(0f, 60f, 100f, 100f)
        var physicalFrame = original
        val cell = com.tencent.kuikly.core.views.ScrollWriteResourceCell(original)
        val operation = ScrollWriteOperationKey(1, 1)
        val revision = checkNotNull(cell.begin(operation, 0L, target))
        val mutation = ChildFrameMutation(
            cell = cell,
            operation = operation,
            provisionalRevision = revision,
            baseCommittedRevision = 0L,
            targetFrame = target,
            currentFrame = { physicalFrame },
            applyFrame = { physicalFrame = it },
        )
        mutation.apply()
        physicalFrame = rawWrite

        assertFalse(mutation.rollback())
        assertEquals(rawWrite, physicalFrame)
    }

    @Test
    fun inheritedChildFrameRollbackRestoresCommittedFrame() {
        val original = Frame(0f, 0f, 100f, 100f)
        val firstTarget = Frame(0f, 40f, 100f, 100f)
        val secondTarget = Frame(0f, 80f, 100f, 100f)
        var physicalFrame = firstTarget
        val cell = com.tencent.kuikly.core.views.ScrollWriteResourceCell(original)
        val first = ScrollWriteOperationKey(1, 1)
        val second = ScrollWriteOperationKey(2, 1)
        val firstRevision = checkNotNull(cell.begin(first, 0L, firstTarget))
        val secondRevision = checkNotNull(
            cell.inherit(first, second, firstRevision, secondTarget),
        )
        val mutation = ChildFrameMutation(
            cell = cell,
            operation = second,
            provisionalRevision = secondRevision,
            baseCommittedRevision = 0L,
            targetFrame = secondTarget,
            currentFrame = { physicalFrame },
            applyFrame = { physicalFrame = it },
            physicalProvisionalFrame = firstTarget,
        )

        assertTrue(mutation.rollback())
        assertEquals(original, physicalFrame)
    }

    @Test
    fun childFrameRollbackRestoresInterveningOrdinaryLayoutFrame() {
        val info = KuiklyScrollInfo()
        val resource = Any()
        val original = Frame(0f, 0f, 100f, 100f)
        val firstTarget = Frame(0f, 40f, 100f, 100f)
        val ordinaryLayout = Frame(0f, 15f, 100f, 120f)
        val secondTarget = Frame(0f, 80f, 100f, 120f)
        var physicalFrame = original
        val first = ScrollWriteOperationKey(1, 1)
        val second = ScrollWriteOperationKey(2, 1)
        val cell = info.childFrameWriteCell(
            resource = resource,
            currentFrame = physicalFrame,
            currentFrameProvider = { physicalFrame },
            applyFrame = { physicalFrame = it },
        )
        val firstBaseRevision = cell.committedSnapshot().second
        val firstRevision = checkNotNull(cell.begin(first, firstBaseRevision, firstTarget))
        ChildFrameMutation(
            cell = cell,
            operation = first,
            provisionalRevision = firstRevision,
            baseCommittedRevision = firstBaseRevision,
            targetFrame = firstTarget,
            currentFrame = { physicalFrame },
            applyFrame = { physicalFrame = it },
        ).apply()
        assertTrue(cell.finalize(first, firstRevision))

        physicalFrame = ordinaryLayout
        val reusedCell = info.childFrameWriteCell(
            resource = resource,
            currentFrame = physicalFrame,
            currentFrameProvider = { physicalFrame },
            applyFrame = { physicalFrame = it },
        )
        val secondBaseRevision = reusedCell.committedSnapshot().second
        val secondRevision = checkNotNull(
            reusedCell.begin(second, secondBaseRevision, secondTarget),
        )
        val secondMutation = ChildFrameMutation(
            cell = reusedCell,
            operation = second,
            provisionalRevision = secondRevision,
            baseCommittedRevision = secondBaseRevision,
            targetFrame = secondTarget,
            currentFrame = { physicalFrame },
            applyFrame = { physicalFrame = it },
        )
        secondMutation.apply()

        assertTrue(secondMutation.rollback())
        assertEquals(ordinaryLayout, physicalFrame)
    }

    @Test
    fun canonicalRecoveryDoesNotOverwriteOrdinaryWriterRejectedByRollbackCas() {
        val info = KuiklyScrollInfo()
        val view = ScrollerView<ScrollerAttr, ScrollerEvent>()
        info.bindScrollView(view)
        val owner = checkNotNull(info.captureScrollOffsetOwnerToken())
        val resource = Any()
        val committed = Frame(0f, 0f, 100f, 100f)
        val provisional = Frame(0f, 40f, 100f, 100f)
        val ordinary = Frame(0f, 60f, 100f, 120f)
        var physical = committed
        val cell = info.childFrameWriteCell(
            resource = resource,
            currentFrame = physical,
            currentFrameProvider = { physical },
            applyFrame = { physical = it },
        )
        val operation = ScrollWriteOperationKey(1, 1)
        val provisionalRevision = checkNotNull(cell.begin(operation, 0L, provisional))
        val mutation = ChildFrameMutation(
            cell = cell,
            operation = operation,
            provisionalRevision = provisionalRevision,
            baseCommittedRevision = 0L,
            targetFrame = provisional,
            currentFrame = { physical },
            applyFrame = { physical = it },
        )
        mutation.apply()

        info.commitOrdinaryChildFrameWrite(
            resource = resource,
            previousFrame = provisional,
            newFrame = ordinary,
            currentFrameProvider = { physical },
            applyFrame = { physical = it },
        )
        physical = ordinary

        assertTrue(mutation.rollback())
        assertTrue(info.canonicalResyncScrollState(owner))
        assertEquals(ordinary, physical)
    }

    @Test
    fun newerSemanticOperationTerminalizesOlderRevisionWaitWithFailure() {
        val info = KuiklyScrollInfo()
        val view = NotReadyScrollerView()
        info.bindScrollView(view)
        val owner = checkNotNull(info.captureScrollOffsetOwnerToken())
        val terminals = mutableListOf<Boolean>()

        assertTrue(
            info.applyScrollViewContentOffset(
                ownerToken = owner,
                offsetX = 0f,
                offsetY = 10f,
                animated = false,
                intent = ScrollOffsetWriteIntent.NonForcedAlignment,
                reason = "revision_wait_terminal_test",
                anchorValidator = { true },
                onCommitResult = terminals::add,
            ),
        )
        view.performRenderViewLazyTasks()
        assertTrue(terminals.isEmpty())

        assertTrue(info.beginScrollOffsetOperation(owner) != null)

        assertEquals(listOf(false), terminals)
    }

    @Test
    fun capabilityReplacementTerminalizesClaimedRevisionWaitWithFailure() {
        val info = KuiklyScrollInfo()
        val view = NotReadyScrollerView()
        info.bindScrollView(view)
        val owner = checkNotNull(info.captureScrollOffsetOwnerToken())
        val capability = checkNotNull(
            info.beginScrollOffsetWriteCapability(ScrollOffsetWriteCapabilityKind.Mutation),
        )
        val terminals = mutableListOf<Boolean>()
        var replacementWasCurrentDuringTerminal = false

        assertTrue(
            info.applyScrollViewContentOffset(
                ownerToken = owner,
                offsetX = 0f,
                offsetY = 10f,
                animated = false,
                intent = ScrollOffsetWriteIntent.MutationOwnedProgrammatic,
                reason = "capability_replacement_terminal_test",
                anchorValidator = { true },
                onCommitResult = { success ->
                    terminals += success
                    if (!success) {
                        replacementWasCurrentDuringTerminal =
                            info.hasCurrentScrollOffsetWriteCapability(
                                ScrollOffsetWriteCapabilityKind.Mutation,
                                owner,
                            )
                    }
                },
            ),
        )
        info.endScrollOffsetWriteCapability(capability)
        view.performRenderViewLazyTasks()
        assertTrue(terminals.isEmpty())

        val replacement = checkNotNull(
            info.beginScrollOffsetWriteCapability(ScrollOffsetWriteCapabilityKind.Mutation),
        )

        assertEquals(listOf(false), terminals)
        assertTrue(replacementWasCurrentDuringTerminal)
        info.endScrollOffsetWriteCapability(replacement)
    }

    @Test
    fun releasedStateHeldClaimStillTerminalizesRevisionWaitOnReplacement() {
        val info = KuiklyScrollInfo()
        val view = NotReadyScrollerView()
        info.bindScrollView(view)
        val owner = checkNotNull(info.captureScrollOffsetOwnerToken())
        val capability = checkNotNull(
            info.beginScrollOffsetWriteCapability(ScrollOffsetWriteCapabilityKind.GestureSnap),
        )
        val claim = checkNotNull(info.claimScrollOffsetWriteCapability(capability))
        val terminals = mutableListOf<Boolean>()

        assertTrue(
            info.applyScrollViewContentOffset(
                ownerToken = owner,
                offsetX = 0f,
                offsetY = 10f,
                animated = false,
                intent = ScrollOffsetWriteIntent.GestureSnap,
                reason = "released_claim_revision_wait_test",
                capabilityClaim = claim,
                anchorValidator = { true },
                onCommitResult = terminals::add,
            ),
        )
        info.endScrollOffsetWriteCapability(capability)
        view.performRenderViewLazyTasks()
        assertTrue(terminals.isEmpty())

        val replacement = checkNotNull(
            info.beginScrollOffsetWriteCapability(ScrollOffsetWriteCapabilityKind.Mutation),
        )
        info.releaseScrollOffsetCapabilityClaim(claim)

        assertEquals(listOf(false), terminals)
        info.endScrollOffsetWriteCapability(replacement)
    }

    @Test
    fun releasedStateHeldClaimStillTerminalizesInteractionWaitOnReplacement() {
        val info = KuiklyScrollInfo()
        val view = BusyScrollerView()
        info.bindScrollView(view)
        val owner = checkNotNull(info.captureScrollOffsetOwnerToken())
        val capability = checkNotNull(
            info.beginScrollOffsetWriteCapability(ScrollOffsetWriteCapabilityKind.GestureSnap),
        )
        val claim = checkNotNull(info.claimScrollOffsetWriteCapability(capability))
        val terminals = mutableListOf<Boolean>()
        assertTrue(
            info.applyScrollViewContentOffset(
                ownerToken = owner,
                offsetX = 0f,
                offsetY = 10f,
                animated = false,
                intent = ScrollOffsetWriteIntent.GestureSnap,
                reason = "released_claim_interaction_wait_test",
                capabilityClaim = claim,
                anchorValidator = { true },
                onCommitResult = terminals::add,
            ),
        )
        view.performRenderViewLazyTasks()
        assertEquals(1, view.callCount)
        assertTrue(terminals.isEmpty())
        info.endScrollOffsetWriteCapability(capability)

        val replacement = checkNotNull(
            info.beginScrollOffsetWriteCapability(ScrollOffsetWriteCapabilityKind.Mutation),
        )
        info.releaseScrollOffsetCapabilityClaim(claim)

        assertEquals(listOf(false), terminals)
        assertEquals(0, info.deferredScrollOffsetAlignmentCoordinator.retryAfterScrollEnd())
        info.endScrollOffsetWriteCapability(replacement)
    }

    @Test
    fun releasedStateHeldClaimRemovesOffsetDeltaRetryOnReplacement() {
        val state = KuiklyScrollableState { it }
        val info = state.kuiklyInfo
        val view = BusyScrollerView().also { scroller ->
            scroller.renderView = RenderView(
                "offset-delta-retry-${scroller.nativeRef}",
                scroller.nativeRef,
                "KRScrollView",
            ).also { renderView ->
                renderView.currentFrame = Frame(0f, 0f, 100f, 100f)
            }
        }
        info.bindScrollView(view)
        val owner = checkNotNull(info.captureScrollOffsetOwnerToken())
        val capability = checkNotNull(
            info.beginScrollOffsetWriteCapability(ScrollOffsetWriteCapabilityKind.GestureSnap),
        )
        val claim = checkNotNull(info.claimScrollOffsetWriteCapability(capability))
        val terminals = mutableListOf<Boolean>()

        assertTrue(
            state.applyScrollViewOffsetDelta(
                delta = 30,
                ownerToken = owner,
                intent = ScrollOffsetWriteIntent.GestureSnap,
                reason = "released_claim_offset_delta_retry_test",
                capabilityClaim = claim,
                anchorValidator = { true },
                onCommitResult = terminals::add,
            ),
        )
        view.performRenderViewLazyTasks()
        assertEquals(1, view.callCount)
        assertTrue(terminals.isEmpty())
        info.endScrollOffsetWriteCapability(capability)

        val replacement = checkNotNull(
            info.beginScrollOffsetWriteCapability(ScrollOffsetWriteCapabilityKind.Mutation),
        )
        info.releaseScrollOffsetCapabilityClaim(claim)

        assertEquals(listOf(false), terminals)
        assertEquals(0, info.deferredScrollOffsetAlignmentCoordinator.retryAfterScrollEnd())
        info.endScrollOffsetWriteCapability(replacement)
    }

    @Test
    fun duplicateOlderAttemptTerminalCannotFinishNewerRetry() {
        val info = KuiklyScrollInfo()
        val view = DuplicateRetryScrollerView()
        info.bindScrollView(view)
        val owner = checkNotNull(info.captureScrollOffsetOwnerToken())
        val terminals = mutableListOf<Boolean>()

        assertTrue(
            info.applyScrollViewContentOffset(
                ownerToken = owner,
                offsetX = 0f,
                offsetY = 10f,
                animated = false,
                intent = ScrollOffsetWriteIntent.NonForcedAlignment,
                reason = "duplicate_attempt_terminal_test",
                anchorValidator = { true },
                onCommitResult = terminals::add,
            ),
        )
        view.performRenderViewLazyTasks()
        assertEquals(1, view.terminals.size)
        view.terminals[0](ScrollWriteResult(ScrollWriteResultCode.Interrupted))
        view.performRenderViewLazyTasks()
        assertEquals(2, view.terminals.size)

        view.terminals[0](ScrollWriteResult(ScrollWriteResultCode.Canceled))
        view.terminals[1](ScrollWriteResult.Committed)

        assertEquals(listOf(true), terminals)
    }

    private class NotReadyScrollerView : ScrollerView<ScrollerAttr, ScrollerEvent>() {
        override fun callContentOffset(
            offsetX: Float,
            offsetY: Float,
            animated: Boolean,
            springAnimation: SpringAnimation?,
            writeToken: ScrollOffsetCommitToken?,
            onCommitResult: ((ScrollWriteResult) -> Unit)?,
        ) {
            onCommitResult?.invoke(
                ScrollWriteResult(
                    code = ScrollWriteResultCode.NotReady,
                    nativeInteractionEpoch = nativeInteractionEpoch,
                    layoutRevision = nativeLayoutRevision,
                    insetRevision = nativeInsetRevision,
                ),
            )
        }
    }

    private class BusyScrollerView : ScrollerView<ScrollerAttr, ScrollerEvent>() {
        var callCount = 0

        override fun callContentOffset(
            offsetX: Float,
            offsetY: Float,
            animated: Boolean,
            springAnimation: SpringAnimation?,
            writeToken: ScrollOffsetCommitToken?,
            onCommitResult: ((ScrollWriteResult) -> Unit)?,
        ) {
            callCount += 1
            onCommitResult?.invoke(
                ScrollWriteResult(
                    code = ScrollWriteResultCode.Busy,
                    nativeInteractionEpoch = nativeInteractionEpoch,
                    layoutRevision = nativeLayoutRevision,
                    insetRevision = nativeInsetRevision,
                ),
            )
        }
    }

    private class DuplicateRetryScrollerView : ScrollerView<ScrollerAttr, ScrollerEvent>() {
        val terminals = mutableListOf<(ScrollWriteResult) -> Unit>()

        override fun callContentOffset(
            offsetX: Float,
            offsetY: Float,
            animated: Boolean,
            springAnimation: SpringAnimation?,
            writeToken: ScrollOffsetCommitToken?,
            onCommitResult: ((ScrollWriteResult) -> Unit)?,
        ) {
            val terminal = checkNotNull(onCommitResult)
            terminals += terminal
        }
    }

    @Test
    fun staleWriterCannotClearReplacementIgnoreMarkerWithSameValue() {
        val info = KuiklyScrollInfo()
        val first = ScrollWriteOperationKey(1, 1)
        val replacement = ScrollWriteOperationKey(2, 1)
        val offset = IntOffset(x = 0, y = 120)

        info.installIgnoreScrollOffset(first, offset)
        info.installIgnoreScrollOffset(replacement, offset)
        info.clearIgnoreScrollOffset(first)

        assertEquals(offset, info.ignoreScrollOffset)
        info.clearIgnoreScrollOffset(replacement)
        assertNull(info.ignoreScrollOffset)
    }
    @Test
    fun mismatchedProgrammaticCallbackClearsGuardAndProceeds() {
        val info = KuiklyScrollInfo().apply {
            ignoreScrollOffset = IntOffset(x = 0, y = 120)
        }

        assertFalse(info.consumeIgnoredScrollOffset(offsetX = 0f, offsetY = 118f, epsilon = 0.5))
        assertNull(info.ignoreScrollOffset)
        assertFalse(info.consumeIgnoredScrollOffset(offsetX = 0f, offsetY = 220f, epsilon = 0.5))
    }

    @Test
    fun matchingProgrammaticCallbackClearsGuardAndIsSkipped() {
        val info = KuiklyScrollInfo().apply {
            ignoreScrollOffset = IntOffset(x = 0, y = 120)
        }

        assertTrue(info.consumeIgnoredScrollOffset(offsetX = 0f, offsetY = 120f, epsilon = 0.5))
        assertNull(info.ignoreScrollOffset)
    }

    // task #318: an off-target echo of a programmatic move (native clamped or
    // split it) must never be dispatched to compose as a phantom user scroll —
    // that phantom walked a bottom-anchored 50-row list to the top, serially
    // composing every row and stalling the Kotlin thread for seconds.
    @Test
    fun exactProgrammaticEchoIsConsumed() {
        val info = KuiklyScrollInfo().apply {
            ignoreScrollOffset = IntOffset(x = 0, y = 120)
        }

        assertEquals(
            KuiklyScrollInfo.NativeScrollEventDisposition.Consume,
            info.resolveNativeScrollEvent(offsetX = 0f, offsetY = 120f, epsilon = 0.5)
        )
        assertNull(info.ignoreScrollOffset)
    }

    @Test
    fun offTargetProgrammaticEchoSyncsWithoutDispatch() {
        val info = KuiklyScrollInfo().apply {
            ignoreScrollOffset = IntOffset(x = 0, y = 4200)
            isDragging = false
        }

        // Native clamped the applied 4200 down to 118: still our own move's
        // echo, so bookkeeping may sync but compose must not scroll.
        assertEquals(
            KuiklyScrollInfo.NativeScrollEventDisposition.SyncOnly,
            info.resolveNativeScrollEvent(offsetX = 0f, offsetY = 118f, epsilon = 0.5)
        )
        assertNull(info.ignoreScrollOffset)
    }

    @Test
    fun offTargetEchoWhileUserDragsStillDispatches() {
        val info = KuiklyScrollInfo().apply {
            ignoreScrollOffset = IntOffset(x = 0, y = 4200)
            isDragging = true
        }

        // A finger on the screen owns the viewport: never swallow real input.
        assertEquals(
            KuiklyScrollInfo.NativeScrollEventDisposition.Dispatch,
            info.resolveNativeScrollEvent(offsetX = 0f, offsetY = 118f, epsilon = 0.5)
        )
    }

    @Test
    fun eventWithoutPendingProgrammaticMoveDispatches() {
        val info = KuiklyScrollInfo().apply { isDragging = false }

        assertEquals(
            KuiklyScrollInfo.NativeScrollEventDisposition.Dispatch,
            info.resolveNativeScrollEvent(offsetX = 0f, offsetY = 118f, epsilon = 0.5)
        )
    }

    @Test
    fun programmaticEchoGuardIsSingleShot() {
        val info = KuiklyScrollInfo().apply {
            ignoreScrollOffset = IntOffset(x = 0, y = 4200)
            isDragging = false
        }

        assertEquals(
            KuiklyScrollInfo.NativeScrollEventDisposition.SyncOnly,
            info.resolveNativeScrollEvent(offsetX = 0f, offsetY = 118f, epsilon = 0.5)
        )
        // The follow-up event has no pending move recorded: genuine scroll.
        assertEquals(
            KuiklyScrollInfo.NativeScrollEventDisposition.Dispatch,
            info.resolveNativeScrollEvent(offsetX = 0f, offsetY = 130f, epsilon = 0.5)
        )
    }

    @Test
    fun hostEmergencySourceContinuationsStayFifoAcrossAsyncFailure() {
        val info = KuiklyScrollInfo()
        val events = mutableListOf<String>()
        var completeFirst: ((Boolean) -> Unit)? = null
        var completeSecond: ((Boolean) -> Unit)? = null

        info.enqueueHostEmergencySourceEvent(
            correction = { complete ->
                events += "start-1"
                completeFirst = complete
                true
            },
            applyNormalPath = { events += "normal-1" },
        )
        info.enqueueHostEmergencySourceEvent(
            correction = { complete ->
                events += "start-2"
                completeSecond = complete
                true
            },
            applyNormalPath = { events += "normal-2" },
        )

        assertEquals(listOf("start-1"), events)
        completeFirst?.invoke(false)
        assertEquals(listOf("start-1", "normal-1", "start-2"), events)
        completeSecond?.invoke(true)
        assertEquals(listOf("start-1", "normal-1", "start-2"), events)
    }

    @Test
    fun hostEmergencySynchronousRejectionAppliesSourceExactlyOnce() {
        val info = KuiklyScrollInfo()
        var normalCount = 0

        info.enqueueHostEmergencySourceEvent(
            correction = { false },
            applyNormalPath = { normalCount += 1 },
        )

        assertEquals(1, normalCount)
    }
}
