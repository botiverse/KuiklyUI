# Shared helpers for the patch scripts. Sourced, not executed.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cfg() { python3 -c 'import json,sys; r=json.load(open(sys.argv[1]))["repos"][0]; print(r[sys.argv[2]])' "$ROOT/patches/config.json" "$1"; }
PATCH_DIR="$ROOT/$(cfg patch_dir)"
UPSTREAM_URL="$(cfg upstream)"
UPSTREAM_REF="$(cfg ref)"
UPSTREAM_COMMIT="$(cfg commit)"
# Fixed committer identity for the commits created by `git am` (dates come from
# the patch authors via --committer-date-is-author-date), so SHAs are reproducible.
export GIT_COMMITTER_NAME="Raft Patch Queue"
export GIT_COMMITTER_EMAIL="patches@raft.invalid"
