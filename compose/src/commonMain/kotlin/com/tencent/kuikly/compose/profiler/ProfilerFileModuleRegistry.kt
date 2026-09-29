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

import com.tencent.kuikly.core.module.FileModule

/**
 * Tracks the live page-owned [FileModule] instances available to the process-wide profiler.
 *
 * FileModule is created per Pager, while [RecompositionProfiler] is process-wide. A profiler
 * session therefore must not permanently bind its output to whichever Pager happened to be the
 * first lifecycle listener during start. The most recently registered live module is preferred;
 * removing it falls back to the previous live module.
 *
 * The caller owns synchronization. [RecompositionProfiler] invokes every method under its lock.
 */
internal class ProfilerFileModuleRegistry {
    private val modules = mutableListOf<FileModule>()

    fun register(module: FileModule): FileModule {
        modules.removeAll { it === module }
        modules.add(module)
        return module
    }

    fun unregister(module: FileModule): FileModule? {
        modules.removeAll { it === module }
        return modules.lastOrNull()
    }

    fun current(): FileModule? = modules.lastOrNull()

    internal fun size(): Int = modules.size
}
