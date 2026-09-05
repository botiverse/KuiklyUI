#!/usr/bin/env bash
set -euo pipefail

readonly RELEASE_SET="$(tr -d '[:space:]' < "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/../KUIKLY_RELEASE_SET")"
readonly NORMAL_KOTLIN="2.1.21"
readonly OHOS_KOTLIN="2.0.21-KBA-010"
readonly PINNED_OHOS_IMAGE="ghcr.io/bytemain/harmony-next-pipeline-docker/harmonyos-ci-image:v6.1.1.280-android.1"

fail() {
  printf 'stage error: %s\n' "$*" >&2
  exit 1
}

[[ $# -eq 2 ]] || fail "usage: $0 <normal-linux|normal-macos|ios-renderer|ohos-gradle|ohos-renderer> <output-dir>"
readonly MODE="$1"
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
readonly SOURCE_ROOT="$(cd "$SCRIPT_DIR/.." && pwd -P)"
readonly OUTPUT_DIR="$(python3 - "$2" <<'PY'
import pathlib
import sys
print(pathlib.Path(sys.argv[1]).resolve(strict=False))
PY
)"
readonly REPOSITORY_DIR="$OUTPUT_DIR/repository"

case "$MODE" in
  normal-linux|normal-macos|ios-renderer|ohos-gradle|ohos-renderer) ;;
  *) fail "unknown producer mode: $MODE" ;;
esac

python3 - "$SOURCE_ROOT" "$OUTPUT_DIR" <<'PY'
import pathlib
import sys
source = pathlib.Path(sys.argv[1]).resolve()
output = pathlib.Path(sys.argv[2]).resolve()
if output == source or source in output.parents:
    raise SystemExit("stage error: output directory must be outside the source checkout")
PY

[[ ! -e "$OUTPUT_DIR" ]] || fail "output already exists: $OUTPUT_DIR"
cd "$SOURCE_ROOT"

readonly SOURCE_SHA="$(git rev-parse HEAD)"
readonly SOURCE_TREE="$(git rev-parse 'HEAD^{tree}')"
[[ "$SOURCE_SHA" =~ ^[0-9a-f]{40}$ ]] || fail "source HEAD is not a full commit SHA"
[[ "$SOURCE_TREE" =~ ^[0-9a-f]{40}$ ]] || fail "source tree is not a full tree SHA"
[[ "${PUBLICATION_SOURCE_SHA:-}" == "$SOURCE_SHA" ]] || fail "PUBLICATION_SOURCE_SHA does not equal HEAD"
[[ -z "$(git status --porcelain --untracked-files=all)" ]] || fail "source checkout is dirty before staging"

case "$MODE" in
  normal-linux|ohos-gradle|ohos-renderer)
    [[ "$(uname -s)" == "Linux" ]] || fail "$MODE requires Linux"
    ;;
  normal-macos|ios-renderer)
    [[ "$(uname -s)" == "Darwin" ]] || fail "$MODE requires macOS"
    ;;
esac

mkdir -p "$OUTPUT_DIR"

export KUIKLY_VERSION="$RELEASE_SET"
export PUBLICATION_SOURCE_SHA="$SOURCE_SHA"
export RAFT_PUBLICATION_STAGING_DIR="$REPOSITORY_DIR"

GRADLE_COMMON=(
  ./gradlew
  -PraftPublicationStagingDir="$REPOSITORY_DIR"
  -PpublicationSourceSha="$SOURCE_SHA"
  --no-build-cache
  --no-daemon
  --stacktrace
)
readonly -a GRADLE_COMMON

create_native_build_worktree() {
  local build_root="$1"
  [[ "$build_root" == "$OUTPUT_DIR/native-build-source" ]] || fail "unexpected native build worktree path: $build_root"
  [[ ! -e "$build_root" ]] || fail "native build worktree already exists: $build_root"
  git -C "$SOURCE_ROOT" worktree add --detach "$build_root" "$SOURCE_SHA"
  [[ "$(git -C "$build_root" rev-parse HEAD)" == "$SOURCE_SHA" ]] || fail "native build worktree HEAD drift"
  [[ "$(git -C "$build_root" rev-parse 'HEAD^{tree}')" == "$SOURCE_TREE" ]] || fail "native build worktree tree drift"
  [[ -z "$(git -C "$build_root" status --porcelain --untracked-files=all)" ]] || fail "native build worktree is not clean"
}

