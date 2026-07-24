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

package com.tencent.kuikly.compose.profiler.output

import com.tencent.kuikly.compose.coroutines.internal.KuiklyContextScheduler
import com.tencent.kuikly.compose.profiler.ComposableRecomposedEvent
import com.tencent.kuikly.compose.profiler.RecompositionEvent
import com.tencent.kuikly.compose.profiler.RecompositionFrameEndEvent
import com.tencent.kuikly.compose.profiler.RecompositionFrameStartEvent
import com.tencent.kuikly.compose.profiler.RecompositionOutputStrategy
import com.tencent.kuikly.compose.profiler.RecompositionReport
import com.tencent.kuikly.compose.profiler.RecompositionSessionOutputStrategy
import com.tencent.kuikly.compose.profiler.ScrollContextEvent
import com.tencent.kuikly.compose.profiler.TouchContextEvent
import com.tencent.kuikly.compose.ui.createSynchronizedObject
import com.tencent.kuikly.compose.ui.synchronized
import com.tencent.kuikly.core.datetime.DateTime
import com.tencent.kuikly.core.module.FileModule
import kotlin.concurrent.Volatile

internal enum class ProfilerFileOperationKind {
    WRITE,
    APPEND
}

internal data class ProfilerFileOperation(
    val kind: ProfilerFileOperationKind,
    val filename: String,
    val content: String
)

internal sealed class ProfilerFileIoResult {
    internal object Success : ProfilerFileIoResult()
    internal data class RetryableFailure(val reason: String) : ProfilerFileIoResult()
    internal data class TerminalFailure(val reason: String) : ProfilerFileIoResult()
}

internal fun interface ProfilerFileIoDispatcher {
    fun dispatch(
        module: FileModule,
        operation: ProfilerFileOperation,
        completion: (ProfilerFileIoResult) -> Unit
    )
}

private object NativeProfilerFileIoDispatcher : ProfilerFileIoDispatcher {
    override fun dispatch(
        module: FileModule,
        operation: ProfilerFileOperation,
        completion: (ProfilerFileIoResult) -> Unit
    ) {
        val invokeNative = {
            val callback: (com.tencent.kuikly.core.nvi.serialization.json.JSONObject?) -> Unit = { result ->
                val error = result?.optString("error").orEmpty()
                val path = result?.optString("path").orEmpty()
                completion(
                    when {
                        error == "context unavailable" -> ProfilerFileIoResult.RetryableFailure(error)
                        error.isNotEmpty() -> ProfilerFileIoResult.TerminalFailure(error)
                        path.isNotEmpty() -> ProfilerFileIoResult.Success
                        else -> ProfilerFileIoResult.TerminalFailure("native completion missing path")
                    }
                )
            }
            try {
                when (operation.kind) {
                    ProfilerFileOperationKind.WRITE ->
                        module.writeFile(operation.filename, operation.content, callback)
                    ProfilerFileOperationKind.APPEND ->
                        module.appendFile(operation.filename, operation.content, callback)
                }
            } catch (throwable: Throwable) {
                completion(
                    ProfilerFileIoResult.TerminalFailure(
                        throwable.message ?: throwable::class.simpleName.orEmpty().ifEmpty { "unknown error" }
                    )
                )
            }
        }

        if (KuiklyContextScheduler.isOnKuiklyThread(module.pagerId)) {
            invokeNative()
        } else {
            KuiklyContextScheduler.runOnKuiklyThread(module.pagerId) { cancel ->
                if (cancel) {
                    completion(ProfilerFileIoResult.RetryableFailure("pager bridge unavailable"))
                } else {
                    invokeNative()
                }
            }
        }
    }
}

/**
 * 文件写入输出策略。
 *
 * 行为（仅在 start/stop 期间生效）：
 * - 每帧完成时将帧 JSON 存入内存缓冲区；每 2 秒批量 append 到 profiler_frames.jsonl
 * - getReport(saveToFile=true) / stop() 时先 flush 剩余帧，再覆盖写入 profiler_report.json
 *
 * 文件格式：
 * - profiler_frames.jsonl 第一行为 session header：{"type":"session","sessionId":"...","startTimestampMs":...}
 *   后续每行一个帧 JSON，可按 sessionId 过滤跨 session 数据
 *
 * 多页面场景：同 App 内多个页面共享同一原生目录，但 FileModule 本身属于 Pager。
 * 每次 I/O 从 [fileModuleProvider] 解析当前 live module；若 Pager 在调度窗口销毁，
 * 操作保留在串行队列中，等待下一个 live module 重试，不得静默丢弃。
 *
 * @param fileModuleProvider 返回当前 live Pager 的 FileModule
 * @param ioDispatcher 生产环境调度到 Kuikly context 并调原生 FileModule；测试可注入 fake
 */
