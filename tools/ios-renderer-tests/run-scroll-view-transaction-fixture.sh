#!/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
OUTPUT_DIR="${TMPDIR:-/tmp}/kuikly-ios-renderer-tests"
OUTPUT="$OUTPUT_DIR/scroll-view-transaction-fixture"

mkdir -p "$OUTPUT_DIR"

INCLUDE_ARGS=""
while IFS= read -r directory; do
  INCLUDE_ARGS="$INCLUDE_ARGS -I$directory"
done <<EOF
$(find "$ROOT_DIR/core-render-ios" -maxdepth 6 -type d)
EOF

# shellcheck disable=SC2086
xcrun clang \
  -fobjc-arc \
  -fmodules \
  -Werror \
  -Wno-incomplete-implementation \
  -Wno-protocol \
  -Wno-unused-parameter \
  -DTARGET_OS_OSX=1 \
  -framework AppKit \
  -framework Foundation \
  -framework QuartzCore \
  $INCLUDE_ARGS \
  "$ROOT_DIR/core-render-ios/MacSupport/KRUIKit.m" \
  "$ROOT_DIR/core-render-ios/Extension/Vendor/KRDisplayLink.m" \
  "$ROOT_DIR/core-render-ios/Extension/Components/Base/KRScrollViewOffsetAnimator.m" \
  "$ROOT_DIR/core-render-ios/Extension/Components/Base/KRWeakObject.m" \
  "$ROOT_DIR/core-render-ios/Extension/Components/Base/KRMultiDelegateProxy.m" \
  "$ROOT_DIR/core-render-ios/Extension/Components/KRScrollView.m" \
  "$ROOT_DIR/tools/ios-renderer-tests/KRScrollViewTransactionFixture.m" \
  -o "$OUTPUT"

"$OUTPUT"
