#!/usr/bin/env python3
"""Fail closed if profiler file output can bind to a dead Pager or lose session files."""

from __future__ import annotations

import argparse
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PATHS = {
    "container": ROOT / "compose/src/commonMain/kotlin/com/tencent/kuikly/compose/ComposeContainer.kt",
    "profiler": ROOT / (
        "compose/src/commonMain/kotlin/com/tencent/kuikly/compose/profiler/"
        "RecompositionProfiler.kt"
    ),
    "strategy": ROOT / (
        "compose/src/commonMain/kotlin/com/tencent/kuikly/compose/profiler/output/"
        "FileOutputStrategy.kt"
    ),
    "completion_result": ROOT / (
        "compose/src/commonMain/kotlin/com/tencent/kuikly/compose/profiler/"
        "RecompositionProfilerFileOutputResult.kt"
    ),
    "tracker": ROOT / (
        "compose/src/commonMain/kotlin/com/tencent/kuikly/compose/profiler/"
        "RecompositionTracker.kt"
    ),
    "output_interface": ROOT / (
        "compose/src/commonMain/kotlin/com/tencent/kuikly/compose/profiler/"
        "RecompositionOutputStrategy.kt"
    ),
    "file_module": ROOT / "core/src/commonMain/kotlin/com/tencent/kuikly/core/module/FileModule.kt",
    "android": ROOT / (
        "core-render-android/src/main/java/com/tencent/kuikly/core/render/android/expand/module/"
        "KRFileModule.kt"
    ),
    "ios": ROOT / "core-render-ios/Extension/Modules/KRFileModule.m",
    "android_tests": ROOT / (
        "core-render-android/src/test/java/com/tencent/kuikly/core/render/android/expand/module/"
        "KRFileModuleTest.kt"
    ),
    "ohos": ROOT / (
        "core-render-ohos/src/main/cpp/libohos_render/expand/modules/file/"
        "KRFileModule.cpp"
    ),
    "tests": ROOT / (
        "compose/src/commonTest/kotlin/com/tencent/kuikly/compose/profiler/"
        "ProfilerFileOutputLifecycleTest.kt"
    ),
    "workflow": ROOT / ".github/workflows/compose-pr.yml",
}


def compact(source: str) -> str:
    return re.sub(r"\s+", "", source)


def between(source: str, start: str, end: str) -> str:
    start_index = source.find(start)
    if start_index < 0:
        raise AssertionError(f"missing region start: {start}")
    end_index = source.find(end, start_index + len(start))
    if end_index < 0:
        raise AssertionError(f"missing region end after {start}: {end}")
    return source[start_index:end_index]


def require_tokens(label: str, source: str, tokens: tuple[str, ...]) -> None:
    compacted = compact(source)
    for token in tokens:
        if compact(token) not in compacted:
            raise AssertionError(f"{label} missing contract token: {token}")


