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

package com.tencent.kuikly.core.render.android.scheduler

import android.os.Handler
import android.os.Looper
import com.tencent.kuikly.core.render.android.IKuiklyRenderViewTreeUpdateListener
import com.tencent.kuikly.core.render.android.adapter.KuiklyRenderLog
import com.tencent.kuikly.core.render.android.context.nativeMethodCallCounts
import com.tencent.kuikly.core.render.android.css.ktx.isMainThread
import com.tencent.kuikly.core.render.android.exception.ErrorReason
import com.tencent.kuikly.core.render.android.exception.IKuiklyRenderExceptionListener
import com.tencent.kuikly.core.render.android.expand.KuiklyRenderTracer

/**
 * KTV页面UI线程调度器
 */
class KuiklyRenderCoreUIScheduler(
    private val preRunKuiklyRenderCoreUITask: PreRunKuiklyRenderCoreTask? = null
) : IKuiklyRenderCoreScheduler {
    private val taskQueue = KuiklyRenderCoreTaskQueue()
    /*
     * 需要立即回到主线程执行的同步主线程执行任务闭包
     */
    var mainThreadTaskWaitToSyncBlock: (() -> Unit)?
        get() = taskQueue.peekMainThreadWaitBlock()
        set(value) {
            taskQueue.replaceMainThreadWaitBlock(value)
        }
    /*
     *  是否执行主线程任务中
     */
    var isPerformingMainQueueTask = false
     private set
    private val uiHandler by lazy {
        Handler(Looper.getMainLooper())
    }
    /*
     * 首屏视图是否加载完
     */
    private var viewDidLoad = false
    /*
     * 主线程上的任务集合
     */
    private val viewDidLoadMainThreadTasks = mutableListOf<KuiklyRenderCoreTask>()

    /**
     * ViewTree 更新事件监听
     */
    private var viewTreeUpdateListener: IKuiklyRenderViewTreeUpdateListener? = null

    /**
     * 异常监听
     */
    private var exceptionListener: IKuiklyRenderExceptionListener? = null


    /**
     * 日志计数
     */
    private var debugLogEnable = false
    private var setNeedSyncLogCount = 0
    private var needSyncLogCount = 0
    private var performFunLogCount = 0
    private var logPerformIfNeedCount = 0
    private var performCount = 0
    private var logRunCount = 0
    private var callNativeLogCount = 0

    override fun scheduleTask(delayMs: Long, task: Runnable) {
        scheduleTask(delayMs, false, task)
    }

    /**
     * 添加 UI 更新任务
     */
    fun scheduleTask(delayMs: Long = 0, isUpdateViewTree: Boolean = false, task: Runnable) {
        addTaskToMainQueue(KuiklyRenderCoreTaskExecutor(task, isUpdateViewTree))
    }

    override fun destroy() {
        KuiklyRenderLog.i("KuiklyRenderCoreUIScheduler", "--destroy uiScheduler--")
        taskQueue.destroy()
        uiHandler.removeCallbacksAndMessages(null)
        viewDidLoadMainThreadTasks.clear()
        viewTreeUpdateListener = null
        exceptionListener = null
    }

    fun setViewTreeUpdateListener(listener: IKuiklyRenderViewTreeUpdateListener) {
        viewTreeUpdateListener = listener
    }

    fun setRenderExceptionListener(listener: IKuiklyRenderExceptionListener?) {
        exceptionListener = listener
    }

    fun performSyncMainQueueTasksBlockIfNeed(sync: Boolean) {
        if (taskQueue.destroyed) return
        var tracer: KuiklyRenderTracer? = null
        if (debugLogEnable && logPerformIfNeedCount < UI_SCHEDULER_MAX_LOG_COUNT) {
            tracer = KuiklyRenderTracer("invoke needSyncMainQueueTasksBlock $logPerformIfNeedCount isNull=${!taskQueue.hasDrainBlock()} sync=$sync")
            logPerformIfNeedCount++
        }
        taskQueue.takeDrainBlock()?.invoke(sync)
        tracer?.end()
    }

    fun performMainThreadTaskWaitToSyncBlockIfNeed() {
        if (taskQueue.destroyed) return
        var tracer: KuiklyRenderTracer? = null
        if (debugLogEnable && logRunCount < UI_SCHEDULER_MAX_LOG_COUNT) {
            tracer = KuiklyRenderTracer("invoke mainThreadTaskWaitToSyncBlock $logRunCount isNull=${!taskQueue.hasMainThreadWaitBlock()}")
            logRunCount++
        }
        val block = taskQueue.takeMainThreadWaitBlock()
        if (!taskQueue.destroyed) {
            block?.invoke()
        }
        tracer?.end()
    }

    // 首屏完成在执行任务
    fun performWhenViewDidLoad(task: KuiklyRenderCoreTask) {
        assert(isMainThread())
        if (taskQueue.destroyed) return
        if (viewDidLoad) {
            task()
        } else {
            viewDidLoadMainThreadTasks.add(task)
        }
    }

    private fun addTaskToMainQueue(task: KuiklyRenderCoreTaskExecutor) {
        assert(!isMainThread())
        if (!taskQueue.enqueue(task)) return
        if (task.isUpdateViewTree) {
            viewTreeUpdateListener?.onUpdateViewTreeEnqueued()
        }
        setNeedSyncMainQueueTasks()
    }

    private fun setNeedSyncMainQueueTasks() {
        assert(!isMainThread())
        if (taskQueue.destroyed) return
        if (debugLogEnable && setNeedSyncLogCount < UI_SCHEDULER_MAX_LOG_COUNT) {
            KuiklyRenderLog.d("KuiklyUIScheduler", "--setNeedSyncMainQueueTasks${setNeedSyncLogCount}--")
            setNeedSyncLogCount++
        }
        val block: (Boolean) -> Unit = syncBlock@ { sync ->
            if (taskQueue.destroyed) return@syncBlock
            assert(!isMainThread())
            if (debugLogEnable && needSyncLogCount < UI_SCHEDULER_MAX_LOG_COUNT) {
                KuiklyRenderLog.d("KuiklyUIScheduler", "--needSyncMainQueueTasksBlock${needSyncLogCount}--")
                needSyncLogCount++
            }
            preRunKuiklyRenderCoreUITask?.invoke()
            if (!taskQueue.transferContextTasksToMain()) return@syncBlock
            performOnMainQueueWithTask(sync = sync) {
                if (taskQueue.destroyed) return@performOnMainQueueWithTask
                if (debugLogEnable && performFunLogCount < UI_SCHEDULER_MAX_LOG_COUNT) {
                    KuiklyRenderLog.d("KuiklyUIScheduler", "--performOnMainQueueWithTask:${sync} ${performFunLogCount}--")
                    performFunLogCount++
                }
                runMainQueueTasks(taskQueue.takeMainTasks())
            }
        }
        if (!taskQueue.installDrainBlock(block)) return
        KuiklyRenderCoreContextScheduler.scheduleTask {
            performSyncMainQueueTasksBlockIfNeed(false)
        } // end task
    }

    fun performOnMainQueueWithTask(sync : Boolean, task: ()-> Unit) {
        if (taskQueue.destroyed) return
        var tracer: KuiklyRenderTracer? = null
        if (debugLogEnable && performCount < UI_SCHEDULER_MAX_LOG_COUNT) {
            tracer = KuiklyRenderTracer("performOnMainQueueWithTask $performCount sync=$sync isNull=${!taskQueue.hasMainThreadWaitBlock()}")
            performCount++
        }
        if (sync) {
            if (isMainThread()) {
                if (!taskQueue.destroyed) task()
            } else {
                // 当前子线程等到主线程可能发生死锁，暂用闭包等后面立即回到主线程处理
                taskQueue.setMainThreadWaitBlock(task)
            }
        } else {
            uiHandler.post {
                if (!taskQueue.destroyed) task()
            }
        }
        tracer?.end()
    }

    private fun runMainQueueTasks(tasks: List<KuiklyRenderCoreTaskExecutor?>?) {
        assert(isMainThread()) {
            "must call on ui thread"
        }
        if (taskQueue.destroyed) return
        try {
            val uiTasks = tasks ?: return
            isPerformingMainQueueTask = true
            executeKuiklyRenderCoreTaskBatch(
                tasks = uiTasks,
                onNullTask = { index ->
                    KuiklyRenderLog.e(
                        "KuiklyRenderCoreUIScheduler",
                        "skip null main queue task index=$index size=${uiTasks.size}"
                    )
                },
                onUpdateViewTreeFinish = { viewTreeUpdateListener?.onUpdateViewTreeFinish() }
            )
            isPerformingMainQueueTask = false
        } catch (e : Exception) {
            exceptionListener?.onRenderException(e, ErrorReason.UPDATE_VIEW_TREE)
        }
        isPerformingMainQueueTask = false
        if(!viewDidLoad) {
            viewDidLoad = true
            performViewDidLoadTasksIfNeed()
        }
        if (debugLogEnable && callNativeLogCount < UI_SCHEDULER_MAX_LOG_COUNT) {
            if (nativeMethodCallCounts.any { it != 0 }) {
                KuiklyRenderLog.d("KuiklyRenderTracer", "runMainQueueTask ${tasks?.size.toString()} taskMap: ${nativeMethodCallCounts.mapIndexed { index, i -> "$index:$i" }.joinToString()}")
                nativeMethodCallCounts.fill(0)
                callNativeLogCount ++
            }
        }
    }

    // perform all wait to viewDidLoad tasks
    private fun performViewDidLoadTasksIfNeed() {
        if (taskQueue.destroyed) return
        performOnMainQueueWithTask(sync = false) {
            if (taskQueue.destroyed) return@performOnMainQueueWithTask
            for (task in viewDidLoadMainThreadTasks.toList()) {
                task()
            }
            viewDidLoadMainThreadTasks.clear()
        }
    }

    fun setDebugLogEnable(enable: Boolean) {
        debugLogEnable = enable
    }

    companion object {
        private const val UI_SCHEDULER_MAX_LOG_COUNT = 10
    }

}

