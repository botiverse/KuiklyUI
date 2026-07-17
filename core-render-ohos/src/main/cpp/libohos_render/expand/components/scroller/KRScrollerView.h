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

#ifndef CORE_RENDER_OHOS_KRSCROLLERVIEW_H
#define CORE_RENDER_OHOS_KRSCROLLERVIEW_H

#include <unordered_set>
#include "KRCurrentOperationArbiter.h"
#include "KRPendingCallbackSlot.h"
#include "KRScrollReplacementPolicy.h"
#include "KRScrollerContentInset.h"
#include "libohos_render/export/IKRRenderViewExport.h"
#include "libohos_render/foundation/KRPoint.h"
#include "libohos_render/foundation/KRRect.h"
#include "libohos_render/utils/animate/KRAnimation.h"
#include "libohos_render/expand/components/view/SuperTouchHandler.h"

class IKRScrollObserver {
 public:
    // 滚动变化回调
    virtual void OnDidScroll(float offsetX, float offsetY) {}
};

class IKRContentScrollObserver {
 public:
    // 子孩子有插入时回调
    virtual void ContentViewDidInsertSubview() {}
    virtual void ContentViewDidMoveToParentView() {}
    virtual void ContentViewWillRemoveFromParentView() {}
};

enum class KRScrollWriteResultCode : int {
    Committed = 0,
    AlreadySatisfied = 1,
    Busy = 2,
    NotReady = 3,
    LayoutChanged = 4,
    Stale = 5,
    Replaced = 6,
    Canceled = 7,
    Destroyed = 8,
    OutOfRange = 9,
    UnsupportedAxisOrNoLayout = 10,
    Interrupted = 11,
    AckTimeout = 12,
    RollbackFailed = 13,
};

enum class KRNativeScrollWriteResource : int {
    ContentOffset = 0,
    ContentInset = 1,
};

struct KRNativeScrollWriteOperation {
    uint64_t sequence = 0;
    KRNativeScrollWriteResource resource = KRNativeScrollWriteResource::ContentOffset;
    KRRenderCallback callback = nullptr;
    int64_t generation = -1;
    int64_t compose_operation = 0;
    int64_t interaction_epoch = 0;
    int64_t layout_revision = 0;
    int64_t inset_revision = 0;
    KRPoint start;
    KRPoint target;
    bool animated = false;
    bool observed_start = false;
    bool inset_mutation_applied = false;
    bool inset_animation_finished = false;
    bool offset_correction_required = false;
    bool offset_correction_finished = false;
    bool replaced_previous = false;
    bool physical_end_emitted = false;
    bool terminal = false;
};

class KRScrollerContentView : public IKRRenderViewExport {
 public:
    KRScrollerContentView() = default;
    KRScrollerContentView(const KRScrollerContentView &) = delete;
    KRScrollerContentView(KRScrollerContentView &&) = delete;
    KRScrollerContentView &operator=(const KRScrollerContentView &) = delete;
    KRScrollerContentView &operator=(KRScrollerContentView &&) = delete;

    ArkUI_NodeHandle CreateNode() override;
    void DidInit() override;
    void OnEvent(ArkUI_NodeEvent *event, const ArkUI_NodeEventType &event_type) override;
    bool CustomSetViewFrame() override;
    void SetRenderViewFrame(const KRRect &frame) override;
    void DidInsertSubRenderView(const std::shared_ptr<IKRRenderViewExport> &sub_render_view, int index) override;
    void DidMoveToParentView() override;
    void WillRemoveFromParentView() override;
    void AddContentScrollObserver(IKRContentScrollObserver *observer);
    void RemoveContentScrollObserver(IKRContentScrollObserver *observer);

    const std::unordered_set<IKRContentScrollObserver *> &GetContentScrollObservers() {
        return contentScrollObservers_;
    }

 private:
    std::unordered_set<IKRContentScrollObserver *> contentScrollObservers_;
    bool handling_set_view_frame_ = false;
};

class KRScrollerView : public IKRRenderViewExport {
 public:
    KRScrollerView() = default;
    KRScrollerView(const KRScrollerView &) = delete;
    KRScrollerView(KRScrollerView &&) = delete;
    KRScrollerView &operator=(const KRScrollerView &) = delete;
    KRScrollerView &operator=(KRScrollerView &&) = delete;

