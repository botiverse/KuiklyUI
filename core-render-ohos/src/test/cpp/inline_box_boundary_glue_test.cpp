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

#include "libohos_render/expand/components/richtext/KRInlineBoxBoundaryGlue.h"

static void AssertPlan(const KRInlineBoxBoundaryGluePlan &actual, bool before, bool after) {
    assert(actual.before == before);
    assert(actual.after == after);
}

int main() {
    // The reported Markdown shapes: an atomic mention/task box is surrounded
    // by literal square brackets emitted by neighboring ordinary text spans.
    AssertPlan(KRResolveInlineBoxBracketBoundaryGlue("package 后交 [", "]；"), true, true);
    AssertPlan(KRResolveInlineBoxBracketBoundaryGlue("不能解除 [", "] 的 gate"), true, true);

    // Each edge is independent so paragraph/style segmentation remains safe.
    AssertPlan(KRResolveInlineBoxBracketBoundaryGlue("[", " ordinary"), true, false);
    AssertPlan(KRResolveInlineBoxBracketBoundaryGlue("ordinary ", "]"), false, true);

    // Only direct bracket adjacency changes. Whitespace, ordinary CJK, URLs,
    // and other punctuation retain the renderer's existing wrap behavior.
    AssertPlan(KRResolveInlineBoxBracketBoundaryGlue("[ ", "]"), false, true);
    AssertPlan(KRResolveInlineBoxBracketBoundaryGlue("[", " ]"), true, false);
    AssertPlan(KRResolveInlineBoxBracketBoundaryGlue("普通正文（", "）继续"), false, false);
    AssertPlan(KRResolveInlineBoxBracketBoundaryGlue("https://raft.build/", ", next"), false, false);

    // Glue is layout-only. The production mapping emits no semantic text for
    // this part, so copy/selection/a11y continue to expose the original `[]`.
    assert(KRInlineBoxBoundaryGlueText(false).empty());
    assert(KRInlineBoxBoundaryGlueText(true) == std::u16string(1, u'\u2060'));

    std::cout << "OHOS inline-box bracket boundary glue test: PASS\n";
    return 0;
}
