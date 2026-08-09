#import <Foundation/Foundation.h>
#import <objc/runtime.h>
#import "KRRenderViewMethodInvoker.h"

static SEL KRViewCallSelector(void) {
    return NSSelectorFromString(@"hrv_callWithMethod:params:callback:");
}

static void KRAssert(BOOL condition, NSString *message) {
    if (!condition) {
        NSLog(@"FAIL: %@", message);
        exit(1);
    }
}

@interface KRConcreteView : NSObject
@property(nonatomic, assign) NSInteger invocationCount;
@property(nonatomic, copy) NSString *lastMethod;
@end

@implementation KRConcreteView
- (void)hrv_callWithMethod:(NSString *)method params:(NSString *)params callback:(id)callback {
    self.invocationCount += 1;
    self.lastMethod = method;
    if (callback) {
        ((void (^)(NSString *))callback)(params);
    }
}
@end

@interface KRInheritedConcreteView : KRConcreteView
@end
@implementation KRInheritedConcreteView
@end

@interface KRFailOpenRichTextView : NSObject
@end

@implementation KRFailOpenRichTextView
- (BOOL)respondsToSelector:(SEL)selector {
    if (sel_isEqual(selector, KRViewCallSelector())) {
        return YES;
    }
    return [super respondsToSelector:selector];
}
@end

@interface KRForwardingOnlyView : NSObject
@property(nonatomic, assign) NSInteger forwardedCount;
@end

@implementation KRForwardingOnlyView
- (BOOL)respondsToSelector:(SEL)selector {
    if (sel_isEqual(selector, KRViewCallSelector())) {
        return YES;
    }
    return [super respondsToSelector:selector];
}
- (NSMethodSignature *)methodSignatureForSelector:(SEL)selector {
    if (sel_isEqual(selector, KRViewCallSelector())) {
        return [NSMethodSignature signatureWithObjCTypes:"v@:@@@?"];
    }
    return [super methodSignatureForSelector:selector];
}
- (void)forwardInvocation:(NSInvocation *)invocation {
    self.forwardedCount += 1;
}
@end

@interface KRWrongABIView : NSObject
@end

@implementation KRWrongABIView
- (id)hrv_callWithMethod:(NSString *)method params:(NSString *)params callback:(id)callback {
    return method;
}
@end

@interface KRDynamicMethodView : NSObject
@property(nonatomic, assign) NSInteger invocationCount;
@end

static void KRDynamicViewCall(id receiver, SEL selector, id method, id params, id callback) {
    (void)selector;
    (void)method;
    (void)params;
    (void)callback;
    ((KRDynamicMethodView *)receiver).invocationCount += 1;
}

@implementation KRDynamicMethodView
+ (BOOL)resolveInstanceMethod:(SEL)selector {
    if (sel_isEqual(selector, KRViewCallSelector())) {
        return class_addMethod(self, selector, (IMP)KRDynamicViewCall, "v@:@@@");
    }
    return [super resolveInstanceMethod:selector];
}
@end

@interface KRThrowingPredicateView : KRConcreteView
@end

@implementation KRThrowingPredicateView
- (BOOL)respondsToSelector:(SEL)selector {
    if (sel_isEqual(selector, KRViewCallSelector())) {
        [NSException raise:NSInternalInconsistencyException format:@"predicate unavailable"];
    }
    return [super respondsToSelector:selector];
}
@end

@interface KRForwardingIMPView : NSObject
@end
@implementation KRForwardingIMPView
@end

@interface KRPostMutationView : KRConcreteView
@end
@implementation KRPostMutationView
@end

@interface KRClassMutatingPredicateView : KRConcreteView
@end
@implementation KRClassMutatingPredicateView
- (BOOL)respondsToSelector:(SEL)selector {
    if (sel_isEqual(selector, KRViewCallSelector())) {
        object_setClass(self, [KRPostMutationView class]);
        return YES;
    }
    return [super respondsToSelector:selector];
}
@end

static void KRReplacementViewCall(id receiver, SEL selector, id method, id params, id callback) {
    (void)selector;
    (void)method;
    (void)params;
    (void)callback;
    ((KRConcreteView *)receiver).invocationCount += 100;
}

