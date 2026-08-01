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

#include <cstddef>

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

}  // namespace kuikly::richtext

#endif  // CORE_RENDER_OHOS_KRINLINEBOXATOMICTEXTEDGE_H
