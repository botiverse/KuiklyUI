#!/usr/bin/env python3
from __future__ import annotations

import collections
import pathlib
import re
import sys
import tempfile


ROOT = pathlib.Path(__file__).resolve().parents[2]
ALLOWLIST = pathlib.Path(__file__).with_name("allowlist.txt")


def files(*patterns: str):
    for pattern in patterns:
        yield from ROOT.glob(pattern)


RULES = (
    (
        "compose-logical-offset",
        lambda: files("compose/src/commonMain/**/*.kt"),
        re.compile(r"(?:\b(?:state\.|internalState\.)?kuiklyInfo\.composeOffset|^\s*(?:var\s+)?composeOffset)\s*(?:[+\-*/]=|=(?!=))"),
    ),
    (
        "compose-content-size",
        lambda: files("compose/src/commonMain/**/*.kt"),
        re.compile(r"\bcurrentContentSize\s*(?:[+\-*/]=|=(?!=))"),
    ),
    (
        "compose-native-entry",
        lambda: files("compose/src/commonMain/**/*.kt"),
        re.compile(r"\b(?:setContentOffset|setContentInset|setContentInsetWhenEndDrag|abortContentOffsetAnimate|prepareForComposeReuse)\s*\("),
    ),
    (
        "compose-frame-writer",
        lambda: files(
            "compose/src/commonMain/kotlin/com/tencent/kuikly/compose/views/ScrollViewEx.kt",
            "compose/src/commonMain/kotlin/com/tencent/kuikly/compose/ui/node/KNode.kt",
            "compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt",
        ),
        re.compile(r"\bsetFrameToRenderView\s*\("),
    ),
    (
        "core-native-bridge",
        lambda: files("core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt"),
        re.compile(r"callMethod\(\s*\"(?:contentOffset|contentInset|contentInsetWhenEndDrag|abortContentOffsetAnimate|prepareForComposeReuse)\""),
    ),
    (
        "core-legacy-scroll-writer",
        lambda: files("core/src/commonMain/**/*.kt"),
        re.compile(r"\.?(?:setContentOffset|setContentInset|setContentInsetWhenEndDrag|abortContentOffsetAnimate)\s*\("),
    ),
    (
        "core-tokenless-reuse-restore",
        lambda: files("core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ListView.kt"),
        re.compile(r"\bcallContentOffset\s*\("),
    ),
    (
        "android-physical-writer",
        lambda: files("core-render-android/src/main/java/com/tencent/kuikly/core/render/android/expand/component/list/*.kt"),
        re.compile(r"\.(?:scrollBy|smoothScrollToPosition|scrollToPositionWithOffset|stopScroll|setFinalTranslation|bounceWithContentInset)\s*\("),
    ),
    (
        "android-lifecycle-fence",
        lambda: files("core-render-android/src/main/java/com/tencent/kuikly/core/render/android/expand/component/list/*.kt"),
        re.compile(r"\b(?:onDestroy|prepareForComposeReuse|onTouchEvent|cancelPendingNativeWritesForUserGesture|finishNativeWrite|installNativeWriteOperation)\s*\("),
    ),
    (
        "ios-physical-writer",
        lambda: files("core-render-ios/Extension/Components/KRScrollView.m"),
        re.compile(r"(?:setContentOffset:|\.contentInset\s*=|setContentInset:|CGAffineTransformMakeTranslation)"),
    ),
    (
        "ios-lifecycle-fence",
        lambda: files("core-render-ios/Extension/Components/KRScrollView.m"),
        re.compile(r"(?:css_prepareForComposeReuse|css_abortContentOffsetAnimate|scrollViewWillBeginDragging|scrollViewDidEndDragging|scrollViewDidEndDecelerating|p_completeScrollWrite|p_invalidateCurrentScrollWrite)"),
    ),
    (
        "ohos-physical-writer",
        lambda: files("core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRScrollerView.cpp"),
        re.compile(r"(?:SetArkUIContentOffset|SetContentInset\s*\(|NODE_SCROLL_BY)"),
    ),
    (
        "ohos-lifecycle-fence",
        lambda: files("core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRScrollerView.cpp"),
        re.compile(r"(?:OnDestroy|Reset\s*\(|PrepareForComposeReuse|AbortContentOffsetAnimate|InstallScrollWrite|FinalizeScrollWrite|ARKUI_NODE_SCROLL_EVENT_ON_SCROLL_STOP)"),
    ),
    (
        "h5-physical-writer",
        lambda: files("core-render-web/h5/src/jsMain/kotlin/com/tencent/kuikly/core/render/web/runtime/web/expand/components/list/H5ListView.kt"),
        re.compile(r"(?:\bele\.scrollTo\s*\(|style\.transform\s*=|setContentInset\s*\()"),
    ),
    (
        "h5-async-fence",
        lambda: files("core-render-web/h5/src/jsMain/kotlin/com/tencent/kuikly/core/render/web/runtime/web/expand/components/list/H5ListView.kt"),
        re.compile(r"(?:requestAnimationFrame|setTimeout|prepareForComposeReuse|abortContentOffsetAnimate|finishCurrentWrite|installWriteOperation)\s*\("),
    ),
)


