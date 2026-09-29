#!/usr/bin/env bash
set -euo pipefail

tools_root="$(cd "$(dirname "$0")/../.." && pwd)"
# Kuikly source materialized by script/sync (override with KUIKLY_SRC).
src_root="$(cd "${KUIKLY_SRC:-$tools_root/src}" && pwd)"
build_dir="${1:-$tools_root/build/ios-textarea-blur-raw-text-test}"
mkdir -p "$build_dir"

xcrun clang \
  -fobjc-arc \
  -fblocks \
  -fmodules \
  -Werror \
  -Wno-incomplete-implementation \
  -Wno-protocol \
  -Wno-unused-parameter \
  -DTARGET_OS_OSX=1 \
  -framework AppKit \
  -framework Foundation \
  -I "$src_root/core-render-ios/include" \
  -I "$src_root/core-render-ios/MacSupport" \
  -I "$src_root/core-render-ios/Extension/Components" \
  -I "$src_root/core-render-ios/Extension/BridgeProtocol" \
  "$src_root/core-render-ios/Extension/Components/KRTextBlurEventPayload.m" \
  "$tools_root/tools/ios-renderer-tests/KRTextBlurPayloadRawTextTest.m" \
  -o "$build_dir/KRTextBlurPayloadRawTextTest"

"$build_dir/KRTextBlurPayloadRawTextTest"
