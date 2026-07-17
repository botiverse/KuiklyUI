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

#ifndef CORE_RENDER_OHOS_KRSCROLLERCONTENTINSET_H
#define CORE_RENDER_OHOS_KRSCROLLERCONTENTINSET_H

#include "libohos_render/foundation/KRCommon.h"
#include "libohos_render/utils/KRStringUtil.h"

class KRScrollerContentInset {
 public:
    explicit KRScrollerContentInset(const KRAnyValue &value) {
        auto content_inset_splits = kuikly::util::SplitString(value->toString(), ' ');
        top = content_inset_splits[0]->toFloat();
        start = content_inset_splits[1]->toFloat();
        bottom = content_inset_splits[2]->toFloat();
        end = content_inset_splits[3]->toFloat();
        if (content_inset_splits.size() >= 5) {
            animate = content_inset_splits[4]->toBool();
        }
        if (content_inset_splits.size() >= 7) {
            generation = content_inset_splits[5]->toLong();
            requires_native_idle = content_inset_splits[6]->toBool();
        }
        if (content_inset_splits.size() >= 8) {
            operation_generation = content_inset_splits[7]->toLong();
        }
        if (content_inset_splits.size() >= 10) {
            expected_content_size = content_inset_splits[8]->toFloat();
            expected_viewport_size = content_inset_splits[9]->toFloat();
        }
        if (content_inset_splits.size() >= 20) {
            binding_generation = content_inset_splits[10]->toLong();
            capability_kind = content_inset_splits[11]->toInt();
            capability_lease_id = content_inset_splits[12]->toLong();
            semantic_operation_id = content_inset_splits[13]->toLong();
            attempt_generation = content_inset_splits[14]->toLong();
            native_interaction_epoch = content_inset_splits[15]->toLong();
            layout_revision = content_inset_splits[16]->toLong();
            anchor_revision = content_inset_splits[17]->toLong();
            range_revision = content_inset_splits[18]->toLong();
            inset_revision = content_inset_splits[19]->toLong();
        }
    }

    bool animate = false;
    float start = 0.0;
    float top = 0.0;
    float end = 0.0;
    float bottom = 0.0;
    int64_t generation = -1;
    bool requires_native_idle = false;
    int64_t operation_generation = 0;
    float expected_content_size = -1.0f;
    float expected_viewport_size = -1.0f;
    int64_t binding_generation = 0;
    int capability_kind = -1;
    int64_t capability_lease_id = 0;
    int64_t semantic_operation_id = 0;
    int64_t attempt_generation = 0;
    int64_t native_interaction_epoch = 0;
    int64_t layout_revision = 0;
    int64_t anchor_revision = 0;
    int64_t range_revision = 0;
    int64_t inset_revision = 0;
};
#endif  // CORE_RENDER_OHOS_KRSCROLLERCONTENTINSET_H
