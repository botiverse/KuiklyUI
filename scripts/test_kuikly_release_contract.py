#!/usr/bin/env python3
"""Adversarial contract tests for Kuikly's immutable Raft release set."""
from __future__ import annotations

import argparse
import copy
import hashlib
import io
import json
import plistlib
import subprocess
import tarfile
import tempfile
import threading
import time
import unittest
import urllib.parse
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from unittest import mock

import kuikly_maven_publish as publisher
import kuikly_release_contract as contract


SOURCE_SHA = "a" * 40
SOURCE_TREE = "b" * 40
FIXTURE_EXCLUDE_COUNTS = {"compose-android": 30, "core-android": 3}


def without_prefix(value: str, prefix: str) -> str:
    return value[len(prefix):] if prefix and value.startswith(prefix) else value


def frozen_source() -> dict[str, object]:
    return {
        "repository": contract.REPOSITORY,
        "commit": SOURCE_SHA,
        "tree": SOURCE_TREE,
        "tag": {
            "ref": "refs/tags/kuikly-v2.24.0-raft.1",
            "object": None,
            "commit": SOURCE_SHA,
            "state": "frozen",
        },
        "submodules": [],
    }


def valid_pom(seed: contract.Seed, *, dependency_version: str | None = None) -> bytes:
    packaging_xml = "\n  <packaging>aar</packaging>" if seed.shape == "android" else ""
    dependencies = []
    source_gav = (contract.GROUP, seed.artifact, seed.version)
    for _, target_gav in sorted(
        edge for edge in contract.EXPECTED_OWNER_POM_EDGES if edge[0] == source_gav
    ):
        group, artifact, version = target_gav
        type_xml = (
            "\n    <type>aar</type>"
            if contract.expected_pom_dependency_type(target_gav) == "aar"
            else ""
        )
        dependencies.append(f"""
  <dependency>
    <groupId>{group}</groupId><artifactId>{artifact}</artifactId>
    <version>{version}</version>{type_xml}<scope>runtime</scope>
  </dependency>""")
    if dependency_version is not None:
        dependencies.append(f"""
  <dependency>
    <groupId>org.example</groupId><artifactId>dependency</artifactId>
    <version>{dependency_version}</version>
  </dependency>""")
    dependency_xml = (
        "\n  <dependencies>" + "".join(dependencies) + "\n  </dependencies>"
        if dependencies else ""
    )
    if seed.role == "host-renderer":
        return f"""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>{contract.GROUP}</groupId>
  <artifactId>{seed.artifact}</artifactId>
  <version>{seed.version}</version>{packaging_xml}
  <properties><dev.raft.sourceSha>{SOURCE_SHA}</dev.raft.sourceSha></properties>
  <scm><url>{contract.SOURCE_BROWSE_URL}</url><tag>{SOURCE_SHA}</tag></scm>
</project>
""".encode()
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>{contract.GROUP}</groupId>
  <artifactId>{seed.artifact}</artifactId>
  <version>{seed.version}</version>{packaging_xml}
  <url>{contract.UPSTREAM_BROWSE_URL}</url>
  <properties><dev.raft.sourceSha>{SOURCE_SHA}</dev.raft.sourceSha></properties>
  <scm>
    <connection>{contract.SOURCE_SCM_CONNECTION}</connection>
    <developerConnection>{contract.SOURCE_SCM_DEVELOPER_CONNECTION}</developerConnection>
    <tag>{SOURCE_SHA}</tag><url>{contract.UPSTREAM_BROWSE_URL}</url>
  </scm>{dependency_xml}
</project>
""".encode()


def pom_dependency(root: ET.Element, artifact: str) -> ET.Element:
    dependencies = contract.direct_xml_children(root, "dependencies")
    if len(dependencies) != 1:
        raise AssertionError("fixture POM must have exactly one dependencies container")
    for dependency in contract.direct_xml_children(dependencies[0], "dependency"):
        artifacts = contract.direct_xml_children(dependency, "artifactId")
        if len(artifacts) == 1 and artifacts[0].text == artifact:
            return dependency
    raise AssertionError(f"fixture POM lacks dependency {artifact}")


def valid_module(seed: contract.Seed) -> bytes:
    owner = contract.owner_root(seed)
    identity = seed if owner is None else owner
    component = {
        "group": contract.GROUP,
        "module": identity.artifact,
        "version": identity.version,
    }
    if owner is not None:
        component["url"] = contract.owner_component_url(seed)
    dependencies = [
        {
            "group": "org.example",
            "module": f"fixture-dependency-{index}",
            "version": {"requires": "1.2.3"},
            "excludes": [{
                "group": "org.jetbrains.kotlin",
                "module": "kotlin-stdlib-common",
            }],
        }
        for index in range(FIXTURE_EXCLUDE_COUNTS.get(seed.artifact, 0))
    ]
    variants = [{"name": "runtime", "dependencies": dependencies}] if dependencies else []
    additional_files = []
    cinterop = contract.cinterop_klib_filename(seed)
    if cinterop is not None:
        additional_files.append({"name": cinterop, "url": cinterop})
    resource = contract.kotlin_resource_filename(seed)
    if resource is not None:
        additional_files.append({"name": resource, "url": resource})
    if additional_files:
        variants.append({"name": "additional-files", "files": additional_files})
    return contract.json_bytes({
        "formatVersion": "1.1",
        "component": component,
        "variants": variants,
    })


KIND_SUFFIX = {
    "pom": ".pom",
    "gradle-module": ".module",
    "jar": ".jar",
    "sources": "-sources.jar",
    "tooling-metadata": "-kotlin-tooling-metadata.json",
    "aar": ".aar",
    "javadoc": "-javadoc.jar",
    "klib": ".klib",
    "metadata-jar": "-metadata.jar",
    "xcframework-zip": ".xcframework.zip",
    "podspec-json": ".podspec.json",
    "provenance-json": ".provenance.json",
    "har": ".har",
}


def write_checksums_for(path: Path) -> None:
    body = path.read_bytes()
    for suffix, (algorithm, _) in contract.CHECKSUM_ALGORITHMS.items():
        path.with_name(path.name + suffix).write_text(
            hashlib.new(algorithm, body).hexdigest() + "\n", encoding="ascii",
        )


def write_har(path: Path, entries: list[tuple[str, bytes, bytes, str]]) -> None:
    with tarfile.open(path, "w:gz", format=tarfile.PAX_FORMAT) as archive:
        for name, body, member_type, link_name in entries:
            entry = tarfile.TarInfo(name)
            entry.type = member_type
            entry.linkname = link_name
            if member_type == tarfile.REGTYPE:
                entry.size = len(body)
                archive.addfile(entry, io.BytesIO(body))
            else:
                archive.addfile(entry)


def valid_har_entries(version: str = contract.OHOS_VERSION) -> list[tuple[str, bytes, bytes, str]]:
    return [
        ("package/libs/arm64-v8a/", b"", tarfile.DIRTYPE, ""),
        ("package/oh-package.json5", json.dumps({"version": version}).encode(), tarfile.REGTYPE, ""),
        (
            "package/src/main/cpp/types/oh-package.json5",
            b'{"version":"nested-native-descriptor"}',
            tarfile.REGTYPE,
            "",
        ),
        ("package/libs/arm64-v8a/libkuikly.so", b"so", tarfile.REGTYPE, ""),
    ]


def write_full_staging(root: Path) -> None:
    for seed in contract.SEEDS:
        coordinate = root / contract.GROUP_PATH / seed.artifact / seed.version
        coordinate.mkdir(parents=True, exist_ok=True)
        for kind in sorted(contract.required_kinds(seed)):
            filename = f"{seed.artifact}-{seed.version}{KIND_SUFFIX[kind]}"
            if kind == "pom":
                body = valid_pom(seed)
            elif kind == "gradle-module":
                body = valid_module(seed)
            else:
                body = f"{seed.coordinate}:{kind}\n".encode()
            (coordinate / filename).write_bytes(body)
        cinterop = contract.cinterop_klib_filename(seed)
        if cinterop is not None:
            (coordinate / cinterop).write_bytes(
                f"{seed.coordinate}:cinterop-klib\n".encode()
            )
        resource = contract.kotlin_resource_filename(seed)
        if resource is not None:
            (coordinate / resource).write_bytes(
                f"{seed.coordinate}:kotlin-resources\n".encode()
            )
    seed = contract.SEEDS[0]
    coordinate = root / contract.GROUP_PATH / seed.artifact / seed.version
    primary = coordinate / f"{seed.artifact}-{seed.version}.pom"
    signature = primary.with_name(primary.name + ".asc")
    signature.write_bytes(b"fixture detached signature\n")
    contract.write_checksum_companions(root)
    metadata = root / contract.GROUP_PATH / seed.artifact / "maven-metadata.xml"
    metadata.write_bytes(b"<metadata>mutable</metadata>\n")
    write_checksums_for(metadata)


def fixture_staging_specs(staging: Path) -> list[str]:
    return [f"{producer}={staging}" for producer in contract.EXPECTED_PRODUCERS]


def write_toolchains(
    path: Path,
    *,
    tag_ref: str | None = "refs/tags/kuikly-v2.24.0-raft.1",
) -> None:
    path.write_bytes(contract.json_bytes({
        "schema": "kuikly-toolchains/v1",
        "releaseSet": contract.RELEASE,
        "sourceSha": SOURCE_SHA,
        "sourceTree": SOURCE_TREE,
        "tagRef": tag_ref,
        "producers": {
            producer: {"image": "fixture@sha256:" + "c" * 64}
            for producer in contract.EXPECTED_PRODUCERS
        },
    }))


def write_producer_toolchain(
    path: Path,
    producer: str,
    *,
    source_sha: str = SOURCE_SHA,
    tag_ref: str | None = "refs/tags/kuikly-v2.24.0-raft.1",
) -> None:
    path.write_bytes(contract.json_bytes({
        "schema": "kuikly-producer-toolchain/v1",
        "releaseSet": contract.RELEASE,
        "producer": producer,
        "sourceSha": source_sha,
        "sourceTree": SOURCE_TREE,
        "tagRef": tag_ref,
        "runner": {"os": "fixture", "arch": "fixture"},
        "tools": {"python": "3.fixture"},
    }))


def assemble_fixture(root: Path) -> tuple[dict[str, object], Path, dict[str, bytes]]:
    staging = root / "staging"
    staging.mkdir()
    write_full_staging(staging)
    toolchains = root / "toolchains.json"
    write_toolchains(toolchains)
    with mock.patch.object(contract, "source_identity", return_value=frozen_source()):
        manifest, bundle = contract.assemble(
            root,
            fixture_staging_specs(staging),
            "refs/tags/kuikly-v2.24.0-raft.1",
            False,
            None,
            toolchains,
        )
    contract.validate_manifest(manifest, require_publishable=True)
    return manifest, staging, bundle


class PublicStateHttp:
    def __init__(self, public: dict[str, bytes] | None = None, listed: set[str] | None = None) -> None:
        self.public = dict(public or {})
        self.listed_override = listed

    def listing(self) -> set[str]:
        return set(self.public) if self.listed_override is None else set(self.listed_override)

    def request(
        self,
        origin: str,
        path: str,
        method: str,
        *,
        body: bytes | None = None,
        token: str | None = None,
        content_type: str | None = None,
        extra_headers: dict[str, str] | None = None,
    ) -> tuple[int, bytes]:
        del body, token, content_type, extra_headers
        if origin == publisher.CONTROL_ORIGIN and path == publisher.CONTROL_LIST_PATH and method == "GET":
            return 200, contract.json_bytes({
                "scope": contract.GROUP,
                "artifacts": [{"key": key} for key in sorted(self.listing())],
            })
        if origin == contract.PUBLIC_MAVEN_ORIGIN and method == "GET":
            key = urllib.parse.unquote(without_prefix(path, "/"))
            return (200, self.public[key]) if key in self.public else (404, b"")
        raise AssertionError(f"unexpected request: {method} {origin}{path}")


class MavenStateHttp(PublicStateHttp):
    TOKEN = "publish-token"

    def __init__(self, public: dict[str, bytes] | None = None) -> None:
        super().__init__(public)
        self.events: list[str] = []

    def request(self, origin: str, path: str, method: str, **kwargs) -> tuple[int, bytes]:
        key = urllib.parse.unquote(without_prefix(path, "/"))
        if origin == contract.PUBLIC_MAVEN_ORIGIN and method == "PUT":
            if kwargs.get("token") != self.TOKEN:
                raise AssertionError("wrong publish token")
            self.events.append("put:" + key)
            if key in self.public:
                return 409, b""
            self.public[key] = kwargs.get("body") or b""
            return 201, b""
        if origin == contract.PUBLIC_MAVEN_ORIGIN and method == "GET":
            self.events.append("get:" + key)
        return super().request(origin, path, method, **kwargs)


class ContractTests(unittest.TestCase):
    def test_maven_owner_boundary_preserves_external_nonzero(self) -> None:
        external_log = """[INFO] BUILD FAILURE
