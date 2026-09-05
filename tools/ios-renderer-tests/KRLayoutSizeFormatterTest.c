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

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "KRLayoutSizeFormatter.h"

static void require_formatted(double width, double height, const char *expected) {
    char actual[64];
    snprintf(actual, sizeof(actual), "%.2f|%.2f",
             KRCeilLayoutSizeToHundredth(width),
             KRCeilLayoutSizeToHundredth(height));
    if (strcmp(actual, expected) != 0) {
        fprintf(stderr, "layout size mismatch: input=%.17g|%.17g expected=%s actual=%s\n",
                width, height, expected, actual);
        exit(1);
    }
}

int main(void) {
    require_formatted(0.0, 10.0, "0.00|10.00");
    require_formatted(10.01, 10.02, "10.01|10.02");
    require_formatted(0.07, 1.10, "0.07|1.10");
    require_formatted(0.001, 0.004, "0.01|0.01");
    require_formatted(0.005, 0.009, "0.01|0.01");
    require_formatted(10.0001, 10.009, "10.01|10.01");
    require_formatted(10.0101, -10.001, "10.02|-10.00");
    if (!isnan(KRCeilLayoutSizeToHundredth(NAN)) ||
        !isinf(KRCeilLayoutSizeToHundredth(INFINITY))) {
        fputs("non-finite layout size handling changed\n", stderr);
        return 1;
    }
    return 0;
}
