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

#include <cstdio>
#include <cstdlib>
#include <string>

#include "libohos_render/utils/KRLayoutSizeFormatter.h"

namespace {

void RequireFormatted(double width, double height, const char *expected) {
    char actual[64];
    std::snprintf(actual, sizeof(actual), "%.2f|%.2f",
                  kuikly::util::CeilLayoutSizeToHundredth(width),
                  kuikly::util::CeilLayoutSizeToHundredth(height));
    if (std::string(actual) != expected) {
        std::fprintf(stderr, "layout size mismatch: input=%.17g|%.17g expected=%s actual=%s\n",
                     width, height, expected, actual);
        std::exit(1);
    }
}

}  // namespace

int main() {
    RequireFormatted(0.0, 10.0, "0.00|10.00");
    RequireFormatted(10.01, 10.02, "10.01|10.02");
    RequireFormatted(0.07, 1.10, "0.07|1.10");
    RequireFormatted(0.001, 0.004, "0.01|0.01");
    RequireFormatted(0.005, 0.009, "0.01|0.01");
    RequireFormatted(10.0001, 10.009, "10.01|10.01");
    RequireFormatted(10.0101, -10.001, "10.02|-10.00");
    if (!std::isnan(kuikly::util::CeilLayoutSizeToHundredth(
            std::numeric_limits<double>::quiet_NaN())) ||
        !std::isinf(kuikly::util::CeilLayoutSizeToHundredth(
            std::numeric_limits<double>::infinity()))) {
        std::fputs("non-finite layout size handling changed\n", stderr);
        return 1;
    }
    return 0;
}
