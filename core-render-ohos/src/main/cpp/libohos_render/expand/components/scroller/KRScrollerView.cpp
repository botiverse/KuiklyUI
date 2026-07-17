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

#include <algorithm>
#include <memory>

#include "libohos_render/expand/components/scroller/KRScrollerView.h"
#include "libohos_render/expand/components/scroller/KRScrollReplacementPolicy.h"

#include <cfloat>
#include <cmath>
#include <arkui/native_node.h>
#include <deviceinfo.h>
#include "libohos_render/expand/components/view/KRView.h"
#include "libohos_render/foundation/type/KRRenderValue.h"
#include "libohos_render/scheduler/KRContextScheduler.h"
#include "libohos_render/utils/KRJSONObject.h"
#include "libohos_render/utils/KRRenderLoger.h"


#ifdef __cplusplus
extern "C" {
#endif
// Remove this declaration if compatable api is raised to 18 and above
extern void* OH_ArkUI_GestureInterrupter_GetUserData(ArkUI_GestureInterruptInfo* event) __attribute__((weak));
#ifdef __cplusplus
};
#endif

constexpr int FLING_SPEED_LIMIT_API_LEVEL = 18;
constexpr ArkUI_NodeAttributeType kScrollFlingSpeedLimitAttr =
    static_cast<ArkUI_NodeAttributeType>(1002019);

static bool IsFlingSpeedLimitApiAvailable() {
    return OH_GetSdkApiVersion() >= FLING_SPEED_LIMIT_API_LEVEL;
}

constexpr char kPropNameDirectionRow[] = "directionRow";
constexpr char kPropNamePagingEnabled[] = "pagingEnabled";
constexpr char kPropNameScrollEnabled[] = "scrollEnabled";
constexpr char kPropNameVerticalBounces[] = "verticalbounces";
constexpr char kPropNameHorizontalBounces[] = "horizontalbounces";
constexpr char kPropNameBouncesEnable[] = "bouncesEnable";
constexpr char kPropNameLimitHeaderBounces[] = "limitHeaderBounces";
constexpr char kPropNameShowScrollerIndicator[] = "showScrollerIndicator";
constexpr char kPropNameNestedScroll[] = "nestedScroll";
constexpr char kPropNameFlingEnable[] = "flingEnable";
constexpr char kPropNameFlingSpeedLimit[] = "flingSpeedLimit";
constexpr char kPropKeyNestedScrollForward[] = "forward";
constexpr char kPropKeyNestedScrollBackward[] = "backward";

constexpr char kEventNameScroll[] = "scroll";
constexpr char kEventNameDragBegin[] = "dragBegin";
constexpr char kEventNameWillDragEnd[] = "willDragEnd";
constexpr char kEventNameDragEnd[] = "dragEnd";
constexpr char kEventNameScrollEnd[] = "scrollEnd";
constexpr char kEventKeyOffsetX[] = "offsetX";
constexpr char kEventKeyOffsetY[] = "offsetY";
constexpr char kEventKeyContentWidth[] = "contentWidth";
constexpr char kEventKeyContentHeight[] = "contentHeight";
constexpr char kEventKeyViewWidth[] = "viewWidth";
constexpr char kEventKeyViewHeight[] = "viewHeight";
constexpr char kEventKeyIsDragging[] = "isDragging";
constexpr char kEventKeyNativeScrollPhase[] = "nativeScrollPhase";
constexpr char kEventKeyNativeInteractionEpoch[] = "nativeInteractionEpoch";
constexpr char kEventKeyLayoutRevision[] = "layoutRevision";
constexpr char kEventKeyInsetRevision[] = "insetRevision";
constexpr char kEventKeyVelocityX[] = "velocityX";
constexpr char kEventKeyVelocityY[] = "velocityY";

constexpr char kMethodNameContentOffset[] = "contentOffset";
constexpr char kMethodNameContentInset[] = "contentInset";
constexpr char kMethodNameContentInsetWhenDragEnd[] = "contentInsetWhenEndDrag";
constexpr char kMethodNameAbortContentOffsetAnimate[] = "abortContentOffsetAnimate";
constexpr char kMethodNamePrepareForComposeReuse[] = "prepareForComposeReuse";

ArkUI_NodeHandle KRScrollerContentView::CreateNode() {
    return kuikly::util::GetNodeApi()->createNode(ARKUI_NODE_STACK);
}

void KRScrollerContentView::DidInit() {
    RegisterEvent(NODE_EVENT_ON_AREA_CHANGE);
}

bool KRScrollerContentView::CustomSetViewFrame() {
    return true;
}

void KRScrollerContentView::SetRenderViewFrame(const KRRect &frame) {
    IKRRenderViewExport::SetRenderViewFrame(frame);
    kuikly::util::UpdateNodeSize(GetNode(), frame.width, frame.height);
    handling_set_view_frame_ = true;
}

void KRScrollerContentView::AddContentScrollObserver(IKRContentScrollObserver *observer) {
    contentScrollObservers_.insert(observer);
}

void KRScrollerContentView::RemoveContentScrollObserver(IKRContentScrollObserver *observer) {
    contentScrollObservers_.erase(observer);
}

void KRScrollerContentView::DidInsertSubRenderView(const std::shared_ptr<IKRRenderViewExport> &sub_render_view,
                                                   int index) {
    IKRRenderViewExport::DidInsertSubRenderView(sub_render_view, index);
    for (IKRContentScrollObserver *observer : contentScrollObservers_) {
        observer->ContentViewDidInsertSubview();
    }
}

void KRScrollerContentView::DidMoveToParentView() {
    IKRRenderViewExport::DidMoveToParentView();
    for (IKRContentScrollObserver *observer : contentScrollObservers_) {
        observer->ContentViewDidMoveToParentView();
    }
}

void KRScrollerContentView::WillRemoveFromParentView() {
    IKRRenderViewExport::WillRemoveFromParentView();
    for (IKRContentScrollObserver *observer : contentScrollObservers_) {
        observer->ContentViewWillRemoveFromParentView();
    }
}

void KRScrollerContentView::OnEvent(ArkUI_NodeEvent *event, const ArkUI_NodeEventType &event_type) {
    if (event_type == NODE_EVENT_ON_AREA_CHANGE) {
        if (handling_set_view_frame_) {
            handling_set_view_frame_ = false;
            if (auto parentView = std::dynamic_pointer_cast<KRScrollerView>(GetParentView())) {
                parentView->TryApplyPendingFireOnScroll();
            }
        }
    }
}

bool isBouncesEnableProp(const std::string &prop_key) {
    auto prop_key_c_str = prop_key.c_str();
    return std::strcmp(prop_key_c_str, kPropNameVerticalBounces) == 0 ||
           std::strcmp(prop_key_c_str, kPropNameHorizontalBounces) == 0 ||
           std::strcmp(prop_key_c_str, kPropNameBouncesEnable) == 0;
}

void KRScrollerView::SetRenderViewFrame(const KRRect &frame) {
    if (frame.width != last_revision_frame_.width || frame.height != last_revision_frame_.height) {
        native_layout_revision_++;
        last_revision_frame_ = frame;
    }
    IKRRenderViewExport::SetRenderViewFrame(frame);
    if (!is_set_frame_) {
        is_set_frame_ = true;
        if (is_need_set_content_offset_) {
            auto callback = first_offset_callback_slot_.Take();
            is_need_set_content_offset_ = false;
            KRScrollWriteResultCode result_code;
            if (CanApplyOffsetWrite(first_offset_generation_, first_offset_requires_native_idle_) &&
                IsCurrentOffsetWrite(first_offset_operation_generation_) &&
                MatchesExpectedLayout(first_offset_expected_content_size_, first_offset_expected_viewport_size_)) {
                kuikly::util::SetArkUIContentOffset(GetNode(), first_offset_x_, first_offset_y_, first_animate_,
                                                    first_duration_, first_curve_, first_damping_);
                result_code = KRScrollWriteResultCode::Committed;
            } else {
                result_code = KRScrollWriteResultCode::LayoutChanged;
            }
            auto result = callback ? ScrollWriteResult(result_code) : nullptr;
            if (callback) {
                callback(result);
            }
        }
    }
}

ArkUI_NodeHandle KRScrollerView::CreateNode() {
    auto node = kuikly::util::GetNodeApi()->createNode(ARKUI_NODE_SCROLL);
    // Scroll 默认会对ContentView居中展示, 这里强制不居中
    kuikly::util::SetArkUIStackNodeContentAlignment(node, ArkUI_Alignment::ARKUI_ALIGNMENT_TOP_START);
    return node;
}

