/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI.
 */

#include <cstdlib>
#include <iostream>

#include "KRScrollReplacementPolicy.h"

static void Assert(bool condition, const char *message) {
    if (!condition) {
        std::cerr << "FAILED: " << message << std::endl;
        std::exit(1);
    }
}

int main() {
    Assert(KRShouldStopReplacedScrollMotion(true, true, false, false),
           "animated ContentOffset must stop");
    Assert(KRShouldStopReplacedScrollMotion(false, true, true, false),
           "unfinished animated ContentInset correction must stop");
    Assert(!KRShouldStopReplacedScrollMotion(false, true, true, true),
           "finished ContentInset correction must not stop again");
    Assert(!KRShouldStopReplacedScrollMotion(false, true, false, false),
           "ContentInset without offset correction must not stop");
    Assert(!KRShouldStopReplacedScrollMotion(false, false, true, false),
           "nonanimated ContentInset must not stop");

    const struct {
        const char *name;
        bool content_offset_resource;
        bool inset_offset_correction_required;
    } replacement_cases[] = {
        {"content-offset", true, false},
        {"inset-correction", false, true},
    };
    KRReplacementStopEventFence stop_event_fence;
    Assert(!stop_event_fence.ConsumeReplacementStop(),
           "ordinary scroll stop must remain visible without a replacement stop");
    for (const auto &replacement_case : replacement_cases) {
        Assert(KRShouldStopReplacedScrollMotion(
                   replacement_case.content_offset_resource,
                   true,
                   replacement_case.inset_offset_correction_required,
                   false),
               replacement_case.name);
        for (const char *successor_state : {"not-started", "moving", "at-target"}) {
            stop_event_fence.Arm();
            Assert(stop_event_fence.ConsumeReplacementStop(), successor_state);
            Assert(!stop_event_fence.Pending(),
                   "replacement stop must be consumed before inspecting successor state");
            Assert(!stop_event_fence.ConsumeReplacementStop(),
                   "successor real stop must remain visible exactly once");
        }
    }
    stop_event_fence.Arm();
    stop_event_fence.Arm();
    Assert(stop_event_fence.ConsumeReplacementStop(),
           "coalesced replacement stop must be consumed once");
    Assert(!stop_event_fence.ConsumeReplacementStop(),
           "coalesced replacements must not leave debt that swallows successor terminal");
    stop_event_fence.Arm();
    stop_event_fence.Reset();
    Assert(!stop_event_fence.Pending(), "detached lifecycle must clear replacement stop debt");
    Assert(!stop_event_fence.ConsumeReplacementStop(),
           "successor terminal after detached lifecycle must remain visible");
    stop_event_fence.Arm();
    Assert(stop_event_fence.Pending(),
           "connected compose reuse must retain an actual deferred replacement stop");
    Assert(stop_event_fence.ConsumeReplacementStop(),
           "old lifecycle replacement stop must be consumed before successor terminal");
    Assert(!stop_event_fence.ConsumeReplacementStop(),
           "successor terminal after connected compose reuse must remain visible");
    return 0;
}
