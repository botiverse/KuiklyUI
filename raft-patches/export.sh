#!/usr/bin/env bash
# Re-export the series from a patch-stack branch (one commit per patch, on top of UPSTREAM).
# Changes under raft-patches/ itself are excluded, so export commits never become patches.
# Usage: raft-patches/export.sh <stack-ref>
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
ref="${1:?stack ref}"
upstream_sha="$(sed -n 's/^commit=//p' "$here/UPSTREAM")"
tmp="$(mktemp -d)"
git format-patch --quiet --no-signature --zero-commit --full-index --no-numbered \
  -o "$tmp" "$upstream_sha..$ref" -- . ':(exclude)raft-patches'
rm -f "$here"/[0-9][0-9][0-9][0-9]-*.patch
: > "$here/series"
for f in "$tmp"/*.patch; do
  b="$(basename "$f")"; mv "$f" "$here/$b"; echo "$b" >> "$here/series"
done
rmdir "$tmp"
echo "exported $(wc -l < "$here/series") patches"
