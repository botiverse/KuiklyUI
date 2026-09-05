#!/usr/bin/env python3
"""Reject inline native event closures retained by Compose node initializers."""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOT = ROOT / "compose/src/commonMain/kotlin"
ALLOWLIST_PATH = ROOT / "tools/compose-node-event-capture-allowlist.txt"
INITIALIZER_PATTERN = re.compile(r"\b(?:factory|viewInit)\s*=\s*\{")
EVENT_ACCESS = r"(?:getViewEvent|`getViewEvent`)\s*\(\s*\)"
EVENT_ACCESS_PATTERN = re.compile(EVENT_ACCESS)
INLINE_EVENT_PATTERN = re.compile(
    EVENT_ACCESS + r"\s*\.\s*(?P<method>[A-Za-z_][A-Za-z0-9_]*)\s*\{"
)
NAMED_EVENT_PATTERN = re.compile(
    EVENT_ACCESS + r"\s*\.\s*(?P<method>[A-Za-z_][A-Za-z0-9_]*)\s*"
    r"\(\s*(?P<handler>[A-Za-z_][A-Za-z0-9_]*)\s*\)"
)
ALLOW_COMMENT = "node-event-freshness-allow:"
CANONICAL_HELPER_PATH = (
    "compose/src/commonMain/kotlin/com/tencent/kuikly/compose/extension/NodeEventBinder.kt"
)
HELPER_IDENTIFIER = r"(?:updatedNodeEvent|`updatedNodeEvent`)"
HELPER_FUNCTION_PATTERN = re.compile(
    r"\bfun\b[^\n=(]*" + HELPER_IDENTIFIER + r"\s*\("
)
HELPER_SHADOW_PATTERNS = (
    re.compile(r"\b(?:val|var|class|object|typealias)\s+" + HELPER_IDENTIFIER),
    re.compile(r"(?:\(|,)\s*" + HELPER_IDENTIFIER + r"\s*:"),
    re.compile(r"\{[^{}]*" + HELPER_IDENTIFIER + r"[^{}]*->"),
    re.compile(r"\bas\s+" + HELPER_IDENTIFIER),
)


@dataclass(frozen=True)
class Finding:
    path: str
    line: int
    method: str
    source_line: str


def matching_brace(source: str, opening: int) -> int:
    depth = 0
    in_string = False
    escaped = False
    index = opening
    while index < len(source):
        char = source[index]
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
        elif source.startswith("//", index):
            newline = source.find("\n", index)
            index = len(source) if newline < 0 else newline
            continue
        elif source.startswith("/*", index):
            closing = source.find("*/", index + 2)
            index = len(source) if closing < 0 else closing + 2
            continue
        elif char == '"':
            in_string = True
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return index
        index += 1
    raise ValueError(f"unbalanced initializer at offset {opening}")


def initializer_ranges(source: str) -> list[tuple[int, int]]:
    ranges = []
    for match in INITIALIZER_PATTERN.finditer(source):
        opening = source.find("{", match.start())
        ranges.append((opening, matching_brace(source, opening)))
    return ranges


def line_number(source: str, offset: int) -> int:
    return source.count("\n", 0, offset) + 1


def preceding_lines(source: str, offset: int, count: int = 3) -> str:
    lines = source[:offset].splitlines()
    return "\n".join(lines[-count:])


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


def is_latest_handler_assignment_updated(code: str, handler: str, before: int) -> bool:
    assignments = list(
        re.finditer(
            rf"\bval\s+{re.escape(handler)}\b[^=\n]*=\s*(?P<rhs>[^\n]+)",
            code[:before],
        )
    )
    if not assignments:
        return False
    rhs = assignments[-1].group("rhs").lstrip()
    return re.match(HELPER_IDENTIFIER + r"\s*(?:<[^>]+>)?\s*\{", rhs) is not None


