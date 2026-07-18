#import <AppKit/AppKit.h>
#include <stdlib.h>
#import "KRScrollView.h"
#import "KRContentOffsetAnimator.h"
#import "KRConvertUtil.h"
#import "KRWrapperView.h"
#import "KuiklyRenderView.h"

@interface KRScrollView (TransactionFixture)
- (void)scrollViewDidEndDragging:(UIScrollView *)scrollView willDecelerate:(BOOL)decelerate;
- (void)setCss_scrollEnd:(KuiklyRenderCallback)callback;
@end

@implementation KRConvertUtil
+ (CGRect)hr_rectInset:(CGRect)rect insets:(UIEdgeInsets)insets {
    return CGRectMake(
        rect.origin.x + insets.left,
        rect.origin.y + insets.top,
        MAX(0, rect.size.width - insets.left - insets.right),
        MAX(0, rect.size.height - insets.top - insets.bottom)
    );
}
@end

@implementation KRWrapperView
- (instancetype)initWithHostView:(UIView *)hostView {
    return [super initWithFrame:hostView.frame];
}
- (void)moveToSuperview:(UIView *)superView {
    [superView addSubview:self];
}
@end

@implementation KRView
@end

@implementation KuiklyRenderView
@end

@implementation KRContentOffsetAnimator
- (instancetype)initWithScrollView:(UIScrollView *)scrollView {
    return [super init];
}
- (void)animateToOffset:(CGPoint)offset
               duration:(CGFloat)duration
         timingFunction:(CAMediaTimingFunction *)timingFunction
             onProgress:(void (^)(CGFloat progress))onProgress
             completion:(void (^)(BOOL finished))completion {
    if (completion) completion(YES);
}
- (void)stop {}
- (BOOL)isAnimating { return NO; }
- (CGPoint)targetOffset { return CGPointZero; }
@end

static NSInteger ResultCode(NSDictionary *result) {
    return [result[@"resultCode"] integerValue];
}

static void Assert(BOOL condition, NSString *message) {
    if (!condition) {
        NSLog(@"FAIL: %@", message);
        exit(1);
    }
}

