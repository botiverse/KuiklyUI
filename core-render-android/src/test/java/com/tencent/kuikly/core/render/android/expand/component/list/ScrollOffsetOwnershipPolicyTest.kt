/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI.
 */

package com.tencent.kuikly.core.render.android.expand.component.list

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollOffsetOwnershipPolicyTest {

    @Test
    fun pendingIdleWriteIsRejectedIfRecyclerStartsSettlingBeforeLayoutCommit() {
        assertTrue(
            canApplyComposeOffsetWrite(
                tokenGeneration = 7,
                requiresNativeIdle = true,
                currentGeneration = 7,
                nativeScrollPhase = 0,
            )
        )
        assertFalse(
            canApplyComposeOffsetWrite(
                tokenGeneration = 7,
                requiresNativeIdle = true,
                currentGeneration = 7,
                nativeScrollPhase = 2,
            )
        )
    }

    @Test
    fun pendingWriteIsRejectedAfterComposeReuseGenerationChanges() {
        assertFalse(
            canApplyComposeOffsetWrite(
                tokenGeneration = 7,
                requiresNativeIdle = false,
                currentGeneration = 8,
                nativeScrollPhase = 0,
            )
        )
        assertTrue(
            canApplyComposeOffsetWrite(
                tokenGeneration = null,
                requiresNativeIdle = false,
                currentGeneration = 8,
                nativeScrollPhase = 2,
            )
        )
    }

    @Test
    fun bounceCompletionDispatchesOnceAcrossCancelAndEnd() {
        var generation = 3L
        var completions = 0
        val gate = BounceCompletionGate(3L) { generation }

        assertTrue(gate.dispatchOnce { completions += 1 })
        assertFalse(gate.dispatchOnce { completions += 1 })
        assertEquals(1, completions)

        generation = 4L
        val staleGate = BounceCompletionGate(3L) { generation }
        assertFalse(staleGate.dispatchOnce { completions += 1 })
        assertEquals(1, completions)
    }

    @Test
    fun pendingWriteReplacementAndReuseRejectExactlyOnce() {
        val rejected = mutableListOf<Int>()
        val slot = PendingOffsetWriteSlot<Int> { rejected += it }

        slot.replace(1)
        slot.replace(2)
        assertEquals(listOf(1), rejected)

        assertEquals(2, slot.take())
        assertEquals(listOf(1), rejected)

        slot.replace(3)
        slot.rejectAndClear()
        slot.rejectAndClear()
        assertEquals(listOf(1, 3), rejected)
    }

    @Test
    fun stalePostedWriteCannotConsumeItsReplacement() {
        val rejected = mutableListOf<Int>()
        val slot = PendingOffsetWriteSlot<Int> { rejected += it }

        slot.replace(1)
        slot.replace(2)

        assertFalse(slot.consume(1))
        assertTrue(slot.consume(2))
        slot.rejectAndClear()
        assertEquals(listOf(1), rejected)
    }

    @Test
    fun destroyOrReuseRejectsPendingWriteOnlyOnce() {
        var rejections = 0
        val slot = PendingOffsetWriteSlot<Int> { rejections += 1 }

        slot.replace(1)
        slot.rejectAndClear()
        slot.rejectAndClear()

        assertEquals(1, rejections)
    }

    @Test
    fun reentrantReplacementWinsOverOlderReplacementFrame() {
        val rejected = mutableListOf<Int>()
        lateinit var slot: PendingOffsetWriteSlot<Int>
        slot = PendingOffsetWriteSlot { value ->
            rejected += value
            if (value == 1) {
                slot.replace(3)
            }
        }

        slot.replace(1)
        assertFalse(slot.replace(2))

        assertTrue(slot.consume(3))
        assertFalse(slot.consume(2))
        assertEquals(listOf(1, 2), rejected)
    }

    @Test
    fun reentrantWriteSurvivesRejectAndClear() {
        lateinit var slot: PendingOffsetWriteSlot<Int>
        slot = PendingOffsetWriteSlot { value ->
            if (value == 1) {
                slot.replace(2)
            }
        }

        slot.replace(1)
        slot.rejectAndClear()

        assertTrue(slot.consume(2))
    }

    @Test
    fun animationReplacementCannotOverwriteReentrantNewAnimation() {
        val oldAnimation = Any()
        val newAnimation = Any()
        val staleAnimation = Any()
        lateinit var slot: AnimationOperationSlot<Any>
        var reentrantOperation = -1L
        slot = AnimationOperationSlot { value ->
            if (value === oldAnimation) {
                reentrantOperation = slot.beginReplacement()
                assertTrue(slot.install(reentrantOperation, newAnimation))
            }
        }

        val oldOperation = slot.beginReplacement()
        assertTrue(slot.install(oldOperation, oldAnimation))

        val staleReplacement = slot.beginReplacement()

        assertFalse(slot.isCurrentOperation(staleReplacement))
        assertFalse(slot.install(staleReplacement, staleAnimation))
        assertTrue(slot.isCurrent(reentrantOperation, newAnimation))
    }

    @Test
    fun scrollMutationReportsFalseWhenNativeCannotStart() {
        assertTrue(canStartScrollMutation(0, 0, true, false, false, false))
        assertFalse(canStartScrollMutation(0, 20, true, true, false, true))
        assertFalse(canStartScrollMutation(0, 20, false, false, false, true))
        assertFalse(canStartScrollMutation(20, 0, false, true, false, true))
        assertTrue(canStartScrollMutation(0, 20, false, true, false, true))
    }

    @Test
    fun nativeWriteReplacementCallbackCanInstallNewCurrent() {
        val arbiter = NativeScrollWriteOperationArbiter()
        lateinit var operationC: NativeScrollWriteOperation
        val operationA = NativeScrollWriteOperation(1L) {
            operationC = NativeScrollWriteOperation(3L, null)
            arbiter.install(operationC)
        }
        val operationB = NativeScrollWriteOperation(2L, null)

        arbiter.install(operationA)
        val replaced = arbiter.install(operationB)
        replaced?.callback?.invoke(emptyMap<String, Any>())

        assertTrue(operationA.terminal)
        assertTrue(operationB.terminal)
        assertTrue(arbiter.isCurrent(operationC))
    }

    @Test
    fun nativeWriteTerminalCanOnlyBeClaimedOnce() {
        val arbiter = NativeScrollWriteOperationArbiter()
        val operation = NativeScrollWriteOperation(1L) { }
        arbiter.install(operation)

        assertNotNull(arbiter.complete(operation))
        assertNull(arbiter.complete(operation))
        assertFalse(arbiter.isCurrent(operation))
    }

    @Test
    fun physicalScrollEndWaitsForPrimaryAndEdgeBarrier() {
        val operation = NativeScrollWriteOperation(1L, null).apply {
            started = true
            primaryPending = true
            edgePending = true
        }

        assertFalse(shouldDispatchNativeScrollEnd(false, operation))
        operation.primaryPending = false
        assertFalse(shouldDispatchNativeScrollEnd(false, operation))
        operation.edgePending = false
        assertTrue(shouldDispatchNativeScrollEnd(false, operation))
    }

    @Test
    fun replacementCleanupSuppressesStalePhysicalScrollEnd() {
        val operation = NativeScrollWriteOperation(1L, null).apply {
            started = true
        }

        assertFalse(shouldDispatchNativeScrollEnd(true, operation))
        assertTrue(shouldDispatchNativeScrollEnd(false, operation))
    }
}
