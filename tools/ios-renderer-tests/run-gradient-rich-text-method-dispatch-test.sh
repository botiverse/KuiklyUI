#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
build_dir="${1:-$repo_root/build/ios-gradient-rich-text-method-dispatch-test}"
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
  -I "$repo_root/core-render-ios/include" \
  -I "$repo_root/core-render-ios/MacSupport" \
  "$repo_root/core-render-ios/MacSupport/KRUIKit.m" \
  "$repo_root/core-render-ios/Extension/AdvancedComps/KRGradientRichTextView.m" \
  "$repo_root/tools/ios-renderer-tests/KRGradientRichTextViewMethodDispatchTest.m" \
  -o "$build_dir/KRGradientRichTextViewMethodDispatchTest"

"$build_dir/KRGradientRichTextViewMethodDispatchTest"
