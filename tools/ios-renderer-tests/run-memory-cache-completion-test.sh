#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
build_dir="${1:-$repo_root/build/ios-memory-cache-completion-test}"
mkdir -p "$build_dir"

xcrun clang \
  -O2 \
  -fobjc-arc \
  -fblocks \
  -fmodules \
  -Werror \
  -Wno-incomplete-implementation \
  -Wno-protocol \
  -Wno-unused-parameter \
  -DTARGET_OS_OSX=1 \
  -framework AppKit \
  -framework Foundation \
  -framework QuartzCore \
  -I "$repo_root/core-render-ios/include" \
  -I "$repo_root/core-render-ios/MacSupport" \
  "$repo_root/core-render-ios/MacSupport/KRUIKit.m" \
  "$repo_root/core-render-ios/Extension/Modules/KRMemoryCacheModule.m" \
  "$repo_root/tools/ios-renderer-tests/KRMemoryCacheCompletionTest.m" \
  -o "$build_dir/KRMemoryCacheCompletionTest"

MallocScribble=1 "$build_dir/KRMemoryCacheCompletionTest"
