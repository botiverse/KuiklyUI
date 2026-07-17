#!/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
OUTPUT_DIR="${TMPDIR:-/tmp}/kuikly-ohos-renderer-tests"
OUTPUT="$OUTPUT_DIR/pending-callback-slot-policy-test"

mkdir -p "$OUTPUT_DIR"

clang++ \
  -std=c++17 \
  -Wall \
  -Wextra \
  -Werror \
  -I "$ROOT_DIR/core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller" \
  "$ROOT_DIR/tools/ohos-renderer-tests/KRPendingCallbackSlotPolicyTest.cpp" \
  -o "$OUTPUT"

"$OUTPUT"
