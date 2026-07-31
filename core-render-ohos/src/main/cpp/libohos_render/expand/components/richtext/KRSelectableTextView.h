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

#ifndef CORE_RENDER_OHOS_KRSELECTABLETEXTVIEW_H
#define CORE_RENDER_OHOS_KRSELECTABLETEXTVIEW_H

#include "libohos_render/export/IKRRenderViewExport.h"

/**
 * System-selectable read-only plain text.
 *
 * An ArkUI Text node with the system copy option enabled
 * (ARKUI_COPY_OPTIONS_LOCAL_DEVICE guarantees local-device copy scope) so
 * long-press brings up the platform's native selection menu. Baseline
 * guarantee: select / select-all / copy. Any additional menu items are
 * whatever the OS selection menu actually offers on the running system
 * version — they must not be assumed from the copy option. Never an input
 * surface: no IME, no mutation except through the "text" prop.
 */
class KRSelectableTextView : public IKRRenderViewExport {
 public:
    ArkUI_NodeHandle CreateNode() override;
    void DidInit() override;
    bool SetProp(const std::string &prop_key, const KRAnyValue &prop_value,
                 const KRRenderCallback event_call_back = nullptr) override;
    bool ReuseEnable() override {
        // Selection state must never leak across reuse.
        return false;
    }

 private:
    void UpdateFont();

    float font_size_ = 15;
    ArkUI_FontWeight font_weight_ = ARKUI_FONT_WEIGHT_NORMAL;
};

#endif  // CORE_RENDER_OHOS_KRSELECTABLETEXTVIEW_H
