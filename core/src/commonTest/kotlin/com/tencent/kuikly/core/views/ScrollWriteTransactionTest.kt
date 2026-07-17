/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI.
 */

package com.tencent.kuikly.core.views

import com.tencent.kuikly.core.layout.Frame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScrollWriteTransactionTest {
    @Test
    fun sameValueReplacementCannotBeRolledBackByOlderWriter() {
        val cell = ScrollWriteResourceCell(10)
        val a = ScrollWriteOperationKey(1, 1)
        val b = ScrollWriteOperationKey(2, 1)

        val aRevision = assertNotNull(cell.begin(a, 0, 20))
        val bRevision = assertNotNull(cell.inherit(a, b, aRevision, 20))

        assertFalse(cell.rollback(a, aRevision))
        assertTrue(cell.finalize(b, bRevision))
        assertEquals(20, cell.snapshot().value)
        assertEquals(1, cell.snapshot().revision)
    }

    @Test
    fun multiResourceFinalizePrechecksEveryOwnerBeforeCommittingAnyCell() {
        val first = ScrollWriteResourceCell(0)
        val second = ScrollWriteResourceCell(0)
        val a = ScrollWriteOperationKey(1, 1)
        val b = ScrollWriteOperationKey(2, 1)
        val firstRevision = assertNotNull(first.begin(a, 0, 10))
        val secondRevision = assertNotNull(second.begin(a, 0, 20))
        assertNotNull(second.inherit(a, b, secondRevision, 30))

        assertFalse(
            finalizeOwnedScrollWriteResources(
                listOf(
                    first.claim(a, firstRevision),
                    second.claim(a, secondRevision),
                ),
            ),
        )
        assertEquals(0, first.snapshot().revision)
        assertEquals(10, first.snapshot().value)
        assertEquals(0, second.snapshot().revision)
        assertEquals(30, second.snapshot().value)
    }

    @Test
    fun terminalEnvelopeIsCreatedOnceBeforeReentrantReplacement() {
        val arbiter = ScrollWriteTerminalArbiter<String>()
        val a = ScrollWriteOperationKey(1, 1)
        val b = ScrollWriteOperationKey(2, 1)
        arbiter.install(a)
        assertTrue(arbiter.transition(a, ScrollWriteOperationState.Prepared, ScrollWriteOperationState.Started))

        val envelope = assertNotNull(arbiter.complete(a, ScrollWriteResult.Committed, "A"))
        arbiter.install(b)

        assertEquals(a, envelope.operation)
        assertEquals("A", envelope.payload)
        assertTrue(arbiter.isCurrent(b))
        assertNull(arbiter.complete(a, ScrollWriteResult.Committed, "late A"))
        assertTrue(arbiter.isCurrent(b))
    }

    @Test
    fun oldAttemptCannotCompleteReplayedSemanticOperation() {
        val arbiter = ScrollWriteTerminalArbiter<Unit>()
        val first = ScrollWriteOperationKey(7, 1)
        val replay = ScrollWriteOperationKey(7, 2)
        arbiter.install(first)
        arbiter.install(replay)

        assertNull(arbiter.complete(first, ScrollWriteResult.Committed, Unit))
        assertTrue(arbiter.isCurrent(replay))
        assertNotNull(arbiter.complete(replay, ScrollWriteResult.Committed, Unit))
    }

    @Test
    fun replayPolicyWaitsOnTheCorrectExternalFactAndStopsAtThreeAttempts() {
        assertEquals(
            ScrollWriteReplayDisposition.WaitForInteractionTerminal,
            ScrollWriteReplayPolicy.decide(
                ScrollWriteResult(ScrollWriteResultCode.Busy),
                completedAttempt = 1,
            ).disposition,
        )
        assertEquals(
            ScrollWriteReplayDisposition.WaitForRevision,
            ScrollWriteReplayPolicy.decide(
                ScrollWriteResult(ScrollWriteResultCode.LayoutChanged),
                completedAttempt = 1,
            ).disposition,
        )
        assertEquals(
            ScrollWriteReplayDisposition.ReplanImmediately,
            ScrollWriteReplayPolicy.decide(
                ScrollWriteResult(ScrollWriteResultCode.AckTimeout),
                completedAttempt = 2,
            ).disposition,
        )
        assertEquals(
            ScrollWriteReplayDisposition.None,
            ScrollWriteReplayPolicy.decide(
                ScrollWriteResult(ScrollWriteResultCode.Busy),
                completedAttempt = 3,
            ).disposition,
        )
    }

    @Test
    fun replayPolicyNeverReplaysIdentityOrRangeFailures() {
        listOf(
            ScrollWriteResultCode.Stale,
            ScrollWriteResultCode.Replaced,
            ScrollWriteResultCode.Destroyed,
            ScrollWriteResultCode.OutOfRange,
            ScrollWriteResultCode.UnsupportedAxisOrNoLayout,
            ScrollWriteResultCode.RollbackFailed,
        ).forEach { code ->
            assertEquals(
                ScrollWriteReplayDisposition.None,
                ScrollWriteReplayPolicy.decide(
                    ScrollWriteResult(code),
                    completedAttempt = 1,
                ).disposition,
            )
        }
    }

    @Test
    fun startAckDeadlineBoundsQueuedReplayWithoutCappingStartedAnimationDuration() {
        val started = 10_000_000_000L
        val justInside = started + ScrollWriteReplayPolicy.START_ACK_DEADLINE_MS * 1_000_000L
        val outside = justInside + 1L

        assertTrue(ScrollWriteReplayPolicy.isWithinStartAckDeadline(started, justInside))
        assertFalse(ScrollWriteReplayPolicy.isWithinStartAckDeadline(started, outside))
    }

    @Test
    fun productionLayoutRevisionWakesWaiterExactlyOnce() {
        val view = ScrollerView<ScrollerAttr, ScrollerEvent>()
        var wakeCount = 0
        view.awaitNativeRevisionAdvance(
            interactionEpoch = view.nativeInteractionEpoch,
            layoutRevision = view.nativeLayoutRevision,
            insetRevision = view.nativeInsetRevision,
        ) {
            wakeCount += 1
        }

        view.layoutFrameDidChanged(Frame(0f, 0f, 100f, 200f))
        view.layoutFrameDidChanged(Frame(0f, 0f, 100f, 200f))

        assertEquals(1, wakeCount)
    }

    @Test
    fun canceledProductionRevisionWaiterCannotWakeLater() {
        val view = ScrollerView<ScrollerAttr, ScrollerEvent>()
        var wakeCount = 0
        val waiter = view.awaitNativeRevisionAdvance(
            interactionEpoch = view.nativeInteractionEpoch,
            layoutRevision = view.nativeLayoutRevision,
            insetRevision = view.nativeInsetRevision,
        ) {
            wakeCount += 1
        }
        view.cancelNativeRevisionWaiter(waiter)

        view.layoutFrameDidChanged(Frame(0f, 0f, 100f, 200f))

        assertEquals(0, wakeCount)
    }

    @Test
    fun idleResourceRefreshAdvancesCommittedRevisionButCannotReplaceProvisionalWriter() {
        val cell = ScrollWriteResourceCell(10)

        assertTrue(cell.refreshCommittedIfIdle(20))
        assertEquals(20 to 1L, cell.committedSnapshot())

        val operation = ScrollWriteOperationKey(1, 1)
        assertNotNull(cell.begin(operation, 1L, 30))
        assertFalse(cell.refreshCommittedIfIdle(40))
        assertEquals(20 to 1L, cell.committedSnapshot())
        assertEquals(30, cell.snapshot().value)
    }

    @Test
    fun ordinaryWriterSupersedesProvisionalWriterAsNewCommittedRevision() {
        val cell = ScrollWriteResourceCell(10)
        val operation = ScrollWriteOperationKey(1, 1)
        val provisionalRevision = assertNotNull(cell.begin(operation, 0L, 20))

        assertTrue(cell.commitExternal(30))
        assertEquals(30 to 1L, cell.committedSnapshot())
        assertFalse(cell.rollback(operation, provisionalRevision))
        assertEquals(30, cell.snapshot().value)
    }

    @Test
    fun staleInteractionEventCannotRegressCurrentNativeState() {
        val view = ScrollerView<ScrollerAttr, ScrollerEvent>()
        view.nativeInteractionEpoch = 5L
        view.nativeScrollPhase = NativeScrollPhase.Dragging
        val stale = ScrollParams(
            offsetX = 0f,
            offsetY = 0f,
            contentWidth = 100f,
            contentHeight = 100f,
            viewWidth = 100f,
            viewHeight = 100f,
            isDragging = false,
            nativeInteractionEpoch = 4L,
        )

        assertFalse(view.acceptNativeScrollEvent(stale))
        assertEquals(5L, view.nativeInteractionEpoch)
        assertEquals(NativeScrollPhase.Dragging, view.nativeScrollPhase)
    }

    @Test
    fun zeroEpochEventCannotTerminateEstablishedNativeInteraction() {
        val view = ScrollerView<ScrollerAttr, ScrollerEvent>()
        view.nativeInteractionEpoch = 5L
        view.nativeScrollPhase = NativeScrollPhase.Dragging
        val legacy = ScrollParams(
            offsetX = 0f,
            offsetY = 0f,
            contentWidth = 100f,
            contentHeight = 100f,
            viewWidth = 100f,
            viewHeight = 100f,
            isDragging = false,
            nativeInteractionEpoch = 0L,
        )

        assertFalse(view.acceptNativeScrollEvent(legacy))
        assertEquals(5L, view.nativeInteractionEpoch)
        assertEquals(NativeScrollPhase.Dragging, view.nativeScrollPhase)
    }

    @Test
    fun zeroEpochEventRemainsCompatibleBeforePositiveEpochIsEstablished() {
        val view = ScrollerView<ScrollerAttr, ScrollerEvent>()
        val legacy = ScrollParams(
            offsetX = 0f,
            offsetY = 0f,
            contentWidth = 100f,
            contentHeight = 100f,
            viewWidth = 100f,
            viewHeight = 100f,
            isDragging = false,
            nativeInteractionEpoch = 0L,
        )

        assertTrue(view.acceptNativeScrollEvent(legacy))
        assertEquals(0L, view.nativeInteractionEpoch)
    }
}
