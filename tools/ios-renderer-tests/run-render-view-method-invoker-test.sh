#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
build_dir="${1:-$repo_root/build/ios-render-view-method-invoker-test}"
mkdir -p "$build_dir"

xcrun clang \
  -fobjc-arc \
  -fblocks \
  -Wall \
  -Wextra \
  -Werror \
  -framework Foundation \
  -I "$repo_root/core-render-ios/Handler" \
  "$repo_root/core-render-ios/Handler/KRRenderViewMethodInvoker.m" \
  "$repo_root/tools/ios-renderer-tests/KRRenderViewMethodInvokerTest.m" \
  -o "$build_dir/KRRenderViewMethodInvokerTest"

"$build_dir/KRRenderViewMethodInvokerTest"
