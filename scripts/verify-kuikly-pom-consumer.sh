#!/usr/bin/env bash
set -euo pipefail

readonly GROUP="com.tencent.kuikly-open"
readonly -a REQUIRED_AAR_ARTIFACTS=(
  core-android
  core-annotations-android
)

fail() {
  printf 'consumer verification error: %s\n' "$*" >&2
  exit 1
}

[[ $# -eq 3 ]] || fail "usage: $0 <candidate-repository> <fresh-work-directory> <receipt-output>"
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
readonly VERSION="$(PYTHONPATH="$SCRIPT_DIR" python3 -c 'import kuikly_release_contract as c; print(c.NORMAL_VERSION)')"
readonly TARGET="${GROUP}:compose-android:${VERSION}"
readonly SOURCE_ROOT="$(cd "$SCRIPT_DIR/.." && pwd -P)"
readonly CANDIDATE_REPOSITORY="$(python3 - "$1" <<'PY'
import pathlib
import sys
print(pathlib.Path(sys.argv[1]).resolve(strict=True))
PY
)"
readonly WORK_ROOT="$(python3 - "$2" <<'PY'
import pathlib
import sys
print(pathlib.Path(sys.argv[1]).resolve(strict=False))
PY
)"
readonly RECEIPT_OUTPUT="$(python3 - "$3" <<'PY'
import pathlib
import sys
print(pathlib.Path(sys.argv[1]).resolve(strict=False))
PY
)"

[[ -d "$CANDIDATE_REPOSITORY" ]] || fail "candidate repository is not a directory"
[[ ! -e "$WORK_ROOT" ]] || fail "fresh work directory already exists: $WORK_ROOT"
[[ ! -e "$RECEIPT_OUTPUT" ]] || fail "receipt output already exists: $RECEIPT_OUTPUT"
mkdir -p "$WORK_ROOT/gradle-project" "$WORK_ROOT/maven-project"

for required_artifact in "${REQUIRED_AAR_ARTIFACTS[@]}"; do
  required_dir="$CANDIDATE_REPOSITORY/com/tencent/kuikly-open/$required_artifact/$VERSION"
  required_prefix="$required_dir/$required_artifact-$VERSION"
  for suffix in .pom .module .aar -sources.jar; do
    primary="$required_prefix$suffix"
    [[ -s "$primary" ]] || fail "$required_artifact lacks required primary: $primary"
    for checksum_suffix in .md5 .sha1 .sha256 .sha512; do
      [[ -s "$primary$checksum_suffix" ]] \
        || fail "$required_artifact lacks required checksum sidecar: $primary$checksum_suffix"
    done
  done
done

cat > "$WORK_ROOT/gradle-project/settings.gradle.kts" <<'KOTLIN'
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
rootProject.name = "task93-pom-consumer"
KOTLIN

cat > "$WORK_ROOT/gradle-project/build.gradle.kts" <<'KOTLIN'
import java.security.MessageDigest

plugins { base }

val candidateRepository = providers.gradleProperty("candidateRepository").get()
repositories {
    maven {
        name = "task93CandidatePomOnly"
        url = uri(candidateRepository)
        metadataSources {
            mavenPom()
            artifact()
            ignoreGradleMetadataRedirection()
        }
    }
    google {
        metadataSources {
            mavenPom()
            artifact()
            ignoreGradleMetadataRedirection()
        }
    }
    mavenCentral {
        metadataSources {
            mavenPom()
            artifact()
            ignoreGradleMetadataRedirection()
        }
    }
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev") {
        metadataSources {
            mavenPom()
            artifact()
            ignoreGradleMetadataRedirection()
        }
    }
}

