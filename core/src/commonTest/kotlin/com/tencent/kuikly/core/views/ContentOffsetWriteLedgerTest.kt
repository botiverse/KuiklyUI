/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI.
 */

package com.tencent.kuikly.core.views

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentOffsetWriteLedgerTest {

    @Test
    fun staleRejectionCannotRollbackNewerOptimisticWrite() {
        val ledger = ContentOffsetWriteLedger()
        val first = ledger.beginWrite(0f to 0f)
        val second = ledger.beginWrite(100f to 0f)

        assertNull(ledger.rollbackTarget(first))
        assertEquals(0f to 0f, ledger.rollbackTarget(second))
    }

    @Test
    fun newerRejectionRollsBackToEarlierConfirmedNativeWrite() {
        val ledger = ContentOffsetWriteLedger()
        val first = ledger.beginWrite(0f to 0f)
        val second = ledger.beginWrite(100f to 0f)

        ledger.confirmWrite(first, 100f to 0f)

        assertEquals(100f to 0f, ledger.rollbackTarget(second))
    }

    @Test
    fun reuseInvalidatesAllEarlierCallbacks() {
        val ledger = ContentOffsetWriteLedger()
        val pending = ledger.beginWrite(0f to 0f)

        ledger.invalidateWrites(40f to 0f)

        assertNull(ledger.rollbackTarget(pending))
        assertFalse(ledger.isLatestWrite(pending))
    }

    @Test
    fun onlyLatestAnimatedWriteMayRestoreIdlePhase() {
        val ledger = ContentOffsetWriteLedger()
        val first = ledger.beginWrite(0f to 0f)
        val second = ledger.beginWrite(0f to 0f)

        assertFalse(ledger.isLatestWrite(first))
        assertTrue(ledger.isLatestWrite(second))
    }
}
