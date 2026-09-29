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
import com.tencent.kuikly.compose.gestures.invalidateDeferredScrollOffsetAlignmentOwnersOnReuse
import com.tencent.kuikly.core.views.ScrollParams
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentSizeExtensionsTest {

    @Test
    fun initialNonTopViewportCommitsBeforeFirstPlacement() {
        assertEquals(
            InitialLazyListNativeViewportAction.Prepare,
            initialLazyListNativeViewportAction(
                pending = true,
                hasItems = true,
                isComposeAtTop = false,
                contentOffset = 0,
                composeOffset = 0,
                isDragging = false,
                hasScrollView = true,
            )
        )
    }

    @Test
    fun initialViewportWaitsForItemsAndNativeBinding() {
        assertEquals(
            InitialLazyListNativeViewportAction.Wait,
            initialLazyListNativeViewportAction(
                pending = true,
                hasItems = false,
                isComposeAtTop = false,
                contentOffset = 0,
                composeOffset = 0,
                isDragging = false,
                hasScrollView = true,
            )
        )
        assertEquals(
            InitialLazyListNativeViewportAction.Wait,
            initialLazyListNativeViewportAction(
                pending = true,
                hasItems = true,
                isComposeAtTop = false,
                contentOffset = 0,
                composeOffset = 0,
                isDragging = false,
                hasScrollView = false,
            )
        )
    }

    @Test
    fun initialTopOrRestoredViewportNeedsNoNewNativeCommit() {
        assertEquals(
            InitialLazyListNativeViewportAction.Complete,
            initialLazyListNativeViewportAction(
                pending = true,
                hasItems = true,
                isComposeAtTop = true,
                contentOffset = 0,
                composeOffset = 0,
                isDragging = false,
                hasScrollView = true,
            )
        )
        assertEquals(
            InitialLazyListNativeViewportAction.Complete,
            initialLazyListNativeViewportAction(
                pending = true,
                hasItems = true,
                isComposeAtTop = false,
                contentOffset = 900,
                composeOffset = 900,
                isDragging = false,
                hasScrollView = true,
            )
        )
    }

    @Test
    fun initialViewportNeverOverridesDragOrRepeats() {
        assertEquals(
            InitialLazyListNativeViewportAction.Complete,
            initialLazyListNativeViewportAction(
                pending = true,
                hasItems = true,
                isComposeAtTop = false,
                contentOffset = 0,
                composeOffset = 0,
                isDragging = true,
                hasScrollView = true,
            )
        )
        assertEquals(
            InitialLazyListNativeViewportAction.Complete,
            initialLazyListNativeViewportAction(
                pending = false,
                hasItems = true,
                isComposeAtTop = false,
                contentOffset = 0,
                composeOffset = 0,
                isDragging = false,
                hasScrollView = true,
            )
        )
    }

    @Test
    fun deferredAlignmentSkipsActiveScrollUnlessForced() {
        assertFalse(
            shouldApplyDeferredScrollOffsetAlignment(
                isScrollInProgress = true,
                forceExpand = false
            )
        )
        assertTrue(
            shouldApplyDeferredScrollOffsetAlignment(
                isScrollInProgress = false,
                forceExpand = false
            )
        )
        assertTrue(
            shouldApplyDeferredScrollOffsetAlignment(
                isScrollInProgress = true,
                forceExpand = true
            )
        )
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
    fun ohosRefreshWindowRejectsFreshGesture() {
        var isScrollInProgress = false

        val shouldApply = runImmediateSuspend {
            shouldApplyDeferredScrollOffsetAlignmentAfterOhosRefresh(
                forceExpand = false,
                isScrollInProgress = { isScrollInProgress },
                isCurrent = { true },
                awaitRefreshWindow = { isScrollInProgress = true }
            )
        }

        assertFalse(shouldApply)
    }

    @Test
    fun ohosRefreshWindowAllowsForcedAlignmentDuringFreshGesture() {
        var isScrollInProgress = false

        val shouldApply = runImmediateSuspend {
            shouldApplyDeferredScrollOffsetAlignmentAfterOhosRefresh(
                forceExpand = true,
                isScrollInProgress = { isScrollInProgress },
                isCurrent = { true },
                awaitRefreshWindow = { isScrollInProgress = true }
            )
        }

        assertTrue(shouldApply)
    }

    @Test
    fun ohosRefreshWindowRejectsInvalidatedRequest() {
        var isCurrent = true

        val shouldApply = runImmediateSuspend {
            shouldApplyDeferredScrollOffsetAlignmentAfterOhosRefresh(
                forceExpand = true,
                isScrollInProgress = { false },
                isCurrent = { isCurrent },
                awaitRefreshWindow = { isCurrent = false }
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
        harness.coordinator.retryAfterScrollEnd {
            harness.schedule().complete()
        }

        assertEquals(1, harness.appliedActions)
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

        state.kuiklyOnScrollEnd(
            params = ScrollParams(
                offsetX = 0f,
                offsetY = 0f,
                contentWidth = 100f,
                contentHeight = 100f,
                viewWidth = 100f,
                viewHeight = 100f,
                isDragging = false
            ),
            retryDeferredAlignment = { retries += 1 }
        )

        assertEquals(1, retries)
    }

    private class DeferredAlignmentHarness(
        var isScrollInProgress: Boolean = false
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
                forceExpand = forceExpand,
                isScrollInProgress = { isScrollInProgress },
                cancelPendingAlignment = {
                    cancelledAlignments += 1
                    it.cancel()
                },
                launchAlignment = { alignment ->
                    PendingAlignment { runImmediateSuspend(alignment) }
                        .also { scheduledAlignment = it }
                },
                awaitAlignmentWindow = { duringWait() },
                applyAlignment = { appliedActions += 1 }
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
