#!/usr/bin/env python3
"""Validate staging3's audited two-parent migration and first-parent DCO."""

from __future__ import annotations

import argparse
import re
import subprocess
from pathlib import Path


UPSTREAM = "76a866795903014cfd2d0363ee31ccee7895775e"
FORK = "6a531a57105ee453edcfdc07a54d1bb1b4348431"
HOLD = "c8f92f053fb60d6fe515b5e986e1c6fe2db775a0"
MERGE = "25cbc1fa25be4bba0ca800a67762a1f1317c5f25"
MANIFEST = Path("docs/migrations/staging3-2.24.0.md")
SIGN_OFF_VALUE = re.compile(r"^[^<>\n]+ <[^<>\s]+@[^<>\s]+>$")


def git(*args: str, check: bool = True) -> str:
    result = subprocess.run(
        ("git", *args),
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if check and result.returncode != 0:
        raise AssertionError(
            f"git {' '.join(args)} failed ({result.returncode}): {result.stderr.strip()}"
        )
    return result.stdout.strip()


def require_equal(label: str, actual: object, expected: object) -> None:
    if actual != expected:
        raise AssertionError(f"{label}: expected {expected}, got {actual}")


def require_dco(commit: str) -> None:
    """Require a valid DCO trailer without conflating author and committer."""

    trailers = git(
        "show",
        "-s",
        "--format=%(trailers:key=Signed-off-by,valueonly,unfold)",
        commit,
    ).splitlines()
    if not any(SIGN_OFF_VALUE.fullmatch(value.strip()) for value in trailers):
        raise AssertionError(
            f"missing valid Signed-off-by trailer on first-parent commit {commit}"
        )


def validate(expected_head: str, require_clean: bool) -> list[str]:
    head = git("rev-parse", "HEAD")
    require_equal("exact head", head, expected_head)

    if require_clean:
        require_equal("checkout cleanliness", git("status", "--porcelain"), "")

    require_equal("hold parent", git("rev-parse", f"{HOLD}^"), UPSTREAM)
    require_equal("replay parents", git("show", "-s", "--format=%P", MERGE), f"{HOLD} {FORK}")
    require_equal("replay parent count", len(git("show", "-s", "--format=%P", MERGE).split()), 2)

    ancestor = subprocess.run(("git", "merge-base", "--is-ancestor", MERGE, head), check=False)
    if ancestor.returncode != 0:
        raise AssertionError(f"candidate {head} does not descend from audited replay {MERGE}")

    merge_tree = git("rev-parse", f"{MERGE}^{{tree}}")
    if merge_tree in (git("rev-parse", f"{HOLD}^{{tree}}"), git("rev-parse", f"{FORK}^{{tree}}")):
        raise AssertionError("replay merge tree equals one parent; fake ours/theirs migration suspected")

    commits = git("rev-list", "--first-parent", "--reverse", f"{UPSTREAM}..{head}").splitlines()
    if commits[:2] != [HOLD, MERGE]:
        raise AssertionError(f"unexpected migration first-parent prefix: {commits[:2]}")
    for commit in commits:
        require_dco(commit)

    manifest = MANIFEST.read_text(encoding="utf-8")
    for coordinate in (UPSTREAM, FORK, HOLD, MERGE):
        if coordinate not in manifest:
            raise AssertionError(f"migration manifest omits frozen coordinate {coordinate}")

    return commits


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--expected-head", default=None)
    parser.add_argument("--allow-dirty", action="store_true")
    args = parser.parse_args()

    expected_head = args.expected_head or git("rev-parse", "HEAD")
    commits = validate(expected_head, require_clean=not args.allow_dirty)
    print(f"head={expected_head}")
    print(f"tree={git('rev-parse', 'HEAD^{tree}')}")
    print(f"upstream={UPSTREAM}")
    print(f"fork={FORK}")
    print(f"hold={HOLD}")
    print(f"replay={MERGE}")
    print(f"replay_parents={git('show', '-s', '--format=%P', MERGE)}")
    print(f"first_parent_commits={','.join(commits)}")
    print("staging3_migration=pass")


if __name__ == "__main__":
    main()
