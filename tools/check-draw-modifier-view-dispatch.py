#!/usr/bin/env python3
"""Keep LayoutNodeDrawScope on the view-aware DrawModifierNode entry point."""

from __future__ import annotations

import argparse
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE_PATH = ROOT / (
    "compose/src/commonMain/kotlin/com/tencent/kuikly/compose/ui/node/"
    "LayoutNodeDrawScope.kt"
)


def mask_non_code(source: str) -> str:
    masked = list(source)
    index = 0
    state = "code"
    depth = 0
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
                depth = 1
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
                depth += 1
                masked[index : index + 2] = "  "
                index += 2
                continue
            if source.startswith("*/", index):
                depth -= 1
                masked[index : index + 2] = "  "
                index += 2
                if depth == 0:
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
    raise AssertionError("unterminated drawDirect block")


def draw_direct_body(source: str) -> str:
    clean = mask_non_code(source)
    matches = list(re.finditer(r"\binternal\s+fun\s+drawDirect\s*\(", clean))
    if len(matches) != 1:
        raise AssertionError(f"expected one drawDirect function, found {len(matches)}")
    opening = clean.find("{", matches[0].end())
    if opening < 0:
        raise AssertionError("drawDirect has no body")
    return clean[opening : matching_brace(clean, opening) + 1]


def check_source(source: str) -> None:
    compact = re.sub(r"\s+", "", draw_direct_body(source))
    view_dispatch = "with(drawNode){this@LayoutNodeDrawScope.draw(kView)}"
    ordinary_dispatch = "with(drawNode){this@LayoutNodeDrawScope.draw()}"
    if compact.count(view_dispatch) != 1:
        raise AssertionError("drawDirect must invoke exactly one view-aware draw(kView)")
    if ordinary_dispatch in compact:
        raise AssertionError("drawDirect must not bypass view-aware dispatch with draw()")


def self_test() -> None:
    valid = """
        internal fun drawDirect(kView: Any?) {
            with(drawNode) {
                this@LayoutNodeDrawScope.draw(kView)
            }
        }
    """
    check_source(valid)
    for label, mutant in (
        ("ordinary dispatch", valid.replace("draw(kView)", "draw()")),
        ("removed dispatch", valid.replace("this@LayoutNodeDrawScope.draw(kView)", "Unit")),
        (
            "comment-only dispatch",
            valid.replace(
                "this@LayoutNodeDrawScope.draw(kView)",
                "// this@LayoutNodeDrawScope.draw(kView)\nUnit",
            ),
        ),
    ):
        try:
            check_source(mutant)
        except AssertionError:
            continue
        raise AssertionError(f"self-test failed to reject {label}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
    check_source(SOURCE_PATH.read_text(encoding="utf-8"))
    print("draw_modifier_view_dispatch=pass")


if __name__ == "__main__":
    main()
