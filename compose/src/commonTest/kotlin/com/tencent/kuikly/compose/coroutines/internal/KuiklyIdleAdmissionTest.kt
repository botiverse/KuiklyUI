/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI;
 * you may not use this file except in compliance with the License.
 */
package com.tencent.kuikly.compose.coroutines.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KuiklyIdleAdmissionTest {

    @Test
    fun normalWorkAlwaysWinsOverIdleAdmission() {
        assertEquals(
            KuiklyIdleAdmissionDecision.WaitForNormalDrain,
            kuiklyIdleAdmissionDecision(
                hasIdleWork = true,
                hasNormalWork = true,
                scheduledGeneration = 4,
                currentGeneration = 4
            )
        )
    }

    @Test
    fun newNormalGenerationReschedulesStaleIdleMarker() {
        assertEquals(
            KuiklyIdleAdmissionDecision.Reschedule,
            kuiklyIdleAdmissionDecision(
                hasIdleWork = true,
                hasNormalWork = false,
                scheduledGeneration = 4,
                currentGeneration = 5
            )
        )
    }

    @Test
    fun stableDrainedGenerationRunsOneIdleCallback() {
        assertEquals(
            KuiklyIdleAdmissionDecision.Run,
            kuiklyIdleAdmissionDecision(
                hasIdleWork = true,
                hasNormalWork = false,
                scheduledGeneration = 5,
                currentGeneration = 5
            )
        )
        assertTrue(shouldScheduleKuiklyIdle(true, false, false))
        assertFalse(shouldScheduleKuiklyIdle(true, true, false))
        assertFalse(shouldScheduleKuiklyIdle(true, false, true))
    }
}
