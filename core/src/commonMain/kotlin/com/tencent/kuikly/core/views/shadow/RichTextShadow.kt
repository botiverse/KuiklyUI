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

package com.tencent.kuikly.core.views.shadow

class RichTextShadow(pagerId: String, viewRef: Int, viewName: String) : TextShadow(
    pagerId, viewRef,
    viewName
) {
    var lastValues : String? = null
    fun setValuesProp(values: String) {
        println(
            "SlockRichTextTrace phase=common.shadow.set_values shadow=${hashCode()} " +
                "length=${values.length} hash=${values.hashCode().toString(16)} previousHash=${lastValues?.hashCode()?.toString(16)}"
        )
        setProp("values", values)
        lastValues = values
    }
}
