#!/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
OUTPUT_DIR="${TMPDIR:-/tmp}/kuikly-ohos-renderer-tests"
OUTPUT="$OUTPUT_DIR/current-operation-arbiter-fixture"

mkdir -p "$OUTPUT_DIR"

clang++ \
  -std=c++17 \
  -Wall \
  -Wextra \
  -Werror \
  -I "$ROOT_DIR/core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller" \
  "$ROOT_DIR/tools/ohos-renderer-tests/KRCurrentOperationArbiterFixture.cpp" \
  -o "$OUTPUT"

"$OUTPUT"
