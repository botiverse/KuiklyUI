package com.tencent.kuikly.core.render.web.expand.components.list

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScrollOffsetWritePolicyTest {

    @Test
    fun tokenRequiresCurrentGenerationAndIdlePhase() {
        assertTrue(canApplyWebOffsetWrite(4, true, 4, 0))
        assertFalse(canApplyWebOffsetWrite(4, true, 4, 1))
        assertFalse(canApplyWebOffsetWrite(4, true, 4, 2))
        assertFalse(canApplyWebOffsetWrite(4, false, 5, 0))
    }

    @Test
    fun staleTimerCannotConsumeReplacement() {
        val rejected = mutableListOf<Int>()
        val slot = PendingWebWriteSlot<Int> { rejected += it }

        slot.replace(1)
        slot.replace(2)

        assertFalse(slot.consume(1))
        assertTrue(slot.consume(2))
        assertEquals(listOf(1), rejected)
    }

    @Test
    fun reuseRejectsPendingTimerExactlyOnce() {
        var rejections = 0
        val slot = PendingWebWriteSlot<Int> { rejections += 1 }

        slot.replace(1)
        slot.rejectAndClear()
        slot.rejectAndClear()

        assertEquals(1, rejections)
    }

    @Test
    fun reentrantReplacementWinsOverOlderReplacementFrame() {
        val rejected = mutableListOf<Int>()
        lateinit var slot: PendingWebWriteSlot<Int>
        slot = PendingWebWriteSlot { value ->
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
        lateinit var slot: PendingWebWriteSlot<Int>
        slot = PendingWebWriteSlot { value ->
            if (value == 1) {
                slot.replace(2)
            }
        }

        slot.replace(1)
        slot.rejectAndClear()

        assertTrue(slot.consume(2))
    }
}