def assert_contract(sources: dict[str, str]) -> None:
    container = sources["container"]
    require_tokens(
        "ComposeContainer",
        container,
        (
            "private var profilerFileModule: FileModule? = null",
            "RecompositionProfiler.registerFileModule(module)",
            "RecompositionProfiler.unregisterFileModule(it)",
            "unregisterProfilerFileModule()",
        ),
    )
    destroy = between(container, "override fun pageWillDestroy()", "private fun updateLifecycleState")
    unregister_index = destroy.find("unregisterProfilerFileModule()")
    dispose_index = destroy.find("dispose()")
    if unregister_index < 0 or dispose_index < 0 or unregister_index > dispose_index:
        raise AssertionError("ComposeContainer must unregister FileModule before Pager dispose")

    profiler = sources["profiler"]
    require_tokens(
        "RecompositionProfiler",
        profiler,
        (
            "private val fileModuleRegistry = ProfilerFileModuleRegistry()",
            "currentFileModule = fileModuleRegistry.register(fileModule)",
            "currentFileModule = fileModuleRegistry.unregister(fileModule)",
            "fileStrategy?.onFileModuleChanged()",
            "FileOutputStrategy(fileModuleProvider = { currentFileModule })",
            "newTracker.addOutputStrategy(strategy)",
            "strategy.activate(newTracker.sessionId, newTracker.startTimestampMs)",
            "fun stop(completion: (RecompositionProfilerFileOutputResult) -> Unit)",
            "strategy.deactivate(report, completion)",
            "fun getReport(",
            "completion: (RecompositionProfilerFileOutputResult) -> Unit",
            "strategy.writeReport(finalReport, completion)",
        ),
    )
    start_stop = between(profiler, "fun start()", "fun getReport(")
    if "fileStrategy = null" in start_stop:
        raise AssertionError("FileOutputStrategy must survive stop/start to serialize native writes")

    strategy = sources["strategy"]
    require_tokens(
        "FileOutputStrategy",
        strategy,
        (
            "private val fileModuleProvider: () -> FileModule?",
            "private val pendingFileOperations = mutableListOf<ProfilerFileOperation>()",
            "private var inFlightFileOperation: ProfilerFileOperation? = null",
            "private var inFlightFileModule: FileModule? = null",
            "val operationId: String = nextProfilerFileOperationId()",
            "module.writeFile(\n                            operation.filename,\n                            operation.content,\n                            operation.operationId,",
            "module.appendFile(\n                            operation.filename,\n                            operation.content,\n                            operation.operationId,",
            "if (operation != null && inFlightFileModule !== currentModule)",
            "if (inFlightFileOperation !== operation || inFlightFileModule !== module)",
            "ProfilerFileIoResult.RetryableFailure(\"pager bridge unavailable\")",
            "pendingFileOperations.add(0, operation)",
            "operation.sameModuleRetryCount < MAX_SAME_MODULE_RETRIES",
            "retryable file output exhausted after",
            "takeCompletionLocked(operation, completionResult)",
            "profiler session superseded before native file output completed",
            "sessionId != report.sessionId",
            "generationArtifactFailures",
            "operation.filename == FILE_FRAMES",
            "internal fun onFileModuleChanged()",
            "override fun onSessionReset(sessionId: String, startTimestampMs: Long)",
            "pendingFileOperations.clear()",
            "FILE_REPORT, \"\", generation",
        ),
    )
    if re.search(r"private\s+val\s+fileModule\s*:\s*FileModule", strategy):
        raise AssertionError("FileOutputStrategy may not retain one fixed Pager FileModule")
    if "if (!cancel) block()" in strategy or "if(!cancel)block()" in compact(strategy):
        raise AssertionError("Pager cancellation must retry; it may not silently drop file I/O")
    if strategy.count("nextProfilerFileOperationId()") != 2:
        raise AssertionError("operation id must be created once per operation, never per dispatch/retry")

    file_module = sources["file_module"]
    require_tokens(
        "FileModule",
        file_module,
        (
            'private const val PARAM_OPERATION_ID = "operationId"',
            "writeFileInternal(filename, content, operationId, callback)",
            "appendFileInternal(filename, content, operationId, callback)",
        ),
    )
    if file_module.count("put(PARAM_OPERATION_ID, operationId)") != 2:
        raise AssertionError("FileModule must serialize operationId for both write and append")

    require_tokens(
        "RecompositionProfilerFileOutputResult",
        sources["completion_result"],
        (
            "sealed class RecompositionProfilerFileOutputResult",
            "data class Success",
            "data class Failure",
            "val reason: String",
        ),
    )

    require_tokens(
        "RecompositionTracker",
        sources["tracker"],
        (
            "strategy.onReset()",
            "strategy is RecompositionSessionOutputStrategy",
            "strategy.onSessionReset(sessionId, startTimestampMs)",
        ),
    )
    require_tokens(
        "RecompositionOutputStrategy",
        sources["output_interface"],
        (
            "internal interface RecompositionSessionOutputStrategy",
            "fun onSessionReset(sessionId: String, startTimestampMs: Long)",
        ),
    )

    android_write = between(sources["android"], "private fun writeFile(", "companion object")
    if "content.isNullOrEmpty()" in android_write:
        raise AssertionError("Android overwrite must allow empty content to truncate stale report")
    if "if (filename.isNullOrEmpty())" not in android_write:
        raise AssertionError("Android overwrite must still reject a missing filename")
    android_append = between(sources["android"], "private fun appendFile(", "private fun writeFile(")
    if "content.isNullOrEmpty()" not in android_append:
        raise AssertionError("Android append must continue rejecting empty batches")
    require_tokens(
        "Android profiler file queue",
        sources["android"],
        (
            "Executors.newSingleThreadExecutor",
            'private const val PARAM_OPERATION_ID = "operationId"',
            "private val COMPLETED_OPERATION_IDS = HashSet<String>()",
        ),
    )
    for label, region in (("write", android_write), ("append", android_append)):
        require_tokens(
            f"Android {label} idempotent FIFO",
            region,
            (
                "FILE_EXECUTOR.execute",
                "COMPLETED_OPERATION_IDS.contains(operationId)",
                "COMPLETED_OPERATION_IDS.add(operationId)",
            ),
        )
        if "Thread {" in region:
            raise AssertionError(f"Android {label} may not start a raw per-call thread")
    require_tokens(
        "Android native file queue tests",
        sources["android_tests"],
        (
            "duplicateOperationIdAcrossPagerModulesAppendsOnlyOnce",
            "moduleA.call(\"appendFile\", params, callback)",
            "moduleB.call(\"appendFile\", params, callback)",
            'assertEquals("frame\\n", file.readText(Charsets.UTF_8))',
        ),
    )

    ios_append = between(sources["ios"], "- (void)appendFile:", "- (void)writeFile:")
    ios_write = between(sources["ios"], "- (void)writeFile:", "@end")
    require_tokens(
        "iOS profiler file queue",
        sources["ios"],
        (
            'dispatch_queue_create("com.tencent.kuikly.profiler.file", DISPATCH_QUEUE_SERIAL)',
            "KRCompletedProfilerOperationIds",
        ),
    )
    for label, region in (("write", ios_write), ("append", ios_append)):
        require_tokens(
            f"iOS {label} idempotent FIFO",
            region,
            (
                "dispatch_async(KRProfilerFileQueue()",
                "[completedOperationIds containsObject:operationId]",
                "[completedOperationIds addObject:operationId]",
            ),
        )
        if "dispatch_get_global_queue" in region:
            raise AssertionError(f"iOS {label} may not use a concurrent global queue")

    ohos_write = between(sources["ohos"], "void KRFileModule::WriteFile", "void KRFileModule::AppendFile")
    if "content.empty()" in ohos_write:
        raise AssertionError("OHOS overwrite must allow empty content to truncate stale report")
    if "if (filename.empty())" not in ohos_write:
        raise AssertionError("OHOS overwrite must still reject a missing filename")
    ohos_append = between(sources["ohos"], "void KRFileModule::AppendFile", "void KRFileModule::GetFilesDir")
    if "content.empty()" not in ohos_append:
        raise AssertionError("OHOS append must continue rejecting empty batches")
    require_tokens(
        "OHOS profiler file queue",
        sources["ohos"],
        (
            "class ProfilerFileWorker",
            "std::condition_variable condition_",
            "std::deque<Task> tasks_",
            "std::unordered_set<std::string> completedOperationIds_",
        ),
    )
    for label, region in (("write", ohos_write), ("append", ohos_append)):
        require_tokens(
            f"OHOS {label} idempotent FIFO",
            region,
            (
                "ProfilerFileWorker::Instance().Enqueue",
                "completedOperationIds.count(operationId)",
                "completedOperationIds.insert(operationId)",
            ),
        )
    if ".detach()" in sources["ohos"]:
        raise AssertionError("OHOS file operations may not use detached per-call threads")

    require_tokens(
        "profiler lifecycle tests",
        sources["tests"],
        (
            "liveModuleRegistryFallsBackWhenThePreferredPagerIsDestroyed",
            "stalePagerCancellationRetriesTheSameWriteOnTheLiveFallback",
            "moduleChangeRecoversAnInFlightFrameWriteWhoseCallbackNeverReturns",
            "queuedWritesWaitForAFileModuleInsteadOfBeingDropped",
            "resetRewritesTheSessionHeaderAndRejectsFramesFromTheOldGeneration",
            "reportWritesRemainSerializedAcrossStopAndPostStopExport",
            "retryableFailureRetriesOnTheSameLiveModuleInsteadOfStalling",
            "retryableFailureExhaustionSurfacesOneTerminalReportFailure",
            "reportCompletionWaitsForQueuedFramesAndNativeReportCommit",
            "earlierFrameFailureMakesACommittedReportArtifactSetFail",
            "reportOnlyTerminalFailureCanRetrySuccessfullyInTheSameSession",
            "newSessionSupersedesOldReportCompletionExactlyOnce",
            "lateStopFromOldSessionCannotDeactivateTheNewSession",
            "twoConsecutiveSessionsEachReceiveTheirOwnCommitAcknowledgement",
        ),
    )
    if "python3 tools/check-profiler-file-output-lifecycle.py --self-test" not in sources["workflow"]:
        raise AssertionError("compose source-contracts must run profiler file lifecycle checker")


