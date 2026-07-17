/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI.
 */

#ifndef CORE_RENDER_OHOS_KRPENDINGCALLBACKSLOT_H
#define CORE_RENDER_OHOS_KRPENDINGCALLBACKSLOT_H

#include <utility>

template <typename Callback>
class KRPendingCallbackSlot {
 public:
    Callback Replace(Callback callback) {
        auto previous = Take();
        callback_ = std::move(callback);
        pending_ = true;
        return previous;
    }

    Callback Take() {
        if (!pending_) {
            return Callback{};
        }
        pending_ = false;
        auto callback = std::move(callback_);
        callback_ = Callback{};
        return callback;
    }

    bool HasPending() const {
        return pending_;
    }

 private:
    bool pending_ = false;
    Callback callback_{};
};

#endif  // CORE_RENDER_OHOS_KRPENDINGCALLBACKSLOT_H