void KRScrollerView::DidInit() {
    current_scroll_state_ = ArkUI_ScrollState::ARKUI_SCROLL_STATE_IDLE;
    last_scroll_time_ = 0;
    last_scroll_x_ = 0;
    last_scroll_y_ = 0;
    last_move_time_ = 0;
    velocity_x_ = 0;
    velocity_y_ = 0;
    SetBouncesEnable(NewKRRenderValue(bounces_enabled_));
    RegisterEvent(NODE_SCROLL_EVENT_ON_SCROLL_FRAME_BEGIN);
    RegisterEvent(NODE_SCROLL_EVENT_ON_SCROLL_START);
    RegisterEvent(NODE_SCROLL_EVENT_ON_WILL_SCROLL);
    RegisterEvent(NODE_SCROLL_EVENT_ON_SCROLL_STOP);
}

bool KRScrollerView::SetProp(const std::string &prop_key, const KRAnyValue &prop_value,
                             const KRRenderCallback event_call_back) {
    auto didHanded = false;
    if (std::strcmp(prop_key.c_str(), kPropNameDirectionRow) == 0) {
        didHanded = SetScrollDirection(prop_value);
    } else if (std::strcmp(prop_key.c_str(), kPropNamePagingEnabled) == 0) {
        didHanded = SetPagingEnabled(prop_value);
    } else if (std::strcmp(prop_key.c_str(), kEventNameScroll) == 0) {
        didHanded = RegisterOnScrollEvent(event_call_back);
    } else if (std::strcmp(prop_key.c_str(), kPropNameScrollEnabled) == 0) {
        didHanded = SetScrollEnabled(prop_value);
    } else if (isBouncesEnableProp(prop_key)) {
        didHanded = SetBouncesEnable(prop_value);
    } else if (std::strcmp(prop_key.c_str(), kPropNameShowScrollerIndicator) == 0) {
        didHanded = SetShowScrollerIndicator(prop_value);
    } else if (kuikly::util::isEqual(prop_key, kEventNameDragBegin)) {
        didHanded = RegisterOnDragBeginEvent(event_call_back);
    } else if (kuikly::util::isEqual(prop_key, kEventNameDragEnd)) {
        didHanded = RegisterOnDragEndEvent(event_call_back);
    } else if (kuikly::util::isEqual(prop_key, kEventNameScrollEnd)) {
        didHanded = RegisterOnScrollEndEvent(event_call_back);
    } else if (kuikly::util::isEqual(prop_key, kEventNameWillDragEnd)) {
        didHanded = RegisterWillDragEndEvent(event_call_back);
    } else if (kuikly::util::isEqual(prop_key, kPropNameLimitHeaderBounces)) {
        didHanded = SetLimitHeaderBounces(prop_value);
    } else if (kuikly::util::isEqual(prop_key, kPropNameNestedScroll)) {
        didHanded = SetNestedScroll(prop_value);
    } else if (kuikly::util::isEqual(prop_key, kPropNameFlingEnable)) {
        didHanded = SetFlingEnable(prop_value->toBool());
    } else if (kuikly::util::isEqual(prop_key, kPropNameFlingSpeedLimit)) {
        didHanded = SetFlingSpeedLimit(prop_value);
    }
    return didHanded;
}

bool KRScrollerView::ResetProp(const std::string &prop_key) {
    native_write_operation_sequence_++;
    compose_offset_write_generation_++;
    native_interaction_epoch_++;
    native_layout_revision_++;
    native_inset_revision_++;
    latest_compose_write_operation_ = 0;
    minimum_compose_write_operation_ = 0;
    std::shared_ptr<KRRenderValue> terminal_result;
    auto terminal = FinalizeScrollWrite(scroll_write_arbiter_.Current(), KRScrollWriteResultCode::Destroyed,
                                        terminal_result);
    auto pending_offset_callback = first_offset_callback_slot_.Take();
    auto pending_offset_result = pending_offset_callback
        ? ScrollWriteResult(KRScrollWriteResultCode::Destroyed) : nullptr;
    is_need_set_content_offset_ = false;
    content_inset_animate_ = nullptr;
    content_inset_when_drag_end_ = nullptr;
    ArkUI_NumberValue values[] = {{.f32 = 0}, {.f32 = 0}};
    ArkUI_AttributeItem item = {values, 2};
    kuikly::util::GetNodeApi()->setAttribute(GetNode(), NODE_SCROLL_BY, &item);
    is_set_frame_ = false;
    first_offset_x_ = 0;
    first_offset_y_ = 0;
    first_animate_ = false;
    auto didHanded = IKRRenderViewExport::ResetProp(prop_key);
    if (!didHanded) {
        if (prop_key == kPropNameNestedScroll) {
            didHanded = true;
            kuikly::util::ResetArkUINestedScroll(GetNode());
        } else if (prop_key == kPropNameFlingEnable) {
            didHanded = true;
            SetFlingEnable(true);
        } else if (prop_key == kPropNameFlingSpeedLimit) {
            didHanded = true;
            if (IsFlingSpeedLimitApiAvailable()) {
                kuikly::util::GetNodeApi()->resetAttribute(GetNode(), kScrollFlingSpeedLimitAttr);
            }
        }
    }
    if (terminal) {
        terminal(terminal_result);
    }
    if (pending_offset_callback) {
        pending_offset_callback(pending_offset_result);
    }
    return didHanded;
}

void KRScrollerView::CallMethod(const std::string &method, const KRAnyValue &params, const KRRenderCallback &callback) {
    if (kuikly::util::isEqual(method, kMethodNameContentOffset)) {
        SetContentOffset(params, callback);
    } else if (kuikly::util::isEqual(method, kMethodNameContentInset)) {
        SetContentInset(params, callback);
    } else if (kuikly::util::isEqual(method, kMethodNameContentInsetWhenDragEnd)) {
        SetContentInsetWhenDragEnd(params);
    } else if (kuikly::util::isEqual(method, kMethodNameAbortContentOffsetAnimate)) {
        AbortContentOffsetAnimate();
    } else if (kuikly::util::isEqual(method, kMethodNamePrepareForComposeReuse)) {
        PrepareForComposeReuse(params);
    } else {
        IKRRenderViewExport::CallMethod(method, params, callback);
    }
}

void KRScrollerView::OnEvent(ArkUI_NodeEvent *event, const ArkUI_NodeEventType &event_type) {
    if (event_type == NODE_SCROLL_EVENT_ON_SCROLL) {
        FireOnScrollEvent(event);
    } else if (event_type == NODE_SCROLL_EVENT_ON_SCROLL_FRAME_BEGIN) {
        OnScrollFrameBegin(event);
    } else if (event_type == NODE_SCROLL_EVENT_ON_SCROLL_START) {
        OnScrollStart(event);
    } else if (event_type == NODE_SCROLL_EVENT_ON_SCROLL_STOP) {
        OnScrollStop(event);
    } else if (event_type == NODE_SCROLL_EVENT_ON_WILL_SCROLL) {
        OnWillScroll(event);
    } else if (event_type == NODE_SCROLL_EVENT_ON_REACH_START) {
        OnScrollReachStart(event);
    }
}

void KRScrollerView::FireOnScrollEvent(ArkUI_NodeEvent *event) {
    auto point = kuikly::util::GetArkUIScrollContentOffset(GetNode());
    if (point.x == last_fired_scroll_x_ && point.y == last_fired_scroll_y_) {
        return;
    }
    last_fired_scroll_x_ = point.x;
    last_fired_scroll_y_ = point.y;
    // 分发滚动事件
    DispatchDidScrollToObservers(point);
    if (!on_scroll_callback_) {
        return;
    }
    on_scroll_callback_(GetCommonScrollParams());
}

void KRScrollerView::FireBeginDragEvent(ArkUI_NodeEvent *event) {
    if (!on_drag_begin_callback_) {
        return;
    }
    on_drag_begin_callback_(GetCommonScrollParams());
}

void KRScrollerView::FireWillDragEndEvent(ArkUI_NodeEvent *event) {
    KR_LOG_INFO << "fire will drag end";
    if (!on_will_drag_end_callback_) {
        return;
    }
    // TODO(userName): 补充加速度参数
    on_will_drag_end_callback_(GetCommonScrollParams());
}

