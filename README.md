# Raft Kuikly patches

This branch holds **only patches**, no Kuikly source. The source is upstream
[Tencent-TDS/KuiklyUI](https://github.com/Tencent-TDS/KuiklyUI) at the commit
pinned in `patches/config.json`, plus the patches in `patches/kuikly/`, applied
in the order listed in `patches/kuikly/.patches`. The layout follows Electron's
`patches/` for Chromium and Node.

## Working on a patch

```bash
script/sync                      # fetch upstream at the pin into src/ and git-am every patch
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

Update `ref` and `commit` in `patches/config.json`, run `script/sync`, resolve
any patch that no longer applies (`git am --3way` stops on it; fix, `git am
--continue`), then `script/export-patches`. Drop patches that upstream has
absorbed.
