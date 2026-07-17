/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI.
 */

package com.tencent.kuikly.core.views

/** Stable result contract shared by Compose, the bridge and every renderer. */
enum class ScrollWriteResultCode(val wireValue: Int) {
    Committed(0),
    AlreadySatisfied(1),
    Busy(2),
    NotReady(3),
    LayoutChanged(4),
    Stale(5),
    Replaced(6),
    Canceled(7),
    Destroyed(8),
    OutOfRange(9),
    UnsupportedAxisOrNoLayout(10),
    Interrupted(11),
    AckTimeout(12),
    RollbackFailed(13),
    ;

    val isSuccess: Boolean
        get() = this == Committed || this == AlreadySatisfied

    val isReplayableAfterResync: Boolean
        get() = this == Busy || this == NotReady || this == LayoutChanged ||
            this == Interrupted || this == AckTimeout

    companion object {
        fun fromWireValue(value: Int): ScrollWriteResultCode =
            entries.firstOrNull { it.wireValue == value } ?: Stale
    }
}

data class ScrollWriteResult(
    val code: ScrollWriteResultCode,
    val nativeInteractionEpoch: Long = -1L,
    val layoutRevision: Long = -1L,
    val insetRevision: Long = -1L,
    val accepted: Boolean = code.isSuccess,
    val installed: Boolean = code.isSuccess,
    val replacedPrevious: Boolean = false,
) {
    val committed: Boolean
        get() = code.isSuccess

    companion object {
        val Committed = ScrollWriteResult(ScrollWriteResultCode.Committed)
        val AlreadySatisfied = ScrollWriteResult(ScrollWriteResultCode.AlreadySatisfied)
    }
}

enum class ScrollWriteReplayDisposition {
    None,
    WaitForInteractionTerminal,
    WaitForRevision,
    ReplanImmediately,
}

data class ScrollWriteReplayDecision(
    val disposition: ScrollWriteReplayDisposition,
    val nextAttempt: Int,
)

object ScrollWriteReplayPolicy {
    const val MAX_ATTEMPTS = 3
    const val START_ACK_DEADLINE_MS = 2_000L
    const val USER_INTERACTION_WATCHDOG_MS = 30_000L

    fun isWithinStartAckDeadline(
        startedAtNanos: Long,
        nowNanos: Long,
    ): Boolean = nowNanos - startedAtNanos <= START_ACK_DEADLINE_MS * 1_000_000L

    fun decide(
        result: ScrollWriteResult,
        completedAttempt: Int,
    ): ScrollWriteReplayDecision {
        if (result.committed || completedAttempt >= MAX_ATTEMPTS) {
            return ScrollWriteReplayDecision(ScrollWriteReplayDisposition.None, completedAttempt)
        }
        val disposition = when (result.code) {
            ScrollWriteResultCode.Busy -> ScrollWriteReplayDisposition.WaitForInteractionTerminal
            ScrollWriteResultCode.NotReady,
            ScrollWriteResultCode.LayoutChanged -> ScrollWriteReplayDisposition.WaitForRevision
            ScrollWriteResultCode.Interrupted,
            ScrollWriteResultCode.AckTimeout -> ScrollWriteReplayDisposition.ReplanImmediately
            ScrollWriteResultCode.Committed,
            ScrollWriteResultCode.AlreadySatisfied,
            ScrollWriteResultCode.Stale,
            ScrollWriteResultCode.Replaced,
            ScrollWriteResultCode.Canceled,
            ScrollWriteResultCode.Destroyed,
            ScrollWriteResultCode.OutOfRange,
            ScrollWriteResultCode.UnsupportedAxisOrNoLayout,
            ScrollWriteResultCode.RollbackFailed -> ScrollWriteReplayDisposition.None
        }
        return ScrollWriteReplayDecision(disposition, completedAttempt + 1)
    }
}

data class ScrollWriteOperationKey(
    val semanticOperationId: Long,
    val attemptGeneration: Long,
)

enum class ScrollWriteOperationState {
    Prepared,
    Started,
    Mutating,
    Animating,
    Committing,
    RollingBack,
    Terminal,
}

/**
 * Immutable notification created only after all state owned by an operation has been finalized.
 * Delivering the envelope may synchronously install another operation, so delivery must never
 * mutate the arbiter again.
 */
internal data class ScrollWriteTerminalEnvelope<T>(
    val operation: ScrollWriteOperationKey,
    val result: ScrollWriteResult,
    val payload: T,
)

/** Reentrancy-safe current-operation slot. All state transitions happen before callbacks. */
internal class ScrollWriteTerminalArbiter<T> {
    private data class ActiveOperation(
        val key: ScrollWriteOperationKey,
        var state: ScrollWriteOperationState,
    )

    private var active: ActiveOperation? = null

    fun install(operation: ScrollWriteOperationKey): ScrollWriteOperationKey? {
        val replaced = active?.key
        active = ActiveOperation(operation, ScrollWriteOperationState.Prepared)
        return replaced
    }