void KRScrollerView::FireEndDragEvent(ArkUI_NodeEvent *event) {
    if (!on_drag_end_callback_) {
        return;
    }
    on_drag_end_callback_(GetCommonScrollParams());
}

void KRScrollerView::FireEndScrollEvent(ArkUI_NodeEvent *event) {
    if (!on_scroll_end_callback_) {
        return;
    }
    on_scroll_end_callback_(GetCommonScrollParams());
}

void KRScrollerView::DidInsertSubRenderView(const std::shared_ptr<IKRRenderViewExport> &sub_render_view, int index) {
    if (content_view_) {
        return;
    }

    content_view_ = std::dynamic_pointer_cast<KRScrollerContentView>(sub_render_view);
}

void KRScrollerView::OnDestroy() {
    replacement_stop_event_fence_.Reset();
    native_write_operation_sequence_++;
    compose_offset_write_generation_++;
    native_interaction_epoch_++;
    native_layout_revision_++;
    native_inset_revision_++;
    latest_compose_write_operation_ = 0;
    minimum_compose_write_operation_ = 0;
    std::shared_ptr<KRRenderValue> terminal_result;
    auto terminal = FinalizeScrollWrite(scroll_write_arbiter_.Current(), KRScrollWriteResultCode::Destroyed,
                                        terminal_result);
    auto pending_offset_callback = first_offset_callback_slot_.Take();
    auto pending_offset_result = pending_offset_callback
        ? ScrollWriteResult(KRScrollWriteResultCode::Destroyed) : nullptr;
    is_need_set_content_offset_ = false;
    content_inset_animate_ = nullptr;
    content_inset_when_drag_end_ = nullptr;
    ArkUI_NumberValue values[] = {{.f32 = 0}, {.f32 = 0}};
    ArkUI_AttributeItem item = {values, 2};
    kuikly::util::GetNodeApi()->setAttribute(GetNode(), NODE_SCROLL_BY, &item);
    content_view_ = nullptr;
    scroll_observers_.clear();
    if (terminal) {
        terminal(terminal_result);
    }
    if (pending_offset_callback) {
        pending_offset_callback(pending_offset_result);
    }
}

static ArkUI_ScrollNestedMode ParseOption(const std::string &option) {
    if (option == "SELF_FIRST") {
        return ARKUI_SCROLL_NESTED_MODE_SELF_FIRST;
    }
    if (option == "SELF_ONLY") {
        return ARKUI_SCROLL_NESTED_MODE_SELF_ONLY;
    }
    if (option == "PARENT_FIRST") {
        return ARKUI_SCROLL_NESTED_MODE_PARENT_FIRST;
    }
    if (option == "PARALLEL") {
        return ARKUI_SCROLL_NESTED_MODE_PARALLEL;
    }
    return ARKUI_SCROLL_NESTED_MODE_SELF_ONLY;
}

bool KRScrollerView::SetNestedScroll(const KRAnyValue &value) {
    const std::string &str = value->toString();
    auto paramObj = kuikly::util::JSONObject::Parse(str);
    const std::string &forwardStr = paramObj->GetString(kPropKeyNestedScrollForward);
    const std::string &backwardStr = paramObj->GetString(kPropKeyNestedScrollBackward);
    ArkUI_ScrollNestedMode forward = ParseOption(forwardStr);
    ArkUI_ScrollNestedMode backward = ParseOption(backwardStr);

    kuikly::util::SetArkUINestedScroll(GetNode(), forward, backward);
    return true;
}

bool KRScrollerView::SetScrollEnabled(const KRAnyValue &value) {
    auto scrollEnabled = value->toBool();
    kuikly::util::SetArkUIScrollEnabled(GetNode(), scrollEnabled);
    return true;
}

bool KRScrollerView::SetScrollDirection(const KRAnyValue &value) {
    direction_row_ = value->toBool();
    kuikly::util::SetArkUIScrollDirection(GetNode(), direction_row_);
    return true;
}

bool KRScrollerView::SetPagingEnabled(const KRAnyValue &value) {
    auto pagingEnabled = value->toBool();
    kuikly::util::SetArkUIScrollPagingEnabled(GetNode(), pagingEnabled);
    return true;
}

bool KRScrollerView::SetBouncesEnable(const KRAnyValue &value) {
    bounces_enabled_ = value->toBool();
    InnerSetBouncesEnable(bounces_enabled_);
    return true;
}

bool KRScrollerView::SetLimitHeaderBounces(const KRAnyValue &value) {
    limit_header_bounces_ = value->toBool();
    RegisterEvent(NODE_SCROLL_EVENT_ON_SCROLL_EDGE);
    return true;
}

bool KRScrollerView::SetShowScrollerIndicator(const KRAnyValue &value) {
    auto enable = value->toBool();
    kuikly::util::SetArkUIShowScrollerIndicator(GetNode(), enable);
    return true;
}

bool KRScrollerView::RegisterOnScrollEvent(const KRRenderCallback event_call_back) {
    RegisterEvent(NODE_SCROLL_EVENT_ON_SCROLL);
    on_scroll_callback_ = event_call_back;
    return true;
}

bool KRScrollerView::RegisterOnDragBeginEvent(const KRRenderCallback event_callback) {
    on_drag_begin_callback_ = event_callback;
    return true;
}

bool KRScrollerView::RegisterOnDragEndEvent(const KRRenderCallback event_callback) {
    on_drag_end_callback_ = event_callback;
    return true;
}

bool KRScrollerView::RegisterOnScrollEndEvent(const KRRenderCallback event_callback) {
    on_scroll_end_callback_ = event_callback;
    return true;
}

bool KRScrollerView::RegisterWillDragEndEvent(const KRRenderCallback event_callback) {
    on_will_drag_end_callback_ = event_callback;
    return true;
}
/**
 *     NSArray<NSString *> *points = [params componentsSeparatedByString:@" "];
    BOOL animated = [points count] > 2 ? [points[2] boolValue] : NO;
    CGFloat duration = [points count] > 3 ? [points[3] floatValue] : 0;
    CGFloat damping = [points count] > 4 ? [points[4] floatValue] : 0;
    CGFloat velocity = [points count] > 5 ? [points[5] floatValue] : 0;
    CGPoint contentOffset = CGPointMake([points.firstObject doubleValue], [points[1] doubleValue]);
 * @param value
 */
