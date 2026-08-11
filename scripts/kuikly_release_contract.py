#!/usr/bin/env python3
"""Build and validate Kuikly's immutable four-platform release manifest.

This module is intentionally a byte-contract tool, not a publisher.  Gradle,
Xcode and Hvigor write to isolated staging directories; this assembler walks
those directories from scratch, verifies the exact 37-publication owner
closure, POM/module provenance and native-host package shapes, then emits the
only input accepted by the create-only publisher.

The public completion marker is release-owner authorized as:
  com.tencent.kuikly-open:kuikly-release-manifest:2.24.0-raft.1
It is transport metadata, not a 38th product publication, and is therefore
not included in the manifest's recursive publication byte set.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import posixpath
import plistlib
import re
import stat
import subprocess
import sys
import tarfile
import xml.etree.ElementTree as ET
import zipfile
from dataclasses import asdict, dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Iterable, Iterator, Sequence


# The release source is the botiverse fork whose protected staging3 branch and
# Actions workflow produce these bytes.  Project-homepage metadata may still
# point at the public bytemain upstream, but source provenance must name the
# repository that actually owns the reviewed commit and tag.
REPOSITORY = "botiverse/KuiklyUI"
UPSTREAM_REPOSITORY = "Tencent-TDS/KuiklyUI"
UPSTREAM_BROWSE_URL = f"https://github.com/{UPSTREAM_REPOSITORY}"
SOURCE_BROWSE_URL = f"https://github.com/{REPOSITORY}"
SOURCE_SCM_CONNECTION = f"scm:git:https://github.com/{REPOSITORY}.git"
SOURCE_SCM_DEVELOPER_CONNECTION = f"scm:git:ssh://git@github.com/{REPOSITORY}.git"
GROUP = "com.tencent.kuikly-open"
GROUP_PATH = "com/tencent/kuikly-open"
RELEASE = "2.24.0-raft.1"
NORMAL_VERSION = f"{RELEASE}-2.1.21"
OHOS_VERSION = f"{RELEASE}-2.0.21-ohos"
MANIFEST_ARTIFACT = "kuikly-release-manifest"
MANIFEST_VERSION = RELEASE
MANIFEST_PATH = (
    f"{GROUP_PATH}/{MANIFEST_ARTIFACT}/{MANIFEST_VERSION}/"
    f"{MANIFEST_ARTIFACT}-{MANIFEST_VERSION}.json"
)
PUBLIC_MAVEN_ORIGIN = "https://maven.artifacts.botiverse.dev"

SHA40 = re.compile(r"[0-9a-f]{40}")
SHA64 = re.compile(r"[0-9a-f]{64}")
EXACT_VERSION = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")
CHECKSUM_SUFFIXES = (".md5", ".sha1", ".sha256", ".sha512")
SIDE_SUFFIXES = CHECKSUM_SUFFIXES + (".asc",)
CHECKSUM_ALGORITHMS = {
    ".md5": ("md5", 32),
    ".sha1": ("sha1", 40),
    ".sha256": ("sha256", 64),
    ".sha512": ("sha512", 128),
}


class ContractError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ContractError(message)


@dataclass(frozen=True)
class Seed:
    plane: str
    artifact: str
    role: str
    shape: str
    target_name: str | None
    publication_name: str
    platform: str
    owner_artifact: str | None = None

    @property
    def version(self) -> str:
        return NORMAL_VERSION if self.plane == "normal" else OHOS_VERSION

    @property
    def coordinate(self) -> str:
        return f"{GROUP}:{self.artifact}:{self.version}"


def seed(
    plane: str,
    artifact: str,
    role: str,
    shape: str,
    target: str | None,
    publication: str,
    platform: str,
    owner: str | None = None,
) -> Seed:
    return Seed(plane, artifact, role, shape, target, publication, platform, owner)


# Owner-reviewed machine closure.  It is deliberately explicit: the staging
# filesystem decides which physical carriers exist, but never which whole GAVs
# are allowed to disappear.
SEEDS: tuple[Seed, ...] = (
    seed("normal", "core", "root", "kmp-root", None, "kotlinMultiplatform", "multiplatform"),
    seed("normal", "core-annotations", "root", "kmp-root", None, "kotlinMultiplatform", "multiplatform"),
    seed("normal", "compose", "root", "kmp-root", None, "kotlinMultiplatform", "multiplatform"),
    seed("normal", "core-android", "metadata-member", "android", "android", "android", "android", owner="core"),
    seed("normal", "core-annotations-android", "metadata-member", "android", "android", "android", "android", owner="core-annotations"),
    seed("normal", "core-iosarm64", "metadata-member", "native", "iosArm64", "iosArm64", "apple", owner="core"),
    seed("normal", "core-iossimulatorarm64", "metadata-member", "native", "iosSimulatorArm64", "iosSimulatorArm64", "apple", owner="core"),
    seed("normal", "core-iosx64", "metadata-member", "native", "iosX64", "iosX64", "apple", owner="core"),
    seed("normal", "core-js", "metadata-member", "js", "js", "js", "js", owner="core"),
    seed("normal", "core-macosarm64", "metadata-member", "native", "macosArm64", "macosArm64", "apple", owner="core"),
    seed("normal", "core-macosx64", "metadata-member", "native", "macosX64", "macosX64", "apple", owner="core"),
    seed("normal", "core-annotations-jvm", "metadata-member", "jvm", "jvm", "jvm", "jvm", owner="core-annotations"),
    seed("normal", "core-annotations-iosarm64", "metadata-member", "native", "iosArm64", "iosArm64", "apple", owner="core-annotations"),
    seed("normal", "core-annotations-iossimulatorarm64", "metadata-member", "native", "iosSimulatorArm64", "iosSimulatorArm64", "apple", owner="core-annotations"),
    seed("normal", "core-annotations-iosx64", "metadata-member", "native", "iosX64", "iosX64", "apple", owner="core-annotations"),
    seed("normal", "core-annotations-js", "metadata-member", "js", "js", "js", "js", owner="core-annotations"),
    seed("normal", "core-annotations-macosarm64", "metadata-member", "native", "macosArm64", "macosArm64", "apple", owner="core-annotations"),
    seed("normal", "core-annotations-macosx64", "metadata-member", "native", "macosX64", "macosX64", "apple", owner="core-annotations"),
    seed("normal", "compose-android", "metadata-member", "android", "android", "android", "android", owner="compose"),
    seed("normal", "compose-iosarm64", "metadata-member", "native", "iosArm64", "iosArm64", "apple", owner="compose"),
    seed("normal", "compose-iossimulatorarm64", "metadata-member", "native", "iosSimulatorArm64", "iosSimulatorArm64", "apple", owner="compose"),
    seed("normal", "compose-iosx64", "metadata-member", "native", "iosX64", "iosX64", "apple", owner="compose"),
    seed("normal", "compose-js", "metadata-member", "js", "js", "js", "js", owner="compose"),
    seed("normal", "compose-macosarm64", "metadata-member", "native", "macosArm64", "macosArm64", "apple", owner="compose"),
    seed("normal", "compose-macosx64", "metadata-member", "native", "macosX64", "macosX64", "apple", owner="compose"),
    seed("normal", "core-ksp", "adjunct", "core-ksp", None, "maven", "jvm"),
    seed("normal", "core-render-android", "adjunct", "android", "android", "maven", "android"),
    seed("ohos", "core", "root", "kmp-root", None, "kotlinMultiplatform", "multiplatform"),
    seed("ohos", "core-ohosarm64", "metadata-member", "native", "ohosArm64", "ohosArm64", "ohos", owner="core"),
    seed("ohos", "core-annotations", "root", "kmp-root", None, "kotlinMultiplatform", "multiplatform"),
    seed("ohos", "core-annotations-jvm", "metadata-member", "jvm", "jvm", "jvm", "jvm", owner="core-annotations"),
    seed("ohos", "core-annotations-ohosarm64", "metadata-member", "native", "ohosArm64", "ohosArm64", "ohos", owner="core-annotations"),
    seed("ohos", "compose", "root", "kmp-root", None, "kotlinMultiplatform", "multiplatform"),
    seed("ohos", "compose-ohosarm64", "metadata-member", "native", "ohosArm64", "ohosArm64", "ohos", owner="compose"),
    seed("ohos", "core-ksp", "adjunct", "core-ksp", None, "maven", "jvm"),
    seed("normal", "core-render-ios", "host-renderer", "host-ios", "ios", "binary", "apple"),
    seed("ohos", "core-render-ohos", "host-renderer", "host-ohos", "ohosArm64", "binary", "ohos"),
)

require(len(SEEDS) == 37, "internal seed closure is not 37")
SEED_BY_GAV = {(GROUP, item.artifact, item.version): item for item in SEEDS}
require(len(SEED_BY_GAV) == len(SEEDS), "internal seed closure contains duplicate GAV")

# Gradle publishes these six cinterop KLIBs as additional files of their
# owning native metadata-member modules.  Keep the schema explicit: an
# arbitrary ``-cinterop-*.klib`` filename must not become an admitted carrier
# merely because it has a familiar suffix.
CINTEROP_KLIB_SCHEMA: dict[tuple[str, str, str], str] = {
    (GROUP, "core-iosarm64", NORMAL_VERSION): "kuikly",
    (GROUP, "core-iossimulatorarm64", NORMAL_VERSION): "kuikly",
    (GROUP, "core-iosx64", NORMAL_VERSION): "kuikly",
    (GROUP, "core-macosarm64", NORMAL_VERSION): "kuikly",
    (GROUP, "core-macosx64", NORMAL_VERSION): "kuikly",
    (GROUP, "core-ohosarm64", OHOS_VERSION): "ohos",
}
require(len(CINTEROP_KLIB_SCHEMA) == 6, "internal cinterop KLIB schema is not six carriers")
require(
    set(CINTEROP_KLIB_SCHEMA).issubset(SEED_BY_GAV),
    "internal cinterop KLIB schema names a non-seed owner",
)


def cinterop_klib_filename(seed_item: Seed) -> str | None:
    interop = CINTEROP_KLIB_SCHEMA.get((GROUP, seed_item.artifact, seed_item.version))
    if interop is None:
        return None
    return f"{seed_item.artifact}-{seed_item.version}-cinterop-{interop}.klib"


def cinterop_klib_path(seed_item: Seed) -> str | None:
    filename = cinterop_klib_filename(seed_item)
    if filename is None:
        return None
    return f"{GROUP_PATH}/{seed_item.artifact}/{seed_item.version}/{filename}"


EXPECTED_CINTEROP_KLIB_PATHS = frozenset(
    path
    for seed_item in SEEDS
    for path in (cinterop_klib_path(seed_item),)
    if path is not None
)
require(len(EXPECTED_CINTEROP_KLIB_PATHS) == 6, "internal cinterop KLIB path schema is not six carriers")

# Compose target publications carry one Gradle-declared Kotlin resources ZIP
# at these exact seven GAVs.  This is another physical-file schema, not a
# wildcard acceptance rule for filenames containing the word "resources".
KOTLIN_RESOURCE_SCHEMA: frozenset[tuple[str, str, str]] = frozenset({
    (GROUP, "compose-iosarm64", NORMAL_VERSION),
    (GROUP, "compose-iossimulatorarm64", NORMAL_VERSION),
    (GROUP, "compose-iosx64", NORMAL_VERSION),
    (GROUP, "compose-js", NORMAL_VERSION),
    (GROUP, "compose-macosarm64", NORMAL_VERSION),
    (GROUP, "compose-macosx64", NORMAL_VERSION),
    (GROUP, "compose-ohosarm64", OHOS_VERSION),
})
require(len(KOTLIN_RESOURCE_SCHEMA) == 7, "internal Kotlin resource schema is not seven carriers")
require(
    set(KOTLIN_RESOURCE_SCHEMA).issubset(SEED_BY_GAV),
    "internal Kotlin resource schema names a non-seed owner",
)


def kotlin_resource_filename(seed_item: Seed) -> str | None:
    if (GROUP, seed_item.artifact, seed_item.version) not in KOTLIN_RESOURCE_SCHEMA:
        return None
    return f"{seed_item.artifact}-{seed_item.version}-kotlin_resources.kotlin_resources.zip"


def kotlin_resource_path(seed_item: Seed) -> str | None:
    filename = kotlin_resource_filename(seed_item)
    if filename is None:
        return None
    return f"{GROUP_PATH}/{seed_item.artifact}/{seed_item.version}/{filename}"


EXPECTED_KOTLIN_RESOURCE_PATHS = frozenset(
    path
    for seed_item in SEEDS
    for path in (kotlin_resource_path(seed_item),)
    if path is not None
)
require(len(EXPECTED_KOTLIN_RESOURCE_PATHS) == 7, "internal Kotlin resource path schema is not seven carriers")


def owner_root(seed_item: Seed) -> Seed | None:
    if seed_item.role != "metadata-member":
        require(seed_item.owner_artifact is None, f"non-member seed declares owner root: {seed_item.coordinate}")
        return None
    require(seed_item.owner_artifact is not None, f"metadata member lacks owner root: {seed_item.coordinate}")
    owner = SEED_BY_GAV.get((GROUP, seed_item.owner_artifact, seed_item.version))
    require(
        owner is not None and owner.role == "root" and owner.plane == seed_item.plane,
        f"metadata member owner root is missing or crosses planes: {seed_item.coordinate}",
    )
    return owner


for seed_item in SEEDS:
    owner_root(seed_item)


def module_primary_path(seed_item: Seed) -> str:
    return (
        f"{GROUP_PATH}/{seed_item.artifact}/{seed_item.version}/"
        f"{seed_item.artifact}-{seed_item.version}.module"
    )


def owner_component_url(seed_item: Seed) -> str | None:
    owner = owner_root(seed_item)
    if owner is None:
        return None
    return (
        f"../../{owner.artifact}/{owner.version}/"
        f"{owner.artifact}-{owner.version}.module"
    )


EXPECTED_PRODUCERS: tuple[str, ...] = (
    "normal-linux",
    "normal-macos",
    "ios-renderer",
    "ohos-gradle",
    "ohos-renderer",
)

REQUIRED_KINDS: dict[str, frozenset[str]] = {
    "kmp-root": frozenset({"pom", "gradle-module", "jar", "sources", "tooling-metadata"}),
    "android": frozenset({"pom", "gradle-module", "aar", "sources"}),
    "jvm": frozenset({"pom", "gradle-module", "jar", "sources", "javadoc"}),
    "native": frozenset({"pom", "gradle-module", "klib", "sources", "metadata-jar"}),
    "js": frozenset({"pom", "gradle-module", "klib", "sources"}),
    "core-ksp": frozenset({"pom", "gradle-module", "jar", "javadoc"}),
    "host-ios": frozenset({"pom", "gradle-module", "xcframework-zip", "podspec-json", "provenance-json"}),
    "host-ohos": frozenset({"pom", "gradle-module", "har", "provenance-json"}),
}

# These producer-specific publication shapes are deliberately narrower than
# their broad platform family.  Do not require carriers that the reviewed
# Gradle publications do not emit, and do not synthesize replacement bytes.
REQUIRED_KIND_OVERRIDES: dict[tuple[str, str, str], frozenset[str]] = {
    (GROUP, "core-render-android", NORMAL_VERSION): frozenset({
        "pom", "gradle-module", "aar",
    }),
    (GROUP, "core-ohosarm64", OHOS_VERSION): frozenset({
        "pom", "gradle-module", "klib", "sources",
    }),
    (GROUP, "core-annotations-ohosarm64", OHOS_VERSION): frozenset({
        "pom", "gradle-module", "klib", "sources",
    }),
    (GROUP, "compose-ohosarm64", OHOS_VERSION): frozenset({
        "pom", "gradle-module", "klib", "sources",
    }),
}
require(len(REQUIRED_KIND_OVERRIDES) == 4, "internal physical shape override is not four GAVs")
require(
    set(REQUIRED_KIND_OVERRIDES).issubset(SEED_BY_GAV),
    "internal physical shape override names a non-seed GAV",
)


def required_kinds(seed_item: Seed) -> frozenset[str]:
    return REQUIRED_KIND_OVERRIDES.get(
        (GROUP, seed_item.artifact, seed_item.version),
        REQUIRED_KINDS[seed_item.shape],
    )

# Exact owner-group dependency graph emitted by the reviewed Gradle producer.
# This is intentionally an edge closure, not merely a target allow-list: a
# missing dependency is just as release-significant as an unexpected one.
EXPECTED_OWNER_POM_EDGES: frozenset[
    tuple[tuple[str, str, str], tuple[str, str, str]]
] = frozenset({
    ((GROUP, "compose", NORMAL_VERSION), (GROUP, "core", NORMAL_VERSION)),
    ((GROUP, "compose", NORMAL_VERSION), (GROUP, "core-annotations", NORMAL_VERSION)),
    ((GROUP, "compose-android", NORMAL_VERSION), (GROUP, "core-android", NORMAL_VERSION)),
    ((GROUP, "compose-android", NORMAL_VERSION), (GROUP, "core-annotations-android", NORMAL_VERSION)),
    ((GROUP, "compose-js", NORMAL_VERSION), (GROUP, "core-js", NORMAL_VERSION)),
    ((GROUP, "compose-js", NORMAL_VERSION), (GROUP, "core-annotations-js", NORMAL_VERSION)),
    ((GROUP, "core-ksp", NORMAL_VERSION), (GROUP, "core-annotations-jvm", NORMAL_VERSION)),
    ((GROUP, "compose-iosarm64", NORMAL_VERSION), (GROUP, "core-iosarm64", NORMAL_VERSION)),
    ((GROUP, "compose-iosarm64", NORMAL_VERSION), (GROUP, "core-annotations-iosarm64", NORMAL_VERSION)),
    ((GROUP, "compose-iossimulatorarm64", NORMAL_VERSION), (GROUP, "core-iossimulatorarm64", NORMAL_VERSION)),
    ((GROUP, "compose-iossimulatorarm64", NORMAL_VERSION), (GROUP, "core-annotations-iossimulatorarm64", NORMAL_VERSION)),
    ((GROUP, "compose-iosx64", NORMAL_VERSION), (GROUP, "core-iosx64", NORMAL_VERSION)),
    ((GROUP, "compose-iosx64", NORMAL_VERSION), (GROUP, "core-annotations-iosx64", NORMAL_VERSION)),
    ((GROUP, "compose-macosarm64", NORMAL_VERSION), (GROUP, "core-macosarm64", NORMAL_VERSION)),
    ((GROUP, "compose-macosarm64", NORMAL_VERSION), (GROUP, "core-annotations-macosarm64", NORMAL_VERSION)),
    ((GROUP, "compose-macosx64", NORMAL_VERSION), (GROUP, "core-macosx64", NORMAL_VERSION)),
    ((GROUP, "compose-macosx64", NORMAL_VERSION), (GROUP, "core-annotations-macosx64", NORMAL_VERSION)),
    ((GROUP, "compose-ohosarm64", OHOS_VERSION), (GROUP, "core-ohosarm64", OHOS_VERSION)),
    ((GROUP, "core-ksp", OHOS_VERSION), (GROUP, "core-annotations-jvm", OHOS_VERSION)),
})


def expected_pom_dependency_type(target: tuple[str, str, str]) -> str | None:
    target_seed = SEED_BY_GAV.get(target)
    require(target_seed is not None, f"POM owner dependency target is not a release seed: {':'.join(target)}")
    return "aar" if "aar" in required_kinds(target_seed) else None


require(len(EXPECTED_OWNER_POM_EDGES) == 19, "internal owner POM graph is not 19 edges")
for expected_source, expected_target in EXPECTED_OWNER_POM_EDGES:
    require(expected_source in SEED_BY_GAV, f"owner POM graph source is not a release seed: {':'.join(expected_source)}")
    require(expected_target in SEED_BY_GAV, f"owner POM graph target is not a release seed: {':'.join(expected_target)}")
require(
    sum(expected_pom_dependency_type(target) == "aar" for _, target in EXPECTED_OWNER_POM_EDGES) == 2,
    "internal owner POM graph must contain exactly two AAR edges",
)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


MAVEN_OWNER_AAR_ARTIFACTS = ("core-android", "core-annotations-android")
MAVEN_UNRESOLVED_LIST = re.compile(
    r"The following artifacts? could not be resolved:\s*(.+?)"
    r"(?=:\s+Could not find artifact| -> \[Help [0-9]+\])"
)
MAVEN_COORDINATE_PART = re.compile(r"[A-Za-z0-9_.-]+")
ANSI_ESCAPE = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")


def parse_maven_unresolved_coordinates(log_text: str) -> list[str]:
    """Return Maven's complete terminal unresolved-coordinate list.

    The consumer deliberately preserves a real nonzero Maven result at a
    non-owner Android dependency boundary.  Classification is therefore based
    only on Maven's own terminal unresolved list, never on a curated external
    allow-list.
    """
    normalized = ANSI_ESCAPE.sub("", log_text).replace("\r", "")
    coordinates: set[str] = set()
    for match in MAVEN_UNRESOLVED_LIST.finditer(normalized):
        for raw_coordinate in match.group(1).split(","):
            coordinate = raw_coordinate.strip()
            parts = coordinate.split(":")
            require(
                len(parts) in {4, 5}
                and all(MAVEN_COORDINATE_PART.fullmatch(part) is not None for part in parts),
                f"Maven terminal unresolved coordinate is malformed: {coordinate!r}",
            )
            coordinates.add(coordinate)
    return sorted(coordinates)


def classify_maven_owner_boundary(exit_code: int, log_text: str) -> dict[str, Any]:
    """Classify Maven without promoting an external failure to full success."""
    require(isinstance(exit_code, int) and 0 <= exit_code <= 255, "Maven exit code is invalid")
    unresolved = parse_maven_unresolved_coordinates(log_text)
    owner_unresolved = [
        coordinate for coordinate in unresolved if coordinate.split(":", 1)[0] == GROUP
    ]
    require(not owner_unresolved, "Maven has unresolved owner-group coordinates: " + ", ".join(owner_unresolved))
    if exit_code == 0:
        require("BUILD SUCCESS" in log_text, "Maven zero exit lacks BUILD SUCCESS")
        require(not unresolved, "Maven zero exit carries unresolved coordinates")
        return {
            "ownerEdgeState": "OWNER_EDGE_CLOSED",
            "terminalState": "FULL_GRAPH_SUCCESS",
            "fullGraphPass": True,
            "mavenExitCode": 0,
            "externalUnresolvedCoordinates": [],
        }
    require("BUILD FAILURE" in log_text, "Maven nonzero exit lacks BUILD FAILURE")
    require(unresolved, "Maven nonzero result lacks a complete unresolved-coordinate list")
    return {
        "ownerEdgeState": "OWNER_EDGE_CLOSED",
        "terminalState": "EXTERNAL_TRANSITIVE_DIAGNOSTIC",
        "fullGraphPass": False,
        "mavenExitCode": exit_code,
        "externalUnresolvedCoordinates": unresolved,
    }


def verify_maven_owner_aar_readback(
    candidate_repository: Path,
    maven_repository: Path,
    log_text: str,
) -> dict[str, dict[str, object]]:
    """Prove both owner AAR edges reached Maven as exact AAR bytes."""
    readback: dict[str, dict[str, object]] = {}
    for artifact in MAVEN_OWNER_AAR_ARTIFACTS:
        candidate_dir = candidate_repository / GROUP_PATH / artifact / NORMAL_VERSION
        cached_dir = maven_repository / GROUP_PATH / artifact / NORMAL_VERSION
        candidate_prefix = candidate_dir / f"{artifact}-{NORMAL_VERSION}"
        cached_prefix = cached_dir / f"{artifact}-{NORMAL_VERSION}"
        artifact_readback: dict[str, object] = {}
        for suffix, kind in ((".pom", "pom"), (".aar", "aar")):
            candidate = Path(str(candidate_prefix) + suffix)
            cached = Path(str(cached_prefix) + suffix)
            require(candidate.is_file() and candidate.stat().st_size > 0, f"candidate owner {artifact} {kind} is missing")
            require(cached.is_file() and cached.stat().st_size > 0, f"Maven cache lacks owner {artifact} {kind}")
            candidate_sha = sha256_file(candidate)
            cached_sha = sha256_file(cached)
            require(candidate_sha == cached_sha, f"Maven owner {artifact} {kind} bytes differ from candidate")
            artifact_readback[f"{kind}Sha256"] = candidate_sha
            artifact_readback[f"{kind}Size"] = candidate.stat().st_size
        jar_prefix = f"{artifact}-{NORMAL_VERSION}.jar"
        forbidden_cache = sorted(path.name for path in cached_dir.glob(jar_prefix + "*"))
        require(not forbidden_cache, f"Maven attempted or cached forbidden owner {artifact} JAR fallback")
        require(jar_prefix not in log_text, f"Maven log records forbidden owner {artifact} JAR request")
        artifact_readback["jarRequestOrCacheEntries"] = 0
        readback[artifact] = artifact_readback
    return readback


def json_bytes(value: object) -> bytes:
    return (json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False) + "\n").encode("utf-8")


def run_git(root: Path, *arguments: str) -> str:
    process = subprocess.run(
        ["git", *arguments], cwd=root, text=True, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, check=False,
    )
    require(process.returncode == 0, f"git {' '.join(arguments)} failed: {process.stderr.strip()}")
    return process.stdout.strip()


def without_suffix(value: str, suffix: str) -> str:
    return value[:-len(suffix)] if suffix and value.endswith(suffix) else value


def safe_har_tar_member(member: tarfile.TarInfo) -> bool:
    name = member.name
    parts = name.rstrip("/").split("/")
    return (
        name != ""
        and not name.startswith("/")
        and "\\" not in name
        and all(part not in {"", ".", ".."} for part in parts)
        and (member.isfile() or member.isdir())
    )


def normalize_origin(value: str) -> str:
    value = without_suffix(value, ".git")
    if value.startswith("git@github.com:"):
        return value[len("git@github.com:"):]
    if value.startswith("ssh://git@github.com/"):
        return value[len("ssh://git@github.com/"):]
    if value.startswith("https://github.com/"):
        return value[len("https://github.com/"):]
    raise ContractError(f"unsupported source origin: {value!r}")


def source_identity(root: Path, tag_ref: str | None, allow_unreleased: bool) -> dict[str, Any]:
    commit = run_git(root, "rev-parse", "HEAD")
    tree = run_git(root, "rev-parse", "HEAD^{tree}")
    require(SHA40.fullmatch(commit) is not None, "source HEAD is not a 40-hex commit")
    require(SHA40.fullmatch(tree) is not None, "source tree is not a 40-hex tree")
    require(run_git(root, "status", "--porcelain") == "", "source worktree is dirty")
    origin = normalize_origin(run_git(root, "remote", "get-url", "origin"))
    require(origin == REPOSITORY, f"source origin is {origin!r}, expected {REPOSITORY}")

    submodules = []
    for line in run_git(root, "ls-files", "-s").splitlines():
        fields = line.split(maxsplit=3)
        if fields and fields[0] == "160000":
            require(len(fields) == 4 and SHA40.fullmatch(fields[1]) is not None, "malformed gitlink entry")
            submodules.append({"path": fields[3], "commit": fields[1]})

    if tag_ref is None:
        require(allow_unreleased, "release manifest requires an immutable tag ref")
        tag: dict[str, Any] = {"ref": None, "object": None, "commit": None, "state": "candidate"}
    else:
        require(tag_ref.startswith("refs/tags/") and ".." not in tag_ref, "tag ref must be fully qualified")
        tag_commit = run_git(root, "rev-parse", f"{tag_ref}^{{commit}}")
        require(tag_commit == commit, "tag does not dereference to source HEAD")
        object_type = run_git(root, "cat-file", "-t", tag_ref)
        tag_object = run_git(root, "rev-parse", tag_ref) if object_type == "tag" else None
        tag = {"ref": tag_ref, "object": tag_object, "commit": tag_commit, "state": "frozen"}
    return {
        "repository": REPOSITORY,
        "commit": commit,
        "tree": tree,
        "tag": tag,
        "submodules": sorted(submodules, key=lambda item: item["path"].encode("utf-8")),
    }


def verify_landed_source(root: Path, source_sha: str, staging3_sha: str) -> None:
    """Require the exact checkout to be reachable from the live staging3 tip."""
    require(SHA40.fullmatch(source_sha) is not None, "source SHA is not a 40-hex commit")
    require(SHA40.fullmatch(staging3_sha) is not None, "staging3 SHA is not a 40-hex commit")
    require(run_git(root, "rev-parse", "HEAD") == source_sha, "source checkout does not match requested SHA")
    require(run_git(root, "status", "--porcelain") == "", "source worktree is dirty")
    run_git(root, "cat-file", "-e", f"{source_sha}^{{commit}}")
    run_git(root, "cat-file", "-e", f"{staging3_sha}^{{commit}}")
    process = subprocess.run(
        ["git", "merge-base", "--is-ancestor", source_sha, staging3_sha],
        cwd=root, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=False,
    )
    require(
        process.returncode == 0,
        f"source exact {source_sha} is not landed in staging3 {staging3_sha}",
    )


def merge_toolchain_receipts(receipt_paths: Sequence[Path]) -> dict[str, Any]:
    """Merge the five independent producer receipts without losing identity."""
    require(len(receipt_paths) == len(EXPECTED_PRODUCERS), "toolchain merge requires exactly five producer receipts")
    producers: dict[str, dict[str, Any]] = {}
    source_sha: str | None = None
    source_tree: str | None = None
    tag_ref: str | None = None
    tag_ref_seen = False
    for path in receipt_paths:
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ContractError(f"cannot read producer toolchain receipt: {path}") from error
        require(
            isinstance(value, dict) and value.get("schema") == "kuikly-producer-toolchain/v1",
            f"unsupported producer toolchain receipt: {path}",
        )
        producer = value.get("producer")
        require(producer in EXPECTED_PRODUCERS, f"unexpected toolchain producer: {producer!r}")
        require(producer not in producers, f"duplicate toolchain producer: {producer}")
        receipt_sha = value.get("sourceSha")
        receipt_tree = value.get("sourceTree")
        receipt_tag_ref = value.get("tagRef")
        require(isinstance(receipt_sha, str) and SHA40.fullmatch(receipt_sha) is not None, f"invalid sourceSha for {producer}")
        require(isinstance(receipt_tree, str) and SHA40.fullmatch(receipt_tree) is not None, f"invalid sourceTree for {producer}")
        require(
            receipt_tag_ref is None
            or (
                isinstance(receipt_tag_ref, str)
                and receipt_tag_ref.startswith("refs/tags/")
                and ".." not in receipt_tag_ref
            ),
            f"invalid tagRef for {producer}",
        )
        require(value.get("releaseSet") == RELEASE, f"release-set mismatch for {producer}")
        tools = value.get("tools")
        require(
            isinstance(tools, dict) and tools
            and all(isinstance(name, str) and name and isinstance(version, str) and version for name, version in tools.items()),
            f"tool inventory is empty or malformed for {producer}",
        )
        if source_sha is None:
            source_sha, source_tree = receipt_sha, receipt_tree
        if not tag_ref_seen:
            tag_ref, tag_ref_seen = receipt_tag_ref, True
        require(receipt_sha == source_sha, f"producer sourceSha drift: {producer}")
        require(receipt_tree == source_tree, f"producer sourceTree drift: {producer}")
        require(receipt_tag_ref == tag_ref, f"producer tagRef drift: {producer}")
        producers[producer] = {
            key: value[key]
            for key in sorted(value)
            if key not in {"schema", "producer", "releaseSet", "sourceSha", "sourceTree", "tagRef"}
        }
    require(set(producers) == set(EXPECTED_PRODUCERS), "toolchain receipts do not exactly cover the five producers")
    return {
        "schema": "kuikly-toolchains/v1",
        "releaseSet": RELEASE,
        "sourceSha": source_sha,
        "sourceTree": source_tree,
        "tagRef": tag_ref,
        "producers": {label: producers[label] for label in EXPECTED_PRODUCERS},
    }


def safe_relative(path: Path, root: Path) -> str:
    relative = path.relative_to(root).as_posix()
    pure = PurePosixPath(relative)
    require(relative != "" and not pure.is_absolute(), f"unsafe staging path: {relative!r}")
    require(".." not in pure.parts and "\\" not in relative and "//" not in relative, f"unsafe staging path: {relative!r}")
    require("%" not in relative and "\x00" not in relative, f"noncanonical staging path: {relative!r}")
    return relative


def walk_files(root: Path) -> Iterator[tuple[str, Path]]:
    require(root.is_dir() and not root.is_symlink(), f"staging root missing or symlinked: {root}")
    for directory, directories, files in os.walk(root, followlinks=False):
        directory_path = Path(directory)
        for name in directories:
            child = directory_path / name
            require(not child.is_symlink(), f"staging contains directory symlink: {safe_relative(child, root)}")
        for name in files:
            child = directory_path / name
            mode = child.lstat().st_mode
            relative = safe_relative(child, root)
            require(stat.S_ISREG(mode) and not child.is_symlink(), f"staging contains non-regular file: {relative}")
            yield relative, child


def parse_gav(path: str) -> tuple[str, str, str, str]:
    parts = path.split("/")
    require(parts[:3] == ["com", "tencent", "kuikly-open"] and len(parts) >= 6, f"path outside {GROUP}: {path}")
    artifact, version = parts[3], parts[4]
    filename = "/".join(parts[5:])
    require(filename != "" and "/" not in filename, f"nested object below version directory: {path}")
    require(EXACT_VERSION.fullmatch(version) is not None and "SNAPSHOT" not in version.upper(), f"invalid immutable version: {path}")
    return GROUP, artifact, version, filename


def classify_kind(filename: str, artifact: str, version: str) -> str:
    prefix = f"{artifact}-{version}"
    require(filename.startswith(prefix), f"filename is not bound to artifact/version: {filename}")
    suffix = filename[len(prefix):]
    if suffix == ".pom":
        return "pom"
    if suffix == ".module":
        return "gradle-module"
    if suffix == ".aar":
        return "aar"
    if suffix == ".klib":
        return "klib"
    interop = CINTEROP_KLIB_SCHEMA.get((GROUP, artifact, version))
    if interop is not None and suffix == f"-cinterop-{interop}.klib":
        return "cinterop-klib"
    if (
        (GROUP, artifact, version) in KOTLIN_RESOURCE_SCHEMA
        and suffix == "-kotlin_resources.kotlin_resources.zip"
    ):
        return "kotlin-resources"
    if suffix == ".har":
        return "har"
    if suffix == ".xcframework.zip":
        return "xcframework-zip"
    if suffix == ".podspec.json":
        return "podspec-json"
    if suffix == ".provenance.json":
        return "provenance-json"
    if suffix == "-sources.jar":
        return "sources"
    if suffix == "-javadoc.jar":
        return "javadoc"
    if suffix == "-metadata.jar":
        return "metadata-jar"
    if suffix == "-kotlin-tooling-metadata.json":
        return "tooling-metadata"
    if suffix == ".jar":
        return "jar"
    raise ContractError(f"unclassified primary carrier: {filename}")


def xml_local_name(tag: str) -> str:
    return tag[tag.index("}") + 1:] if tag.startswith("{") else tag


def direct_xml_children(parent: ET.Element, name: str) -> list[ET.Element]:
    return [child for child in parent if xml_local_name(child.tag) == name]


def unique_xml_text(parent: ET.Element, name: str, path: Path) -> str:
    children = direct_xml_children(parent, name)
    require(len(children) == 1, f"POM profile shape mismatch ({name}): {path}")
    value = children[0].text
    require(value is not None and value.strip() == value and value != "", f"POM profile shape mismatch ({name}): {path}")
    return value


def parse_pom(path: Path, expected: Seed, source_sha: str) -> set[tuple[str, str, str]]:
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as error:
        raise ContractError(f"invalid POM XML: {path}") from error
    require(root.tag.endswith("project"), f"POM root is not project: {path}")
    require(unique_xml_text(root, "modelVersion", path) == "4.0.0", f"POM modelVersion mismatch: {path}")
    properties_nodes = direct_xml_children(root, "properties")
    scm_nodes = direct_xml_children(root, "scm")
    require(len(properties_nodes) == 1 and len(scm_nodes) == 1, f"POM profile shape mismatch: {path}")
    properties = properties_nodes[0]
    scm = scm_nodes[0]
    require(
        [xml_local_name(child.tag) for child in properties] == ["dev.raft.sourceSha"],
        f"POM properties profile shape mismatch: {path}",
    )
    profile = "renderer" if expected.role == "host-renderer" else "gradle"
    if profile == "renderer":
        require(
            sorted(xml_local_name(child.tag) for child in root)
            == sorted(("modelVersion", "groupId", "artifactId", "version", "properties", "scm")),
            f"renderer POM field shape mismatch: {path}",
        )
        require(
            [xml_local_name(child.tag) for child in scm] == ["url", "tag"],
            f"renderer POM SCM field shape mismatch: {path}",
        )
        expected_project_url = None
        expected_scm_url = SOURCE_BROWSE_URL
        expected_connection = None
        expected_developer_connection = None
    else:
        require(
            sorted(xml_local_name(child.tag) for child in scm)
            == sorted(("connection", "developerConnection", "tag", "url")),
            f"Gradle POM SCM field shape mismatch: {path}",
        )
        expected_project_url = UPSTREAM_BROWSE_URL
        expected_scm_url = UPSTREAM_BROWSE_URL
        expected_connection = SOURCE_SCM_CONNECTION
        expected_developer_connection = SOURCE_SCM_DEVELOPER_CONNECTION
    values = {
        "group": unique_xml_text(root, "groupId", path),
        "artifact": unique_xml_text(root, "artifactId", path),
        "version": unique_xml_text(root, "version", path),
        "source": unique_xml_text(properties, "dev.raft.sourceSha", path),
        "project-url": unique_xml_text(root, "url", path) if profile == "gradle" else None,
        "scm-url": unique_xml_text(scm, "url", path),
        "scm-connection": unique_xml_text(scm, "connection", path) if profile == "gradle" else None,
        "scm-developer-connection": unique_xml_text(scm, "developerConnection", path) if profile == "gradle" else None,
        "scm-tag": unique_xml_text(scm, "tag", path),
    }
    expected_values = {
        "group": GROUP,
        "artifact": expected.artifact,
        "version": expected.version,
        "source": source_sha,
        "project-url": expected_project_url,
        "scm-url": expected_scm_url,
        "scm-connection": expected_connection,
        "scm-developer-connection": expected_developer_connection,
        "scm-tag": source_sha,
    }
    require(values == expected_values, f"POM identity/provenance mismatch: {path}")

    namespace = root.tag[: root.tag.index("}") + 1] if root.tag.startswith("{") else ""
    dependencies: set[tuple[str, str, str]] = set()
    dependencies_node = root.find(f"{namespace}dependencies")
    if dependencies_node is not None:
        for dependency in dependencies_node.findall(f"{namespace}dependency"):
            group = dependency.findtext(f"{namespace}groupId", "").strip()
            artifact = dependency.findtext(f"{namespace}artifactId", "").strip()
            version = dependency.findtext(f"{namespace}version", "").strip()
            require(
                bool(group and artifact and version),
                f"POM dependency lacks explicit group/artifact/version: {path}",
            )
            require_dynamic_free(group, artifact, version)
            gav = (group, artifact, version)
            type_nodes = direct_xml_children(dependency, "type")
            require(len(type_nodes) <= 1, f"POM dependency has duplicate type fields: {':'.join(gav)}")
            declared_type = None
            if type_nodes:
                declared_type = type_nodes[0].text
                require(
                    declared_type is not None
                    and declared_type.strip() == declared_type
                    and declared_type != "",
                    f"POM dependency has malformed type: {':'.join(gav)}",
                )
            if group == GROUP and gav in SEED_BY_GAV:
                expected_type = expected_pom_dependency_type(gav)
                if expected_type == "aar":
                    require(
                        declared_type == "aar",
                        f"POM AAR dependency type mismatch: {':'.join(gav)}",
                    )
                else:
                    require(
                        declared_type != "aar",
                        f"POM non-AAR dependency declares aar: {':'.join(gav)}",
                    )
            dependencies.add(gav)
    return dependencies


def require_dynamic_free(group: str, artifact: str, version: str) -> None:
    require(group.strip() == group and artifact.strip() == artifact and version.strip() == version, "dependency coordinate has whitespace")
    require(EXACT_VERSION.fullmatch(version) is not None, f"dependency version is not exact: {group}:{artifact}:{version}")
    upper = version.upper()
    require("SNAPSHOT" not in upper and "+" not in version and "[" not in version and "(" not in version, f"dynamic/changing dependency forbidden: {group}:{artifact}:{version}")


def is_dependency_path(path: tuple[Any, ...]) -> bool:
    return (
        len(path) == 4
        and path[0] == "variants"
        and isinstance(path[1], int)
        and path[2] == "dependencies"
        and isinstance(path[3], int)
    )


def is_dependency_exclude_path(path: tuple[Any, ...]) -> bool:
    return (
        len(path) == 6
        and path[0] == "variants"
        and isinstance(path[1], int)
        and path[2] == "dependencies"
        and isinstance(path[3], int)
        and path[4] == "excludes"
        and isinstance(path[5], int)
    )


def is_dependency_constraint_path(path: tuple[Any, ...]) -> bool:
    return (
        len(path) == 4
        and path[0] == "variants"
        and isinstance(path[1], int)
        and path[2] == "dependencyConstraints"
        and isinstance(path[3], int)
    )


def is_available_at_path(path: tuple[Any, ...]) -> bool:
    return (
        len(path) == 3
        and path[0] == "variants"
        and isinstance(path[1], int)
        and path[2] == "available-at"
    )


def recursive_coordinate_refs(
    value: Any, path: tuple[Any, ...] = (),
) -> Iterator[tuple[str, str, str]]:
    if is_dependency_exclude_path(path):
        require(
            isinstance(value, dict)
            and set(value) == {"group", "module"}
            and isinstance(value.get("group"), str)
            and bool(value["group"])
            and value["group"].strip() == value["group"]
            and isinstance(value.get("module"), str)
            and bool(value["module"])
            and value["module"].strip() == value["module"],
            "Gradle dependency exclude must contain exact nonempty group/module strings",
        )
        return
    if (
        is_dependency_path(path)
        or is_dependency_constraint_path(path)
        or is_available_at_path(path)
    ):
        require(
            isinstance(value, dict)
            and isinstance(value.get("group"), str)
            and bool(value["group"])
            and isinstance(value.get("module"), str)
            and bool(value["module"]),
            "Gradle dependency/constraint/available-at lacks group/module",
        )
    if isinstance(value, dict):
        if is_dependency_path(path) and "excludes" in value:
            require(
                isinstance(value["excludes"], list),
                "Gradle dependency excludes must be a list",
            )
        if "group" in value or "module" in value:
            group = value.get("group")
            module = value.get("module")
            require(
                isinstance(group, str) and group
                and isinstance(module, str) and module,
                "Gradle module coordinate lacks group/module",
            )
            raw_version = value.get("version")
            if isinstance(raw_version, str):
                version = raw_version
            else:
                require(isinstance(raw_version, dict), f"Gradle module coordinate lacks explicit version: {group}:{module}")
                requires = raw_version.get("requires")
                strictly = raw_version.get("strictly")
                require(
                    isinstance(requires, str) and requires
                    or isinstance(strictly, str) and strictly,
                    f"Gradle module coordinate lacks binding requires/strictly version: {group}:{module}",
                )
                if isinstance(requires, str) and requires and isinstance(strictly, str) and strictly:
                    require(requires == strictly, f"Gradle module coordinate has divergent requires/strictly versions: {group}:{module}")
                version = requires if isinstance(requires, str) and requires else strictly
            require(isinstance(version, str) and version, f"Gradle module coordinate version is empty: {group}:{module}")
            yield group, module, version
        for key, child in value.items():
            yield from recursive_coordinate_refs(child, path + (key,))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from recursive_coordinate_refs(child, path + (index,))


def validate_module_component(path: Path, expected: Seed, component: Any) -> None:
    require(isinstance(component, dict), f"module component identity missing: {path}")
    owner = owner_root(expected)
    identity = expected if owner is None else owner
    require(
        component.get("group") == GROUP
        and component.get("module") == identity.artifact
        and component.get("version") == identity.version,
        f"module component identity mismatch: {path}",
    )
    if owner is None:
        require("url" not in component, f"non-member module component redirects: {path}")
    else:
        url = component.get("url")
        require(isinstance(url, str) and url, f"metadata member component URL missing: {path}")
        physical_path = module_primary_path(expected)
        resolved_path = posixpath.normpath(
            posixpath.join(posixpath.dirname(physical_path), url)
        )
        require(
            resolved_path.startswith(GROUP_PATH + "/"),
            f"metadata member component URL escapes the owner group: {path}",
        )
        require(
            resolved_path == module_primary_path(owner),
            f"metadata member component URL targets the wrong owner root: {path}",
        )
        require(
            url == owner_component_url(expected),
            f"metadata member component URL is not canonical: {path}",
        )


def parse_module(path: Path, expected: Seed) -> set[tuple[str, str, str]]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ContractError(f"invalid Gradle module metadata: {path}") from error
    require(isinstance(value, dict) and value.get("formatVersion") in {"1.1", "1.0"}, f"unsupported module metadata: {path}")
    component = value.get("component")
    validate_module_component(path, expected, component)
    cinterop_urls: list[str] = []
    resource_urls: list[str] = []
    variants = value.get("variants")
    require(isinstance(variants, list), f"Gradle module variants are missing: {path}")
    for variant in variants:
        require(isinstance(variant, dict), f"Gradle module variant is malformed: {path}")
        files = variant.get("files", [])
        require(isinstance(files, list), f"Gradle module variant files are malformed: {path}")
        for file_record in files:
            require(isinstance(file_record, dict), f"Gradle module file record is malformed: {path}")
            url = file_record.get("url")
            if isinstance(url, str) and "-cinterop-" in url:
                require(
                    url != "" and url.strip() == url and "/" not in url and "\\" not in url,
                    f"Gradle module cinterop KLIB URL is not a direct version object: {path}",
                )
                cinterop_urls.append(url)
            if isinstance(url, str) and "-kotlin_resources." in url:
                require(
                    url != "" and url.strip() == url and "/" not in url and "\\" not in url,
                    f"Gradle module Kotlin resource URL is not a direct version object: {path}",
                )
                resource_urls.append(url)
    expected_cinterop = cinterop_klib_filename(expected)
    require(
        cinterop_urls == ([] if expected_cinterop is None else [expected_cinterop]),
        f"Gradle module cinterop KLIB owner mismatch: {path}",
    )
    expected_resource = kotlin_resource_filename(expected)
    require(
        resource_urls == ([] if expected_resource is None else [expected_resource]),
        f"Gradle module Kotlin resource owner mismatch: {path}",
    )
    refs = set(recursive_coordinate_refs(value))
    for ref in refs:
        require_dynamic_free(*ref)
    return refs


def is_mutable_metadata(relative: str) -> bool:
    parts = relative.split("/")
    if parts[:3] != ["com", "tencent", "kuikly-open"] or len(parts) != 5:
        return False
    filename = parts[-1]
    base = filename
    while True:
        suffix = next((candidate for candidate in SIDE_SUFFIXES if base.endswith(candidate)), None)
        if suffix is None:
            break
        base = base[: -len(suffix)]
    return base == "maven-metadata.xml"


def write_checksum_companions(repository: Path, *, replace_existing: bool = False) -> int:
    """Create the four immutable Maven checksum objects for every versioned byte.

    Gradle's file-repository publisher does not promise remote-repository
    checksum sidecars.  Producer shards therefore materialize them before the
    assembler sees the repository.  Artifact-level maven-metadata.xml remains
    deliberately excluded because it is mutable repository state, not part of
    an immutable version closure.
    """
    require(repository.is_dir() and not repository.is_symlink(), f"repository directory missing: {repository}")
    targets: list[tuple[str, Path]] = []
    for relative, path in walk_files(repository):
        if is_mutable_metadata(relative) or relative.endswith(CHECKSUM_SUFFIXES):
            continue
        direct = relative[:-4] if relative.endswith(".asc") else relative
        parse_gav(direct)
        targets.append((relative, path))

    require(targets, f"repository has no versioned checksum targets: {repository}")
    written = 0
    for relative, path in sorted(targets, key=lambda item: item[0].encode("utf-8")):
        body = path.read_bytes()
        require(body, f"checksum target is empty: {relative}")
        for suffix, (algorithm, _) in CHECKSUM_ALGORITHMS.items():
            companion = path.with_name(path.name + suffix)
            expected = (hashlib.new(algorithm, body).hexdigest() + "\n").encode("ascii")
            if companion.exists() or companion.is_symlink():
                require(
                    companion.is_file() and not companion.is_symlink(),
                    f"preexisting checksum companion is not a regular file: {companion}",
                )
                if companion.read_bytes() == expected:
                    continue
                require(
                    replace_existing,
                    f"preexisting checksum companion differs: {companion}",
                )
                companion.write_bytes(expected)
                written += 1
            else:
                companion.write_bytes(expected)
                written += 1
    return written


def checksum_descriptor(relative: str) -> tuple[str, str, str] | None:
    for suffix, (algorithm, _) in CHECKSUM_ALGORITHMS.items():
        if relative.endswith(suffix):
            direct_base = relative[: -len(suffix)]
            prefix = "signature-checksum" if direct_base.endswith(".asc") else "checksum"
            return direct_base, algorithm, f"{prefix}-{algorithm}"
    return None


def validate_checksum(body: bytes, direct_body: bytes, algorithm: str, expected_length: int, relative: str) -> None:
    try:
        value = body.decode("ascii").strip()
    except UnicodeDecodeError as error:
        raise ContractError(f"checksum companion is not ASCII: {relative}") from error
    require(
        len(value) == expected_length and re.fullmatch(r"[0-9A-Fa-f]+", value) is not None,
        f"checksum companion has invalid {algorithm} shape: {relative}",
    )
    expected = hashlib.new(algorithm, direct_body).hexdigest()
    require(value.lower() == expected, f"checksum companion differs from direct object: {relative}")


def canonical_set_digest(publications: Sequence[dict[str, Any]]) -> str:
    accumulator = bytearray()
    for item in sorted(publications, key=lambda value: value["path"].encode("utf-8")):
        accumulator.extend(item["path"].encode("utf-8"))
        accumulator.extend(b"\0")
        accumulator.extend(item["sha256"].encode("ascii"))
        accumulator.extend(b"\0")
        accumulator.extend(str(item["size"]).encode("ascii"))
        accumulator.extend(b"\n")
    return sha256_bytes(bytes(accumulator))


def raft_required(group: str, version: str) -> bool:
    return (
        group.startswith("com.tencent.kuikly-open.compose")
        or version.endswith("-raft.1")
        or "-KBA-" in version
    )


def load_predecessor_receipts(path: Path | None) -> dict[str, dict[str, Any]]:
    if path is None:
        return {}
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ContractError(f"cannot read predecessor receipt lock: {path}") from error
    require(isinstance(value, dict) and value.get("schema") == "kuikly-predecessors/v1", "unsupported predecessor receipt lock")
    receipts = value.get("coordinates")
    require(isinstance(receipts, dict), "predecessor receipt lock lacks coordinates")
    return receipts


def assemble(
    source_root: Path,
    staging_specs: Sequence[str],
    tag_ref: str | None,
    allow_unreleased: bool,
    predecessor_receipt_path: Path | None,
    toolchains_path: Path,
) -> tuple[dict[str, Any], dict[str, bytes]]:
    source = source_identity(source_root, tag_ref, allow_unreleased)
    source_sha = source["commit"]
    require(staging_specs, "at least one labelled staging directory is required")

    parsed_staging: list[tuple[str, Path]] = []
    seen_labels: set[str] = set()
    for spec in staging_specs:
        label, separator, raw_root = spec.partition("=")
        require(separator == "=" and label and raw_root, f"staging must be label=directory: {spec}")
        require(label in EXPECTED_PRODUCERS, f"unexpected staging producer label: {label!r}")
        require(label not in seen_labels, f"duplicate staging producer label: {label}")
        seen_labels.add(label)
        parsed_staging.append((label, Path(raw_root).resolve()))
    require(
        seen_labels == set(EXPECTED_PRODUCERS),
        "staging directories do not exactly cover the five producer labels",
    )

    staged: dict[str, tuple[bytes, set[str]]] = {}
    for label, root in parsed_staging:
        for relative, path in walk_files(root):
            # Gradle publishes artifact-level Maven metadata independently in
            # each producer shard.  Those mutable views can legitimately list
            # different versions, so exclude them before cross-shard collision
            # checks.  Only versioned immutable objects enter the release set.
            if is_mutable_metadata(relative):
                continue
            body = path.read_bytes()
            require(body, f"staged carrier is empty: {label}:{relative}")
            if relative in staged:
                previous, producers = staged[relative]
                require(previous == body, f"staging collision has different bytes: {relative}")
                producers.add(label)
            else:
                staged[relative] = (body, {label})
    all_paths = set(staged)
    ignored_metadata = {relative for relative in all_paths if is_mutable_metadata(relative)}
    versioned_paths = all_paths - ignored_metadata
    primary_paths: set[str] = set()
    for relative in sorted(versioned_paths):
        if any(relative.endswith(suffix) for suffix in SIDE_SUFFIXES):
            continue
        parse_gav(relative)
        primary_paths.add(relative)

    companion_descriptors: dict[str, tuple[str, str, str]] = {}
    signature_paths: set[str] = set()
    for relative in sorted(versioned_paths - primary_paths):
        if relative.endswith(".asc"):
            direct_base = relative[:-4]
            require(
                direct_base in primary_paths,
                f"staging contains orphan/unclassified auxiliary objects: {relative}",
            )
            signature_paths.add(relative)
            companion_descriptors[relative] = (direct_base, direct_base, "signature")

    for relative in sorted(versioned_paths - primary_paths - signature_paths):
        descriptor = checksum_descriptor(relative)
        require(
            descriptor is not None,
            f"staging contains orphan/unclassified auxiliary objects: {relative}",
        )
        direct_base, algorithm, kind = descriptor
        if direct_base in primary_paths:
            primary_base = direct_base
        else:
            require(
                direct_base in signature_paths and direct_base[:-4] in primary_paths,
                f"staging contains orphan/unclassified auxiliary objects: {relative}",
            )
            primary_base = direct_base[:-4]
        suffix = next(candidate for candidate, value in CHECKSUM_ALGORITHMS.items() if value[0] == algorithm)
        _, expected_length = CHECKSUM_ALGORITHMS[suffix]
        validate_checksum(
            staged[relative][0], staged[direct_base][0], algorithm, expected_length, relative,
        )
        companion_descriptors[relative] = (direct_base, primary_base, kind)

    publication_paths = primary_paths | set(companion_descriptors)

    publications: list[dict[str, Any]] = []
    discovered_gavs: set[tuple[str, str, str]] = set()
    kinds_by_gav: dict[tuple[str, str, str], set[str]] = {}
    dependencies: set[tuple[str, str, str]] = set()
    pom_owner_edges: set[
        tuple[tuple[str, str, str], tuple[str, str, str]]
    ] = set()
    primary_records: dict[str, dict[str, Any]] = {}
    cinterop_paths: set[str] = set()
    kotlin_resource_paths: set[str] = set()
    for relative in sorted(primary_paths, key=lambda value: value.encode("utf-8")):
        group, artifact, version, filename = parse_gav(relative)
        gav = (group, artifact, version)
        expected = SEED_BY_GAV.get(gav)
        require(expected is not None, f"unexpected staged publication GAV: {group}:{artifact}:{version}")
        kind = classify_kind(filename, artifact, version)
        if kind == "cinterop-klib":
            cinterop_paths.add(relative)
        elif kind == "kotlin-resources":
            kotlin_resource_paths.add(relative)
        body, producers = staged[relative]
        if kind == "pom":
            # Parse the original on-disk copy so XML diagnostics name the producer path.
            matching = []
            for label, root in parsed_staging:
                candidate = root / relative
                if label in producers and candidate.is_file():
                    matching.append(candidate)
            require(matching, f"cannot locate staged POM source: {relative}")
            pom_dependencies = parse_pom(matching[0], expected, source_sha)
            dependencies.update(pom_dependencies)
            pom_owner_edges.update(
                (gav, dependency)
                for dependency in pom_dependencies
                if dependency[0] == GROUP
            )
        elif kind == "gradle-module":
            matching = []
            for label, root in parsed_staging:
                candidate = root / relative
                if label in producers and candidate.is_file():
                    matching.append(candidate)
            require(matching, f"cannot locate staged module source: {relative}")
            dependencies.update(parse_module(matching[0], expected))
        discovered_gavs.add(gav)
        kinds_by_gav.setdefault(gav, set()).add(kind)
        record = {
            "group": group,
            "artifact": artifact,
            "version": version,
            "coordinate": f"{group}:{artifact}:{version}",
            "plane": expected.plane,
            "role": expected.role,
            "shape": expected.shape,
            "targetName": expected.target_name,
            "publicationName": expected.publication_name,
            "platform": expected.platform,
            "kind": kind,
            "path": relative,
            "sha256": sha256_bytes(body),
            "size": len(body),
            "sourceExact": source_sha,
            "tree": source["tree"],
            "producers": sorted(producers),
        }
        publications.append(record)
        primary_records[relative] = record

    for relative in sorted(companion_descriptors, key=lambda value: value.encode("utf-8")):
        direct_base, primary_base, kind = companion_descriptors[relative]
        primary = primary_records[primary_base]
        body, producers = staged[relative]
        if primary["kind"] in {"cinterop-klib", "kotlin-resources"}:
            require(
                sorted(producers) == primary["producers"],
                f"schema-bound carrier companion producer attribution mismatch: {relative}",
            )
        companion = {
            key: primary[key]
            for key in (
                "group", "artifact", "version", "coordinate", "plane", "role",
                "shape", "targetName", "publicationName", "platform",
            )
        }
        companion_details = {
            "kind": kind,
            "path": relative,
            "companionOf": direct_base,
            "sha256": sha256_bytes(body),
            "size": len(body),
            "sourceExact": source_sha,
            "tree": source["tree"],
            "producers": sorted(producers),
        }
        companion.update(companion_details)
        publications.append(companion)

    for direct_base in sorted(primary_paths | signature_paths):
        missing = [
            direct_base + suffix
            for suffix in CHECKSUM_SUFFIXES
            if direct_base + suffix not in companion_descriptors
        ]
        require(
            not missing,
            f"versioned object lacks required checksum companions: {direct_base}: "
            + ", ".join(missing),
        )

    missing_gavs = sorted(set(SEED_BY_GAV) - discovered_gavs)
    require(not missing_gavs, "whole publication seeds missing: " + ", ".join(":".join(item) for item in missing_gavs[:5]))
    extra_gavs = sorted(discovered_gavs - set(SEED_BY_GAV))
    require(not extra_gavs, "unexpected publication seeds: " + ", ".join(":".join(item) for item in extra_gavs[:5]))
    require(
        cinterop_paths == EXPECTED_CINTEROP_KLIB_PATHS,
        "cinterop KLIB carrier set mismatch: missing="
        + ",".join(sorted(EXPECTED_CINTEROP_KLIB_PATHS - cinterop_paths))
        + " unexpected="
        + ",".join(sorted(cinterop_paths - EXPECTED_CINTEROP_KLIB_PATHS)),
    )
    require(
        kotlin_resource_paths == EXPECTED_KOTLIN_RESOURCE_PATHS,
        "Kotlin resource carrier set mismatch: missing="
        + ",".join(sorted(EXPECTED_KOTLIN_RESOURCE_PATHS - kotlin_resource_paths))
        + " unexpected="
        + ",".join(sorted(kotlin_resource_paths - EXPECTED_KOTLIN_RESOURCE_PATHS)),
    )
    for gav, expected in SEED_BY_GAV.items():
        missing_kinds = required_kinds(expected) - kinds_by_gav.get(gav, set())
        require(not missing_kinds, f"{expected.coordinate} lacks required carriers: {', '.join(sorted(missing_kinds))}")

    for seed_item in SEEDS:
        owner = owner_root(seed_item)
        if owner is not None:
            require(
                module_primary_path(owner) in primary_paths,
                f"metadata member owner module is absent: {seed_item.coordinate}",
            )

    # Owner-group references must resolve within the 37-publication closure.
    for dependency in dependencies:
        if dependency[0] == GROUP:
            require(dependency in SEED_BY_GAV, f"owner dependency escapes the 37-seed closure: {':'.join(dependency)}")

    missing_owner_edges = sorted(EXPECTED_OWNER_POM_EDGES - pom_owner_edges)
    require(
        not missing_owner_edges,
        "generated POM graph lacks expected owner edges: "
        + ", ".join(
            f"{':'.join(source)} -> {':'.join(target)}"
            for source, target in missing_owner_edges[:5]
        ),
    )
    unexpected_owner_edges = sorted(pom_owner_edges - EXPECTED_OWNER_POM_EDGES)
    require(
        not unexpected_owner_edges,
        "generated POM graph contains unexpected owner edges: "
        + ", ".join(
            f"{':'.join(source)} -> {':'.join(target)}"
            for source, target in unexpected_owner_edges[:5]
        ),
    )

    receipt_lock = load_predecessor_receipts(predecessor_receipt_path)
    external_dependencies = sorted(
        dependency for dependency in dependencies if dependency[0] != GROUP
    )
    external_coordinates = {":".join(dependency) for dependency in external_dependencies}
    unexpected_receipts = sorted(set(receipt_lock) - external_coordinates)
    require(
        not unexpected_receipts,
        "predecessor receipt lock contains coordinates outside generated metadata: "
        + ", ".join(unexpected_receipts[:5]),
    )
    predecessors = []
    for group, artifact, version in external_dependencies:
        coordinate = f"{group}:{artifact}:{version}"
        required = raft_required(group, version)
        receipt = receipt_lock.get(coordinate)
        if receipt is not None:
            require(isinstance(receipt, dict) and receipt.get("status") == "verified", f"invalid predecessor receipt: {coordinate}")
            require(receipt.get("coordinate") == coordinate, f"predecessor receipt coordinate mismatch: {coordinate}")
            require(receipt.get("publicReadbackState") == "verified", f"predecessor public readback is not verified: {coordinate}")
            require(SHA64.fullmatch(str(receipt.get("manifestSha256", ""))) is not None, f"predecessor receipt lacks manifest hash: {coordinate}")
        predecessors.append({
            "coordinate": coordinate,
            "requiredOnRaft": required,
            "receipt": receipt,
            "status": "verified" if receipt is not None else ("missing" if required else "external-public"),
        })

    try:
        toolchains = json.loads(toolchains_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ContractError(f"cannot read toolchain receipt: {toolchains_path}") from error
    require(isinstance(toolchains, dict) and toolchains.get("schema") == "kuikly-toolchains/v1", "unsupported toolchain receipt")
    producer_labels = {label for label, _, _ in (spec.partition("=") for spec in staging_specs)}
    require(set(toolchains.get("producers", {})) == producer_labels, "toolchain receipt does not exactly cover staging producers")
    require(toolchains.get("releaseSet") == RELEASE, "toolchain receipt release-set mismatch")
    require(toolchains.get("sourceSha") == source_sha, "toolchain receipt sourceSha mismatch")
    require(toolchains.get("sourceTree") == source["tree"], "toolchain receipt sourceTree mismatch")
    require(toolchains.get("tagRef") == source["tag"]["ref"], "toolchain receipt tagRef mismatch")

    closure = []
    for item in SEEDS:
        record = asdict(item)
        record["version"] = item.version
        record["coordinate"] = item.coordinate
        record["publicationSeed"] = f"{item.plane}:{item.artifact}:{item.publication_name}"
        closure.append(record)

    manifest: dict[str, Any] = {
        "schema": "kuikly-release-manifest/v1",
        "releaseSet": RELEASE,
        "completionLocator": {
            "group": GROUP,
            "artifact": MANIFEST_ARTIFACT,
            "version": MANIFEST_VERSION,
            "path": MANIFEST_PATH,
        },
        "versions": {"normal": NORMAL_VERSION, "ohos": OHOS_VERSION},
        "source": source,
        "artifactGroup": GROUP,
        "publicationSeedCount": len(SEEDS),
        "artifactClosure": closure,
        "predecessors": predecessors,
        "toolchainTagRef": toolchains["tagRef"],
        "toolchains": toolchains["producers"],
        "publications": publications,
        "publicationFileCount": len(publications),
        "publicationPrimaryFileCount": len(primary_paths),
        "publicationChecksumFileCount": sum(
            checksum_descriptor(relative) is not None for relative in companion_descriptors
        ),
        "publicationSignatureFileCount": len(signature_paths),
        "mutableMetadataPolicy": "exclude-artifact-level-maven-metadata-and-companions",
        "setSha256": canonical_set_digest(publications),
        "publishable": source["tag"]["state"] == "frozen" and all(
            item["status"] != "missing" for item in predecessors
        ),
    }
    return manifest, {path: staged[path][0] for path in publication_paths}


def validate_manifest(value: dict[str, Any], require_publishable: bool = False) -> None:
    require(value.get("schema") == "kuikly-release-manifest/v1", "unsupported manifest schema")
    require(value.get("releaseSet") == RELEASE, "release-set mismatch")
    require(value.get("versions") == {"normal": NORMAL_VERSION, "ohos": OHOS_VERSION}, "version plane mismatch")
    locator = value.get("completionLocator")
    require(locator == {"group": GROUP, "artifact": MANIFEST_ARTIFACT, "version": MANIFEST_VERSION, "path": MANIFEST_PATH}, "completion locator mismatch")
    source = value.get("source")
    require(isinstance(source, dict) and source.get("repository") == REPOSITORY, "source repository mismatch")
    require(SHA40.fullmatch(str(source.get("commit", ""))) is not None, "source commit invalid")
    require(SHA40.fullmatch(str(source.get("tree", ""))) is not None, "source tree invalid")
    source_tag = source.get("tag")
    require(isinstance(source_tag, dict), "source tag receipt missing")
    if source_tag.get("state") == "candidate":
        require(
            source_tag == {"ref": None, "object": None, "commit": None, "state": "candidate"},
            "candidate source tag receipt invalid",
        )
    else:
        require(
            source_tag.get("state") == "frozen"
            and isinstance(source_tag.get("ref"), str)
            and source_tag["ref"].startswith("refs/tags/")
            and source_tag.get("commit") == source.get("commit"),
            "frozen source tag receipt invalid",
        )
    closure = value.get("artifactClosure")
    require(isinstance(closure, list) and len(closure) == 37 and value.get("publicationSeedCount") == 37, "manifest does not contain 37 product seeds")
    expected_closure = []
    for seed_item in SEEDS:
        record = asdict(seed_item)
        record["version"] = seed_item.version
        record["coordinate"] = seed_item.coordinate
        record["publicationSeed"] = f"{seed_item.plane}:{seed_item.artifact}:{seed_item.publication_name}"
        expected_closure.append(record)
    require(closure == expected_closure, "manifest closure records do not match the exact 37-seed contract")
    require(value.get("toolchainTagRef") == source_tag.get("ref"), "manifest toolchain tag binding mismatch")
    toolchains = value.get("toolchains")
    require(
        isinstance(toolchains, dict) and set(toolchains) == set(EXPECTED_PRODUCERS)
        and all(isinstance(receipt, dict) and receipt for receipt in toolchains.values()),
        "manifest toolchain producer closure mismatch",
    )
    publications = value.get("publications")
    require(isinstance(publications, list) and publications, "manifest publications empty")
    require(value.get("publicationFileCount") == len(publications), "manifest publicationFileCount mismatch")
    require(
        value.get("mutableMetadataPolicy")
        == "exclude-artifact-level-maven-metadata-and-companions",
        "manifest mutable metadata exclusion policy mismatch",
    )
    paths: set[str] = set()
    publication_gavs: set[str] = set()
    for item in publications:
        require(isinstance(item, dict), "invalid publication record")
        path = item.get("path")
        require(isinstance(path, str) and path not in paths, f"duplicate/invalid publication path: {path!r}")
        require(not is_mutable_metadata(path), f"manifest includes mutable artifact metadata: {path}")
        paths.add(path)
        require(SHA64.fullmatch(str(item.get("sha256", ""))) is not None, f"publication digest invalid: {path}")
        require(isinstance(item.get("size"), int) and item["size"] > 0, f"publication size invalid: {path}")
    primary_kinds: dict[tuple[str, str, str], set[str]] = {}
    for item in publications:
        path = item["path"]
        group, artifact, version, filename = parse_gav(path)
        expected = SEED_BY_GAV.get((group, artifact, version))
        require(expected is not None, f"manifest publication is outside seed closure: {path}")
        expected_identity = {
            "group": group,
            "artifact": artifact,
            "version": version,
            "coordinate": expected.coordinate,
            "plane": expected.plane,
            "role": expected.role,
            "shape": expected.shape,
            "targetName": expected.target_name,
            "publicationName": expected.publication_name,
            "platform": expected.platform,
            "sourceExact": source["commit"],
            "tree": source["tree"],
        }
        require(
            all(item.get(key) == expected_value for key, expected_value in expected_identity.items()),
            f"manifest publication identity mismatch: {path}",
        )
        producers = item.get("producers")
        require(
            isinstance(producers, list) and bool(producers) and producers == sorted(set(producers))
            and all(isinstance(producer, str) and producer in EXPECTED_PRODUCERS for producer in producers),
            f"manifest publication producer binding invalid: {path}",
        )
        checksum = checksum_descriptor(path)
        if checksum is not None:
            direct_base, _, expected_kind = checksum
            require(direct_base in paths, f"manifest checksum companion is orphaned: {path}")
            if direct_base.endswith(".asc"):
                require(direct_base[:-4] in paths, f"manifest signature checksum is orphaned: {path}")
            require(
                item.get("kind") == expected_kind and item.get("companionOf") == direct_base,
                f"manifest checksum companion classification mismatch: {path}",
            )
        elif path.endswith(".asc"):
            direct_base = path[:-4]
            require(direct_base in paths, f"manifest signature companion is orphaned: {path}")
            require(
                item.get("kind") == "signature" and item.get("companionOf") == direct_base,
                f"manifest signature companion classification mismatch: {path}",
            )
        else:
            kind = classify_kind(filename, artifact, version)
            require(item.get("kind") == kind and "companionOf" not in item, f"manifest primary kind mismatch: {path}")
            primary_kinds.setdefault((group, artifact, version), set()).add(kind)
            publication_gavs.add(expected.coordinate)
    primary_paths = {
        item["path"] for item in publications
        if checksum_descriptor(item["path"]) is None and not item["path"].endswith(".asc")
    }
    signature_paths = {item["path"] for item in publications if item["path"].endswith(".asc")}
    checksum_paths = {item["path"] for item in publications if checksum_descriptor(item["path"]) is not None}
    cinterop_paths = {
        item["path"] for item in publications if item.get("kind") == "cinterop-klib"
    }
    require(
        cinterop_paths == EXPECTED_CINTEROP_KLIB_PATHS,
        "manifest cinterop KLIB carrier set mismatch",
    )
    kotlin_resource_paths = {
        item["path"] for item in publications if item.get("kind") == "kotlin-resources"
    }
    require(
        kotlin_resource_paths == EXPECTED_KOTLIN_RESOURCE_PATHS,
        "manifest Kotlin resource carrier set mismatch",
    )
    records_by_path = {item["path"]: item for item in publications}
    for path in sorted(cinterop_paths | kotlin_resource_paths):
        for suffix in CHECKSUM_SUFFIXES:
            companion = records_by_path.get(path + suffix)
            require(
                isinstance(companion, dict)
                and companion.get("producers") == records_by_path[path].get("producers"),
                f"manifest schema-bound carrier companion producer attribution mismatch: {path + suffix}",
            )
    for direct_base in sorted(primary_paths | signature_paths):
        missing = [suffix for suffix in CHECKSUM_SUFFIXES if direct_base + suffix not in checksum_paths]
        require(
            not missing,
            f"manifest versioned object lacks required checksum companions: {direct_base}: "
            + ", ".join(missing),
        )
    require(
        value.get("publicationPrimaryFileCount") == len(primary_paths),
        "manifest publicationPrimaryFileCount mismatch",
    )
    require(
        value.get("publicationChecksumFileCount") == len(checksum_paths),
        "manifest publicationChecksumFileCount mismatch",
    )
    require(
        value.get("publicationSignatureFileCount") == len(signature_paths),
        "manifest publicationSignatureFileCount mismatch",
    )
    require(publication_gavs == {item.coordinate for item in SEEDS}, "manifest physical GAV set mismatch")
    for gav, expected in SEED_BY_GAV.items():
        missing_kinds = required_kinds(expected) - primary_kinds.get(gav, set())
        require(not missing_kinds, f"manifest {expected.coordinate} lacks required primary carriers")
    require(value.get("setSha256") == canonical_set_digest(publications), "manifest set digest mismatch")
    require(MANIFEST_PATH not in paths, "completion manifest recursively lists itself")
    predecessors = value.get("predecessors")
    require(isinstance(predecessors, list), "manifest predecessor list missing")
    predecessor_coordinates: set[str] = set()
    for predecessor in predecessors:
        require(isinstance(predecessor, dict), "invalid manifest predecessor record")
        coordinate = predecessor.get("coordinate")
        require(isinstance(coordinate, str) and coordinate.count(":") == 2, "invalid predecessor coordinate")
        require(coordinate not in predecessor_coordinates, f"duplicate predecessor coordinate: {coordinate}")
        predecessor_coordinates.add(coordinate)
        status = predecessor.get("status")
        require(status in {"verified", "missing", "external-public"}, f"invalid predecessor status: {coordinate}")
        receipt = predecessor.get("receipt")
        if status == "verified":
            require(
                isinstance(receipt, dict)
                and receipt.get("coordinate") == coordinate
                and receipt.get("status") == "verified"
                and receipt.get("publicReadbackState") == "verified"
                and SHA64.fullmatch(str(receipt.get("manifestSha256", ""))) is not None,
                f"verified predecessor receipt is incomplete: {coordinate}",
            )
        else:
            require(receipt is None, f"non-verified predecessor carries a receipt: {coordinate}")
    computed_publishable = source_tag.get("state") == "frozen" and all(
        predecessor.get("status") != "missing" for predecessor in predecessors
    )
    require(value.get("publishable") is computed_publishable, "manifest publishable state is not derived from source/predecessors")
    if require_publishable:
        require(value.get("publishable") is True, "manifest is not publication-ready")
        tag = source_tag
        require(tag.get("state") == "frozen" and tag.get("commit") == source.get("commit"), "publication manifest lacks a frozen source tag")
        missing = [item["coordinate"] for item in value.get("predecessors", []) if item.get("requiredOnRaft") and item.get("status") != "verified"]
        require(not missing, "required Raft predecessors are not verified: " + ", ".join(missing))


def deterministic_zip(directory: Path, output: Path) -> None:
    require(directory.is_dir() and not directory.is_symlink(), f"XCFramework directory missing: {directory}")
    files = []
    for relative, path in walk_files(directory):
        files.append((relative, path))
    require(files, "XCFramework directory is empty")
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for relative, path in sorted(files, key=lambda item: item[0].encode("utf-8")):
            info = zipfile.ZipInfo(f"OpenKuiklyIOSRender.xcframework/{relative}", (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            normalized_mode = 0o100755 if path.stat().st_mode & stat.S_IXUSR else 0o100644
            info.external_attr = (normalized_mode & 0xFFFF) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, path.read_bytes())


def write_host_common(output_root: Path, expected: Seed, source: dict[str, Any], files: list[tuple[str, bytes]]) -> None:
    coordinate_dir = output_root / GROUP_PATH / expected.artifact / expected.version
    coordinate_dir.mkdir(parents=True, exist_ok=False)
    published_files = []
    for suffix, body in files:
        filename = f"{expected.artifact}-{expected.version}{suffix}"
        (coordinate_dir / filename).write_bytes(body)
        published_files.append({"name": filename, "url": filename, "size": len(body), "sha512": hashlib.sha512(body).hexdigest()})

    pom = f'''<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>{GROUP}</groupId><artifactId>{expected.artifact}</artifactId><version>{expected.version}</version>
  <properties><dev.raft.sourceSha>{source["commit"]}</dev.raft.sourceSha></properties>
  <scm><url>https://github.com/{REPOSITORY}</url><tag>{source["commit"]}</tag></scm>
</project>
'''.encode("utf-8")
    pom_name = f"{expected.artifact}-{expected.version}.pom"
    (coordinate_dir / pom_name).write_bytes(pom)
    published_files.append({"name": pom_name, "url": pom_name, "size": len(pom), "sha512": hashlib.sha512(pom).hexdigest()})
    module = {
        "formatVersion": "1.1",
        "component": {"group": GROUP, "module": expected.artifact, "version": expected.version},
        "createdBy": {"kuiklyReleaseContract": {"version": "1"}},
        "variants": [{"name": "binary", "attributes": {"org.gradle.category": "library"}, "files": published_files}],
    }
    (coordinate_dir / f"{expected.artifact}-{expected.version}.module").write_bytes(json_bytes(module))
    write_checksum_companions(output_root)


def package_ios(args: argparse.Namespace) -> None:
    source = source_identity(Path(args.source_root).resolve(), args.tag_ref, args.allow_unreleased)
    expected = SEED_BY_GAV[(GROUP, "core-render-ios", NORMAL_VERSION)]
    xcframework = Path(args.xcframework).resolve()
    info_path = xcframework / "Info.plist"
    require(info_path.is_file(), "XCFramework Info.plist missing")
    info = plistlib.loads(info_path.read_bytes())
    libraries = info.get("AvailableLibraries")
    require(isinstance(libraries, list) and len(libraries) == 2, "XCFramework must contain exactly device and simulator libraries")
    slice_kinds: set[str] = set()
    for library in libraries:
        require(isinstance(library, dict), "XCFramework library entry is not an object")
        identifier = library.get("LibraryIdentifier")
        library_path = library.get("LibraryPath")
        architectures = library.get("SupportedArchitectures")
        require(
            isinstance(identifier, str) and re.fullmatch(r"[A-Za-z0-9._-]+", identifier) is not None,
            "XCFramework LibraryIdentifier is unsafe",
        )
        require(
            isinstance(library_path, str)
            and re.fullmatch(r"[A-Za-z0-9._-]+\.framework", library_path) is not None,
            "XCFramework LibraryPath is unsafe or not a framework",
        )
        require(
            isinstance(architectures, list)
            and "arm64" in architectures
            and all(isinstance(architecture, str) and architecture for architecture in architectures),
            f"XCFramework slice {identifier} lacks arm64",
        )
        require(library.get("SupportedPlatform") == "ios", f"XCFramework slice {identifier} is not iOS")
        variant = library.get("SupportedPlatformVariant")
        slice_kind = "simulator" if variant == "simulator" else "device" if variant is None else ""
        require(slice_kind != "", f"XCFramework slice {identifier} has unexpected platform variant")
        require(slice_kind not in slice_kinds, f"XCFramework contains duplicate {slice_kind} slice")
        slice_kinds.add(slice_kind)
        framework = xcframework / identifier / library_path
        require(framework.is_dir() and not framework.is_symlink(), f"XCFramework slice directory missing: {identifier}/{library_path}")
        executable = framework / without_suffix(library_path, ".framework")
        require(
            executable.is_file() and not executable.is_symlink() and executable.stat().st_size > 0,
            f"XCFramework slice executable missing or empty: {identifier}/{library_path}",
        )
    require(slice_kinds == {"device", "simulator"}, "XCFramework lacks device/simulator slice pair")

    temporary_zip = Path(args.output).resolve().parent / ".core-render-ios.xcframework.zip"
    deterministic_zip(xcframework, temporary_zip)
    binary = temporary_zip.read_bytes()
    temporary_zip.unlink()
    binary_sha = sha256_bytes(binary)
    artifact_url = f"{PUBLIC_MAVEN_ORIGIN}/{GROUP_PATH}/{expected.artifact}/{expected.version}/{expected.artifact}-{expected.version}.xcframework.zip"
    podspec = {
        "name": "OpenKuiklyIOSRender",
        "version": expected.version,
        "summary": "Kuikly iOS renderer immutable binary",
        "homepage": "https://github.com/bytemain/KuiklyUI",
        "license": {"type": "KuiklyUI"},
        "authors": {"Kuikly Team": ""},
        "platforms": {"ios": "12.0"},
        "source": {"http": artifact_url, "sha256": binary_sha},
        "vendored_frameworks": "OpenKuiklyIOSRender.xcframework",
        "libraries": "c++",
        "frameworks": ["UIKit", "QuartzCore", "CoreGraphics", "Foundation", "CoreText"],
    }
    provenance = {
        "schema": "kuikly-host-renderer-provenance/v1",
        "coordinate": expected.coordinate,
        "source": source,
        "binarySha256": binary_sha,
        "binarySize": len(binary),
        "producer": "macos-14/Xcode-16.2",
    }
    output = Path(args.output).resolve()
    require(not output.exists(), f"output directory already exists: {output}")
    output.mkdir(parents=True)
    write_host_common(output, expected, source, [
        (".xcframework.zip", binary),
        (".podspec.json", json_bytes(podspec)),
        (".provenance.json", json_bytes(provenance)),
    ])


def package_ohos(args: argparse.Namespace) -> None:
    source = source_identity(Path(args.source_root).resolve(), args.tag_ref, args.allow_unreleased)
    expected = SEED_BY_GAV[(GROUP, "core-render-ohos", OHOS_VERSION)]
    har_path = Path(args.har).resolve()
    require(har_path.is_file() and not har_path.is_symlink(), f"release HAR missing: {har_path}")
    har = har_path.read_bytes()
    require(har.startswith(b"\x1f\x8b"), "HAR is not a gzip-compressed tar archive")
    try:
        with tarfile.open(har_path, "r:gz") as archive:
            entries = archive.getmembers()
            names = [entry.name for entry in entries]
            require(len(names) == len(set(names)), "HAR contains duplicate TAR names")
            for entry in entries:
                require(safe_har_tar_member(entry), f"HAR contains unsafe TAR member: {entry.name}")
            package_entries = [
                entry for entry in entries
                if entry.isfile() and entry.name == "package/oh-package.json5"
            ]
            require(
                len(package_entries) == 1,
                "HAR must contain exactly one root package/oh-package.json5",
            )
            package_stream = archive.extractfile(package_entries[0])
            require(package_stream is not None, "HAR root package manifest is unreadable")
            try:
                package = json.loads(package_stream.read().decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError) as error:
                raise ContractError("HAR root oh-package.json5 is not canonical JSON") from error
            require(
                isinstance(package, dict) and package.get("version") == expected.version,
                "HAR internal package version is not the release coordinate",
            )
            native_entries = [
                entry for entry in entries
                if entry.isfile()
                and entry.name.startswith("package/libs/arm64-v8a/")
                and entry.name.endswith(".so")
            ]
            require(
                native_entries and all(entry.size > 0 for entry in native_entries),
                "HAR lacks nonempty package/libs/arm64-v8a native library",
            )
    except (tarfile.TarError, OSError) as error:
        raise ContractError("HAR gzip payload is not a valid tar archive") from error
    provenance = {
        "schema": "kuikly-host-renderer-provenance/v1",
        "coordinate": expected.coordinate,
        "source": source,
        "binarySha256": sha256_bytes(har),
        "binarySize": len(har),
        "producer": "ghcr.io/bytemain/harmony-next-pipeline-docker/harmonyos-ci-image:v6.1.1.280-android.1",
    }
    output = Path(args.output).resolve()
    require(not output.exists(), f"output directory already exists: {output}")
    output.mkdir(parents=True)
    write_host_common(output, expected, source, [
        (".har", har),
        (".provenance.json", json_bytes(provenance)),
    ])


def command_assemble(args: argparse.Namespace) -> None:
    manifest, bundle = assemble(
        Path(args.source_root).resolve(), args.staging, args.tag_ref,
        args.allow_unreleased,
        Path(args.predecessor_receipts).resolve() if args.predecessor_receipts else None,
        Path(args.toolchains).resolve(),
    )
    validate_manifest(manifest, require_publishable=args.require_publishable)
    bundle_output = Path(args.bundle_output).resolve()
    require(not bundle_output.exists(), f"bundle output already exists: {bundle_output}")
    bundle_output.mkdir(parents=True)
    for relative, body in sorted(bundle.items(), key=lambda item: item[0].encode("utf-8")):
        destination = bundle_output.joinpath(*PurePosixPath(relative).parts)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(body)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(json_bytes(manifest))
    print(
        f"Kuikly release manifest: seeds={manifest['publicationSeedCount']} "
        f"files={manifest['publicationFileCount']} publishable={str(manifest['publishable']).lower()} "
        f"set_sha256={manifest['setSha256']}"
    )


def command_validate(args: argparse.Namespace) -> None:
    try:
        value = json.loads(Path(args.manifest).read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ContractError(f"cannot read manifest: {error}") from error
    require(isinstance(value, dict), "manifest root must be an object")
    validate_manifest(value, require_publishable=args.require_publishable)
    print(
        f"Kuikly release manifest valid: seeds=37 files={value['publicationFileCount']} "
        f"set_sha256={value['setSha256']}"
    )


def command_merge_toolchains(args: argparse.Namespace) -> None:
    output = Path(args.output).resolve()
    require(not output.exists(), f"toolchain output already exists: {output}")
    value = merge_toolchain_receipts([Path(path).resolve() for path in args.receipt])
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(json_bytes(value))
    print(
        f"Kuikly toolchains merged: producers={len(value['producers'])} "
        f"source={value['sourceSha']}"
    )


def command_write_checksums(args: argparse.Namespace) -> None:
    repository = Path(args.repository).resolve()
    written = write_checksum_companions(
        repository,
        replace_existing=args.replace_existing,
    )
    print(f"Kuikly versioned checksum companions ready: created={written}")


def command_verify_landed_source(args: argparse.Namespace) -> None:
    verify_landed_source(
        Path(args.source_root).resolve(),
        args.source_sha,
        args.staging3_sha,
    )
    print(
        f"Kuikly source landed: source={args.source_sha} "
        f"staging3={args.staging3_sha}"
    )


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    commands = root.add_subparsers(dest="command", required=True)

    assemble_command = commands.add_parser("assemble")
    assemble_command.add_argument("--source-root", required=True)
    assemble_command.add_argument("--staging", action="append", required=True, help="producer-label=directory")
    assemble_command.add_argument("--tag-ref")
    assemble_command.add_argument("--allow-unreleased", action="store_true")
    assemble_command.add_argument("--predecessor-receipts")
    assemble_command.add_argument("--toolchains", required=True)
    assemble_command.add_argument("--require-publishable", action="store_true")
    assemble_command.add_argument("--output", required=True)
    assemble_command.add_argument("--bundle-output", required=True)
    assemble_command.set_defaults(function=command_assemble)

    validate_command = commands.add_parser("validate")
    validate_command.add_argument("--manifest", required=True)
    validate_command.add_argument("--require-publishable", action="store_true")
    validate_command.set_defaults(function=command_validate)

    toolchains_command = commands.add_parser("merge-toolchains")
    toolchains_command.add_argument("--receipt", action="append", required=True)
    toolchains_command.add_argument("--output", required=True)
    toolchains_command.set_defaults(function=command_merge_toolchains)

    checksums_command = commands.add_parser("write-checksums")
    checksums_command.add_argument("--repository", required=True)
    checksums_command.add_argument("--replace-existing", action="store_true")
    checksums_command.set_defaults(function=command_write_checksums)

    landed_command = commands.add_parser("verify-landed-source")
    landed_command.add_argument("--source-root", required=True)
    landed_command.add_argument("--source-sha", required=True)
    landed_command.add_argument("--staging3-sha", required=True)
    landed_command.set_defaults(function=command_verify_landed_source)

    ios_command = commands.add_parser("package-ios")
    ios_command.add_argument("--source-root", required=True)
    ios_command.add_argument("--xcframework", required=True)
    ios_command.add_argument("--tag-ref")
    ios_command.add_argument("--allow-unreleased", action="store_true")
    ios_command.add_argument("--output", required=True)
    ios_command.set_defaults(function=package_ios)

    ohos_command = commands.add_parser("package-ohos")
    ohos_command.add_argument("--source-root", required=True)
    ohos_command.add_argument("--har", required=True)
    ohos_command.add_argument("--tag-ref")
    ohos_command.add_argument("--allow-unreleased", action="store_true")
    ohos_command.add_argument("--output", required=True)
    ohos_command.set_defaults(function=package_ohos)
    return root


def main(argv: Sequence[str] | None = None) -> int:
    try:
        args = parser().parse_args(argv)
        args.function(args)
        return 0
    except ContractError as error:
        print(f"contract error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
