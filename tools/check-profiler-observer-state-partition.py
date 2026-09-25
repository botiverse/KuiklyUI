#!/usr/bin/env python3
"""Fail closed if profiler observer state loses composition/thread ownership or locking."""

from __future__ import annotations

import argparse
import re
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
REGISTRY_PATH = ROOT / (
    "compose/src/commonMain/kotlin/com/tencent/kuikly/compose/profiler/"
    "ProfilerCompositionStateRegistry.kt"
)
OBSERVER_PATH = ROOT / (
    "compose/src/commonMain/kotlin/com/tencent/kuikly/compose/profiler/"
    "ProfilerCompositionObserver.kt"
)
TRACKER_PATH = ROOT / (
    "compose/src/commonMain/kotlin/com/tencent/kuikly/compose/profiler/"
    "RecompositionTracker.kt"
)

LOCKED_ENTRY_POINTS = (
    "beginComposition",
    "registerHandle",
    "endComposition",
    "disposeAll",
    "beginScope",
    "endScope",
    "scopeDisposed",
    "currentScopeSnapshot",
)
LOCKED_HELPERS = ("removeCompositionScopesLocked", "removeScopeLocked")
GUARDED_STATE = ("statesByComposition", "activeScopesByThread", "nextGeneration")


@dataclass(frozen=True)
class FunctionRegion:
    name: str
    start: int
    end: int
    source: str


def mask_non_code(source: str) -> str:
    """Replace comments and literals with spaces while preserving offsets and newlines."""
    masked = list(source)
    index = 0
    state = "code"
    block_depth = 0
    escaped = False
    while index < len(source):
        if state == "code":
            if source.startswith("//", index):
                state = "line-comment"
                masked[index : index + 2] = "  "
                index += 2
                continue
            if source.startswith("/*", index):
                state = "block-comment"
                block_depth = 1
                masked[index : index + 2] = "  "
                index += 2
                continue
            if source.startswith('"""', index):
                state = "triple-string"
                masked[index : index + 3] = "   "
                index += 3
                continue
            if source[index] == '"':
                state = "string"
                escaped = False
                masked[index] = " "
            elif source[index] == "'":
                state = "char"
                escaped = False
                masked[index] = " "
        elif state == "line-comment":
            if source[index] == "\n":
                state = "code"
            else:
                masked[index] = " "
        elif state == "block-comment":
            if source.startswith("/*", index):
                block_depth += 1
                masked[index : index + 2] = "  "
                index += 2
                continue
            if source.startswith("*/", index):
                block_depth -= 1
                masked[index : index + 2] = "  "
                index += 2
                if block_depth == 0:
                    state = "code"
                continue
            if source[index] != "\n":
                masked[index] = " "
        elif state == "triple-string":
            if source.startswith('"""', index):
                masked[index : index + 3] = "   "
                index += 3
                state = "code"
                continue
            if source[index] != "\n":
                masked[index] = " "
        else:
            if source[index] != "\n":
                masked[index] = " "
            if escaped:
                escaped = False
            elif source[index] == "\\":
                escaped = True
            elif state == "string" and source[index] == '"':
                state = "code"
            elif state == "char" and source[index] == "'":
                state = "code"
        index += 1
    return "".join(masked)


