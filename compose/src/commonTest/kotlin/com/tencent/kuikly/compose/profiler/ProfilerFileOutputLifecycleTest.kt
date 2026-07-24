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

import com.tencent.kuikly.compose.profiler.output.FileOutputStrategy
import com.tencent.kuikly.compose.profiler.output.ProfilerFileIoDispatcher
import com.tencent.kuikly.compose.profiler.output.ProfilerFileIoResult
import com.tencent.kuikly.compose.profiler.output.ProfilerFileOperation
import com.tencent.kuikly.compose.profiler.output.ProfilerFileOperationKind
import com.tencent.kuikly.core.module.FileModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProfilerFileOutputLifecycleTest {

    @Test
    fun liveModuleRegistryFallsBackWhenThePreferredPagerIsDestroyed() {
        val registry = ProfilerFileModuleRegistry()
        val moduleA = fileModule("pager-a")
        val moduleB = fileModule("pager-b")

        assertSame(moduleA, registry.register(moduleA))
        assertSame(moduleB, registry.register(moduleB))
        assertEquals(2, registry.size())
        assertSame(moduleB, registry.current())

        assertSame(moduleA, registry.unregister(moduleB))
        assertEquals(1, registry.size())
        assertSame(moduleA, registry.current())

        assertNull(registry.unregister(moduleA))
        assertEquals(0, registry.size())
        assertNull(registry.current())
    }

    @Test
    fun registeringTheSamePagerPromotesItWithoutDuplicatingOwnership() {
        val registry = ProfilerFileModuleRegistry()
        val moduleA = fileModule("pager-a")
        val moduleB = fileModule("pager-b")

        registry.register(moduleA)
        registry.register(moduleB)
        registry.register(moduleA)

        assertEquals(2, registry.size())
        assertSame(moduleA, registry.current())
        assertSame(moduleB, registry.unregister(moduleA))
    }

    @Test
    fun stalePagerCancellationRetriesTheSameWriteOnTheLiveFallback() {
        val moduleA = fileModule("pager-a")
        val moduleB = fileModule("pager-b")
        var currentModule: FileModule? = moduleA
        val dispatcher = RecordingDispatcher()
        val strategy = FileOutputStrategy({ currentModule }, dispatcher)

        strategy.activate("session-a", 100L)
        assertDispatch(dispatcher.dispatches[0], moduleA, ProfilerFileOperationKind.WRITE, "profiler_frames.jsonl")
        assertTrue(dispatcher.dispatches[0].operation.content.contains("session-a"))

        currentModule = moduleB
        dispatcher.complete(0, ProfilerFileIoResult.RetryableFailure("pager bridge unavailable"))

        assertEquals(2, dispatcher.dispatches.size)
        assertSame(dispatcher.dispatches[0].operation, dispatcher.dispatches[1].operation)
        assertSame(moduleB, dispatcher.dispatches[1].module)

        dispatcher.complete(1, ProfilerFileIoResult.Success)
        assertEquals(3, dispatcher.dispatches.size)
        assertDispatch(dispatcher.dispatches[2], moduleB, ProfilerFileOperationKind.WRITE, "profiler_report.json")
        assertEquals("", dispatcher.dispatches[2].operation.content)
        dispatcher.complete(2, ProfilerFileIoResult.Success)

        strategy.deactivate(report("session-a", 100L))
        assertEquals(4, dispatcher.dispatches.size)
        assertDispatch(dispatcher.dispatches[3], moduleB, ProfilerFileOperationKind.WRITE, "profiler_report.json")
        assertTrue(dispatcher.dispatches[3].operation.content.contains("\"sessionId\":\"session-a\""))
    }

    @Test
    fun queuedWritesWaitForAFileModuleInsteadOfBeingDropped() {
        var currentModule: FileModule? = null
        val dispatcher = RecordingDispatcher()
        val strategy = FileOutputStrategy({ currentModule }, dispatcher)

        strategy.activate("session-a", 100L)
        assertTrue(dispatcher.dispatches.isEmpty())

        currentModule = fileModule("pager-live")
        strategy.onFileModuleChanged()

        assertEquals(1, dispatcher.dispatches.size)
        assertSame(currentModule, dispatcher.dispatches.single().module)
        assertEquals("profiler_frames.jsonl", dispatcher.dispatches.single().operation.filename)
    }

    @Test
    fun resetRewritesTheSessionHeaderAndRejectsFramesFromTheOldGeneration() {
        val module = fileModule("pager-live")
        val dispatcher = RecordingDispatcher()
        val strategy = FileOutputStrategy({ module }, dispatcher)

        strategy.activate("session-old", 100L)
        dispatcher.complete(0, ProfilerFileIoResult.Success)
        dispatcher.complete(1, ProfilerFileIoResult.Success)

        strategy.onFrameComplete(frame(frameId = 1L, startTimestampMs = 150L))
        strategy.onSessionReset("session-new", 200L)

        assertEquals(3, dispatcher.dispatches.size)
        assertTrue(dispatcher.dispatches[2].operation.content.contains("session-new"))
        assertTrue(dispatcher.dispatches[2].operation.content.contains("200"))
        dispatcher.complete(2, ProfilerFileIoResult.Success)
        dispatcher.complete(3, ProfilerFileIoResult.Success)

        strategy.onFrameComplete(frame(frameId = 2L, startTimestampMs = 150L))
        strategy.onFrameComplete(frame(frameId = 3L, startTimestampMs = 210L))
        strategy.deactivate(report("session-new", 200L))

        assertEquals(5, dispatcher.dispatches.size)
        val append = dispatcher.dispatches[4]
        assertDispatch(append, module, ProfilerFileOperationKind.APPEND, "profiler_frames.jsonl")
        assertTrue(append.operation.content.contains("\"frameId\":3"))
        assertTrue(!append.operation.content.contains("\"frameId\":2"))
        dispatcher.complete(4, ProfilerFileIoResult.Success)

        assertEquals(6, dispatcher.dispatches.size)
        assertEquals("profiler_report.json", dispatcher.dispatches[5].operation.filename)
        assertTrue(dispatcher.dispatches[5].operation.content.contains("session-new"))
    }

    @Test
    fun reportWritesRemainSerializedAcrossStopAndPostStopExport() {
        val module = fileModule("pager-live")
        val dispatcher = RecordingDispatcher()
        val strategy = FileOutputStrategy({ module }, dispatcher)

        strategy.activate("session-a", 100L)
        dispatcher.complete(0, ProfilerFileIoResult.Success)
        dispatcher.complete(1, ProfilerFileIoResult.Success)

        strategy.deactivate(report("session-a", 100L))
        strategy.writeReport(report("session-a", 101L))

        assertEquals(3, dispatcher.dispatches.size)
        assertTrue(dispatcher.dispatches[2].operation.content.contains("session-a"))
        dispatcher.complete(2, ProfilerFileIoResult.Success)

        assertEquals(4, dispatcher.dispatches.size)
        assertTrue(dispatcher.dispatches[3].operation.content.contains("\"startTimestampMs\":101"))
    }

    @Test
    fun retryableFailureRetriesOnTheSameLiveModuleInsteadOfStalling() {
        val module = fileModule("pager-live")
        val dispatcher = RecordingDispatcher()
        val strategy = FileOutputStrategy({ module }, dispatcher)

        strategy.activate("session-a", 100L)
        dispatcher.complete(0, ProfilerFileIoResult.RetryableFailure("context unavailable"))

        assertEquals(2, dispatcher.dispatches.size)
        assertSame(module, dispatcher.dispatches[1].module)
        assertSame(dispatcher.dispatches[0].operation, dispatcher.dispatches[1].operation)

        dispatcher.complete(1, ProfilerFileIoResult.Success)
        assertEquals(3, dispatcher.dispatches.size)
        assertEquals("profiler_report.json", dispatcher.dispatches[2].operation.filename)
    }

    @Test
    fun retryableFailureExhaustionSurfacesOneTerminalReportFailure() {
        val module = fileModule("pager-live")
        val dispatcher = RecordingDispatcher()
        val strategy = FileOutputStrategy({ module }, dispatcher)
        val completions = mutableListOf<RecompositionProfilerFileOutputResult>()

        strategy.activate("session-a", 100L)
        dispatcher.complete(0, ProfilerFileIoResult.Success)
        dispatcher.complete(1, ProfilerFileIoResult.Success)
        strategy.writeReport(report("session-a", 100L), completions::add)

        dispatcher.complete(2, ProfilerFileIoResult.RetryableFailure("context unavailable"))
        dispatcher.complete(3, ProfilerFileIoResult.RetryableFailure("context unavailable"))
        dispatcher.complete(4, ProfilerFileIoResult.RetryableFailure("context unavailable"))

        assertEquals(5, dispatcher.dispatches.size)
        assertEquals(1, completions.size)
        val failure = completions.single()
        assertTrue(failure is RecompositionProfilerFileOutputResult.Failure)
        assertTrue(failure.reason.contains("exhausted after 3 attempts"))
    }

    @Test
    fun reportCompletionWaitsForQueuedFramesAndNativeReportCommit() {
        val module = fileModule("pager-live")
        val dispatcher = RecordingDispatcher()
        val strategy = FileOutputStrategy({ module }, dispatcher)
        val completions = mutableListOf<RecompositionProfilerFileOutputResult>()

        strategy.activate("session-a", 100L)
        dispatcher.complete(0, ProfilerFileIoResult.Success)
        dispatcher.complete(1, ProfilerFileIoResult.Success)
        strategy.onFrameComplete(frame(frameId = 1L, startTimestampMs = 150L))
        strategy.deactivate(report("session-a", 100L), completions::add)

        assertEquals(3, dispatcher.dispatches.size)
        assertEquals("profiler_frames.jsonl", dispatcher.dispatches[2].operation.filename)
        assertTrue(completions.isEmpty())

        dispatcher.complete(2, ProfilerFileIoResult.Success)
        assertEquals(4, dispatcher.dispatches.size)
        assertEquals("profiler_report.json", dispatcher.dispatches[3].operation.filename)
        assertTrue(completions.isEmpty())

        dispatcher.complete(3, ProfilerFileIoResult.Success)
        assertEquals(
            RecompositionProfilerFileOutputResult.Success("session-a"),
            completions.single()
        )
    }

    @Test
    fun earlierFrameFailureMakesACommittedReportArtifactSetFail() {
        val module = fileModule("pager-live")
        val dispatcher = RecordingDispatcher()
        val strategy = FileOutputStrategy({ module }, dispatcher)
        val completions = mutableListOf<RecompositionProfilerFileOutputResult>()

        strategy.activate("session-a", 100L)
        dispatcher.complete(0, ProfilerFileIoResult.Success)
        dispatcher.complete(1, ProfilerFileIoResult.Success)
        strategy.onFrameComplete(frame(frameId = 1L, startTimestampMs = 150L))
        strategy.deactivate(report("session-a", 100L), completions::add)

        dispatcher.complete(2, ProfilerFileIoResult.TerminalFailure("append denied"))
        assertTrue(completions.isEmpty())
        dispatcher.complete(3, ProfilerFileIoResult.Success)

        val failure = completions.single()
        assertTrue(failure is RecompositionProfilerFileOutputResult.Failure)
        assertTrue(failure.reason.contains("earlier profiler file operation failed"))
        assertTrue(failure.reason.contains("append denied"))
    }

    @Test
    fun reportOnlyTerminalFailureCanRetrySuccessfullyInTheSameSession() {
        val module = fileModule("pager-live")
        val dispatcher = RecordingDispatcher()
        val strategy = FileOutputStrategy({ module }, dispatcher)
        val firstCompletions = mutableListOf<RecompositionProfilerFileOutputResult>()
        val secondCompletions = mutableListOf<RecompositionProfilerFileOutputResult>()

        strategy.activate("session-a", 100L)
        dispatcher.complete(0, ProfilerFileIoResult.Success)
        dispatcher.complete(1, ProfilerFileIoResult.Success)

        strategy.writeReport(report("session-a", 100L), firstCompletions::add)
        dispatcher.complete(2, ProfilerFileIoResult.TerminalFailure("report denied"))

        assertEquals(1, firstCompletions.size)
        val firstFailure = firstCompletions.single()
        assertTrue(firstFailure is RecompositionProfilerFileOutputResult.Failure)
        assertEquals("report denied", firstFailure.reason)

        strategy.writeReport(report("session-a", 100L), secondCompletions::add)
        assertEquals(4, dispatcher.dispatches.size)
        assertTrue(secondCompletions.isEmpty())
        dispatcher.complete(3, ProfilerFileIoResult.Success)

        assertEquals(1, firstCompletions.size)
        assertEquals(
            listOf<RecompositionProfilerFileOutputResult>(
                RecompositionProfilerFileOutputResult.Success("session-a")
            ),
            secondCompletions
        )
    }

    @Test
    fun newSessionSupersedesOldReportCompletionExactlyOnce() {
        val module = fileModule("pager-live")
        val dispatcher = RecordingDispatcher()
        val strategy = FileOutputStrategy({ module }, dispatcher)
        val oldCompletions = mutableListOf<RecompositionProfilerFileOutputResult>()
        val newCompletions = mutableListOf<RecompositionProfilerFileOutputResult>()

        strategy.activate("session-old", 100L)
        dispatcher.complete(0, ProfilerFileIoResult.Success)
        dispatcher.complete(1, ProfilerFileIoResult.Success)
        strategy.writeReport(report("session-old", 100L), oldCompletions::add)

        strategy.activate("session-new", 200L)
        assertEquals(1, oldCompletions.size)
        assertTrue(oldCompletions.single() is RecompositionProfilerFileOutputResult.Failure)

        dispatcher.complete(2, ProfilerFileIoResult.Success)
        assertEquals(1, oldCompletions.size)
        dispatcher.complete(3, ProfilerFileIoResult.Success)
        dispatcher.complete(4, ProfilerFileIoResult.Success)

        strategy.deactivate(report("session-new", 200L), newCompletions::add)
        dispatcher.complete(5, ProfilerFileIoResult.Success)

        assertEquals(
            RecompositionProfilerFileOutputResult.Success("session-new"),
            newCompletions.single()
        )
    }

    @Test
    fun lateStopFromOldSessionCannotDeactivateTheNewSession() {
        val module = fileModule("pager-live")
        val dispatcher = RecordingDispatcher()
        val strategy = FileOutputStrategy({ module }, dispatcher)
        val oldCompletions = mutableListOf<RecompositionProfilerFileOutputResult>()
        val newCompletions = mutableListOf<RecompositionProfilerFileOutputResult>()

        strategy.activate("session-old", 100L)
        dispatcher.complete(0, ProfilerFileIoResult.Success)
        dispatcher.complete(1, ProfilerFileIoResult.Success)
        strategy.activate("session-new", 200L)

        strategy.deactivate(report("session-old", 100L), oldCompletions::add)
        assertTrue(oldCompletions.single() is RecompositionProfilerFileOutputResult.Failure)

        dispatcher.complete(2, ProfilerFileIoResult.Success)
        dispatcher.complete(3, ProfilerFileIoResult.Success)
        strategy.onFrameComplete(frame(frameId = 2L, startTimestampMs = 250L))
        strategy.deactivate(report("session-new", 200L), newCompletions::add)

        assertEquals("profiler_frames.jsonl", dispatcher.dispatches[4].operation.filename)
        dispatcher.complete(4, ProfilerFileIoResult.Success)
        dispatcher.complete(5, ProfilerFileIoResult.Success)
        assertEquals(
            RecompositionProfilerFileOutputResult.Success("session-new"),
            newCompletions.single()
        )
    }

    @Test
    fun twoConsecutiveSessionsEachReceiveTheirOwnCommitAcknowledgement() {
        val module = fileModule("pager-live")
        val dispatcher = RecordingDispatcher()
        val strategy = FileOutputStrategy({ module }, dispatcher)
        val completions = mutableListOf<RecompositionProfilerFileOutputResult>()

        strategy.activate("session-one", 100L)
        dispatcher.complete(0, ProfilerFileIoResult.Success)
        dispatcher.complete(1, ProfilerFileIoResult.Success)
        strategy.deactivate(report("session-one", 100L), completions::add)
        assertTrue(completions.isEmpty())
        dispatcher.complete(2, ProfilerFileIoResult.Success)

        strategy.activate("session-two", 200L)
        dispatcher.complete(3, ProfilerFileIoResult.Success)
        dispatcher.complete(4, ProfilerFileIoResult.Success)
        strategy.deactivate(report("session-two", 200L), completions::add)
        assertEquals(1, completions.size)
        dispatcher.complete(5, ProfilerFileIoResult.Success)

        assertEquals(
            listOf<RecompositionProfilerFileOutputResult>(
                RecompositionProfilerFileOutputResult.Success("session-one"),
                RecompositionProfilerFileOutputResult.Success("session-two")
            ),
            completions
        )
        assertFalse(completions.any { it is RecompositionProfilerFileOutputResult.Failure })
    }

    private fun fileModule(pagerId: String): FileModule = FileModule().also { it.pagerId = pagerId }

    private fun frame(frameId: Long, startTimestampMs: Long): List<RecompositionEvent> =
        listOf(
            RecompositionFrameStartEvent(startTimestampMs, frameId),
            RecompositionFrameEndEvent(startTimestampMs + 1L, frameId, durationMs = 1L, recomposedCount = 0)
        )

    private fun report(sessionId: String, startTimestampMs: Long): RecompositionReport =
        RecompositionReport(
            sessionId = sessionId,
            startTimestampMs = startTimestampMs,
            durationMs = 10L,
            totalFrames = 1L,
            totalRecompositions = 1,
            composables = emptyList(),
            hotspots = emptyList(),
            stateChanges = emptyList()
        )

    private fun assertDispatch(
        dispatch: RecordingDispatcher.Dispatch,
        module: FileModule,
        kind: ProfilerFileOperationKind,
        filename: String
    ) {
        assertSame(module, dispatch.module)
        assertEquals(kind, dispatch.operation.kind)
        assertEquals(filename, dispatch.operation.filename)
    }

    private class RecordingDispatcher : ProfilerFileIoDispatcher {
        data class Dispatch(
            val module: FileModule,
            val operation: ProfilerFileOperation,
            val completion: (ProfilerFileIoResult) -> Unit
        )

        val dispatches = mutableListOf<Dispatch>()

        override fun dispatch(
            module: FileModule,
            operation: ProfilerFileOperation,
            completion: (ProfilerFileIoResult) -> Unit
        ) {
            dispatches.add(Dispatch(module, operation, completion))
        }

        fun complete(index: Int, result: ProfilerFileIoResult) {
            dispatches[index].completion(result)
        }
    }
}
