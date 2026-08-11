#!/usr/bin/env python3
"""Focused tests for the task-bound immutable Maven mirrors."""

from __future__ import annotations

import contextlib
import hashlib
import importlib.util
import io
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock


SCRIPT = Path(__file__).with_name("mirror_maven_manifest.py")
REPOSITORY = SCRIPT.parents[2]
TASK121_MANIFEST = REPOSITORY / "publish/predecessors/task121-current-kba-manifest.tsv"
TASK127_MANIFEST = REPOSITORY / "publish/predecessors/task127-kba010-manifest.tsv"
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
    def test_task121_repository_manifest_remains_the_exact_closure(self) -> None:
        metadata, entries = mirror.load_manifest(TASK121_MANIFEST)

        self.assertEqual(metadata["task"], "121")
        self.assertEqual(len(entries), 31)
        actual_gavs = {(item.group_id, item.artifact_id, item.version) for item in entries}
        self.assertEqual(actual_gavs, mirror.TASK121_EXPECTED_GAVS)
        paths = "\n".join(item.path for item in entries)
        self.assertNotIn("1.8.0-KBA-001", paths)
        self.assertNotIn("0.23.2.KBA-001", paths)

    def test_task127_repository_manifest_is_the_exact_frozen_closure(self) -> None:
        metadata, entries = mirror.load_manifest(TASK127_MANIFEST)

        self.assertEqual(metadata["task"], "127")
        self.assertEqual(len(entries), 13)
        self.assertEqual(sum(item.size for item in entries), 15128114)
        actual_gavs = {(item.group_id, item.artifact_id, item.version) for item in entries}
        self.assertEqual(actual_gavs, mirror.TASK127_EXPECTED_GAVS)
        paths = "\n".join(item.path for item in entries)
        for excluded in (
            "KBA-003",
            "kotlin-gradle-plugin",
            "kotlin-compiler",
            "konan",
            "jdk7",
            "jdk8",
            "kotlin-stdlib-js",
            "wasm",
        ):
            self.assertNotIn(excluded, paths)

    def _mutated_task127(self, old: str, new: str) -> Path:
        temporary = tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", suffix=".tsv", delete=False
        )
        self.addCleanup(Path(temporary.name).unlink, missing_ok=True)
        source = TASK127_MANIFEST.read_text(encoding="utf-8")
        self.assertIn(old, source)
        temporary.write(source.replace(old, new, 1))
        temporary.close()
        return Path(temporary.name)

    def test_task127_candidate_binding_is_fail_closed(self) -> None:
        manifest = self._mutated_task127(
            "# candidate_source_exact=c6afb625cd5ab547e5fbe2db85d420ede7cee847",
            "# candidate_source_exact=" + "0" * 40,
        )
        with self.assertRaisesRegex(mirror.MirrorError, "metadata mismatch"):
            mirror.load_manifest(manifest)

    def test_task127_byte_digest_mutation_is_fail_closed(self) -> None:
        manifest = self._mutated_task127(
            "4200a274bbbdfe19ef5418d3013075dddaca4d2f14c85bade6652dc6d8ad42ce",
            "0" * 64,
        )
        with self.assertRaisesRegex(mirror.MirrorError, "exact frozen closure"):
            mirror.load_manifest(manifest)

    def test_task127_authority_origin_mutation_is_fail_closed(self) -> None:
        manifest = self._mutated_task127(
            "https://mirrors.tencent.com/repository/maven-tencent/",
            "https://repo1.maven.org/maven2/",
        )
        with self.assertRaisesRegex(mirror.MirrorError, "authority does not bind"):
            mirror.load_manifest(manifest)

    def test_task127_path_escape_is_fail_closed(self) -> None:
        path = (
            "org/jetbrains/kotlin/kotlin-stdlib-common/2.0.21-KBA-010/"
            "kotlin-stdlib-common-2.0.21-KBA-010-javadoc.jar"
        )
        escaped = path.replace(
            "2.0.21-KBA-010/kotlin", "2.0.21-KBA-010/../kotlin"
        )
        manifest = self._mutated_task127(path, escaped)
        with self.assertRaisesRegex(mirror.MirrorError, "outside its GAV"):
            mirror.load_manifest(manifest)

    def test_task127_duplicate_path_is_fail_closed(self) -> None:
        lines = TASK127_MANIFEST.read_text(encoding="utf-8").splitlines()
        data_index = next(index for index, line in enumerate(lines) if not line.startswith("#"))
        lines.append(lines[data_index + 1])
        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", suffix=".tsv", delete=False
        ) as temporary:
            temporary.write("\n".join(lines) + "\n")
            path = Path(temporary.name)
        self.addCleanup(path.unlink, missing_ok=True)
        with self.assertRaisesRegex(mirror.MirrorError, "duplicate path"):
            mirror.load_manifest(path)


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

    def test_plan_classifies_all_four_frozen_states(self) -> None:
        first = entry(b"one")
        second = mirror.dataclasses.replace(
            entry(b"two"),
            path="org/example/library/1.0/library-1.0.pom",
        )
        with contextlib.redirect_stdout(io.StringIO()):
            classification = mirror.print_plan(
                [mirror.Probe(first, "ABSENT"), mirror.Probe(second, "ABSENT")]
            )
        self.assertEqual(classification, "ALL_ABSENT")

        with contextlib.redirect_stdout(io.StringIO()):
            classification = mirror.print_plan(
                [mirror.Probe(first, "EXACT"), mirror.Probe(second, "EXACT")]
            )
        self.assertEqual(classification, "ALL_COMPLETE_EXACT")

        with contextlib.redirect_stdout(io.StringIO()):
            classification = mirror.print_plan(
                [mirror.Probe(first, "EXACT"), mirror.Probe(second, "ABSENT")]
            )
        self.assertEqual(classification, "RESUMABLE_PARTIAL")

        with contextlib.redirect_stdout(io.StringIO()):
            classification = mirror.print_plan(
                [mirror.Probe(first, "EXACT"), mirror.Probe(second, "CONFLICT")]
            )
        self.assertEqual(classification, "CONFLICT")

    def test_conflict_exits_before_any_publish_attempt(self) -> None:
        item = entry(b"canonical")
        with contextlib.ExitStack() as stack:
            stack.enter_context(
                mock.patch.object(
                    mirror,
                    "load_manifest",
                    return_value=({"selection": "test"}, [item]),
                )
            )
            stack.enter_context(
                mock.patch.object(mirror, "download_authority", return_value=b"canonical")
            )
            stack.enter_context(mock.patch.object(
                mirror,
                "probe_destination",
                return_value=mirror.Probe(item, "CONFLICT", "different bytes"),
            ))
            publish = stack.enter_context(mock.patch.object(mirror, "publish_one"))
            stack.enter_context(
                mock.patch.dict(
                    mirror.os.environ,
                    {"RAFT_ARTIFACTS_PUBLISH_TOKEN": "present"},
                )
            )
            stack.enter_context(mock.patch.object(
                sys,
                "argv",
                ["mirror_maven_manifest.py", "--manifest", "unused", "--mode", "publish"],
            ))
            stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
            self.assertEqual(mirror.main(), 2)
        publish.assert_not_called()

    def test_resumable_partial_selects_only_absent_bytes(self) -> None:
        first = entry(b"one")
        second = mirror.dataclasses.replace(
            entry(b"two"),
            path="org/example/library/1.0/library-1.0.pom",
        )
        selected = mirror.select_missing(
            [first, second],
            [b"one", b"two"],
            [mirror.Probe(first, "EXACT"), mirror.Probe(second, "ABSENT")],
        )
        self.assertEqual(selected, [(second, b"two")])

    def test_authority_byte_mismatch_is_fail_closed(self) -> None:
        item = entry(b"canonical")
        with mock.patch.object(mirror, "_request", return_value=(200, b"different")):
            with self.assertRaisesRegex(mirror.MirrorError, "authority byte mismatch"):
                mirror.download_authority(item)


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