val kuiklyPom by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    resolutionStrategy.failOnDynamicVersions()
    resolutionStrategy.failOnChangingVersions()
}
dependencies {
    add(kuiklyPom.name, "com.tencent.kuikly-open:compose-android:__NORMAL_VERSION__")
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            digest.update(buffer, 0, size)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

tasks.register("verifyKuiklyPomClosure") {
    doLast {
        require(gradle.gradleVersion == "7.6.3") {
            "consumer fixture requires Gradle 7.6.3, got ${gradle.gradleVersion}"
        }
        val unresolved = kuiklyPom.resolvedConfiguration.lenientConfiguration.unresolvedModuleDependencies
        require(unresolved.isEmpty()) {
            "unresolved dependencies: " + unresolved.joinToString { it.selector.toString() }
        }
        val records = kuiklyPom.resolvedConfiguration.resolvedArtifacts
            .filter { it.moduleVersion.id.group == "com.tencent.kuikly-open" }
            .map {
                listOf(
                    it.moduleVersion.id.group,
                    it.name,
                    it.moduleVersion.id.version,
                    it.type,
                    sha256(it.file),
                ).joinToString("\t")
            }
            .sorted()
        require(records.any {
            it.startsWith("com.tencent.kuikly-open\tcore-android\t__NORMAL_VERSION__\taar\t")
        }) { "compose POM did not resolve the exact core-android AAR" }
        require(records.any {
            it.startsWith("com.tencent.kuikly-open\tcore-annotations-android\t__NORMAL_VERSION__\taar\t")
        }) { "compose POM did not resolve the exact core-annotations-android AAR" }
        file(System.getenv("TASK93_GRADLE_RECEIPT")).writeText(records.joinToString("\n", postfix = "\n"))
    }
}
KOTLIN
python3 - "$WORK_ROOT/gradle-project/build.gradle.kts" "$VERSION" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
path.write_text(
    path.read_text(encoding="utf-8").replace("__NORMAL_VERSION__", sys.argv[2]),
    encoding="utf-8",
)
PY

readonly GRADLE_HOME="$WORK_ROOT/gradle-home"
readonly GRADLE_RECEIPT="$WORK_ROOT/gradle-owner-artifacts.tsv"
[[ ! -e "$GRADLE_HOME" ]] || fail "Gradle cache is not fresh"
TASK93_GRADLE_RECEIPT="$GRADLE_RECEIPT" \
GRADLE_USER_HOME="$GRADLE_HOME" \
  "$SOURCE_ROOT/gradlew" -p "$WORK_ROOT/gradle-project" \
  --no-daemon --stacktrace \
  -PcandidateRepository="$CANDIDATE_REPOSITORY" verifyKuiklyPomClosure
grep -Fq $'com.tencent.kuikly-open\tcore-annotations-android\t'"${VERSION}"$'\taar\t' \
  "$GRADLE_RECEIPT" || fail "Gradle receipt lacks exact 37th-seed resolution"
grep -Fq $'com.tencent.kuikly-open\tcore-android\t'"${VERSION}"$'\taar\t' \
  "$GRADLE_RECEIPT" || fail "Gradle receipt lacks exact core-android AAR resolution"

cat > "$WORK_ROOT/maven-project/pom.xml" <<MAVEN
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>dev.raft.fixture</groupId><artifactId>task93-pom-consumer</artifactId><version>1</version>
  <repositories>
    <repository><id>task93-candidate</id><url>file://$CANDIDATE_REPOSITORY</url></repository>
    <repository><id>google</id><url>https://dl.google.com/dl/android/maven2/</url></repository>
    <repository><id>central</id><url>https://repo.maven.apache.org/maven2/</url></repository>
    <repository><id>jetbrains-compose</id><url>https://maven.pkg.jetbrains.space/public/p/compose/dev</url></repository>
  </repositories>
</project>
MAVEN

readonly MAVEN_HOME="$WORK_ROOT/maven-home"
readonly MAVEN_LOG="$WORK_ROOT/maven-resolution.log"
readonly MAVEN_BIN="${TASK93_MAVEN_BIN:-/usr/bin/mvn}"
[[ ! -e "$MAVEN_HOME" ]] || fail "Maven cache is not fresh"
[[ -x "$MAVEN_BIN" ]] || fail "Maven executable is unavailable: $MAVEN_BIN"
readonly MAVEN_VERSION_OUTPUT="$("$MAVEN_BIN" --version)"
[[ "$MAVEN_VERSION_OUTPUT" == *"Apache Maven 3.8.7"* ]] \
  || fail "consumer fixture requires Maven 3.8.7"
set +e
"$MAVEN_BIN" --batch-mode \
  -Dmaven.repo.local="$MAVEN_HOME" \
  -f "$WORK_ROOT/maven-project/pom.xml" \
  org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get \
  -Dartifact="$TARGET:pom" -Dtransitive=true 2>&1 | tee "$MAVEN_LOG" >/dev/null
readonly -a MAVEN_PIPE_STATUS=("${PIPESTATUS[@]}")
set -e
[[ "${#MAVEN_PIPE_STATUS[@]}" -eq 2 ]] || fail "Maven pipeline status is incomplete"
[[ "${MAVEN_PIPE_STATUS[1]}" -eq 0 ]] || fail "Maven raw log could not be preserved"
readonly MAVEN_EXIT_CODE="${MAVEN_PIPE_STATUS[0]}"

mkdir -p "$(dirname "$RECEIPT_OUTPUT")"
python3 - "$SOURCE_ROOT" "$CANDIDATE_REPOSITORY" "$GRADLE_RECEIPT" "$MAVEN_HOME" \
  "$MAVEN_LOG" "$MAVEN_EXIT_CODE" "$RECEIPT_OUTPUT" <<'PY'
import hashlib
import json
import pathlib
import sys

source_root = pathlib.Path(sys.argv[1])
sys.path.insert(0, str(source_root / "scripts"))
import kuikly_release_contract as contract

repository = pathlib.Path(sys.argv[2])
gradle_receipt = pathlib.Path(sys.argv[3])
maven_home = pathlib.Path(sys.argv[4])
maven_log = pathlib.Path(sys.argv[5])
maven_exit_code = int(sys.argv[6])
output = pathlib.Path(sys.argv[7])
raw_maven_output = output.with_name("pom-consumer-maven-raw.log")
raw_gradle_output = output.with_name("pom-consumer-gradle-owner.tsv")
version = contract.NORMAL_VERSION
required_artifacts = contract.MAVEN_OWNER_AAR_ARTIFACTS

def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

primaries = {}
for artifact in required_artifacts:
    required_prefix = repository / "com/tencent/kuikly-open" / artifact / version / (artifact + "-" + version)
    artifact_primaries = {}
    for suffix, kind in ((".pom", "pom"), (".module", "gradle-module"), (".aar", "aar"), ("-sources.jar", "sources")):
        path = pathlib.Path(str(required_prefix) + suffix)
        artifact_primaries[kind] = {"sha256": sha256(path), "size": path.stat().st_size}
    primaries[artifact] = artifact_primaries
log_text = maven_log.read_text(encoding="utf-8", errors="strict")
maven_readback = contract.verify_maven_owner_aar_readback(repository, maven_home, log_text)
maven_terminal = contract.classify_maven_owner_boundary(maven_exit_code, log_text)

value = {
    "schema": "kuikly-pom-consumer/v2",
    "target": f"com.tencent.kuikly-open:compose-android:{version}",
    "requiredTransitives": [
        f"com.tencent.kuikly-open:core-android:{version}",
        f"com.tencent.kuikly-open:core-annotations-android:{version}",
    ],
    "candidateRepositoryState": "assembled-review-candidate",
    "candidatePrimaries": primaries,
    "gradle": {
        "cacheState": "fresh",
        "metadataSource": "mavenPom+artifact-only",
        "fullGraphState": "FULL_GRAPH_SUCCESS",
        "requiredAarsResolved": list(required_artifacts),
        "ownerArtifactRecordsSha256": sha256(gradle_receipt),
    },
    "maven": {
        "cacheState": "fresh",
        "rootSelection": "compose-android-pom",
        "transitiveTypeOverrides": 0,
        "resolutionLogSha256": sha256(maven_log),
        "requiredAarReadback": maven_readback,
        **maven_terminal,
    },
}
output.write_text(json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
raw_maven_output.write_bytes(maven_log.read_bytes())
raw_gradle_output.write_bytes(gradle_receipt.read_bytes())
print(
    "Maven owner gate: "
    + maven_terminal["ownerEdgeState"]
    + " / "
    + maven_terminal["terminalState"]
    + f" (raw exit={maven_terminal['mavenExitCode']})"
)
PY

printf 'Kuikly POM consumer gates verified: target=%s requiredAars=%s receipt=%s\n' \
  "$TARGET" "${REQUIRED_AAR_ARTIFACTS[*]}" "$RECEIPT_OUTPUT"