    ArkUI_NodeHandle CreateNode() override;
    void DidInit() override;
    void SetRenderViewFrame(const KRRect &frame) override;
    bool SetProp(const std::string &prop_key, const KRAnyValue &prop_value,
                 const KRRenderCallback event_call_back = nullptr) override;
    bool ResetProp(const std::string &prop_key) override;
    void CallMethod(const std::string &method, const KRAnyValue &params, const KRRenderCallback &callback) override;
    void OnEvent(ArkUI_NodeEvent *event, const ArkUI_NodeEventType &event_type) override;
    void OnDestroy() override;
    void DidInsertSubRenderView(const std::shared_ptr<IKRRenderViewExport> &sub_render_view, int index) override;
    void AddScrollObserver(IKRScrollObserver *observer);
    void RemoveScrollObserver(IKRScrollObserver *observer);
    KRPoint GetContentOffset();
    void DidMoveToParentView() override;
    void WillRemoveFromParentView() override;
    ArkUI_GestureInterruptResult OnInterruptGestureEvent(const ArkUI_GestureInterruptInfo *info) override;
    void TryApplyPendingFireOnScroll();

    bool IsScrollView() override {
        return true;
    }

 private:
    bool SetNestedScroll(const KRAnyValue &value);
    bool SetScrollEnabled(const KRAnyValue &value);
    bool SetScrollDirection(const KRAnyValue &value);
    bool SetPagingEnabled(const KRAnyValue &value);
    bool SetBouncesEnable(const KRAnyValue &value);
    bool SetLimitHeaderBounces(const KRAnyValue &value);
    bool SetShowScrollerIndicator(const KRAnyValue &value);
    bool RegisterOnScrollEvent(const KRRenderCallback event_call_back);
    bool RegisterOnDragBeginEvent(const KRRenderCallback event_callback);
    bool RegisterOnDragEndEvent(const KRRenderCallback event_callback);
    bool RegisterOnScrollEndEvent(const KRRenderCallback event_callback);
    bool RegisterWillDragEndEvent(const KRRenderCallback event_callback);
    void FireOnScrollEvent(ArkUI_NodeEvent *event);
    void FireBeginDragEvent(ArkUI_NodeEvent *event);
    void FireEndDragEvent(ArkUI_NodeEvent *event);
    void FireEndScrollEvent(ArkUI_NodeEvent *event);
    void FireWillDragEndEvent(ArkUI_NodeEvent *event);
    void SetContentOffset(const KRAnyValue &value, const KRRenderCallback &callback);
    void CompleteOffsetWrite(const KRRenderCallback &callback, KRScrollWriteResultCode result_code);
    KRScrollWriteResultCode ValidateOffsetWrite(int64_t generation, bool requires_native_idle,
                                                int64_t operation_generation,
                                                int64_t interaction_epoch,
                                                int64_t layout_revision,
                                                int64_t inset_revision);
    std::shared_ptr<KRNativeScrollWriteOperation> InstallScrollWrite(
        KRNativeScrollWriteResource resource,
        int64_t generation, int64_t operation_generation, int64_t interaction_epoch,
        int64_t layout_revision, int64_t inset_revision, const KRRenderCallback &callback);
    KRRenderCallback FinalizeScrollWrite(const std::shared_ptr<KRNativeScrollWriteOperation> &operation,
                                         KRScrollWriteResultCode result_code,
                                         std::shared_ptr<KRRenderValue> &result);
    void InvalidateCurrentScrollWrite(KRScrollWriteResultCode result_code);
    bool IsCurrentNativeScrollWrite(const std::shared_ptr<KRNativeScrollWriteOperation> &operation) const;
    std::shared_ptr<KRRenderValue> ScrollWriteResult(
        KRScrollWriteResultCode result_code,
        const std::shared_ptr<KRNativeScrollWriteOperation> &operation = nullptr);
    void SetContentInset(const KRAnyValue &value, const KRRenderCallback &callback);
    void SetContentInset(const std::shared_ptr<KRScrollerContentInset> &content_inset,
                         const KRRenderCallback &callback);
    void CompleteContentInsetWrite(const std::shared_ptr<KRNativeScrollWriteOperation> &operation,
                                   KRScrollWriteResultCode result_code, bool fire_scroll_end);
    void SetContentInsetWhenDragEnd(const KRAnyValue &value);
    void AbortContentOffsetAnimate();
    void PrepareForComposeReuse(const KRAnyValue &value);
    void OnScrollFrameBegin(ArkUI_NodeEvent *event);
    void OnScrollStop(ArkUI_NodeEvent *event);
    void OnWillScroll(ArkUI_NodeEvent *event);
    void OnWillDragEnd(ArkUI_NodeEvent *event);
    void OnScrollStart(ArkUI_NodeEvent *event);
    void OnScrollReachStart(ArkUI_NodeEvent *event);
    bool IsIdeaStateToDraggingState(ArkUI_ScrollState new_scroll_state);
    bool IsFlingStateToDraggingState(ArkUI_ScrollState new_scroll_state);
    bool IsDraggingStateToFlingState(ArkUI_ScrollState new_scroll_state);
    bool IsDraggingStateToIdeaState(ArkUI_ScrollState new_scroll_state);
    std::shared_ptr<KRRenderValue> GetCommonScrollParams();
    void ApplyContentInsetWhenDragEnd();
    void InnerSetBouncesEnable(bool enable);
    void AdjustHeaderBouncesEnableWhenWillScroll(ArkUI_NodeEvent *event);
    void DispatchDidScrollToObservers(KRPoint point);
    bool SetFlingEnable(bool enable);
    bool SetFlingSpeedLimit(const KRAnyValue &value);
    KRPoint MaxContentOffsetInContentInset(const std::shared_ptr<KRScrollerContentInset> &content_inset);
    bool CanApplyOffsetWrite(int64_t generation, bool requires_native_idle) const;
    bool ClaimOffsetWrite(int64_t generation, bool requires_native_idle, int64_t operation_generation);
    bool IsCurrentOffsetWrite(int64_t operation_generation) const;
    bool MatchesExpectedLayout(float expected_content_size, float expected_viewport_size);
    int NativeScrollPhase() const;

