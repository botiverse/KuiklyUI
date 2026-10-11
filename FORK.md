# Raft Kuikly patches

This branch is upstream [Tencent-TDS/KuiklyUI](https://github.com/Tencent-TDS/KuiklyUI)
at the commit pinned in `patches/config.json` (shared history, so GitHub can
compare it with upstream), **plus the fork's patches and tooling on top**:
`patches/kuikly/*.patch` applied in the order listed in `patches/kuikly/.patches`,
`script/`, the fork's `.github/workflows`, `KUIKLY_RELEASE_SET`, `Gemfile`.
The patch files are the source of truth; the Kuikly source checked in on this
branch is the **unmodified upstream pin** and is never edited directly. The
layout follows Electron's `patches/` for Chromium and Node, with the pinned
upstream tree living in the same repository instead of being fetched.

## Working on a patch

```bash
script/sync                      # check out the pin into src/ (from this repo, no network) and git-am every patch
cd src                           # a normal git repo: branch `patched` = pin + one commit per patch
# edit, then either amend the commit that owns the change
# (git commit --fixup <sha> && git rebase -i --autosquash upstream-pin)
# or add a new commit for a new patch
cd .. && script/export-patches   # rewrite patches/kuikly/*.patch and .patches from src/
script/check-patches             # what CI runs: clean apply + canonical export form
```

Every patch commit needs an English conventional subject, a short body, and
`Fork-Patch:` / `Origin:` / `Upstream-Status:` trailers plus `Signed-off-by`.

## Moving to a new upstream release

1. Merge the upstream tag into this branch so the checked-in tree and the
   history advance together: `git fetch upstream refs/tags/<ver>` then
   `git merge --no-ff <ver>` (our files live outside the upstream tree except
   `.gitignore`; resolve it by keeping both halves).
2. Update `ref` and `commit` in `patches/config.json` to the new tag.
3. Run `script/sync`, resolve any patch that no longer applies (`git am --3way`
   stops on it; fix, `git am --continue`), then `script/export-patches`. Drop
   patches that upstream has absorbed.

Never edit the upstream source files on this branch directly; every change to
Kuikly goes through a patch.
