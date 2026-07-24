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
    "android": ROOT / (
        "core-render-android/src/main/java/com/tencent/kuikly/core/render/android/expand/module/"
        "KRFileModule.kt"
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

    ohos_write = between(sources["ohos"], "void KRFileModule::WriteFile", "void KRFileModule::AppendFile")
    if "content.empty()" in ohos_write:
        raise AssertionError("OHOS overwrite must allow empty content to truncate stale report")
    if "if (filename.empty())" not in ohos_write:
        raise AssertionError("OHOS overwrite must still reject a missing filename")
    ohos_append = between(sources["ohos"], "void KRFileModule::AppendFile", "void KRFileModule::GetFilesDir")
    if "content.empty()" not in ohos_append:
        raise AssertionError("OHOS append must continue rejecting empty batches")

    require_tokens(
        "profiler lifecycle tests",
        sources["tests"],
        (
            "liveModuleRegistryFallsBackWhenThePreferredPagerIsDestroyed",
            "stalePagerCancellationRetriesTheSameWriteOnTheLiveFallback",
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
