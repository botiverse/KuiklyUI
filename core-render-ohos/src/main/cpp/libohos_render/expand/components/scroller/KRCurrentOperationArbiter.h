/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI.
 */

#ifndef CORE_RENDER_OHOS_KRCURRENTOPERATIONARBITER_H
#define CORE_RENDER_OHOS_KRCURRENTOPERATIONARBITER_H

#include <memory>

template <typename Operation>
class KRCurrentOperationArbiter {
 public:
    std::shared_ptr<Operation> Replace(const std::shared_ptr<Operation> &operation) {
        auto previous = current_;
        current_ = operation;
        return previous;
    }

    bool Finalize(const std::shared_ptr<Operation> &operation) {
        if (!operation || operation->terminal) {
            return false;
        }
        operation->terminal = true;
        if (current_ == operation) {
            current_ = nullptr;
        }
        return true;
    }

    bool IsCurrent(const std::shared_ptr<Operation> &operation) const {
        return operation && !operation->terminal && current_ == operation;
    }

    const std::shared_ptr<Operation> &Current() const {
        return current_;
    }

 private:
    std::shared_ptr<Operation> current_;
};

#endif  // CORE_RENDER_OHOS_KRCURRENTOPERATIONARBITER_H
