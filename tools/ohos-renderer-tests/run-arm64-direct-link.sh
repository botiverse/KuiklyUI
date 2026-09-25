#!/usr/bin/env bash

set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
evidence_dir="${1:-$repo_root/build/ohos-evidence}"
: "${OHOS_SDK_HOME:?OHOS_SDK_HOME must point to the OpenHarmony SDK}"
: "${DEVECO_SDK_HOME:?DEVECO_SDK_HOME must point to the DevEco SDK}"

cmake_bin="$OHOS_SDK_HOME/native/build-tools/cmake/bin/cmake"
ninja_bin="$OHOS_SDK_HOME/native/build-tools/cmake/bin/ninja"
clang_bin="$OHOS_SDK_HOME/native/llvm/bin/clang++"
readelf_bin="$OHOS_SDK_HOME/native/llvm/bin/llvm-readelf"
build_dir="$repo_root/core-render-ohos/.cxx/default/default/debug/arm64-v8a"
hvigor_dir="$repo_root/core-render-ohos/.cxx/default/default/debug/hvigor/arm64-v8a"
out_dir="$repo_root/core-render-ohos/build/default/intermediates/cmake/default/obj/arm64-v8a"

test -x "$cmake_bin"
test -x "$ninja_bin"
test -x "$clang_bin"
test -x "$readelf_bin"
test -f "$hvigor_dir/summary.cmake"

rm -rf "$build_dir" "$out_dir"
mkdir -p "$build_dir" "$out_dir" "$evidence_dir"

"$cmake_bin" \
  -S "$repo_root/core-render-ohos/src/main/cpp" \
  -B "$build_dir" \
  -G Ninja \
  -DOHOS_ARCH=arm64-v8a \
  -DCMAKE_OHOS_ARCH_ABI=arm64-v8a \
  -DCMAKE_BUILD_TYPE=Debug \
  -DCMAKE_SYSTEM_NAME=OHOS \
  -DCMAKE_EXPORT_COMPILE_COMMANDS=ON \
  -DOHOS_SDK_NATIVE="$OHOS_SDK_HOME/native" \
  -DHMOS_SDK_NATIVE="$DEVECO_SDK_HOME/default/hms/native" \
  -DCMAKE_TOOLCHAIN_FILE="$DEVECO_SDK_HOME/default/hms/native/build/cmake/hmos.toolchain.cmake" \
  -DCMAKE_MAKE_PROGRAM="$ninja_bin" \
  -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="$out_dir" \
  -DCMAKE_FIND_ROOT_PATH="$hvigor_dir" \
  -DPACKAGE_FIND_FILE="$hvigor_dir/summary.cmake" \
  --no-warn-unused-cli 2>&1 | tee "$evidence_dir/configure.log"

"$ninja_bin" -C "$build_dir" -v kuikly 2>&1 | tee "$evidence_dir/link.log"

test -f "$out_dir/libkuikly.so"
grep -F 'KRScrollerView.cpp' "$evidence_dir/link.log"
grep -F 'libkuikly.so' "$evidence_dir/link.log"
sha256sum "$out_dir/libkuikly.so" | tee "$evidence_dir/libkuikly.so.sha256"
"$readelf_bin" -h "$out_dir/libkuikly.so" | tee "$evidence_dir/readelf.txt"
grep -F 'Class:                             ELF64' "$evidence_dir/readelf.txt"
grep -E 'Machine:.*AArch64' "$evidence_dir/readelf.txt"
cp "$build_dir/compile_commands.json" "$evidence_dir/"
cp "$out_dir/libkuikly.so" "$evidence_dir/"

{
  echo "head=$(git -C "$repo_root" rev-parse HEAD)"
  echo "tree=$(git -C "$repo_root" rev-parse HEAD^{tree})"
  echo "parent=$(git -C "$repo_root" rev-parse HEAD^)"
  "$cmake_bin" --version
  "$ninja_bin" --version
  "$clang_bin" --version
} | tee "$evidence_dir/manifest.txt"
