#!/usr/bin/env python3
"""Fail closed if entering RecyclerView DRAGGING can stop active user motion."""

from __future__ import annotations

import argparse
import re
from pathlib import Path


SOURCE = Path(
    "core-render-android/src/main/java/com/tencent/kuikly/core/render/android/"
    "expand/component/list/KRRecyclerView.kt"
)


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


def compact(source: str) -> str:
    return re.sub(r"\s+", "", strip_comments(source))


def drag_entry_block(source: str) -> str:
    clean = strip_comments(source)
    pattern = re.compile(
        r"if\s*\(\s*isIdeaStateToDraggingState\s*\(\s*currentState\s*\)\s*\|\|\s*"
        r"isSettlingStateToDraggingState\s*\(\s*currentState\s*\)\s*\)"
    )
    match = pattern.search(clean)
    if not match:
        raise AssertionError("missing idle/settling -> DRAGGING transition block")
    return brace_block(clean, match.end())


def local_functions(source: str) -> dict[str, str]:
    clean = strip_comments(source)
    functions: dict[str, str] = {}
    name_pattern = r"(?:`([^`]+)`|([A-Za-z_]\w*))"
    block_pattern = re.compile(rf"\bfun\s+{name_pattern}\s*\([^)]*\)[^{{=]*\{{")
    for match in block_pattern.finditer(clean):
        name = match.group(1) or match.group(2)
        functions[name] = brace_block(clean, match.end() - 1)

    expression_pattern = re.compile(
        rf"\bfun\s+{name_pattern}\s*\([^)]*\)[^{{=\n]*=\s*([^\n]+)"
    )
    for match in expression_pattern.finditer(clean):
        name = match.group(1) or match.group(2)
        functions[name] = match.group(3)
    return functions


def called_names(source: str) -> set[str]:
    clean = strip_comments(source)
    pattern = re.compile(r"(?:(?:this|self)\s*\.\s*)?(?:`([^`]+)`|([A-Za-z_]\w*))\s*\(")
    return {backtick or plain for backtick, plain in pattern.findall(clean)}


def assert_no_stop_path(block: str, functions: dict[str, str], stack: tuple[str, ...] = ()) -> None:
    compacted = compact(block)
    for forbidden in ("stopScroll(", "forceSetScrollState("):
        if forbidden in compacted:
            path = " -> ".join(stack) if stack else "DRAGGING transition"
            raise AssertionError(f"active physical gesture can be stopped via {path}: {forbidden}")

    for name in called_names(block):
        if name in functions and name not in stack:
            assert_no_stop_path(functions[name], functions, stack + (name,))


def check(source: str) -> None:
    block = drag_entry_block(source)
    compacted = compact(block)
    for required in ("scrollAnimationManager.cancel()", "fireBeginDragEvent()"):
        if required not in compacted:
            raise AssertionError(f"DRAGGING transition must retain executable {required}")
    assert_no_stop_path(block, local_functions(source))


def expect_failure(source: str, label: str) -> None:
    try:
        check(source)
    except AssertionError:
        return
    raise AssertionError(f"self-test mutant survived: {label}")


def self_test(source: str) -> None:
    check(source)
    expect_failure(
        source.replace(
            "                    scrollAnimationManager.cancel()\n                    fireBeginDragEvent()",
            "                    // scrollAnimationManager.cancel()\n                    fireBeginDragEvent()",
            1,
        ),
        "commented required cancellation",
    )

    expect_failure(
        source.replace(
            "                    fireBeginDragEvent()\n                }",
            "                    fireBeginDragEvent()\n                    stopScroll \n                    ()\n                }",
            1,
        ),
        "whitespace-split direct stopScroll",
    )

    helper = "\n    private fun injectedGestureStop() {\n        stopScroll()\n    }\n"
    helper_source = source.replace("\n    private fun fireBeginDragEvent()", helper + "\n    private fun fireBeginDragEvent()", 1)
    helper_source = helper_source.replace(
        "                    fireBeginDragEvent()\n                }",
        "                    fireBeginDragEvent()\n                    injectedGestureStop \n                    ()\n                }",
        1,
    )
    expect_failure(helper_source, "helper-indirected stopScroll")

    backtick_helper = "\n    private fun `injected gesture stop`() {\n        stopScroll()\n    }\n"
    backtick_source = source.replace(
        "\n    private fun fireBeginDragEvent()",
        backtick_helper + "\n    private fun fireBeginDragEvent()",
        1,
    ).replace(
        "                    fireBeginDragEvent()\n                }",
        "                    fireBeginDragEvent()\n                    `injected gesture stop`()\n                }",
        1,
    )
    expect_failure(backtick_source, "backtick helper-indirected stopScroll")

    qualified_helper = "\n    private fun injectedQualifiedStop() {\n        stopScroll()\n    }\n"
    qualified_source = source.replace(
        "\n    private fun fireBeginDragEvent()",
        qualified_helper + "\n    private fun fireBeginDragEvent()",
        1,
    ).replace(
        "                    fireBeginDragEvent()\n                }",
        "                    fireBeginDragEvent()\n                    this.injectedQualifiedStop()\n                }",
        1,
    )
    expect_failure(qualified_source, "qualified helper-indirected stopScroll")

    expression_helper = "\n    private fun injectedExpressionStop() = stopScroll()\n"
    expression_source = source.replace(
        "\n    private fun fireBeginDragEvent()",
        expression_helper + "\n    private fun fireBeginDragEvent()",
        1,
    ).replace(
        "                    fireBeginDragEvent()\n                }",
        "                    fireBeginDragEvent()\n                    injectedExpressionStop()\n                }",
        1,
    )
    expect_failure(expression_source, "expression-bodied helper-indirected stopScroll")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--source", type=Path, default=SOURCE)
    args = parser.parse_args()

    source = args.source.read_text(encoding="utf-8")
    self_test(source) if args.self_test else check(source)
    print("android_drag_entry_preserves_physical_scroll=pass")


if __name__ == "__main__":
    main()