int main(void) {
    @autoreleasepool {
        [NSApplication sharedApplication];
        KRScrollView *scrollView = [[KRScrollView alloc] initWithFrame:CGRectMake(0, 0, 100, 100)];
        scrollView.contentSize = CGSizeMake(100, 400);

        __block NSInteger aResult = -1;
        __block NSInteger bResult = -1;
        __block NSInteger cResult = -1;

        [scrollView hrv_callWithMethod:@"contentOffset"
                                params:@"0 40 1"
                              callback:^(NSDictionary *result) {
            aResult = ResultCode(result);
            [scrollView hrv_callWithMethod:@"contentOffset"
                                    params:@"0 120 0"
                                  callback:^(NSDictionary *cTerminal) {
                cResult = ResultCode(cTerminal);
            }];
        }];

        [scrollView hrv_callWithMethod:@"contentOffset"
                                params:@"0 80 1"
                              callback:^(NSDictionary *result) {
            bResult = ResultCode(result);
        }];

        Assert(aResult == 6, @"A must receive Replaced before its callback installs C");
        Assert(bResult == 6, @"C installation must replace B exactly once");
        Assert(cResult == 0, @"C immediate write must commit");
        Assert(fabs(scrollView.contentOffset.y - 120.0) <= 1.0, @"A/B stale stacks must not overwrite C");

        scrollView.contentInset = UIEdgeInsetsMake(40, 0, 0, 0);
        scrollView.contentOffset = CGPointMake(0, -40);
        __block NSInteger insetAResult = -1;
        __block NSInteger insetBResult = -1;
        __block NSInteger insetCResult = -1;
        __block NSInteger insetATerminals = 0;

        [scrollView hrv_callWithMethod:@"contentInset"
                                params:@"20 0 0 0 1"
                              callback:^(NSDictionary *result) {
            insetATerminals += 1;
            insetAResult = ResultCode(result);
            [scrollView hrv_callWithMethod:@"contentInset"
                                    params:@"30 0 0 0 0"
                                  callback:^(NSDictionary *cTerminal) {
                insetCResult = ResultCode(cTerminal);
            }];
        }];

        [scrollView hrv_callWithMethod:@"contentInset"
                                params:@"10 0 0 0 0"
                              callback:^(NSDictionary *result) {
            insetBResult = ResultCode(result);
        }];

        [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.5]];
        Assert(insetAResult == 6, @"animated inset A must be replaced");
        Assert(insetATerminals == 1, @"animated inset A must terminate exactly once");
        Assert(insetBResult == 6, @"inset C installation must replace B exactly once");
        Assert(insetCResult == 0, @"inset C immediate write must commit");
        Assert(fabs(scrollView.contentInset.top - 30.0) <= 1.0,
               @"stale animated inset A must not overwrite callback-installed C");

        if (getenv("KR_SKIP_ACTIVE_OPERATION_END_TEST") == NULL) {
            KRScrollView *dragEndView = [[KRScrollView alloc] initWithFrame:CGRectMake(0, 0, 100, 100)];
            dragEndView.contentSize = CGSizeMake(100, 400);
            __block NSInteger prematureScrollEnds = 0;
            [dragEndView setCss_scrollEnd:^(NSDictionary *params) {
                prematureScrollEnds += 1;
            }];
            [dragEndView hrv_callWithMethod:@"contentOffset"
                                     params:@"0 120 1 300 1 0 0 0 0 1 -1 -1 0 -1 0 1 1 0 0 0 0 0"
                                   callback:^(NSDictionary *result) {}];
            [dragEndView scrollViewDidEndDragging:dragEndView willDecelerate:NO];
            Assert(prematureScrollEnds == 0,
                   @"drag end must not emit scrollEnd while a compose write operation is active");
            [dragEndView hrv_callWithMethod:@"contentOffset"
                                     params:@"0 140 0 0 0 0 0 0 0 2 -1 -1 0 -1 0 2 1 0 0 0 0 0"
                                   callback:^(NSDictionary *result) {}];
        }

        if (getenv("KR_SKIP_DIRECT_INSET_END_TEST") == NULL) {
            KRScrollView *directInsetView = [[KRScrollView alloc] initWithFrame:CGRectMake(0, 0, 100, 100)];
            directInsetView.contentSize = CGSizeMake(100, 400);
            directInsetView.contentOffset = CGPointMake(0, 100);
            __block NSInteger directInsetResult = -1;
            __block NSInteger directInsetScrollEnds = 0;
            __block NSInteger directInsetSourceOperation = -1;
            [directInsetView setCss_scrollEnd:^(NSDictionary *params) {
                directInsetScrollEnds += 1;
                directInsetSourceOperation = [params[@"sourceOperationGeneration"] integerValue];
            }];
            [directInsetView hrv_callWithMethod:@"contentInset"
                                         params:@"20 0 0 0 1 0 0 1 -1 -1 0 -1 0 1 1 0 0 0 0 0"
                                       callback:^(NSDictionary *result) {
                directInsetResult = ResultCode(result);
            }];
            Assert(directInsetResult == 0, @"direct animated inset write must commit");
            Assert(directInsetScrollEnds == 1,
                   @"direct animated inset commit must emit exactly one physical end");
            Assert(directInsetSourceOperation == 1,
                   @"direct animated inset end must retain its compose operation identity");
        }

        if (getenv("KR_SKIP_ANIMATED_COMPLETION_END_TEST") == NULL) {
            KRScrollView *completionView = [[KRScrollView alloc] initWithFrame:CGRectMake(0, 0, 100, 100)];
            completionView.contentSize = CGSizeMake(100, 400);
            __block NSInteger completionResult = -1;
            __block NSInteger completionScrollEnds = 0;
            __block NSInteger completionSourceOperation = -1;
            [completionView setCss_scrollEnd:^(NSDictionary *params) {
                completionScrollEnds += 1;
                completionSourceOperation = [params[@"sourceOperationGeneration"] integerValue];
            }];
            [completionView hrv_callWithMethod:@"contentOffset"
                                        params:@"0 50 1 10 1 0 0 0 0 3 -1 -1 0 -1 0 3 1 0 0 0 0 0"
                                      callback:^(NSDictionary *result) {
                completionResult = ResultCode(result);
            }];
            [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.3]];
            Assert(completionResult == 0, @"animated offset write must commit");
            Assert(completionScrollEnds == 1,
                   @"animated offset completion must emit exactly one physical end");
            Assert(completionSourceOperation == 3,
                   @"animated offset end must retain its compose operation identity");
        }
    }
    return 0;
}
