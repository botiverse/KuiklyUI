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
#include <string>

#include "libohos_render/expand/components/richtext/KRInlineBoxAtomicTextEdge.h"

int main() {
    using namespace kuikly::richtext;

    assert(KRCanUseInlineBoxAtomicTextEdges(false));
    assert(!KRCanUseInlineBoxAtomicTextEdges(true));

    const auto leading = KRMakeInlineBoxAtomicTextEdgeStyle(9.25f);
    const auto trailing = KRMakeInlineBoxAtomicTextEdgeStyle(7.5f);
    assert(leading.font_size_px == 0.0f);
    assert(leading.letter_spacing_px == 9.25f);
    assert(trailing.font_size_px == 0.0f);
    assert(trailing.letter_spacing_px == 7.5f);

    // Regression shape: ordinary text nearly fills a line, followed by one
    // mention tag. There are deliberately no source brackets. The two ZWSPs
    // are outside the box, while both zero-font non-breaking edge carriers and
    // the token are joined into one non-breaking text word.
    const std::u16string prefix = u"ordinary text consuming almost all line width ";
    const std::u16string token = u"@artin";
    std::u16string layout = prefix;
    layout.push_back(kInlineBoxBoundaryBreak);
    const size_t box_start = layout.size();
    layout.push_back(kInlineBoxReservedEdge);
    layout.push_back(kInlineBoxWordJoiner);
    layout.append(token);
    layout.push_back(kInlineBoxWordJoiner);
    layout.push_back(kInlineBoxReservedEdge);
    const size_t box_end = layout.size();
    layout.push_back(kInlineBoxBoundaryBreak);

    assert(layout.find(u'[') == std::u16string::npos);
    assert(layout.find(u']') == std::u16string::npos);
    assert(layout[box_start] == kInlineBoxReservedEdge);
    assert(layout[box_end - 1] == kInlineBoxReservedEdge);
    assert(layout[box_start - 1] == kInlineBoxBoundaryBreak);
    assert(layout[box_end] == kInlineBoxBoundaryBreak);
    assert(box_end - box_start == token.size() + KRInlineBoxAtomicLayoutOnlyClusterCount(1) - 2);

    std::cout << "OHOS inline-box atomic text edge test: PASS\n";
    return 0;
}
