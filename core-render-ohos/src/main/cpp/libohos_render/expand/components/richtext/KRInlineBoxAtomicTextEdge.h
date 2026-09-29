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

#ifndef CORE_RENDER_OHOS_KRINLINEBOXATOMICTEXTEDGE_H
#define CORE_RENDER_OHOS_KRINLINEBOXATOMICTEXTEDGE_H

#include <cmath>
#include <cstddef>
#include <vector>

namespace kuikly::richtext {

// OH_Drawing placeholders are independent SkParagraph words, so a word
// joiner cannot bind a placeholder edge to the text inside an inline box.
// Text-only boxes instead reserve each edge with a non-breaking text cluster:
// a zero-font U+2011 NON-BREAKING HYPHEN has zero glyph advance and letter
// spacing supplies the exact requested width. Unlike NBSP it is not whitespace,
// so SkParagraph's too-long-word fallback cannot pick the edge itself as an
// intra-word space. ZWSP stays outside the box range to preserve legal breaks
// before and after the otherwise non-breaking group.
constexpr char16_t kInlineBoxBoundaryBreak = u'\u200B';
constexpr char16_t kInlineBoxReservedEdge = u'\u2011';
constexpr char16_t kInlineBoxWordJoiner = u'\u2060';

struct KRInlineBoxAtomicTextEdgeStyle {
    float font_size_px = 0.0f;
    float letter_spacing_px = 0.0f;
};

inline KRInlineBoxAtomicTextEdgeStyle KRMakeInlineBoxAtomicTextEdgeStyle(float edge_width_px) {
    return KRInlineBoxAtomicTextEdgeStyle{0.0f, edge_width_px};
}

inline bool KRCanUseInlineBoxAtomicTextEdges(bool has_placeholder_child) {
    return !has_placeholder_child;
}

// The layout skeleton for N ordinary-text children is:
//   ZWSP NBHY (WJ child){N} WJ NBHY ZWSP
// Child payloads are emitted separately, so this count covers only the
// layout-only clusters surrounding them.
inline size_t KRInlineBoxAtomicLayoutOnlyClusterCount(size_t child_count) {
    return child_count + 5;
}

// Range the chip chrome is measured over, in layout (UTF-16) offsets.
//
// Placeholder edges are real SkParagraph runs, so measuring the whole box
// range [layout_start, layout_end) returns the reserved width and the view
// only trims the margins. The zero-font letter-spaced edge carriers are not
// reliably reported by OH_Drawing_TypographyGetRectsForRange: on-device the
// trailing carrier contributed no width, so the chrome ended before the last
// glyphs of the token even though layout had reserved the space. Measure the
// children only ([layout_start + 1, layout_end - 1)) and let the view add
// padding and border back around the text it actually painted.
struct KRInlineBoxChromeRange {
    int start = 0;
    int end = 0;
    bool includes_reserved_edges = false;
};

inline KRInlineBoxChromeRange KRMakeInlineBoxChromeRange(int layout_start, int layout_end,
                                                          bool atomic_text_edges) {
    if (!atomic_text_edges || layout_end - layout_start < 2) {
        return KRInlineBoxChromeRange{layout_start, layout_end, true};
    }
    return KRInlineBoxChromeRange{layout_start + 1, layout_end - 1, false};
}

struct KRInlineBoxChromeEdges {
    float left = 0.0f;
    float right = 0.0f;
};

// Horizontal extent of one chrome fragment of an atomic-edge box.
//
// The chrome range of such a box covers the children only (see
// KRMakeInlineBoxChromeRange). GetRectsForRange reports those children as if
// the zero-font leading carrier had no advance, while the glyphs are drawn
// after its reserved letter spacing, so the reported fragment sits to the left
// of the ink by `shift_px`: the reserved width of every zero-font carrier that
// precedes this fragment on its line -- earlier boxes' leading and trailing
// carriers (see KRInlineBoxLineReservedWidths) plus the box's own leading edge
// for the fragment that starts the box. A wrapped continuation starts a new
// line, so only carriers already passed on that line count. Move the fragment
// back over the ink first, then grow it by padding and border on real span
// ends -- never on internal wrap boundaries.
inline KRInlineBoxChromeEdges KRMakeInlineBoxAtomicChromeEdges(float fragment_left, float fragment_right,
                                                                 bool is_span_start, bool is_span_end,
                                                                 float shift_px, float padding_start_px,
                                                                 float padding_end_px, float border_width_px) {
    KRInlineBoxChromeEdges edges{fragment_left + shift_px, fragment_right + shift_px};
    if (is_span_start) {
        edges.left -= padding_start_px + border_width_px;
    }
    if (is_span_end) {
        edges.right += padding_end_px + border_width_px;
    }
    return edges;
}

// Reserved widths of the zero-font carriers of an atomic-edge box: the layout
// space the leading edge occupies before the first child glyph and the
// trailing edge after the last one.
inline float KRInlineBoxLeadingReservedWidth(float margin_start_px, float border_width_px,
                                              float padding_start_px) {
    return margin_start_px + border_width_px + padding_start_px;
}

inline float KRInlineBoxTrailingReservedWidth(float padding_end_px, float border_width_px,
                                               float margin_end_px) {
    return padding_end_px + border_width_px + margin_end_px;
}

// Per-line running sum of reserved carrier width already passed.
//
// The displacement accumulates along a line: every rect reported after a
// zero-font carrier is short by that carrier's reserved width, so the second
// chip on a line sat three reserved widths left of its ink (its predecessor's
// leading and trailing carriers plus its own leading one) while the first sat
// one. Lines are keyed by fragment centre y, matching the tolerance used when
// fragments are merged per line.
class KRInlineBoxLineReservedWidths {
public:
    float Before(float line_center_y) const {
        for (const auto &entry : entries_) {
            if (std::fabs(entry.center_y - line_center_y) <= 1.0f) {
                return entry.px;
            }
        }
        return 0.0f;
    }

    void Add(float line_center_y, float px) {
        for (auto &entry : entries_) {
            if (std::fabs(entry.center_y - line_center_y) <= 1.0f) {
                entry.px += px;
                return;
            }
        }
        entries_.push_back(Entry{line_center_y, px});
    }

private:
    struct Entry {
        float center_y;
        float px;
    };
    std::vector<Entry> entries_;
};

}  // namespace kuikly::richtext

#endif  // CORE_RENDER_OHOS_KRINLINEBOXATOMICTEXTEDGE_H
