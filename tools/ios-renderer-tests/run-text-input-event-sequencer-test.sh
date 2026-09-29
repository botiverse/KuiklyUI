#!/usr/bin/env bash
set -euo pipefail

tools_root="$(cd "$(dirname "$0")/../.." && pwd)"
# Kuikly source materialized by script/sync (override with KUIKLY_SRC).
src_root="$(cd "${KUIKLY_SRC:-$tools_root/src}" && pwd)"
build_dir="${1:-$tools_root/build/ios-text-input-sequencer-test}"
mkdir -p "$build_dir"

xcrun clang \
  -fobjc-arc \
  -fblocks \
  -framework Foundation \
  -I "$src_root/core-render-ios/Extension/Components" \
  "$src_root/core-render-ios/Extension/Components/KRTextInputEventSequencer.m" \
  "$tools_root/tools/ios-renderer-tests/KRTextInputEventSequencerTest.m" \
  -o "$build_dir/KRTextInputEventSequencerTest"

"$build_dir/KRTextInputEventSequencerTest"