remove_native_build_worktree() {
  local build_root="$1"
  [[ "$build_root" == "$OUTPUT_DIR/native-build-source" ]] || fail "unexpected native build worktree cleanup path: $build_root"
  if ! git -C "$SOURCE_ROOT" worktree remove --force "$build_root"; then
    printf 'stage error: could not remove native build worktree: %s\n' "$build_root" >&2
    return 1
  fi
  if [[ -e "$build_root" ]]; then
    printf 'stage error: native build worktree cleanup left files behind: %s\n' "$build_root" >&2
    return 1
  fi
}

run_normal_linux() {
  [[ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]] || fail "normal-linux requires ANDROID_HOME or ANDROID_SDK_ROOT"
  export KUIKLY_KOTLIN_VERSION="$NORMAL_KOTLIN"
  mkdir -p "$REPOSITORY_DIR"
  "${GRADLE_COMMON[@]}" -c settings.2.1.21.gradle.kts \
    :core:publishKotlinMultiplatformPublicationToRaftPublicationStagingRepository \
    :core:publishAndroidPublicationToRaftPublicationStagingRepository \
    :core:publishJsPublicationToRaftPublicationStagingRepository \
    :core-annotations:publishKotlinMultiplatformPublicationToRaftPublicationStagingRepository \
    :core-annotations:publishAndroidPublicationToRaftPublicationStagingRepository \
    :core-annotations:publishJvmPublicationToRaftPublicationStagingRepository \
    :core-annotations:publishJsPublicationToRaftPublicationStagingRepository \
    :compose:publishKotlinMultiplatformPublicationToRaftPublicationStagingRepository \
    :compose:publishAndroidPublicationToRaftPublicationStagingRepository \
    :compose:publishJsPublicationToRaftPublicationStagingRepository \
    :core-ksp:publishMavenPublicationToRaftPublicationStagingRepository \
    :core-render-android:publishMavenPublicationToRaftPublicationStagingRepository
}

run_normal_macos() {
  export KUIKLY_KOTLIN_VERSION="$NORMAL_KOTLIN"
  mkdir -p "$REPOSITORY_DIR"
  local -a tasks=()
  local module target
  for module in core core-annotations compose; do
    for target in IosArm64 IosSimulatorArm64 IosX64 MacosArm64 MacosX64; do
      tasks+=(":${module}:publish${target}PublicationToRaftPublicationStagingRepository")
    done
  done
  "${GRADLE_COMMON[@]}" -c settings.2.1.21.gradle.kts "${tasks[@]}"
}

run_ios_renderer() (
  command -v xcodebuild >/dev/null || fail "ios-renderer requires xcodebuild"
  command -v bundle >/dev/null || fail "ios-renderer requires Bundler"
  xcodebuild -version | grep -Fx 'Xcode 16.2' >/dev/null || fail "ios-renderer requires Xcode 16.2"
  export KUIKLY_KOTLIN_VERSION="$NORMAL_KOTLIN"

  local build_root="$OUTPUT_DIR/native-build-source"
  create_native_build_worktree "$build_root"
  native_build_cleanup() {
    local status=$?
    trap - EXIT
    cd "$SOURCE_ROOT" || exit 1
    remove_native_build_worktree "$build_root" || status=1
    exit "$status"
  }
  trap native_build_cleanup EXIT
  cd "$build_root"

  ./gradlew :demo:generateDummyFramework --no-build-cache --no-daemon --stacktrace
  env -u KUIKLY_VERSION KUIKLY_RELEASE_FRAMEWORK_BUILD=1 \
    BUNDLE_PATH="$SOURCE_ROOT/vendor/bundle" BUNDLE_DEPLOYMENT=true \
    bundle exec pod install --project-directory=iosApp --deployment

  local archives="$OUTPUT_DIR/archives"
  local device_archive="$archives/device.xcarchive"
  local simulator_archive="$archives/simulator.xcarchive"
  local xcframework="$OUTPUT_DIR/OpenKuiklyIOSRender.xcframework"
  mkdir -p "$archives"
  xcodebuild archive \
    -workspace iosApp/iosApp.xcworkspace \
    -scheme OpenKuiklyIOSRender \
    -configuration Release \
    -sdk iphoneos \
    -destination 'generic/platform=iOS' \
    -archivePath "$device_archive" \
    SKIP_INSTALL=NO BUILD_LIBRARY_FOR_DISTRIBUTION=YES CODE_SIGNING_ALLOWED=NO
  xcodebuild archive \
    -workspace iosApp/iosApp.xcworkspace \
    -scheme OpenKuiklyIOSRender \
    -configuration Release \
    -sdk iphonesimulator \
    -destination 'generic/platform=iOS Simulator' \
    -archivePath "$simulator_archive" \
    SKIP_INSTALL=NO BUILD_LIBRARY_FOR_DISTRIBUTION=YES CODE_SIGNING_ALLOWED=NO
  local device_framework="$device_archive/Products/Library/Frameworks/OpenKuiklyIOSRender.framework"
  local simulator_framework="$simulator_archive/Products/Library/Frameworks/OpenKuiklyIOSRender.framework"
  [[ -d "$device_framework" && -d "$simulator_framework" ]] || fail "renderer archives do not contain both frameworks"
  xcodebuild -create-xcframework \
    -framework "$device_framework" \
    -framework "$simulator_framework" \
    -output "$xcframework"
  if [[ -n "${PUBLICATION_TAG_REF:-}" ]]; then
    python3 "$SOURCE_ROOT/scripts/kuikly_release_contract.py" package-ios \
      --source-root "$SOURCE_ROOT" --xcframework "$xcframework" \
      --tag-ref "$PUBLICATION_TAG_REF" --output "$REPOSITORY_DIR"
  else
    python3 "$SOURCE_ROOT/scripts/kuikly_release_contract.py" package-ios \
      --source-root "$SOURCE_ROOT" --xcframework "$xcframework" \
      --allow-unreleased --output "$REPOSITORY_DIR"
  fi
  rm -rf "$archives" "$xcframework"
)

