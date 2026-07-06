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

#include "libohos_render/manager/KRKeyboardManager.h"
#include "libohos_render/utils/KRConvertUtil.h"
#include "libohos_render/utils/KRViewUtil.h"
#include "libohos_render/expand/components/input/KRTextAreaView.h"
#include <algorithm>
#include <arkui/native_node.h>
#include <arkui/native_type.h>

constexpr char kLineHeight[] = "lineHeight";
constexpr char kSlockSystemNewlineAction[] = "slockSystemNewlineAction";
constexpr int32_t kSystemNewlineMenuItemId = ARKUI_TEXT_MENU_ITEM_ID_APP_RESERVED_BEGIN;

void KRTextAreaView::DidInit() {
    // 调用父类的 DidInit 来设置默认样式（透明背景、无圆角、无padding）
    KRTextFieldView::DidInit();
    // 设置弹起软键盘之后默认不收回
    ArkUI_NumberValue value = {.i32 = 0};
    ArkUI_AttributeItem item = {&value, 1};
    kuikly::util::GetNodeApi()->setAttribute(GetNode(), NODE_TEXT_AREA_BLUR_ON_SUBMIT, &item);
}

bool KRTextAreaView::SetProp(const std::string &prop_key, const KRAnyValue &prop_value,
                             const KRRenderCallback event_call_back) {
    if (kuikly::util::isEqual(prop_key, kLineHeight)) {
        kuikly::util::UpdateTextAreaNodeLineHeight(GetNode(), prop_value->toFloat());
        return true;
    }
    if (kuikly::util::isEqual(prop_key, kSlockSystemNewlineAction)) {
        if (prop_value->toInt() == 1) {
            SetupSystemNewlineEditMenu();
        } else {
            TeardownSystemNewlineEditMenu();
        }
        return true;
    }
    return KRTextFieldView::SetProp(prop_key, prop_value, event_call_back);
}


void KRTextAreaView::UpdateInputNodePlaceholder(const std::string &propValue) {
    ArkUI_AttributeItem item = {.string = propValue.c_str()};
    kuikly::util::GetNodeApi()->setAttribute(GetNode(), NODE_TEXT_AREA_PLACEHOLDER, &item);
}
void KRTextAreaView::UpdateInputNodePlaceholderColor(const std::string &propValue) {
    ArkUI_NumberValue value = {.u32 = kuikly::util::ConvertToHexColor(propValue)};
    ArkUI_AttributeItem item = {&value, 1};
    kuikly::util::GetNodeApi()->setAttribute(GetNode(), NODE_TEXT_AREA_PLACEHOLDER_COLOR, &item);
}
void KRTextAreaView::UpdateInputNodeColor(const std::string &propValue) {
    ArkUI_NumberValue preparedColorValue[] = {{.u32 = kuikly::util::ConvertToHexColor(propValue)}};
    ArkUI_AttributeItem colorItem = {preparedColorValue, sizeof(preparedColorValue) / sizeof(ArkUI_NumberValue)};
    kuikly::util::GetNodeApi()->setAttribute(GetNode(), NODE_FONT_COLOR, &colorItem);
}
void KRTextAreaView::UpdateInputNodeCaretrColor(const std::string &propValue) {
    ArkUI_NumberValue value = {.u32 = kuikly::util::ConvertToHexColor(propValue)};
    ArkUI_AttributeItem item = {&value, 1};
    kuikly::util::GetNodeApi()->setAttribute(GetNode(), NODE_TEXT_AREA_CARET_COLOR, &item);
}
void KRTextAreaView::UpdateInputNodeSelectionColor(const std::string &propValue) {
    kuikly::util::UpdateTextAreaNodeSelectionColor(GetNode(), kuikly::util::ConvertToHexColor(propValue));
}

void KRTextAreaView::UpdateInputNodeKeyboardType(const std::string &propValue) {
    ArkUI_TextAreaType type = ARKUI_TEXTAREA_TYPE_NORMAL;
    if (propValue == "number") {
        type = ARKUI_TEXTAREA_TYPE_NUMBER;
    }else if (propValue == "email") {
        type = ARKUI_TEXTAREA_TYPE_EMAIL;
    }

    ArkUI_NumberValue value[] = {{.i32 = type}};
    ArkUI_AttributeItem item = {value, sizeof(value) / sizeof(ArkUI_NumberValue)};
    kuikly::util::GetNodeApi()->setAttribute(GetNode(), NODE_TEXT_AREA_TYPE, &item);
}

