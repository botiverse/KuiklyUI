/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI.
 */

package com.tencent.kuikly.core.views

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposeWriteOperationLedgerTest {

    @Test
    fun laterInsetMakesEarlierOffsetTerminalStale() {
        val ledger = ComposeWriteOperationLedger()

        assertTrue(ledger.claim(11))
        assertTrue(ledger.claim(12))

        assertFalse(ledger.isCurrent(11))
        assertTrue(ledger.isCurrent(12))
        assertFalse(ledger.claim(11))
    }

    @Test
    fun reuseInvalidatesPriorOperationWithoutRejectingNewOwnerSequence() {
        val ledger = ComposeWriteOperationLedger()
        assertTrue(ledger.claim(40))

        ledger.invalidate()

        assertTrue(ledger.claim(1))
        assertTrue(ledger.isCurrent(1))
    }
}
