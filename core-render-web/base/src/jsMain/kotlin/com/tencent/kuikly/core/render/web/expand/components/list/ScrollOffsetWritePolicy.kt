package com.tencent.kuikly.core.render.web.expand.components.list

fun canApplyWebOffsetWrite(
    tokenGeneration: Long,
    requiresNativeIdle: Boolean,
    currentGeneration: Long,
    nativeScrollPhase: Int,
): Boolean {
    return tokenGeneration < 0 ||
        (tokenGeneration == currentGeneration && (!requiresNativeIdle || nativeScrollPhase == 0))
}

class PendingWebWriteSlot<T>(private val reject: (T) -> Unit) {
    private var pending: T? = null
    private var revision = 0L

    fun replace(value: T): Boolean {
        val replacementRevision = revision + 1L
        val previous = pending
        pending = null
        revision = replacementRevision
        previous?.let(reject)
        if (revision != replacementRevision || pending != null) {
            reject(value)
            return false
        }
        pending = value
        return true
    }

    fun consume(value: T): Boolean {
        if (pending !== value) return false
        pending = null
        revision += 1L
        return true
    }

    fun isCurrent(value: T): Boolean = pending === value

    fun hasPending(): Boolean = pending != null

    fun rejectAndClear() {
        val previous = pending ?: return
        pending = null
        revision += 1L
        reject(previous)
    }
}
