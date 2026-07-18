/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI;
 * you may not use this file except in compliance with the License.
 */
package com.tencent.kuikly.compose.coroutines

import com.tencent.kuikly.compose.coroutines.internal.IdleComposeDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

/**
 * Runs speculative work on the Kuikly context idle lane for [pagerId].
 *
 * Every continuation of [block] is admitted only after normal context work has
 * drained. New normal work invalidates a pending admission and runs first.
 * Callers must keep each non-suspending section bounded: a callback that has
 * already started cannot be preempted in the middle of arbitrary user code.
 */
suspend fun <T> withKuiklyIdleContext(
    pagerId: String,
    block: suspend CoroutineScope.() -> T
): T = withContext(IdleComposeDispatcher(pagerId), block)
