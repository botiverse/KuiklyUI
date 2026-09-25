/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2026 Tencent. All rights reserved.
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

package com.tencent.kuikly.compose.profiler

/**
 * Terminal result of persisting a profiler report and every file operation queued before it.
 *
 * A callback receiving [Success] is the acknowledgement that the native report write completed
 * for [sessionId]. Enqueueing a write is deliberately not considered success. [Failure] includes
 * native terminal errors, exhausted retryable failures, incomplete earlier frame operations, and
 * a session being superseded before its report commits.
 */
sealed class RecompositionProfilerFileOutputResult {
    abstract val sessionId: String

    data class Success(
        override val sessionId: String
    ) : RecompositionProfilerFileOutputResult()

    data class Failure(
        override val sessionId: String,
        val reason: String
    ) : RecompositionProfilerFileOutputResult()
}
