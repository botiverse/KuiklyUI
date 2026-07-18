#!/usr/bin/env bash
# Policy fixture runner. Exit codes (task #17 harness contract):
#   0    = behavior PASS (mutation would SURVIVE)
#   1    = behavior FAILED (mutation KILLED)
#   125  = setup/compile failure (mutation NOT APPLICABLE — never counts as kill)
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
CXX_BIN="${CXX:-clang++}"
OUT_DIR="${TMPDIR:-/tmp}/kuikly-ohos-scroll-replacement-policy"

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

if ! "$CXX_BIN" \
  -std=c++17 \
  -Wall \
  -Wextra \
  -Werror \
  -I"$ROOT_DIR/core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller" \
  "$ROOT_DIR/tools/ohos-renderer-tests/KRScrollReplacementPolicyTest.cpp" \
  -o "$OUT_DIR/KRScrollReplacementPolicyTest" 2> "$OUT_DIR/compile.log"; then
  echo "policy fixture compile failed (setup failure, not a behavior kill):" >&2
  cat "$OUT_DIR/compile.log" >&2
  exit 125
fi

"$OUT_DIR/KRScrollReplacementPolicyTest"