 private:
    KRRenderCallback on_scroll_callback_ = nullptr;
    KRRenderCallback on_drag_begin_callback_ = nullptr;
    KRRenderCallback on_drag_end_callback_ = nullptr;
    KRRenderCallback on_scroll_end_callback_ = nullptr;
    KRRenderCallback on_will_drag_end_callback_ = nullptr;
    std::shared_ptr<KRScrollerContentView> content_view_;
    bool bounces_enabled_ = true;
    bool limit_header_bounces_ = false;
    bool current_bounces_enabled_ = false;
    bool is_dragging_ = false;
    bool is_set_frame_ = false;
    bool is_need_set_content_offset_ = false;
    float first_offset_x_ = 0;
    float first_offset_y_ = 0;
    bool first_animate_ = false;
    int first_duration_ = 0;
    int first_curve_ = 0;
    float first_damping_ = 0;
    int64_t first_offset_generation_ = -1;
    bool first_offset_requires_native_idle_ = false;
    int64_t first_offset_operation_generation_ = 0;
    float first_offset_expected_content_size_ = -1.0f;
    float first_offset_expected_viewport_size_ = -1.0f;
    KRPendingCallbackSlot<KRRenderCallback> first_offset_callback_slot_;
    int64_t compose_offset_write_generation_ = 0;
    uint64_t native_write_operation_sequence_ = 0;
    int64_t latest_compose_write_operation_ = 0;
    int64_t minimum_compose_write_operation_ = 0;
    int64_t native_interaction_epoch_ = 0;
    int64_t native_layout_revision_ = 0;
    int64_t native_inset_revision_ = 0;
    KRRect last_revision_frame_;
    KRCurrentOperationArbiter<KRNativeScrollWriteOperation> scroll_write_arbiter_;
    KRReplacementStopEventFence replacement_stop_event_fence_;
    ArkUI_ScrollState current_scroll_state_;

    std::shared_ptr<KRAnimation> content_inset_animate_;
    std::shared_ptr<KRScrollerContentInset> content_inset_when_drag_end_;
    std::unordered_set<IKRScrollObserver *> scroll_observers_;

    // 滚动相关的成员变量
    int64_t last_scroll_time_ = 0;
    float last_scroll_x_ = 0;
    float last_scroll_y_ = 0;
    float velocity_x_ = 0;
    float velocity_y_ = 0;
    int64_t last_move_time_ = 0;  // 上次产生有效位移的时间，用于判断速度是否过期
    std::weak_ptr<SuperTouchHandler> weak_super_touch_handler_;
    bool is_fling_enabled_ = true;
    float last_fired_scroll_x_ = 0;
    float last_fired_scroll_y_ = 0;
    bool direction_row_ = false;
};

#endif  // CORE_RENDER_OHOS_KRSCROLLERVIEW_H
