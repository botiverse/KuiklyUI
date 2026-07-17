#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
CXX_BIN="${CXX:-clang++}"
OUT_DIR="${TMPDIR:-/tmp}/kuikly-ohos-scroll-replacement-policy"

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

"$CXX_BIN" \
  -std=c++17 \
  -Wall \
  -Wextra \
  -Werror \
  -I"$ROOT_DIR/core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller" \
  "$ROOT_DIR/tools/ohos-renderer-tests/KRScrollReplacementPolicyTest.cpp" \
  -o "$OUT_DIR/KRScrollReplacementPolicyTest"

"$OUT_DIR/KRScrollReplacementPolicyTest"
