#!/usr/bin/env python3
"""Verify or publish an immutable Maven manifest using ordinary Maven PUTs."""

from __future__ import annotations

import argparse
import base64
import concurrent.futures
import csv
import dataclasses
import hashlib
import os
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


SHA256_RE = re.compile(r"[0-9a-f]{64}")
COMPONENT_RE = re.compile(r"[A-Za-z0-9_.-]+")
DEFAULT_DESTINATION = "https://maven.artifacts.botiverse.dev"
EXPECTED_COLUMNS = [
    "groupId",
    "artifactId",
    "version",
    "path",
    "size",
    "sha256",
    "authority",
]
EXPECTED_GAVS = {
    ("org.jetbrains.kotlin", "kotlin-stdlib", "2.0.21-KBA-003"),
    ("org.jetbrains.kotlinx", "atomicfu", "0.23.2-KBA-001"),
    ("org.jetbrains.kotlinx", "atomicfu-ohosarm64", "0.23.2-KBA-001"),
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-core", "1.8.0-KBA-002"),
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-ohosarm64", "1.8.0-KBA-002"),
}
AUTHORITY_ORIGIN = "mirrors.tencent.com"
AUTHORITY_PREFIX = "/repository/maven-tencent/"


class MirrorError(RuntimeError):
    pass


@dataclasses.dataclass(frozen=True)
class Entry:
    group_id: str
    artifact_id: str
    version: str
    path: str
    size: int
    sha256: str
    authority: str


@dataclasses.dataclass(frozen=True)
class Probe:
    entry: Entry
    state: str
    detail: str = ""


def _validate_component(label: str, value: str) -> None:
    if not COMPONENT_RE.fullmatch(value):
        raise MirrorError(f"invalid {label}: {value!r}")


def load_manifest(path: Path) -> tuple[dict[str, str], list[Entry]]:
    metadata: dict[str, str] = {}
    data_lines: list[str] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("# "):
            key, separator, value = line[2:].partition("=")
            if not separator or not key or key in metadata:
                raise MirrorError(f"invalid or duplicate metadata line: {line}")
            metadata[key] = value
        elif line.strip():
            data_lines.append(line)

    for key in ("schema", "task", "selected_by", "selection", "provenance"):
        if key not in metadata:
            raise MirrorError(f"manifest metadata missing {key}")
    if metadata["schema"] != "1" or metadata["task"] != "121":
        raise MirrorError("unsupported manifest schema or task binding")

    reader = csv.DictReader(data_lines, delimiter="\t")
    if reader.fieldnames != EXPECTED_COLUMNS:
        raise MirrorError(f"unexpected columns: {reader.fieldnames}")

    entries: list[Entry] = []
    seen_paths: set[str] = set()
    for row_number, row in enumerate(reader, start=2):
        if None in row or any(row[column] is None for column in EXPECTED_COLUMNS):
            raise MirrorError(f"row {row_number}: unexpected or missing field")
        group_id = row["groupId"]
        artifact_id = row["artifactId"]
        version = row["version"]
        for label, value in (
            ("groupId", group_id),
            ("artifactId", artifact_id),
            ("version", version),
        ):
            _validate_component(label, value)

        expected_prefix = f"{group_id.replace('.', '/')}/{artifact_id}/{version}/"
        object_path = row["path"]
        if not object_path.startswith(expected_prefix) or ".." in object_path.split("/"):
            raise MirrorError(f"row {row_number}: path is outside its GAV: {object_path}")
        if object_path in seen_paths:
            raise MirrorError(f"row {row_number}: duplicate path: {object_path}")
        seen_paths.add(object_path)

        try:
            size = int(row["size"])
        except ValueError as error:
            raise MirrorError(f"row {row_number}: invalid size") from error
        if size <= 0 or not SHA256_RE.fullmatch(row["sha256"]):
            raise MirrorError(f"row {row_number}: invalid size or SHA-256")

        authority = row["authority"]
        parsed = urllib.parse.urlparse(authority)
        if (
            parsed.scheme != "https"
            or parsed.netloc != AUTHORITY_ORIGIN
            or parsed.path != AUTHORITY_PREFIX + object_path
            or parsed.params
            or parsed.query
            or parsed.fragment
        ):
            raise MirrorError(f"row {row_number}: authority does not bind the exact path")

        entries.append(
            Entry(
                group_id=group_id,
                artifact_id=artifact_id,
                version=version,
                path=object_path,
                size=size,
                sha256=row["sha256"],
                authority=authority,
            )
        )

    actual_gavs = {(entry.group_id, entry.artifact_id, entry.version) for entry in entries}
    if len(entries) != 25 or actual_gavs != EXPECTED_GAVS:
        raise MirrorError("task #121 narrowed manifest is not the exact 25-file / 5-GAV closure")
    if entries != sorted(entries, key=lambda entry: entry.path):
        raise MirrorError("manifest entries must be sorted by path")
    return metadata, entries