    fun transition(
        operation: ScrollWriteOperationKey,
        expected: ScrollWriteOperationState,
        next: ScrollWriteOperationState,
    ): Boolean {
        val current = active ?: return false
        if (current.key != operation || current.state != expected) return false
        current.state = next
        return true
    }

    fun isCurrent(operation: ScrollWriteOperationKey): Boolean = active?.key == operation

    fun state(operation: ScrollWriteOperationKey): ScrollWriteOperationState? =
        active?.takeIf { it.key == operation }?.state

    fun complete(
        operation: ScrollWriteOperationKey,
        result: ScrollWriteResult,
        payload: T,
    ): ScrollWriteTerminalEnvelope<T>? {
        val current = active ?: return null
        if (current.key != operation || current.state == ScrollWriteOperationState.Terminal) return null
        current.state = ScrollWriteOperationState.Terminal
        active = null
        return ScrollWriteTerminalEnvelope(operation, result, payload)
    }

    fun invalidate(): ScrollWriteOperationKey? = active?.key.also { active = null }
}

/**
 * One resource cell in the compensating-CAS protocol. Writer identity, not value equality,
 * decides whether a late operation may finalize or roll back its provisional state.
 */
class ScrollWriteResourceCell<T>(initialValue: T) {
    data class Snapshot<T>(
        val value: T,
        val revision: Long,
        val provisionalWriter: ScrollWriteOperationKey?,
        val provisionalRevision: Long,
    )

    private var committedValue = initialValue
    private var committedRevision = 0L
    private var provisionalValue: T? = null
    private var provisionalWriter: ScrollWriteOperationKey? = null
    private var provisionalRevision = 0L

    fun snapshot(): Snapshot<T> = Snapshot(
        value = provisionalValue ?: committedValue,
        revision = committedRevision,
        provisionalWriter = provisionalWriter,
        provisionalRevision = provisionalRevision,
    )

    fun committedSnapshot(): Pair<T, Long> = committedValue to committedRevision

    fun refreshCommittedIfIdle(value: T): Boolean {
        if (provisionalWriter != null || committedValue == value) return false
        committedValue = value
        committedRevision += 1L
        return true
    }

    fun commitExternal(value: T): Boolean {
        if (provisionalWriter == null && committedValue == value) return false
        provisionalRevision += 1L
        provisionalWriter = null
        provisionalValue = null
        committedValue = value
        committedRevision += 1L
        return true
    }

    fun resetToCommitted(): T {
        provisionalWriter = null
        provisionalValue = null
        return committedValue
    }

    fun begin(
        operation: ScrollWriteOperationKey,
        expectedCommittedRevision: Long,
        value: T,
    ): Long? {
        if (committedRevision != expectedCommittedRevision || provisionalWriter != null) return null
        provisionalRevision += 1L
        provisionalWriter = operation
        provisionalValue = value
        return provisionalRevision
    }

    fun inherit(
        dependency: ScrollWriteOperationKey,
        operation: ScrollWriteOperationKey,
        expectedProvisionalRevision: Long,
        value: T,
    ): Long? {
        if (provisionalWriter != dependency || provisionalRevision != expectedProvisionalRevision) {
            return null
        }
        provisionalRevision += 1L
        provisionalWriter = operation
        provisionalValue = value
        return provisionalRevision
    }

    fun finalize(
        operation: ScrollWriteOperationKey,
        expectedProvisionalRevision: Long,
    ): Boolean {
        if (provisionalWriter != operation || provisionalRevision != expectedProvisionalRevision) {
            return false
        }
        @Suppress("UNCHECKED_CAST")
        val value = provisionalValue as T
        committedValue = value
        committedRevision += 1L
        provisionalWriter = null
        provisionalValue = null
        return true
    }

    fun rollback(
        operation: ScrollWriteOperationKey,
        expectedProvisionalRevision: Long,
    ): Boolean {
        if (provisionalWriter != operation || provisionalRevision != expectedProvisionalRevision) {
            return false
        }
        provisionalWriter = null
        provisionalValue = null
        return true
    }

    fun isOwnedBy(
        operation: ScrollWriteOperationKey,
        expectedProvisionalRevision: Long,
    ): Boolean = provisionalWriter == operation &&
        provisionalRevision == expectedProvisionalRevision

    fun claim(
        operation: ScrollWriteOperationKey,
        expectedProvisionalRevision: Long,
    ): ScrollWriteResourceClaim = ScrollWriteResourceClaim(
        isOwned = { isOwnedBy(operation, expectedProvisionalRevision) },
        finalize = { finalize(operation, expectedProvisionalRevision) },
    )
}

class ScrollWriteResourceClaim internal constructor(
    private val isOwned: () -> Boolean,
    private val finalize: () -> Boolean,
) {
    internal fun isStillOwned(): Boolean = isOwned()
    internal fun finalizeOwned(): Boolean = finalize()
}

fun finalizeOwnedScrollWriteResources(resources: List<ScrollWriteResourceClaim>): Boolean {
    if (resources.any { !it.isStillOwned() }) return false
    return resources.all { it.finalizeOwned() }
}
