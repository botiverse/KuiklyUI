/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI;
 * you may not use this file except in compliance with the License.
 */
package com.tencent.kuikly.compose.coroutines.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext

internal class IdleComposeDispatcher(
    private val pagerId: String
) : CoroutineDispatcher() {

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        KuiklyContextScheduler.runOnKuiklyThreadIdle(pagerId) { cancel ->
            if (cancel) {
                context.cancel(CancellationException("The idle task was rejected, Pager($pagerId) is closed."))
                return@runOnKuiklyThreadIdle
            }
            block.run()
        }
    }

    override fun toString(): String = "IdleComposeDispatcher($pagerId)"

    override fun equals(other: Any?): Boolean = other is IdleComposeDispatcher && pagerId == other.pagerId

    override fun hashCode(): Int = pagerId.hashCode()
}