void KRScrollerView::SetContentOffset(const KRAnyValue &value, const KRRenderCallback &callback) {
    auto content_offset_splits = kuikly::util::SplitString(value->toString(), ' ');
    auto offset_x = content_offset_splits[0]->toFloat();
    auto offset_y = content_offset_splits[1]->toFloat();
    auto animate = content_offset_splits[2]->toBool();
    auto duration = content_offset_splits.size() > 3 ? content_offset_splits[3]->toInt() : 0;
    auto damping = content_offset_splits.size() > 4 ? content_offset_splits[4]->toFloat() : 0;
    auto curve = content_offset_splits.size() > 6 ? content_offset_splits[6]->toInt() : 0;
    auto generation = content_offset_splits.size() > 8 ? content_offset_splits[7]->toLong() : -1;
    auto requires_native_idle = content_offset_splits.size() > 8 && content_offset_splits[8]->toBool();
    auto compose_operation = content_offset_splits.size() > 9 ? content_offset_splits[9]->toLong() : 0;
    auto expected_content_size = content_offset_splits.size() > 10 ? content_offset_splits[10]->toFloat() : -1.0f;
    auto expected_viewport_size = content_offset_splits.size() > 11 ? content_offset_splits[11]->toFloat() : -1.0f;
    auto interaction_epoch = content_offset_splits.size() > 17 ? content_offset_splits[17]->toLong()
                                                                 : native_interaction_epoch_;
    auto layout_revision = content_offset_splits.size() > 18 ? content_offset_splits[18]->toLong()
                                                              : native_layout_revision_;
    auto inset_revision = content_offset_splits.size() > 21 ? content_offset_splits[21]->toLong()
                                                             : native_inset_revision_;

    auto validation = ValidateOffsetWrite(generation, requires_native_idle, compose_operation,
                                          interaction_epoch, layout_revision, inset_revision);
    if (validation != KRScrollWriteResultCode::Committed) {
        CompleteOffsetWrite(callback, validation);
        return;
    }
    if (!MatchesExpectedLayout(expected_content_size, expected_viewport_size)) {
        CompleteOffsetWrite(callback, is_set_frame_ ? KRScrollWriteResultCode::LayoutChanged
                                                    : KRScrollWriteResultCode::NotReady);
        return;
    }

    if (!is_set_frame_) {
        if (generation >= 0) {
            CompleteOffsetWrite(callback, KRScrollWriteResultCode::NotReady);
            return;
        }
        first_offset_x_ = offset_x;
        first_offset_y_ = offset_y;
        first_animate_ = animate;
        first_duration_ = duration;
        first_curve_ = curve;
        first_damping_ = damping;
        first_offset_generation_ = generation;
        first_offset_requires_native_idle_ = requires_native_idle;
        first_offset_operation_generation_ = compose_operation;
        first_offset_expected_content_size_ = expected_content_size;
        first_offset_expected_viewport_size_ = expected_viewport_size;
        auto previous_callback = first_offset_callback_slot_.Replace(callback);
        is_need_set_content_offset_ = true;
        auto previous_result = previous_callback
            ? ScrollWriteResult(KRScrollWriteResultCode::Replaced) : nullptr;
        if (previous_callback) {
            previous_callback(previous_result);
        }
        return;
    }
    auto operation = InstallScrollWrite(KRNativeScrollWriteResource::ContentOffset,
                                        generation, compose_operation, interaction_epoch,
                                        layout_revision, inset_revision, callback);
    if (!IsCurrentNativeScrollWrite(operation)) {
        return;
    }
    operation->target = KRPoint{offset_x, offset_y};
    operation->animated = animate;
    auto current_offset = GetContentOffset();
    if (std::fabs(current_offset.x - offset_x) <= 0.5f &&
        std::fabs(current_offset.y - offset_y) <= 0.5f) {
        std::shared_ptr<KRRenderValue> result;
        auto terminal = FinalizeScrollWrite(operation, KRScrollWriteResultCode::AlreadySatisfied, result);
        if (terminal) {
            terminal(result);
        }
        return;
    }
    kuikly::util::SetArkUIContentOffset(GetNode(), offset_x, offset_y, animate, duration, curve, damping);
    if (!animate && IsCurrentNativeScrollWrite(operation)) {
        std::shared_ptr<KRRenderValue> result;
        auto terminal = FinalizeScrollWrite(operation, KRScrollWriteResultCode::Committed, result);
        if (terminal) {
            terminal(result);
        }
    } else if (animate && IsCurrentNativeScrollWrite(operation)) {
        auto accepted_duration = duration > 0 ? duration : 1000;
        auto slack = std::max(1000, accepted_duration / 4);
        auto weak_this = std::weak_ptr<KRScrollerView>(
            std::dynamic_pointer_cast<KRScrollerView>(shared_from_this()));
        KRContextScheduler::ScheduleTask(accepted_duration + slack, [weak_this, operation]() {
            if (auto strong_this = weak_this.lock()) {
                if (!strong_this->IsCurrentNativeScrollWrite(operation)) {
                    return;
                }
                std::shared_ptr<KRRenderValue> result;
                auto terminal = strong_this->FinalizeScrollWrite(
                    operation, KRScrollWriteResultCode::AckTimeout, result);
                ArkUI_NumberValue values[] = {{.f32 = 0}, {.f32 = 0}};
                ArkUI_AttributeItem item = {values, 2};
                strong_this->replacement_stop_event_fence_.Arm();
                kuikly::util::GetNodeApi()->setAttribute(
                    strong_this->GetNode(), NODE_SCROLL_BY, &item);
                strong_this->FireEndScrollEvent(nullptr);
                if (terminal) {
                    terminal(result);
                }
            }
        });
    }
}

void KRScrollerView::CompleteOffsetWrite(const KRRenderCallback &callback,
                                         KRScrollWriteResultCode result_code) {
    if (!callback) {
        return;
    }
    callback(ScrollWriteResult(result_code));
}

std::shared_ptr<KRRenderValue> KRScrollerView::ScrollWriteResult(
    KRScrollWriteResultCode result_code,
    const std::shared_ptr<KRNativeScrollWriteOperation> &operation) {
    KRRenderValueMap result;
    auto committed = result_code == KRScrollWriteResultCode::Committed ||
        result_code == KRScrollWriteResultCode::AlreadySatisfied;
    result["committed"] = NewKRRenderValue(committed ? 1 : 0);
    result["resultCode"] = NewKRRenderValue(static_cast<int>(result_code));
    result["accepted"] = NewKRRenderValue(operation ? 1 : (committed ? 1 : 0));
    result["installed"] = NewKRRenderValue(operation ? 1 : (committed ? 1 : 0));
    result["replacedPrevious"] = NewKRRenderValue(
        operation && operation->replaced_previous ? 1 : 0);
    result["nativeInteractionEpoch"] = NewKRRenderValue(native_interaction_epoch_);
    result["layoutRevision"] = NewKRRenderValue(native_layout_revision_);
    result["insetRevision"] = NewKRRenderValue(native_inset_revision_);
    return NewKRRenderValue(result);
}

KRScrollWriteResultCode KRScrollerView::ValidateOffsetWrite(
    int64_t generation, bool requires_native_idle, int64_t operation_generation,
    int64_t interaction_epoch, int64_t layout_revision, int64_t inset_revision) {
    if (generation >= 0 && generation != compose_offset_write_generation_) {
        return KRScrollWriteResultCode::Stale;
    }
    if (requires_native_idle && NativeScrollPhase() != 0) {
        return KRScrollWriteResultCode::Busy;
    }
    if (operation_generation > 0 &&
        (operation_generation < minimum_compose_write_operation_ ||
         operation_generation < latest_compose_write_operation_)) {
        return KRScrollWriteResultCode::Stale;
    }
    if (interaction_epoch != native_interaction_epoch_) {
        return KRScrollWriteResultCode::Interrupted;
    }
    if (layout_revision != native_layout_revision_) {
        return KRScrollWriteResultCode::LayoutChanged;
    }
    if (inset_revision != native_inset_revision_) {
        return KRScrollWriteResultCode::Stale;
    }
    return KRScrollWriteResultCode::Committed;
}

std::shared_ptr<KRNativeScrollWriteOperation> KRScrollerView::InstallScrollWrite(
    KRNativeScrollWriteResource resource, int64_t generation,
    int64_t operation_generation, int64_t interaction_epoch,
    int64_t layout_revision, int64_t inset_revision, const KRRenderCallback &callback) {
    KREnsureMainThread();
    auto operation = std::make_shared<KRNativeScrollWriteOperation>();
    operation->sequence = ++native_write_operation_sequence_;
    operation->resource = resource;
    operation->callback = callback;
    operation->generation = generation;
    operation->compose_operation = operation_generation;
    operation->interaction_epoch = interaction_epoch;
    operation->layout_revision = layout_revision;
    operation->inset_revision = inset_revision;
    operation->start = GetContentOffset();
    if (operation_generation > 0) {
        latest_compose_write_operation_ = operation_generation;
    }

    auto previous = scroll_write_arbiter_.Replace(operation);
    operation->replaced_previous = previous != nullptr;
    KRRenderCallback previous_callback = nullptr;
    std::shared_ptr<KRRenderValue> previous_result;
    if (previous) {
        previous_callback = FinalizeScrollWrite(previous, KRScrollWriteResultCode::Replaced, previous_result);
    }

    auto pending_offset_callback = first_offset_callback_slot_.Take();
    auto pending_offset_result = pending_offset_callback
        ? ScrollWriteResult(KRScrollWriteResultCode::Replaced) : nullptr;
    is_need_set_content_offset_ = false;
    content_inset_animate_ = nullptr;
    if (previous && KRShouldStopReplacedScrollMotion(
        previous->resource == KRNativeScrollWriteResource::ContentOffset,
        previous->animated,
        previous->offset_correction_required,
        previous->offset_correction_finished)) {
        ArkUI_NumberValue values[] = {{.f32 = 0}, {.f32 = 0}};
        ArkUI_AttributeItem item = {values, 2};
        replacement_stop_event_fence_.Arm();
        kuikly::util::GetNodeApi()->setAttribute(GetNode(), NODE_SCROLL_BY, &item);
    }

    if (previous_callback) {
        previous_callback(previous_result);
    }
    if (pending_offset_callback) {
        pending_offset_callback(pending_offset_result);
    }
    return operation;
}