void KRTextAreaView::UpdateInputNodeMaxLength(int maxLength) {
    ArkUI_NumberValue value[] = {{.i32 = maxLength}};
    ArkUI_AttributeItem item = {value, sizeof(value) / sizeof(ArkUI_NumberValue)};
    kuikly::util::GetNodeApi()->setAttribute(GetNode(), NODE_TEXT_INPUT_MAX_LENGTH, &item);
}

uint32_t KRTextAreaView::GetInputNodeSelectionStartPosition() {
    auto item = kuikly::util::GetNodeApi()->getAttribute(GetNode(), NODE_TEXT_AREA_TEXT_SELECTION);
    return item ? item->value[0].i32 : 0;
}
void KRTextAreaView::UpdateInputNodeSelectionStartPosition(uint32_t index) {
    std::array<ArkUI_NumberValue, 2> value = {{{.i32 = static_cast<int32_t>(index)}, {.i32 = static_cast<int32_t>(index)}}};
    ArkUI_AttributeItem item = {value.data(), value.size()};
    kuikly::util::GetNodeApi()->setAttribute(GetNode(), NODE_TEXT_AREA_TEXT_SELECTION, &item);
}
std::pair<uint32_t, uint32_t> KRTextAreaView::GetInputNodeTextSelectionRange() {
    auto item = kuikly::util::GetNodeApi()->getAttribute(GetNode(), NODE_TEXT_AREA_TEXT_SELECTION);
    if (item && item->size >= 2) {
        return {static_cast<uint32_t>(item->value[0].i32), static_cast<uint32_t>(item->value[1].i32)};
    }
    return {0, 0};
}
void KRTextAreaView::UpdateInputNodePlaceholderFont(uint32_t font_size, ArkUI_FontWeight font_weight) {
    ArkUI_NumberValue fontWeight = {.i32 = font_weight};
    ArkUI_NumberValue tempStyle = {.i32 = ARKUI_FONT_STYLE_NORMAL};
    const auto &rootView = GetRootView().lock();
    bool fontSizeScaleFollowSystem = true;
    bool font_size_px = 0;
    if (rootView) {
        fontSizeScaleFollowSystem = rootView->GetContext()->Config()->GetFontSizeScaleFollowSystem();
        font_size_px = rootView->GetContext()->Config()->fp2px(font_size);
    }
    float font_size_temp = font_size;
    auto node = GetNode();
    // 如果禁用输入框内字体缩放需要设置为px
    if (!fontSizeScaleFollowSystem) {
        kuikly::util::GetNodeApi()->setLengthMetricUnit(node, ARKUI_LENGTH_METRIC_UNIT_PX);
        font_size_temp = font_size_px;
    }
    std::array<ArkUI_NumberValue, 3> value = {{{.f32 = font_size_temp}, tempStyle, fontWeight}};
    ArkUI_AttributeItem item = {value.data(), value.size()};
    kuikly::util::GetNodeApi()->setAttribute(node, NODE_TEXT_AREA_PLACEHOLDER_FONT, &item);
    if (!fontSizeScaleFollowSystem) {
        kuikly::util::GetNodeApi()->setLengthMetricUnit(node, ARKUI_LENGTH_METRIC_UNIT_DEFAULT);
    }
    {
        ArkUI_NumberValue valueSize[] = {{.f32 = static_cast<float>(font_size)}};
        ArkUI_AttributeItem itemSize = {valueSize, sizeof(valueSize) / sizeof(ArkUI_NumberValue)};
        kuikly::util::GetNodeApi()->setAttribute(node, NODE_FONT_SIZE, &itemSize);
    }
    {
        ArkUI_NumberValue fontWeight = {.i32 = font_weight};
        ArkUI_NumberValue valueWeight[] = {fontWeight};
        ArkUI_AttributeItem itemWeight = {valueWeight, 1};
        kuikly::util::GetNodeApi()->setAttribute(node, NODE_FONT_WEIGHT, &itemWeight);
    }
}
void KRTextAreaView::UpdateInputNodeContentText(const std::string &text) {
    ArkUI_AttributeItem item = {.string = text.c_str()};
    kuikly::util::GetNodeApi()->setAttribute(GetNode(), NODE_TEXT_AREA_TEXT, &item);
}

void KRTextAreaView::OnDestroy() {
    TeardownSystemNewlineEditMenu();
    KRTextFieldView::OnDestroy();
}

