#!/usr/bin/env bash
# Apply the Raft patch series onto a pristine upstream checkout.
# Usage: raft-patches/apply.sh [--check] [<worktree>]
#   <worktree> defaults to the repo root; it must be at the tag named in raft-patches/UPSTREAM.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
check=0
if [[ "${1:-}" == "--check" ]]; then check=1; shift; fi
wt="${1:-$(git -C "$here" rev-parse --show-toplevel)}"
upstream_tag="$(sed -n 's/^tag=//p' "$here/UPSTREAM")"
upstream_sha="$(sed -n 's/^commit=//p' "$here/UPSTREAM")"
head_sha="$(git -C "$wt" rev-parse HEAD)"
if [[ "$head_sha" != "$upstream_sha" ]]; then
  echo "error: $wt HEAD=$head_sha, expected upstream $upstream_tag=$upstream_sha" >&2
  exit 2
fi
n=0
while read -r p; do
  [[ -z "$p" || "$p" == \#* ]] && continue
  n=$((n+1))
  if (( check )); then
    git -C "$wt" apply --check --3way "$here/$p" || { echo "FAIL: $p" >&2; exit 1; }
    git -C "$wt" apply --index --3way "$here/$p" >/dev/null
  else
    git -C "$wt" am --3way --keep-cr "$here/$p" || {
      echo "conflict in $p — resolve, 'git am --continue', then raft-patches/export.sh" >&2; exit 1; }
  fi
done < "$here/series"
echo "applied $n patches on $upstream_tag"