KRRenderCallback KRScrollerView::FinalizeScrollWrite(
    const std::shared_ptr<KRNativeScrollWriteOperation> &operation,
    KRScrollWriteResultCode result_code,
    std::shared_ptr<KRRenderValue> &result) {
    if (!scroll_write_arbiter_.Finalize(operation)) {
        return nullptr;
    }
    auto callback = operation->callback;
    operation->callback = nullptr;
    result = ScrollWriteResult(result_code, operation);
    return callback;
}

void KRScrollerView::InvalidateCurrentScrollWrite(KRScrollWriteResultCode result_code) {
    std::shared_ptr<KRRenderValue> result;
    auto callback = FinalizeScrollWrite(scroll_write_arbiter_.Current(), result_code, result);
    if (callback) {
        callback(result);
    }
}

bool KRScrollerView::IsCurrentNativeScrollWrite(
    const std::shared_ptr<KRNativeScrollWriteOperation> &operation) const {
    return scroll_write_arbiter_.IsCurrent(operation);
}

bool KRScrollerView::CanApplyOffsetWrite(int64_t generation, bool requires_native_idle) const {
    if (generation < 0) {
        return true;
    }
    return generation == compose_offset_write_generation_ &&
           (!requires_native_idle || NativeScrollPhase() == 0);
}

bool KRScrollerView::ClaimOffsetWrite(int64_t generation, bool requires_native_idle,
                                      int64_t operation_generation) {
    if (!CanApplyOffsetWrite(generation, requires_native_idle)) {
        return false;
    }
    if (operation_generation <= 0) {
        return true;
    }
    if (operation_generation < minimum_compose_write_operation_ ||
        operation_generation < latest_compose_write_operation_) {
        return false;
    }
    latest_compose_write_operation_ = operation_generation;
    return true;
}

bool KRScrollerView::IsCurrentOffsetWrite(int64_t operation_generation) const {
    return operation_generation <= 0 ||
        (operation_generation == latest_compose_write_operation_ &&
         operation_generation >= minimum_compose_write_operation_);
}

bool KRScrollerView::MatchesExpectedLayout(float expected_content_size, float expected_viewport_size) {
    if (expected_content_size < 0 || expected_viewport_size < 0) {
        return true;
    }
    if (!content_view_) {
        return false;
    }
    auto content_frame = content_view_->GetFrame();
    auto viewport_frame = GetFrame();
    auto actual_content_size = direction_row_ ? content_frame.width : content_frame.height;
    auto actual_viewport_size = direction_row_ ? viewport_frame.width : viewport_frame.height;
    return std::fabs(actual_content_size - expected_content_size) <= 1.0f &&
        std::fabs(actual_viewport_size - expected_viewport_size) <= 1.0f;
}

int KRScrollerView::NativeScrollPhase() const {
    if (is_dragging_ || current_scroll_state_ == ArkUI_ScrollState::ARKUI_SCROLL_STATE_SCROLL) {
        return 1;
    }
    if (current_scroll_state_ == ArkUI_ScrollState::ARKUI_SCROLL_STATE_FLING ||
        content_inset_animate_ ||
        (scroll_write_arbiter_.Current() && scroll_write_arbiter_.Current()->animated &&
         !scroll_write_arbiter_.Current()->terminal)) {
        return 2;
    }
    return 0;
}

void KRScrollerView::SetContentInset(const KRAnyValue &value, const KRRenderCallback &callback) {
    auto content_inset = std::make_shared<KRScrollerContentInset>(value);
    SetContentInset(content_inset, callback);
}

void KRScrollerView::SetContentInsetWhenDragEnd(const KRAnyValue &value) {
    if (!content_view_) {
        return;
    }
    auto content_inset = std::make_shared<KRScrollerContentInset>(value);
    auto validation = ValidateOffsetWrite(
        content_inset->generation, content_inset->requires_native_idle,
        content_inset->operation_generation,
        content_inset->generation < 0 ? native_interaction_epoch_ : content_inset->native_interaction_epoch,
        content_inset->generation < 0 ? native_layout_revision_ : content_inset->layout_revision,
        content_inset->generation < 0 ? native_inset_revision_ : content_inset->inset_revision);
    if (validation != KRScrollWriteResultCode::Committed) {
        return;
    }
    if (!MatchesExpectedLayout(content_inset->expected_content_size,
                               content_inset->expected_viewport_size)) {
        return;
    }
    if (!ClaimOffsetWrite(content_inset->generation, content_inset->requires_native_idle,
                          content_inset->operation_generation)) {
        return;
    }
    content_inset_when_drag_end_ = content_inset;
}