void KRTextAreaView::SetupSystemNewlineEditMenu() {
    if (system_newline_edit_menu_options_ != nullptr) {
        return;
    }
    auto options = OH_ArkUI_TextEditMenuOptions_Create();
    if (options == nullptr) {
        return;
    }
    OH_ArkUI_TextEditMenuOptions_RegisterOnCreateMenuCallback(
        options, this, KRTextAreaView::OnCreateSystemNewlineMenu);
    OH_ArkUI_TextEditMenuOptions_RegisterOnMenuItemClickCallback(
        options, this, KRTextAreaView::OnSystemNewlineMenuItemClick);
    system_newline_edit_menu_options_ = options;

    ArkUI_AttributeItem item = {};
    item.object = options;
    kuikly::util::GetNodeApi()->setAttribute(GetNode(), NODE_TEXT_EDIT_MENU_OPTIONS, &item);
}

void KRTextAreaView::TeardownSystemNewlineEditMenu() {
    if (system_newline_edit_menu_options_ == nullptr) {
        return;
    }
    kuikly::util::GetNodeApi()->resetAttribute(GetNode(), NODE_TEXT_EDIT_MENU_OPTIONS);
    OH_ArkUI_TextEditMenuOptions_Dispose(system_newline_edit_menu_options_);
    system_newline_edit_menu_options_ = nullptr;
}

void KRTextAreaView::OnCreateSystemNewlineMenu(ArkUI_TextMenuItemArray *items, void *userData) {
    if (items == nullptr) {
        return;
    }
    auto item = OH_ArkUI_TextMenuItem_Create();
    if (item == nullptr) {
        return;
    }
    OH_ArkUI_TextMenuItem_SetId(item, kSystemNewlineMenuItemId);
    OH_ArkUI_TextMenuItem_SetContent(item, "换行");

    int32_t itemCount = 0;
    if (OH_ArkUI_TextMenuItemArray_GetSize(items, &itemCount) != ARKUI_ERROR_CODE_NO_ERROR) {
        itemCount = 0;
    }
    OH_ArkUI_TextMenuItemArray_Insert(items, item, itemCount);
    OH_ArkUI_TextMenuItem_Dispose(item);
}

bool KRTextAreaView::OnSystemNewlineMenuItemClick(const ArkUI_TextMenuItem *item, int32_t start, int32_t end,
                                                  void *userData) {
    auto self = static_cast<KRTextAreaView *>(userData);
    if (self == nullptr || item == nullptr) {
        return false;
    }
    int32_t itemId = 0;
    if (OH_ArkUI_TextMenuItem_GetId(item, &itemId) != ARKUI_ERROR_CODE_NO_ERROR ||
        itemId != kSystemNewlineMenuItemId) {
        return false;
    }
    self->InsertNewlineAtSelection(start, end);
    return true;
}

void KRTextAreaView::InsertNewlineAtSelection(int32_t start, int32_t end) {
    std::string text;
    if (auto content = kuikly::util::GetNodeApi()->getAttribute(GetNode(), NODE_TEXT_AREA_TEXT)) {
        if (content->string != nullptr) {
            text = content->string;
        }
    }

    int32_t rangeStart = start;
    int32_t rangeEnd = end;
    if (rangeStart < 0 || rangeEnd < 0) {
        auto selection = GetInputNodeTextSelectionRange();
        rangeStart = static_cast<int32_t>(selection.first);
        rangeEnd = static_cast<int32_t>(selection.second);
    }

    int32_t u16Length = GetUTF16Length(text);
    int32_t u16Start = std::min(rangeStart, rangeEnd);
    int32_t u16End = std::max(rangeStart, rangeEnd);
    u16Start = std::max(0, std::min(u16Start, u16Length));
    u16End = std::max(u16Start, std::min(u16End, u16Length));

    auto u8Start = static_cast<size_t>(GetUTF8ByteCount(text, 0, static_cast<size_t>(u16Start)));
    auto u8End = u8Start + static_cast<size_t>(
        GetUTF8ByteCount(text, u8Start, static_cast<size_t>(u16End - u16Start)));

    std::string newText = text;
    newText.replace(u8Start, u8End - u8Start, "\n");
    UpdateInputNodeContentText(newText);

    KRMainThread::RunOnMainThreadForNextLoop(
        [weakSelf = weak_from_this(), caret = static_cast<uint32_t>(u16Start + 1)]() {
            if (auto strongSelf = std::dynamic_pointer_cast<KRTextAreaView>(weakSelf.lock())) {
                strongSelf->UpdateInputNodeSelectionStartPosition(caret);
            }
        });
}
