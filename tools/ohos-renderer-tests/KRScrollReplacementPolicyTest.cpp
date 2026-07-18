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
    Assert(KRShouldStopReplacedScrollMotion(true, true, true, false, false),
           "animated ContentOffset must stop");
    Assert(KRShouldStopReplacedScrollMotion(true, false, true, true, false),
           "unfinished animated ContentInset correction must stop");
    Assert(!KRShouldStopReplacedScrollMotion(true, false, true, true, true),
           "finished ContentInset correction must not stop again");
    Assert(!KRShouldStopReplacedScrollMotion(true, false, true, false, false),
           "ContentInset without offset correction must not stop");
    Assert(!KRShouldStopReplacedScrollMotion(true, false, false, true, false),
           "nonanimated ContentInset must not stop");
    Assert(!KRShouldStopReplacedScrollMotion(false, true, true, false, false),
           "no native motion expected => replacement stop must not be armed");

    // Active-axis fact (OHOS API12 SetScrollTo animates along the scroller axis only).
    // Repro of the confirmed bug: vertical scroller at (0,100) asked to animate to
    // (8,100) — off-axis diff must NOT be treated as motion, otherwise the armed
    // fence consumes the next real OnScrollStop (lost scrollEnd / stuck Busy).
    Assert(!KRScrollActiveAxisMotionExpected(false, 0, 100, 8, 100, true),
           "vertical scroller: off-axis-only animated target expects no native motion");
    Assert(KRScrollActiveAxisMotionExpected(false, 0, 100, 8, 101, true),
           "vertical scroller: active-axis animated diff expects native motion");
    Assert(KRScrollActiveAxisMotionExpected(true, 0, 100, 8, 100, true),
           "horizontal scroller: active-axis animated diff expects native motion");
    Assert(!KRScrollActiveAxisMotionExpected(true, 0, 100, 0, 108, true),
           "horizontal scroller: off-axis-only animated target expects no native motion");
    Assert(!KRScrollActiveAxisMotionExpected(false, 0, 100, 0, 100.4f, true),
           "sub-epsilon active-axis diff expects no native motion");
    Assert(!KRScrollActiveAxisMotionExpected(false, 0, 100, 0, 108, false),
           "non-animated write never expects deferred stop");

    // Terminal must be judged on the same active axis as admission (review blocker):
    // vertical (0,100)->(8,101) is admitted (y diff 1 > 0.5) and physically ends at
    // (0,101); x+y semantics would reject terminal (x delta 8) and force AckTimeout.
    Assert(KRScrollActiveAxisDelta(false, 0, 101, 8, 101) <= 1.0f,
           "vertical: active-axis terminal reached despite off-axis residue");
    Assert(KRScrollActiveAxisDelta(false, 0, 100, 8, 101) > 0.5f,
           "vertical: admission sees active-axis diff");
    Assert(KRScrollActiveAxisDelta(true, 101, 0, 101, 8) <= 1.0f,
           "horizontal: active-axis terminal reached despite off-axis residue");
    Assert(KRScrollActiveAxisDelta(true, 100, 0, 101, 8) > 0.5f,
           "horizontal: admission sees active-axis diff");

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
                   true,
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