def scan_source(path: Path, source: str) -> list[Finding]:
    relative = path.relative_to(ROOT).as_posix()
    code = mask_non_code(source)
    findings = []
    for start, end in initializer_ranges(code):
        block = code[start : end + 1]
        for access in EVENT_ACCESS_PATTERN.finditer(block):
            absolute = start + access.start()
            tail = block[access.start() :]
            inline = INLINE_EVENT_PATTERN.match(tail)
            if inline is not None:
                if ALLOW_COMMENT in preceding_lines(source, absolute):
                    continue
                method = inline.group("method")
            else:
                named = NAMED_EVENT_PATTERN.match(tail)
                if named is not None and is_latest_handler_assignment_updated(
                    code,
                    named.group("handler"),
                    absolute,
                ):
                    continue
                method = (
                    f"{named.group('method')}({named.group('handler')})"
                    if named is not None
                    else "indirect event scope"
                )
            line = line_number(source, absolute)
            findings.append(
                Finding(relative, line, method, source.splitlines()[line - 1].strip())
            )
    return findings


def read_allowlist() -> dict[tuple[str, str], str]:
    entries: dict[tuple[str, str], str] = {}
    for raw_line in ALLOWLIST_PATH.read_text().splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        path, method, reason = line.split("|", 2)
        entries[(path, method)] = reason
    return entries


def helper_provenance_errors(sources: dict[str, str]) -> list[str]:
    definitions = []
    shadows = []
    for relative, source in sources.items():
        code = mask_non_code(source)
        definitions.extend(
            (relative, line_number(code, match.start()))
            for match in HELPER_FUNCTION_PATTERN.finditer(code)
        )
        for pattern in HELPER_SHADOW_PATTERNS:
            shadows.extend(
                (relative, line_number(code, match.start()))
                for match in pattern.finditer(code)
            )
    errors = []
    if len(definitions) != 1 or definitions[0][0] != CANONICAL_HELPER_PATH:
        errors.append(
            "updatedNodeEvent must have exactly one canonical function declaration in "
            f"{CANONICAL_HELPER_PATH}; found {definitions}"
        )
    if shadows:
        errors.append(f"updatedNodeEvent may not be shadowed or import-aliased; found {shadows}")
    return errors


def validate_allowlist() -> list[str]:
    errors = []
    entries = read_allowlist()
    used: dict[tuple[str, str], int] = {}
    for path in sorted(SOURCE_ROOT.rglob("*.kt")):
        source = path.read_text()
        relative = path.relative_to(ROOT).as_posix()
        for match in re.finditer(ALLOW_COMMENT, source):
            following = source[match.end() :]
            event = INLINE_EVENT_PATTERN.search(following)
            if event is None or line_number(following, event.start()) > 4:
                errors.append(f"{relative}:{line_number(source, match.start())}: orphan allow comment")
                continue
            key = (relative, event.group("method"))
            if key not in entries:
                errors.append(f"{relative}: allow comment for {key[1]} is not in allowlist")
            opening = event.end() - 1
            closing = matching_brace(following, opening)
            if following[opening + 1 : closing].strip():
                errors.append(f"{relative}: allowlisted {key[1]} handler must be capture-free and empty")
            used[key] = used.get(key, 0) + 1
    for key, count in used.items():
        if count > 1:
            errors.append(f"duplicate allow usage: {key[0]}|{key[1]} ({count})")
    for key in entries.keys() - used.keys():
        errors.append(f"stale allowlist entry: {key[0]}|{key[1]}")
    return errors