internal class FileOutputStrategy(
    private val fileModuleProvider: () -> FileModule?,
    private val ioDispatcher: ProfilerFileIoDispatcher = NativeProfilerFileIoDispatcher
) : RecompositionOutputStrategy, RecompositionSessionOutputStrategy {

    companion object {
        private const val FILE_FRAMES = "profiler_frames.jsonl"
        private const val FILE_REPORT = "profiler_report.json"
        /** append 批量写入间隔（毫秒） */
        private const val APPEND_INTERVAL_MS = 2000L
    }

    /** 待 append 的帧 JSON 缓冲区（onFrameComplete 在帧路径线程写入，须加锁） */
    private val pendingFrames = mutableListOf<String>()
    private val pendingFramesLock = createSynchronizedObject()

    /** FileModule 是异步接口；所有 write/append 必须串行，防止 header/clear/report 与 append 乱序。 */
    private val fileOperationsLock = createSynchronizedObject()
    private val pendingFileOperations = mutableListOf<ProfilerFileOperation>()
    private var inFlightFileOperation: ProfilerFileOperation? = null
    private var blockedFileModule: FileModule? = null

    /** 上次 append 的时间戳 */
    private var lastAppendMs: Long = 0L

    /** 当前是否处于 start/stop 之间（由外部通过 setActive 控制）；帧路径线程读、context 线程写 */
    @Volatile
    private var active: Boolean = false

    /** session 真正的 start 时间戳（tracker.startTimestampMs），用于过滤旧帧 */
    private var sessionStartTimestampMs: Long = 0L

    /** reset/activate 代际；防止 reset 前已开始构建的帧在 reset 后进入新 session。 */
    private var sessionGeneration: Long = 0L

    /**
     * 由 RecompositionProfiler 在 start() 时调用，激活文件写入。
     * 写入 session header 行到 frames 文件（覆盖旧文件），确保每次 session 数据独立。
     *
     * @param sessionId tracker 的 sessionId
     * @param sessionStartMs tracker.startTimestampMs，用于过滤 start 之前的旧帧
     */
    fun activate(sessionId: String, sessionStartMs: Long) {
        beginSession(sessionId, sessionStartMs, activate = true)
    }

    /**
     * 由 RecompositionProfiler 在 stop() 时调用，停止文件写入并 flush 剩余帧数据。
     */
    fun deactivate(report: RecompositionReport) {
        if (!active) return
        active = false
        // flush 剩余帧
        flushPendingFrames()
        // 写聚合报告
        writeReport(report)
    }

    /**
     * 主动写入报告文件（对应 getReport(saveToFile=true)）。
     * 先 flush 内存中尚未写入的帧，确保 frames 文件与 report 数据完整一致。
     */
    fun writeReport(report: RecompositionReport) {
        flushPendingFrames()
        enqueueFileOperation(
            ProfilerFileOperation(
                kind = ProfilerFileOperationKind.WRITE,
                filename = FILE_REPORT,
                content = report.toJson()
            )
        )
    }

    override fun onFrameComplete(events: List<RecompositionEvent>) {
        val generation = synchronized(pendingFramesLock) {
            if (!active) return
            sessionGeneration
        }
        // 过滤 session start 之前产生的旧帧（多页面场景下其他页面的帧可能晚于 activate 到达）
        val frameStart = events.firstOrNull { it is RecompositionFrameStartEvent } as? RecompositionFrameStartEvent
        val frameJson = buildFrameJson(events)
        val now = DateTime.currentTimestamp()
        val shouldFlush = synchronized(pendingFramesLock) {
            if (!active || generation != sessionGeneration) return@synchronized false
            if (frameStart != null && frameStart.timestampMs < sessionStartTimestampMs) {
                return@synchronized false
            }
            pendingFrames.add(frameJson)
            if (now - lastAppendMs >= APPEND_INTERVAL_MS) {
                lastAppendMs = now
                true
            } else {
                false
            }
        }
        if (shouldFlush) {
            flushPendingFrames()
        }
    }

    override fun onReportReady(report: RecompositionReport) {
        // 由 deactivate() / writeReport() 主动调用，此处不重复写
    }

    override fun onSessionReset(sessionId: String, startTimestampMs: Long) {
        if (!active) return
        beginSession(sessionId, startTimestampMs, activate = false)
    }

    /**
     * 追加上下文事件（touch_context / scroll_context）为独立 JSONL 行到 pendingFrames 缓冲区。
     * 由 RecompositionProfiler.recordTouchContext / recordScrollContext 调用。
     * 非 active 状态下忽略（Profiler 未启用时零开销由调用方的 isEnabled 门控保证）。
     */
    internal fun appendContextEvent(event: RecompositionEvent) {
        val json = buildContextEventJson(event) ?: return
        synchronized(pendingFramesLock) {
            if (active && event.timestampMs >= sessionStartTimestampMs) {
                pendingFrames.add(json)
            }
        }
    }

    /** 当 live Pager/FileModule 集合变化时，重试因旧 Pager 销毁而保留的队头操作。 */
    internal fun onFileModuleChanged() {
        synchronized(fileOperationsLock) {
            blockedFileModule = null
        }
        dispatchNextFileOperation()
    }

    // ========== 内部方法 ==========

    private fun beginSession(sessionId: String, sessionStartMs: Long, activate: Boolean) {
        synchronized(pendingFramesLock) {
            if (activate) active = true
            sessionStartTimestampMs = sessionStartMs
            sessionGeneration += 1L
            lastAppendMs = DateTime.currentTimestamp()
            pendingFrames.clear()
        }

        val header = "{\"type\":\"session\",\"sessionId\":\"$sessionId\",\"startTimestampMs\":$sessionStartMs}\n"
        synchronized(fileOperationsLock) {
            // 新 session 取代尚未提交的旧 session 操作。已进入原生层的操作不可取消，
            // 但串行队列保证新 header/clear 必定在其完成后覆盖旧数据。
            pendingFileOperations.clear()
            pendingFileOperations.add(
                ProfilerFileOperation(ProfilerFileOperationKind.WRITE, FILE_FRAMES, header)
            )
            // 清空旧 report，避免上一 session 的 report 与新 frames 被误当成同一次采集。
            pendingFileOperations.add(
                ProfilerFileOperation(ProfilerFileOperationKind.WRITE, FILE_REPORT, "")
            )
            blockedFileModule = null
        }
        dispatchNextFileOperation()
    }

    private fun flushPendingFrames() {
        val batch = synchronized(pendingFramesLock) {
            if (pendingFrames.isEmpty()) return
            pendingFrames.joinToString("\n").also { pendingFrames.clear() }
        }
        enqueueFileOperation(
            ProfilerFileOperation(
                kind = ProfilerFileOperationKind.APPEND,
                filename = FILE_FRAMES,
                content = batch
            )
        )
    }

    private fun enqueueFileOperation(operation: ProfilerFileOperation) {
        synchronized(fileOperationsLock) {
            pendingFileOperations.add(operation)
        }
        dispatchNextFileOperation()
    }

    private fun dispatchNextFileOperation() {
        var dispatch: Pair<FileModule, ProfilerFileOperation>? = null
        synchronized(fileOperationsLock) {
            if (inFlightFileOperation != null || pendingFileOperations.isEmpty()) return@synchronized
            val module = fileModuleProvider() ?: return@synchronized
            if (module === blockedFileModule) return@synchronized
            val operation = pendingFileOperations.removeAt(0)
            inFlightFileOperation = operation
            dispatch = module to operation
        }
        val (module, operation) = dispatch ?: return
        ioDispatcher.dispatch(module, operation) { result ->
            completeFileOperation(module, operation, result)
        }
    }

    private fun completeFileOperation(
        module: FileModule,
        operation: ProfilerFileOperation,
        result: ProfilerFileIoResult
    ) {
        var retryImmediately = false
        var terminalFailure: String? = null
        synchronized(fileOperationsLock) {
            if (inFlightFileOperation !== operation) return@synchronized
            inFlightFileOperation = null
            when (result) {
                ProfilerFileIoResult.Success -> blockedFileModule = null
                is ProfilerFileIoResult.RetryableFailure -> {
                    pendingFileOperations.add(0, operation)
                    blockedFileModule = module
                    retryImmediately = fileModuleProvider()?.let { it !== module } == true
                    if (retryImmediately) blockedFileModule = null
                }
                is ProfilerFileIoResult.TerminalFailure -> {
                    blockedFileModule = null
                    terminalFailure = result.reason
                }
            }
        }
        terminalFailure?.let { reason ->
            com.tencent.kuikly.core.log.KLog.e(
                "RCProfiler",
                "File output failed operation=${operation.kind} file=${operation.filename}: $reason"
            )
        }
        if (result is ProfilerFileIoResult.Success || terminalFailure != null || retryImmediately) {
            dispatchNextFileOperation()
        }
    }

    private fun buildFrameJson(events: List<RecompositionEvent>): String {
        return buildString {
            append("{\"type\":\"frame\",\"events\":[")
            var firstWritten = false
            events.forEach { event ->
                val before = length
                appendEventJson(event)
                val written = length > before
                if (written) {
                    if (firstWritten) {
                        // insert comma before this event
                        insert(before, ",")
                    }
                    firstWritten = true
                }
            }
            append("]}")
        }
    }

    private fun StringBuilder.appendEventJson(event: RecompositionEvent) {
        when (event) {
            is RecompositionFrameStartEvent -> {
                append("{\"eventType\":\"${event.eventType}\",")
                append("\"timestampMs\":${event.timestampMs},")
                append("\"frameId\":${event.frameId}}")
            }
            is RecompositionFrameEndEvent -> {
                append("{\"eventType\":\"${event.eventType}\",")
                append("\"timestampMs\":${event.timestampMs},")
                append("\"frameId\":${event.frameId},")
                append("\"durationMs\":${event.durationMs},")
                append("\"recomposedCount\":${event.recomposedCount}}")
            }
            is ComposableRecomposedEvent -> {
                if (event.composableName == "<anonymous>") return
                append("{\"eventType\":\"${event.eventType}\",")
                append("\"timestampMs\":${event.timestampMs},")
                append("\"composableName\":\"${escapeJson(event.composableName)}\",")
                if (event.sourceLocation != null) {
                    append("\"sourceLocation\":\"${escapeJson(event.sourceLocation)}\",")
                }
                append("\"durationMs\":${event.durationMs},")
                if (event.parentName != null) {
                    append("\"parentName\":\"${escapeJson(event.parentName)}\",")
                }
                append("\"reason\":\"${event.reason.name}\",")
                if (event.paramChanges != null) {
                    append("\"paramChanges\":{")
                    append("\"totalParams\":${event.paramChanges.totalParams},")
                    append("\"changedParams\":[${event.paramChanges.changedParams.joinToString(",")}],")
                    append("\"unknownParams\":[${event.paramChanges.unknownParams.joinToString(",")}]")
                    append("},")
                }
                append("\"triggerStates\":[")
                event.triggerStates.forEachIndexed { i, s ->
                    if (i > 0) append(",")
                    append("\"${escapeJson(s)}\"")
                }
                append("]}")
            }
            else -> { /* TouchContextEvent / ScrollContextEvent are written as standalone lines via appendContextEvent, not inside frame arrays */ }
        }
    }

    private fun buildContextEventJson(event: RecompositionEvent): String? {
        return when (event) {
            is TouchContextEvent -> buildString {
                append("{\"type\":\"touch_context\",")
                append("\"eventType\":\"${event.touchEventType}\",")
                append("\"timestampMs\":${event.timestampMs},")
                append("\"pointerCount\":${event.pointerCount}}")
            }
            is ScrollContextEvent -> buildString {
                append("{\"type\":\"scroll_context\",")
                append("\"listId\":\"${escapeJson(event.listId)}\",")
                append("\"firstVisibleItemFrom\":${event.firstVisibleItemFrom},")
                append("\"firstVisibleItemTo\":${event.firstVisibleItemTo},")
                append("\"visibleItemCount\":${event.visibleItemCount},")
                append("\"timestampMs\":${event.timestampMs}}")
            }
            else -> null
        }
    }
}
