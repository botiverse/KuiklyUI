package com.tencent.kuikly.core.render.web.expand.components.list

import com.tencent.kuikly.core.render.web.ktx.KuiklyRenderCallback

enum class WebScrollWriteResultCode(val wireValue: Int) {
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
}

enum class WebScrollWriteKind {
    ContentOffset,
    ContentInset,
}

data class WebScrollWriteOperation(
    val sequence: Long,
    val kind: WebScrollWriteKind,
    val callback: KuiklyRenderCallback?,
    val generation: Long,
    val composeOperation: Long,
    val interactionEpoch: Long,
    val layoutRevision: Long,
    val insetRevision: Long,
    val bindingGeneration: Long = 0,
    val capabilityKind: Int = -1,
    val capabilityLeaseId: Long = 0,
    val semanticOperationId: Long = 0,
    val attemptGeneration: Long = 0,
    val anchorRevision: Long = 0,
    val rangeRevision: Long = 0,
) {
    var terminal = false
    var started = false
    var observedFrames = 0
    var targetX = 0f
    var targetY = 0f
}

class WebScrollWriteOperationArbiter {
    private var current: WebScrollWriteOperation? = null

    fun install(operation: WebScrollWriteOperation): WebScrollWriteOperation? {
        val previous = current
        current = operation
        previous?.terminal = true
        return previous
    }

    fun complete(operation: WebScrollWriteOperation): KuiklyRenderCallback? {
        if (!isCurrent(operation)) return null
        operation.terminal = true
        current = null
        return operation.callback
    }

    fun invalidate(): WebScrollWriteOperation? {
        val operation = current ?: return null
        operation.terminal = true
        current = null
        return operation
    }

    fun isCurrent(operation: WebScrollWriteOperation): Boolean =
        current === operation && !operation.terminal

    fun current(): WebScrollWriteOperation? = current
}

fun webScrollWriteResult(
    code: WebScrollWriteResultCode,
    interactionEpoch: Long,
    layoutRevision: Long,
    insetRevision: Long,
): Map<String, Any> = mapOf(
    "committed" to if (
        code == WebScrollWriteResultCode.Committed ||
        code == WebScrollWriteResultCode.AlreadySatisfied
    ) 1 else 0,
    "resultCode" to code.wireValue,
    "nativeInteractionEpoch" to interactionEpoch,
    "layoutRevision" to layoutRevision,
    "insetRevision" to insetRevision,
)
