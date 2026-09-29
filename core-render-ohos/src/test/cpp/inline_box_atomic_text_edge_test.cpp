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

    // Chrome measurement range. Placeholder-edge boxes keep the full range
    // and let the view trim margins; atomic-edge boxes measure the children
    // only and let the view add padding and border, because the zero-font
    // trailing carrier is not reliably reported by GetRectsForRange.
    const auto placeholder_range = KRMakeInlineBoxChromeRange(10, 20, false);
    assert(placeholder_range.start == 10);
    assert(placeholder_range.end == 20);
    assert(placeholder_range.includes_reserved_edges);
    const auto atomic_range =
        KRMakeInlineBoxChromeRange(static_cast<int>(box_start), static_cast<int>(box_end), true);
    assert(atomic_range.start == static_cast<int>(box_start) + 1);
    assert(atomic_range.end == static_cast<int>(box_end) - 1);
    assert(!atomic_range.includes_reserved_edges);
    assert(layout[atomic_range.start] == kInlineBoxWordJoiner);
    assert(layout[atomic_range.end - 1] == kInlineBoxWordJoiner);
    assert(layout.compare(atomic_range.start + 1, token.size(), token) == 0);
    // Degenerate boxes (no room for both carriers) fall back to the full range.
    const auto degenerate = KRMakeInlineBoxChromeRange(4, 5, true);
    assert(degenerate.start == 4 && degenerate.end == 5 && degenerate.includes_reserved_edges);

    // Fragment geometry. Regression shape (2.28.0-raft.4, OHOS ⑦): a bold
    // mention chip with margin 2 / border 1 / padding 4 vp at dpi 3 was drawn
    // with too much room on the left and its glyphs touching or crossing the
    // right edge -- the whole box sat one leading reserved width (21 px) to
    // the left of the ink. The measured fragment is [100, 160); the ink is
    // [121, 181).
    const float margin = 6.0f, border = 3.0f, padding = 12.0f;
    const float leading_reserved = KRInlineBoxLeadingReservedWidth(margin, border, padding);
    assert(leading_reserved == 21.0f);
    const auto single = KRMakeInlineBoxAtomicChromeEdges(100.0f, 160.0f, true, true, leading_reserved,
                                                        padding, padding, border);
    assert(single.left == 121.0f - (padding + border));
    assert(single.right == 181.0f + (padding + border));
    // A wrapped box: the first fragment ends at an internal wrap boundary (no
    // trailing growth); the continuation starts a new line, so nothing on its
    // line precedes it (no shift) and it only grows at the real span end.
    const auto head = KRMakeInlineBoxAtomicChromeEdges(300.0f, 340.0f, true, false, leading_reserved,
                                                      padding, padding, border);
    assert(head.left == 321.0f - (padding + border));
    assert(head.right == 361.0f);
    const auto tail = KRMakeInlineBoxAtomicChromeEdges(0.0f, 30.0f, false, true, 0.0f, padding, padding, border);
    assert(tail.left == 0.0f);
    assert(tail.right == 30.0f + (padding + border));
    // Inline code (margin 0 / border 0 / padding 4 vp): with no shift the box
    // is exactly tight on the right -- the raft.4 symptom; with the shift it is
    // padded symmetrically.
    const float code_reserved = KRInlineBoxLeadingReservedWidth(0.0f, 0.0f, 12.0f);
    assert(code_reserved == 12.0f);
    const auto code = KRMakeInlineBoxAtomicChromeEdges(50.0f, 90.0f, true, true, code_reserved, 12.0f, 12.0f, 0.0f);
    assert(code.left == 50.0f && code.right == 114.0f);

    // Per-line accumulation (measured on 10000978: two mention chips on one
    // line, the first 45 px / -2 px, the second 95 px / -53 px). The second
    // box sits behind its predecessor's leading and trailing carriers plus
    // its own leading one; a chip on another line starts from zero.
    KRInlineBoxLineReservedWidths passed;
    const float trailing_reserved = KRInlineBoxTrailingReservedWidth(padding, border, margin);
    assert(trailing_reserved == 21.0f);
    assert(passed.Before(200.0f) == 0.0f);
    const float first_shift = passed.Before(200.0f) + leading_reserved;
    assert(first_shift == 21.0f);
    passed.Add(200.0f, leading_reserved);
    passed.Add(200.0f, trailing_reserved);
    assert(passed.Before(200.0f) == 42.0f);
    assert(passed.Before(200.5f) == 42.0f);  // same line within merge tolerance
    assert(passed.Before(260.0f) == 0.0f);   // another line
    const float second_shift = passed.Before(200.0f) + leading_reserved;
    assert(second_shift == 63.0f);
    const auto second = KRMakeInlineBoxAtomicChromeEdges(400.0f, 470.0f, true, true, second_shift,
                                                        padding, padding, border);
    assert(second.left == 463.0f - (padding + border));
    assert(second.right == 533.0f + (padding + border));
    // A placeholder-edge box later on the same line is displaced by the
    // carriers passed, but has no carriers of its own to add.
    passed.Add(200.0f, leading_reserved);
    passed.Add(200.0f, trailing_reserved);
    assert(passed.Before(200.0f) == 84.0f);

    std::cout << "OHOS inline-box atomic text edge test: PASS\n";
    return 0;
}
