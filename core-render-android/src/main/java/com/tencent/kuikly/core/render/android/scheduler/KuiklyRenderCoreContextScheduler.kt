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

import android.os.ConditionVariable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.MessageQueue
import android.os.Process
import com.tencent.kuikly.core.nvi.NativeBridge
import com.tencent.kuikly.core.render.android.adapter.KuiklyRenderAdapterManager

/**
 * KTV页面执行环境调度器
 */
object KuiklyRenderCoreContextScheduler : IKuiklyRenderCoreScheduler {

    const val THREAD_NAME = "HRContextQueueHandlerThread"

    private val handler by lazy {
        val stackSize = KuiklyRenderAdapterManager.krThreadAdapter?.stackSize() ?: -1L
        Handler(if (stackSize <= 0L) {
            HandlerThread(THREAD_NAME, Process.THREAD_PRIORITY_FOREGROUND).apply { start() }.looper
        } else {
            KRHandlerThread(THREAD_NAME, Process.THREAD_PRIORITY_FOREGROUND, stackSize).apply { start() }.looper
        })
    }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    override fun scheduleTask(delayMs: Long, task: Runnable) {
        handler.postDelayed(task, delayMs)
    }

    /**
     * Runs one bounded task after this context looper has drained normal work.
     *
     * The callback temporarily uses background thread priority so speculative
     * work yields CPU to interactive threads. A task already executing is not
     * forcibly interrupted; callers must keep each callback bounded.
     */
    fun scheduleIdleTask(task: Runnable) {
        scheduleAfterContextIdle {
            mainHandler.post {
                Looper.myQueue().addIdleHandler(
                    MessageQueue.IdleHandler {
                        // Foreground context work may have arrived while the
                        // main queue was draining. Re-admit on context idle.
                        scheduleAfterContextIdle {
                            val threadId = Process.myTid()
                            val previousPriority = Process.getThreadPriority(threadId)
                            try {
                                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                                task.run()
                            } finally {
                                Process.setThreadPriority(previousPriority)
                            }
                        }
                        false
                    }
                )
            }
        }
    }

    private fun scheduleAfterContextIdle(task: Runnable) {
        handler.post {
            Looper.myQueue().addIdleHandler(
                MessageQueue.IdleHandler {
                    task.run()
                    false
                }
            )
        }
    }

    override fun destroy() {
    }

    fun runTaskSyncUnsafely(delayMs: Long = 0, timeout: Long, task: KuiklyRenderCoreTask) {
        handler.runTaskSyncUnsafely(delayMs, timeout, task)
    }

    private val replaceableRegistry = ReplaceableTaskRegistry { handler.removeCallbacks(it) }

    /**
     * Runs [task] on the context queue and blocks the caller up to [timeout],
     * reporting whether the task actually finished in time and how long the
     * caller waited. When [replaceToken] is non-null the posted runnable is
     * registered under that token: a later same-token dispatch (sync or
     * [scheduleReplaceableTask]) evicts it, so a task that outlived its
     * caller's timeout never executes as stale work once superseded.
     */
    fun runTaskSyncUnsafelyWithResult(
        replaceToken: String? = null,
        delayMs: Long = 0,
        timeout: Long,
        task: KuiklyRenderCoreTask,
    ): KuiklyRenderSyncTaskResult {
        require(timeout >= 0) { "timeout must be non-negative" }
        if (Looper.myLooper() == handler.looper) {
            task()
            return KuiklyRenderSyncTaskResult(completedInTime = true, waitMs = 0)
        }
        val blocking = BlockingRunnable(task)
        if (replaceToken != null) {
            replaceableRegistry.registerRunnable(replaceToken, blocking)
        }
        val startNs = System.nanoTime()
        val posted = handler.postDelayed(blocking, delayMs)
        val completed = posted && blocking.waitCompletion(timeout)
        val waitMs = (System.nanoTime() - startNs) / NS_PER_MS
        if (completed && replaceToken != null) {
            replaceableRegistry.unregisterIfCurrent(replaceToken, blocking)
        }
        return KuiklyRenderSyncTaskResult(completedInTime = completed, waitMs = waitMs)
    }

    /**
     * Posts [task] under [replaceToken], replacing any pending (not yet
     * started) task registered under the same token. Last-wins coalescing for
     * high-frequency events such as touch MOVE.
     */
    fun scheduleReplaceableTask(replaceToken: String, delayMs: Long = 0, task: KuiklyRenderCoreTask) {
        handler.postDelayed(replaceableRegistry.register(replaceToken) { task() }, delayMs)
    }

    /** Evicts every pending replaceable task whose token starts with [prefix]. */
    fun evictReplaceableTasksByPrefix(prefix: String) {
        replaceableRegistry.evictByPrefix(prefix)
    }

}

private fun Handler.runTaskSyncUnsafely(delayMs: Long, timeout: Long, task: KuiklyRenderCoreTask): Boolean {
    require(timeout >= 0) { "timeout must be non-negative" }

    if (Looper.myLooper() == looper) {
        task()
        return true
    }

    return BlockingRunnable(task).postAndWait(this, delayMs, timeout)
}

private const val NS_PER_MS = 1_000_000L

/**
 * Outcome of one bounded synchronous dispatch to the context queue.
 */
class KuiklyRenderSyncTaskResult(
    /** false when the task did not finish within the timeout (it may still run later, unless superseded). */
    val completedInTime: Boolean,
    /** Wall-clock time the blocked caller waited, in milliseconds. */
    val waitMs: Long,
)

private class BlockingRunnable(private val mTask: KuiklyRenderCoreTask) : Runnable {

    private val conditionVariable = ConditionVariable()

    override fun run() {
        try {
            mTask.invoke()
        } finally {
            conditionVariable.open()
        }
    }

    /** Returns true when the task finished before [timeout] elapsed. */
    fun waitCompletion(timeout: Long): Boolean {
        return conditionVariable.block(timeout)
    }

    fun postAndWait(handler: Handler, delayMs: Long, timeout: Long): Boolean {
        if (!handler.postDelayed(this, delayMs)) {
            return false
        }

        conditionVariable.block(timeout)
        return true
    }
}
