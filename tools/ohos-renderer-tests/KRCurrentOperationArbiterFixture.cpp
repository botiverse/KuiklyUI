/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI.
 */

#include <cstdlib>
#include <functional>
#include <iostream>
#include <memory>

#include "KRCurrentOperationArbiter.h"

struct FixtureOperation {
    bool terminal = false;
    std::function<void()> callback;
};

static void Assert(bool condition, const char *message) {
    if (!condition) {
        std::cerr << "FAILED: " << message << std::endl;
        std::exit(1);
    }
}

int main() {
    KRCurrentOperationArbiter<FixtureOperation> arbiter;
    auto operation_a = std::make_shared<FixtureOperation>();
    auto operation_b = std::make_shared<FixtureOperation>();
    auto operation_c = std::make_shared<FixtureOperation>();

    Assert(!arbiter.Replace(operation_a), "A should install into an empty arbiter");
    Assert(arbiter.IsCurrent(operation_a), "A should be current");

    auto replaced_a = arbiter.Replace(operation_b);
    Assert(replaced_a == operation_a, "B should detach A");
    Assert(!arbiter.IsCurrent(operation_a), "detached A must stop being current before terminal delivery");
    Assert(arbiter.IsCurrent(operation_b), "B should become current before A terminal delivery");
    Assert(arbiter.Finalize(replaced_a), "A should finalize once");
    replaced_a->callback = [&]() {
        auto replaced_b = arbiter.Replace(operation_c);
        Assert(replaced_b == operation_b, "A callback should replace B with C");
        Assert(arbiter.Finalize(replaced_b), "B should finalize once");
    };
    replaced_a->callback();

    Assert(arbiter.IsCurrent(operation_c), "callback-installed C should remain current");
    Assert(!arbiter.Finalize(operation_a), "stale A teardown must be one-shot");
    Assert(!arbiter.Finalize(operation_b), "stale B teardown must be one-shot");
    Assert(arbiter.IsCurrent(operation_c), "stale teardown must not clear C");
    Assert(arbiter.Finalize(operation_c), "C should finalize once");
    Assert(!arbiter.Current(), "terminal C should clear the arbiter");

    return 0;
}
