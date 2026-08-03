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

#include <cassert>
#include <iostream>

#include "libohos_render/expand/components/scroller/KRScrollerContentOffset.h"

static void AssertAdjustment(const KRScrollerAxisOffsetAdjustment &actual,
                             bool should_adjust, float target_offset) {
    assert(actual.should_adjust == should_adjust);
    assert(actual.target_offset == target_offset);
}

int main() {
    // Horizontal start and vertical top share this axis resolver. Margin owns
    // the inset displacement, so a negative offset always rests at zero.
    AssertAdjustment(KRResolveMarginInsetAxisOffset(-80.0f, 400.0f, 200.0f, 0.0f), true, 0.0f);
    AssertAdjustment(KRResolveMarginInsetAxisOffset(0.0f, 400.0f, 200.0f, 0.0f), false, 0.0f);
    AssertAdjustment(KRResolveMarginInsetAxisOffset(40.0f, 400.0f, 200.0f, 0.0f), false, 40.0f);

    // Content that already fits has no scroll range.
    AssertAdjustment(KRResolveMarginInsetAxisOffset(25.0f, 180.0f, 200.0f, 0.0f), true, 0.0f);

    // The trailing inset extends the maximum range and is clamped once.
    AssertAdjustment(KRResolveMarginInsetAxisOffset(130.0f, 300.0f, 200.0f, 30.0f), false, 130.0f);
    AssertAdjustment(KRResolveMarginInsetAxisOffset(150.0f, 300.0f, 200.0f, 30.0f), true, 130.0f);

    std::cout << "OHOS scroller margin-inset axis offset test: PASS\n";
    return 0;
}
