#!/usr/bin/env bash
set -euo pipefail

tools_root="$(cd "$(dirname "$0")/../.." && pwd)"
# Kuikly source materialized by script/sync (override with KUIKLY_SRC).
src_root="$(cd "${KUIKLY_SRC:-$tools_root/src}" && pwd)"
build_dir="${1:-$tools_root/build/ios-layout-size-formatter-test}"
mkdir -p "$build_dir"

xcrun clang \
  -std=c11 \
  -Wall \
  -Wextra \
  -Werror \
  -I "$src_root/core-render-ios/Extension/Category" \
  "$tools_root/tools/ios-renderer-tests/KRLayoutSizeFormatterTest.c" \
  -o "$build_dir/KRLayoutSizeFormatterTest"

"$build_dir/KRLayoutSizeFormatterTest"