def matching_brace(source: str, opening: int) -> int:
    depth = 0
    for index in range(opening, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return index
    raise AssertionError(f"unterminated block at offset {opening}")


def compact(source: str) -> str:
    return re.sub(r"\s+", "", mask_non_code(source))


def function_region(source: str, name: str) -> FunctionRegion:
    clean = mask_non_code(source)
    matches = list(re.finditer(rf"\bfun\s+{re.escape(name)}\s*\(", clean))
    if len(matches) != 1:
        raise AssertionError(f"expected exactly one function {name}, found {len(matches)}")
    match = matches[0]
    opening = clean.find("{", match.end())
    if opening < 0:
        raise AssertionError(f"function {name} has no block")
    closing = matching_brace(clean, opening)
    return FunctionRegion(name, match.start(), closing + 1, source[match.start() : closing + 1])


def class_region(source: str, name: str) -> FunctionRegion:
    clean = mask_non_code(source)
    matches = list(re.finditer(rf"\bclass\s+{re.escape(name)}\b[^{{]*\{{", clean))
    if len(matches) != 1:
        raise AssertionError(f"expected exactly one class {name}, found {len(matches)}")
    match = matches[0]
    opening = clean.find("{", match.start(), match.end())
    closing = matching_brace(clean, opening)
    return FunctionRegion(name, match.start(), closing + 1, source[match.start() : closing + 1])


def synchronized_ranges_for_lock(
    source: str,
    lock_name: str,
    start: int = 0,
    end: int | None = None,
) -> list[tuple[int, int]]:
    clean = mask_non_code(source)
    if end is None:
        end = len(source)
    region = clean[start:end]
    ranges: list[tuple[int, int]] = []
    for match in re.finditer(
        rf"\bsynchronized\s*\(\s*{re.escape(lock_name)}\s*\)\s*\{{",
        region,
    ):
        opening = start + region.find("{", match.start(), match.end())
        ranges.append((opening, matching_brace(clean, opening) + 1))
    return ranges


def property_declaration_range(source: str, name: str) -> tuple[int, int]:
    clean = mask_non_code(source)
    matches = list(
        re.finditer(
            rf"\bprivate\s+(?:val|var)\s+{re.escape(name)}\b[^\n]*",
            clean,
        )
    )
    if len(matches) != 1:
        raise AssertionError(
            f"expected exactly one private property {name}, found {len(matches)}"
        )
    return matches[0].start(), matches[0].end()


def assert_occurrences_within(
    source: str,
    name: str,
    allowed_ranges: list[tuple[int, int]],
    label: str,
) -> None:
    clean = mask_non_code(source)
    for occurrence in re.finditer(rf"\b{re.escape(name)}\b", clean):
        if not any(start <= occurrence.start() < end for start, end in allowed_ranges):
            raise AssertionError(
                f"{label} accesses {name} outside its ownership/lock boundary "
                f"at offset {occurrence.start()}"
            )


def synchronized_range(source: str, region: FunctionRegion) -> tuple[int, int]:
    clean = mask_non_code(source)
    region_clean = clean[region.start : region.end]
    matches = list(re.finditer(r"\bsynchronized\s*\(\s*lock\s*\)\s*\{", region_clean))
    if len(matches) != 1:
        raise AssertionError(
            f"{region.name} must have exactly one synchronized(lock) state boundary; "
            f"found {len(matches)}"
        )
    opening = region.start + region_clean.find("{", matches[0].start())
    closing = matching_brace(clean, opening) + 1

    # The registry entry point may be expression-bodied (`= synchronized(lock) { ... }`) or
    # block-bodied (`{ synchronized(lock) { ... } }`), but the synchronized call must be its only
    # executable statement. This prevents a future refactor from moving a mutation/snapshot before
    # or after the lock while leaving a token `synchronized(lock)` somewhere in the function.
    outer_opening = clean.find("{", region.start, region.end)
    outer_closing = region.end - 1
    sync_call_start = region.start + matches[0].start()
    if outer_opening != opening:
        if clean[outer_opening + 1 : sync_call_start].strip():
            raise AssertionError(f"{region.name} executes code before synchronized(lock)")
        if clean[closing:outer_closing].strip():
            raise AssertionError(f"{region.name} executes code after synchronized(lock)")
    elif clean[closing:region.end].strip():
        raise AssertionError(f"{region.name} executes code after synchronized(lock)")
    return opening, closing


def assert_registry_contract(source: str) -> None:
    clean = mask_non_code(source)
    compacted = compact(source)
    required = (
        "privatevalstatesByComposition=mutableMapOf<CompositionKey,CompositionState<Scope,Handle>>()",
        "privatevalactiveScopesByThread=mutableMapOf<Long,MutableList<ActiveScope<CompositionKey,Scope>>>()",
        "privatevalcurrentThreadId:()->Long=::getCurrentThreadId",
        "valcomposition:CompositionKey",
        "valgeneration:Long",
        "valscope:Scope",
    )
    for token in required:
        if token not in compacted:
            raise AssertionError(f"missing profiler partition contract: {token}")

    if re.search(r"\.\s*dispose\s*\(", clean):
        raise AssertionError("generic registry must detach handles; it may not dispose under its lock")

    locked_ranges: list[tuple[int, int]] = []
    entry_regions: dict[str, FunctionRegion] = {}
    for name in LOCKED_ENTRY_POINTS:
        region = function_region(source, name)
        entry_regions[name] = region
        locked_ranges.append(synchronized_range(source, region))

    for name in ("beginScope", "endScope", "currentScopeSnapshot"):
        if "currentThreadId()" not in compact(entry_regions[name].source):
            raise AssertionError(f"{name} must index active scopes with getCurrentThreadId")

    helper_regions = [function_region(source, name) for name in LOCKED_HELPERS]
    helper_ranges = [(region.start, region.end) for region in helper_regions]
    for helper in helper_regions:
        declaration = re.compile(rf"\bprivate\s+fun\s+{re.escape(helper.name)}\s*\(")
        if not declaration.search(clean):
            raise AssertionError(f"{helper.name} must remain private and lock-owned")

    declaration_ranges: list[tuple[int, int]] = []
    for state_name in GUARDED_STATE:
        declaration = re.search(
            rf"\bprivate\s+(?:val|var)\s+{re.escape(state_name)}\b[^\n]*",
            clean,
        )
        if declaration is None:
            raise AssertionError(f"missing guarded state declaration: {state_name}")
        declaration_ranges.append((declaration.start(), declaration.end()))

    allowed_ranges = locked_ranges + helper_ranges + declaration_ranges
    for state_name in GUARDED_STATE:
        for occurrence in re.finditer(rf"\b{re.escape(state_name)}\b", clean):
            if not any(start <= occurrence.start() < end for start, end in allowed_ranges):
                raise AssertionError(
                    f"{state_name} is accessed outside synchronized(lock) at offset {occurrence.start()}"
                )

    for helper in LOCKED_HELPERS:
        declaration_start = function_region(source, helper).start
        for occurrence in re.finditer(rf"\b{re.escape(helper)}\s*\(", clean):
            if occurrence.start() == declaration_start + clean[declaration_start:].find(helper):
                continue
            if not any(start <= occurrence.start() < end for start, end in locked_ranges):
                raise AssertionError(f"{helper} is called without synchronized(lock)")

    generation_checks = {
        "registerHandle": "state==null||state.generation!=generation",
        "beginScope": "state==null||state.generation!=generation",
        "scopeDisposed": "state!=null&&state.generation==generation",
        "currentScopeSnapshot": "state==null||state.generation!=activeScope.generation",
    }
    for name, required_check in generation_checks.items():
        if required_check not in compact(entry_regions[name].source):
            raise AssertionError(f"{name} is missing generation fence: {required_check}")


def assert_observer_contract(source: str) -> None:
    clean = mask_non_code(source)
    compacted = compact(source)
    for forbidden in ("activeScopeStack", "scopeToStatesMap", "scopeObserverHandles"):
        if re.search(rf"\b{forbidden}\b", clean):
            raise AssertionError(f"observer reintroduced shared mutable state: {forbidden}")
    if re.search(r"\bvar\s+hasPreciseMapping\b", clean):
        raise AssertionError("observer precise mapping may not be a global mutable flag")

    required = (
        "privatevalstateRegistry=ProfilerCompositionStateRegistry<",
        "valbeginResult=stateRegistry.beginComposition(composition,invalidationMap)",
        "disposeHandles(beginResult.handlesToDispose)",
        "disposeHandles(stateRegistry.endComposition(composition))",
        "if(!stateRegistry.registerHandle(composition,beginResult.generation,handle))",
        "handle.dispose()",
        "disposeHandles(stateRegistry.disposeAll())",
        "valsnapshot=stateRegistry.currentScopeSnapshot()",
    )
    for token in required:
        if token not in compacted:
            raise AssertionError(f"missing observer ownership/disposal contract: {token}")

    begin_region = compact(function_region(source, "onBeginComposition").source)
    if begin_region.index("stateRegistry.beginComposition") > begin_region.index(
        "disposeHandles(beginResult.handlesToDispose)"
    ):
        raise AssertionError("old handles must be detached before lock-free disposal")


def assert_tracker_contract(source: str) -> None:
    clean = mask_non_code(source)
    compacted = compact(source)
    forbidden = (
        r"\bvar\s+hasPreciseScopeMapping\b",
        r"\bfun\s+onCompositionObserverBegin\s*\(",
        r"\bfun\s+onCompositionObserverEnd\s*\(",
    )
    for pattern in forbidden:
        if re.search(pattern, clean):
            raise AssertionError(f"tracker restored global observer authority: {pattern}")

    required = (
        "privatevaltracerStatesByThread=mutableMapOf<Long,TracerThreadState>()",
        "privatevaltraceStackLock=createSynchronizedObject()",
        "privatevalcomposableAccumulatorLock=createSynchronizedObject()",
        "valobserverSnapshot=entry.observerSnapshot",
        "if(observerSnapshot.hasPreciseMapping)",
        "compositionObserver.dispose()",
    )
    for token in required:
        if token not in compacted:
            raise AssertionError(f"missing tracker snapshot/stop contract: {token}")

    trace_entry = compact(class_region(source, "TraceEntry").source)
    if (
        "valobserverSnapshot:ProfilerCompositionObserver.CurrentScopeSnapshot"
        not in trace_entry
    ):
        raise AssertionError("TraceEntry must own the immutable observer snapshot from start")

    tracer_thread_state_region = class_region(source, "TracerThreadState")
    tracer_thread_state = compact(tracer_thread_state_region.source)
    for token in (
        "valtraceStack:MutableList<TraceEntry>=mutableListOf()",
        "varoverlayFilterDepth:Int=0",
    ):
        if token not in tracer_thread_state:
            raise AssertionError(f"thread tracer bucket is missing owned state: {token}")

    # There may be no naked tracker-global stack or overlay depth. Their only property
    # declarations must live inside TracerThreadState.
    for state_name in ("traceStack", "overlayFilterDepth"):
        declarations = re.finditer(
            rf"\b(?:private\s+)?(?:val|var)\s+{state_name}\b",
            clean,
        )
        for declaration in declarations:
            if not (
                tracer_thread_state_region.start
                <= declaration.start()
                < tracer_thread_state_region.end
            ):
                raise AssertionError(f"tracker reintroduced global {state_name}")

    start_region = function_region(source, "onComposableTraceStart")
    end_region = function_region(source, "onComposableTraceEnd")
    stop_region = function_region(source, "stop")
    helper_region = function_region(source, "removeTracerStateIfIdleLocked")

    trace_locked_ranges: list[tuple[int, int]] = []
    for region in (start_region, end_region, stop_region):
        ranges = synchronized_ranges_for_lock(
            source,
            "traceStackLock",
            region.start,
            region.end,
        )
        if len(ranges) != 1:
            raise AssertionError(
                f"{region.name} must have exactly one synchronized(traceStackLock) boundary; "
                f"found {len(ranges)}"
            )
        trace_locked_ranges.extend(ranges)

    start_compact = compact(start_region.source)
    end_compact = compact(end_region.source)
    stop_compact = compact(stop_region.source)
    for region_name, region_compact in (
        (start_region.name, start_compact),
        (end_region.name, end_compact),
    ):
        if "valthreadId=getCurrentThreadId()" not in region_compact:
            raise AssertionError(f"{region_name} must index tracer state by current thread")

    start_required = (
        "valtracerState=tracerStatesByThread.getOrPut(threadId){TracerThreadState()}",
        "tracerState.traceStack.add(",
        "observerSnapshot=compositionObserver.currentScopeSnapshot()",
    )
    for token in start_required:
        if token not in start_compact:
            raise AssertionError(f"trace start lost thread/snapshot ownership: {token}")

    end_required = (
        "valtracerState=tracerStatesByThread[threadId]?:return",
        "valpoppedEntry=tracerState.traceStack.removeAt(tracerState.traceStack.lastIndex)",
        "valpoppedParentInfo=tracerState.traceStack.lastOrNull",
        "valobserverSnapshot=entry.observerSnapshot",
    )
    for token in end_required:
        if token not in end_compact:
            raise AssertionError(f"trace end lost calling-thread ownership: {token}")
    if "compositionObserver.currentScopeSnapshot()" in end_compact:
        raise AssertionError("trace end may not recapture observer context on a later thread/time")

    if "tracerStatesByThread.clear()" not in stop_compact:
        raise AssertionError("stop must clear every execution-thread tracer bucket")
    if "currentFrameSampled=false" not in stop_compact:
        raise AssertionError("stop must close tracer admission before clearing thread buckets")
    if stop_compact.index("currentFrameSampled=false") > stop_compact.index(
        "synchronized(traceStackLock)"
    ):
        raise AssertionError("stop must close tracer admission before taking traceStackLock")
    if start_compact.count("if(!currentFrameSampled)") < 2:
        raise AssertionError("trace start must re-check admission under traceStackLock")

    if not re.search(
        r"\bprivate\s+fun\s+removeTracerStateIfIdleLocked\s*\(",
        clean,
    ):
        raise AssertionError("tracer bucket cleanup helper must remain private and lock-owned")

    # All tracker bucket access is either directly under traceStackLock or in the private
    # *Locked helper, and every helper call must itself be under traceStackLock.
    map_allowed_ranges = trace_locked_ranges + [
        property_declaration_range(source, "tracerStatesByThread"),
        (helper_region.start, helper_region.end),
    ]
    assert_occurrences_within(
        source,
        "tracerStatesByThread",
        map_allowed_ranges,
        "tracer partition",
    )
    owned_state_ranges = trace_locked_ranges + [
        (tracer_thread_state_region.start, tracer_thread_state_region.end),
        (helper_region.start, helper_region.end),
    ]
    for state_name in ("traceStack", "overlayFilterDepth"):
        assert_occurrences_within(
            source,
            state_name,
            owned_state_ranges,
            "tracer partition",
        )

    helper_declaration_offset = helper_region.start + mask_non_code(
        helper_region.source
    ).find("removeTracerStateIfIdleLocked")
    for call in re.finditer(r"\bremoveTracerStateIfIdleLocked\s*\(", clean):
        if call.start() == helper_declaration_offset:
            continue
        if not any(start <= call.start() < end for start, end in trace_locked_ranges):
            raise AssertionError("removeTracerStateIfIdleLocked is called without traceStackLock")

    # Real tracer callbacks can finish concurrently, so the aggregate map and its frame count
    # must be a single lock domain for mutation, reset, and report snapshots.
    accumulator_ranges = synchronized_ranges_for_lock(source, "composableAccumulatorLock")
    accumulator_declarations = [
        property_declaration_range(source, "composableAccumulator"),
        property_declaration_range(source, "currentFrameRecomposedCount"),
    ]
    for state_name in ("composableAccumulator", "currentFrameRecomposedCount"):
        assert_occurrences_within(
            source,
            state_name,
            accumulator_ranges + accumulator_declarations,
            "composable accumulator",
        )


def check_sources(registry: str, observer: str, tracker: str) -> None:
    assert_registry_contract(registry)
    assert_observer_contract(observer)
    assert_tracker_contract(tracker)


def expect_failure(registry: str, observer: str, tracker: str, label: str) -> None:
    try:
        check_sources(registry, observer, tracker)
    except AssertionError:
        return
    raise AssertionError(f"self-test mutant survived: {label}")


def replace_in_function(source: str, name: str, old: str, new: str) -> str:
    region = function_region(source, name)
    if region.source.count(old) != 1:
        raise AssertionError(f"self-test fixture for {name} expected one {old!r}")
    mutated = region.source.replace(old, new, 1)
    return source[: region.start] + mutated + source[region.end :]


def self_test(registry: str, observer: str, tracker: str) -> None:
    check_sources(registry, observer, tracker)

    expect_failure(
        registry.replace("activeScopesByThread", "activeScopeStack"),
        observer,
        tracker,
        "removed execution-thread partition",
    )
    expect_failure(
        registry.replace("statesByComposition", "sharedCompositionState"),
        observer,
        tracker,
        "removed composition partition",
    )
    expect_failure(
        replace_in_function(registry, "currentScopeSnapshot", "synchronized(lock)", "run"),
        observer,
        tracker,
        "removed snapshot lock",
    )
    expect_failure(
        replace_in_function(registry, "beginScope", "synchronized(lock)", "run"),
        observer,
        tracker,
        "removed mutation lock",
    )
    expect_failure(
        replace_in_function(registry, "beginScope", "currentThreadId()", "0L"),
        observer,
        tracker,
        "removed current-thread identity",
    )

    observer_marker = "internal class ProfilerCompositionObserver : CompositionObserver {"
    if observer_marker not in observer:
        raise AssertionError("self-test observer marker missing")
    naked_stack = observer.replace(
        observer_marker,
        observer_marker + "\n    private val activeScopeStack = mutableListOf<RecomposeScope>()",
        1,
    )
    expect_failure(registry, naked_stack, tracker, "reintroduced naked observer scope stack")

    tracker_marker = "internal class RecompositionTracker {"
    if tracker_marker not in tracker:
        raise AssertionError("self-test tracker marker missing")
    global_flag = tracker.replace(
        tracker_marker,
        tracker_marker + "\n    private var hasPreciseScopeMapping = false",
        1,
    )
    expect_failure(registry, observer, global_flag, "restored global precise flag")

    collapsed_stack = tracker.replace(
        "private val tracerStatesByThread = mutableMapOf<Long, TracerThreadState>()",
        "private val traceStack = mutableListOf<TraceEntry>()",
        1,
    )
    expect_failure(registry, observer, collapsed_stack, "collapsed tracer buckets to global stack")

    global_overlay = tracker.replace(
        tracker_marker,
        tracker_marker + "\n    private var overlayFilterDepth = 0",
        1,
    )
    expect_failure(registry, observer, global_overlay, "restored global overlay depth")

    for function_name in ("onComposableTraceStart", "onComposableTraceEnd"):
        no_thread_identity = replace_in_function(
            tracker,
            function_name,
            "val threadId = getCurrentThreadId()",
            "val threadId = 0L",
        )
        expect_failure(
            registry,
            observer,
            no_thread_identity,
            f"removed current-thread lookup from {function_name}",
        )

    end_time_snapshot = replace_in_function(
        tracker,
        "onComposableTraceEnd",
        "val observerSnapshot = entry.observerSnapshot",
        "val observerSnapshot = compositionObserver.currentScopeSnapshot()",
    )
    expect_failure(
        registry,
        observer,
        end_time_snapshot,
        "replaced entry-owned observer snapshot with end-time snapshot",
    )

    no_stop_clear = replace_in_function(
        tracker,
        "stop",
        "tracerStatesByThread.clear()",
        "Unit",
    )
    expect_failure(registry, observer, no_stop_clear, "removed stop tracer-bucket cleanup")

    no_stop_admission_close = replace_in_function(
        tracker,
        "stop",
        "currentFrameSampled = false",
        "Unit",
    )
    expect_failure(
        registry,
        observer,
        no_stop_admission_close,
        "allowed late trace starts to repopulate stopped tracker",
    )

    for function_name in ("onComposableTraceStart", "onComposableTraceEnd"):
        no_tracer_lock = replace_in_function(
            tracker,
            function_name,
            "synchronized(traceStackLock)",
            "run",
        )
        expect_failure(
            registry,
            observer,
            no_tracer_lock,
            f"removed traceStackLock from {function_name}",
        )

    wrong_parent_bucket = replace_in_function(
        tracker,
        "onComposableTraceEnd",
        "val poppedParentInfo = tracerState.traceStack.lastOrNull",
        "val poppedParentInfo = tracerStatesByThread.values.first().traceStack.lastOrNull",
    )
    expect_failure(
        registry,
        observer,
        wrong_parent_bucket,
        "looked up parent from another execution-thread bucket",
    )

    no_accumulator_lock = replace_in_function(
        tracker,
        "onComposableTraceEnd",
        "synchronized(composableAccumulatorLock)",
        "run",
    )
    expect_failure(
        registry,
        observer,
        no_accumulator_lock,
        "removed concurrent composable accumulator lock",
    )

    dispose_under_lock = registry.replace(
        "val handlesToDispose = statesByComposition.remove(composition)?.handles?.toList().orEmpty()",
        "val handlesToDispose = statesByComposition.remove(composition)?.handles?.toList().orEmpty()\n"
        "        handlesToDispose.forEach { it.dispose() }",
        1,
    )
    expect_failure(
        registry=dispose_under_lock,
        observer=observer,
        tracker=tracker,
        label="disposed handles in registry",
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    registry = REGISTRY_PATH.read_text(encoding="utf-8")
    observer = OBSERVER_PATH.read_text(encoding="utf-8")
    tracker = TRACKER_PATH.read_text(encoding="utf-8")
    if args.self_test:
        self_test(registry, observer, tracker)
    else:
        check_sources(registry, observer, tracker)
    print("profiler_observer_state_partition=pass")


if __name__ == "__main__":
    main()