/**
 * Owns all render-task queue state shared by context/native producers and the Android main thread.
 * A non-main-thread assertion does not imply a single producer, so every mutation must use [lock].
 */
internal class KuiklyRenderCoreTaskQueue {
    private val lock = Any()
    private var contextTasks: MutableList<KuiklyRenderCoreTaskExecutor?>? = null
    private val mainTasks = mutableListOf<KuiklyRenderCoreTaskExecutor?>()
    private var drainBlock: ((Boolean) -> Unit)? = null
    private var mainThreadWaitBlock: (() -> Unit)? = null

    @Volatile
    var destroyed = false
        private set

    fun enqueue(task: KuiklyRenderCoreTaskExecutor): Boolean = synchronized(lock) {
        if (destroyed) return@synchronized false
        val tasks = contextTasks ?: mutableListOf<KuiklyRenderCoreTaskExecutor?>().also {
            contextTasks = it
        }
        tasks.add(task)
        true
    }

    fun installDrainBlock(block: (Boolean) -> Unit): Boolean = synchronized(lock) {
        if (destroyed || drainBlock != null) return@synchronized false
        drainBlock = block
        true
    }

    fun hasDrainBlock(): Boolean = synchronized(lock) { drainBlock != null }

    fun takeDrainBlock(): ((Boolean) -> Unit)? = synchronized(lock) {
        drainBlock.also { drainBlock = null }
    }

