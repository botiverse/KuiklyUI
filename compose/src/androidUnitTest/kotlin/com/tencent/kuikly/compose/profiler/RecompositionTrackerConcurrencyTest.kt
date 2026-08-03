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

import androidx.compose.runtime.InternalComposeTracingApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(InternalComposeTracingApi::class)
class RecompositionTrackerConcurrencyTest {

    @Test
    fun interleavedTracerEndsPopOnlyTheCallingThreadsEntries() {
        val tracker = startedTracker()
        val aStarted = CountDownLatch(1)
        val bStarted = CountDownLatch(1)
        val allowBEnd = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)

        val threadA = checkedThread("tracer-owner-a", failure) {
            tracker.compositionTracer.traceEventStart(1, 0, 0, "AParent (A.kt:1)")
            tracker.compositionTracer.traceEventStart(2, 0, 0, "AChild (A.kt:2)")
            aStarted.countDown()
            assertTrue(bStarted.await(10, TimeUnit.SECONDS))
            try {
                tracker.compositionTracer.traceEventEnd()
                tracker.compositionTracer.traceEventEnd()

                assertEquals(
                    setOf("AParent", "AChild"),
                    tracker.generateReport().composables.map { it.name }.toSet()
                )
            } finally {
                allowBEnd.countDown()
            }
        }

        val threadB = checkedThread("tracer-owner-b", failure) {
            assertTrue(aStarted.await(10, TimeUnit.SECONDS))
            tracker.compositionTracer.traceEventStart(3, 0, 0, "BParent (B.kt:1)")
            tracker.compositionTracer.traceEventStart(4, 0, 0, "BChild (B.kt:2)")
            bStarted.countDown()
            assertTrue(allowBEnd.await(10, TimeUnit.SECONDS))
            tracker.compositionTracer.traceEventEnd()
            tracker.compositionTracer.traceEventEnd()
        }

        joinChecked(threadA, threadB, failure)
        assertEquals(
            setOf("AParent", "AChild", "BParent", "BChild"),
            tracker.generateReport().composables.map { it.name }.toSet()
        )
        tracker.stop()
    }

    @Test
    fun overlayDepthOnOneThreadDoesNotFilterAnotherThreadsBusinessTrace() {
        val tracker = startedTracker()
        val overlayStarted = CountDownLatch(1)
        val businessFinished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)

        val overlayThread = checkedThread("tracer-overlay", failure) {
            tracker.compositionTracer.traceEventStart(
                1,
                0,
                0,
                "com.tencent.kuikly.compose.profiler.ProfilerOverlaySlot (Overlay.kt:1)"
            )
            overlayStarted.countDown()
            assertTrue(businessFinished.await(10, TimeUnit.SECONDS))
            tracker.compositionTracer.traceEventEnd()
        }

        val businessThread = checkedThread("tracer-business", failure) {
            assertTrue(overlayStarted.await(10, TimeUnit.SECONDS))
            tracker.compositionTracer.traceEventStart(2, 0, 0, "BusinessTrace (Business.kt:1)")
            tracker.compositionTracer.traceEventEnd()
            businessFinished.countDown()
        }

        joinChecked(overlayThread, businessThread, failure)
        assertEquals(
            listOf("BusinessTrace"),
            tracker.generateReport().composables.map { it.name }
        )
        tracker.stop()
    }

    @Test
    fun stopClearsEveryThreadBucketBeforeTrackerRestart() {
        val tracker = RecompositionTracker()
        val collector = CollectingStrategy()
        tracker.addOutputStrategy(collector)
        tracker.start(testConfig())
        assertTrue(tracker.onFrameStart())

        val staleEntriesReady = CyclicBarrier(3)
        val trackerRestarted = CyclicBarrier(3)
        val failure = AtomicReference<Throwable?>(null)

        val threadA = checkedThread("tracer-restart-a", failure) {
            tracker.compositionTracer.traceEventStart(1, 0, 0, "StaleParent (Stale.kt:1)")
            staleEntriesReady.await(10, TimeUnit.SECONDS)
            trackerRestarted.await(10, TimeUnit.SECONDS)
            tracker.compositionTracer.traceEventStart(2, 0, 0, "FreshA (Fresh.kt:1)")
            tracker.compositionTracer.traceEventEnd()
        }

        val threadB = checkedThread("tracer-restart-b", failure) {
            tracker.compositionTracer.traceEventStart(
                3,
                0,
                0,
                "com.tencent.kuikly.compose.profiler.StaleOverlay (Stale.kt:2)"
            )
            staleEntriesReady.await(10, TimeUnit.SECONDS)
            trackerRestarted.await(10, TimeUnit.SECONDS)
            tracker.compositionTracer.traceEventStart(4, 0, 0, "FreshB (Fresh.kt:2)")
            tracker.compositionTracer.traceEventEnd()
        }

        staleEntriesReady.await(10, TimeUnit.SECONDS)
        tracker.stop()
        // A callback already holding a reference to the old tracer must not repopulate the
        // stopped tracker's cleared bucket before a restart.
        tracker.compositionTracer.traceEventStart(99, 0, 0, "LateAfterStop (Late.kt:1)")
        tracker.start(testConfig())
        assertTrue(tracker.onFrameStart())
        trackerRestarted.await(10, TimeUnit.SECONDS)

        joinChecked(threadA, threadB, failure)
        tracker.compositionTracer.traceEventEnd()
        tracker.onFrameEnd(0)
        val freshEvents = collector.events.filterIsInstance<ComposableRecomposedEvent>()
        assertEquals(setOf("FreshA", "FreshB"), freshEvents.map { it.composableName }.toSet())
        assertTrue(freshEvents.all { it.parentName == null })
        tracker.stop()
    }

    private fun startedTracker(): RecompositionTracker = RecompositionTracker().also { tracker ->
        tracker.start(testConfig())
        assertTrue(tracker.onFrameStart())
    }

    private fun testConfig(): RecompositionConfig = RecompositionConfig(
        enableStateTracking = false,
        includeFrameworkComposables = true,
        enableLog = false,
        enableFile = false,
        enableBuiltinFilters = false
    )

    private fun checkedThread(
        name: String,
        failure: AtomicReference<Throwable?>,
        block: () -> Unit
    ): Thread = thread(start = true, name = name) {
        try {
            block()
        } catch (throwable: Throwable) {
            failure.compareAndSet(null, throwable)
        }
    }

    private fun joinChecked(
        first: Thread,
        second: Thread,
        failure: AtomicReference<Throwable?>
    ) {
        first.join(TimeUnit.SECONDS.toMillis(30))
        second.join(TimeUnit.SECONDS.toMillis(30))
        assertFalse(first.isAlive, "${first.name} did not finish")
        assertFalse(second.isAlive, "${second.name} did not finish")
        failure.get()?.let { throw it }
    }

    private class CollectingStrategy : RecompositionOutputStrategy {
        val events = mutableListOf<RecompositionEvent>()

        override fun onFrameComplete(events: List<RecompositionEvent>) {
            this.events += events
        }
    }
}