[ERROR] The following artifacts could not be resolved: androidx.annotation:annotation-experimental:jar:1.4.1, androidx.profileinstaller:profileinstaller:jar:1.3.1: Could not find artifact androidx.annotation:annotation-experimental:jar:1.4.1 in google -> [Help 1]
"""
        value = contract.classify_maven_owner_boundary(1, external_log)
        self.assertEqual("OWNER_EDGE_CLOSED", value["ownerEdgeState"])
        self.assertEqual("EXTERNAL_TRANSITIVE_DIAGNOSTIC", value["terminalState"])
        self.assertFalse(value["fullGraphPass"])
        self.assertEqual(1, value["mavenExitCode"])
        self.assertEqual(
            [
                "androidx.annotation:annotation-experimental:jar:1.4.1",
                "androidx.profileinstaller:profileinstaller:jar:1.3.1",
            ],
            value["externalUnresolvedCoordinates"],
        )

        success = contract.classify_maven_owner_boundary(0, "[INFO] BUILD SUCCESS\n")
        self.assertEqual("FULL_GRAPH_SUCCESS", success["terminalState"])
        self.assertTrue(success["fullGraphPass"])
        self.assertEqual([], success["externalUnresolvedCoordinates"])

        owner_log = """[INFO] BUILD FAILURE
