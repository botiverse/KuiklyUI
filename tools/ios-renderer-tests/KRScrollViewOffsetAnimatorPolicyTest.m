/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI.
 */

#import <Foundation/Foundation.h>
#import "KRScrollViewOffsetAnimator.h"

static void Assert(BOOL condition, NSString *message) {
    if (!condition) {
        NSLog(@"FAILED: %@", message);
        exit(1);
    }
}

@interface OffsetAnimatorTestDelegate : NSObject <KRScrollViewOffsetAnimatorDelegate>
@end

@implementation OffsetAnimatorTestDelegate
- (void)animateContentOffsetDidChanged:(CGPoint)contentOffset {
}
@end

int main(void) {
    @autoreleasepool {
        UIScrollView *scrollView = [[UIScrollView alloc] init];
        OffsetAnimatorTestDelegate *delegate = [[OffsetAnimatorTestDelegate alloc] init];
        KRScrollViewOffsetAnimator *first =
            [[KRScrollViewOffsetAnimator alloc] initWithScrollView:scrollView delegate:delegate];
        KRScrollViewOffsetAnimator *replacement =
            [[KRScrollViewOffsetAnimator alloc] initWithScrollView:scrollView delegate:delegate];

        Assert(
            [KRScrollViewOffsetAnimator isCurrentAnimator:first
                                                 candidate:first
                                         currentGeneration:7
                                      completionGeneration:7],
            @"current animator and generation should be accepted"
        );
        Assert(
            ![KRScrollViewOffsetAnimator isCurrentAnimator:replacement
                                                  candidate:first
                                          currentGeneration:8
                                       completionGeneration:7],
            @"replacement should reject stale animator completion"
        );
        Assert(
            ![KRScrollViewOffsetAnimator isCurrentAnimator:first
                                                  candidate:first
                                          currentGeneration:8
                                       completionGeneration:7],
            @"reuse generation should reject stale completion"
        );

        Assert([first claimCompletion], @"current completion should be claimed once");
        Assert(![first claimCompletion], @"duplicate completion should be rejected");

        [replacement cancel];
        Assert(![replacement claimCompletion], @"canceled animator should not emit terminal");

        Assert(
            [KRScrollViewOffsetAnimator shouldEmitTerminalForNativePhase:0],
            @"idle completion should emit terminal"
        );
        Assert(
            ![KRScrollViewOffsetAnimator shouldEmitTerminalForNativePhase:1],
            @"dragging completion should defer to gesture terminal"
        );
        Assert(
            ![KRScrollViewOffsetAnimator shouldEmitTerminalForNativePhase:2],
            @"another active animation should own terminal"
        );
    }
    return 0;
}
