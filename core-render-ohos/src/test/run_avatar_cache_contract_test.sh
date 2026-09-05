#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
log_file="$(mktemp "${TMPDIR:-/tmp}/kuikly-avatar-cache-test.XXXXXX")"
self_test_file="$(mktemp "${TMPDIR:-/tmp}/kuikly-avatar-cache-self-test.XXXXXX")"
trap 'rm -f "$log_file" "$self_test_file"' EXIT

has_hypium_failure() {
  grep -Fq 'ERROR: Error in ' "$1"
}

# Prove the wrapper ignores ordinary text while detecting the exact Hypium assertion signature.
printf '%s\n' '> hvigor WARN: documentation mentions Error in a neutral sentence' >"$self_test_file"
if has_hypium_failure "$self_test_file"; then
  echo 'avatar-cache test wrapper self-test false-positive' >&2
  exit 1
fi
printf '%s\n' '> hvigor ERROR: Error in focused mutation, expect false, actualValue is true' >"$self_test_file"
if ! has_hypium_failure "$self_test_file"; then
  echo 'avatar-cache test wrapper self-test false-negative' >&2
  exit 1
fi

(
  cd "$repo_root/ohosApp"
  hvigorw \
    --mode module \
    -p module=render@default \
    -p product=default \
    test \
    --analyze=normal \
    --parallel
) 2>&1 | tee "$log_file"

# Hvigor 6.24 currently returns zero when Hypium assertions fail. Make the focused test a real gate.
if has_hypium_failure "$log_file"; then
  echo 'OHOS avatar-cache focused test failed' >&2
  exit 1
fi

echo 'OHOS avatar-cache focused test PASS'
