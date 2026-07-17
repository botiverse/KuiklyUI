/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI.
 */

#pragma once

inline bool KRShouldStopReplacedScrollMotion(
    bool content_offset_resource,
    bool animated,
    bool inset_offset_correction_required,
    bool inset_offset_correction_finished) {
    return animated && (
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
