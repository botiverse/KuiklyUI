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

package com.tencent.kuikly.core.views

import com.tencent.kuikly.core.base.Color

/**
 * Semantic-agnostic inline box decoration carried by an existing [TextSpan].
 *
 * Values use Kuikly logical pixels. Renderers must include the horizontal box
 * geometry in text measurement, paint one fragment per final visual line, and
 * keep hit-testing/copy mapped to the original span text.
 */
data class InlineBoxSpanStyle(
    val backgroundColor: Color? = null,
    val borderColor: Color? = null,
    val borderWidth: Float = 0f,
    val paddingStart: Float = 0f,
    val paddingEnd: Float = 0f,
    val paddingTop: Float = 0f,
    val paddingBottom: Float = 0f,
    val marginStart: Float = 0f,
    val marginEnd: Float = 0f,
    val cornerRadius: Float = 0f,
)
