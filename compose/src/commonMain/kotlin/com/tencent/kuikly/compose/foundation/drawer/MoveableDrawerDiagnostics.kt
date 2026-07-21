/*
 * Diagnostic-only tracing for task #37. This branch is never merged.
 */
package com.tencent.kuikly.compose.foundation.drawer

import kotlin.time.TimeSource

data class MoveableDrawerDiagnosticEvent(
    val monotonicNanos: Long,
    val stage: String,
    val commandGeneration: Long,
    val bindingId: Long,
    val pagerOwnerId: Long,
    val scrollInfoOwnerId: Long,
    val thread: String = "kuikly",
    val detail: String = ""
)

object MoveableDrawerDiagnosticClock {
    private val origin = TimeSource.Monotonic.markNow()

    fun nowNanos(): Long = origin.elapsedNow().inWholeNanoseconds
}

internal object MoveableDrawerDiagnosticIds {
    private var nextId = 0L

    fun next(): Long = ++nextId
}
