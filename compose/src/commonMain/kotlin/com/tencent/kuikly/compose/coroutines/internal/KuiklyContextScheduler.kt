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
package com.tencent.kuikly.compose.coroutines.internal

import com.tencent.kuikly.core.manager.BridgeManager
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

internal object KuiklyContextScheduler : SynchronizedObject() {

    private val taskMap = mutableMapOf<String, MutableList<(Boolean) -> Unit>>()
    private val scheduleMap = mutableMapOf<String, Boolean>()
    private val idleTaskMap = mutableMapOf<String, MutableList<(Boolean) -> Unit>>()
    private val idleScheduleMap = mutableMapOf<String, Boolean>()
    private val normalGenerationMap = mutableMapOf<String, Long>()
    private val idleScheduleGenerationMap = mutableMapOf<String, Long>()
    private var executingIdlePagerId: String? = null

    init {
        platformInitScheduler()
    }

    /**
     * 判断当前线程是否是Kuikly线程
     * @param pagerId 页面ID
     * @return 是否是Kuikly线程
     */
    fun isOnKuiklyThread(pagerId: String) = platformIsOnKuiklyThread(pagerId)

    /**
     * 在Kuikly线程执行任务
     * @param pagerId 页面ID
     * @param block 任务
     */
    fun runOnKuiklyThread(pagerId: String, block: (cancel: Boolean) -> Unit) {
        if (executingIdlePagerId == pagerId && platformIsOnKuiklyThread(pagerId)) {
            runOnKuiklyThreadIdle(pagerId, block)
            return
        }
        var needSchedule = false
        synchronized(this) {
            val taskList = taskMap[pagerId] ?: mutableListOf<(Boolean) -> Unit>().also { taskMap[pagerId] = it }
            taskList.add(block)
            normalGenerationMap[pagerId] = (normalGenerationMap[pagerId] ?: 0L) + 1L
            if (scheduleMap[pagerId] != true) {
                scheduleMap[pagerId] = true
                needSchedule = true
            }
        }
        if (needSchedule) {
            platformScheduleOnKuiklyThread(pagerId)
        }
    }

    /**
     * Enqueues speculative work on the Kuikly context idle lane.
     *
     * Idle work runs one callback at a time only after normal context work has
     * drained. Normal work queued before the callback executes invalidates the
     * idle admission and moves the callback behind the new foreground work.
     * Work scheduled recursively from an idle callback inherits the idle lane.
     */
    fun runOnKuiklyThreadIdle(pagerId: String, block: (cancel: Boolean) -> Unit) {
        synchronized(this) {
            val taskList = idleTaskMap[pagerId]
                ?: mutableListOf<(Boolean) -> Unit>().also { idleTaskMap[pagerId] = it }
            taskList.add(block)
        }
        scheduleIdleIfNeeded(pagerId)
    }

    /**
     * 执行任务，非线程安全
     * @param pagerId 页面ID
     */
    internal fun runTask(pagerId: String) {
        val cancel = !BridgeManager.containNativeBridge(pagerId)
        var taskList: List<(Boolean) -> Unit>? = null
        synchronized(this) {
            taskList = taskMap.remove(pagerId)
            scheduleMap[pagerId] = false
        }
        if (taskList.isNullOrEmpty()) {
            return
        }
        BridgeManager.currentPageId = pagerId
        for (task in taskList!!) {
            try {
                task(cancel)
            } catch (t: Throwable) {
                platformNotifyKuiklyException(t)
            }
        }
        scheduleIdleIfNeeded(pagerId)
    }

    /** Executes at most one idle callback. Called by the platform idle lane. */
    internal fun runIdleTask(pagerId: String) {
        val cancel = !BridgeManager.containNativeBridge(pagerId)
        var task: ((Boolean) -> Unit)? = null
        var shouldReschedule = false
        synchronized(this) {
            val scheduledGeneration = idleScheduleGenerationMap.remove(pagerId)
            idleScheduleMap[pagerId] = false
            val currentGeneration = normalGenerationMap[pagerId] ?: 0L
            val hasNormalWork = scheduleMap[pagerId] == true || taskMap[pagerId].isNullOrEmpty().not()
            val idleTasks = idleTaskMap[pagerId]
            when (
                kuiklyIdleAdmissionDecision(
                    hasIdleWork = idleTasks.isNullOrEmpty().not(),
                    hasNormalWork = hasNormalWork,
                    scheduledGeneration = scheduledGeneration,
                    currentGeneration = currentGeneration
                )
            ) {
                KuiklyIdleAdmissionDecision.Run -> {
                    val admittedTasks = idleTasks ?: return@synchronized
                    task = admittedTasks.removeAt(0)
                    if (admittedTasks.isEmpty()) {
                        idleTaskMap.remove(pagerId)
                    }
                }
                KuiklyIdleAdmissionDecision.Reschedule -> shouldReschedule = true
                KuiklyIdleAdmissionDecision.WaitForNormalDrain,
                KuiklyIdleAdmissionDecision.None -> Unit
            }
        }
        if (shouldReschedule) {
            scheduleIdleIfNeeded(pagerId)
            return
        }
        val idleTask = task ?: return
        BridgeManager.currentPageId = pagerId
        executingIdlePagerId = pagerId
        try {
            idleTask(cancel)
        } catch (t: Throwable) {
            platformNotifyKuiklyException(t)
        } finally {
            executingIdlePagerId = null
        }
        scheduleIdleIfNeeded(pagerId)
    }

    private fun scheduleIdleIfNeeded(pagerId: String) {
        var needSchedule = false
        synchronized(this) {
            val hasIdleWork = idleTaskMap[pagerId].isNullOrEmpty().not()
            val hasNormalWork = scheduleMap[pagerId] == true || taskMap[pagerId].isNullOrEmpty().not()
            if (
                shouldScheduleKuiklyIdle(
                    hasIdleWork = hasIdleWork,
                    hasNormalWork = hasNormalWork,
                    alreadyScheduled = idleScheduleMap[pagerId] == true
                )
            ) {
                idleScheduleMap[pagerId] = true
                idleScheduleGenerationMap[pagerId] = normalGenerationMap[pagerId] ?: 0L
                needSchedule = true
            }
        }
        if (needSchedule) {
            platformScheduleIdleOnKuiklyThread(pagerId)
        }
    }

}

internal expect fun platformInitScheduler()

internal expect inline fun platformIsOnKuiklyThread(pagerId: String): Boolean

internal expect inline fun platformScheduleOnKuiklyThread(pagerId: String)

internal expect inline fun platformScheduleIdleOnKuiklyThread(pagerId: String)

internal expect inline fun platformNotifyKuiklyException(t: Throwable)