@interface KRMethodMutatingPredicateView : KRConcreteView
@end
@implementation KRMethodMutatingPredicateView
- (BOOL)respondsToSelector:(SEL)selector {
    if (sel_isEqual(selector, KRViewCallSelector())) {
        class_replaceMethod(object_getClass(self), selector, (IMP)KRReplacementViewCall, "v@:@@@");
        return YES;
    }
    return [super respondsToSelector:selector];
}
@end

static void KRTestConcreteMethod(void) {
    KRConcreteView *view = [KRConcreteView new];
    __block NSString *callbackValue = nil;
    KRRenderViewMethodDispatchReceipt receipt = {0};
    BOOL invoked = KRInvokeRenderViewMethodIfImplemented(
        view,
        KRViewCallSelector(),
        @"reload",
        @"payload",
        ^(NSString *value) { callbackValue = value; },
        &receipt
    );
    KRAssert(invoked, @"concrete implementation must invoke");
    KRAssert(view.invocationCount == 1, @"concrete implementation must run exactly once");
    KRAssert([view.lastMethod isEqualToString:@"reload"], @"method argument must be preserved");
    KRAssert([callbackValue isEqualToString:@"payload"], @"callback argument must be preserved");
    KRAssert(receipt.methodFound && receipt.implementationFound && receipt.abiCompatible,
             @"concrete receipt must prove method, IMP, and ABI");
    KRAssert(receipt.declaringClass == [KRConcreteView class], @"declaring class must be recorded");
}

static void KRTestInheritedConcreteMethod(void) {
    KRInheritedConcreteView *view = [KRInheritedConcreteView new];
    KRRenderViewMethodDispatchReceipt receipt = {0};
    BOOL invoked = KRInvokeRenderViewMethodIfImplemented(
        view, KRViewCallSelector(), @"inherited", nil, nil, &receipt
    );
    KRAssert(invoked && view.invocationCount == 1, @"inherited concrete method must invoke");
    KRAssert(receipt.dynamicClass == [KRInheritedConcreteView class], @"dynamic class must be recorded");
    KRAssert(receipt.declaringClass == [KRConcreteView class], @"inherited owner must be recorded");
}

static void KRTestFailOpenPredicate(void) {
    KRFailOpenRichTextView *view = [KRFailOpenRichTextView new];
    KRRenderViewMethodDispatchReceipt receipt = {0};
    BOOL invoked = KRInvokeRenderViewMethodIfImplemented(
        view, KRViewCallSelector(), @"unsupported", nil, nil, &receipt
    );
    KRAssert(receipt.predicateResult, @"fixture must reproduce fail-open predicate");
    KRAssert(!receipt.methodFound && !receipt.implementationFound,
             @"fail-open fixture must have no concrete method or IMP");
    KRAssert(!invoked && !receipt.invoked, @"fail-open predicate must be rejected without crashing");
}

static void KRTestForwardingOnlyMethod(void) {
    KRForwardingOnlyView *view = [KRForwardingOnlyView new];
    KRRenderViewMethodDispatchReceipt receipt = {0};
    BOOL invoked = KRInvokeRenderViewMethodIfImplemented(
        view, KRViewCallSelector(), @"forwarded", nil, nil, &receipt
    );
    KRAssert(receipt.predicateResult, @"forwarding fixture must claim capability");
    KRAssert(!receipt.methodFound && !invoked, @"forwarding-only capability must fail closed");
    KRAssert(view.forwardedCount == 0, @"fail-closed path must not enter forwarding");
}

static void KRTestWrongABI(void) {
    KRWrongABIView *view = [KRWrongABIView new];
    KRRenderViewMethodDispatchReceipt receipt = {0};
    BOOL invoked = KRInvokeRenderViewMethodIfImplemented(
        view, KRViewCallSelector(), @"wrongABI", nil, nil, &receipt
    );
    KRAssert(receipt.methodFound && receipt.implementationFound, @"wrong ABI method must be found");
    KRAssert(!receipt.abiCompatible && !invoked, @"wrong ABI method must fail closed");
}

