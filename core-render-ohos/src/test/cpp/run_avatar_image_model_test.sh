#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$script_dir/../../../.." && pwd)"
build_dir="$(mktemp -d)"
trap 'rm -rf "$build_dir"' EXIT

"${CC:-cc}" -std=c11 -Wall -Wextra -Werror \
  -I"$repo_root/core-render-ohos/src/main/cpp" \
  -c "$repo_root/core-render-ohos/src/main/cpp/thirdparty/cJSON/cJSON.c" \
  -o "$build_dir/cJSON.o"

"${CXX:-c++}" -std=c++17 -Wall -Wextra -Werror \
  -I"$repo_root/core-render-ohos/src/main/cpp" \
  "$script_dir/avatar_image_model_test.cpp" \
  "$build_dir/cJSON.o" \
  -o "$build_dir/avatar_image_model_test"

"$build_dir/avatar_image_model_test"