def expect_failure(sources: dict[str, str], key: str, old: str, new: str) -> None:
    mutated = dict(sources)
    if old not in mutated[key]:
        raise AssertionError(f"self-test mutation source missing in {key}: {old}")
    mutated[key] = mutated[key].replace(old, new, 1)
    try:
        assert_contract(mutated)
    except AssertionError:
        return
    raise AssertionError(f"checker accepted mutation in {key}: {old} -> {new}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    sources = {name: path.read_text(encoding="utf-8") for name, path in PATHS.items()}
    assert_contract(sources)

    if args.self_test:
        expect_failure(
            sources,
            "container",
            "        unregisterProfilerFileModule()\n        RecompositionProfiler.removeLifecycleListener",
            "        // mutation: stale module retained\n        RecompositionProfiler.removeLifecycleListener",
        )
        expect_failure(
            sources,
            "strategy",
            "completion(ProfilerFileIoResult.RetryableFailure(\"pager bridge unavailable\"))",
            "completion(ProfilerFileIoResult.TerminalFailure(\"mutation: cancelled operation dropped\"))",
        )
        expect_failure(
            sources,
            "tracker",
            "strategy.onSessionReset(sessionId, startTimestampMs)",
            "// mutation: persisted session not reset",
        )
        expect_failure(
            sources,
            "profiler",
            "strategy.deactivate(report, completion)",
            "strategy.deactivate(report)\n            fileStrategy = null",
        )
        expect_failure(
            sources,
            "strategy",
            "operation.sameModuleRetryCount < MAX_SAME_MODULE_RETRIES",
            "false // mutation: same live module never retries",
        )
        expect_failure(
            sources,
            "strategy",
            "takeCompletionLocked(operation, completionResult)",
            "null // mutation: native success is never acknowledged",
        )
        expect_failure(
            sources,
            "strategy",
            "sessionId != report.sessionId",
            "false // mutation: old stop may deactivate a new session",
        )
        expect_failure(
            sources,
            "strategy",
            "operation.filename == FILE_FRAMES",
            "operation.filename == FILE_REPORT // mutation: report failure poisons retry",
        )
        expect_failure(
            sources,
            "strategy",
            "                            operation.operationId,",
            "                            nextProfilerFileOperationId(), // mutation: new id on retry",
        )
        expect_failure(
            sources,
            "strategy",
            "if (inFlightFileOperation !== operation || inFlightFileModule !== module)",
            "if (inFlightFileOperation !== operation) // mutation: old Pager callback may win",
        )
        expect_failure(
            sources,
            "file_module",
            "put(PARAM_OPERATION_ID, operationId)",
            "put(\"mutationOperationId\", operationId)",
        )
        expect_failure(
            sources,
            "android",
            "COMPLETED_OPERATION_IDS.contains(operationId)",
            "false // mutation: duplicate native commit",
        )
        expect_failure(
            sources,
            "android",
            "FILE_EXECUTOR.execute {",
            "Thread { // mutation: concurrent per-call writer",
        )
        expect_failure(
            sources,
            "ios",
            "[completedOperationIds containsObject:operationId]",
            "NO // mutation: duplicate native commit",
        )
        expect_failure(
            sources,
            "ios",
            "dispatch_async(KRProfilerFileQueue(), ^{",
            "dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{",
        )
        expect_failure(
            sources,
            "ohos",
            "completedOperationIds.count(operationId) > 0",
            "false // mutation: duplicate native commit",
        )
        expect_failure(
            sources,
            "ohos",
            "ProfilerFileWorker::Instance().Enqueue(",
            "std::thread( // mutation: concurrent per-call writer",
        )
        expect_failure(
            sources,
            "android",
            "if (filename.isNullOrEmpty()) {",
            "if (filename.isNullOrEmpty() || content.isNullOrEmpty()) {",
        )
        expect_failure(
            sources,
            "ohos",
            "if (filename.empty()) {",
            "if (filename.empty() || content.empty()) {",
        )
        print("profiler file output lifecycle checker self-test: PASS")
    else:
        print("profiler file output lifecycle contract: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
