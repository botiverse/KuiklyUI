/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI;
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://github.com/Tencent-TDS/KuiklyUI/blob/main/LICENSE
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#import "KRRenderViewMethodInvoker.h"
#import <ptrauth.h>

@interface KRRenderViewMethodForwardingProbe : NSObject
@end
@implementation KRRenderViewMethodForwardingProbe
@end

static const char *KRSkipObjCTypeQualifiers(const char *type) {
    if (!type) {
        return "";
    }
    while (*type && strchr("rnNoORV", *type)) {
        type++;
    }
    return type;
}

static BOOL KRMethodHasExpectedViewCallABI(Method method) {
    if (!method || method_getNumberOfArguments(method) != 5) {
        return NO;
    }
    char *returnType = method_copyReturnType(method);
    BOOL compatible = KRSkipObjCTypeQualifiers(returnType)[0] == 'v';
    free(returnType);
    for (unsigned int index = 2; compatible && index < 5; index++) {
        char *argumentType = method_copyArgumentType(method, index);
        compatible = KRSkipObjCTypeQualifiers(argumentType)[0] == '@';
        free(argumentType);
    }
    return compatible;
}

static BOOL KRImplementationsEqual(IMP lhs, IMP rhs) {
    if (!lhs || !rhs) {
        return NO;
    }
#if __has_feature(ptrauth_calls)
    // Runtime APIs may return the same IMP with different arm64e signatures.
    return ptrauth_strip(lhs, ptrauth_key_function_pointer) ==
        ptrauth_strip(rhs, ptrauth_key_function_pointer);
#else
    return lhs == rhs;
#endif
}

static Class KRClassDeclaringInstanceMethod(Class dynamicClass, SEL selector) {
    for (Class candidate = dynamicClass; candidate; candidate = class_getSuperclass(candidate)) {
        unsigned int count = 0;
        Method *methods = class_copyMethodList(candidate, &count);
        for (unsigned int index = 0; index < count; index++) {
            if (sel_isEqual(method_getName(methods[index]), selector)) {
                free(methods);
                return candidate;
            }
        }
        free(methods);
    }
    return Nil;
}

BOOL KRInvokeRenderViewMethodIfImplemented(
    id receiver,
    SEL selector,
    id argument0,
    id argument1,
    id argument2,
    KRRenderViewMethodDispatchReceipt *receipt
) {
    KRRenderViewMethodDispatchReceipt result = {0};
    result.dynamicClass = receiver ? object_getClass(receiver) : Nil;
    result.metaclass = result.dynamicClass ? object_getClass(result.dynamicClass) : Nil;
    if (result.dynamicClass) {
        result.method = class_getInstanceMethod(result.dynamicClass, selector);
        result.methodFound = result.method != NULL;
        result.declaringClass = KRClassDeclaringInstanceMethod(result.dynamicClass, selector);
        result.implementation = result.method ? method_getImplementation(result.method) : NULL;
        result.implementationFound = result.implementation != NULL;
        IMP forwardingImplementation = class_getMethodImplementation(
            [KRRenderViewMethodForwardingProbe class],
            sel_registerName("__kr_render_view_method_invoker_missing__")
        );
        result.forwardingImplementation = result.implementationFound &&
            KRImplementationsEqual(result.implementation, forwardingImplementation);
        result.abiCompatible = KRMethodHasExpectedViewCallABI(result.method);
    }
    // Evaluate the overridable predicate only after capability is captured so
    // it cannot grant admission by mutating isa or the method table.
    if (receiver) {
        result.predicateEvaluated = YES;
        @try {
            result.predicateResult = [receiver respondsToSelector:selector];
        } @catch (__unused NSException *exception) {
            result.predicateResult = NO;
        }
    }
    result.postPredicateClass = receiver ? object_getClass(receiver) : Nil;
    result.classStable = result.dynamicClass &&
        result.postPredicateClass == result.dynamicClass;
    if (result.classStable) {
        Method postPredicateMethod = class_getInstanceMethod(result.postPredicateClass, selector);
        IMP postPredicateImplementation = postPredicateMethod ?
            method_getImplementation(postPredicateMethod) : NULL;
        result.methodStable = postPredicateMethod == result.method;
        result.implementationStable =
            (!postPredicateImplementation && !result.implementation) ||
            KRImplementationsEqual(postPredicateImplementation, result.implementation);
    }
    if (result.methodFound && result.implementationFound &&
        !result.forwardingImplementation && result.abiCompatible &&
        result.classStable && result.methodStable && result.implementationStable) {
        typedef void (*KRRenderViewMethodIMP)(id, SEL, id, id, id);
        ((KRRenderViewMethodIMP)result.implementation)(receiver, selector, argument0, argument1, argument2);
        result.invoked = YES;
    }
    if (receipt) {
        *receipt = result;
    }
    return result.invoked;
}
