/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI.
 */

#include <cstdlib>
#include <iostream>
#include <string>

#include "KRPendingCallbackSlot.h"

static void Assert(bool condition, const char *message) {
    if (!condition) {
        std::cerr << "FAILED: " << message << std::endl;
        std::exit(1);
    }
}

int main() {
    KRPendingCallbackSlot<std::string> slot;

    Assert(!slot.HasPending(), "new slot should be empty");
    Assert(slot.Replace("A").empty(), "first replace should not return a callback");
    Assert(slot.HasPending(), "replace should create pending state");
    Assert(slot.Replace("B") == "A", "replacement should return the old callback once");
    Assert(slot.Take() == "B", "take should return the replacement");
    Assert(slot.Take().empty(), "repeated teardown should not return a callback twice");
    Assert(!slot.HasPending(), "taken slot should be empty");

    return 0;
}
