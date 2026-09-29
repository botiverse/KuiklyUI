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

#ifndef CORE_RENDER_OHOS_KRSCROLLERCONTENTOFFSET_H
#define CORE_RENDER_OHOS_KRSCROLLERCONTENTOFFSET_H

struct KRScrollerAxisOffsetAdjustment {
    bool should_adjust = false;
    float target_offset = 0.0f;
};

// OHOS implements contentInset by applying a margin to the content view. The
// leading resting offset is therefore always zero on either axis; subtracting
// start/top here would count the same inset again. Only the trailing inset
// extends the maximum scroll range.
inline KRScrollerAxisOffsetAdjustment KRResolveMarginInsetAxisOffset(
    float current_offset, float content_size, float frame_size, float trailing_inset) {
    if (content_size <= frame_size || current_offset < 0.0f) {
        return {true, 0.0f};
    }

    const auto max_offset = content_size + trailing_inset - frame_size;
    if (current_offset > max_offset) {
        return {true, max_offset};
    }
    return {false, current_offset};
}

#endif  // CORE_RENDER_OHOS_KRSCROLLERCONTENTOFFSET_H