def _request(
    url: str,
    *,
    method: str = "GET",
    data: bytes | None = None,
    token: str | None = None,
    timeout: float = 90.0,
) -> tuple[int, bytes]:
    headers = {"User-Agent": "KuiklyUI-task121-mirror/1"}
    if token is not None:
        username = os.environ.get("RAFT_ARTIFACTS_USERNAME", "raft-ci")
        credential = base64.b64encode(f"{username}:{token}".encode()).decode()
        headers["Authorization"] = f"Basic {credential}"
    if data is not None:
        headers["Content-Type"] = "application/octet-stream"
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def _verify_bytes(entry: Entry, data: bytes, source: str) -> None:
    actual_sha = hashlib.sha256(data).hexdigest()
    if len(data) != entry.size or actual_sha != entry.sha256:
        raise MirrorError(
            f"{entry.path}: {source} byte mismatch "
            f"(size={len(data)}, sha256={actual_sha})"
        )


def download_authority(entry: Entry) -> bytes:
    status, data = _request(entry.authority)
    if status != 200:
        raise MirrorError(f"{entry.path}: authority returned HTTP {status}")
    _verify_bytes(entry, data, "authority")
    return data


def destination_url(base_url: str, entry: Entry) -> str:
    return f"{base_url.rstrip('/')}/{entry.path}"


def probe_destination(base_url: str, entry: Entry) -> Probe:
    url = destination_url(base_url, entry)
    status, _ = _request(url, method="HEAD")
    if status == 404:
        return Probe(entry, "ABSENT")
    if status != 200:
        return Probe(entry, "CONFLICT", f"HEAD HTTP {status}")
    get_status, data = _request(url)
    if get_status != 200:
        return Probe(entry, "CONFLICT", f"GET HTTP {get_status} after HEAD 200")
    try:
        _verify_bytes(entry, data, "destination")
    except MirrorError as error:
        return Probe(entry, "CONFLICT", str(error))
    return Probe(entry, "EXACT")


def parallel_map(function, values, workers: int):
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
        return list(executor.map(function, values))


def print_plan(probes: list[Probe]) -> str:
    counts = {state: sum(probe.state == state for probe in probes) for state in ("ABSENT", "EXACT", "CONFLICT")}
    if counts["CONFLICT"]:
        classification = "CONFLICT"
    elif counts["ABSENT"] == len(probes):
        classification = "ALL_ABSENT"
    elif counts["EXACT"] == len(probes):
        classification = "ALL_COMPLETE_EXACT"
    else:
        classification = "RESUMABLE_PARTIAL"
    print(
        f"classification={classification} files={len(probes)} "
        f"absent={counts['ABSENT']} exact={counts['EXACT']} conflicts={counts['CONFLICT']}"
    )
    for probe in sorted(probes, key=lambda value: value.entry.path):
        suffix = f" ({probe.detail})" if probe.detail else ""
        print(f"{probe.state}\t{probe.entry.path}{suffix}")
    return classification


def publish_one(base_url: str, token: str, entry: Entry, data: bytes) -> None:
    _verify_bytes(entry, data, "upload candidate")
    url = destination_url(base_url, entry)
    status, _ = _request(url, method="PUT", data=data, token=token)
    if status not in (200, 201, 204, 409):
        raise MirrorError(f"{entry.path}: PUT returned HTTP {status}")

    # A racing identical writer is success; a racing different writer is a conflict.
    for attempt in range(8):
        get_status, published = _request(url)
        if get_status == 200:
            _verify_bytes(entry, published, "published readback")
            return
        if get_status != 404:
            raise MirrorError(f"{entry.path}: readback returned HTTP {get_status}")
        time.sleep(min(0.5 * (2**attempt), 4.0))
    raise MirrorError(f"{entry.path}: published object did not become readable")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--mode", choices=("plan", "publish"), required=True)
    parser.add_argument("--destination", default=DEFAULT_DESTINATION)
    parser.add_argument("--workers", type=int, default=8)
    args = parser.parse_args()
    if not 1 <= args.workers <= 16:
        raise MirrorError("workers must be between 1 and 16")
    destination = urllib.parse.urlparse(args.destination)
    if (
        destination.scheme != "https"
        or not destination.netloc
        or destination.path not in ("", "/")
        or destination.params
        or destination.query
        or destination.fragment
    ):
        raise MirrorError("destination must be an HTTPS origin")
    if args.mode == "publish" and args.destination.rstrip("/") != DEFAULT_DESTINATION:
        raise MirrorError("publish mode is pinned to the Raft Artifacts Maven origin")

    metadata, entries = load_manifest(args.manifest)
    authority_bytes = parallel_map(download_authority, entries, args.workers)
    probes = parallel_map(lambda entry: probe_destination(args.destination, entry), entries, args.workers)
    classification = print_plan(probes)
    if classification == "CONFLICT":
        return 2
    if args.mode == "plan":
        print(f"authority=verified selection={metadata['selection']}")
        return 0

    token = os.environ.get("RAFT_ARTIFACTS_PUBLISH_TOKEN", "")
    if not token:
        raise MirrorError("RAFT_ARTIFACTS_PUBLISH_TOKEN is required for publish mode")
    missing = [
        (entry, data)
        for entry, data, probe in zip(entries, authority_bytes, probes, strict=True)
        if probe.state == "ABSENT"
    ]
    parallel_map(
        lambda pair: publish_one(args.destination, token, pair[0], pair[1]),
        missing,
        args.workers,
    )
    final_probes = parallel_map(lambda entry: probe_destination(args.destination, entry), entries, args.workers)
    final_classification = print_plan(final_probes)
    if final_classification != "ALL_COMPLETE_EXACT":
        raise MirrorError(f"final readback is {final_classification}")
    print(f"published_or_verified={len(entries)} uploaded={len(missing)}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except MirrorError as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)
