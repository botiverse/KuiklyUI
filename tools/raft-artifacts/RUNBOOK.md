# Raft Artifacts mirror publication runbook

How to mirror an existing upstream Maven coordinate into Raft Artifacts with its
original GAV and exact authority bytes.

Written after task #141. Tasks #120, #121, #127 and #141 each grew their own
near-duplicate workflow; the point of this document is that the next one should
only need its coordinates filled in, and should not have to rediscover the traps
below.

## The one rule that shapes everything

**Raft Artifacts is create-only. A published path cannot be yanked.**

So when in doubt, publish *less*: an absent file can always be added later, a
wrong one cannot be removed. When you decide to exclude something, say so in the
manifest rather than leaving it implied — see "Disclose the superset" below.

## Steps

### 1. Freeze the file set from the authority

Authority origin is `https://mirrors.tencent.com/repository/maven-tencent`.

**It serves no directory listing.** A directory URL answers **HTTP 200 with a
"not directly browseable" notice — not 404**, and the Nexus REST browse endpoint
404s. Any check that treats "no listing" as a 404 is reading the wrong signal.

So the file set is derived, not listed:

1. Fetch the coordinate's `.module` (Gradle module metadata) and `.pom`.
2. Read the declared files and `available-at` redirects out of them.
3. Probe every plausible classifier/extension, then every checksum sidecar.

**Probe widely and record the grid you used.** Task #141's first inventory used a
narrow grid and silently missed `-kotlin-tooling-metadata.json` plus its four
sidecars — 5 files, found only because a reviewer probed differently. A
228-probe grid then confirmed the true set. Record the grid in the manifest
header (`probe_grid=` in `task141-coroutines-test-manifest.tsv`) so the next
coordinate runs a known grid instead of improvising one.

**Before reporting "no matches", prove the filter can see anything.** Pair every
zero-hit claim with a known-present positive control:

- task #141 reported "no `.spdx.json`" only next to a `.pom` returning 200 from
  the same directory;
- checking whether upstream `1.7.3` had an OHOS variant, a first pass matched
  `hos` case-insensitively and counted 18 `watchos*` variants as hits, nearly
  producing the opposite conclusion; counting `watchos*` deliberately proved the
  filter saw real variant names before trusting the OHOS count of zero.

A zero without a control cannot distinguish "absent" from "the filter was
looking at the wrong thing".

Shapes differ between coordinates — do not assume the previous task's shape:

- task #121/#127 coordinates publish `.spdx.json`; task #141's do not.
- task #141's OHOS module has a `-javadoc.jar`; its root does not.

### 2. Classify against Raft

For each frozen path, GET it on Raft and classify `absent` / `exact` / `conflict`
by comparing size and SHA-256.

**Do not treat 404 as the only "absent" signal.** Raft answers some paths with
**401**, e.g. `org/jetbrains/annotations/13.0/annotations-13.0.pom`. Confirm what
a non-200 actually means before calling a path absent. (Task #141's own 45 paths
all returned clean 404s, which is why its ALL_ABSENT reading stood.)

**Raft serves no `maven-metadata.xml`** — it 404s with a JSON error body. Raft
therefore supports exact-version resolution only: no version ranges, no
`latest`, no SNAPSHOT.

### 3. Write the manifest and bind it in the contract

Manifest lives in `publish/predecessors/`, tab-separated, sorted by path, with
`groupId artifactId version path size sha256 authority` columns and a `#`-comment
header.

Add a matching entry to `MANIFEST_CONTRACTS` in `mirror_maven_manifest.py`
binding at minimum `files`, `gavs`, `total_bytes` and `set_sha256`. The loader
fails closed if the manifest drifts from its frozen closure.

#### Disclose the superset

If the authority serves files you deliberately do not mirror, bind that as data,
not prose. Task #141 records `authority_files=50` against 45 mirrored rows, names
the excluded files in `excluded=`, and states the precedent that justified the
exclusion. `authority_files` is contract-bound, so deleting or editing the
disclosure fails the suite.

