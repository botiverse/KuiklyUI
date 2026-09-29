#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
build_dir="${1:-$repo_root/build/ios-layout-size-formatter-test}"
mkdir -p "$build_dir"

xcrun clang \
  -std=c11 \
  -Wall \
  -Wextra \
  -Werror \
  -I "$repo_root/core-render-ios/Extension/Category" \
  "$repo_root/tools/ios-renderer-tests/KRLayoutSizeFormatterTest.c" \
  -o "$build_dir/KRLayoutSizeFormatterTest"

"$build_dir/KRLayoutSizeFormatterTest"