require_ohos_environment() {
  [[ -n "${OHOS_SDK_HOME:-}" && -n "${DEVECO_SDK_HOME:-}" ]] || fail "$MODE requires OHOS_SDK_HOME and DEVECO_SDK_HOME"
  command -v ohpm >/dev/null || fail "$MODE requires ohpm"
  command -v hvigorw >/dev/null || fail "$MODE requires hvigorw"
}

run_ohos_gradle() {
  require_ohos_environment
  export KUIKLY_KOTLIN_VERSION="$OHOS_KOTLIN"
  mkdir -p "$REPOSITORY_DIR"
  "${GRADLE_COMMON[@]}" -c settings.2.0.ohos.gradle.kts -PkuiklyOhosOnly=true \
    :core:publishKotlinMultiplatformPublicationToRaftPublicationStagingRepository \
    :core:publishOhosArm64PublicationToRaftPublicationStagingRepository \
    :core-annotations:publishKotlinMultiplatformPublicationToRaftPublicationStagingRepository \
    :core-annotations:publishJvmPublicationToRaftPublicationStagingRepository \
    :core-annotations:publishOhosArm64PublicationToRaftPublicationStagingRepository \
    :compose:publishKotlinMultiplatformPublicationToRaftPublicationStagingRepository \
    :compose:publishOhosArm64PublicationToRaftPublicationStagingRepository \
    :core-ksp:publishMavenPublicationToRaftPublicationStagingRepository
}

run_ohos_renderer() (
  require_ohos_environment
  export KUIKLY_KOTLIN_VERSION="$OHOS_KOTLIN"
  export PATH="$OHOS_SDK_HOME/native/build-tools/cmake/bin:$OHOS_SDK_HOME/native/llvm/bin:$PATH"

  local build_root="$OUTPUT_DIR/native-build-source"
  create_native_build_worktree "$build_root"
  native_build_cleanup() {
    local status=$?
    trap - EXIT
    cd "$SOURCE_ROOT" || exit 1
    remove_native_build_worktree "$build_root" || status=1
    exit "$status"
  }
  trap native_build_cleanup EXIT
  cd "$build_root"

  (
    cd ohosApp
    ohpm install --all
    hvigorw --sync -p product=default --analyze=normal --parallel --no-daemon
    hvigorw assembleHar \
      --mode module \
      -p module=render@default \
      -p product=default \
      -p buildMode=release \
      --analyze=normal \
      --parallel \
      --no-daemon
  )
  mapfile -t release_hars < <(find core-render-ohos/build -type f -name '*.har' -print | sort)
  [[ ${#release_hars[@]} -eq 1 ]] || fail "expected exactly one release HAR, found ${#release_hars[@]}"
  if [[ -n "${PUBLICATION_TAG_REF:-}" ]]; then
    python3 "$SOURCE_ROOT/scripts/kuikly_release_contract.py" package-ohos \
      --source-root "$SOURCE_ROOT" --har "${release_hars[0]}" \
      --tag-ref "$PUBLICATION_TAG_REF" --output "$REPOSITORY_DIR"
  else
    python3 "$SOURCE_ROOT/scripts/kuikly_release_contract.py" package-ohos \
      --source-root "$SOURCE_ROOT" --har "${release_hars[0]}" \
      --allow-unreleased --output "$REPOSITORY_DIR"
  fi
)

case "$MODE" in
  normal-linux) run_normal_linux ;;
  normal-macos) run_normal_macos ;;
  ios-renderer) run_ios_renderer ;;
  ohos-gradle) run_ohos_gradle ;;
  ohos-renderer) run_ohos_renderer ;;
