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

#ifndef CORE_RENDER_OHOS_KRINLINEBOXBOUNDARYGLUE_H
#define CORE_RENDER_OHOS_KRINLINEBOXBOUNDARYGLUE_H

#include <string>

struct KRInlineBoxBoundaryGluePlan {
    bool before = false;
    bool after = false;
};

/**
 * Keeps a bracket directly adjacent to an atomic inline box on the same line.
 *
 * OH_Drawing's BREAK_WORD mode may otherwise choose the native span boundary
 * after `[` (or before `]`) even though the inline box itself is atomic. The
 * caller lowers each true edge to a layout-only U+2060 and keeps semantic text
 * mapped to the original neighboring spans.
 */
inline KRInlineBoxBoundaryGluePlan KRResolveInlineBoxBracketBoundaryGlue(
    const std::string &previous_text, const std::string &next_text) {
    return {
        !previous_text.empty() && previous_text.back() == '[',
        !next_text.empty() && next_text.front() == ']',
    };
}

inline std::u16string KRInlineBoxBoundaryGlueText(bool enabled) {
    return enabled ? std::u16string(1, u'\u2060') : std::u16string();
}

#endif  // CORE_RENDER_OHOS_KRINLINEBOXBOUNDARYGLUE_H
