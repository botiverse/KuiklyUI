#import <AppKit/AppKit.h>
#import <objc/runtime.h>

#import "KRGradientRichTextView.h"
#import "KRRichTextView.h"

@implementation KRLabel
@end

@implementation KRRichTextView

- (void)hrv_setPropWithKey:(NSString *)propKey propValue:(id)propValue {
}

- (void)hrv_prepareForeReuse {
}

+ (id<KuiklyRenderShadowProtocol>)hrv_createShadow {
    return nil;
}

- (void)hrv_setShadow:(id<KuiklyRenderShadowProtocol>)shadow {
}

@end

static NSInteger forwardedCallCount = 0;
static NSString *forwardedMethod = nil;
static NSString *forwardedParams = nil;

static void KRImplementedViewMethod(
    __unused KRRichTextView *self,
    __unused SEL command,
    NSString *method,
    NSString *params,
    KuiklyRenderCallback callback
) {
    forwardedCallCount += 1;
    forwardedMethod = method;
    forwardedParams = params;
    if (callback) {
        callback(@{ @"forwarded": @YES });
    }
}

static void KRAssert(BOOL condition, NSString *message) {
    if (!condition) {
        NSLog(@"KRGradientRichTextViewMethodDispatchTest failed: %@", message);
        exit(1);
    }
}

static void testMissingOptionalInnerMethodIsIgnored(void) {
    SEL selector = @selector(hrv_callWithMethod:params:callback:);
    KRAssert(
        ![KRRichTextView instancesRespondToSelector:selector],
        @"fixture inner view must begin without the optional method"
    );

    KRGradientRichTextView *gradientView =
        [[KRGradientRichTextView alloc] initWithFrame:CGRectMake(0, 0, 100, 20)];
    __block BOOL callbackCalled = NO;
    BOOL threw = NO;

    @try {
        [gradientView hrv_callWithMethod:@"unsupported"
                                  params:@"payload"
                                callback:^(__unused id result) {
                                    callbackCalled = YES;
                                }];
    } @catch (__unused NSException *exception) {
        threw = YES;
    }

    KRAssert(!threw, @"wrapper must not message an inner view that lacks the optional selector");
    KRAssert(!callbackCalled, @"unsupported method must not synthesize a callback");
}

static void testImplementedInnerMethodStillForwardsExactlyOnce(void) {
    SEL selector = @selector(hrv_callWithMethod:params:callback:);
    BOOL added = class_addMethod(
        [KRRichTextView class],
        selector,
        (IMP)KRImplementedViewMethod,
        "v@:@@@?"
    );
    KRAssert(added, @"fixture must install the optional inner method");

    KRGradientRichTextView *gradientView =
        [[KRGradientRichTextView alloc] initWithFrame:CGRectMake(0, 0, 100, 20)];
    __block NSInteger callbackCount = 0;
    __block NSDictionary *callbackResult = nil;

    [gradientView hrv_callWithMethod:@"supported"
                              params:@"payload"
                            callback:^(id result) {
                                callbackCount += 1;
                                callbackResult = result;
                            }];

    KRAssert(forwardedCallCount == 1, @"implemented inner method must be forwarded exactly once");
    KRAssert([forwardedMethod isEqualToString:@"supported"], @"method identity must be preserved");
    KRAssert([forwardedParams isEqualToString:@"payload"], @"params identity must be preserved");
    KRAssert(callbackCount == 1, @"implemented method callback must be delivered exactly once");
    KRAssert([callbackResult[@"forwarded"] boolValue], @"callback payload must be preserved");
}

int main(void) {
    @autoreleasepool {
        testMissingOptionalInnerMethodIsIgnored();
        testImplementedInnerMethodStillForwardsExactlyOnce();
        NSLog(@"KRGradientRichTextViewMethodDispatchTest passed");
    }
    return 0;
}
