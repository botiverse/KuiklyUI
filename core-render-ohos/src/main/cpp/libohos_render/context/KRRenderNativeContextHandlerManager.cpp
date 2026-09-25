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

#include "libohos_render/context/KRRenderNativeContextHandlerManager.h"

#include <cstdlib>
#include "libohos_render/context/DefaultRenderNativeContextHandler.h"
#include "libohos_render/manager/KRRenderManager.h"
#include "libohos_render/scheduler/KRContextScheduler.h"
#include "libohos_render/utils/KRRenderLoger.h"

extern CallKotlin callKotlin_;

void KRRenderNativeContextHandlerManager::SetContextHandlerCreator(const KRRenderContextHandlerCreator &creator) {
    creator_ = creator;
}

std::shared_ptr<IKRRenderNativeContextHandler> KRRenderNativeContextHandlerManager::CreateContextHandler(
    const std::shared_ptr<KRRenderContextParams> &context_params) {
    KRRenderContextHandlerCreator creator_;
    if (context_params->ExecuteMode()) {
        std::unordered_map<int, KRRenderContextHandlerCreator> context_creator_register =
            GetContextHandlerCreatorRegister();
        int param_mode = context_params->ExecuteMode()->GetMode();
        if (context_creator_register.find(param_mode) != context_creator_register.end()) {
            creator_ = context_creator_register[param_mode];  //  优先使用自定义注册的创建器
        } else if (auto native_mode = dynamic_cast<KRRenderNativeMode *>(context_params->ExecuteMode().get())) {
            auto context_handler_register = [](const std::shared_ptr<KRRenderContextParams> &context_params)
                -> std::shared_ptr<IKRRenderNativeContextHandler> {
                return std::make_shared<DefaultRenderNativeContextHandler>();
            };
            creator_ = context_handler_register;
        }
    }
    if (creator_) {
        return creator_(context_params);
    } else {
        throw std::runtime_error("Custom execute mode, contextHandler must be registered");
    }
}

void KRRenderNativeContextHandlerManager::RegisterContextHandler(
    const std::string &instanceId, const std::shared_ptr<IKRRenderNativeContextHandler> &contextHandler) {
    context_handler_map_.Set(instanceId, contextHandler);
}

void KRRenderNativeContextHandlerManager::UnregisterContextHandler(const std::string &instanceId) {
    context_handler_map_.Erase(instanceId);
}

void KRRenderNativeContextHandlerManager::ScheduleDeallocRenderValues(
    std::shared_ptr<KRRenderValue> will_dealloc_render_value) {
    {
        KRScopedSpinLock lock(&pending_dealloc_render_values_lock_);
        pending_dealloc_render_values_.push_back(std::move(will_dealloc_render_value));
    }
    bool expected = false;
    if (scheduling_dealloc_render_values_.compare_exchange_strong(expected, true)) {
    KRContextScheduler::ScheduleTask(16, [this]() {
            // `this` is safe to be captured in the closure, because it is an singleton.
            decltype(pending_dealloc_render_values_) values;
            {
                KRScopedSpinLock lock(&pending_dealloc_render_values_lock_);
                values.swap(pending_dealloc_render_values_);
            }
            // 必须先把标志位重置为 false 再让 values 离开作用域析构，
            // 否则若析构过程中又触发 ScheduleDeallocRenderValues，将无法再投递新一轮调度任务。
            KRRenderNativeContextHandlerManager::GetInstance()
                .scheduling_dealloc_render_values_.store(false);
            // values 在这里析构 -> shared_ptr<KRRenderValue> release，全部发生在 context 线程
        });
    }
}

static inline std::shared_ptr<KRRenderValue> MakeFromCValue(const KRRenderCValue &cValue) {
    if (cValue.type == KRRenderCValue::NULL_VALUE) {
        return KRRenderValue::MakeNull();  // 复用静态单例，避免堆分配
    }
    return KRRenderValue::Make(cValue);
}

KRRenderCValue KRRenderNativeContextHandlerManager::DispatchCallNative(
    const std::string &instanceId, int methodId, const KRRenderCValue &arg0, const KRRenderCValue &arg1,
    const KRRenderCValue &arg2, const KRRenderCValue &arg3, const KRRenderCValue &arg4, const KRRenderCValue &arg5) {
    // arg0 is a reserved slot. Keep the task #26 off-context marshal contract,
    // but use the upstream null singleton and NULL fast path for every value.
    auto cv0 = KRRenderValue::MakeNull();
    auto cv1 = MakeFromCValue(arg1);
    auto cv2 = MakeFromCValue(arg2);
    auto cv3 = MakeFromCValue(arg3);
    auto cv4 = MakeFromCValue(arg4);
    auto cv5 = MakeFromCValue(arg5);
    auto method = static_cast<KuiklyRenderNativeMethod>(methodId);
    if (!KRContextScheduler::IsCurrentOnContextThread()) {
        if (KRNativeMethodRequiresContextThread(method, cv5)) {
            KR_LOG_ERROR << "Synchronous Kuikly native method " << methodId
                         << " called off the context thread; aborting";
            std::abort();
        }
        KRContextScheduler::ScheduleTask(0, [this, instanceId, method, cv0, cv1, cv2, cv3, cv4, cv5]() mutable {
            DispatchPreparedCallNative(instanceId, method, cv0, cv1, cv2, cv3, cv4, cv5);
        });
        return KRRenderCValue{};
    }

    auto return_value = DispatchPreparedCallNative(instanceId, method, cv0, cv1, cv2, cv3, cv4, cv5);
    if (return_value == nullptr || return_value->isNull()) {
        // Value-initialize the aggregate so union value and size never leak
        // uninitialized stack bytes across the napi C ABI.
        return KRRenderCValue{};
    }
    ScheduleDeallocRenderValues(return_value);
    return return_value->toCValue();
}

std::shared_ptr<KRRenderValue> KRRenderNativeContextHandlerManager::DispatchPreparedCallNative(
    const std::string &instanceId, const KuiklyRenderNativeMethod &method, std::shared_ptr<KRRenderValue> &arg0,
    std::shared_ptr<KRRenderValue> &arg1, std::shared_ptr<KRRenderValue> &arg2,
    std::shared_ptr<KRRenderValue> &arg3, std::shared_ptr<KRRenderValue> &arg4,
    std::shared_ptr<KRRenderValue> &arg5) {
    auto handler = context_handler_map_.Get(instanceId);
    if (!handler || nullptr == KRRenderManager::GetInstance().GetRenderView(instanceId)) {
        return nullptr;
    }
    return handler->OnCallNative(method, arg0, arg1, arg2, arg3, arg4, arg5);
}
