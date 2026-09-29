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

#ifndef CORE_RENDER_OHOS_KRLAYOUTSIZEFORMATTER_H
#define CORE_RENDER_OHOS_KRLAYOUTSIZEFORMATTER_H

#include <cmath>
#include <limits>

namespace kuikly {
namespace util {

inline double CeilLayoutSizeToHundredth(double value) {
    if (!std::isfinite(value)) {
        return value;
    }

    constexpr double scale = 100.0;
    double scaled = value * scale;
    const double nearest_hundredth = std::round(scaled);
    const double infinity = std::numeric_limits<double>::infinity();
    const double value_magnitude = std::fabs(value);
    const double scaled_magnitude = std::fabs(scaled);
    const double input_ulp = std::nextafter(value_magnitude, infinity) - value_magnitude;
    const double scaled_ulp = std::nextafter(scaled_magnitude, infinity) - scaled_magnitude;
    const double representation_tolerance = input_ulp * (scale / 2.0) + scaled_ulp / 2.0;
    if (std::fabs(scaled - nearest_hundredth) <= representation_tolerance) {
        scaled = nearest_hundredth;
    }
    return std::ceil(scaled) / scale;
}

}  // namespace util
}  // namespace kuikly

#endif  // CORE_RENDER_OHOS_KRLAYOUTSIZEFORMATTER_H
