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

#import <Foundation/Foundation.h>
#import <objc/runtime.h>

NS_ASSUME_NONNULL_BEGIN

typedef struct {
    __unsafe_unretained Class _Nullable dynamicClass;
    __unsafe_unretained Class _Nullable postPredicateClass;
    __unsafe_unretained Class _Nullable metaclass;
    __unsafe_unretained Class _Nullable declaringClass;
    BOOL predicateEvaluated;
    BOOL predicateResult;
    BOOL methodFound;
    BOOL implementationFound;
    BOOL forwardingImplementation;
    BOOL abiCompatible;
    BOOL classStable;
    BOOL methodStable;
    BOOL implementationStable;
    BOOL invoked;
    Method _Nullable method;
    IMP _Nullable implementation;
} KRRenderViewMethodDispatchReceipt;

/**
 * Invokes a concrete Objective-C instance method with three object arguments.
 *
 * Capability is established from the receiver's dynamic class method table,
 * not from the overridable `respondsToSelector:` predicate. Forwarding-only or
 * ABI-incompatible methods fail closed. A concrete IMP is invoked directly so
 * a later message-dispatch capability disagreement cannot re-open the call.
 */
FOUNDATION_EXPORT BOOL KRInvokeRenderViewMethodIfImplemented(
    id _Nullable receiver,
    SEL selector,
    id _Nullable argument0,
    id _Nullable argument1,
    id _Nullable argument2,
    KRRenderViewMethodDispatchReceipt *_Nullable receipt
);

NS_ASSUME_NONNULL_END