void KRScrollerView::SetContentInset(const std::shared_ptr<KRScrollerContentInset> &content_inset,
                                      const KRRenderCallback &callback) {
    auto validation = ValidateOffsetWrite(
        content_inset->generation, content_inset->requires_native_idle,
        content_inset->operation_generation,
        content_inset->generation < 0 ? native_interaction_epoch_ : content_inset->native_interaction_epoch,
        content_inset->generation < 0 ? native_layout_revision_ : content_inset->layout_revision,
        content_inset->generation < 0 ? native_inset_revision_ : content_inset->inset_revision);
    if (validation != KRScrollWriteResultCode::Committed) {
        CompleteOffsetWrite(callback, validation);
        return;
    }
    if (!MatchesExpectedLayout(content_inset->expected_content_size,
                               content_inset->expected_viewport_size)) {
        CompleteOffsetWrite(callback, KRScrollWriteResultCode::LayoutChanged);
        return;
    }
    auto native_phase_before_install = NativeScrollPhase();
    auto operation = InstallScrollWrite(
        KRNativeScrollWriteResource::ContentInset,
        content_inset->generation, content_inset->operation_generation,
        content_inset->generation < 0 ? native_interaction_epoch_ : content_inset->native_interaction_epoch,
        content_inset->generation < 0 ? native_layout_revision_ : content_inset->layout_revision,
        content_inset->generation < 0 ? native_inset_revision_ : content_inset->inset_revision,
        callback);
    if (!IsCurrentNativeScrollWrite(operation)) {
        return;
    }
    if (!content_view_) {
        CompleteContentInsetWrite(operation, KRScrollWriteResultCode::NotReady, false);
        return;
    }
    auto top = content_inset->top;
    auto start = content_inset->start;
    auto bottom = content_inset->bottom;
    auto end = content_inset->end;
    auto animate = content_inset->animate;
    operation->animated = animate;
    if (content_inset->generation < 0 && native_phase_before_install != 0) {
        kuikly::util::SetArkUIMargin(content_view_->GetNode(), start, top, end, bottom);
        operation->inset_mutation_applied = true;
        CompleteContentInsetWrite(operation, KRScrollWriteResultCode::Committed, false);
    } else if (animate) {
        // 对齐 iOS 逻辑：若当前 offset 超出新 inset 的合法范围，先滚回合法位置
        auto current_offset = GetContentOffset();
        auto target_offset = MaxContentOffsetInContentInset(content_inset);
        if (target_offset.x != current_offset.x || target_offset.y != current_offset.y) {
            operation->target = target_offset;
            operation->offset_correction_required = true;
            kuikly::util::SetArkUIContentOffset(GetNode(), target_offset.x, target_offset.y, true, 0, 0, 0);
        } else {
            operation->offset_correction_finished = true;
        }
        // 再用原有动画逻辑设置 margin
        auto root_view = GetRootView().lock();
        if (!root_view) {
            if (operation->offset_correction_required) {
                kuikly::util::SetArkUIContentOffset(
                    GetNode(), operation->target.x, operation->target.y, false, 0, 0, 0);
                operation->offset_correction_finished = true;
            }
            kuikly::util::SetArkUIMargin(content_view_->GetNode(), start, top, end, bottom);
            operation->inset_mutation_applied = true;
            CompleteContentInsetWrite(operation, KRScrollWriteResultCode::Committed, false);
        } else {
            auto animate_option = std::make_shared<KRAnimateOption>();
            animate_option->SetDuration(200);
            auto weak_this = std::weak_ptr<KRScrollerView>(std::dynamic_pointer_cast<KRScrollerView>(shared_from_this()));
            content_inset_animate_ = std::make_shared<KRAnimation>(
                root_view->GetUIContextHandle(), animate_option,
                [weak_this, content_inset, top, start, bottom, end, operation]() {
                    if (auto strong_this = weak_this.lock()) {
                        if (!strong_this->IsCurrentNativeScrollWrite(operation)) {
                            return;
                        }
                        bool current = operation->generation < 0 ||
                            operation->generation == strong_this->compose_offset_write_generation_;
                        current = current && strong_this->IsCurrentOffsetWrite(operation->compose_operation);
                        current = current && operation->interaction_epoch ==
                            strong_this->native_interaction_epoch_;
                        current = current && operation->layout_revision ==
                            strong_this->native_layout_revision_;
                        current = current && operation->inset_revision ==
                            strong_this->native_inset_revision_;
                        current = current && strong_this->MatchesExpectedLayout(
                            content_inset->expected_content_size,
                            content_inset->expected_viewport_size);
                        bool native_idle = !strong_this->is_dragging_ &&
                            strong_this->current_scroll_state_ == ArkUI_ScrollState::ARKUI_SCROLL_STATE_IDLE;
                        if (strong_this->content_view_ && current &&
                            (!content_inset->requires_native_idle || native_idle)) {
                            kuikly::util::SetArkUIMargin(
                                strong_this->content_view_->GetNode(), start, top, end, bottom);
                            operation->inset_mutation_applied = true;
                        }
                    }
                });
            content_inset_animate_->SetCompleteCallback(
                ArkUI_FinishCallbackType::ARKUI_FINISH_CALLBACK_LOGICALLY,
                [weak_this, content_inset, operation]() {
                    if (auto strong_this = weak_this.lock()) {
                        if (!strong_this->IsCurrentNativeScrollWrite(operation)) {
                            return;
                        }
                        auto result_code = KRScrollWriteResultCode::Committed;
                        if (!strong_this->content_view_) {
                            result_code = KRScrollWriteResultCode::NotReady;
                        } else if (operation->interaction_epoch != strong_this->native_interaction_epoch_) {
                            result_code = KRScrollWriteResultCode::Interrupted;
                        } else if (operation->layout_revision != strong_this->native_layout_revision_ ||
                                   !strong_this->MatchesExpectedLayout(
                                       content_inset->expected_content_size,
                                       content_inset->expected_viewport_size)) {
                            result_code = KRScrollWriteResultCode::LayoutChanged;
                        } else if ((operation->generation >= 0 &&
                                    operation->generation != strong_this->compose_offset_write_generation_) ||
                                   !strong_this->IsCurrentOffsetWrite(operation->compose_operation) ||
                                   operation->inset_revision != strong_this->native_inset_revision_) {
                            result_code = KRScrollWriteResultCode::Stale;
                        }
                        bool native_idle = !strong_this->is_dragging_ &&
                            strong_this->current_scroll_state_ == ArkUI_ScrollState::ARKUI_SCROLL_STATE_IDLE;
                        if (result_code == KRScrollWriteResultCode::Committed &&
                            content_inset->requires_native_idle && !native_idle) {
                            result_code = KRScrollWriteResultCode::Busy;
                        }
                        strong_this->content_inset_animate_ = nullptr;
                        operation->inset_animation_finished = true;
                        if (result_code != KRScrollWriteResultCode::Committed) {
                            strong_this->CompleteContentInsetWrite(
                                operation, result_code, !operation->physical_end_emitted);
                        } else if (!operation->inset_mutation_applied) {
                            strong_this->CompleteContentInsetWrite(
                                operation, KRScrollWriteResultCode::NotReady,
                                !operation->physical_end_emitted);
                        } else if (!operation->offset_correction_required ||
                                   operation->offset_correction_finished) {
                            strong_this->CompleteContentInsetWrite(
                                operation, KRScrollWriteResultCode::Committed,
                                !operation->physical_end_emitted);
                        }
                    }
                });
            content_inset_animate_->Start();
            KRContextScheduler::ScheduleTask(1200, [weak_this, operation]() {
                if (auto strong_this = weak_this.lock()) {
                    if (!strong_this->IsCurrentNativeScrollWrite(operation)) {
                        return;
                    }
                    ArkUI_NumberValue values[] = {{.f32 = 0}, {.f32 = 0}};
                    ArkUI_AttributeItem item = {values, 2};
                    if (operation->offset_correction_required &&
                        !operation->offset_correction_finished) {
                        strong_this->replacement_stop_event_fence_.Arm();
                    }
                    kuikly::util::GetNodeApi()->setAttribute(
                        strong_this->GetNode(), NODE_SCROLL_BY, &item);
                    strong_this->CompleteContentInsetWrite(
                        operation, KRScrollWriteResultCode::AckTimeout, true);
                }
            });
        }
    } else {
        kuikly::util::SetArkUIMargin(content_view_->GetNode(), start, top, end, bottom);
        operation->inset_mutation_applied = true;
        CompleteContentInsetWrite(operation, KRScrollWriteResultCode::Committed, false);
    }
}

void KRScrollerView::CompleteContentInsetWrite(
    const std::shared_ptr<KRNativeScrollWriteOperation> &operation,
    KRScrollWriteResultCode result_code, bool fire_scroll_end) {
    if (!IsCurrentNativeScrollWrite(operation) ||
        operation->resource != KRNativeScrollWriteResource::ContentInset) {
        return;
    }
    content_inset_animate_ = nullptr;
    if (result_code == KRScrollWriteResultCode::Committed) {
        native_inset_revision_++;
    }
    std::shared_ptr<KRRenderValue> result;
    auto callback = FinalizeScrollWrite(operation, result_code, result);
    if (fire_scroll_end) {
        FireEndScrollEvent(nullptr);
    }
    if (callback) {
        callback(result);
    }
}

// 计算在指定 contentInset 下 offset 的合法位置（对齐 iOS p_maxContentOffsetInContentInset）
KRPoint KRScrollerView::MaxContentOffsetInContentInset(
    const std::shared_ptr<KRScrollerContentInset> &content_inset) {
    if (!content_view_) {
        return KRPoint();
    }

    auto frame = GetFrame();
    auto content_frame = content_view_->GetFrame();
    auto current_offset = GetContentOffset();

    if (direction_row_) {
        float content_size = content_frame.width;
        float frame_size = frame.width;
        if (content_size <= frame_size) {
            return KRPoint();
        }
        // 上/左越界：offset 滚到了 inset 头部之前
        if (current_offset.x < -content_inset->start) {
            return KRPoint{-content_inset->start, 0};
        }
        // 下/右越界：offset 滚过了内容尾部
        float max_offset = content_size + content_inset->end - frame_size;
        if (current_offset.x > max_offset) {
            return KRPoint{max_offset, 0};
        }
    } else {
        float content_size = content_frame.height;
        float frame_size = frame.height;
        if (content_size <= frame_size) {
            return KRPoint();
        }
        // 上越界
        if (current_offset.y < -content_inset->top) {
            return KRPoint{0, -content_inset->top};
        }
        // 下越界
        float max_offset = content_size + content_inset->bottom - frame_size;
        if (current_offset.y > max_offset) {
            return KRPoint{0, max_offset};
        }
    }

    return current_offset;
}

void KRScrollerView::OnScrollFrameBegin(ArkUI_NodeEvent *event) {
    auto current_time = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
    auto point = kuikly::util::GetArkUIScrollContentOffset(GetNode());
    auto operation = scroll_write_arbiter_.Current();
    if (IsCurrentNativeScrollWrite(operation) &&
        operation->resource == KRNativeScrollWriteResource::ContentOffset &&
        operation->animated &&
        (std::fabs(point.x - operation->start.x) > 0.5f ||
         std::fabs(point.y - operation->start.y) > 0.5f)) {
        operation->observed_start = true;
    } else if (IsCurrentNativeScrollWrite(operation) &&
               operation->resource == KRNativeScrollWriteResource::ContentInset &&
               operation->offset_correction_required &&
               (std::fabs(point.x - operation->start.x) > 0.5f ||
                std::fabs(point.y - operation->start.y) > 0.5f)) {
        operation->observed_start = true;
    }

    if (last_scroll_time_ > 0) {
        auto dt = current_time - last_scroll_time_;
        if (dt > 0) {
            float instant_vx = (point.x - last_scroll_x_) * 1000.0f / dt;
            float instant_vy = (point.y - last_scroll_y_) * 1000.0f / dt;
            // EMA 平滑，抑制帧间抖动（alpha=0.3，半衰期约 2 帧）
            constexpr float kEmaAlpha = 0.3f;
            velocity_x_ = kEmaAlpha * instant_vx + (1.0f - kEmaAlpha) * velocity_x_;
            velocity_y_ = kEmaAlpha * instant_vy + (1.0f - kEmaAlpha) * velocity_y_;
            // 追踪产生有效位移的时间点，用于 OnWillDragEnd 的 stale 检测
            constexpr float kMinOffsetDelta = 0.5f;
            if (fabsf(point.x - last_scroll_x_) > kMinOffsetDelta ||
                fabsf(point.y - last_scroll_y_) > kMinOffsetDelta) {
                last_move_time_ = current_time;
            }
        }
    }

    last_scroll_time_ = current_time;
    last_scroll_x_ = point.x;
    last_scroll_y_ = point.y;
}