def self_test() -> None:
    raw = "factory = { KNode(view) { getViewEvent().inputFocus { use(enabled) } } }"
    named_raw = (
        "val inputFocusEvent = { use(enabled) }\n"
        "factory = { KNode(view) { getViewEvent().inputFocus(inputFocusEvent) } }"
    )
    safe = (
        "val inputFocusEvent = updatedNodeEvent { use(enabled) }\n"
        "factory = { KNode(view) { getViewEvent().inputFocus(inputFocusEvent) } }"
    )
    scoped = [
        "viewInit = { getViewEvent().run { click { use(state) } } }",
        "viewInit = { getViewEvent().apply { click { use(state) } } }",
        "viewInit = { getViewEvent().let { it.click { use(state) } } }",
        "viewInit = { with(getViewEvent()) { click { use(state) } } }",
    ]
    spaced_access = [
        "factory = { KNode(view) { getViewEvent ().inputFocus { use(enabled) } } }",
        "factory = { KNode(view) { getViewEvent/*gap*/().inputFocus { use(enabled) } } }",
        "factory = { KNode(view) { getViewEvent\n().inputFocus { use(enabled) } } }",
        "factory = { KNode(view) { `getViewEvent`().inputFocus { use(enabled) } } }",
    ]
    stable_node = "factory = { val view = rememberedNativeView; KNode(view) }"
    allowed_empty = (
        "viewInit = {\n"
        "// node-event-freshness-allow: capture-free test fixture\n"
        "getViewEvent().click { }\n"
        "}"
    )
    comment_bypass = (
        "// val inputFocusEvent = updatedNodeEvent { use(enabled) }\n"
        "val inputFocusEvent = { use(enabled) }\n"
        "factory = { KNode(view) { getViewEvent().inputFocus(inputFocusEvent) } }"
    )
    shadowed_bypass = (
        "val inputFocusEvent = updatedNodeEvent { use(first) }\n"
        "val inputFocusEvent = { use(second) }\n"
        "factory = { KNode(view) { getViewEvent().inputFocus(inputFocusEvent) } }"
    )
    fake = ROOT / "compose/src/commonMain/kotlin/Fake.kt"
    assert len(scan_source(fake, raw)) == 1
    assert len(scan_source(fake, named_raw)) == 1
    assert scan_source(fake, safe) == []
    assert all(len(scan_source(fake, fixture)) == 1 for fixture in scoped)
    assert all(len(scan_source(fake, fixture)) == 1 for fixture in spaced_access)
    assert scan_source(fake, stable_node) == []
    assert scan_source(fake, allowed_empty) == []
    assert len(scan_source(fake, comment_bypass)) == 1
    assert len(scan_source(fake, shadowed_bypass)) == 1
    canonical_source = "internal fun <P> updatedNodeEvent(event: (P) -> Unit): (P) -> Unit = event"
    assert helper_provenance_errors({CANONICAL_HELPER_PATH: canonical_source}) == []
    assert helper_provenance_errors(
        {
            CANONICAL_HELPER_PATH: canonical_source,
            "compose/src/commonMain/kotlin/Fake.kt": "fun updatedNodeEvent(block: () -> Unit) = block",
        }
    )
    assert helper_provenance_errors(
        {
            CANONICAL_HELPER_PATH: canonical_source,
            "compose/src/commonMain/kotlin/Fake.kt": "fun `updatedNodeEvent`(block: () -> Unit) = block",
        }
    )
    for lambda_shadow in (
        "val f: ((() -> Unit) -> Unit) -> Unit = {\nupdatedNodeEvent -> use(value) }",
        "val f: ((() -> Unit) -> Unit) -> Unit = { updatedNodeEvent\n-> use(value) }",
    ):
        assert helper_provenance_errors(
            {
                CANONICAL_HELPER_PATH: canonical_source,
                "compose/src/commonMain/kotlin/Fake.kt": lambda_shadow,
            }
        )
    assert helper_provenance_errors(
        {
            CANONICAL_HELPER_PATH: canonical_source,
            "compose/src/commonMain/kotlin/Fake.kt": (
                "val mapper: ((() -> Unit) -> Unit) -> Unit = { updatedNodeEvent -> "
                "updatedNodeEvent { use(value) } }"
            ),
        }
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()

    sources = {
        path.relative_to(ROOT).as_posix(): path.read_text()
        for path in sorted(SOURCE_ROOT.rglob("*.kt"))
    }
    errors = validate_allowlist() + helper_provenance_errors(sources)
    findings = []
    for relative, source in sources.items():
        findings.extend(scan_source(ROOT / relative, source))
    if findings or errors:
        for finding in findings:
            print(
                f"{finding.path}:{finding.line}: retained inline native event "
                f"'{finding.method}': {finding.source_line}",
                file=sys.stderr,
            )
        for error in errors:
            print(error, file=sys.stderr)
        print(
            "Use updatedNodeEvent and pass its stable handler to the native event API. "
            "Only capture-free stable handlers may be allowlisted.",
            file=sys.stderr,
        )
        return 1
    print("compose node event freshness audit PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
