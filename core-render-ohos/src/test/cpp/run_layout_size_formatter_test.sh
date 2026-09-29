#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$script_dir/../../../.." && pwd)"
build_dir="$(mktemp -d)"
trap 'rm -rf "$build_dir"' EXIT

"${CXX:-c++}" \
  -std=c++17 \
  -Wall \
  -Wextra \
  -Werror \
  -I"$repo_root/core-render-ohos/src/main/cpp" \
  "$script_dir/layout_size_formatter_test.cpp" \
  -o "$build_dir/layout_size_formatter_test"

"$build_dir/layout_size_formatter_test"
