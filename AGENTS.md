# AGENTS.md — Raft Kuikly patches (staging4)

This repository branch (`staging4` of botiverse/KuiklyUI) holds **only patches and
tooling**, no Kuikly source. The product source is upstream Tencent-TDS/KuiklyUI at
the commit pinned in `patches/config.json`, plus `patches/kuikly/*.patch` applied in
the order of `patches/kuikly/.patches` (Electron-style, like `electron/patches`).
Consumer: botiverse/mobile, via Raft Artifacts (`com.tencent.kuikly-open:*` at
`<KUIKLY_RELEASE_SET>-2.1.21`, OHOS `<KUIKLY_RELEASE_SET>-2.0.21-ohos`).
All human and agent contributors follow these rules.

## Layout

| path | what |
|---|---|
| `patches/config.json` | upstream repo, tag ref and exact commit (the pin) |
| `patches/kuikly/.patches`, `*.patch` | the queue, one patch per logical fork change, in apply order |
| `script/sync`, `script/export-patches`, `script/check-patches`, `script/lint-patches` | materialize `src/`, export back, CI drift gate, trailer lint |
| `src/` (git-ignored) | materialized source: branch `patched` = `upstream-pin` + one commit per patch |
| `script/release/` | release contract, publisher, producer and POM-consumer scripts (+ tests) |
| `KUIKLY_RELEASE_SET`, `Gemfile*`, `.github/raft-artifacts/` | release set, CocoaPods pin, release metadata |
| `.github/workflows/`, `.github/actions/sync/` | CI; every job runs `script/sync` then builds inside `src/` |

Tooling that only *operates on* the source lives here. Anything the Gradle,
CocoaPods or hvigor build itself reads from the source tree is a **patch** (for
example the OHOS HAR version in `core-render-ohos/oh-package.json5`, the OHOS
settings and publication hooks, `iosApp/Podfile.lock`).

## Working on patches

```bash
script/sync                        # src/ = pin + queue (fails if src/ has unexported edits; --force discards them)
cd src
# new patch: commit on top of `patched`
# change a patch: git commit --fixup=<patch commit>; GIT_SEQUENCE_EDITOR=: git rebase -i --autosquash upstream-pin
cd .. && script/export-patches     # rewrites patches/kuikly/*.patch and .patches from src/
script/lint-patches && script/check-patches
```

- **One logical change = one patch.** Commit only the final form. Never a fix+revert pair, never a "fix of fix" patch. Fold follow-ups into the owning patch.
- **Tests travel with their fix.** Module unit tests (`src/*Test*/`), source-contract checkers (`tools/check-*.py`), iOS/OHOS fixture sources and runners (`tools/*-renderer-tests/`) all go in the patch whose fix they verify, at their source-tree path. A helper shared by several fixes goes in the earliest owning patch. This repository keeps tests only for its own tooling (`script/release/test_kuikly_release_contract.py`).
- Never hand-edit a `.patch` file. `script/check-patches` fails if the queue is not in canonical export form.
- **Upstream backports:** in `src/`, run `git cherry-pick -x <upstream sha>`, keep upstream authorship, add the fork trailers, then export. When a backport replaces a fork patch, drop the fork patch.
- **Dropping a patch:** drop its commit in `src/`, then export. Its tests go with it; remove the matching workflow step.
- **Moving to a new upstream tag:**
  1. Update `ref` and `commit` in `patches/config.json`.
  2. Run `script/sync`. `git am --3way` stops on a conflict: resolve it keeping upstream behaviour plus our intent, then `git am --continue`.
  3. Export.
  4. Drop patches that upstream has absorbed. Judge that by file content and symbols, not patch-id.
  5. Release numbering restarts at `<tag>-raft.1`.

## Patch commit messages

- Subject: English conventional form `<type>(<area>): <summary>`. No Chinese, no internal task numbers. The subject becomes the patch file name, so keep it stable.
- Body: 2-6 lines on what and why.
- Trailers:
  - `Fork-Patch: <slug>`
  - `Origin: <fork shas>` (if any)
  - `Upstream-Status: pending | submitted <PR> | not-upstreamable`
  - `Signed-off-by` (use `git commit -s`)

`script/lint-patches` enforces `Signed-off-by`, `Fork-Patch` and the subject form on every queued patch.

## Pull requests to staging4

- PR titles are **English only**, in the same conventional form.
- Every commit of this repository must be DCO signed off by its author (`git commit -s`).
- The `Compose PR Exact Matrix` gate runs:
  - `dco`
  - `patch-queue` (lint + `script/check-patches` + materialized identity)
  - `release-contract` (contract tests on the materialized source)
  - `common-core-android` (JVM + Web suites)
  - `ios-renderer` (fixtures + warnings-as-errors renderer build)
  - `ohos-native` (arm64 link)
  - `source-contracts` (`src/tools/check-*.py` + OHOS host fixtures, all shipped by patches)
- **No local Gradle** (org rule). Locally, only run static checks:
  - `script/sync`, `script/lint-patches`, `script/check-patches`;
  - `python3 script/release/test_kuikly_release_contract.py` and `cd src && python3 tools/check-*.py --self-test`;
  - `bash -n`, YAML parse, and `git grep` that the symbols you reference exist.

  Compilation, unit tests and device checks belong to CI and real devices.

## Releases

- **Version.** `KUIKLY_RELEASE_SET` is the single source of the version. A bump also changes the OHOS HAR version patch (`build_ohos_set_the_har_release_version.patch`) to `<release>-2.0.21-ohos`, in the same commit.
- **Source identity** = (this repo's commit, upstream pin, materialized `src/` tree). `script/sync` is deterministic (fixed committer, author dates), so the tree is a pure function of the patches commit.
- **Publishing** goes through `task93-kuikly-release.yml` `workflow_dispatch`:
  1. Create an annotated tag `<release>` on the patches commit.
  2. Dispatch a `publish=false` candidate run.
  3. Dispatch the protected `publish=true` run, passing the candidate run id, the materialized tree and the set/receipt digests.
- **Landed** means the patches commit is an ancestor of live `origin/staging4`.
- **Hotfixes** go on `release/*` branches of this repository, based on a published raft tag. They may contain only cherry-picks of patches-repo commits already landed on staging4, or version-only bumps (`KUIKLY_RELEASE_SET` + the OHOS HAR version patch).

## Conflicts with upstream and fork features

- Before fixing an upstream bug, check Tencent-TDS/KuiklyUI for an in-flight or merged equivalent. If one exists, prefer backporting it.
- When a fork feature (native dispatch capture, text-input state arbitration, inline-box, …) and an upstream change touch the same area, **never resolve by blind overwrite**. The feature owner must review the merge, and it needs a real-device regression pass.
- High-churn areas need a locked test with every change:
  - Android line-height centering (`HRLineHeightSpan*Test`);
  - Compose lazy scroll echo/offset (`KuiklyScrollInfo`, `SubcomposeLayout`);
  - layout-size report rounding (the four `LayoutSizeFormatter`s and `tools/check-layout-size-report-rounding.py`).