[ERROR] The following artifact could not be resolved: com.tencent.kuikly-open:core-android:aar:2.24.0-raft.1-2.1.21: Could not find artifact com.tencent.kuikly-open:core-android:aar:2.24.0-raft.1-2.1.21 -> [Help 1]
"""
        with self.assertRaisesRegex(contract.ContractError, "unresolved owner-group"):
            contract.classify_maven_owner_boundary(1, owner_log)
        with self.assertRaisesRegex(contract.ContractError, "complete unresolved-coordinate list"):
            contract.classify_maven_owner_boundary(1, "[INFO] BUILD FAILURE\n")

    def test_maven_owner_readback_cannot_be_faked(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            candidate = root / "candidate"
            cache = root / "cache"
            for artifact in contract.MAVEN_OWNER_AAR_ARTIFACTS:
                relative = Path(contract.GROUP_PATH) / artifact / contract.NORMAL_VERSION
                candidate_dir = candidate / relative
                cached_dir = cache / relative
                candidate_dir.mkdir(parents=True)
                cached_dir.mkdir(parents=True)
                prefix = f"{artifact}-{contract.NORMAL_VERSION}"
                for suffix in (".pom", ".aar"):
                    body = f"{artifact}{suffix}\n".encode()
                    (candidate_dir / (prefix + suffix)).write_bytes(body)
                    (cached_dir / (prefix + suffix)).write_bytes(body)

            readback = contract.verify_maven_owner_aar_readback(candidate, cache, "")
            self.assertEqual(set(contract.MAVEN_OWNER_AAR_ARTIFACTS), set(readback))
            self.assertTrue(all(item["jarRequestOrCacheEntries"] == 0 for item in readback.values()))

            corrupt = (
                cache / contract.GROUP_PATH / "core-android" / contract.NORMAL_VERSION
                / f"core-android-{contract.NORMAL_VERSION}.aar"
            )
            corrupt.write_bytes(b"wrong\n")
            with self.assertRaisesRegex(contract.ContractError, "bytes differ from candidate"):
                contract.verify_maven_owner_aar_readback(candidate, cache, "")

            candidate_aar = (
                candidate / contract.GROUP_PATH / "core-android" / contract.NORMAL_VERSION
                / f"core-android-{contract.NORMAL_VERSION}.aar"
            )
            corrupt.write_bytes(candidate_aar.read_bytes())
            with self.assertRaisesRegex(contract.ContractError, "forbidden owner core-android JAR request"):
                contract.verify_maven_owner_aar_readback(
                    candidate,
                    cache,
                    f"Downloading core-android-{contract.NORMAL_VERSION}.jar\n",
                )

    def test_source_identity_requires_the_release_fork_origin(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            (root / "source.txt").write_text("release source\n", encoding="utf-8")
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            subprocess.run(["git", "add", "source.txt"], cwd=root, check=True)
            subprocess.run(
                [
                    "git", "-c", "user.name=Task93 Fixture",
                    "-c", "user.email=task93@example.invalid",
                    "commit", "-qm", "source",
                ],
                cwd=root, check=True,
            )
            subprocess.run(
                ["git", "remote", "add", "origin", "https://github.com/botiverse/KuiklyUI.git"],
                cwd=root, check=True,
            )
            identity = contract.source_identity(root, None, True)
            self.assertEqual("botiverse/KuiklyUI", identity["repository"])

            subprocess.run(
                ["git", "remote", "set-url", "origin", "https://github.com/bytemain/KuiklyUI.git"],
                cwd=root, check=True,
            )
            with self.assertRaisesRegex(contract.ContractError, "source origin is 'bytemain/KuiklyUI'"):
                contract.source_identity(root, None, True)

            subprocess.run(
                ["git", "remote", "set-url", "origin", "botiverse/KuiklyUI"],
                cwd=root, check=True,
            )
            with self.assertRaisesRegex(contract.ContractError, "unsupported source origin"):
                contract.source_identity(root, None, True)

    def test_landed_source_requires_staging3_ancestry(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            (root / "source.txt").write_text("base\n", encoding="utf-8")
            subprocess.run(["git", "add", "source.txt"], cwd=root, check=True)
            commit = [
                "git", "-c", "user.name=Task93 Fixture",
                "-c", "user.email=task93@example.invalid",
                "commit", "-qm",
            ]
            subprocess.run(commit + ["base"], cwd=root, check=True)
            source_sha = subprocess.run(
                ["git", "rev-parse", "HEAD"], cwd=root, check=True,
                text=True, stdout=subprocess.PIPE,
            ).stdout.strip()
            (root / "source.txt").write_text("staging3 tip\n", encoding="utf-8")
            subprocess.run(["git", "add", "source.txt"], cwd=root, check=True)
            subprocess.run(commit + ["staging3"], cwd=root, check=True)
            staging3_sha = subprocess.run(
                ["git", "rev-parse", "HEAD"], cwd=root, check=True,
                text=True, stdout=subprocess.PIPE,
            ).stdout.strip()
            subprocess.run(["git", "checkout", "-q", source_sha], cwd=root, check=True)
            contract.verify_landed_source(root, source_sha, staging3_sha)

            subprocess.run(["git", "checkout", "-qb", "unlanded", source_sha], cwd=root, check=True)
            (root / "source.txt").write_text("unlanded\n", encoding="utf-8")
            subprocess.run(["git", "add", "source.txt"], cwd=root, check=True)
            subprocess.run(commit + ["unlanded"], cwd=root, check=True)
            unlanded_sha = subprocess.run(
                ["git", "rev-parse", "HEAD"], cwd=root, check=True,
                text=True, stdout=subprocess.PIPE,
            ).stdout.strip()
            with self.assertRaisesRegex(contract.ContractError, "is not landed in staging3"):
                contract.verify_landed_source(root, unlanded_sha, staging3_sha)

    def test_exact_37_seed_closure(self) -> None:
        self.assertEqual(37, len(contract.SEEDS))
        self.assertEqual(37, len({seed.coordinate for seed in contract.SEEDS}))
        self.assertEqual(28, sum(seed.plane == "normal" for seed in contract.SEEDS))
        self.assertEqual(9, sum(seed.plane == "ohos" for seed in contract.SEEDS))
        annotations_android = next(
            seed for seed in contract.SEEDS if seed.artifact == "core-annotations-android"
        )
        self.assertEqual("metadata-member", annotations_android.role)
        self.assertEqual("core-annotations", annotations_android.owner_artifact)
        self.assertEqual("android", annotations_android.shape)
        stage = (
            Path(__file__).resolve().parents[1] / "scripts/stage-kuikly-release.sh"
        ).read_text(encoding="utf-8")
        annotations_build = (
            Path(__file__).resolve().parents[1]
            / "core-annotations/build.2.1.21.gradle.kts"
        ).read_text(encoding="utf-8")
        self.assertEqual(
            1,
            annotations_build.count('publishLibraryVariants("release")'),
            "the 37th seed must be created by a real Android release publication",
        )
        self.assertEqual(
            1,
            annotations_build.count("publishLibraryVariantsGroupedByFlavor = true"),
            "the 37th seed must use the canonical Android publication identity",
        )
        self.assertEqual(
            1,
            stage.count(":core-annotations:publishAndroidPublicationToRaftPublicationStagingRepository"),
            "the 37th seed must be produced by core-annotations' real Android publication",
        )
        self.assertNotIn(
            f"{contract.GROUP}:{contract.MANIFEST_ARTIFACT}:{contract.MANIFEST_VERSION}",
            {seed.coordinate for seed in contract.SEEDS},
        )

    def test_expected_owner_pom_graph_has_19_edges_and_two_aar_targets(self) -> None:
        self.assertEqual(19, len(contract.EXPECTED_OWNER_POM_EDGES))
        aar_edges = {
            edge for edge in contract.EXPECTED_OWNER_POM_EDGES
            if contract.expected_pom_dependency_type(edge[1]) == "aar"
        }
        self.assertEqual({
            (
                (contract.GROUP, "compose-android", contract.NORMAL_VERSION),
                (contract.GROUP, "core-android", contract.NORMAL_VERSION),
            ),
            (
                (contract.GROUP, "compose-android", contract.NORMAL_VERSION),
                (contract.GROUP, "core-annotations-android", contract.NORMAL_VERSION),
            ),
        }, aar_edges)
        for source_gav, target_gav in contract.EXPECTED_OWNER_POM_EDGES:
            seed = contract.SEED_BY_GAV[source_gav]
            root = ET.fromstring(valid_pom(seed))
            dependency = pom_dependency(root, target_gav[1])
            declared_types = [
                child.text for child in contract.direct_xml_children(dependency, "type")
            ]
            expected_types = ["aar"] if target_gav in {edge[1] for edge in aar_edges} else []
            self.assertEqual(expected_types, declared_types, f"wrong fixture type for {source_gav} -> {target_gav}")

    def test_exact_six_cinterop_klibs_are_owned_by_native_modules(self) -> None:
        self.assertEqual(6, len(contract.CINTEROP_KLIB_SCHEMA))
        self.assertEqual(6, len(contract.EXPECTED_CINTEROP_KLIB_PATHS))
        with tempfile.TemporaryDirectory() as raw:
            manifest, _, bundle = assemble_fixture(Path(raw))
        cinterop_records = {
            item["path"]: item
            for item in manifest["publications"]
            if item["kind"] == "cinterop-klib"
        }
        self.assertEqual(contract.EXPECTED_CINTEROP_KLIB_PATHS, set(cinterop_records))
        for path, record in cinterop_records.items():
            self.assertEqual("metadata-member", record["role"])
            self.assertEqual("native", record["shape"])
            self.assertIn(path, bundle)
            for suffix in contract.CHECKSUM_SUFFIXES:
                self.assertIn(path + suffix, bundle)

    def test_exact_seven_kotlin_resource_zips_are_owned_by_compose_modules(self) -> None:
        self.assertEqual(7, len(contract.KOTLIN_RESOURCE_SCHEMA))
        self.assertEqual(7, len(contract.EXPECTED_KOTLIN_RESOURCE_PATHS))
        with tempfile.TemporaryDirectory() as raw:
            manifest, _, bundle = assemble_fixture(Path(raw))
        resource_records = {
            item["path"]: item
            for item in manifest["publications"]
            if item["kind"] == "kotlin-resources"
        }
        self.assertEqual(contract.EXPECTED_KOTLIN_RESOURCE_PATHS, set(resource_records))
        for path, record in resource_records.items():
            self.assertEqual("metadata-member", record["role"])
            self.assertTrue(record["artifact"].startswith("compose-"))
            self.assertIn(path, bundle)
            for suffix in contract.CHECKSUM_SUFFIXES:
                self.assertIn(path + suffix, bundle)

    def test_four_real_producer_shape_overrides_do_not_synthesize_carriers(self) -> None:
        expected = {
            "core-render-android": {"pom", "gradle-module", "aar"},
            "core-ohosarm64": {"pom", "gradle-module", "klib", "sources"},
            "core-annotations-ohosarm64": {"pom", "gradle-module", "klib", "sources"},
            "compose-ohosarm64": {"pom", "gradle-module", "klib", "sources"},
        }
        self.assertEqual(4, len(contract.REQUIRED_KIND_OVERRIDES))
        for artifact, kinds in expected.items():
            seed = next(item for item in contract.SEEDS if item.artifact == artifact)
            self.assertEqual(kinds, set(contract.required_kinds(seed)))

    def test_gradle_producer_generically_binds_aar_dependency_types(self) -> None:
        repository = Path(__file__).resolve().parents[1]
        producer = (
            repository / "gradle/raft-artifacts-publishing.gradle.kts"
        ).read_text(encoding="utf-8")
        self.assertIn("fun raftPublicationPrimaryKindIndex(project: Project)", producer)
        self.assertIn("project.rootProject.allprojects.forEach", producer)
        self.assertIn("publication.artifacts.filter", producer)
        self.assertIn('if (primaryKind == "aar")', producer)
        self.assertIn('dependency.appendNode("type", "aar")', producer)
        self.assertIn("raftBindAarDependencyTypes", producer)
        self.assertNotIn("core-android", producer)
        self.assertNotIn("core-annotations-android", producer)

    def test_known_set_digest(self) -> None:
        objects = [
            {"path": "b", "sha256": "2" * 64, "size": 2},
            {"path": "a", "sha256": "1" * 64, "size": 1},
        ]
        self.assertEqual(
            "52dd8338808c791ce5c94dec323fc4ee63c4788d03cf072c148d7d6d8ed97f52",
            contract.canonical_set_digest(objects),
        )

    def test_toolchain_merge_exact_coverage_and_source_binding(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            receipts = []
            for producer in contract.EXPECTED_PRODUCERS:
                path = root / f"{producer}.json"
                write_producer_toolchain(path, producer)
                receipts.append(path)
            merged = contract.merge_toolchain_receipts(receipts)
            self.assertEqual(set(contract.EXPECTED_PRODUCERS), set(merged["producers"]))
            self.assertEqual(SOURCE_SHA, merged["sourceSha"])
            self.assertEqual(SOURCE_TREE, merged["sourceTree"])

            with self.assertRaisesRegex(contract.ContractError, "exactly five"):
                contract.merge_toolchain_receipts(receipts[:-1])

            write_producer_toolchain(receipts[-1], contract.EXPECTED_PRODUCERS[-1], source_sha="c" * 40)
            with self.assertRaisesRegex(contract.ContractError, "sourceSha drift"):
                contract.merge_toolchain_receipts(receipts)

            write_producer_toolchain(
                receipts[-1], contract.EXPECTED_PRODUCERS[-1],
                tag_ref="refs/tags/different",
            )
            with self.assertRaisesRegex(contract.ContractError, "tagRef drift"):
                contract.merge_toolchain_receipts(receipts)

    def test_workflow_locks_bash_no_mutation_and_no_aggregate_publication(self) -> None:
        repository = Path(__file__).resolve().parents[1]
        workflow = (repository / ".github/workflows/task93-kuikly-release.yml").read_text(encoding="utf-8")
        ignore = (repository / ".gitignore").read_text(encoding="utf-8")
        stage = (repository / "scripts/stage-kuikly-release.sh").read_text(encoding="utf-8")
        consumer = (repository / "scripts/verify-kuikly-pom-consumer.sh").read_text(encoding="utf-8")
        ohos_settings = (repository / "settings.2.0.ohos.gradle.kts").read_text(encoding="utf-8")
        self.assertIn("defaults:\n  run:\n    shell: bash\n", workflow)
        self.assertNotIn("shell: sh", workflow)
        self.assertNotIn("publishToMavenLocal", stage)
        self.assertNotIn("publishAllPublications", stage)
        self.assertNotRegex(stage, r"(?m)(?:^|[ \t])publish(?:[ \t]|$)")
        self.assertIn('kuikly_release_contract.py write-checksums', stage)
        self.assertIn('--repository "$REPOSITORY_DIR" --replace-existing', stage)
        self.assertFalse((repository / "scripts/run-kuikly-release-mutations.sh").exists())
        self.assertNotIn("mutation", workflow.lower())
        self.assertNotIn("watchdog", workflow.lower())
        self.assertIn(
            "container:\n      image: python:3.8.20-slim-bookworm",
            workflow,
            "Hosted contract must execute in the oldest supported Python container",
        )
        install_git = workflow.find("      - name: Install contract prerequisites")
        checkout = workflow.find("      - uses: actions/checkout@v4")
        self.assertGreaterEqual(
            install_git,
            0,
            "Hosted contract container must install git before checkout",
        )
        self.assertIn(
            "apt-get install --yes --no-install-recommends git",
            workflow,
            "Hosted contract container must install git before checkout",
        )
        self.assertLess(
            install_git,
            checkout,
            "Hosted contract container must install git before checkout",
        )
        self.assertIn('test "$(python3 --version)" = "Python 3.8.20"', workflow)
        assemble_workflow = workflow.split("\n  assemble-candidate:\n", 1)[1].split(
            "\n  publish:\n", 1
        )[0]
        self.assertIn(
            "    timeout-minutes: 45\n",
            assemble_workflow,
            "the 920-path public preflight must retain enough time for slow anonymous readback",
        )
        publish_workflow = workflow.split("\n  publish:\n", 1)[1]
        self.assertIn(
            "    timeout-minutes: 90\n",
            publish_workflow,
            "the bounded parallel writer must retain a full retry budget",
        )
        self.assertIn(
            "      candidate_run_id:\n"
            "        description: Terminal publish=false run whose exact producer shards the writer must reuse\n"
            "        required: false\n"
            "        type: string\n",
            workflow,
            "the writer must take an explicit immutable producer-shard carrier",
        )
        self.assertIn(
            "      control_plane_sha:\n"
            "        description: Landed staging3 SHA supplying the publisher classifier and its contract\n"
            "        required: false\n"
            "        type: string\n",
            workflow,
        )
        self.assertIn(
            "      PINNED_CANDIDATE_RUN_ID: ${{ inputs.candidate_run_id }}\n",
            publish_workflow,
        )
        self.assertIn(
            '          [[ "$PINNED_CANDIDATE_RUN_ID" =~ ^[1-9][0-9]*$ ]]\n'
            '          test "$PINNED_CANDIDATE_RUN_ID" != "$GITHUB_RUN_ID"\n',
            publish_workflow,
            "the writer must reject an absent or self-referential candidate run",
        )
        self.assertIn('          [[ "$CONTROL_PLANE_SHA" =~ ^[0-9a-f]{40}$ ]]\n', publish_workflow)
        self.assertIn('          test "$live_staging3" = "$CONTROL_PLANE_SHA"\n', publish_workflow)
        self.assertIn(
            '          git worktree add --detach "$control_plane" "$CONTROL_PLANE_SHA"\n',
            publish_workflow,
        )
        self.assertIn(
            'python3 "$control_plane/scripts/kuikly_maven_publish.py" plan',
            publish_workflow,
        )
        self.assertIn(
            'python3 "$RUNNER_TEMP/kuikly-control-plane/scripts/kuikly_maven_publish.py" release',
            publish_workflow,
        )
        self.assertNotIn(
            "python3 scripts/kuikly_maven_publish.py release",
            publish_workflow,
            "writer must never fall back to the frozen product source publisher",
        )
        self.assertIn(
            "PublisherTests.test_checksum_listing_is_optional_but_exact_get_is_required",
            publish_workflow,
        )
        self.assertIn(
            "'.state == \"PARTIAL_EXACT\" and .presentCount >= 69 and .presentCount < 920 and .productFileCount == 920",
            publish_workflow,
            "a resumed writer must accept only a non-regressing exact partial publication",
        )
        self.assertEqual(
            5,
            publish_workflow.count("          run-id: ${{ inputs.candidate_run_id }}\n"),
            "all five writer shards must come from the same pinned candidate run",
        )
        self.assertEqual(5, publish_workflow.count("          github-token: ${{ github.token }}\n"))
        self.assertEqual(5, publish_workflow.count("          repository: ${{ github.repository }}\n"))
        self.assertNotIn(
            "run-id: ${{ inputs.candidate_run_id }}",
            assemble_workflow,
            "source-review candidate assembly must continue using its own run's shards",
        )
        self.assertNotIn(
            "actions/setup-python",
            workflow,
            "Hosted must not fall back to a runner-dependent Python toolcache",
        )
        self.assertEqual(
            2,
            workflow.count("          scripts/verify-kuikly-pom-consumer.sh \\\n"),
            "candidate and protected final assembly must both use fresh POM consumers",
        )
        self.assertIn(
            'metadataSources {\n            mavenPom()\n            artifact()\n'
            '            ignoreGradleMetadataRedirection()',
            consumer,
        )
        self.assertEqual(
            4,
            consumer.count("            ignoreGradleMetadataRedirection()"),
            "every Gradle consumer repository must remain POM-only",
        )
        self.assertNotIn('artifact {\n            type = "aar"', consumer)
        self.assertNotIn("<type>aar</type>", consumer)
        self.assertIn('readonly GRADLE_HOME="$WORK_ROOT/gradle-home"', consumer)
        self.assertIn('readonly MAVEN_HOME="$WORK_ROOT/maven-home"', consumer)
        self.assertIn('require(gradle.gradleVersion == "7.6.3")', consumer)
        self.assertIn('readonly MAVEN_VERSION_OUTPUT="$("$MAVEN_BIN" --version)"', consumer)
        self.assertIn('[[ "$MAVEN_VERSION_OUTPUT" == *"Apache Maven 3.8.7"* ]]', consumer)
        self.assertEqual(
            2,
            workflow.count("sudo apt-get install --yes --no-install-recommends maven=3.8.7-2"),
            "candidate and protected consumers must use the exact Maven 3.8.7 carrier",
        )
        self.assertIn('-Dartifact="$TARGET:pom" -Dtransitive=true', consumer)
        self.assertIn("transitiveTypeOverrides\": 0", consumer)
        self.assertIn('readonly -a MAVEN_PIPE_STATUS=("${PIPESTATUS[@]}")', consumer)
        self.assertIn('readonly MAVEN_EXIT_CODE="${MAVEN_PIPE_STATUS[0]}"', consumer)
        self.assertIn('"schema": "kuikly-pom-consumer/v2"', consumer)
        self.assertIn('raw_maven_output = output.with_name("pom-consumer-maven-raw.log")', consumer)
        self.assertIn('raw_gradle_output = output.with_name("pom-consumer-gradle-owner.tsv")', consumer)
        self.assertIn('raw_maven_output.write_bytes(maven_log.read_bytes())', consumer)
        self.assertEqual(3, workflow.count("pom-consumer-maven-raw.log"))
        self.assertEqual(3, workflow.count("pom-consumer-gradle-owner.tsv"))
        self.assertIn('"fullGraphState": "FULL_GRAPH_SUCCESS"', consumer)
        self.assertIn("contract.verify_maven_owner_aar_readback", consumer)
        self.assertIn("contract.classify_maven_owner_boundary", consumer)
        self.assertIn('"OWNER_EDGE_CLOSED"', (repository / "scripts/kuikly_release_contract.py").read_text(encoding="utf-8"))
        self.assertIn('"EXTERNAL_TRANSITIVE_DIAGNOSTIC"', (repository / "scripts/kuikly_release_contract.py").read_text(encoding="utf-8"))
        self.assertNotIn("profileinstaller", consumer)
        self.assertNotIn("annotation-experimental", consumer)
        self.assertNotIn("<dependency>", consumer)
        self.assertNotIn("<exclusions>", consumer)
        self.assertIn(
            "compose POM did not resolve the exact core-android AAR",
            consumer,
        )
        self.assertIn(
            "compose POM did not resolve the exact core-annotations-android AAR",
            consumer,
        )
        self.assertEqual(
            1,
            consumer.count(
                '            it.startsWith("com.tencent.kuikly-open\\tcore-android\\t'
                '2.24.0-raft.1-2.1.21\\taar\\t")'
            ),
        )
        self.assertEqual(
            1,
            consumer.count(
                '            it.startsWith("com.tencent.kuikly-open\\tcore-annotations-android\\t'
                '2.24.0-raft.1-2.1.21\\taar\\t")'
            ),
        )
        self.assertIn("        require(records.any {", consumer)
        contract_source = (repository / "scripts/kuikly_release_contract.py").read_text(encoding="utf-8")
        self.assertIn("Maven attempted or cached forbidden owner {artifact} JAR fallback", contract_source)
        self.assertIn("Maven log records forbidden owner {artifact} JAR request", contract_source)
        self.assertIn("DIFF_BASE_SHA: ${{ github.event_name == 'pull_request'", workflow)
        self.assertIn('git diff --check "$diff_base" "$SOURCE_SHA"', workflow)
        self.assertIn('test "$GITHUB_REF" = "refs/heads/staging3"', workflow)
        self.assertIn('git ls-remote --exit-code "https://github.com/${GITHUB_REPOSITORY}.git" refs/heads/staging3', workflow)
        self.assertIn("verify-landed-source", workflow)
        self.assertNotIn("git diff --check HEAD^", workflow)
        self.assertIn("git check-ignore -q .bundle/config", workflow)
        self.assertIn("git check-ignore -q vendor/bundle/task93-bootstrap", workflow)
        self.assertEqual(1, ignore.splitlines().count("/.bundle/"))
        self.assertEqual(1, ignore.splitlines().count("/vendor/bundle/"))
        self.assertEqual(
            2,
            stage.count('[[ -z "$(git status --porcelain --untracked-files=all)" ]] || fail'),
        )
        with tempfile.TemporaryDirectory() as raw:
            fixture = Path(raw)
            (fixture / ".gitignore").write_text(ignore, encoding="utf-8")
            (fixture / "source.kt").write_text("clean\n", encoding="utf-8")
            subprocess.run(["git", "init", "-q"], cwd=fixture, check=True)
            subprocess.run(["git", "add", ".gitignore", "source.kt"], cwd=fixture, check=True)
            subprocess.run(
                [
                    "git", "-c", "user.name=Task93 Fixture",
                    "-c", "user.email=task93@example.invalid",
                    "commit", "-qm", "fixture",
                ],
                cwd=fixture,
                check=True,
            )
            (fixture / ".bundle").mkdir()
            (fixture / ".bundle" / "config").write_text("BUNDLE_PATH: vendor/bundle\n", encoding="utf-8")
            (fixture / "vendor" / "bundle").mkdir(parents=True)
            (fixture / "vendor" / "bundle" / "cache").write_text("ephemeral\n", encoding="utf-8")

            def status() -> str:
                return subprocess.run(
                    ["git", "status", "--porcelain", "--untracked-files=all"],
                    cwd=fixture,
                    check=True,
                    text=True,
                    stdout=subprocess.PIPE,
                ).stdout

            self.assertEqual("", status(), "declared Bundler cache must stay invisible")
            (fixture / "source.kt").write_text("dirty\n", encoding="utf-8")
            self.assertIn("source.kt", status(), "tracked source dirt must remain visible")
            (fixture / "source.kt").write_text("clean\n", encoding="utf-8")
            (fixture / "vendor" / "source.kt").write_text("unexpected\n", encoding="utf-8")
            self.assertIn("vendor/source.kt", status(), "the vendor parent must not be broadly ignored")
        self.assertIn(
            "RAFT_REQUIRE_PUBLIC_PREDECESSORS: ${{ github.event_name == 'workflow_dispatch' && inputs.publish && 'true' || 'false' }}",
            workflow,
        )
        self.assertEqual(
            6,
            workflow.count(
                "if: ${{ !(github.event_name == 'workflow_dispatch' && inputs.publish == true) }}"
            ),
        )
        self.assertIn("always() &&", workflow)
        self.assertIn("needs.contract.result == 'success'", workflow)
        for job in (
            "assemble-candidate",
            "normal-linux",
            "normal-macos",
            "ios-renderer",
            "ohos-gradle",
            "ohos-renderer",
        ):
            self.assertIn("needs.%s.result == 'skipped'" % job, workflow)
        self.assertIn(
            "needs: [contract, assemble-candidate, normal-linux, normal-macos, ios-renderer, ohos-gradle, ohos-renderer]",
            workflow,
        )
        self.assertEqual(1, workflow.count("secrets.RAFT_ARTIFACTS_PUBLISH_TOKEN"))
        self.assertIn("scripts/kuikly_maven_publish.py", workflow)
        self.assertIn('state == "PARTIAL_EXACT"', workflow)
        self.assertNotIn("scripts/kuikly_atomic_publish.py", workflow)
        self.assertNotIn("RAFT_KUIKLY_NORMAL_PUBLISH_TOKEN", workflow)
        self.assertNotIn("RAFT_KUIKLY_OHOS_PUBLISH_TOKEN", workflow)
        self.assertNotIn("RAFT_KUIKLY_MANIFEST_PUBLISH_TOKEN", workflow)
        self.assertNotIn("token_receipt", workflow)
        self.assertNotIn("token-receipt", workflow)
        self.assertNotIn("raftArtifactsPluginPredecessors", ohos_settings)
        self.assertIn("raftArtifactsRequiredPredecessors", ohos_settings)
        self.assertGreaterEqual(ohos_settings.count("exclusiveContent"), 1)
        self.assertEqual(
            1,
            ohos_settings.count(
                'includeGroupByRegex("org\\\\.jetbrains\\\\.kotlin.*")'
            ),
        )
        self.assertEqual(
            1,
            ohos_settings.count(
                'includeModule("org.jetbrains.kotlin", "kotlin-stdlib")'
            ),
        )
        self.assertEqual(
            1,
            ohos_settings.count(
                'includeModule("org.jetbrains.kotlin", "kotlin-stdlib-common")'
            ),
        )

    def test_producer_refreshes_stale_gradle_checksum_sidecars(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            repository = Path(raw)
            coordinate = repository / contract.GROUP_PATH / "fixture" / "1.0"
            coordinate.mkdir(parents=True)
            primary = coordinate / "fixture-1.0.jar"
            primary.write_bytes(b"final publication bytes\n")
            stale = primary.with_name(primary.name + ".md5")
            stale.write_text("0" * 32 + "\n", encoding="ascii")
            with self.assertRaisesRegex(contract.ContractError, "preexisting checksum companion differs"):
                contract.write_checksum_companions(repository)
            contract.write_checksum_companions(repository, replace_existing=True)
            self.assertEqual(
                hashlib.md5(primary.read_bytes()).hexdigest() + "\n",
                stale.read_text(encoding="ascii"),
            )
            for suffix, (algorithm, _) in contract.CHECKSUM_ALGORITHMS.items():
                self.assertEqual(
                    hashlib.new(algorithm, primary.read_bytes()).hexdigest() + "\n",
                    primary.with_name(primary.name + suffix).read_text(encoding="ascii"),
                )

    def test_ios_pod_lock_bootstrap_ignores_release_version(self) -> None:
        repository = Path(__file__).resolve().parents[1]
        stage = (repository / "scripts/stage-kuikly-release.sh").read_text(encoding="utf-8")
        bootstrap = (
            "env -u KUIKLY_VERSION KUIKLY_RELEASE_FRAMEWORK_BUILD=1 \\\n"
            "    BUNDLE_PATH=\"$SOURCE_ROOT/vendor/bundle\" BUNDLE_DEPLOYMENT=true \\\n"
            "    bundle exec pod install --project-directory=iosApp --deployment"
        )
        self.assertIn(
            bootstrap,
            stage,
            "iOS Pod lock bootstrap must unset KUIKLY_VERSION",
        )
        self.assertEqual(1, stage.count(bootstrap))
        self.assertNotIn(
            "\n  bundle exec pod install --project-directory=iosApp --deployment",
            stage,
            "iOS Pod lock bootstrap must not resolve the release version into the repository lock",
        )
        self.assertLess(stage.index(bootstrap), stage.index("xcodebuild archive"))

    def test_ios_renderer_release_uses_static_framework_pods(self) -> None:
        repository = Path(__file__).resolve().parents[1]
        podfile = (repository / "iosApp/Podfile").read_bytes()
        lock = (repository / "iosApp/Podfile.lock").read_text(encoding="utf-8")
        stage = (repository / "scripts/stage-kuikly-release.sh").read_text(encoding="utf-8")
        self.assertIn(
            b"use_frameworks! :linkage => :static if ENV['KUIKLY_RELEASE_FRAMEWORK_BUILD'] == '1'",
            podfile,
        )
        self.assertIn(
            "env -u KUIKLY_VERSION KUIKLY_RELEASE_FRAMEWORK_BUILD=1",
            stage,
            "release pod bootstrap must enable static framework mode",
        )
        self.assertIn(
            "PODFILE CHECKSUM: " + hashlib.sha1(podfile).hexdigest(),
            lock,
            "Podfile.lock must bind the conditional framework Podfile bytes",
        )

    def test_native_renderers_use_disposable_detached_source_worktrees(self) -> None:
        repository = Path(__file__).resolve().parents[1]
        stage = (repository / "scripts/stage-kuikly-release.sh").read_text(encoding="utf-8")
        add_command = 'git -C "$SOURCE_ROOT" worktree add --detach "$build_root" "$SOURCE_SHA"'
        self.assertIn(
            add_command,
            stage,
            "native renderers must create an exact detached build worktree",
        )
        self.assertEqual(1, stage.count(add_command))
        self.assertEqual(2, stage.count('create_native_build_worktree "$build_root"'))
        self.assertEqual(2, stage.count('trap native_build_cleanup EXIT'))
        self.assertEqual(2, stage.count('cd "$build_root"'))
        self.assertEqual(
            4,
            stage.count('python3 "$SOURCE_ROOT/scripts/kuikly_release_contract.py" package-'),
        )
        self.assertEqual(4, stage.count('--source-root "$SOURCE_ROOT"'))
        self.assertIn(
            'git -C "$SOURCE_ROOT" worktree remove --force "$build_root"',
            stage,
            "native renderer scratch worktrees must be removed before producer return",
        )
        self.assertIn(
            'BUNDLE_PATH="$SOURCE_ROOT/vendor/bundle" BUNDLE_DEPLOYMENT=true',
            stage,
            "the isolated iOS checkout must reuse the pinned Bundler installation",
        )

        ios_body = stage.split("run_ios_renderer() (", 1)[1].split(
            "\n)\n\nrequire_ohos_environment", 1,
        )[0]
        ohos_body = stage.split("run_ohos_renderer() (", 1)[1].split(
            "\n)\n\ncase \"$MODE\" in", 1,
        )[0]
        for body, build_marker in (
            (ios_body, "./gradlew :demo:generateDummyFramework"),
            (ohos_body, "ohpm install --all"),
        ):
            self.assertLess(
                body.index('create_native_build_worktree "$build_root"'),
                body.index(build_marker),
                "native build must enter the detached checkout before executing its toolchain",
            )
            self.assertIn('cd "$build_root"', body)
            self.assertIn('--source-root "$SOURCE_ROOT"', body)

        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            source = root / "source"
            scratch = root / "scratch"
            source.mkdir()
            tracked = source / "tracked.txt"
            tracked.write_text("committed\n", encoding="utf-8")
            subprocess.run(["git", "init", "-q"], cwd=source, check=True)
            subprocess.run(["git", "add", "tracked.txt"], cwd=source, check=True)
            subprocess.run(
                [
                    "git", "-c", "user.name=Task93 Fixture",
                    "-c", "user.email=task93@example.invalid",
                    "commit", "-qm", "fixture",
                ],
                cwd=source,
                check=True,
            )
            subprocess.run(
                ["git", "worktree", "add", "--detach", str(scratch), "HEAD"],
                cwd=source,
                check=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            (scratch / "tracked.txt").write_text("native toolchain mutation\n", encoding="utf-8")
            original_status = subprocess.run(
                ["git", "status", "--porcelain", "--untracked-files=all"],
                cwd=source,
                check=True,
                text=True,
                stdout=subprocess.PIPE,
            ).stdout
            self.assertEqual("", original_status, "scratch dirt must not mutate the source authority")
            subprocess.run(
                ["git", "worktree", "remove", "--force", str(scratch)],
                cwd=source,
                check=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            self.assertFalse(scratch.exists(), "scratch worktree must be disposable")
            self.assertEqual(
                "",
                subprocess.run(
                    ["git", "status", "--porcelain", "--untracked-files=all"],
                    cwd=source,
                    check=True,
                    text=True,
                    stdout=subprocess.PIPE,
                ).stdout,
            )

    def test_release_contract_avoids_python_39_only_runtime_features(self) -> None:
        repository = Path(__file__).resolve().parents[1]
        contract_source = (repository / "scripts/kuikly_release_contract.py").read_text(
            encoding="utf-8",
        )
        for helper in (".removeprefix(", ".removesuffix("):
            self.assertNotIn(
                helper,
                contract_source,
                "production release contract must not require Python 3.9 string helpers",
            )
        self.assertNotIn(
            "companion | companion_details",
            contract_source,
            "production release contract must not require Python 3.9 dict merge",
        )
        self.assertEqual("botiverse/KuiklyUI", contract.without_suffix("botiverse/KuiklyUI.git", ".git"))
        self.assertEqual("OpenKuiklyIOSRender", contract.without_suffix("OpenKuiklyIOSRender", ".framework"))
        self.assertEqual("", contract.without_suffix("", ".git"))
        self.assertEqual("value", contract.without_suffix("value", ""))

    def test_ohos_kotlin_plugin_ids_map_to_the_canonical_kba_module(self) -> None:
        repository = Path(__file__).resolve().parents[1]
        settings = (repository / "settings.2.0.ohos.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('requested.id.id == "org.jetbrains.kotlin.multiplatform"', settings)
        self.assertIn('requested.id.id == "org.jetbrains.kotlin.plugin.compose"', settings)
        self.assertIn(
            'useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}")',
            settings,
            "KBA plugin ids must bypass their unpublished marker modules",
        )
        self.assertIn(
            'useModule("org.jetbrains.kotlin:compose-compiler-gradle-plugin:${requested.version}")',
            settings,
            "the Compose plugin id must use its own implementation module",
        )

    def test_ohos_renderer_reads_the_external_module_build_directory(self) -> None:
        repository = Path(__file__).resolve().parents[1]
        stage = (repository / "scripts/stage-kuikly-release.sh").read_text(encoding="utf-8")
        self.assertIn("find core-render-ohos/build -type f -name '*.har'", stage)
        self.assertNotIn("find ohosApp/render/build", stage)

    def test_pom_comment_spoof_and_dynamic_dependency_are_red(self) -> None:
        seed = contract.SEEDS[0]
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            spoof = root / "spoof.pom"
            spoof.write_text(
                f"<project><!-- {contract.GROUP}:{seed.artifact}:{seed.version} {SOURCE_SHA} --></project>",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(contract.ContractError, "POM (profile shape|identity/provenance)"):
                contract.parse_pom(spoof, seed, SOURCE_SHA)

            def mutate_field(
                pom_seed: contract.Seed,
                label: str,
                parent_name: str | None,
                field_name: str,
                value: str,
            ) -> Path:
                pom_root = ET.fromstring(valid_pom(pom_seed))
                parent = pom_root
                if parent_name is not None:
                    parent = contract.direct_xml_children(pom_root, parent_name)[0]
                contract.direct_xml_children(parent, field_name)[0].text = value
                result = root / f"{pom_seed.artifact}-{label}.pom"
                result.write_bytes(ET.tostring(pom_root, encoding="utf-8", xml_declaration=True))
                return result

            gradle_fields = (
                (None, "groupId", "org.example"),
                (None, "artifactId", seed.artifact + "-wrong"),
                (None, "version", seed.version + "-wrong"),
                ("properties", "dev.raft.sourceSha", "b" * 40),
                (None, "url", contract.SOURCE_BROWSE_URL),
                ("scm", "url", contract.SOURCE_BROWSE_URL),
                ("scm", "connection", "scm:git:https://github.com/example/wrong.git"),
                ("scm", "developerConnection", "scm:git:ssh://git@github.com/example/wrong.git"),
                ("scm", "tag", "b" * 40),
            )
            for index, (parent_name, field_name, wrong_value) in enumerate(gradle_fields):
                with self.subTest(profile="gradle", field=field_name, parent=parent_name):
                    wrong = mutate_field(
                        seed, f"gradle-field-{index}", parent_name, field_name, wrong_value,
                    )
                    with self.assertRaisesRegex(contract.ContractError, "identity/provenance mismatch"):
                        contract.parse_pom(wrong, seed, SOURCE_SHA)

            renderer = next(item for item in contract.SEEDS if item.role == "host-renderer")
            renderer_fields = (
                (None, "groupId", "org.example"),
                (None, "artifactId", renderer.artifact + "-wrong"),
                (None, "version", renderer.version + "-wrong"),
                ("properties", "dev.raft.sourceSha", "b" * 40),
                ("scm", "url", contract.UPSTREAM_BROWSE_URL),
                ("scm", "tag", "b" * 40),
            )
            for index, (parent_name, field_name, wrong_value) in enumerate(renderer_fields):
                with self.subTest(profile="renderer", field=field_name, parent=parent_name):
                    wrong = mutate_field(
                        renderer, f"renderer-field-{index}", parent_name, field_name, wrong_value,
                    )
                    with self.assertRaisesRegex(contract.ContractError, "identity/provenance mismatch"):
                        contract.parse_pom(wrong, renderer, SOURCE_SHA)

            for label, parent_name, field_name in (
                ("forbidden-project-url", None, "url"),
                ("forbidden-connection", "scm", "connection"),
                ("forbidden-developer-connection", "scm", "developerConnection"),
            ):
                with self.subTest(profile="renderer", field=field_name):
                    pom_root = ET.fromstring(valid_pom(renderer))
                    parent = pom_root if parent_name is None else contract.direct_xml_children(
                        pom_root, parent_name,
                    )[0]
                    namespace = pom_root.tag[: pom_root.tag.index("}") + 1]
                    ET.SubElement(parent, namespace + field_name).text = "forbidden"
                    wrong = root / f"{renderer.artifact}-{label}.pom"
                    wrong.write_bytes(ET.tostring(pom_root, encoding="utf-8", xml_declaration=True))
                    with self.assertRaisesRegex(contract.ContractError, "field shape mismatch"):
                        contract.parse_pom(wrong, renderer, SOURCE_SHA)

            compose_android = next(
                item for item in contract.SEEDS if item.artifact == "compose-android"
            )
            compose_pom = root / "compose-android.pom"
            compose_pom.write_bytes(valid_pom(compose_android))
            self.assertIn(
                (contract.GROUP, "core-annotations-android", compose_android.version),
                contract.parse_pom(compose_pom, compose_android, SOURCE_SHA),
                "the generated fixture must preserve the real compose POM edge",
            )
            dynamic = root / "dynamic.pom"
            dynamic.write_bytes(valid_pom(seed, dependency_version="1.+"))
            with self.assertRaisesRegex(contract.ContractError, "dependency version is not exact|dynamic/changing dependency"):
                contract.parse_pom(dynamic, seed, SOURCE_SHA)
            incomplete = root / "incomplete.pom"
            incomplete.write_bytes(
                valid_pom(seed).replace(
                    b"</project>",
                    b"<dependencies><dependency><groupId>org.example</groupId>"
                    b"<artifactId>missing-version</artifactId></dependency></dependencies></project>",
                )
            )
            with self.assertRaisesRegex(contract.ContractError, "lacks explicit group/artifact/version"):
                contract.parse_pom(incomplete, seed, SOURCE_SHA)

            module = root / "dependency.module"
            module.write_bytes(contract.json_bytes({
                "formatVersion": "1.1",
                "component": {
                    "group": contract.GROUP,
                    "module": seed.artifact,
                    "version": seed.version,
                },
                "variants": [{
                    "name": "runtime",
                    "dependencies": [{
                        "group": "org.example",
                        "module": "module-dependency",
                        "version": {"requires": "1.2.3"},
                    }],
                }],
            }))
            refs = contract.parse_module(module, seed)
            self.assertIn(("org.example", "module-dependency", "1.2.3"), refs)
            incomplete_module = root / "incomplete.module"
            incomplete_module.write_bytes(module.read_bytes().replace(b'"requires":"1.2.3"', b'"prefers":"1.2.3"'))
            with self.assertRaisesRegex(contract.ContractError, "lacks binding requires/strictly"):
                contract.parse_module(incomplete_module, seed)

    def test_pom_aar_dependency_omitted_type_is_red(self) -> None:
        seed = contract.SEED_BY_GAV[(contract.GROUP, "compose-android", contract.NORMAL_VERSION)]
        root = ET.fromstring(valid_pom(seed))
        dependency = pom_dependency(root, "core-annotations-android")
        dependency.remove(contract.direct_xml_children(dependency, "type")[0])
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / "omitted-aar-type.pom"
            path.write_bytes(ET.tostring(root, encoding="utf-8", xml_declaration=True))
            with self.assertRaisesRegex(contract.ContractError, "POM AAR dependency type mismatch"):
                contract.parse_pom(path, seed, SOURCE_SHA)

    def test_pom_aar_dependency_wrong_type_is_red(self) -> None:
        seed = contract.SEED_BY_GAV[(contract.GROUP, "compose-android", contract.NORMAL_VERSION)]
        root = ET.fromstring(valid_pom(seed))
        dependency = pom_dependency(root, "core-annotations-android")
        contract.direct_xml_children(dependency, "type")[0].text = "jar"
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / "wrong-aar-type.pom"
            path.write_bytes(ET.tostring(root, encoding="utf-8", xml_declaration=True))
            with self.assertRaisesRegex(contract.ContractError, "POM AAR dependency type mismatch"):
                contract.parse_pom(path, seed, SOURCE_SHA)

    def test_pom_dependency_duplicate_type_is_red(self) -> None:
        seed = contract.SEED_BY_GAV[(contract.GROUP, "compose-android", contract.NORMAL_VERSION)]
        root = ET.fromstring(valid_pom(seed))
        dependency = pom_dependency(root, "core-annotations-android")
        namespace = root.tag[: root.tag.index("}") + 1]
        ET.SubElement(dependency, namespace + "type").text = "aar"
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / "duplicate-aar-type.pom"
            path.write_bytes(ET.tostring(root, encoding="utf-8", xml_declaration=True))
            with self.assertRaisesRegex(contract.ContractError, "duplicate type fields"):
                contract.parse_pom(path, seed, SOURCE_SHA)

    def test_pom_non_aar_dependency_aar_type_is_red(self) -> None:
        seed = contract.SEED_BY_GAV[(contract.GROUP, "compose", contract.NORMAL_VERSION)]
        root = ET.fromstring(valid_pom(seed))
        dependency = pom_dependency(root, "core")
        namespace = root.tag[: root.tag.index("}") + 1]
        ET.SubElement(dependency, namespace + "type").text = "aar"
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / "non-aar-type.pom"
            path.write_bytes(ET.tostring(root, encoding="utf-8", xml_declaration=True))
            with self.assertRaisesRegex(contract.ContractError, "POM non-AAR dependency declares aar"):
                contract.parse_pom(path, seed, SOURCE_SHA)

    def test_assemble_missing_aar_owner_edge_is_red(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            staging = root / "staging"
            staging.mkdir()
            write_full_staging(staging)
            write_toolchains(root / "toolchains.json")
            seed = contract.SEED_BY_GAV[(
                contract.GROUP, "compose-android", contract.NORMAL_VERSION,
            )]
            pom_path = (
                staging / contract.GROUP_PATH / seed.artifact / seed.version
                / f"{seed.artifact}-{seed.version}.pom"
            )
            pom_root = ET.fromstring(pom_path.read_bytes())
            dependency = pom_dependency(pom_root, "core-annotations-android")
            dependencies = contract.direct_xml_children(pom_root, "dependencies")[0]
            dependencies.remove(dependency)
            pom_path.write_bytes(ET.tostring(pom_root, encoding="utf-8", xml_declaration=True))
            write_checksums_for(pom_path)
            with mock.patch.object(contract, "source_identity", return_value=frozen_source()):
                with self.assertRaisesRegex(
                    contract.ContractError, "generated POM graph lacks expected owner edges",
                ):
                    contract.assemble(
                        root, fixture_staging_specs(staging), "refs/tags/x", False,
                        None, root / "toolchains.json",
                    )

    def test_module_metadata_owner_graph_uses_canonical_redirects(self) -> None:
        members = [seed for seed in contract.SEEDS if seed.role == "metadata-member"]
        self.assertEqual(26, len(members))
        self.assertEqual(
            {"core": 8, "core-annotations": 10, "compose": 8},
            {
                owner: sum(seed.owner_artifact == owner for seed in members)
                for owner in {seed.owner_artifact for seed in members}
            },
        )
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            for index, seed in enumerate(contract.SEEDS):
                path = root / f"{index}.module"
                path.write_bytes(valid_module(seed))
                refs = contract.parse_module(path, seed)
                owner = contract.owner_root(seed)
                identity = seed if owner is None else owner
                self.assertIn(
                    (contract.GROUP, identity.artifact, identity.version), refs,
                )

    def test_module_metadata_member_wrong_owner_is_red(self) -> None:
        seed = next(
            item for item in contract.SEEDS
            if item.role == "metadata-member" and item.owner_artifact == "core"
        )
        value = json.loads(valid_module(seed))
        value["component"]["module"] = "compose"
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / "wrong-owner.module"
            path.write_bytes(contract.json_bytes(value))
            with self.assertRaisesRegex(contract.ContractError, "component identity mismatch"):
                contract.parse_module(path, seed)

    def test_module_metadata_member_wrong_version_is_red(self) -> None:
        seed = next(item for item in contract.SEEDS if item.role == "metadata-member")
        value = json.loads(valid_module(seed))
        value["component"]["version"] = seed.version + "-wrong"
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / "wrong-version.module"
            path.write_bytes(contract.json_bytes(value))
            with self.assertRaisesRegex(contract.ContractError, "component identity mismatch"):
                contract.parse_module(path, seed)

    def test_module_metadata_member_noncanonical_url_is_red(self) -> None:
        seed = next(item for item in contract.SEEDS if item.role == "metadata-member")
        owner = contract.owner_root(seed)
        self.assertIsNotNone(owner)
        value = json.loads(valid_module(seed))
        value["component"]["url"] = (
            f"../../{owner.artifact}/{owner.version}/./"
            f"{owner.artifact}-{owner.version}.module"
        )
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / "noncanonical-url.module"
            path.write_bytes(contract.json_bytes(value))
            with self.assertRaisesRegex(contract.ContractError, "URL is not canonical"):
                contract.parse_module(path, seed)

    def test_module_metadata_member_out_of_bound_url_is_red(self) -> None:
        seed = next(item for item in contract.SEEDS if item.role == "metadata-member")
        value = json.loads(valid_module(seed))
        value["component"]["url"] = "../../../../outside.module"
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / "out-of-bound-url.module"
            path.write_bytes(contract.json_bytes(value))
            with self.assertRaisesRegex(contract.ContractError, "escapes the owner group"):
                contract.parse_module(path, seed)

    def test_module_metadata_nonmember_redirect_is_red(self) -> None:
        seed = next(item for item in contract.SEEDS if item.role == "root")
        value = json.loads(valid_module(seed))
        value["component"]["url"] = contract.module_primary_path(seed)
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / "nonmember-redirect.module"
            path.write_bytes(contract.json_bytes(value))
            with self.assertRaisesRegex(contract.ContractError, "non-member module component redirects"):
                contract.parse_module(path, seed)

    def test_module_dependency_excludes_are_path_aware(self) -> None:
        total = 0
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            for artifact, expected_count in FIXTURE_EXCLUDE_COUNTS.items():
                seed = next(item for item in contract.SEEDS if item.artifact == artifact)
                value = json.loads(valid_module(seed))
                dependencies = value["variants"][0]["dependencies"]
                excludes = [exclude for item in dependencies for exclude in item["excludes"]]
                self.assertEqual(expected_count, len(excludes))
                self.assertTrue(all(set(item) == {"group", "module"} for item in excludes))
                path = root / f"{artifact}.module"
                path.write_bytes(contract.json_bytes(value))
                refs = contract.parse_module(path, seed)
                self.assertNotIn(
                    ("org.jetbrains.kotlin", "kotlin-stdlib-common", "1.2.3"), refs,
                )
                total += len(excludes)
        self.assertEqual(33, total)

    def test_module_dependency_exclude_shape_is_red(self) -> None:
        seed = next(item for item in contract.SEEDS if item.artifact == "compose-android")
        for invalid in (
            {"group": "org.example", "module": "excluded", "version": "1.0"},
            {"group": 7, "module": "excluded"},
            {"group": "org.example"},
            "excluded",
        ):
            with self.subTest(invalid=invalid), tempfile.TemporaryDirectory() as raw:
                value = json.loads(valid_module(seed))
                value["variants"][0]["dependencies"][0]["excludes"] = [invalid]
                path = Path(raw) / "invalid-exclude.module"
                path.write_bytes(contract.json_bytes(value))
                with self.assertRaisesRegex(contract.ContractError, "exclude must contain exact"):
                    contract.parse_module(path, seed)

    def test_module_dependency_excludes_outside_variant_path_are_red(self) -> None:
        seed = contract.SEEDS[0]
        value = json.loads(valid_module(seed))
        value["custom"] = {"dependencies": [{
            "group": "org.example",
            "module": "dependency",
            "version": {"requires": "1.2.3"},
            "excludes": [{"group": "org.example", "module": "excluded"}],
        }]}
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / "exclude-outside-variant.module"
            path.write_bytes(contract.json_bytes(value))
            with self.assertRaisesRegex(contract.ContractError, "lacks explicit version"):
                contract.parse_module(path, seed)

    def test_module_dependency_missing_version_is_red(self) -> None:
        seed = next(item for item in contract.SEEDS if item.artifact == "compose-android")
        value = json.loads(valid_module(seed))
        value["variants"][0]["dependencies"][0].pop("version")
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / "dependency-missing-version.module"
            path.write_bytes(contract.json_bytes(value))
            with self.assertRaisesRegex(contract.ContractError, "lacks explicit version"):
                contract.parse_module(path, seed)

    def test_module_dependency_constraint_missing_version_is_red(self) -> None:
        seed = contract.SEEDS[0]
        value = json.loads(valid_module(seed))
        value["variants"] = [{
            "name": "runtime",
            "dependencyConstraints": [{"group": "org.example", "module": "constraint"}],
        }]
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / "constraint-missing-version.module"
            path.write_bytes(contract.json_bytes(value))
            with self.assertRaisesRegex(contract.ContractError, "lacks explicit version"):
                contract.parse_module(path, seed)

    def test_module_component_missing_version_is_red(self) -> None:
        seed = contract.SEEDS[0]
        value = json.loads(valid_module(seed))
        value["component"].pop("version")
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / "component-missing-version.module"
            path.write_bytes(contract.json_bytes(value))
            with self.assertRaisesRegex(contract.ContractError, "component identity mismatch"):
                contract.parse_module(path, seed)

    def test_module_available_at_missing_version_is_red(self) -> None:
        seed = contract.SEEDS[0]
        value = json.loads(valid_module(seed))
        value["variants"] = [{
            "name": "runtime",
            "available-at": {"group": contract.GROUP, "module": seed.artifact},
        }]
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / "available-at-missing-version.module"
            path.write_bytes(contract.json_bytes(value))
            with self.assertRaisesRegex(contract.ContractError, "lacks explicit version"):
                contract.parse_module(path, seed)

    def test_assemble_full_closure_bundle_and_missing_or_orphan_are_red(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            manifest, staging, bundle = assemble_fixture(root)
            self.assertEqual(37, manifest["publicationSeedCount"])
            self.assertEqual(set(bundle), {item["path"] for item in manifest["publications"]})
            self.assertTrue(manifest["publishable"])
            self.assertEqual(
                manifest["publicationFileCount"],
                manifest["publicationPrimaryFileCount"]
                + manifest["publicationChecksumFileCount"]
                + manifest["publicationSignatureFileCount"],
            )
            self.assertEqual(
                4 * (
                    manifest["publicationPrimaryFileCount"]
                    + manifest["publicationSignatureFileCount"]
                ),
                manifest["publicationChecksumFileCount"],
            )
            paths = set(bundle)
            seed = contract.SEEDS[0]
            primary = f"{contract.GROUP_PATH}/{seed.artifact}/{seed.version}/{seed.artifact}-{seed.version}.pom"
            self.assertIn(primary + ".sha256", paths)
            self.assertIn(primary + ".md5", paths)
            self.assertIn(primary + ".sha1", paths)
            self.assertIn(primary + ".sha512", paths)
            self.assertIn(primary + ".asc", paths)
            self.assertIn(primary + ".asc.sha256", paths)
            self.assertIn(primary + ".asc.md5", paths)
            self.assertIn(primary + ".asc.sha1", paths)
            self.assertIn(primary + ".asc.sha512", paths)
            self.assertNotIn(
                f"{contract.GROUP_PATH}/{seed.artifact}/maven-metadata.xml",
                paths,
            )

            # Real producer shards generate mutable artifact-level metadata
            # independently.  Normal and OHOS shards therefore list different
            # version views and checksum bytes.  They must be excluded before
            # immutable cross-producer collision checks, not merely omitted
            # from the final manifest after the merge.
            metadata_drift = root / "metadata-drift"
            metadata = (
                metadata_drift / contract.GROUP_PATH / seed.artifact
                / "maven-metadata.xml"
            )
            metadata.parent.mkdir(parents=True)
            metadata.write_bytes(b"<metadata>different producer view</metadata>\n")
            write_checksums_for(metadata)
            staging_specs = fixture_staging_specs(staging)
            staging_specs[contract.EXPECTED_PRODUCERS.index("ohos-gradle")] = (
                f"ohos-gradle={metadata_drift}"
            )
            with mock.patch.object(
                contract, "source_identity", return_value=frozen_source(),
            ):
                drift_manifest, drift_bundle = contract.assemble(
                    root,
                    staging_specs,
                    "refs/tags/kuikly-v2.24.0-raft.1",
                    False,
                    None,
                    root / "toolchains.json",
                )
            self.assertEqual(manifest["setSha256"], drift_manifest["setSha256"])
            self.assertEqual(bundle, drift_bundle)

            manifest_without_checksum = copy.deepcopy(manifest)
            manifest_without_checksum["publications"] = [
                item for item in manifest_without_checksum["publications"]
                if item["path"] != primary + ".md5"
            ]
            manifest_without_checksum["publicationFileCount"] -= 1
            manifest_without_checksum["publicationChecksumFileCount"] -= 1
            manifest_without_checksum["setSha256"] = contract.canonical_set_digest(
                manifest_without_checksum["publications"],
            )
            with self.assertRaisesRegex(
                contract.ContractError, "versioned object lacks required checksum companions",
            ):
                contract.validate_manifest(manifest_without_checksum, require_publishable=True)

            missing = next(
                item for item in contract.SEEDS
                if item.artifact == "core-annotations-android"
            )
            missing_dir = staging / contract.GROUP_PATH / missing.artifact / missing.version
            for child in missing_dir.iterdir():
                child.unlink()
            missing_dir.rmdir()
            toolchains = root / "toolchains.json"
            with mock.patch.object(contract, "source_identity", return_value=frozen_source()):
                with self.assertRaisesRegex(contract.ContractError, "whole publication seeds missing"):
                    contract.assemble(root, fixture_staging_specs(staging), "refs/tags/x", False, None, toolchains)

        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            _, staging, _ = assemble_fixture(root)
            orphan = staging / contract.GROUP_PATH / contract.SEEDS[0].artifact / contract.SEEDS[0].version / "orphan.bin.sha256"
            orphan.write_text("deadbeef\n", encoding="utf-8")
            with mock.patch.object(contract, "source_identity", return_value=frozen_source()):
                with self.assertRaisesRegex(contract.ContractError, "orphan/unclassified auxiliary"):
                    contract.assemble(root, fixture_staging_specs(staging), "refs/tags/x", False, None, root / "toolchains.json")

        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            _, staging, _ = assemble_fixture(root)
            seed = contract.SEEDS[0]
            checksum = (
                staging / contract.GROUP_PATH / seed.artifact / seed.version
                / f"{seed.artifact}-{seed.version}.pom.sha256"
            )
            checksum.write_text("0" * 64 + "\n", encoding="ascii")
            with mock.patch.object(contract, "source_identity", return_value=frozen_source()):
                with self.assertRaisesRegex(contract.ContractError, "checksum companion differs"):
                    contract.assemble(root, fixture_staging_specs(staging), "refs/tags/x", False, None, root / "toolchains.json")

        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            _, staging, _ = assemble_fixture(root)
            seed = contract.SEEDS[0]
            missing = (
                staging / contract.GROUP_PATH / seed.artifact / seed.version
                / f"{seed.artifact}-{seed.version}.pom.md5"
            )
            missing.unlink()
            with mock.patch.object(contract, "source_identity", return_value=frozen_source()):
                with self.assertRaisesRegex(contract.ContractError, "lacks required checksum companions"):
                    contract.assemble(
                        root, fixture_staging_specs(staging), "refs/tags/x", False,
                        None, root / "toolchains.json",
                    )

        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            staging = root / "staging"
            staging.mkdir()
            write_full_staging(staging)
            write_toolchains(root / "toolchains.json")
            duplicate = fixture_staging_specs(staging)
            duplicate[-1] = duplicate[0]
            with mock.patch.object(contract, "source_identity", return_value=frozen_source()):
                with self.assertRaisesRegex(contract.ContractError, "duplicate staging producer label"):
                    contract.assemble(
                        root, duplicate, "refs/tags/x", False,
                        None, root / "toolchains.json",
                    )

        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            staging = root / "staging"
            staging.mkdir()
            write_full_staging(staging)
            write_toolchains(root / "toolchains.json", tag_ref="refs/tags/different")
            with mock.patch.object(contract, "source_identity", return_value=frozen_source()):
                with self.assertRaisesRegex(contract.ContractError, "toolchain receipt tagRef mismatch"):
                    contract.assemble(
                        root, fixture_staging_specs(staging), "refs/tags/x", False,
                        None, root / "toolchains.json",
                    )

    def test_assemble_command_copies_exact_primary_bundle(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            staging = root / "staging"
            staging.mkdir()
            write_full_staging(staging)
            write_toolchains(root / "toolchains.json")
            args = argparse.Namespace(
                source_root=str(root),
                staging=fixture_staging_specs(staging),
                tag_ref="refs/tags/kuikly-v2.24.0-raft.1",
                allow_unreleased=False,
                predecessor_receipts=None,
                toolchains=str(root / "toolchains.json"),
                require_publishable=True,
                output=str(root / "manifest.json"),
                bundle_output=str(root / "bundle"),
            )
            with mock.patch.object(contract, "source_identity", return_value=frozen_source()):
                contract.command_assemble(args)
            manifest = json.loads((root / "manifest.json").read_text())
            copied = {path.relative_to(root / "bundle").as_posix() for path in (root / "bundle").rglob("*") if path.is_file()}
            self.assertEqual(copied, {item["path"] for item in manifest["publications"]})

    def test_predecessor_receipt_binds_coordinate_public_readback_and_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            staging = root / "staging"
            staging.mkdir()
            write_full_staging(staging)
            write_toolchains(root / "toolchains.json")
            seed = contract.SEEDS[0]
            pom = (
                staging / contract.GROUP_PATH / seed.artifact / seed.version
                / f"{seed.artifact}-{seed.version}.pom"
            )
            pom.write_bytes(valid_pom(seed, dependency_version="1.0-KBA-001"))
            write_checksums_for(pom)
            coordinate = "org.example:dependency:1.0-KBA-001"
            receipt_path = root / "predecessors.json"
            receipt = {
                "schema": "kuikly-predecessors/v1",
                "coordinates": {
                    coordinate: {
                        "coordinate": coordinate,
                        "status": "verified",
                        "publicReadbackState": "not-verified",
                        "manifestSha256": "d" * 64,
                    }
                },
            }
            receipt_path.write_bytes(contract.json_bytes(receipt))
            with mock.patch.object(contract, "source_identity", return_value=frozen_source()):
                with self.assertRaisesRegex(contract.ContractError, "public readback is not verified"):
                    contract.assemble(
                        root, fixture_staging_specs(staging), "refs/tags/x", False,
                        receipt_path, root / "toolchains.json",
                    )
            receipt["coordinates"][coordinate]["publicReadbackState"] = "verified"
            receipt_path.write_bytes(contract.json_bytes(receipt))
            with mock.patch.object(contract, "source_identity", return_value=frozen_source()):
                manifest, _ = contract.assemble(
                    root, fixture_staging_specs(staging), "refs/tags/x", False,
                    receipt_path, root / "toolchains.json",
                )
            self.assertTrue(manifest["publishable"])

    def test_native_package_validators(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            xcframework = root / "OpenKuiklyIOSRender.xcframework"
            xcframework.mkdir()
            (xcframework / "Info.plist").write_bytes(plistlib.dumps({
                "AvailableLibraries": [
                    {
                        "LibraryIdentifier": "ios-arm64",
                        "LibraryPath": "OpenKuiklyIOSRender.framework",
                        "SupportedArchitectures": ["arm64"],
                        "SupportedPlatform": "ios",
                    },
                    {
                        "LibraryIdentifier": "ios-sim",
                        "LibraryPath": "OpenKuiklyIOSRender.framework",
                        "SupportedArchitectures": ["arm64"],
                        "SupportedPlatform": "ios",
                        "SupportedPlatformVariant": "simulator",
                    },
                ],
            }))
            for identifier, body in (("ios-arm64", b"device"), ("ios-sim", b"simulator")):
                framework = xcframework / identifier / "OpenKuiklyIOSRender.framework"
                framework.mkdir(parents=True)
                (framework / "OpenKuiklyIOSRender").write_bytes(body)
            ios_args = argparse.Namespace(
                source_root=str(root), xcframework=str(xcframework), tag_ref="refs/tags/x",
                allow_unreleased=False, output=str(root / "ios-output"),
            )
            with mock.patch.object(contract, "source_identity", return_value=frozen_source()):
                contract.package_ios(ios_args)
            self.assertTrue(any((root / "ios-output").rglob("*.xcframework.zip")))

            har = root / "render.har"
            write_har(har, valid_har_entries())
            ohos_args = argparse.Namespace(
                source_root=str(root), har=str(har), tag_ref="refs/tags/x",
                allow_unreleased=False, output=str(root / "ohos-output"),
            )
            with mock.patch.object(contract, "source_identity", return_value=frozen_source()):
                contract.package_ohos(ohos_args)
            self.assertTrue(any((root / "ohos-output").rglob("*.har")))

            bad_har = root / "bad.har"
            write_har(bad_har, valid_har_entries("wrong"))
            bad_args = copy.copy(ohos_args)
            bad_args.har = str(bad_har)
            bad_args.output = str(root / "bad-output")
            with mock.patch.object(contract, "source_identity", return_value=frozen_source()):
                with self.assertRaisesRegex(contract.ContractError, "internal package version"):
                    contract.package_ohos(bad_args)

    def test_ohos_har_rejects_zip_and_wrong_root(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            zip_har = root / "zip.har"
            with zipfile.ZipFile(zip_har, "w") as archive:
                archive.writestr("package/oh-package.json5", json.dumps({"version": contract.OHOS_VERSION}))
                archive.writestr("package/libs/arm64-v8a/libkuikly.so", b"so")
            args = argparse.Namespace(
                source_root=str(root), har=str(zip_har), tag_ref="refs/tags/x",
                allow_unreleased=False, output=str(root / "zip-output"),
            )
            with mock.patch.object(contract, "source_identity", return_value=frozen_source()):
                with self.assertRaisesRegex(contract.ContractError, "gzip-compressed tar archive"):
                    contract.package_ohos(args)

            wrong_root = root / "wrong-root.har"
            write_har(wrong_root, [
                (
                    "package/nested/oh-package.json5",
                    json.dumps({"version": contract.OHOS_VERSION}).encode(),
                    tarfile.REGTYPE,
                    "",
                ),
                ("package/libs/arm64-v8a/libkuikly.so", b"so", tarfile.REGTYPE, ""),
            ])
            args.har = str(wrong_root)
            args.output = str(root / "wrong-root-output")
            with mock.patch.object(contract, "source_identity", return_value=frozen_source()):
                with self.assertRaisesRegex(contract.ContractError, "root package/oh-package.json5"):
                    contract.package_ohos(args)

    def test_ohos_har_rejects_duplicate_and_unsafe_entries(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            duplicate = root / "duplicate.har"
            write_har(duplicate, valid_har_entries() + [
                ("package/README.md", b"first", tarfile.REGTYPE, ""),
                ("package/README.md", b"second", tarfile.REGTYPE, ""),
            ])
            args = argparse.Namespace(
                source_root=str(root), har=str(duplicate), tag_ref="refs/tags/x",
                allow_unreleased=False, output=str(root / "duplicate-output"),
            )
            with mock.patch.object(contract, "source_identity", return_value=frozen_source()):
                with self.assertRaisesRegex(contract.ContractError, "duplicate TAR names"):
                    contract.package_ohos(args)

            unsafe = root / "unsafe.har"
            write_har(unsafe, valid_har_entries() + [
                ("package/../escape", b"escape", tarfile.REGTYPE, ""),
            ])
            args.har = str(unsafe)
            args.output = str(root / "unsafe-output")
            with mock.patch.object(contract, "source_identity", return_value=frozen_source()):
                with self.assertRaisesRegex(contract.ContractError, "unsafe TAR member"):
                    contract.package_ohos(args)

    def test_ohos_har_rejects_nonregular_entries(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            linked = root / "linked.har"
            write_har(linked, valid_har_entries() + [
                ("package/link", b"", tarfile.SYMTYPE, "../outside"),
            ])
            args = argparse.Namespace(
                source_root=str(root), har=str(linked), tag_ref="refs/tags/x",
                allow_unreleased=False, output=str(root / "linked-output"),
            )
            with mock.patch.object(contract, "source_identity", return_value=frozen_source()):
                with self.assertRaisesRegex(contract.ContractError, "unsafe TAR member"):
                    contract.package_ohos(args)


class PublisherTests(unittest.TestCase):
    def test_product_publication_is_bounded_and_parallel(self) -> None:
        class TracksParallelPuts(MavenStateHttp):
            def __init__(self) -> None:
                super().__init__()
                self.active = 0
                self.peak = 0
                self.lock = threading.Lock()

            def request(self, origin, path, method, **kwargs):
                if origin != contract.PUBLIC_MAVEN_ORIGIN or method != "PUT":
                    return super().request(origin, path, method, **kwargs)
                with self.lock:
                    self.active += 1
                    self.peak = max(self.peak, self.active)
                try:
                    time.sleep(0.01)
                    return super().request(origin, path, method, **kwargs)
                finally:
                    with self.lock:
                        self.active -= 1

        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            manifest, bundle_root, _ = assemble_fixture(root)
            http = TracksParallelPuts()
            plan = publisher.classify(http, manifest, bundle_root)
            publisher.release(
                http, manifest, plan, bundle_root, MavenStateHttp.TOKEN,
                root / "execution.json",
            )

        self.assertGreater(http.peak, 1)
        self.assertLessEqual(http.peak, publisher.MAX_PARALLEL_REQUESTS)

    def test_transient_put_retries_only_after_exact_readback(self) -> None:
        entry = {"path": "object.bin", "sha256": contract.sha256_bytes(b"bytes"), "size": 5}

        class TransientThenSuccess(MavenStateHttp):
            def __init__(self) -> None:
                super().__init__()
                self.puts = 0

            def request(self, origin, path, method, **kwargs):
                if origin == contract.PUBLIC_MAVEN_ORIGIN and method == "PUT":
                    self.puts += 1
                    if self.puts == 1:
                        return 503, b""
                return super().request(origin, path, method, **kwargs)

        http = TransientThenSuccess()
        with mock.patch.object(publisher.time, "sleep") as sleep:
            result = publisher.put_and_readback(
                http, MavenStateHttp.TOKEN, entry, b"bytes",
                content_type="application/octet-stream",
            )
        self.assertEqual("uploaded", result)
        self.assertEqual(2, http.puts)
        sleep.assert_called_once_with(0.25)

        class TransientButCommitted(MavenStateHttp):
            def request(self, origin, path, method, **kwargs):
                if origin == contract.PUBLIC_MAVEN_ORIGIN and method == "PUT":
                    self.public[entry["path"]] = kwargs["body"]
                    return 503, b""
                return super().request(origin, path, method, **kwargs)

        committed = TransientButCommitted()
        with mock.patch.object(publisher.time, "sleep"):
            result = publisher.put_and_readback(
                committed, MavenStateHttp.TOKEN, entry, b"bytes",
                content_type="application/octet-stream",
            )
        self.assertEqual("reused-after-transient", result)

    def test_checksum_listing_is_optional_but_exact_get_is_required(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            manifest, bundle_root, bundle = assemble_fixture(Path(raw))
            checksum = next(
                item["path"]
                for item in manifest["publications"]
                if contract.checksum_descriptor(item["path"]) is not None
            )
            primary = contract.checksum_descriptor(checksum)[0]

            exact_unlisted_checksum = publisher.classify(
                PublicStateHttp({checksum: bundle[checksum]}, listed=set()),
                manifest,
                bundle_root,
            )
            self.assertEqual("PARTIAL_EXACT", exact_unlisted_checksum["state"])
            self.assertEqual([], exact_unlisted_checksum["listingDisagreement"])

            unlisted_primary = publisher.classify(
                PublicStateHttp({primary: bundle[primary]}, listed=set()),
                manifest,
                bundle_root,
            )
            self.assertEqual("CONFLICT", unlisted_primary["state"])
            self.assertEqual([primary], unlisted_primary["listingDisagreement"])

            listed_unreadable_checksum = publisher.classify(
                PublicStateHttp({}, listed={checksum}), manifest, bundle_root,
            )
            self.assertEqual("CONFLICT", listed_unreadable_checksum["state"])
            self.assertEqual([checksum], listed_unreadable_checksum["listingDisagreement"])

            checksum_mismatch = publisher.classify(
                PublicStateHttp({checksum: b"wrong"}, listed=set()),
                manifest,
                bundle_root,
            )
            self.assertEqual("CONFLICT", checksum_mismatch["state"])
            self.assertEqual([checksum], checksum_mismatch["different"])

            unexpected = primary.rsplit("/", 1)[0] + "/unexpected.bin"
            unexpected_listed = publisher.classify(
                PublicStateHttp({}, listed={unexpected}), manifest, bundle_root,
            )
            self.assertEqual("CONFLICT", unexpected_listed["state"])
            self.assertEqual([unexpected], unexpected_listed["unexpected"])

    def test_planner_states_and_trailing_slash_prefixes(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            manifest, bundle_root, bundle = assemble_fixture(Path(raw))
            absent = publisher.classify(PublicStateHttp(), manifest, bundle_root)
            self.assertEqual("ALL_ABSENT", absent["state"])
            self.assertTrue(all(prefix.endswith("/") for prefix in absent["ownedPrefixes"]))
            metadata = f"{contract.GROUP_PATH}/{contract.SEEDS[0].artifact}/maven-metadata.xml"
            metadata_only = publisher.classify(
                PublicStateHttp({}, listed={metadata}), manifest, bundle_root,
            )
            self.assertEqual("ALL_ABSENT", metadata_only["state"])

            first_path = manifest["publications"][0]["path"]
            partial = publisher.classify(
                PublicStateHttp({first_path: bundle[first_path]}), manifest, bundle_root,
            )
            self.assertEqual("PARTIAL_EXACT", partial["state"])

            unexpected = first_path.rsplit("/", 1)[0] + "/unexpected.bin"
            conflict = publisher.classify(
                PublicStateHttp({}, listed={unexpected}), manifest, bundle_root,
            )
            self.assertEqual("CONFLICT", conflict["state"])

            complete_public = dict(bundle)
            _, completion_body = publisher.completion_manifest(manifest)
            complete_public[contract.MANIFEST_PATH] = completion_body
            complete = publisher.classify(PublicStateHttp(complete_public), manifest, bundle_root)
            self.assertEqual("ALL_COMPLETE_EXACT", complete["state"])

            completion_with_extra = json.loads(completion_body)
            completion_with_extra["unreviewed"] = True
            complete_public[contract.MANIFEST_PATH] = contract.json_bytes(completion_with_extra)
            exact_byte_conflict = publisher.classify(
                PublicStateHttp(complete_public), manifest, bundle_root,
            )
            self.assertEqual("CONFLICT", exact_byte_conflict["state"])

    def test_single_token_publish_manifest_last_and_idempotent_retry(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            manifest, bundle_root, _ = assemble_fixture(root)
            http = MavenStateHttp()
            plan = publisher.classify(http, manifest, bundle_root)
            receipt_path = root / "execution.json"
            publisher.release(
                http, manifest, plan, bundle_root, MavenStateHttp.TOKEN, receipt_path,
            )
            receipt = json.loads(receipt_path.read_text())
            self.assertEqual("complete", receipt["state"])
            puts = [without_prefix(event, "put:") for event in http.events if event.startswith("put:")]
            self.assertEqual(contract.MANIFEST_PATH, puts[-1])
            self.assertEqual(len(manifest["publications"]) + 1, len(puts))
            self.assertEqual(len(manifest["publications"]), receipt["publicReadbackCount"])

            http.events.clear()
            terminal_plan = publisher.classify(http, manifest, bundle_root)
            publisher.release(
                http, manifest, terminal_plan, bundle_root, MavenStateHttp.TOKEN, receipt_path,
            )
            self.assertFalse(any(event.startswith("put:") for event in http.events))
            self.assertEqual(
                len(manifest["publications"]) + 1,
                json.loads(receipt_path.read_text())["reusedCount"],
            )

    def test_partial_exact_retry_uploads_only_missing_files(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            manifest, bundle_root, bundle = assemble_fixture(root)
            first = manifest["publications"][0]["path"]
            http = MavenStateHttp({first: bundle[first]})
            plan = publisher.classify(http, manifest, bundle_root)
            self.assertEqual("PARTIAL_EXACT", plan["state"])
            publisher.release(
                http, manifest, plan, bundle_root, MavenStateHttp.TOKEN,
                root / "execution.json",
            )
            puts = [without_prefix(event, "put:") for event in http.events if event.startswith("put:")]
            self.assertNotIn(first, puts)
            self.assertEqual(contract.MANIFEST_PATH, puts[-1])
            self.assertEqual(len(manifest["publications"]), len(puts))

    def test_conflicting_remote_bytes_stop_without_put(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            manifest, bundle_root, _ = assemble_fixture(root)
            first = manifest["publications"][0]["path"]
            http = MavenStateHttp({first: b"wrong"})
            plan = publisher.classify(http, manifest, bundle_root)
            self.assertEqual("CONFLICT", plan["state"])
            with self.assertRaisesRegex(publisher.PublishError, "publication plan is conflicting"):
                publisher.release(
                    http, manifest, plan, bundle_root, MavenStateHttp.TOKEN,
                    root / "execution.json",
                )
            self.assertFalse(any(event.startswith("put:") for event in http.events))

    def test_exact_remote_rejects_mismatched_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            manifest, _, _ = assemble_fixture(Path(raw))
            entry = manifest["publications"][0]
            http = MavenStateHttp({entry["path"]: b"wrong"})
            with self.assertRaisesRegex(publisher.PublishError, "existing remote bytes conflict"):
                publisher.exact_remote(http, entry)

    def test_publish_requires_one_nonempty_token(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            manifest, bundle_root, _ = assemble_fixture(root)
            plan = publisher.classify(MavenStateHttp(), manifest, bundle_root)
            for token in ("", "bad\nvalue"):
                with self.subTest(token=repr(token)):
                    with self.assertRaisesRegex(publisher.PublishError, "missing or malformed"):
                        publisher.release(
                            MavenStateHttp(), manifest, plan, bundle_root, token,
                            root / "execution.json",
                        )

    def test_interrupted_publish_is_retryable_with_same_version(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            manifest, bundle_root, _ = assemble_fixture(root)
            plan = publisher.classify(MavenStateHttp(), manifest, bundle_root)

            class FailsSecondPut(MavenStateHttp):
                put_count = 0

                def request(self, origin, path, method, **kwargs):
                    if origin == contract.PUBLIC_MAVEN_ORIGIN and method == "PUT":
                        self.put_count += 1
                        if 2 <= self.put_count < 2 + publisher.PUT_MAX_ATTEMPTS:
                            return 500, b"injected"
                    return super().request(origin, path, method, **kwargs)

            execution_path = root / "interrupted.json"
            http = FailsSecondPut()
            with mock.patch.object(publisher.time, "sleep"):
                with self.assertRaisesRegex(publisher.PublishError, "PUT failed with HTTP 500"):
                    publisher.release(
                        http, manifest, plan, bundle_root, MavenStateHttp.TOKEN, execution_path,
                    )
            execution = json.loads(execution_path.read_text())
            self.assertEqual("incomplete-retryable", execution["state"])
            self.assertNotIn(contract.MANIFEST_PATH, http.public)

            retry_plan = publisher.classify(http, manifest, bundle_root)
            self.assertEqual("PARTIAL_EXACT", retry_plan["state"])
            http.put_count = 10
            publisher.release(
                http, manifest, retry_plan, bundle_root, MavenStateHttp.TOKEN, execution_path,
            )
            self.assertEqual("complete", json.loads(execution_path.read_text())["state"])

    def test_product_readback_failure_prevents_completion_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            manifest, bundle_root, _ = assemble_fixture(root)
            plan = publisher.classify(MavenStateHttp(), manifest, bundle_root)

            class CorruptFinalVerification(MavenStateHttp):
                successful_gets: dict[str, int]

                def __init__(self):
                    super().__init__()
                    self.successful_gets = {}

                def request(self, origin, path, method, **kwargs):
                    status, body = super().request(origin, path, method, **kwargs)
                    if (
                        origin == contract.PUBLIC_MAVEN_ORIGIN
                        and method == "GET"
                        and status == 200
                    ):
                        key = urllib.parse.unquote(without_prefix(path, "/"))
                        self.successful_gets[key] = self.successful_gets.get(key, 0) + 1
                        if (
                            key == manifest["publications"][0]["path"]
                            and self.successful_gets[key] == 2
                        ):
                            return status, b"corrupt-" + body
                    return status, body

            execution_path = root / "corrupt-readback.json"
            http = CorruptFinalVerification()
            with self.assertRaisesRegex(publisher.PublishError, "public readback differs"):
                publisher.release(
                    http, manifest, plan, bundle_root, MavenStateHttp.TOKEN, execution_path,
                )
            self.assertNotIn(contract.MANIFEST_PATH, http.public)
            self.assertEqual("incomplete-retryable", json.loads(execution_path.read_text())["state"])

    def test_completion_readback_failure_keeps_receipt_incomplete(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            manifest, bundle_root, _ = assemble_fixture(root)
            plan = publisher.classify(MavenStateHttp(), manifest, bundle_root)

            class CorruptCompletionReadback(MavenStateHttp):
                successful_completion_gets = 0

                def request(self, origin, path, method, **kwargs):
                    status, body = super().request(origin, path, method, **kwargs)
                    key = urllib.parse.unquote(without_prefix(path, "/"))
                    if (
                        origin == contract.PUBLIC_MAVEN_ORIGIN
                        and method == "GET"
                        and key == contract.MANIFEST_PATH
                        and status == 200
                    ):
                        self.successful_completion_gets += 1
                        if self.successful_completion_gets == 2:
                            return status, b"corrupt-" + body
                    return status, body

            execution_path = root / "completion-readback.json"
            http = CorruptCompletionReadback()
            with self.assertRaisesRegex(publisher.PublishError, "completion manifest public readback"):
                publisher.release(
                    http, manifest, plan, bundle_root, MavenStateHttp.TOKEN, execution_path,
                )
            self.assertEqual("incomplete-retryable", json.loads(execution_path.read_text())["state"])

    def test_put_conflict_race_accepts_exact_remote_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            manifest, bundle_root, _ = assemble_fixture(root)
            plan = publisher.classify(MavenStateHttp(), manifest, bundle_root)

            class ExactRace(MavenStateHttp):
                raced = False

                def request(self, origin, path, method, **kwargs):
                    if origin == contract.PUBLIC_MAVEN_ORIGIN and method == "PUT" and not self.raced:
                        key = urllib.parse.unquote(without_prefix(path, "/"))
                        self.public[key] = kwargs.get("body") or b""
                        self.events.append("put:" + key)
                        self.raced = True
                        return 409, b""
                    return super().request(origin, path, method, **kwargs)

            execution_path = root / "race.json"
            http = ExactRace()
            publisher.release(
                http, manifest, plan, bundle_root, MavenStateHttp.TOKEN, execution_path,
            )
            self.assertTrue(http.raced)
            self.assertEqual("complete", json.loads(execution_path.read_text())["state"])

    def test_source_contains_no_atomic_claim_or_lease_protocol(self) -> None:
        source = Path(publisher.__file__).read_text(encoding="utf-8")
        self.assertNotIn("/api/releases/claims", source)
        self.assertNotIn("leaseExpiresAt", source)
        self.assertNotIn("TOKEN_PLANES", source)
        self.assertEqual("RAFT_ARTIFACTS_PUBLISH_TOKEN", publisher.PUBLISH_TOKEN_ENV)


if __name__ == "__main__":
    unittest.main(verbosity=2)
