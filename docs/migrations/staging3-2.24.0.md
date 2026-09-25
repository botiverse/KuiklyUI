# `staging3` migration to Kuikly 2.24.0

Status: migration candidate; not a production branch or published artifact source
Owner: `@BiSheng`
Canonical Raft task: `#Kuiklybase` task #73
Audit source: task #72, `notes/task72-upstream-2.24-intake-audit.md`

## Frozen coordinates

| Role | Exact coordinate |
|---|---|
| Common ancestor | `2.23.0@0eadaedfb1fd4ad61b16ef09367cf5e59aa3b1f5` |
| Upstream release base | `2.24.0@76a866795903014cfd2d0363ee31ccee7895775e` |
| Upstream main observed during migration | `tencent/main@dd8ca639ef1b770c1045f7cf2238458e88dc7a65` |
| Fork replay source | `origin/staging2@6a531a57105ee453edcfdc07a54d1bb1b4348431` |
| Hold commit | `c8f92f053fb60d6fe515b5e986e1c6fe2db775a0` |
| Audited replay merge | `25cbc1fa25be4bba0ca800a67762a1f1317c5f25` |
| Replay merge first parent | `c8f92f053fb60d6fe515b5e986e1c6fe2db775a0` |
| Replay merge second parent | `6a531a57105ee453edcfdc07a54d1bb1b4348431` |

The exact candidate head is the SHA in the `Staging3 Migration Exact Matrix` workflow payload. It is deliberately not hard-coded here, because post-merge repairs and gate definitions advance the first-parent head while the audited replay merge and both frozen sides remain immutable.

## Construction method

1. Create local `staging3` from the exact upstream `2.24.0` tag.
2. Explicitly revert upstream material that the divergence audit classified as unsafe to consume verbatim.
3. Merge the frozen current fork side with a real two-parent merge, preserving both histories.
4. Resolve conflicts by retaining fork invariants while accepting reviewed upstream intent.
5. Repair remaining guarded upstream behavior, then validate the exact first-parent head on Linux, macOS/iOS, Web, and OHOS without publishing artifacts.

This is not an `ours` merge and not a mechanical replay of 175 noisy fork-history commits. The merge tree contains the net retained fork state and advances ancestry to the official release line.

## Explicit upstream holds

Commit `c8f92f05…` reverts these upstream commits before the fork replay:

| Upstream commit | Topic | Reason held |
|---|---|---|
| `24871011` | #1422 LazyList prefetch core | Large runtime/lifecycle change colliding with fork profiler, slot reuse, accessibility, and idle scheduling. |
| `83bdfae8` | #1562 OHOS prefetch build follow-up | Has no independent value without the held prefetch core. |
| `388f9044` | #1568 prefetch publication cleanup | Has no independent value without the held prefetch core. |
| `fac3e1bf` | #1580 Compose 1.9 compile follow-up | Belongs to the held prefetch/runtime-1.9 bundle. |
| `b20353e1` | #1566 dedicated iOS context thread | Architecture/ABI/scheduler migration conflicts with the fork's preemptible idle lane and renderer thread behavior. |
| `69eebf51` | #1563 Pager snap/touch deferral | Useful intent, but stale touch lifetime and Drawer/no-op-snap gaps require a separately repaired design. |
| `f3c1101c` | #1582 PullToRefresh inset shortcut | The verbatim native early return can skip offset restoration and mishandle pending animation callbacks. |

These holds are not permanent rejections. Each requires its own reviewable intake with platform-specific tests and rollback.

## Replay conflict decisions

The explicit hold baseline reduced the merge to nine conflicts. All nine were resolved intentionally:

| Path | Resolution |
|---|---|
| `compose/build.2.0.ohos.gradle.kts` | Keep fork `kotlinx-coroutines-core:1.8.0-KBA-002`; do not inherit the held runtime-1.9 bundle. |
| `compose/.../scroller/ContentSizeExtensions.kt` | Keep fork imports and `DeferredScrollOffsetAlignmentCoordinator`; retain upstream #1567 stable Pager content size using `calculateNewMaxScrollOffset + mainAxisViewportSize`. |
| `core-render-ios/.../KRTextAreaView.m` | Keep stronger fork focus-intent arbitration through `shouldRequestComposeFocus`; do not call `becomeFirstResponder` directly. |
| `core-render-ios/.../KuiklyTurboDisplayRenderLayerHandler.m` | Keep the more precise upstream cache-lifecycle comment; remove whitespace-only conflict. |
| `core-render-ohos/.../KRRenderNativeContextHandlerManager.cpp` | Keep fork off-context marshal and synchronous-method fail-fast; preserve upstream null-singleton/value initialization safety and the required `KRRenderManager.h` include. |
| `core-render-ohos/.../KRScrollerView.cpp` | Keep fork `KRScrollerContentOffset.h` integration. |
| `core-render-ohos/.../KRView.cpp` | Keep fork native-dispatch capture together with upstream #1508 handled-first semantics. |
| `core-render-ohos/.../KRRenderValue.h` | Resolve whitespace only; no semantic choice. |
| `h5App/README.md` | Keep upstream browser-default/context-menu/resize documentation. |

## Retained fork capability groups

The replay preserves the fork's net behavior rather than reproducing every historical commit:

