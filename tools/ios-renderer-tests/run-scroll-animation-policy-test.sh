#!/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
OUTPUT_DIR="${TMPDIR:-/tmp}/kuikly-ios-renderer-tests"
OUTPUT="$OUTPUT_DIR/scroll-animation-policy-test"

mkdir -p "$OUTPUT_DIR"

xcrun clang \
  -fobjc-arc \
  -fmodules \
  -DTARGET_OS_OSX=1 \
  -framework AppKit \
  -framework Foundation \
  -framework QuartzCore \
  -I "$ROOT_DIR/core-render-ios/MacSupport" \
  -I "$ROOT_DIR/core-render-ios/Extension/Vendor" \
  -I "$ROOT_DIR/core-render-ios/Extension/Components/Base" \
  "$ROOT_DIR/core-render-ios/MacSupport/KRUIKit.m" \
  "$ROOT_DIR/core-render-ios/Extension/Vendor/KRDisplayLink.m" \
  "$ROOT_DIR/core-render-ios/Extension/Components/Base/KRScrollViewOffsetAnimator.m" \
  "$ROOT_DIR/tools/ios-renderer-tests/KRScrollViewOffsetAnimatorPolicyTest.m" \
  -o "$OUTPUT"

"$OUTPUT"
