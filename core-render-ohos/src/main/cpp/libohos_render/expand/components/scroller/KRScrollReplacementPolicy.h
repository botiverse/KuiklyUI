/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI.
 */

#pragma once

#include <cmath>

// Active-axis fact: on OHOS (API12 SetScrollTo), an offset animation only runs
// along the scroller's own direction axis. If the target already equals the
// current offset on that axis, ArkUI starts no physical motion and never
// produces the corresponding OnScrollStop — even when the off-axis component
// differs. Any "wait for a deferred stop" bookkeeping must therefore be gated
// on this fact, or the debt will be consumed by the next real user/successor
// stop (observed as lost scrollEnd / stuck Busy state).
// Active-axis distance between two offsets: the only component ArkUI can
// physically move. Off-axis components are ignored by SetScrollTo, so they must
// not participate in motion/start/target-reached judgements.
inline float KRScrollActiveAxisDelta(
    bool direction_row,
    float ax, float ay,
    float bx, float by) {
    return direction_row ? std::fabs(ax - bx) : std::fabs(ay - by);
}

inline bool KRScrollActiveAxisMotionExpected(
    bool direction_row,
    float current_x, float current_y,
    float target_x, float target_y,
    bool animated) {
    if (!animated) {
        return false;
    }
    return KRScrollActiveAxisDelta(direction_row, current_x, current_y, target_x, target_y) > 0.5f;
}

inline bool KRShouldStopReplacedScrollMotion(
    bool native_motion_expected,
    bool content_offset_resource,
    bool animated,
    bool inset_offset_correction_required,
    bool inset_offset_correction_finished) {
    return native_motion_expected && animated && (
        content_offset_resource ||
        (inset_offset_correction_required && !inset_offset_correction_finished));
}

class KRReplacementStopEventFence {
 public:
    void Arm() {
        pending_stop_event_ = true;
    }

    bool ConsumeReplacementStop() {
        if (!pending_stop_event_) {
            return false;
        }
        pending_stop_event_ = false;
        return true;
    }

    void Reset() {
        pending_stop_event_ = false;
    }

    bool Pending() const {
        return pending_stop_event_;
    }

 private:
    bool pending_stop_event_ = false;
};