- Compose scheduling: preemptible idle work, current page-context routing, and fork coroutine dependency coordinates.
- Composition correctness: subcompose slot draw reactivation, node-event callback freshness, semantics registry behavior, and draw invalidation.
- Scrolling and paging: fork scroll echo/offset coordination, stable Pager content-size integration, initial native viewport handling, and OHOS leading-inset offset model.
- Input and focus: focus-target reduction, keyboard dismissal, text-input revision arbitration, iOS focus intent, and handled-first native dispatch capture.
- Text: selectable text, inline-box span lowering, inline-code chrome, rich-text line-height/underline behavior, and platform renderer support.
- Profiler and files: observer state partitioning, composition/session lifecycle, file-output module ownership, and platform logging.
- Android renderer: context-thread marshal rules, decoration reuse, SuperTouch/overscroll behavior, selectable text, and scheduler batching.
- iOS renderer: finite scroll-event bridge, text-input sequencer, renderer-thread bridge behavior, selectable text, and fork cache/focus invariants.
- OHOS renderer: off-context native-call marshal, null/value initialization safety, handled-first capture, text/selectable-text parity, file/cache ownership, and direct-link surface.
- Fork build and policy: Android/OHOS build coordinates, exact-head workflows, locked CocoaPods inputs, DCO, and the audited two-parent migration exception in `AGENTS.md`.

## Guarded upstream behavior

Upstream #1588 had valid anti-clipping intent but used `value + 0.005` before nearest formatting. That is not a mathematical ceiling: an exact integer such as `10.0f` can become `10.01`, and Android also changed the null wire value from `0.00|0.00` to `0|0`.

The staging3 repair therefore:

- performs a true ceiling at the hundredth boundary;
- absorbs only one input-representation unit of binary floating-point noise so exact hundredths stay exact;
- retains `0.00|0.00` null serialization;
- locks `0`, integer, exact-hundredth, fractional, negative defensive, and null cases on Android/Web plus native helper fixtures on iOS/OHOS;
- keeps Android's separate `LayoutParams.width <= 0` remeasurement change from #1588, covered by the renderer JVM matrix.

## Required gates

Every acceptance receipt must bind to one exact staging3 head and record the commit tree and replay parents.

### Source and host gates

- `git diff --check` and a clean exact checkout.
- First-parent DCO for every migration-authored commit.
- Replay ancestry check: `25cbc1fa…` has exactly the two frozen parents above and is an ancestor of the candidate.
- No `+0.005` size-report bias and no Android null serialization drift.
- Compose node-event freshness, Android physical-scroll drag entry, iOS finite scroll values, profiler observer partition, profiler file lifecycle, OHOS inset-offset, and four-platform size-format fixtures.

### Linux / Android / Web

- `:core:compileCommonMainKotlinMetadata`
- `:compose:compileCommonMainKotlinMetadata`
- `:core:testDebugUnitTest`
- `:compose:testDebugUnitTest`
- `:core-render-android:testDebugUnitTest`
- `:core-render-web:base:jsNodeTest`

The Compose and Android renderer suites must report non-zero test counts and zero failures/errors. Web must execute the actual Kotlin/JS formatter test under Node, not merely compile it.

### iOS

- Xcode 16.2 hosted macOS runner.
- Locked Bundler/CocoaPods install using `Gemfile.lock` and `iosApp/Podfile.lock`.
- `core` and `compose` Kotlin/Native simulator compilation plus Compose native test compilation.
- Native text-input sequencer and layout-size formatter fixtures.
- Production `OpenKuiklyIOSRender` simulator build with Objective-C/Swift warnings treated as errors.

This is a build/contract gate, not a claim of Mobile product runtime or physical-device acceptance.

### OHOS

- Pinned HarmonyOS CI container and SDK paths.
- `ohpm install`, hvigor sync, and debug HAR assembly.
- Fresh arm64 production `libkuikly` compile and direct link.
- Host-executable inset-offset and layout-size fixtures.

This is a source/native-link gate, not a signed HAP, install, or product runtime claim.

## Cutover rules

1. Do not publish staging3 artifacts and do not update Mobile while any exact-head gate is missing, skipped, cancelled, stale, or red.
2. Obtain an independent source/ancestry review of the exact candidate and its held-change manifest.
3. Keep `staging2` and its current artifacts immutable during evaluation.
4. Cut over in a separate Mobile integration PR that atomically updates the Kuikly gitlink, HAR/binary hashes, provenance/BuildId, and the staging policy in both repositories.
5. Run Mobile Android, iOS, and OHOS compile/link plus product-level smoke tests on the cutover exact. Do not inherit Kuikly-only greens as Mobile runtime evidence.
6. Only after that PR is accepted may staging3 become the production staging line or produce a release artifact.

## Rollback

- Before publication/cutover: delete or stop consuming the remote candidate branch; production remains on the unchanged staging2 pin.
- During Mobile canary: revert the atomic Mobile pin/HAR/provenance commit to the last accepted staging2 exact; do not rewrite staging2 history.
- After any immutable artifact publication: publish a new corrective version rather than overwriting bytes, and restore the previous Mobile coordinate/pin until the corrective build passes the same gates.
- Preserve this manifest, exact workflow receipts, and failed-run logs so a later intake does not repeat an unsafe upstream change blindly.

No step in this migration authorizes an artifact publish, a Mobile pin update, or a force-update of `staging2`.
