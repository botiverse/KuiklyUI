#!/usr/bin/env python3
"""Focused tests for the task #121 immutable Maven mirror."""

from __future__ import annotations

import contextlib
import hashlib
import importlib.util
import io
from pathlib import Path
import sys
import unittest
from unittest import mock


SCRIPT = Path(__file__).with_name("mirror_maven_manifest.py")
SPEC = importlib.util.spec_from_file_location("mirror_maven_manifest", SCRIPT)
assert SPEC and SPEC.loader
mirror = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = mirror
SPEC.loader.exec_module(mirror)


def entry(data: bytes = b"canonical"):
    return mirror.Entry(
        group_id="org.example",
        artifact_id="library",
        version="1.0",
        path="org/example/library/1.0/library-1.0.jar",
        size=len(data),
        sha256=hashlib.sha256(data).hexdigest(),
        authority="https://authority.example/org/example/library/1.0/library-1.0.jar",
    )


class ManifestTest(unittest.TestCase):
    def test_repository_manifest_is_the_exact_narrowed_closure(self) -> None:
        manifest = SCRIPT.parents[2] / "publish/predecessors/task121-current-kba-manifest.tsv"
        metadata, entries = mirror.load_manifest(manifest)

        self.assertEqual(metadata["task"], "121")
        self.assertEqual(len(entries), 31)
        actual_gavs = {(item.group_id, item.artifact_id, item.version) for item in entries}
        self.assertEqual(actual_gavs, mirror.EXPECTED_GAVS)
        paths = "\n".join(item.path for item in entries)
        self.assertNotIn("1.8.0-KBA-001", paths)
        self.assertNotIn("0.23.2.KBA-001", paths)


class DestinationStateTest(unittest.TestCase):
    def test_probe_distinguishes_absent_exact_and_conflict(self) -> None:
        canonical = b"canonical"
        item = entry(canonical)

        with mock.patch.object(mirror, "_request", return_value=(404, b"")):
            self.assertEqual(mirror.probe_destination("https://repo.example", item).state, "ABSENT")

        with mock.patch.object(
            mirror,
            "_request",
            side_effect=[(200, b""), (200, canonical)],
        ):
            self.assertEqual(mirror.probe_destination("https://repo.example", item).state, "EXACT")

        with mock.patch.object(
            mirror,
            "_request",
            side_effect=[(200, b""), (200, b"different")],
        ):
            probe = mirror.probe_destination("https://repo.example", item)
            self.assertEqual(probe.state, "CONFLICT")
            self.assertIn("byte mismatch", probe.detail)

    def test_plan_classifies_resumable_partial_without_hiding_conflict(self) -> None:
        first = entry(b"one")
        second = mirror.dataclasses.replace(
            entry(b"two"),
            path="org/example/library/1.0/library-1.0.pom",
        )
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            classification = mirror.print_plan(
                [mirror.Probe(first, "EXACT"), mirror.Probe(second, "ABSENT")]
            )
        self.assertEqual(classification, "RESUMABLE_PARTIAL")

        with contextlib.redirect_stdout(io.StringIO()):
            classification = mirror.print_plan(
                [mirror.Probe(first, "EXACT"), mirror.Probe(second, "CONFLICT")]
            )
        self.assertEqual(classification, "CONFLICT")


class PublishTest(unittest.TestCase):
    def test_invalid_upload_candidate_never_reaches_the_network(self) -> None:
        item = entry(b"canonical")
        with mock.patch.object(mirror, "_request") as request:
            with self.assertRaisesRegex(mirror.MirrorError, "upload candidate byte mismatch"):
                mirror.publish_one("https://repo.example", "secret", item, b"different")
        request.assert_not_called()

    def test_racing_identical_put_is_success(self) -> None:
        canonical = b"canonical"
        item = entry(canonical)
        with mock.patch.object(
            mirror,
            "_request",
            side_effect=[(409, b""), (200, canonical)],
        ):
            mirror.publish_one("https://repo.example", "secret", item, canonical)

    def test_racing_different_put_is_a_hard_failure(self) -> None:
        canonical = b"canonical"
        item = entry(canonical)
        with mock.patch.object(
            mirror,
            "_request",
            side_effect=[(409, b""), (200, b"different")],
        ):
            with self.assertRaisesRegex(mirror.MirrorError, "byte mismatch"):
                mirror.publish_one("https://repo.example", "secret", item, canonical)


if __name__ == "__main__":
    unittest.main()
