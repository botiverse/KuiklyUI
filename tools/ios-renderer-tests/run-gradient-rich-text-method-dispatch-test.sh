#!/usr/bin/env bash
set -euo pipefail

tools_root="$(cd "$(dirname "$0")/../.." && pwd)"
# Kuikly source materialized by script/sync (override with KUIKLY_SRC).
src_root="$(cd "${KUIKLY_SRC:-$tools_root/src}" && pwd)"
build_dir="${1:-$tools_root/build/ios-gradient-rich-text-method-dispatch-test}"
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
  -framework QuartzCore \
  -I "$src_root/core-render-ios/include" \
  -I "$src_root/core-render-ios/MacSupport" \
  "$src_root/core-render-ios/MacSupport/KRUIKit.m" \
  "$src_root/core-render-ios/Extension/AdvancedComps/KRGradientRichTextView.m" \
  "$tools_root/tools/ios-renderer-tests/KRGradientRichTextViewMethodDispatchTest.m" \
  -o "$build_dir/KRGradientRichTextViewMethodDispatchTest"

"$build_dir/KRGradientRichTextViewMethodDispatchTest"