def scan() -> collections.Counter[str]:
    matches: collections.Counter[str] = collections.Counter()
    for category, file_provider, pattern in RULES:
        for path in file_provider():
            if not path.is_file():
                continue
            relative = path.relative_to(ROOT).as_posix()
            for line in path.read_text(encoding="utf-8").splitlines():
                stripped = line.strip()
                if not stripped or stripped.startswith(("//", "*", "/*")):
                    continue
                searchable = stripped
                if category in {"compose-logical-offset", "compose-content-size"}:
                    searchable = re.sub(r'"(?:\\.|[^"\\])*"', '""', searchable)
                if pattern.search(searchable):
                    matches[f"{category}|{relative}|{stripped}"] += 1
    return matches


def load_allowlist() -> collections.Counter[str]:
    expected: collections.Counter[str] = collections.Counter()
    for raw in ALLOWLIST.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        key, count, reason = line.rsplit("|", 2)
        if not reason:
            raise ValueError(f"missing owner/reason: {line}")
        expected[key] = int(count)
    return expected


def function_body(path: pathlib.Path, signature: str | tuple[str, ...]) -> str:
    source = path.read_text(encoding="utf-8")
    signatures = (signature,) if isinstance(signature, str) else signature
    start = next((source.index(candidate) for candidate in signatures if candidate in source), -1)
    if start < 0:
        raise ValueError(f"missing function {signatures} in {path}")
    brace = source.index("{", start)
    depth = 0
    for index in range(brace, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[brace + 1:index]
    raise ValueError(f"unterminated function {signature} in {path}")


def authority_placement_test() -> int:
    latest_assignments = (
        (
            ROOT / "core-render-android/src/main/java/com/tencent/kuikly/core/render/android/expand/component/list/KRRecyclerView.kt",
            ("private fun validateOffsetWrite", "private fun validateAndClaimOffsetWrite"),
            ("private fun commitOffsetWriteAuthority", "private fun installNativeWriteOperation"),
            "latestComposeWriteOperation =",
            "Android",
        ),
        (
            ROOT / "core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRScrollerView.cpp",
            "KRScrollWriteResultCode KRScrollerView::ValidateOffsetWrite",
            "std::shared_ptr<KRNativeScrollWriteOperation> KRScrollerView::InstallScrollWrite",
            "latest_compose_write_operation_ =",
            "OHOS",
        ),
    )
    failures = []
    for path, validation_signature, install_signature, assignment, platform in latest_assignments:
        validation = function_body(path, validation_signature)
        install = function_body(path, install_signature)
        if assignment in validation:
            failures.append(f"{platform} validation advances latest native authority before install")
        if assignment not in install:
            failures.append(f"{platform} install does not commit latest native authority")

    ohos_path = ROOT / "core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRScrollerView.cpp"
    ohos_install = function_body(
        ohos_path,
        "std::shared_ptr<KRNativeScrollWriteOperation> KRScrollerView::InstallScrollWrite",
    )
    if "KREnsureMainThread()" not in ohos_install:
        failures.append("OHOS replacement stop installation is not asserted on the main serial lifecycle")
    arm_index = ohos_install.find("replacement_stop_event_fence_.Arm()")
    stop_index = ohos_install.find("setAttribute(GetNode(), NODE_SCROLL_BY")
    if arm_index < 0 or stop_index < 0 or arm_index > stop_index:
        failures.append("OHOS replacement stop does not arm its deferred stop-event fence before NODE_SCROLL_BY")

    destroy = function_body(ohos_path, "void KRScrollerView::OnDestroy")
    reset_index = destroy.find("replacement_stop_event_fence_.Reset()")
    destroy_stop_index = destroy.find("setAttribute(GetNode(), NODE_SCROLL_BY")
    if reset_index < 0 or destroy_stop_index < 0 or reset_index > destroy_stop_index:
        failures.append("OHOS destroy does not clear replacement stop debt before node teardown")

    compose_reuse = function_body(ohos_path, "void KRScrollerView::PrepareForComposeReuse")
    if "replacement_stop_event_fence_.Reset()" in compose_reuse:
        failures.append(
            "OHOS connected Compose reuse clears an actual deferred stop before it can be consumed"
        )
    compose_reuse_arm = compose_reuse.find("replacement_stop_event_fence_.Arm()")
    compose_reuse_stop = compose_reuse.find("setAttribute(GetNode(), NODE_SCROLL_BY")
    if compose_reuse_arm < 0 or compose_reuse_stop < 0 or compose_reuse_arm > compose_reuse_stop:
        failures.append("OHOS Compose reuse does not arm deferred stop consumption before NODE_SCROLL_BY")

    abort = function_body(ohos_path, "void KRScrollerView::AbortContentOffsetAnimate")
    abort_arm = abort.find("replacement_stop_event_fence_.Arm()")
    abort_stop = abort.find("setAttribute(GetNode(), NODE_SCROLL_BY")
    if abort_arm < 0 or abort_stop < 0 or abort_arm > abort_stop:
        failures.append("OHOS explicit abort does not arm deferred stop consumption before NODE_SCROLL_BY")

    ohos_stop = function_body(ohos_path, "void KRScrollerView::OnScrollStop")
    if "KREnsureMainThread()" not in ohos_stop:
        failures.append("OHOS scroll-stop consumption is not asserted on the main serial lifecycle")
    suppress_index = ohos_stop.find("replacement_stop_event_fence_.ConsumeReplacementStop()")
    current_index = ohos_stop.find("scroll_write_arbiter_.Current()")
    terminal_index = ohos_stop.find("KRRenderCallback terminal")
    publish_index = ohos_stop.find("FireEndScrollEvent(event)")
    if (
        suppress_index < 0
        or current_index < 0
        or terminal_index < 0
        or publish_index < 0
        or suppress_index > current_index
        or suppress_index > terminal_index
        or suppress_index > publish_index
    ):
        failures.append(
            "OHOS deferred replacement stop is not consumed before successor lookup, terminal handling, and scrollEnd publication"
        )
    if failures:
        for failure in failures:
            print(failure)
        return 1
    print("scroll write authority placement PASS (Android/OHOS install-time commit)")
    print("OHOS replacement stop-event ownership PASS")
    return 0


def report_mismatch(actual: collections.Counter[str], expected: collections.Counter[str]) -> int:
    if actual == expected:
        print(f"scroll transaction allowlist PASS ({sum(actual.values())} audited sites)")
        return 0
    for key in sorted(actual.keys() | expected.keys()):
        if actual[key] != expected[key]:
            print(f"writer mismatch expected={expected[key]} actual={actual[key]} {key}")
    return 1


def mutation_test(expected: collections.Counter[str]) -> int:
    directory = ROOT / "compose" / "src" / "commonMain" / "kotlin"
    with tempfile.NamedTemporaryFile(
        mode="w",
        suffix=".kt",
        prefix="ScrollWriterAllowlistMutation",
        dir=directory,
        encoding="utf-8",
        delete=False,
    ) as handle:
        handle.write("package mutation\nfun mutate() {\n    composeOffset = 1f\n}\n")
        path = pathlib.Path(handle.name)
    try:
        actual = scan()
        if actual == expected:
            print("mutation test failed: unregistered composeOffset writer was accepted")
            return 1
        print("scroll transaction allowlist mutation PASS (new writer rejected)")
        return 0
    finally:
        path.unlink(missing_ok=True)


def main() -> int:
    actual = scan()
    if len(sys.argv) == 2 and sys.argv[1] == "--print-baseline":
        reasons = {
            "core-legacy-scroll-writer": "legacy-scroller-fenced-by-synthetic-operation",
            "core-tokenless-reuse-restore": "android-pre-content-reuse-pending",
            "compose-logical-offset": "compose-transaction-or-native-ingress",
            "compose-content-size": "compose-viewport-resource-owner",
            "compose-frame-writer": "compose-frame-resource-owner",
            "compose-native-entry": "compose-tokenized-native-entry",
            "core-native-bridge": "core-token-wire-funnel",
            "android-physical-writer": "android-native-operation-arbiter",
            "android-lifecycle-fence": "android-terminal-lifecycle-funnel",
            "ios-physical-writer": "ios-current-operation-arbiter",
            "ios-lifecycle-fence": "ios-terminal-lifecycle-funnel",
            "ohos-physical-writer": "ohos-current-operation-arbiter",
            "ohos-lifecycle-fence": "ohos-terminal-lifecycle-funnel",
            "h5-physical-writer": "h5-web-operation-arbiter",
            "h5-async-fence": "h5-tagged-async-fence",
        }
        for key, count in sorted(actual.items()):
            category = key.split("|", 1)[0]
            print(f"{key}|{count}|{reasons[category]}")
        return 0
    if authority_placement_test() != 0:
        return 1
    expected = load_allowlist()
    if len(sys.argv) == 2 and sys.argv[1] == "--mutation-test":
        if report_mismatch(actual, expected) != 0:
            return 1
        return mutation_test(expected)
    return report_mismatch(actual, expected)


if __name__ == "__main__":
    raise SystemExit(main())