void KRScrollerView::OnScrollStop(ArkUI_NodeEvent *event) {
    KREnsureMainThread();
    if (replacement_stop_event_fence_.ConsumeReplacementStop()) {
        return;
    }
    KRRenderCallback terminal = nullptr;
    std::shared_ptr<KRRenderValue> terminal_result;
    auto operation = scroll_write_arbiter_.Current();
    if (IsCurrentNativeScrollWrite(operation) && operation->animated && operation->observed_start) {
        auto point = GetContentOffset();
        if (std::fabs(point.x - operation->target.x) <= 1.0f &&
            std::fabs(point.y - operation->target.y) <= 1.0f) {
            if (operation->resource == KRNativeScrollWriteResource::ContentOffset) {
                terminal = FinalizeScrollWrite(operation, KRScrollWriteResultCode::Committed,
                                               terminal_result);
            } else if (operation->resource == KRNativeScrollWriteResource::ContentInset &&
                       operation->offset_correction_required) {
                operation->offset_correction_finished = true;
                operation->physical_end_emitted = true;
                if (operation->inset_animation_finished && operation->inset_mutation_applied) {
                    content_inset_animate_ = nullptr;
                    native_inset_revision_++;
                    terminal = FinalizeScrollWrite(operation, KRScrollWriteResultCode::Committed,
                                                   terminal_result);
                }
            }
        }
    }
    current_scroll_state_ = ArkUI_ScrollState::ARKUI_SCROLL_STATE_IDLE;
    if (is_dragging_) {
        OnWillDragEnd(event);
    }
    FireEndScrollEvent(event);
    if (terminal) {
        terminal(terminal_result);
    }
    if (auto handler = weak_super_touch_handler_.lock()) {
        handler->ClearNativeTouchConsumer(shared_from_this());
    }
}

void KRScrollerView::OnWillScroll(ArkUI_NodeEvent *event) {
    AdjustHeaderBouncesEnableWhenWillScroll(event);

    auto new_scroll_state = kuikly::util::GetArkUIScrollerState(event, 2);
    if (new_scroll_state == current_scroll_state_) {
        return;
    }
    if (IsIdeaStateToDraggingState(new_scroll_state) || IsFlingStateToDraggingState(new_scroll_state)) {
        current_scroll_state_ = new_scroll_state;
        is_dragging_ = true;
        native_interaction_epoch_++;
        native_write_operation_sequence_++;
        minimum_compose_write_operation_ = latest_compose_write_operation_ + 1;
        std::shared_ptr<KRRenderValue> terminal_result;
        auto terminal = FinalizeScrollWrite(scroll_write_arbiter_.Current(),
                                            KRScrollWriteResultCode::Interrupted,
                                            terminal_result);
        auto pending_offset_callback = first_offset_callback_slot_.Take();
        auto pending_offset_result = pending_offset_callback
            ? ScrollWriteResult(KRScrollWriteResultCode::Interrupted) : nullptr;
        is_need_set_content_offset_ = false;
        content_inset_animate_ = nullptr;
        content_inset_when_drag_end_ = nullptr;
        if (terminal) {
            terminal(terminal_result);
        }
        if (pending_offset_callback) {
            pending_offset_callback(pending_offset_result);
        }
        FireBeginDragEvent(event);
    } else if (is_dragging_ &&
        (IsDraggingStateToFlingState(new_scroll_state) || IsDraggingStateToIdeaState(new_scroll_state))) {
        current_scroll_state_ = new_scroll_state;
        OnWillDragEnd(event);
    } else {
        current_scroll_state_ = new_scroll_state;
    }
    if (auto handler = weak_super_touch_handler_.lock()) {
        handler->SetNativeTouchConsumer(shared_from_this());
    }
}

void KRScrollerView::OnWillDragEnd(ArkUI_NodeEvent *event) {
    is_dragging_ = false;
    if (!is_fling_enabled_) {
        // call scrollBy with 0 velocity to stop fling
        ArkUI_NumberValue values[] = {{.f32 = 0}, {.f32 = 0}};
        ArkUI_AttributeItem item = {values, 2};
        kuikly::util::GetNodeApi()->setAttribute(GetNode(), NODE_SCROLL_BY, &item);
    }
    ApplyContentInsetWhenDragEnd();
    FireWillDragEndEvent(event);
    FireEndDragEvent(event);
}

void KRScrollerView::OnScrollStart(ArkUI_NodeEvent *event) {}

void KRScrollerView::OnScrollReachStart(ArkUI_NodeEvent *event) {
    if (!limit_header_bounces_) {
        return;
    }
    InnerSetBouncesEnable(false);
}

bool KRScrollerView::IsIdeaStateToDraggingState(ArkUI_ScrollState new_scroll_state) {
    return current_scroll_state_ == ArkUI_ScrollState::ARKUI_SCROLL_STATE_IDLE &&
           new_scroll_state == ArkUI_ScrollState::ARKUI_SCROLL_STATE_SCROLL;
}

bool KRScrollerView::IsFlingStateToDraggingState(ArkUI_ScrollState new_scroll_state) {
    return current_scroll_state_ == ArkUI_ScrollState::ARKUI_SCROLL_STATE_FLING &&
           new_scroll_state == ArkUI_ScrollState::ARKUI_SCROLL_STATE_SCROLL;
}

bool KRScrollerView::IsDraggingStateToFlingState(ArkUI_ScrollState new_scroll_state) {
    return current_scroll_state_ == ArkUI_ScrollState::ARKUI_SCROLL_STATE_SCROLL &&
           new_scroll_state == ArkUI_ScrollState::ARKUI_SCROLL_STATE_FLING;
}

bool KRScrollerView::IsDraggingStateToIdeaState(ArkUI_ScrollState new_scroll_state) {
    return current_scroll_state_ == ArkUI_ScrollState::ARKUI_SCROLL_STATE_SCROLL &&
           new_scroll_state == ArkUI_ScrollState::ARKUI_SCROLL_STATE_IDLE;
}

std::shared_ptr<KRRenderValue> KRScrollerView::GetCommonScrollParams() {
    KRRenderValueMap map;
    auto point = kuikly::util::GetArkUIScrollContentOffset(GetNode());
    map[kEventKeyOffsetX] = NewKRRenderValue(point.x);
    map[kEventKeyOffsetY] = NewKRRenderValue(point.y);

    auto frame = GetFrame();
    map[kEventKeyViewWidth] = NewKRRenderValue(frame.width);
    map[kEventKeyViewHeight] = NewKRRenderValue(frame.height);

    if (content_view_) {
        auto content_view_frame = content_view_->GetFrame();
        map[kEventKeyContentWidth] = NewKRRenderValue(content_view_frame.width);
        map[kEventKeyContentHeight] = NewKRRenderValue(content_view_frame.height);
    }
    map[kEventKeyIsDragging] = NewKRRenderValue(is_dragging_ ? 1 : 0);
    map[kEventKeyNativeScrollPhase] = NewKRRenderValue(NativeScrollPhase());
    map[kEventKeyNativeInteractionEpoch] = NewKRRenderValue(native_interaction_epoch_);
    map[kEventKeyLayoutRevision] = NewKRRenderValue(native_layout_revision_);
    map[kEventKeyInsetRevision] = NewKRRenderValue(native_inset_revision_);
    auto current_operation = scroll_write_arbiter_.Current();
    auto source_operation = !is_dragging_ && current_operation && current_operation->animated
        ? current_operation->compose_operation : 0;
    map["sourceOperationGeneration"] = NewKRRenderValue(source_operation);

    // 统一计算有效速度：基于最后位移的 stale 检测 + 最小阈值过滤
    auto now = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
    constexpr int64_t kVelocityDecayThresholdMs = 150;
    constexpr float kMinVelocityThreshold = 100.0f;  // px/s

    float effective_vx = velocity_x_;
    float effective_vy = velocity_y_;
    if (now - last_move_time_ > kVelocityDecayThresholdMs) {
        effective_vx = 0;
        effective_vy = 0;
    }
    if (fabsf(effective_vx) < kMinVelocityThreshold) {
        effective_vx = 0;
    }
    if (fabsf(effective_vy) < kMinVelocityThreshold) {
        effective_vy = 0;
    }

    map[kEventKeyVelocityX] = NewKRRenderValue(effective_vx);
    map[kEventKeyVelocityY] = NewKRRenderValue(effective_vy);
    return NewKRRenderValue(std::move(map));
}