### 4. Hosted validation

A per-task workflow runs, on `pull_request`:

- the contract test suite plus `--mode plan` against live Raft;
- an empty-cache resolve of the frozen bytes from the authority.

The `publish` job must be gated on `github.event_name == 'workflow_dispatch' &&
inputs.publish`, live in `environment: raft-artifacts-production`, and be the
only place the token is referenced.

**Carriers must be exactly one commit** on the merge base, author == committer,
with a matching `Signed-off-by`. `identity-diff` fails a two-commit branch even
when everything else is green — squash before pushing.

### 5. Independent review

Someone other than the author reviews the exact head. Do not self-review.

### 6. Publish

Dispatch the workflow with `publish=true` and `expected_sha` set to the
**landed** SHA.

**`expected_sha` must equal the live `staging3` tip.** Squash-merging changes the
SHA, so the reviewed PR head is *not* on `staging3` and using it fails closed at
the source-binding step. Read the tip fresh at dispatch time.

The writer plans, PUTs only missing paths, and re-reads. A clean run prints
`classification=ALL_ABSENT …` before and `classification=ALL_COMPLETE_EXACT …`
after.

### 7. Verify from outside

The tool reporting success is not the receipt. Re-fetch every path
**anonymously, with no credentials**, and compare bytes against the frozen
manifest. Report the count and total bytes.

## Consumer caveats

Mirroring a multiplatform root and only one platform module means the root
resolves **for that platform only**. Resolving it elsewhere selects a different
`available-at` platform module that was never mirrored, and 404s. That is a
property of the consuming host, not of the bytes; state it as a known limitation
rather than letting someone discover it later.

Watch for a coordinate whose version differs between a project's platform legs.
In Mobile, the OHOS leg builds from `compose/shared/build.ohos.gradle.kts` and
pins `kotlinx-coroutines-core:1.8.0-KBA-002`, while the non-OHOS
`build.gradle.kts` pins `1.7.3`. Copying the version across legs requests a
coordinate that was never mirrored.

**The consumer contract belongs to the consuming repository, not this one.**
A check that reads `build.ohos.gradle.kts` can only run in Mobile's CI; nothing
here can enforce it, so this repo deliberately ships no such check rather than
implying coverage it does not have.

Measured, so nobody has to guess how the mistake would surface:

```
kotlinx-coroutines-test:1.7.3            tencent 404  raft 404  central 200
kotlinx-coroutines-test-ohosarm64:1.7.3  tencent 404  raft 404  central 404
```

Copying `1.7.3` therefore resolves the *root* from Maven Central but finds **no
OHOS platform module at that version anywhere** — upstream never published one,
since the OHOS target is a fork addition. The build fails at variant resolution.
It is a loud failure, not a silent fallback that quietly reintroduces an
upstream dependency.

Definition of done for the first change that introduces `runTest` on OHOS:
coordinate `1.8.0-KBA-002`, a version check wired into Mobile's own CI, a real
`compileTestKotlinOhosArm64` pass, and readable artifact provenance.

## Verifying a consumer before publishing

`stage_manifest.py stage` materialises a frozen manifest into a local
Maven-layout directory, verifying every byte. Pointed at a real consumer build
alongside Raft and with no authority repository, it simulates the
post-publication world without writing anything.

If you build such a gate, note what makes it real evidence:

- bind proof to the compile task's **actual inputs**, not to a coordinate merely
  appearing in some resolvable configuration's graph;
- force the failure you claim to prevent — a conditional "if X is requested it
  will fail" never executes when X is not expected, so it proves nothing;
- keep the cache cold (throwaway `GRADLE_USER_HOME`, `--refresh-dependencies`,
  no cache restore) and treat missing download URIs as *gate invalid*, since a
  warm cache proves nothing about provenance;
- a red that lands on a 401, a script error or a compile error is **gate
  invalid**, not a pass and not "could not reproduce".
