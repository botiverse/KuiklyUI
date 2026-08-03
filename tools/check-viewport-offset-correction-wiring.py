#!/usr/bin/env python3
"""Keep viewport offset correction connected to KNode's production owner fence."""

from __future__ import annotations

import argparse
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE_PATH = ROOT / (
    "compose/src/commonMain/kotlin/com/tencent/kuikly/compose/ui/node/KNode.kt"
)
LAYOUT_SOURCE_PATH = ROOT / (
    "compose/src/commonMain/kotlin/com/tencent/kuikly/compose/ui/layout/"
    "SubcomposeLayout.kt"
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
    raise AssertionError("unterminated updateScrollViewOffset block")


def update_scroll_view_offset_body(source: str) -> str:
    clean = mask_non_code(source)
    matches = list(
        re.finditer(r"\bprivate\s+fun\s+updateScrollViewOffset\s*\(", clean)
    )
    if len(matches) != 1:
        raise AssertionError(
            f"expected one updateScrollViewOffset function, found {len(matches)}"
        )
    opening = clean.find("{", matches[0].end())
    if opening < 0:
        raise AssertionError("updateScrollViewOffset has no body")
    return clean[opening : matching_brace(clean, opening) + 1]


def check_source(source: str) -> None:
    compact = re.sub(r"\s+", "", update_scroll_view_offset_body(source))
    owner_input = "programmaticOffsetPending=kuiklyInfo.ignoreScrollOffset!=null"
    production_write = (
        ")?.let{correctedOffset->"
        "kuiklyInfo.composeOffset=correctedOffset.toFloat()}"
    )
    if compact.count("correctedComposeOffsetForViewportChange(") != 1:
        raise AssertionError("production must invoke exactly one viewport correction")
    if compact.count(owner_input) != 1:
        raise AssertionError("production correction must receive programmatic owner state")
    if compact.count(production_write) != 1:
        raise AssertionError("production correction result must update composeOffset")


def check_native_echo_source(source: str) -> None:
    compact = re.sub(r"\s+", "", mask_non_code(source))
    exact_echo = "NativeScrollEventDisposition.Consume->return@scroll"
    off_target_echo = (
        "NativeScrollEventDisposition.SyncOnly->{"
        "kuiklyInfo.composeOffset=offset.toFloat()"
        "return@scroll}"
    )
    if compact.count(exact_echo) != 1:
        raise AssertionError("exact programmatic echo must remain consumed")
    if compact.count(off_target_echo) != 1:
        raise AssertionError("off-target echo must adopt native offset without dispatch")


def self_test() -> None:
    valid = """
        private fun updateScrollViewOffset(curFrame: Frame, newFrame: Frame) {
            correctedComposeOffsetForViewportChange(
                composeOffset = kuiklyInfo.composeOffset.toInt(),
                nativeOffset = currentOffset,
                contentSize = currentContentSize,
                previousViewportSize = previousViewportSize,
                newViewportSize = viewportSize,
                programmaticOffsetPending = kuiklyInfo.ignoreScrollOffset != null,
            )?.let { correctedOffset ->
                kuiklyInfo.composeOffset = correctedOffset.toFloat()
            }
        }
    """
    check_source(valid)
    for label, mutant in (
        (
            "removed production write",
            valid.replace(
                ")?.let { correctedOffset ->\n"
                "                kuiklyInfo.composeOffset = correctedOffset.toFloat()\n"
                "            }",
                ")",
            ),
        ),
        (
            "removed owner fence",
            valid.replace(
                "programmaticOffsetPending = kuiklyInfo.ignoreScrollOffset != null",
                "programmaticOffsetPending = false",
            ),
        ),
        (
            "comment-only production write",
            valid.replace(
                "kuiklyInfo.composeOffset = correctedOffset.toFloat()",
                "// kuiklyInfo.composeOffset = correctedOffset.toFloat()\n"
                "                Unit",
            ),
        ),
    ):
        try:
            check_source(mutant)
        except AssertionError:
            continue
        raise AssertionError(f"self-test failed to reject {label}")

    valid_echo = """
        when (disposition) {
            NativeScrollEventDisposition.Consume -> return@scroll
            NativeScrollEventDisposition.SyncOnly -> {
                kuiklyInfo.composeOffset = offset.toFloat()
                return@scroll
            }
            NativeScrollEventDisposition.Dispatch -> Unit
        }
    """
    check_native_echo_source(valid_echo)
    for label, mutant in (
        (
            "exact echo dispatch",
            valid_echo.replace(
                "NativeScrollEventDisposition.Consume -> return@scroll",
                "NativeScrollEventDisposition.Consume -> Unit",
            ),
        ),
        (
            "off-target echo skips native adoption",
            valid_echo.replace(
                "kuiklyInfo.composeOffset = offset.toFloat()",
                "Unit",
            ),
        ),
        (
            "off-target echo dispatches",
            valid_echo.replace(
                "return@scroll\n"
                "            }\n"
                "            NativeScrollEventDisposition.Dispatch",
                "Unit\n"
                "            }\n"
                "            NativeScrollEventDisposition.Dispatch",
                1,
            ),
        ),
    ):
        try:
            check_native_echo_source(mutant)
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
    check_native_echo_source(LAYOUT_SOURCE_PATH.read_text(encoding="utf-8"))
    print("viewport_offset_correction_wiring=pass")


if __name__ == "__main__":
    main()