void KRScrollerView::ApplyContentInsetWhenDragEnd() {
    if (!content_inset_when_drag_end_) {
        return;
    }
    auto content_inset = content_inset_when_drag_end_;
    content_inset_when_drag_end_ = nullptr;
    KR_LOG_INFO << "apply content inset: " << content_inset->top;
    SetContentInset(content_inset, nullptr);
}

void KRScrollerView::InnerSetBouncesEnable(bool enable) {
    if (current_bounces_enabled_ == enable) {
        return;
    }

    current_bounces_enabled_ = enable;
    kuikly::util::SetArkUIBouncesEnabled(GetNode(), current_bounces_enabled_);
}

void KRScrollerView::AdjustHeaderBouncesEnableWhenWillScroll(ArkUI_NodeEvent *event) {
    if (!limit_header_bounces_) {
        return;
    }
    auto content_offset = kuikly::util::GetArkUIScrollContentOffset(GetNode());
    if (content_offset.y <= 0) {
        InnerSetBouncesEnable(false);
    } else {
        InnerSetBouncesEnable(true);
    }
}

void KRScrollerView::AddScrollObserver(IKRScrollObserver *observer) {
    scroll_observers_.insert(observer);
}
void KRScrollerView::RemoveScrollObserver(IKRScrollObserver *observer) {
    scroll_observers_.erase(observer);
}

void KRScrollerView::DispatchDidScrollToObservers(KRPoint point) {
    for (IKRScrollObserver *observer : scroll_observers_) {
        observer->OnDidScroll(point.x, point.y);
    }
}

KRPoint KRScrollerView::GetContentOffset() {
    return kuikly::util::GetArkUIScrollContentOffset(GetNode());
}

void KRScrollerView::DidMoveToParentView() {
    IKRRenderViewExport::DidMoveToParentView();
    auto parent_view = GetParentView();
    while (parent_view != nullptr) {
        if (auto view = std::dynamic_pointer_cast<KRView>(parent_view)) {
            auto handler = view->GetSuperTouchHandler();
            if (handler) {
                weak_super_touch_handler_ = handler;
                if(!OH_ArkUI_GestureInterrupter_GetUserData){
                    // Only needed when `OH_ArkUI_GestureInterrupter_GetUserData` unavailable
                    SetViewTag(GetViewTag());
                }
                RegisterGestureInterrupter();
                break;
            }
        }
        parent_view = parent_view->GetParentView();
    }
}

void KRScrollerView::WillRemoveFromParentView() {
    IKRRenderViewExport::WillRemoveFromParentView();
    weak_super_touch_handler_.reset();
}

ArkUI_GestureInterruptResult KRScrollerView::OnInterruptGestureEvent(const ArkUI_GestureInterruptInfo *info) {
    if (auto handler = weak_super_touch_handler_.lock()) {
        auto recognizer = OH_ArkUI_GestureInterruptInfo_GetRecognizer(info);
        handler->CollectGestureRecognizer(recognizer);
        if (handler->IsPreventTouch()) {
            return GESTURE_INTERRUPT_RESULT_REJECT;
        }
    }
    return IKRRenderViewExport::OnInterruptGestureEvent(info);
}

bool KRScrollerView::SetFlingEnable(bool enable) {
    is_fling_enabled_ = enable;
    return true;
}

bool KRScrollerView::SetFlingSpeedLimit(const KRAnyValue &value) {
    if (!IsFlingSpeedLimitApiAvailable()) {
        return true;
    }
    auto speed = value->toFloat();
    if (speed <= 0) {
        kuikly::util::GetNodeApi()->resetAttribute(GetNode(), kScrollFlingSpeedLimitAttr);
    } else {
        ArkUI_NumberValue values[] = {{.f32 = speed}};
        ArkUI_AttributeItem item = {values, 1};
        kuikly::util::GetNodeApi()->setAttribute(GetNode(), kScrollFlingSpeedLimitAttr, &item);
    }
    return true;
}

void KRScrollerView::TryApplyPendingFireOnScroll() {
    FireOnScrollEvent(nullptr);
}

// Clear transient native state for Compose DSL reuse (not the native reuse pool).
void KRScrollerView::PrepareForComposeReuse(const KRAnyValue &value) {
    auto previous = scroll_write_arbiter_.Current();
    auto should_stop_previous = previous && KRShouldStopReplacedScrollMotion(
        previous->resource == KRNativeScrollWriteResource::ContentOffset,
        previous->animated,
        previous->offset_correction_required,
        previous->offset_correction_finished);
    native_write_operation_sequence_++;
    compose_offset_write_generation_ = value ? value->toLong() : compose_offset_write_generation_ + 1;
    native_interaction_epoch_++;
    native_layout_revision_++;
    native_inset_revision_++;
    latest_compose_write_operation_ = 0;
    minimum_compose_write_operation_ = 0;
    std::shared_ptr<KRRenderValue> terminal_result;
    auto terminal = FinalizeScrollWrite(scroll_write_arbiter_.Current(), KRScrollWriteResultCode::Destroyed,
                                        terminal_result);
    auto pending_offset_callback = first_offset_callback_slot_.Take();
    auto pending_offset_result = pending_offset_callback
        ? ScrollWriteResult(KRScrollWriteResultCode::Destroyed) : nullptr;
    is_need_set_content_offset_ = false;
    content_inset_animate_ = nullptr;
    ArkUI_NumberValue values[] = {{.f32 = 0}, {.f32 = 0}};
    ArkUI_AttributeItem item = {values, 2};
    if (should_stop_previous) {
        replacement_stop_event_fence_.Arm();
    }
    kuikly::util::GetNodeApi()->setAttribute(GetNode(), NODE_SCROLL_BY, &item);
    // Reset scroll event dedup cache so restored offset fires a scroll event
    last_fired_scroll_x_ = -FLT_MAX;
    last_fired_scroll_y_ = -FLT_MAX;
    // Reset scroll state machine
    current_scroll_state_ = ArkUI_ScrollState::ARKUI_SCROLL_STATE_IDLE;
    // Reset drag state
    is_dragging_ = false;
    // Reset bounces dedup flag
    current_bounces_enabled_ = false;
    // Clear PullToRefresh residual
    content_inset_when_drag_end_ = nullptr;
    // Reset velocity calculation state
    last_scroll_time_ = 0;
    last_scroll_x_ = 0;
    last_scroll_y_ = 0;
    last_move_time_ = 0;
    velocity_x_ = 0;
    velocity_y_ = 0;
    if (terminal) {
        terminal(terminal_result);
    }
    if (pending_offset_callback) {
        pending_offset_callback(pending_offset_result);
    }
}

void KRScrollerView::AbortContentOffsetAnimate() {
    auto previous = scroll_write_arbiter_.Current();
    auto should_stop_previous = previous && KRShouldStopReplacedScrollMotion(
        previous->resource == KRNativeScrollWriteResource::ContentOffset,
        previous->animated,
        previous->offset_correction_required,
        previous->offset_correction_finished);
    std::shared_ptr<KRRenderValue> terminal_result;
    auto terminal = FinalizeScrollWrite(scroll_write_arbiter_.Current(), KRScrollWriteResultCode::Canceled,
                                        terminal_result);
    // 停止 ContentInset 动画（释放动画对象）
    if (content_inset_animate_) {
        content_inset_animate_ = nullptr;
    }

    // 停止滚动：通过 scrollBy(0, 0) 来停止当前的滚动/Fling 动画
    ArkUI_NumberValue values[] = {{.f32 = 0}, {.f32 = 0}};
    ArkUI_AttributeItem item = {values, 2};
    if (should_stop_previous) {
        replacement_stop_event_fence_.Arm();
    }
    kuikly::util::GetNodeApi()->setAttribute(GetNode(), NODE_SCROLL_BY, &item);
    FireEndScrollEvent(nullptr);
    if (terminal) {
        terminal(terminal_result);
    }
}