    fun transferContextTasksToMain(): Boolean = synchronized(lock) {
        if (destroyed) return@synchronized false
        mainTasks.addAll(contextTasks?.toList().orEmpty())
        contextTasks = null
        true
    }

    fun takeMainTasks(): List<KuiklyRenderCoreTaskExecutor?> = synchronized(lock) {
        if (destroyed) return@synchronized emptyList()
        mainTasks.toList().also { mainTasks.clear() }
    }

    fun hasMainThreadWaitBlock(): Boolean = synchronized(lock) { mainThreadWaitBlock != null }

    fun peekMainThreadWaitBlock(): (() -> Unit)? = synchronized(lock) { mainThreadWaitBlock }

    fun setMainThreadWaitBlock(block: () -> Unit): Boolean = synchronized(lock) {
        if (destroyed) return@synchronized false
        mainThreadWaitBlock = block
        true
    }

    fun replaceMainThreadWaitBlock(block: (() -> Unit)?): Boolean = synchronized(lock) {
        if (destroyed && block != null) return@synchronized false
        mainThreadWaitBlock = block
        true
    }

    fun takeMainThreadWaitBlock(): (() -> Unit)? = synchronized(lock) {
        mainThreadWaitBlock.also { mainThreadWaitBlock = null }
    }

    fun destroy() {
        synchronized(lock) {
            destroyed = true
            contextTasks?.clear()
            contextTasks = null
            mainTasks.clear()
            drainBlock = null
            mainThreadWaitBlock = null
        }
    }
}

internal fun executeKuiklyRenderCoreTaskBatch(
    tasks: List<KuiklyRenderCoreTaskExecutor?>,
    onNullTask: (Int) -> Unit = {},
    onUpdateViewTreeFinish: () -> Unit = {}
): Int {
    var executed = 0
    tasks.forEachIndexed { index, task ->
        if (task == null) {
            onNullTask(index)
            return@forEachIndexed
        }
        task.execute()
        executed++
        if (task.isUpdateViewTree) {
            onUpdateViewTreeFinish()
        }
    }
    return executed
}

/**
 * 执行任务包装类，用于区分是否为更新 UI 的任务
 */
class KuiklyRenderCoreTaskExecutor(
    private val task: Runnable,
    val isUpdateViewTree: Boolean) {

    fun execute() {
        task.run()
    }

}
