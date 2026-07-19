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

import com.tencent.kuikly.compose.profiler.ComposableRecomposedEvent
import com.tencent.kuikly.compose.profiler.RecompositionEvent
import com.tencent.kuikly.compose.profiler.RecompositionFrameEndEvent
import com.tencent.kuikly.compose.profiler.RecompositionFrameStartEvent
import com.tencent.kuikly.compose.profiler.RecompositionOutputStrategy
import com.tencent.kuikly.compose.profiler.RecompositionReport
import com.tencent.kuikly.compose.profiler.ScrollContextEvent
import com.tencent.kuikly.compose.profiler.TouchContextEvent
import com.tencent.kuikly.compose.coroutines.internal.KuiklyContextScheduler
import com.tencent.kuikly.compose.ui.SynchronizedObject
import com.tencent.kuikly.compose.ui.synchronized
import com.tencent.kuikly.core.datetime.DateTime
import com.tencent.kuikly.core.module.FileModule
import kotlinx.atomicfu.atomic

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
 * 多页面场景：同 App 内多个页面共享同一 FileModule 目录，同名文件后写覆盖前写。
 *
 * @param fileModule KMP 层 FileModule 实例，由 ComposeContainer 传入
 */
internal class FileOutputStrategy(
    private val fileModule: FileModule
) : RecompositionOutputStrategy {

    companion object {
        private const val FILE_FRAMES = "profiler_frames.jsonl"
        private const val FILE_REPORT = "profiler_report.json"
        /** append 批量写入间隔（毫秒） */
        private const val APPEND_INTERVAL_MS = 2000L
    }

    /** 待 append 的帧 JSON 缓冲区（仅可在 [pendingLock] 内访问） */
    private val pendingFrames = mutableListOf<String>()

    /** [pendingFrames] 的访问锁：帧完成回调可能来自任意线程 */
    private val pendingLock = SynchronizedObject()

    /** 上次 append 的时间戳 */
    private var lastAppendMs: Long = 0L

    /** 当前是否处于 start/stop 之间（由外部通过 activate/deactivate 控制） */
    private val active = atomic(false)

    /** 当前 session ID，写入 frames 文件 header 用 */
    private var currentSessionId: String = ""

    /** session 真正的 start 时间戳（tracker.startTimestampMs），用于过滤旧帧 */
    private var sessionStartTimestampMs: Long = 0L

    /**
     * 由 RecompositionProfiler 在 start() 时调用，激活文件写入。
     * 写入 session header 行到 frames 文件（覆盖旧文件），确保每次 session 数据独立。
     *
     * @param sessionId tracker 的 sessionId
     * @param sessionStartMs tracker.startTimestampMs，用于过滤 start 之前的旧帧
     */
    fun activate(sessionId: String, sessionStartMs: Long) {
        active.value = true
        currentSessionId = sessionId
        sessionStartTimestampMs = sessionStartMs
        lastAppendMs = DateTime.currentTimestamp()
        synchronized(pendingLock) { pendingFrames.clear() }
        // 写 session header，同时清空上次 session 的帧数据
        val header = "{\"type\":\"session\",\"sessionId\":\"$sessionId\",\"startTimestampMs\":$sessionStartMs}\n"
        scheduleFileWrite { fileModule.writeFile(FILE_FRAMES, header) { } }
        // 同步清空 report 文件，避免旧 report 与新 frames 属于不同 session
        scheduleFileWrite { fileModule.writeFile(FILE_REPORT, "") { } }
    }

    /**
     * 由 RecompositionProfiler 在 stop() 时调用，停止文件写入并 flush 剩余帧数据。
     */
    fun deactivate(report: RecompositionReport) {
        if (!active.value) return
        active.value = false
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
        val reportJson = report.toJson()
        scheduleFileWrite { fileModule.writeFile(FILE_REPORT, reportJson) { } }
    }

    override fun onFrameComplete(events: List<RecompositionEvent>) {
        if (!active.value) return
        // 过滤 session start 之前产生的旧帧（多页面场景下其他页面的帧可能晚于 activate 到达）
        val frameStart = events.firstOrNull { it is RecompositionFrameStartEvent } as? RecompositionFrameStartEvent
        if (frameStart != null && frameStart.timestampMs < sessionStartTimestampMs) return
        val frameJson = buildFrameJson(events)
        // 每 2 秒批量写入一次
        val now = DateTime.currentTimestamp()
        val batch: String? = synchronized(pendingLock) {
            pendingFrames.add(frameJson)
            if (now - lastAppendMs >= APPEND_INTERVAL_MS) {
                lastAppendMs = now
                drainPendingFramesLocked()
            } else {
                null
            }
        }
        batch?.let { content ->
            scheduleFileWrite { fileModule.appendFile(FILE_FRAMES, content) { } }
        }
    }

    override fun onReportReady(report: RecompositionReport) {
        // 由 deactivate() / writeReport() 主动调用，此处不重复写
    }

    /**
     * 追加上下文事件（touch_context / scroll_context）为独立 JSONL 行到 pendingFrames 缓冲区。
     * 由 RecompositionProfiler.recordTouchContext / recordScrollContext 调用。
     * 非 active 状态下忽略（Profiler 未启用时零开销由调用方的 isEnabled 门控保证）。
     */
    internal fun appendContextEvent(event: RecompositionEvent) {
        if (!active.value) return
        val json = buildContextEventJson(event) ?: return
        synchronized(pendingLock) { pendingFrames.add(json) }
    }

    // ========== 内部方法 ==========

    private fun flushPendingFrames() {
        val batch = synchronized(pendingLock) { drainPendingFramesLocked() } ?: return
        scheduleFileWrite { fileModule.appendFile(FILE_FRAMES, batch) { } }
    }

    /**
     * FileModule 必须从 Kuikly context 线程调用。帧完成回调由全局 snapshot
     * observer 触发，可能落在主线程（例如新 ComposeView attach）；直接写文件会
     * 触发 KuiklyRenderJvmContextHandler.callNative 的非主线程断言。
     *
     * KuiklyContextScheduler 不仅切换线程，还会恢复正确的 pager context；通用后台
     * dispatcher 无法提供这个保证。调度器会按 pager 保持任务提交顺序，因此
     * session header、frame append 与 report write 不会乱序。
     */
    private fun scheduleFileWrite(block: () -> Unit) {
        KuiklyContextScheduler.runOnKuiklyThread(fileModule.pagerId) { cancelled ->
            if (!cancelled) block()
        }
    }

    /** 调用方必须持有 [pendingLock]；返回 null 表示缓冲为空无需写入。 */
    private fun drainPendingFramesLocked(): String? {
        if (pendingFrames.isEmpty()) return null
        val batch = pendingFrames.joinToString("\n")
        pendingFrames.clear()
        return batch
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
