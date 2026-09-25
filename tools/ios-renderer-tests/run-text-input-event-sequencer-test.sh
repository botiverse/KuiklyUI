#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
build_dir="${1:-$repo_root/build/ios-text-input-sequencer-test}"
mkdir -p "$build_dir"

xcrun clang \
  -fobjc-arc \
  -fblocks \
  -framework Foundation \
  -I "$repo_root/core-render-ios/Extension/Components" \
  "$repo_root/core-render-ios/Extension/Components/KRTextInputEventSequencer.m" \
  "$repo_root/tools/ios-renderer-tests/KRTextInputEventSequencerTest.m" \
  -o "$build_dir/KRTextInputEventSequencerTest"

"$build_dir/KRTextInputEventSequencerTest"
