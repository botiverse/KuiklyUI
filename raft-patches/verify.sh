#!/usr/bin/env bash
# Drift gate: applying the series on UPSTREAM must reproduce the current tree (excluding raft-patches/).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(git -C "$here" rev-parse --show-toplevel)"
upstream_sha="$(sed -n 's/^commit=//p' "$here/UPSTREAM")"
wt="$(mktemp -d)/wt"
git -C "$root" worktree add --detach -q "$wt" "$upstream_sha"
trap 'git -C "$root" worktree remove --force "$wt"' EXIT
cp -r "$here" "$wt/raft-patches.tmp"
( cd "$wt" && git -c user.name=verify -c user.email=verify@localhost \
    -c core.hooksPath=/dev/null am --3way -q $(sed '/^#/d;/^$/d;s#^#raft-patches.tmp/#' raft-patches.tmp/series) )
rm -rf "$wt/raft-patches.tmp"
want="$(git -C "$wt" rev-parse HEAD^{tree})"
have="$(git -C "$root" ls-tree HEAD | grep -v $'\traft-patches$' | git -C "$root" mktree)"
if [[ "$want" != "$have" ]]; then
  echo "DRIFT: tree(series on upstream)=$want != tree(HEAD minus raft-patches)=$have" >&2
  git -C "$root" diff --stat "$want" "$have" >&2 || true
  exit 1
fi
echo "OK: series reproduces HEAD tree $have"