static void KRTestDynamicMethodResolution(void) {
    KRDynamicMethodView *view = [KRDynamicMethodView new];
    KRRenderViewMethodDispatchReceipt receipt = {0};
    BOOL invoked = KRInvokeRenderViewMethodIfImplemented(
        view, KRViewCallSelector(), @"dynamic", nil, nil, &receipt
    );
    KRAssert(invoked && view.invocationCount == 1,
             @"resolved concrete Method/IMP must invoke exactly once");
    KRAssert(receipt.methodFound && receipt.implementationFound && receipt.abiCompatible,
             @"dynamic resolution receipt must prove Method/IMP/ABI");
    KRAssert(receipt.declaringClass == [KRDynamicMethodView class],
             @"resolved method owner must be recorded");
}

static void KRTestThrowingPredicateIsDiagnosticOnly(void) {
    KRThrowingPredicateView *view = [KRThrowingPredicateView new];
    KRRenderViewMethodDispatchReceipt receipt = {0};
    BOOL invoked = KRInvokeRenderViewMethodIfImplemented(
        view, KRViewCallSelector(), @"predicateThrows", nil, nil, &receipt
    );
    KRAssert(receipt.predicateEvaluated && !receipt.predicateResult,
             @"throwing predicate must be contained and recorded false");
    KRAssert(invoked && view.invocationCount == 1,
             @"predicate failure must not reject a concrete compatible Method/IMP");
}

static void KRTestExplicitForwardingIMP(void) {
    SEL missingSelector = sel_registerName("__kr_test_forwarding_imp_missing__");
    IMP forwardingImplementation = class_getMethodImplementation([KRForwardingIMPView class], missingSelector);
    BOOL added = class_addMethod(
        [KRForwardingIMPView class], KRViewCallSelector(), forwardingImplementation, "v@:@@@"
    );
    KRAssert(added, @"fixture must install forwarding IMP as a concrete method-table entry");

    KRForwardingIMPView *view = [KRForwardingIMPView new];
    KRRenderViewMethodDispatchReceipt receipt = {0};
    BOOL invoked = KRInvokeRenderViewMethodIfImplemented(
        view, KRViewCallSelector(), @"forwardingIMP", nil, nil, &receipt
    );
    KRAssert(receipt.methodFound && receipt.implementationFound && receipt.forwardingImplementation,
             @"forwarding IMP fixture must be identified explicitly");
    KRAssert(!invoked, @"forwarding IMP method-table entry must fail closed");
}

static void KRTestPredicateClassMutation(void) {
    KRClassMutatingPredicateView *view = [KRClassMutatingPredicateView new];
    KRRenderViewMethodDispatchReceipt receipt = {0};
    BOOL invoked = KRInvokeRenderViewMethodIfImplemented(
        view, KRViewCallSelector(), @"classMutation", nil, nil, &receipt
    );
    KRAssert(receipt.dynamicClass == [KRClassMutatingPredicateView class] &&
                 receipt.postPredicateClass == [KRPostMutationView class],
             @"predicate class mutation must be recorded");
    KRAssert(!receipt.classStable && !invoked && view.invocationCount == 0,
             @"predicate class mutation must fail closed");
}

static void KRTestPredicateMethodMutation(void) {
    KRMethodMutatingPredicateView *view = [KRMethodMutatingPredicateView new];
    KRRenderViewMethodDispatchReceipt receipt = {0};
    BOOL invoked = KRInvokeRenderViewMethodIfImplemented(
        view, KRViewCallSelector(), @"methodMutation", nil, nil, &receipt
    );
    KRAssert(receipt.classStable &&
                 (!receipt.methodStable || !receipt.implementationStable),
             @"predicate method-table mutation must be detected");
    KRAssert(!invoked && view.invocationCount == 0,
             @"predicate method-table mutation must fail closed");
}

int main(void) {
    @autoreleasepool {
        KRTestConcreteMethod();
        KRTestInheritedConcreteMethod();
        KRTestFailOpenPredicate();
        KRTestForwardingOnlyMethod();
        KRTestWrongABI();
        KRTestDynamicMethodResolution();
        KRTestThrowingPredicateIsDiagnosticOnly();
        KRTestExplicitForwardingIMP();
        KRTestPredicateClassMutation();
        KRTestPredicateMethodMutation();
        NSLog(@"KRRenderViewMethodInvokerTest PASS");
    }
    return 0;
}
