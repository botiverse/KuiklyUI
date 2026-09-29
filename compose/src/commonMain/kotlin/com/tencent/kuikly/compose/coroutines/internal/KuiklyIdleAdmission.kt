/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI;
 * you may not use this file except in compliance with the License.
 */
package com.tencent.kuikly.compose.coroutines.internal

internal enum class KuiklyIdleAdmissionDecision {
    Run,
    Reschedule,
    WaitForNormalDrain,
    None
}

internal fun kuiklyIdleAdmissionDecision(
    hasIdleWork: Boolean,
    hasNormalWork: Boolean,
    scheduledGeneration: Long?,
    currentGeneration: Long
): KuiklyIdleAdmissionDecision =
    when {
        !hasIdleWork -> KuiklyIdleAdmissionDecision.None
        hasNormalWork -> KuiklyIdleAdmissionDecision.WaitForNormalDrain
        scheduledGeneration != currentGeneration -> KuiklyIdleAdmissionDecision.Reschedule
        else -> KuiklyIdleAdmissionDecision.Run
    }

internal fun shouldScheduleKuiklyIdle(
    hasIdleWork: Boolean,
    hasNormalWork: Boolean,
    alreadyScheduled: Boolean
): Boolean = hasIdleWork && !hasNormalWork && !alreadyScheduled