esac

[[ -d "$REPOSITORY_DIR" ]] || fail "producer did not create a repository"
[[ -n "$(find "$REPOSITORY_DIR" -type f -print -quit)" ]] || fail "producer repository is empty"
python3 scripts/kuikly_release_contract.py write-checksums \
  --repository "$REPOSITORY_DIR" --replace-existing
[[ -z "$(git status --porcelain --untracked-files=all)" ]] || fail "producer modified the source checkout"

export TOOL_PYTHON="$(python3 --version 2>&1)"
export TOOL_GIT="$(git --version 2>&1)"
export TOOL_JAVA="$(java -version 2>&1 | sed -n '1p')"
gradle_version="$(./gradlew --version 2>&1)"
export TOOL_GRADLE="$(printf '%s\n' "$gradle_version" | awk '/^Gradle / {print; exit}')"
[[ -n "$TOOL_GRADLE" ]] || fail "could not identify Gradle version"
export TOOL_PLATFORM="$(uname -s)-$(uname -m)"
export TOOL_IMAGE="${RUNNER_IMAGE:-${ImageOS:-host}}"
export TOOL_XCODE=""
export TOOL_RUBY=""
export TOOL_BUNDLER=""
export TOOL_OHPM=""
export TOOL_HVIGOR=""
if [[ "$MODE" == normal-macos || "$MODE" == ios-renderer ]]; then
  export TOOL_XCODE="$(xcodebuild -version | tr '\n' ' ' | sed 's/ $//')"
fi
if [[ "$MODE" == ios-renderer ]]; then
  export TOOL_RUBY="$(ruby --version)"
  export TOOL_BUNDLER="$(bundle --version)"
fi
if [[ "$MODE" == ohos-gradle || "$MODE" == ohos-renderer ]]; then
  export TOOL_IMAGE="$PINNED_OHOS_IMAGE"
  export TOOL_OHPM="$(ohpm --version 2>&1 | sed -n '1p')"
  export TOOL_HVIGOR="$(hvigorw --version 2>&1 | sed -n '1p')"
fi

export PRODUCER_MODE="$MODE"
export RELEASE_SET SOURCE_SHA SOURCE_TREE
python3 - "$OUTPUT_DIR/toolchain.json" <<'PY'
import json
import os
import pathlib
import sys

tools = {
    "python": os.environ["TOOL_PYTHON"],
    "git": os.environ["TOOL_GIT"],
    "java": os.environ["TOOL_JAVA"],
    "gradle": os.environ["TOOL_GRADLE"],
}
for name, variable in (
    ("xcode", "TOOL_XCODE"),
    ("ruby", "TOOL_RUBY"),
    ("bundler", "TOOL_BUNDLER"),
    ("ohpm", "TOOL_OHPM"),
    ("hvigor", "TOOL_HVIGOR"),
):
    value = os.environ.get(variable, "")
    if value:
        tools[name] = value
value = {
    "schema": "kuikly-producer-toolchain/v1",
    "releaseSet": os.environ["RELEASE_SET"],
    "producer": os.environ["PRODUCER_MODE"],
    "sourceSha": os.environ["SOURCE_SHA"],
    "sourceTree": os.environ["SOURCE_TREE"],
    "tagRef": os.environ.get("PUBLICATION_TAG_REF") or None,
    "runner": {
        "platform": os.environ["TOOL_PLATFORM"],
        "image": os.environ["TOOL_IMAGE"],
    },
    "tools": tools,
}
path = pathlib.Path(sys.argv[1])
path.write_text(json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
PY

printf 'Kuikly producer staged: mode=%s source=%s tree=%s files=%s\n' \
  "$MODE" "$SOURCE_SHA" "$SOURCE_TREE" "$(find "$REPOSITORY_DIR" -type f | wc -l | tr -d ' ')"
