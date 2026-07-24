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

package com.tencent.kuikly.core.render.android.expand.module

import com.tencent.kuikly.core.render.android.css.ktx.toJSONObjectSafely
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import java.io.File
import java.util.concurrent.Executors

/**
 * 文件读写 Module，提供 App Caches 目录的文件写入能力。
 * 主要用于 RecompositionProfiler 导出 JSON 报告供 AI 分析。
 *
 * 写入目录：context.cacheDir/KuiklyProfiler/
 * 使用 cacheDir（而非 filesDir）的原因：
 *   - 不纳入 备份
 *   - 多页面共享同一目录，同名文件后写覆盖前写
 *   - adb shell run-as <pkg> cat cache/KuiklyProfiler/<filename> 可直接读取
 */
class KRFileModule : KuiklyRenderBaseModule() {

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        return when (method) {
            METHOD_WRITE_FILE -> writeFile(params, callback)
            METHOD_APPEND_FILE -> appendFile(params, callback)
            METHOD_GET_FILES_DIR -> getFilesDir(callback)
            else -> super.call(method, params, callback)
        }
    }

    private fun profilerDir(): File? {
        val cacheDir = context?.cacheDir ?: return null
        val dir = File(cacheDir, "KuiklyProfiler")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getFilesDir(callback: KuiklyRenderCallback?) {
        val dir = profilerDir()
        if (dir != null) {
            callback?.invoke(mapOf("path" to dir.absolutePath))
        } else {
            callback?.invoke(mapOf("error" to "context unavailable"))
        }
    }

    private fun appendFile(params: String?, callback: KuiklyRenderCallback?) {
        val json = params.toJSONObjectSafely()
        val filename = json.optString(PARAM_FILENAME)
        val content = json.optString(PARAM_CONTENT)
        val operationId = json.optString(PARAM_OPERATION_ID).orEmpty()

        if (filename.isNullOrEmpty() || content.isNullOrEmpty()) {
            callback?.invoke(mapOf("error" to "missing filename or content"))
            return
        }

        val dir = profilerDir()
        if (dir == null) {
            callback?.invoke(mapOf("error" to "context unavailable"))
            return
        }

        FILE_EXECUTOR.execute {
            val file = File(dir, filename)
            if (operationId.isNotEmpty() && COMPLETED_OPERATION_IDS.contains(operationId)) {
                callback?.invoke(mapOf("path" to file.absolutePath))
                return@execute
            }
            try {
                // 追加写，末尾加换行，适合 JSONL 格式
                file.appendText(content + "\n", Charsets.UTF_8)
                if (operationId.isNotEmpty()) {
                    COMPLETED_OPERATION_IDS.add(operationId)
                }
                callback?.invoke(mapOf("path" to file.absolutePath))
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to (e.message ?: "unknown error")))
            }
        }
    }

    private fun writeFile(params: String?, callback: KuiklyRenderCallback?) {
        val json = params.toJSONObjectSafely()
        val filename = json.optString(PARAM_FILENAME)
        val content = json.optString(PARAM_CONTENT)
        val operationId = json.optString(PARAM_OPERATION_ID).orEmpty()

        // Empty content is a valid overwrite operation: profiler start/reset uses it to truncate
        // the previous session's report before any new frame is recorded.
        if (filename.isNullOrEmpty()) {
            callback?.invoke(mapOf("error" to "missing filename"))
            return
        }

        val dir = profilerDir()
        if (dir == null) {
            callback?.invoke(mapOf("error" to "context unavailable"))
            return
        }

        // A process-wide FIFO keeps writes ordered even when a profiler operation is retried
        // through another Pager after the first Pager lost its native callback.
        FILE_EXECUTOR.execute {
            val file = File(dir, filename)
            if (operationId.isNotEmpty() && COMPLETED_OPERATION_IDS.contains(operationId)) {
                callback?.invoke(mapOf("path" to file.absolutePath))
                return@execute
            }
            try {
                file.writeText(content, Charsets.UTF_8)
                if (operationId.isNotEmpty()) {
                    COMPLETED_OPERATION_IDS.add(operationId)
                }
                callback?.invoke(mapOf("path" to file.absolutePath))
            } catch (e: Exception) {
                callback?.invoke(mapOf("error" to (e.message ?: "unknown error")))
            }
        }
    }

    companion object {
        const val MODULE_NAME = "KRFileModule"
        private const val METHOD_WRITE_FILE = "writeFile"
        private const val METHOD_APPEND_FILE = "appendFile"
        private const val METHOD_GET_FILES_DIR = "getFilesDir"
        private const val PARAM_FILENAME = "filename"
        private const val PARAM_CONTENT = "content"
        private const val PARAM_OPERATION_ID = "operationId"

        /** All accesses to [COMPLETED_OPERATION_IDS] run on this process-wide FIFO executor. */
        private val FILE_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "KuiklyProfilerFile").apply { isDaemon = true }
        }
        private val COMPLETED_OPERATION_IDS = HashSet<String>()
    }
}
