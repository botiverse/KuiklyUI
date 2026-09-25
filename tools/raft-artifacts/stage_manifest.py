#!/usr/bin/env python3
"""Materialise a frozen mirror manifest into a local Maven-layout directory.

Every byte is verified against the manifest digest. Pointing a consumer build
at the result — alongside Raft and with no authority repository — simulates the
post-publication world without performing any PUT, so a consumer can be tested
before an unyankable write.

Kept separate from the reviewed publication writer so that verification work
never edits publication-critical code.
"""

from __future__ import annotations

import argparse
import hashlib
import sys
import urllib.request
from pathlib import Path

class StageError(RuntimeError):
    """A staged byte did not match the frozen manifest, or the manifest is unusable."""


def _manifest_rows(manifest: Path) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    columns: list[str] | None = None
    for line in manifest.read_text(encoding="utf-8").splitlines():
        if line.startswith("# ") or not line.strip():
            continue
        fields = line.split("\t")
        if columns is None:
            columns = fields
            continue
        rows.append(dict(zip(columns, fields)))
    if not rows:
        raise StageError("manifest contains no data rows")
    return rows


def stage(manifest: Path, output: Path) -> int:
    rows = _manifest_rows(manifest)
    staged = 0
    for row in rows:
        url = row["authority"]
        target = output / row["path"]
        request = urllib.request.Request(
            url, headers={"User-Agent": "raft-task141-gate-stage"}
        )
        with urllib.request.urlopen(request, timeout=120) as response:
            if response.status != 200:
                raise StageError(f"authority returned {response.status} for {url}")
            body = response.read()
        if len(body) != int(row["size"]):
            raise StageError(f"size mismatch staging {row['path']}")
        digest = hashlib.sha256(body).hexdigest()
        if digest != row["sha256"]:
            raise StageError(f"sha256 mismatch staging {row['path']}")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(body)
        staged += 1
    print(f"TASK141_STAGED files={staged} root={output}")
    return staged


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    stage_parser = sub.add_parser("stage")
    stage_parser.add_argument("--manifest", type=Path, required=True)
    stage_parser.add_argument("--output", type=Path, required=True)


    args = parser.parse_args(argv)
    try:
        stage(args.manifest, args.output)
    except StageError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
