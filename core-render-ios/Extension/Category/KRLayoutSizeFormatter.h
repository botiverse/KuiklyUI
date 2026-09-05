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

#ifndef CORE_RENDER_IOS_KRLAYOUTSIZEFORMATTER_H
#define CORE_RENDER_IOS_KRLAYOUTSIZEFORMATTER_H

#include <math.h>

/**
 * Ceils a measured value to a hundredth while absorbing only the binary
 * representation error around an existing hundredth boundary.
 */
static inline double KRCeilLayoutSizeToHundredth(double value) {
    if (!isfinite(value)) {
        return value;
    }

    const double scale = 100.0;
    double scaled = value * scale;
    const double nearest_hundredth = round(scaled);
    const double value_magnitude = fabs(value);
    const double scaled_magnitude = fabs(scaled);
    const double input_ulp = nextafter(value_magnitude, INFINITY) - value_magnitude;
    const double scaled_ulp = nextafter(scaled_magnitude, INFINITY) - scaled_magnitude;
    const double representation_tolerance = input_ulp * (scale / 2.0) + scaled_ulp / 2.0;
    if (fabs(scaled - nearest_hundredth) <= representation_tolerance) {
        scaled = nearest_hundredth;
    }
    return ceil(scaled) / scale;
}

#endif  // CORE_RENDER_IOS_KRLAYOUTSIZEFORMATTER_H
