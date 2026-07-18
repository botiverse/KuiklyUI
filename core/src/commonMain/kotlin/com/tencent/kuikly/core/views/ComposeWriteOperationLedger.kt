/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI.
 */

package com.tencent.kuikly.core.views

internal class ComposeWriteOperationLedger {
    private var latestOperation = 0L

    fun claim(operation: Long): Boolean {
        if (operation <= 0L) return true
        if (operation < latestOperation) return false
        latestOperation = operation
        return true
    }

    fun isCurrent(operation: Long): Boolean {
        return operation <= 0L || operation == latestOperation
    }

    fun nextAfter(floor: Long): Long = maxOf(latestOperation, floor) + 1L

    fun invalidate() {
        latestOperation = 0L
    }
}
