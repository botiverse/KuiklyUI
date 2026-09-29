#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
build_dir="${1:-$repo_root/build/ios-textarea-blur-raw-text-test}"
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
  -I "$repo_root/core-render-ios/include" \
  -I "$repo_root/core-render-ios/MacSupport" \
  -I "$repo_root/core-render-ios/Extension/Components" \
  -I "$repo_root/core-render-ios/Extension/BridgeProtocol" \
  "$repo_root/core-render-ios/Extension/Components/KRTextBlurEventPayload.m" \
  "$repo_root/tools/ios-renderer-tests/KRTextBlurPayloadRawTextTest.m" \
  -o "$build_dir/KRTextBlurPayloadRawTextTest"

"$build_dir/KRTextBlurPayloadRawTextTest"
