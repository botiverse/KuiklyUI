#!/usr/bin/env python3
"""Fail closed if KRScrollView can bridge NaN/Inf or a nil event payload."""

from __future__ import annotations

import argparse
import re
from pathlib import Path


SOURCE = Path("core-render-ios/Extension/Components/KRScrollView.m")


def strip_comments(source: str) -> str:
    output: list[str] = []
    index = 0
    state = "code"
    quote = ""
    while index < len(source):
        char = source[index]
        nxt = source[index + 1] if index + 1 < len(source) else ""
        if state == "code":
            if char == "/" and nxt == "/":
                state = "line"
                output.extend("  ")
                index += 2
                continue
            if char == "/" and nxt == "*":
                state = "block"
                output.extend("  ")
                index += 2
                continue
            if char in ('"', "'"):
                state = "string"
                quote = char
            output.append(char)
        elif state == "line":
            output.append("\n" if char == "\n" else " ")
            if char == "\n":
                state = "code"
        elif state == "block":
            if char == "*" and nxt == "/":
                output.extend("  ")
                index += 2
                state = "code"
                continue
            output.append("\n" if char == "\n" else " ")
        else:
            output.append(char)
            if char == "\\" and index + 1 < len(source):
                output.append(source[index + 1])
                index += 2
                continue
            if char == quote:
                state = "code"
        index += 1
    return "".join(output)


def compact(source: str) -> str:
    return re.sub(r"\s+", "", strip_comments(source))


def brace_block(source: str, start: int) -> str:
    brace = source.find("{", start)
    if brace < 0:
        raise AssertionError("missing block body")
    depth = 0
    for index in range(brace, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[brace + 1 : index]
    raise AssertionError("unterminated block body")


def block_after(source: str, marker: str) -> str:
    clean = strip_comments(source)
    start = clean.find(marker)
    if start < 0:
        raise AssertionError(f"missing required function/method: {marker}")
    return brace_block(clean, start + len(marker))


def enclosing_nonnull_guard(source: str, call_start: int, variable: str) -> int | None:
    stack: list[int] = []
    for index, char in enumerate(source[:call_start]):
        if char == "{":
            stack.append(index)
        elif char == "}" and stack:
            stack.pop()
    for brace in reversed(stack):
        prefix = source[max(0, brace - len(variable) - 12) : brace]
        if prefix.endswith(f"if({variable})"):
            return brace
    return None


def check_callback_guards(source: str) -> None:
    compacted = compact(source)
    any_call = re.compile(r"(?:strongSelf->)?_css_(?:dragBegin|dragEnd|scrollEnd|willDragEnd)\(")
    guarded_call = re.compile(
        r"(?:strongSelf->)?_css_(?:dragBegin|dragEnd|scrollEnd|willDragEnd)\((\w+)\);"
    )
    all_calls = list(any_call.finditer(compacted))
    parsed_calls = list(guarded_call.finditer(compacted))
    if len(all_calls) != len(parsed_calls):
        raise AssertionError("scroll callback has a non-variable or direct generated payload")
    for call in parsed_calls:
        variable = call.group(1)
        guard_brace = enclosing_nonnull_guard(compacted, call.start(), variable)
        if guard_brace is None:
            raise AssertionError(f"scroll callback payload is not guarded against nil: {call.group(0)}")
        guarded_prefix = compacted[guard_brace + 1 : call.start()]
        direct_assignment = re.compile(rf"(?<![\w.]){re.escape(variable)}=(?!=)")
        if direct_assignment.search(guarded_prefix):
            raise AssertionError(
                f"scroll callback payload is reassigned after its non-nil guard: {call.group(0)}"
            )


def check(source: str) -> None:
    finite = compact(block_after(source, "static BOOL KRScrollEventValueIsFinite(CGFloat value)"))
    if "return!isnan(value)&&!isinf(value);" not in finite:
        raise AssertionError("KRScrollEventValueIsFinite must reject both NaN and Inf")

    point = compact(block_after(source, "static BOOL KRScrollEventPointIsFinite(CGPoint point)"))
    if "returnKRScrollEventValueIsFinite(point.x)&&KRScrollEventValueIsFinite(point.y);" not in point:
        raise AssertionError("KRScrollEventPointIsFinite must validate both axes")

    base = compact(block_after(source, "- (NSDictionary *)p_generateEventBaseParams"))
    required_base = (
        "if(!KRScrollEventValueIsFinite(coreValues[i])){",
        "KRLogDroppedScrollEventValue(coreFields[i],coreValues[i],@\"drop_event\");returnnil;",
        "if(!KRScrollEventPointIsFinite(pagePoint)){",
        "continue;",
        "if(KRScrollEventPointIsFinite(mousePoint)){",
    )
    for required in required_base:
        if required not in base:
            raise AssertionError(f"missing event-base finiteness behavior: {required}")

    will_end = compact(block_after(source, "- (void)scrollViewWillEndDragging:"))
    for field, value in (
        ("velocityX", "velocity.x"),
        ("velocityY", "velocity.y"),
        ("targetContentOffsetX", "target.x"),
        ("targetContentOffsetY", "target.y"),
    ):
        required = f"!KRScrollEventValueIsFinite({value})"
        log = f"KRLogDroppedScrollEventValue(@\"{field}\",{value},@\"drop_event\");"
        if required not in will_end or log not in will_end:
            raise AssertionError(f"missing drag-end finiteness behavior for {field}")
    if "if(params){" not in will_end or "_css_willDragEnd(params);" not in will_end:
        raise AssertionError("willDragEnd must only dispatch a non-nil payload")

    check_callback_guards(source)


def expect_failure(source: str, label: str) -> None:
    try:
        check(source)
    except AssertionError:
        return
    raise AssertionError(f"self-test mutant survived: {label}")


def self_test(source: str) -> None:
    check(source)
    expect_failure(
        re.sub(
            r"(static BOOL KRScrollEventValueIsFinite\(CGFloat value\)\s*\{).*?(\})",
            r"\1 return YES; \2",
            source,
            count=1,
            flags=re.S,
        ),
        "finite helper always true",
    )
    expect_failure(
        source.replace(
            "if (!KRScrollEventValueIsFinite(coreValues[i]))",
            "// if (!KRScrollEventValueIsFinite(coreValues[i]))",
            1,
        ),
        "commented core geometry guard",
    )
    marker = "\n- (void)scrollViewDidEndDecelerating:"
    unguarded = "\n- (void)injectedUnguardedScrollEnd {\n    _css_scrollEnd \n    (eventParams);\n}\n"
    expect_failure(source.replace(marker, unguarded + marker, 1), "whitespace-split unguarded callback")

    guarded_nil_reassignment = (
        "\n- (void)injectedNilReassignedScrollEnd {\n"
        "    NSDictionary *eventParams = [self p_generateEventBaseParams];\n"
        "    if (eventParams) {\n"
        "        eventParams = nil;\n"
        "        _css_scrollEnd(eventParams);\n"
        "    }\n"
        "}\n"
    )
    expect_failure(
        source.replace(marker, guarded_nil_reassignment + marker, 1),
        "payload reassigned to nil inside non-null guard",
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--source", type=Path, default=SOURCE)
    args = parser.parse_args()

    source = args.source.read_text(encoding="utf-8")
    self_test(source) if args.self_test else check(source)
    print("ios_scroll_event_finiteness=pass")


if __name__ == "__main__":
    main()
