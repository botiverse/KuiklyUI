package com.tencent.kuikly.core.render.android.scheduler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class KuiklyRenderCoreUISchedulerTaskBatchTest {

    @Test
    fun nullQueueEntriesAreSkippedWithoutDroppingValidTasks() {
        val executed = mutableListOf<String>()
        val nullIndexes = mutableListOf<Int>()
        var updateViewTreeFinishCount = 0

        val executedCount =
            executeKuiklyRenderCoreTaskBatch(
                tasks =
                    listOf(
                        KuiklyRenderCoreTaskExecutor(Runnable { executed += "first" }, false),
                        null,
                        KuiklyRenderCoreTaskExecutor(Runnable { executed += "second" }, true)
                    ),
                onNullTask = nullIndexes::add,
                onUpdateViewTreeFinish = { updateViewTreeFinishCount++ }
            )

        assertEquals(listOf("first", "second"), executed)
        assertEquals(listOf(1), nullIndexes)
        assertEquals(2, executedCount)
        assertEquals(1, updateViewTreeFinishCount)
    }

    @Test
    fun taskFailureStillStopsTheBatchForTheSchedulerExceptionBoundary() {
        val executed = mutableListOf<String>()

        val error =
            runCatching {
                executeKuiklyRenderCoreTaskBatch(
                    tasks =
                        listOf(
                            KuiklyRenderCoreTaskExecutor(Runnable { executed += "first" }, false),
                            KuiklyRenderCoreTaskExecutor(Runnable { error("boom") }, false),
                            KuiklyRenderCoreTaskExecutor(Runnable { executed += "third" }, false)
                        )
                )
            }.exceptionOrNull()

        assertEquals("boom", error?.message)
        assertEquals(listOf("first"), executed)
    }

    @Test
    fun concurrentProducersDoNotCorruptOrDropQueueEntries() {
        val queue = KuiklyRenderCoreTaskQueue()
        val producerCount = 4
        val tasksPerProducer = 500
        val start = CountDownLatch(1)
        val done = CountDownLatch(producerCount)
        val executor = Executors.newFixedThreadPool(producerCount)
        val allAccepted = AtomicBoolean(true)

        repeat(producerCount) { producer ->
            executor.execute {
                try {
                    start.await()
                    repeat(tasksPerProducer) { index ->
                        if (!queue.enqueue(
                            KuiklyRenderCoreTaskExecutor(
                                Runnable {},
                                (producer + index) % 2 == 0
                            )
                        )) {
                            allAccepted.set(false)
                        }
                    }
                } finally {
                    done.countDown()
                }
            }
        }

        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        executor.shutdownNow()

        assertTrue(allAccepted.get())
        assertTrue(queue.transferContextTasksToMain())
        val tasks = queue.takeMainTasks()
        assertEquals(producerCount * tasksPerProducer, tasks.size)
        assertTrue(tasks.all { it != null })
    }

    @Test
    fun destroyClearsPendingWorkAndRejectsNewWork() {
        val queue = KuiklyRenderCoreTaskQueue()
        var drained = false
        var waited = false

        assertTrue(queue.enqueue(KuiklyRenderCoreTaskExecutor(Runnable {}, false)))
        assertTrue(queue.installDrainBlock { drained = true })
        assertTrue(queue.setMainThreadWaitBlock { waited = true })
        assertTrue(queue.transferContextTasksToMain())

        queue.destroy()

        assertTrue(queue.destroyed)
        assertFalse(queue.enqueue(KuiklyRenderCoreTaskExecutor(Runnable {}, false)))
        assertFalse(queue.installDrainBlock { drained = true })
        assertFalse(queue.setMainThreadWaitBlock { waited = true })
        assertFalse(queue.transferContextTasksToMain())
        assertTrue(queue.takeMainTasks().isEmpty())
        queue.takeDrainBlock()?.invoke(false)
        queue.takeMainThreadWaitBlock()?.invoke()
        assertFalse(drained)
        assertFalse(waited)
    }

    @Test
    fun takingDrainBlockAllowsConcurrentFollowUpBatchToSchedule() {
        val queue = KuiklyRenderCoreTaskQueue()
        val first: (Boolean) -> Unit = {}
        val second: (Boolean) -> Unit = {}

        assertTrue(queue.installDrainBlock(first))
        assertFalse(queue.installDrainBlock(second))
        assertTrue(queue.takeDrainBlock() === first)
        assertTrue(queue.installDrainBlock(second))
        assertTrue(queue.takeDrainBlock() === second)
    }
}
