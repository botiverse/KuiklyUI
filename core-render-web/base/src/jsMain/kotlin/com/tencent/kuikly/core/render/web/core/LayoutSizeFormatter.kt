/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI;
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://github.com/Tencent-TDS/KuiklyUI/blob/main/LICENSE
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tencent.kuikly.core.render.web.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.round

private const val HUNDREDTH_SCALE = 100.0

/** See the Android renderer's counterpart for the cross-platform contract. */
internal fun ceilLayoutSizeToHundredth(value: Float): Double {
    if (!value.isFinite()) {
        return value.toDouble()
    }

    val scaled = value.toDouble() * HUNDREDTH_SCALE
    val nearestHundredth = round(scaled)
    val representationTolerance = floatUlp(value) * (HUNDREDTH_SCALE / 2.0)
    val normalized = if (abs(scaled - nearestHundredth) <= representationTolerance) {
        nearestHundredth
    } else {
        scaled
    }
    return ceil(normalized) / HUNDREDTH_SCALE
}

private fun floatUlp(value: Float): Double {
    val magnitude = abs(value)
    if (magnitude == 0f) {
        return Float.MIN_VALUE.toDouble()
    }
    val adjacent = if (magnitude < Float.MAX_VALUE) {
        Float.fromBits(magnitude.toBits() + 1)
    } else {
        Float.fromBits(magnitude.toBits() - 1)
    }
    return abs(adjacent.toDouble() - magnitude.toDouble())
}

internal fun formatLayoutSizeForReport(width: Float?, height: Float?): String {
    if (width == null || height == null) {
        return "0.00|0.00"
    }
    val roundedWidth = ceilLayoutSizeToHundredth(width).asDynamic().toFixed(2)
    val roundedHeight = ceilLayoutSizeToHundredth(height).asDynamic().toFixed(2)
    return "$roundedWidth|$roundedHeight"
}
