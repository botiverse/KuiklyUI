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

#include "libohos_render/expand/components/richtext/KRSelectableTextView.h"

#include "libohos_render/utils/KRConvertUtil.h"
#include "libohos_render/utils/KRViewUtil.h"

static constexpr const char *kSelectableTextPropText = "text";
static constexpr const char *kSelectableTextPropFontSize = "fontSize";
static constexpr const char *kSelectableTextPropFontWeight = "fontWeight";
static constexpr const char *kSelectableTextPropColor = "color";
static constexpr const char *kSelectableTextPropLineHeight = "lineHeight";
static constexpr const char *kSelectableTextPropTextAlign = "textAlign";

ArkUI_NodeHandle KRSelectableTextView::CreateNode() {
    return kuikly::util::GetNodeApi()->createNode(ARKUI_NODE_TEXT);
}

void KRSelectableTextView::DidInit() {
    IKRRenderViewExport::DidInit();
    // Enable the system selection/copy menu; keeps the surface read-only.
    ArkUI_NumberValue copy_option = {.i32 = ARKUI_COPY_OPTIONS_LOCAL_DEVICE};
    ArkUI_AttributeItem copy_item = {&copy_option, 1};
    kuikly::util::GetNodeApi()->setAttribute(GetNode(), NODE_TEXT_COPY_OPTION, &copy_item);
    UpdateFont();
}

bool KRSelectableTextView::SetProp(const std::string &prop_key, const KRAnyValue &prop_value,
                                   const KRRenderCallback event_call_back) {
    if (kuikly::util::isEqual(prop_key, kSelectableTextPropText)) {
        auto text = prop_value->toString();
        ArkUI_AttributeItem item = {.string = text.c_str()};
        kuikly::util::GetNodeApi()->setAttribute(GetNode(), NODE_TEXT_CONTENT, &item);
        return true;
    }
    if (kuikly::util::isEqual(prop_key, kSelectableTextPropFontSize)) {
        font_size_ = prop_value->toFloat();
        UpdateFont();
        return true;
    }
    if (kuikly::util::isEqual(prop_key, kSelectableTextPropFontWeight)) {
        float scale = 1.0;
        if (auto root = GetRootView().lock()) {
            scale = root->GetContext()->Config()->GetFontWeightScale();
        }
        font_weight_ = kuikly::util::ConvertArkUIFontWeight(prop_value->toInt(), scale);
        UpdateFont();
        return true;
    }
    if (kuikly::util::isEqual(prop_key, kSelectableTextPropColor)) {
        kuikly::util::UpdateInputNodeColor(GetNode(), kuikly::util::ConvertToHexColor(prop_value->toString()));
        return true;
    }
    if (kuikly::util::isEqual(prop_key, kSelectableTextPropLineHeight)) {
        auto line_height = prop_value->toFloat();
        if (line_height > 0) {
            kuikly::util::UpdateTextAreaNodeLineHeight(GetNode(), line_height);
        } else {
            kuikly::util::GetNodeApi()->resetAttribute(GetNode(), NODE_TEXT_LINE_HEIGHT);
        }
        return true;
    }
    if (kuikly::util::isEqual(prop_key, kSelectableTextPropTextAlign)) {
        kuikly::util::UpdateInputNodeTextAlign(GetNode(), prop_value->toString());
        return true;
    }
    return IKRRenderViewExport::SetProp(prop_key, prop_value, event_call_back);
}

void KRSelectableTextView::UpdateFont() {
    ArkUI_NumberValue size_value[] = {{.f32 = font_size_}};
    ArkUI_AttributeItem size_item = {size_value, 1};
    kuikly::util::GetNodeApi()->setAttribute(GetNode(), NODE_FONT_SIZE, &size_item);

    ArkUI_NumberValue weight_value[] = {{.i32 = font_weight_}};
    ArkUI_AttributeItem weight_item = {weight_value, 1};
    kuikly::util::GetNodeApi()->setAttribute(GetNode(), NODE_FONT_WEIGHT, &weight_item);
}
