#!/usr/bin/env bash

set -euo pipefail

group="${1:-}"
case "$group" in
  common-android|ios|ohos) ;;
  *)
    echo "usage: $0 {common-android|ios|ohos}" >&2
    exit 2
    ;;
esac

source_root="$(git rev-parse --show-toplevel)"
source_head="$(git -C "$source_root" rev-parse HEAD)"
source_tree="$(git -C "$source_root" rev-parse HEAD^{tree})"
worktree_parent="$(mktemp -d)"
worktree="$worktree_parent/repo"
source_status="$worktree_parent/source-status"
candidate_head="$source_head"

git -C "$source_root" status --porcelain=v1 -z > "$source_status"
if [ -s "$source_status" ]; then
  snapshot_index="$worktree_parent/snapshot-index"
  rm -f "$snapshot_index"
  GIT_INDEX_FILE="$snapshot_index" git -C "$source_root" read-tree HEAD
  GIT_INDEX_FILE="$snapshot_index" git -C "$source_root" add -A
  candidate_tree="$(GIT_INDEX_FILE="$snapshot_index" git -C "$source_root" write-tree)"
  candidate_head="$(printf 'scroll transaction mutation snapshot\n' | \
    git -C "$source_root" commit-tree "$candidate_tree" -p "$source_head")"
else
  candidate_tree="$source_tree"
fi

cleanup() {
  git -C "$source_root" worktree remove --force "$worktree" >/dev/null 2>&1 || true
  rm -rf "$worktree_parent"
}
trap cleanup EXIT

git -C "$source_root" worktree add --detach "$worktree" "$candidate_head" >/dev/null

restore_candidate() {
  git -C "$worktree" reset --hard "$candidate_head" >/dev/null
  git -C "$worktree" clean -ffdqx >/dev/null
  test "$(git -C "$worktree" rev-parse HEAD)" = "$candidate_head"
  test "$(git -C "$worktree" rev-parse HEAD^{tree})" = "$candidate_tree"
  test -z "$(git -C "$worktree" status --porcelain)"
}

expect_mutation_killed() {
  local name="$1"
  shift
  echo "mutation start: $name"
  set +e
  (cd "$worktree" && "$@")
  local status=$?
  set -e
  if [ "$status" -eq 125 ]; then
    echo "mutation could not be applied: $name" >&2
    exit 1
  fi
  if [ "$status" -eq 0 ]; then
    echo "mutation survived: $name" >&2
    exit 1
  fi
  echo "mutation killed: $name"
  restore_candidate
}

mutate_common_attempt_limit() {
  grep -Fq 'const val MAX_ATTEMPTS = 3' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollWriteTransaction.kt || return 125
  perl -0pi -e 's/const val MAX_ATTEMPTS = 3/const val MAX_ATTEMPTS = 4/' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollWriteTransaction.kt
  grep -Fq 'const val MAX_ATTEMPTS = 4' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollWriteTransaction.kt || return 125
  ./gradlew :core:testDebugUnitTest \
    --tests com.tencent.kuikly.core.views.ScrollWriteTransactionTest \
    --no-build-cache --no-daemon
}

mutate_capability_replacement_invalidation() {
  grep -Fq 'invalidateScrollOffsetCapabilityLeases()' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt || return 125
  perl -0pi -e \
    's/(internal fun beginScrollOffsetWriteCapability\(\n        kind: ScrollOffsetWriteCapabilityKind,\n    \): ScrollOffsetWriteCapability\? \{\n        val ownerToken = captureScrollOffsetOwnerToken\(\) \?: return null\n        val invalidationTerminals = collectCapabilityReplacementTerminals\(\)\n)        invalidateScrollOffsetCapabilityLeases\(\)\n/$1/' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt
  git diff --quiet -- \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt && return 125
  ./gradlew :compose:testDebugUnitTest \
    --tests com.tencent.kuikly.compose.gestures.KuiklyScrollInfoTest.newGestureCapabilityInvalidatesOlderClaimedLease \
    --no-build-cache --no-daemon
}

mutate_capability_replacement_terminal() {
  grep -Fq 'val invalidationTerminals = collectCapabilityReplacementTerminals()' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt || return 125
  perl -0pi -e \
    's/val invalidationTerminals = collectCapabilityReplacementTerminals\(\)/val invalidationTerminals = emptyList<() -> Unit>()/' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt
  grep -Fq 'val invalidationTerminals = emptyList<() -> Unit>()' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt || return 125
  ./gradlew :compose:testDebugUnitTest \
    --tests com.tencent.kuikly.compose.gestures.KuiklyScrollInfoTest.capabilityReplacementTerminalizesClaimedRevisionWaitWithFailure \
    --no-build-cache --no-daemon
}

mutate_retry_interaction_filter() {
  grep -Fq 'pending.interactionEpoch != interactionEpoch' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt || return 125
  perl -0pi -e \
    's/if \(interactionEpoch != null && pending\.interactionEpoch != null &&\n                pending\.interactionEpoch != interactionEpoch\n            \) \{\n                return\@mapNotNull null\n            \}/if (false) { return\@mapNotNull null }/' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt
  grep -Fq 'if (false) { return@mapNotNull null }' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt || return 125
  ./gradlew :compose:testDebugUnitTest \
    --tests com.tencent.kuikly.compose.gestures.KuiklyScrollInfoTest.scrollEndOnlyDrainsRetriesFromItsInteraction \
    --no-build-cache --no-daemon
}

mutate_child_frame_external_commit() {
  grep -Fq 'writeResource.cell.commitExternal(newFrame)' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt || return 125
  perl -0pi -e \
    's/writeResource\.cell\.commitExternal\(newFrame\)/writeResource.cell.refreshCommittedIfIdle(newFrame)/' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt
  grep -Fq 'writeResource.cell.refreshCommittedIfIdle(newFrame)' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt || return 125
  ./gradlew :compose:testDebugUnitTest \
    --tests com.tencent.kuikly.compose.gestures.KuiklyScrollInfoTest.canonicalRecoveryDoesNotOverwriteOrdinaryWriterRejectedByRollbackCas \
    --no-build-cache --no-daemon
}

mutate_semantic_invalidation_terminal() {
  grep -Fq 'invalidationTerminal?.invoke()' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt || return 125
  perl -0pi -e \
    's/^\s*invalidationTerminal\?\.invoke\(\)\n//mg' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt
  grep -Fq 'invalidationTerminal?.invoke()' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt && return 125
  ./gradlew :compose:testDebugUnitTest \
    --tests com.tencent.kuikly.compose.gestures.KuiklyScrollInfoTest.newerSemanticOperationTerminalizesOlderRevisionWaitWithFailure \
    --no-build-cache --no-daemon
}

mutate_bind_publication_order() {
  grep -Fq 'scrollView = value' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt || return 125
  perl -0pi -e \
    's/(        val invalidationTerminals = invalidateScrollWriteOwnership\(\)\n)        scrollView = value\n(        if \(resetComposeScrollState\) \{.*?        deferredScrollOffsetAlignmentCoordinator\.cancelAndInvalidate \{ it\.cancel\(\) \}\n)/$1$2        scrollView = value\n/s' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt
  git diff --quiet -- \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt && return 125
  ./gradlew :compose:testDebugUnitTest \
    --tests com.tencent.kuikly.compose.gestures.KuiklyScrollInfoTest.bindingReplacementPublishesNewOwnerBeforeRetryInvalidationCallback \
    --no-build-cache --no-daemon
}

mutate_detach_publication_order() {
  grep -Fq 'scrollView = value' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt || return 125
  perl -0pi -e \
    's/(        val invalidationTerminals = invalidateScrollWriteOwnership\(\)\n)        scrollView = value\n(        if \(resetComposeScrollState\) \{.*?        deferredScrollOffsetAlignmentCoordinator\.cancelAndInvalidate \{ it\.cancel\(\) \}\n)/$1$2        scrollView = value\n/s' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt
  git diff --quiet -- \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt && return 125
  ./gradlew :compose:testDebugUnitTest \
    --tests com.tencent.kuikly.compose.gestures.KuiklyScrollInfoTest.detachClearsOwnerBeforeRetryInvalidationCallback \
    --no-build-cache --no-daemon
}

mutate_retry_replacement_terminal() {
  grep -Fq 'replaced?.onInvalidated?.invoke()' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt || return 125
  perl -0pi -e \
    's/        replaced\?\.onInvalidated\?\.invoke\(\)\n//' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt
  grep -Fq 'replaced?.onInvalidated?.invoke()' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt && return 125
  ./gradlew :compose:testDebugUnitTest \
    --tests com.tencent.kuikly.compose.gestures.KuiklyScrollInfoTest.replacingRetryOperationInvalidatesPreviousTerminal \
    --no-build-cache --no-daemon
}

mutate_semantic_replacement_terminal() {
  grep -Fq '"scroll_write_$semanticOperationId"' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt || return 125
  perl -0pi -e \
    's/            deferredScrollOffsetAlignmentCoordinator\.takeRetryOperationInvalidation\(\n                "scroll_write_\$semanticOperationId",\n            \),\n//' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt
  git diff --quiet -- \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt && return 125
  ./gradlew :compose:testDebugUnitTest \
    --tests com.tencent.kuikly.compose.gestures.KuiklyScrollInfoTest.newerSemanticOperationInvalidatesOlderRetryTerminal \
    --no-build-cache --no-daemon
}

mutate_binding_interaction_epoch_reset() {
  grep -Fq 'lastNativeInteractionEpoch = -1L' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt || return 125
  perl -0pi -e \
    's/(private fun invalidateScrollWriteOwnership\(\): List<\(\) -> Unit> \{.*?rangeRevision \+= 1L\n)        lastNativeInteractionEpoch = -1L\n/$1/s' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt
  git diff --quiet -- \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt && return 125
  ./gradlew :compose:testDebugUnitTest \
    --tests com.tencent.kuikly.compose.gestures.KuiklyScrollInfoTest.bindingReplacementAllowsNativeInteractionEpochToRestart \
    --no-build-cache --no-daemon
}

mutate_dispatch_raw_delta_capability_guard() {
  grep -Fq 'if (!kuiklyInfo.hasCurrentScrollOffsetWriteCapability(' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollableState.kt || return 125
  perl -0pi -e \
    's/if \(!kuiklyInfo\.hasCurrentScrollOffsetWriteCapability\(\n                ScrollOffsetWriteCapabilityKind\.Mutation,\n                ownerToken,\n            \)\n        \) \{/if (false) {/' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollableState.kt
  grep -Fq 'if (false) {' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollableState.kt || return 125
  ./gradlew :compose:testDebugUnitTest \
    --tests com.tencent.kuikly.compose.gestures.KuiklyScrollInfoTest.dispatchRawDeltaRequiresActiveMutationCapability \
    --no-build-cache --no-daemon
}

mutate_nested_terminal_order() {
  grep -Fq '        restoreNestedScrollPolicy()' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/views/ScrollViewEx.kt || return 125
  perl -0pi -e \
    's/        restoreNestedScrollPolicy\(\)\n        onCommitResult\(terminalResult, newOffset\)/        onCommitResult(terminalResult, newOffset)/' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/views/ScrollViewEx.kt
  git diff --quiet -- \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/views/ScrollViewEx.kt && return 125
  ./gradlew :compose:testDebugUnitTest \
    --tests com.tencent.kuikly.compose.views.ScrollViewExTest.synchronousTerminalDoesNotExposeOrRestoreTemporaryNestedScrollState \
    --no-build-cache --no-daemon
}

mutate_pager_request_authority() {
  grep -Fq 'val capability = kuiklyInfo.beginScrollOffsetWriteCapability(' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/foundation/pager/PagerState.kt || return 125
  perl -0pi -e \
    's/(    fun requestScrollToPage\(.*?    \) \{\n)        val capability = kuiklyInfo\.beginScrollOffsetWriteCapability\(\n            ScrollOffsetWriteCapabilityKind\.Mutation,\n        \)/$1        val capability = null/s' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/foundation/pager/PagerState.kt
  git diff --quiet -- \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/foundation/pager/PagerState.kt && return 125
  ./gradlew :compose:testDebugUnitTest \
    --tests com.tencent.kuikly.compose.foundation.pager.PagerRequestScrollTest.requestScrollToPageAcquiresMutationAuthority \
    --no-build-cache --no-daemon
}

mutate_drawer_request_authority() {
  grep -Fq 'val capability = kuiklyInfo.beginScrollOffsetWriteCapability(' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/foundation/drawer/DrawerInternalPagerState.kt || return 125
  perl -0pi -e \
    's/(    fun requestScrollToPage\(.*?    \) \{\n)        val capability = kuiklyInfo\.beginScrollOffsetWriteCapability\(\n            ScrollOffsetWriteCapabilityKind\.Mutation,\n        \)/$1        val capability = null/s' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/foundation/drawer/DrawerInternalPagerState.kt
  git diff --quiet -- \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/foundation/drawer/DrawerInternalPagerState.kt && return 125
  ./gradlew :compose:testDebugUnitTest \
    --tests com.tencent.kuikly.compose.foundation.drawer.DrawerRequestScrollTest.requestScrollToPageAcquiresMutationAuthority \
    --no-build-cache --no-daemon
}

mutate_pager_delayed_claim_fence() {
  grep -Fq '!kuiklyInfo.isCurrentScrollOffsetCapabilityClaim(capabilityClaim)' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/foundation/pager/PagerState.kt || return 125
  perl -0pi -e \
    's/        if \(capabilityClaim != snapOffsetCapabilityClaim\) return\n        if \(capabilityClaim != null &&\n            !kuiklyInfo\.isCurrentScrollOffsetCapabilityClaim\(capabilityClaim\)\n        \) \{\n            if \(snapOffsetCapabilityClaim == capabilityClaim\) clearSnapAnimationState\(\)\n            return\n        \}\n//' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/foundation/pager/PagerState.kt
  git diff --quiet -- \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/foundation/pager/PagerState.kt && return 125
  ./gradlew :compose:testDebugUnitTest \
    --tests com.tencent.kuikly.compose.foundation.pager.PagerRequestScrollTest.delayedAlignmentCannotMutateNewerSnapClaim \
    --no-build-cache --no-daemon
}

mutate_drawer_delayed_claim_fence() {
  grep -Fq '!kuiklyInfo.isCurrentScrollOffsetCapabilityClaim(capabilityClaim)' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/foundation/drawer/DrawerInternalPagerState.kt || return 125
  perl -0pi -e \
    's/        if \(capabilityClaim != snapOffsetCapabilityClaim\) return\n        if \(capabilityClaim != null &&\n            !kuiklyInfo\.isCurrentScrollOffsetCapabilityClaim\(capabilityClaim\)\n        \) \{\n            if \(snapOffsetCapabilityClaim == capabilityClaim\) clearSnapAnimationState\(\)\n            return\n        \}\n//' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/foundation/drawer/DrawerInternalPagerState.kt
  git diff --quiet -- \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/foundation/drawer/DrawerInternalPagerState.kt && return 125
  ./gradlew :compose:testDebugUnitTest \
    --tests com.tencent.kuikly.compose.foundation.drawer.DrawerRequestScrollTest.delayedAlignmentCannotMutateNewerSnapClaim \
    --no-build-cache --no-daemon
}

mutate_pager_null_delayed_claim_fence() {
  grep -Fq 'if (capabilityClaim != snapOffsetCapabilityClaim) return' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/foundation/pager/PagerState.kt || return 125
  perl -0pi -e \
    's/        if \(capabilityClaim != snapOffsetCapabilityClaim\) return\n//' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/foundation/pager/PagerState.kt
  git diff --quiet -- \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/foundation/pager/PagerState.kt && return 125
  ./gradlew :compose:testDebugUnitTest \
    --tests com.tencent.kuikly.compose.foundation.pager.PagerRequestScrollTest.delayedAlignmentWithoutClaimCannotMutateNewerSnapClaim \
    --no-build-cache --no-daemon
}

mutate_drawer_null_delayed_claim_fence() {
  grep -Fq 'if (capabilityClaim != snapOffsetCapabilityClaim) return' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/foundation/drawer/DrawerInternalPagerState.kt || return 125
  perl -0pi -e \
    's/        if \(capabilityClaim != snapOffsetCapabilityClaim\) return\n//' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/foundation/drawer/DrawerInternalPagerState.kt
  git diff --quiet -- \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/foundation/drawer/DrawerInternalPagerState.kt && return 125
  ./gradlew :compose:testDebugUnitTest \
    --tests com.tencent.kuikly.compose.foundation.drawer.DrawerRequestScrollTest.delayedAlignmentWithoutClaimCannotMutateNewerSnapClaim \
    --no-build-cache --no-daemon
}

mutate_binding_reentrant_alignment_cleanup() {
  grep -Fq '        invalidateRetryOperations()' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt || return 125
  perl -0pi -e \
    's/(    fun cancelAndInvalidate\(cancelPendingAlignment: \(T\) -> Unit\) \{\n        alignmentGeneration \+= 1\n)(        val previous = pendingAlignment\(\)\n        updatePendingAlignment\(null\)\n        previous\?\.let\(cancelPendingAlignment\)\n)(        invalidateRetryOperations\(\)\n)/$1$3$2/' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt
  git diff --quiet -- \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt && return 125
  ./gradlew :compose:testDebugUnitTest \
    --tests com.tencent.kuikly.compose.gestures.KuiklyScrollInfoTest.bindingReplacementPreservesReentrantNewOwnerAlignment \
    --no-build-cache --no-daemon
}

mutate_pager_request_replacement_order() {
  perl -0pi -e \
    's/(    fun requestScrollToPage\(.*?    \) \{\n)(        val capability = kuiklyInfo\.beginScrollOffsetWriteCapability\(\n            ScrollOffsetWriteCapabilityKind\.Mutation,\n        \)\n)(        clearSnapAnimationState\(\)\n)/$1$3$2/s' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/foundation/pager/PagerState.kt
  git diff --quiet -- \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/foundation/pager/PagerState.kt && return 125
  ./gradlew :compose:testDebugUnitTest \
    --tests com.tencent.kuikly.compose.foundation.pager.PagerRequestScrollTest.requestCurrentPageTerminalizesStateHeldRevisionWaitExactlyOnce \
    --tests com.tencent.kuikly.compose.foundation.pager.PagerRequestScrollTest.requestCurrentPageTerminalizesStateHeldInteractionWaitExactlyOnce \
    --no-build-cache --no-daemon
}

mutate_drawer_request_replacement_order() {
  perl -0pi -e \
    's/(    fun requestScrollToPage\(.*?    \) \{\n)(        val capability = kuiklyInfo\.beginScrollOffsetWriteCapability\(\n            ScrollOffsetWriteCapabilityKind\.Mutation,\n        \)\n)(        clearSnapAnimationState\(\)\n)/$1$3$2/s' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/foundation/drawer/DrawerInternalPagerState.kt
  git diff --quiet -- \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/foundation/drawer/DrawerInternalPagerState.kt && return 125
  ./gradlew :compose:testDebugUnitTest \
    --tests com.tencent.kuikly.compose.foundation.drawer.DrawerRequestScrollTest.requestCurrentPageTerminalizesStateHeldRevisionWaitExactlyOnce \
    --tests com.tencent.kuikly.compose.foundation.drawer.DrawerRequestScrollTest.requestCurrentPageTerminalizesStateHeldInteractionWaitExactlyOnce \
    --no-build-cache --no-daemon
}

mutate_state_held_scroll_write_retry_extraction() {
  grep -Fq '"scroll_write_$semanticOperationId"' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt || return 125
  perl -0pi -e \
    's/            deferredScrollOffsetAlignmentCoordinator\.takeRetryOperationInvalidation\(\n                "scroll_write_\$semanticOperationId",\n            \),\n//' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt
  git diff --quiet -- \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt && return 125
  ./gradlew :compose:testDebugUnitTest \
    --tests com.tencent.kuikly.compose.gestures.KuiklyScrollInfoTest.releasedStateHeldClaimStillTerminalizesInteractionWaitOnReplacement \
    --tests com.tencent.kuikly.compose.foundation.pager.PagerRequestScrollTest.requestCurrentPageTerminalizesStateHeldInteractionWaitExactlyOnce \
    --tests com.tencent.kuikly.compose.foundation.drawer.DrawerRequestScrollTest.requestCurrentPageTerminalizesStateHeldInteractionWaitExactlyOnce \
    --no-build-cache --no-daemon
}

mutate_state_held_offset_delta_retry_extraction() {
  grep -Fq '"offset_delta_$semanticOperationId"' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt || return 125
  perl -0pi -e \
    's/            deferredScrollOffsetAlignmentCoordinator\.takeRetryOperationInvalidation\(\n                "offset_delta_\$semanticOperationId",\n            \),\n//' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt
  git diff --quiet -- \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt && return 125
  ./gradlew :compose:testDebugUnitTest \
    --tests com.tencent.kuikly.compose.gestures.KuiklyScrollInfoTest.releasedStateHeldClaimRemovesOffsetDeltaRetryOnReplacement \
    --no-build-cache --no-daemon
}

mutate_bind_native_prepare_publication() {
  grep -Fq 'previous.prepareForComposeReuse(publishReplacement)' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt || return 125
  perl -0pi -e \
    's/                previous\.prepareForComposeReuse\(publishReplacement\)/                previous.prepareForComposeReuse()\n                publishReplacement()/' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt
  ./gradlew :compose:testDebugUnitTest \
    --tests com.tencent.kuikly.compose.gestures.KuiklyScrollInfoBindingPrepareTest.bindPublishesNewOwnerBeforeSynchronousNativePrepareTerminal \
    --no-build-cache --no-daemon
}

mutate_detach_native_prepare_publication() {
  grep -Fq 'expected.prepareForComposeReuse(publishDetached)' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt || return 125
  perl -0pi -e \
    's/            expected\.prepareForComposeReuse\(publishDetached\)/            expected.prepareForComposeReuse()\n            publishDetached()/' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt
  ./gradlew :compose:testDebugUnitTest \
    --tests com.tencent.kuikly.compose.gestures.KuiklyScrollInfoBindingPrepareTest.detachPublishesNoOwnerBeforeSynchronousNativePrepareTerminal \
    --no-build-cache --no-daemon
}

mutate_bound_reuse_native_prepare_publication() {
  grep -Fq 'expected.prepareForComposeReuse(beforeNativePrepare = {' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt || return 125
  perl -0pi -e \
    's/        var invalidationTerminals: List<\(\) -> Unit> = emptyList\(\)\n        expected\.prepareForComposeReuse\(beforeNativePrepare = \{\n            invalidationTerminals = publishScrollViewBinding\(expected\)\n        \}\)\n        invalidationTerminals\.forEach \{ it\(\) \}/        expected.prepareForComposeReuse()/' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/gestures/KuiklyScrollInfo.kt
  ./gradlew :compose:testDebugUnitTest \
    --tests com.tencent.kuikly.compose.gestures.KuiklyScrollInfoBindingPrepareTest.boundReusePublishesNewGenerationBeforeSynchronousNativePrepareTerminal \
    --no-build-cache --no-daemon
}

mutate_attempt_terminal_consumption() {
  grep -Fq 'if (attemptTerminalHandled) return' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/scroller/ScrollableStateExtensions.kt || return 125
  perl -0pi -e \
    's/            if \(attemptTerminalHandled\) return\n            attemptTerminalHandled = true\n//' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/scroller/ScrollableStateExtensions.kt
  git diff --quiet -- \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/scroller/ScrollableStateExtensions.kt && return 125
  ./gradlew :compose:testDebugUnitTest \
    --tests com.tencent.kuikly.compose.gestures.KuiklyScrollInfoTest.duplicateOlderAttemptTerminalCannotFinishNewerRetry \
    --no-build-cache --no-daemon
}

mutate_zero_epoch_rejection() {
  grep -Fq 'nativeInteractionEpoch > 0L && params.nativeInteractionEpoch <= 0L' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt || return 125
  perl -0pi -e \
    's/        if \(nativeInteractionEpoch > 0L && params\.nativeInteractionEpoch <= 0L\) \{\n            return false\n        \}\n//' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt
  git diff --quiet -- \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt && return 125
  ./gradlew :core:testDebugUnitTest \
    --tests com.tencent.kuikly.core.views.ScrollWriteTransactionTest.zeroEpochEventCannotTerminateEstablishedNativeInteraction \
    --no-build-cache --no-daemon
}

mutate_stale_native_event_rejection() {
  grep -Fq 'params.nativeInteractionEpoch < nativeInteractionEpoch' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt || return 125
  perl -0pi -e \
    's/params\.nativeInteractionEpoch < nativeInteractionEpoch/false/' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt
  grep -Fq 'params.nativeInteractionEpoch < nativeInteractionEpoch' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt && return 125
  ./gradlew :core:testDebugUnitTest \
    --tests com.tencent.kuikly.core.views.ScrollWriteTransactionTest.staleInteractionEventCannotRegressCurrentNativeState \
    --no-build-cache --no-daemon
}

mutate_scale_native_interaction_epoch() {
  grep -Fq 'nativeInteractionEpoch = nativeInteractionEpoch,' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/ui/ScaleWithDensity.kt || return 125
  perl -0pi -e 's/\n        nativeInteractionEpoch = nativeInteractionEpoch,//' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/ui/ScaleWithDensity.kt
  grep -Fq 'nativeInteractionEpoch = nativeInteractionEpoch,' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/ui/ScaleWithDensity.kt && return 125
  ./gradlew :compose:testDebugUnitTest \
    --tests com.tencent.kuikly.compose.scroller.ContentSizeExtensionsTest.scaledRendererScrollEndPreservesTransactionIdentityAndDrainsMatchingRetry \
    --no-build-cache --no-daemon
}

mutate_scale_layout_revision() {
  grep -Fq 'layoutRevision = layoutRevision,' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/ui/ScaleWithDensity.kt || return 125
  perl -0pi -e 's/\n        layoutRevision = layoutRevision,//' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/ui/ScaleWithDensity.kt
  grep -Fq 'layoutRevision = layoutRevision,' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/ui/ScaleWithDensity.kt && return 125
  ./gradlew :compose:testDebugUnitTest \
    --tests com.tencent.kuikly.compose.scroller.ContentSizeExtensionsTest.scaledRendererScrollEndPreservesTransactionIdentityAndDrainsMatchingRetry \
    --no-build-cache --no-daemon
}

mutate_scale_inset_revision() {
  grep -Fq 'insetRevision = insetRevision,' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/ui/ScaleWithDensity.kt || return 125
  perl -0pi -e 's/\n        insetRevision = insetRevision,//' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/ui/ScaleWithDensity.kt
  grep -Fq 'insetRevision = insetRevision,' \
    compose/src/commonMain/kotlin/com/tencent/kuikly/compose/ui/ScaleWithDensity.kt && return 125
  ./gradlew :compose:testDebugUnitTest \
    --tests com.tencent.kuikly.compose.scroller.ContentSizeExtensionsTest.scaledRendererScrollEndPreservesTransactionIdentityAndDrainsMatchingRetry \
    --no-build-cache --no-daemon
}

mutate_animated_offset_already_satisfied_phase() {
  grep -Fq '(!committed || result.code == ScrollWriteResultCode.AlreadySatisfied)' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt || return 125
  perl -0pi -e \
    's/\(!committed \|\| result\.code == ScrollWriteResultCode\.AlreadySatisfied\)/!committed/' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt
  grep -Fq '(!committed || result.code == ScrollWriteResultCode.AlreadySatisfied)' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt && return 125
  ./gradlew :core:testDebugUnitTest \
    --tests com.tencent.kuikly.core.views.ScrollerViewAlreadySatisfiedPhaseTest.transactionalAnimatedOffsetRestoresIdleAfterAlreadySatisfied \
    --tests com.tencent.kuikly.core.views.ScrollerViewAlreadySatisfiedPhaseTest.alreadySatisfiedRestoresPreExistingDraggingPhase \
    --no-build-cache --no-daemon
}

mutate_legacy_animated_offset_already_satisfied_phase() {
  grep -Fq 'animation != null &&' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt || return 125
  perl -0pi -e \
    's/(animation != null &&\n\s+)\(!decoded\.committed \|\| decoded\.code == ScrollWriteResultCode\.AlreadySatisfied\)/$1!decoded.committed/' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt
  git diff --quiet -- core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt && return 125
  ./gradlew :core:testDebugUnitTest \
    --tests com.tencent.kuikly.core.views.ScrollerViewAlreadySatisfiedPhaseTest.legacyAnimatedOffsetRestoresIdleAfterAlreadySatisfied \
    --no-build-cache --no-daemon
}

mutate_animated_inset_already_satisfied_phase() {
  grep -Fq 'animated &&' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt || return 125
  perl -0pi -e \
    's/(animated &&\n\s+)\(!decoded\.committed \|\| decoded\.code == ScrollWriteResultCode\.AlreadySatisfied\)/$1!decoded.committed/' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt
  git diff --quiet -- core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt && return 125
  ./gradlew :core:testDebugUnitTest \
    --tests com.tencent.kuikly.core.views.ScrollerViewAlreadySatisfiedPhaseTest.animatedInsetRestoresIdleAndAdmitsNextIdleRequiredWrite \
    --no-build-cache --no-daemon
}

mutate_animated_phase_restore_owner() {
  grep -Fq 'if (phase.owner != composeAnimatedPhaseOwner) return' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt || return 125
  perl -0pi -e \
    's/(private fun restoreComposeAnimatedPhase\(phase: ComposeAnimatedPhaseSnapshot\) \{\n        if \(phase\.authorityGeneration != composeAnimatedPhaseAuthorityGeneration\) return\n)        if \(phase\.owner != composeAnimatedPhaseOwner\) return\n/$1/' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt
  test "$(grep -Fc 'if (phase.owner != composeAnimatedPhaseOwner) return' core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt)" -eq 1 || return 125
  ./gradlew :core:testDebugUnitTest \
    --tests com.tencent.kuikly.core.views.ScrollerViewAlreadySatisfiedPhaseTest.lateAlreadySatisfiedCannotRestoreOverNewerAnimatedWrite \
    --tests com.tencent.kuikly.core.views.ScrollerViewAlreadySatisfiedPhaseTest.lateAnimatedInsetTerminalCannotRestoreOverNewerAnimatedOffset \
    --no-build-cache --no-daemon
}

mutate_preinstall_phase_owner_rollback() {
  grep -Fq 'if (!result.installed) {' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt || return 125
  perl -0pi -e \
    's/if \(!result\.installed\) \{/if (!result.installed \&\& isCurrentComposeWriteOperation(commitToken)) {/' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt
  perl -0pi -e \
    's/if \(!decoded\.installed\) \{/if (!decoded.installed \&\& isCurrentComposeWriteOperation(commitToken)) {/g' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt
  grep -Fq 'if (!result.installed && isCurrentComposeWriteOperation(commitToken)) {' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt || return 125
  ./gradlew :core:testDebugUnitTest \
    --tests com.tencent.kuikly.core.views.ScrollerViewAlreadySatisfiedPhaseTest.chainedPreinstallRejectionsDoNotRestoreRejectedProvisionalOwner \
    --no-build-cache --no-daemon
}

mutate_preinstall_phase_owner_rollback_cas() {
  grep -Fq 'if (phase.owner != composeAnimatedPhaseOwner) return' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt || return 125
  perl -0pi -e \
    's/        if \(phase\.owner != composeAnimatedPhaseOwner\) return\n//' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt
  test "$(grep -Fc 'if (phase.owner != composeAnimatedPhaseOwner) return' core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt)" -eq 1 || return 125
  ./gradlew :core:testDebugUnitTest \
    --tests com.tencent.kuikly.core.views.ScrollerViewAlreadySatisfiedPhaseTest.lateRejectedAttemptCannotRollbackNewerAttemptPhaseOwner \
    --no-build-cache --no-daemon
}

mutate_animated_phase_provenance() {
  grep -Fq 'composeAnimatedUnderlyingPhase != null' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt || return 125
  perl -0pi -e \
    's/nativeScrollPhase == NativeScrollPhase\.SettlingOrAnimating &&\n            composeAnimatedUnderlyingPhase != null/false && nativeScrollPhase == NativeScrollPhase.SettlingOrAnimating &&\n            composeAnimatedUnderlyingPhase != null/' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt
  grep -Fq 'false && nativeScrollPhase == NativeScrollPhase.SettlingOrAnimating' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt || return 125
  ./gradlew :core:testDebugUnitTest \
    --tests com.tencent.kuikly.core.views.ScrollerViewAlreadySatisfiedPhaseTest.replacingAnimatedWriteAlreadySatisfiedRestoresUnderlyingIdlePhase \
    --tests com.tencent.kuikly.core.views.ScrollerViewAlreadySatisfiedPhaseTest.animatedInsetReplacementRestoresUnderlyingIdlePhase \
    --tests com.tencent.kuikly.core.views.ScrollerViewAlreadySatisfiedPhaseTest.legacyAnimatedReplacementRestoresUnderlyingIdlePhase \
    --no-build-cache --no-daemon
}

mutate_immediate_phase_native_authority() {
  grep -Fq 'if (phase.authorityGeneration != composeAnimatedPhaseAuthorityGeneration) return' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt || return 125
  perl -0pi -e \
    's/(private fun commitImmediateComposeWritePhase\(phase: ComposeAnimatedPhaseSnapshot\) \{\n)        if \(phase\.authorityGeneration != composeAnimatedPhaseAuthorityGeneration\) return\n/$1/' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt
  test "$(grep -Fc 'if (phase.authorityGeneration != composeAnimatedPhaseAuthorityGeneration) return' core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt)" -eq 3 || return 125
  ./gradlew :core:testDebugUnitTest \
    --tests com.tencent.kuikly.core.views.ScrollerViewAlreadySatisfiedPhaseTest.acceptedNativeDraggingBeforeImmediateCommitCannotBeOverwritten \
    --no-build-cache --no-daemon
}

mutate_phase_event_operation_identity() {
  grep -Fq 'if (sourceOperationGeneration <= 0L) {' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt || return 125
  perl -0pi -e \
    's/if \(sourceOperationGeneration <= 0L\) \{/if (true) {/' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt
  grep -Fq 'if (true) {' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt || return 125
  ./gradlew :core:testDebugUnitTest \
    --tests com.tencent.kuikly.core.views.ScrollerViewAlreadySatisfiedPhaseTest.sameOperationProgrammaticSettlingPreservesUnderlyingPhase \
    --tests com.tencent.kuikly.core.views.ScrollerViewAlreadySatisfiedPhaseTest.latePredecessorSettlingEventCannotInvalidateReplacementPhaseRestore \
    --no-build-cache --no-daemon
}

mutate_scroll_end_operation_identity() {
  grep -Fq 'it.sourceOperationGeneration,' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt || return 125
  perl -0pi -e \
    's/(NativeScrollPhase\.Idle,\n\s+)it\.sourceOperationGeneration,/$1 0L,/' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt
  grep -Fq 'NativeScrollPhase.Idle,' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt || return 125
  ./gradlew :core:testDebugUnitTest \
    --tests com.tencent.kuikly.core.views.ScrollerViewAlreadySatisfiedPhaseTest.operationOwnedScrollEndCannotClearNewerAnimatedOwner \
    --no-build-cache --no-daemon
}

mutate_installed_phase_rollback_boundary() {
  grep -Fq 'if (!result.installed) {' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt || return 125
  perl -0pi -e \
    's/if \(!result\.installed\) \{/if (true) {/' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt
  grep -Fq 'if (!result.installed) {' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt && return 125
  ./gradlew :core:testDebugUnitTest \
    --tests com.tencent.kuikly.core.views.ScrollerViewAlreadySatisfiedPhaseTest.postInstallFailureDoesNotRollbackPredecessorPhaseOwner \
    --no-build-cache --no-daemon
}

mutate_installed_terminal_owner_recording() {
  local target=core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt
  test "$(grep -Fc 'markComposeWritePhaseTerminal(phaseBeforeAnimatedWrite)' "$target")" -eq 3 || return 125
  # Delete exactly the three 3-line recording blocks: anchored to line starts
  # ([ \t]* indent, NOT \s+ — a leading \s+ eats the previous line's newline
  # and glues the next statement onto it, breaking compilation; that made the
  # connected tests "fail" for compilation instead of behavior).
  perl -0pi -e \
    's/^[ \t]*if \((?:result|decoded)\.installed\) \{\n[ \t]*markComposeWritePhaseTerminal\(phaseBeforeAnimatedWrite\)\n[ \t]*\}\n//mg' \
    "$target"
  # Application count must be exactly 3 blocks: 0 additions / 9 deletions.
  test "$(git diff --numstat -- "$target" | awk '{print $1" "$2}')" = "0 9" || return 125
  grep -Fq 'markComposeWritePhaseTerminal(phaseBeforeAnimatedWrite)' \
    "$target" && return 125
  # The three connected regressions must fail for BEHAVIOR, not compilation:
  # capture the output and treat any compile error as a broken mutation setup.
  local out status
  out="$(./gradlew :core:testDebugUnitTest \
    --tests com.tencent.kuikly.core.views.ScrollerViewAlreadySatisfiedPhaseTest.rejectedSuccessorDoesNotReviveInstalledPredecessorAfterTerminal \
    --tests com.tencent.kuikly.core.views.ScrollerViewAlreadySatisfiedPhaseTest.rejectedLegacySuccessorDoesNotReviveInstalledPredecessorAfterTerminal \
    --tests com.tencent.kuikly.core.views.ScrollerViewAlreadySatisfiedPhaseTest.rejectedInsetSuccessorDoesNotReviveInstalledPredecessorAfterTerminal \
    --no-build-cache --no-daemon 2>&1)"
  status=$?
  echo "$out" | grep -Eq "^e: |Compilation error|compileDebug.*FAILED" && return 125
  # Behavioral kill evidence: at least one test actually ran and failed.
  echo "$out" | grep -q "FAILED" || return 125
  return $status
}

mutate_preinstall_immediate_phase_witness() {
  grep -Fq 'nativeScrollPhase = NativeScrollPhase.SettlingOrAnimating' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt || return 125
  perl -0pi -e \
    's/(if \(animated\) \{\n\s+composeAnimatedPhasePredecessors\[owner\] = composeAnimatedPhaseOwner\n\s+composeAnimatedPhaseUnderlyingPhases\[owner\] = underlyingPhase\n\s+inactiveComposeAnimatedPhaseOwners\.remove\(owner\)\n\s+composeAnimatedUnderlyingPhase = underlyingPhase\n\s+composeAnimatedPhaseOwner = owner\n\s+nativeScrollPhase = NativeScrollPhase\.SettlingOrAnimating\n\s+\})/$1 else if (composeAnimatedUnderlyingPhase != null) {\n            invalidateComposeAnimatedPhase()\n            nativeScrollPhase = underlyingPhase\n        }/' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt
  grep -Fq 'else if (composeAnimatedUnderlyingPhase != null)' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ScrollerView.kt || return 125
  ./gradlew :core:testDebugUnitTest \
    --tests com.tencent.kuikly.core.views.ScrollerViewAlreadySatisfiedPhaseTest.rejectedImmediateReplacementDoesNotPublishIdleBeforeNativeAcceptance \
    --no-build-cache --no-daemon
}

mutate_list_reuse_tokenless_restore() {
  grep -Fq 'val contentOffset = transformInputSetContentOffset(curOffsetX, curOffsetY)' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ListView.kt || return 125
  perl -0pi -e \
    's/val contentOffset = transformInputSetContentOffset\(curOffsetX, curOffsetY\)\n            performTaskWhenRenderViewDidLoad \{\n                callContentOffset\(\n                    offsetX = contentOffset\.first,\n                    offsetY = contentOffset\.second,\n                    animated = false,\n                \)\n            \}/setContentOffset(offsetX = curOffsetX, offsetY = curOffsetY, animated = false)/' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ListView.kt
  grep -Fq 'setContentOffset(offsetX = curOffsetX, offsetY = curOffsetY, animated = false)' \
    core/src/commonMain/kotlin/com/tencent/kuikly/core/views/ListView.kt || return 125
  ./gradlew :core-render-android:testDebugUnitTest \
    --tests com.tencent.kuikly.core.render.android.expand.component.list.KRRecyclerViewTransactionFixtureTest.listReuseRestoreRemainsPendingUntilAndroidContentIsAttached \
    --no-build-cache --no-daemon
}

mutate_android_edge_barrier() {
  grep -Fq '(operation.primaryPending || operation.edgePending)' \
    core-render-android/src/main/java/com/tencent/kuikly/core/render/android/expand/component/list/KRRecyclerView.kt || return 125
  perl -0pi -e \
    's/\(operation\.primaryPending \|\| operation\.edgePending\)/operation.primaryPending/' \
    core-render-android/src/main/java/com/tencent/kuikly/core/render/android/expand/component/list/KRRecyclerView.kt
  grep -Fq 'operation?.started == true && operation.primaryPending' \
    core-render-android/src/main/java/com/tencent/kuikly/core/render/android/expand/component/list/KRRecyclerView.kt || return 125
  ./gradlew :core-render-android:testDebugUnitTest \
    --tests com.tencent.kuikly.core.render.android.expand.component.list.KRRecyclerViewTransactionFixtureTest \
    --no-build-cache --no-daemon
}

mutate_android_preinstall_authority() {
  grep -Fq 'private fun validateOffsetWrite(token: OffsetWriteToken?): NativeScrollWriteResultCode {' \
    core-render-android/src/main/java/com/tencent/kuikly/core/render/android/expand/component/list/KRRecyclerView.kt || return 125
  perl -0pi -e \
    's/(if \(token\.insetRevision != nativeInsetRevision\) \{\n            return NativeScrollWriteResultCode\.Stale\n        \}\n)(        return NativeScrollWriteResultCode\.Committed)/$1        commitOffsetWriteAuthority(token)\n$2/' \
    core-render-android/src/main/java/com/tencent/kuikly/core/render/android/expand/component/list/KRRecyclerView.kt
  test "$(grep -Fc 'commitOffsetWriteAuthority(token)' core-render-android/src/main/java/com/tencent/kuikly/core/render/android/expand/component/list/KRRecyclerView.kt)" -eq 3 || return 125
  ./gradlew :core-render-android:testDebugUnitTest \
    --tests com.tencent.kuikly.core.render.android.expand.component.list.KRRecyclerViewTransactionFixtureTest.rejectedPreinstallWriteDoesNotAdvanceLatestComposeAuthority \
    --no-build-cache --no-daemon
}

mutate_h5_delayed_current_check() {
  grep -Fq 'if (!writeArbiter.isCurrent(operation)) return@scheduleTask' \
    core-render-web/h5/src/jsMain/kotlin/com/tencent/kuikly/core/render/web/runtime/web/expand/components/list/H5ListView.kt || return 125
  perl -0pi -e \
    's/if \(!writeArbiter\.isCurrent\(operation\)\) return\@scheduleTask\n            refreshLayoutRevision\(\)/refreshLayoutRevision()/g' \
    core-render-web/h5/src/jsMain/kotlin/com/tencent/kuikly/core/render/web/runtime/web/expand/components/list/H5ListView.kt
  git diff --quiet -- \
    core-render-web/h5/src/jsMain/kotlin/com/tencent/kuikly/core/render/web/runtime/web/expand/components/list/H5ListView.kt && return 125
  ./gradlew :core-render-web:h5:jsBrowserTest --no-build-cache --no-daemon
}

mutate_ios_animation_current_check() {
  grep -Fq 'if (![self p_isCurrentScrollWrite:operation]) {' \
    core-render-ios/Extension/Components/KRScrollView.m || return 125
  perl -0pi -e \
    's/if \(!\[self p_isCurrentScrollWrite:operation\]\) \{\n\s+return;\n\s+\}/if (NO) { return; }/g' \
    core-render-ios/Extension/Components/KRScrollView.m
  grep -Fq 'if (NO) { return; }' core-render-ios/Extension/Components/KRScrollView.m || return 125
  tools/ios-renderer-tests/run-scroll-view-transaction-fixture.sh
}

mutate_ios_active_operation_scroll_end() {
  grep -Fq 'BOOL animating = _currentScrollWriteOperation != nil || _offsetAnimator != nil ||' \
    core-render-ios/Extension/Components/KRScrollView.m || return 125
  perl -0pi -e \
    's/BOOL animating = _currentScrollWriteOperation != nil \|\| _offsetAnimator != nil \|\|\n\s+\[_ku_coreAnimator isAnimating\];/BOOL animating = [_ku_coreAnimator isAnimating];/g' \
    core-render-ios/Extension/Components/KRScrollView.m
  grep -Fq 'BOOL animating = _currentScrollWriteOperation != nil || _offsetAnimator != nil ||' \
    core-render-ios/Extension/Components/KRScrollView.m && return 125
  KR_SKIP_DIRECT_INSET_END_TEST=1 KR_SKIP_ANIMATED_COMPLETION_END_TEST=1 \
    tools/ios-renderer-tests/run-scroll-view-transaction-fixture.sh
}

mutate_ios_direct_inset_scroll_end() {
  grep -Fq '_css_scrollEnd(eventParams);' \
    core-render-ios/Extension/Components/KRScrollView.m || return 125
  perl -0pi -e \
    's/(self\.contentInset = contentInset;\n            _nativeInsetRevision \+= 1;\n)            NSDictionary \*eventParams = \[self p_generateEventBaseParams\];\n(            dispatch_block_t terminal = \[self p_finalizeScrollWrite:operation\n                                                          resultCode:KRScrollWriteResultCodeCommitted\];\n)            if \(_css_scrollEnd\) \{\n                _css_scrollEnd\(eventParams\);\n            \}\n/$1$2/' \
    core-render-ios/Extension/Components/KRScrollView.m
  git diff --quiet -- core-render-ios/Extension/Components/KRScrollView.m && return 125
  KR_SKIP_ACTIVE_OPERATION_END_TEST=1 KR_SKIP_ANIMATED_COMPLETION_END_TEST=1 \
    tools/ios-renderer-tests/run-scroll-view-transaction-fixture.sh
}

mutate_ios_terminal_source_identity() {
  grep -Fq 'NSUInteger sourceOperation = (!_isCurrentlyDragging && _currentScrollWriteOperation.animated)' \
    core-render-ios/Extension/Components/KRScrollView.m || return 125
  perl -0pi -e \
    's/NSUInteger sourceOperation = \(!_isCurrentlyDragging && _currentScrollWriteOperation\.animated\)\n        \? _currentScrollWriteOperation\.composeOperation : 0;/NSUInteger sourceOperation = 0;/' \
    core-render-ios/Extension/Components/KRScrollView.m
  grep -Fq 'NSUInteger sourceOperation = 0;' \
    core-render-ios/Extension/Components/KRScrollView.m || return 125
  KR_SKIP_ACTIVE_OPERATION_END_TEST=1 \
    tools/ios-renderer-tests/run-scroll-view-transaction-fixture.sh
}

mutate_ohos_current_identity() {
  grep -Fq 'current_ == operation' \
    core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRCurrentOperationArbiter.h || return 125
  perl -0pi -e \
    's/return operation && !operation->terminal && current_ == operation;/return operation && !operation->terminal;/' \
    core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRCurrentOperationArbiter.h
  grep -Fq 'return operation && !operation->terminal;' \
    core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRCurrentOperationArbiter.h || return 125
  tools/ohos-renderer-tests/run-current-operation-arbiter-fixture.sh
}

mutate_ohos_preinstall_authority() {
  grep -Fq 'KRScrollWriteResultCode KRScrollerView::ValidateOffsetWrite' \
    core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRScrollerView.cpp || return 125
  perl -0pi -e \
    's/(if \(inset_revision != native_inset_revision_\) \{\n        return KRScrollWriteResultCode::Stale;\n    \}\n)(    return KRScrollWriteResultCode::Committed;)/$1    if (operation_generation > 0) {\n        latest_compose_write_operation_ = operation_generation;\n    }\n$2/' \
    core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRScrollerView.cpp
  test "$(grep -Fc 'latest_compose_write_operation_ = operation_generation;' core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRScrollerView.cpp)" -eq 3 || return 125
  python3 tools/scroll-transaction-audit/check.py
}

mutate_ohos_inset_correction_stop() {
  grep -Fq '(inset_offset_correction_required && !inset_offset_correction_finished)' \
    core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRScrollReplacementPolicy.h || return 125
  perl -0pi -e \
    's/return native_motion_expected && animated && \(\n        content_offset_resource \|\|\n        \(inset_offset_correction_required && !inset_offset_correction_finished\)\);/return (static_cast<void>(inset_offset_correction_required), static_cast<void>(inset_offset_correction_finished), native_motion_expected \&\& animated \&\& content_offset_resource);/' \
    core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRScrollReplacementPolicy.h
  grep -Fq 'static_cast<void>(inset_offset_correction_required)' \
    core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRScrollReplacementPolicy.h || return 125
  tools/ohos-renderer-tests/run-scroll-replacement-policy-test.sh
}

mutate_ohos_active_axis_terminal_xy() {
  grep -Fq 'return direction_row ? std::fabs(ax - bx) : std::fabs(ay - by);' \
    core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRScrollReplacementPolicy.h || return 125
  perl -0pi -e \
    's/return direction_row \? std::fabs\(ax - bx\) : std::fabs\(ay - by\);/return direction_row ? std::fabs(ay - by) : std::fabs(ax - bx);/' \
    core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRScrollReplacementPolicy.h
  grep -Fq 'return direction_row ? std::fabs(ay - by) : std::fabs(ax - bx);' \
    core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRScrollReplacementPolicy.h || return 125
  tools/ohos-renderer-tests/run-scroll-replacement-policy-test.sh
}

mutate_ohos_native_motion_expected_gate() {
  grep -Fq 'return native_motion_expected && animated && (' \
    core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRScrollReplacementPolicy.h || return 125
  perl -0pi -e \
    's/return native_motion_expected && animated && \(/return !native_motion_expected \&\& animated && (/' \
    core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRScrollReplacementPolicy.h
  grep -Fq 'return !native_motion_expected && animated && (' \
    core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRScrollReplacementPolicy.h || return 125
  tools/ohos-renderer-tests/run-scroll-replacement-policy-test.sh
}

mutate_ohos_replacement_stop_fence_policy() {
  grep -Fq 'return native_motion_expected && animated && (' \
    core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRScrollReplacementPolicy.h || return 125
  perl -0pi -e \
    's/return native_motion_expected && animated && \(/return (static_cast<void>(animated), native_motion_expected) \&\& (/' \
    core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRScrollReplacementPolicy.h
  grep -Fq 'return (static_cast<void>(animated), native_motion_expected) && (' \
    core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRScrollReplacementPolicy.h || return 125
  tools/ohos-renderer-tests/run-scroll-replacement-policy-test.sh
}

mutate_ohos_replacement_stop_fence_coalescing() {
  grep -Fq 'bool pending_stop_event_ = false;' \
    core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRScrollReplacementPolicy.h || return 125
  perl -0pi -e \
    's/bool pending_stop_event_ = false;/unsigned int pending_stop_event_ = 0;/; s/pending_stop_event_ = true;/pending_stop_event_++;/; s/pending_stop_event_ = false;\n        return true;/pending_stop_event_--;\n        return true;/' \
    core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRScrollReplacementPolicy.h
  grep -Fq 'pending_stop_event_++;' \
    core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRScrollReplacementPolicy.h || return 125
  tools/ohos-renderer-tests/run-scroll-replacement-policy-test.sh
}

mutate_ohos_replacement_stop_fence_arm() {
  test "$(grep -Fc 'replacement_stop_event_fence_.Arm();' core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRScrollerView.cpp)" -eq 5 || return 125
  perl -0pi -e \
    's/(if \(previous && KRShouldStopReplacedScrollMotion\([\s\S]*?ArkUI_AttributeItem item = \{values, 2\};\n)        replacement_stop_event_fence_\.Arm\(\);\n/$1/' \
    core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRScrollerView.cpp
  test "$(grep -Fc 'replacement_stop_event_fence_.Arm();' core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRScrollerView.cpp)" -eq 4 || return 125
  python3 tools/scroll-transaction-audit/check.py
}

mutate_ohos_replacement_stop_fence_consume() {
  grep -Fq 'replacement_stop_event_fence_.ConsumeReplacementStop()' \
    core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRScrollerView.cpp || return 125
  perl -0pi -e \
    's/    if \(replacement_stop_event_fence_\.ConsumeReplacementStop\(\)\) \{\n        return;\n    \}\n//' \
    core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRScrollerView.cpp
  grep -Fq 'replacement_stop_event_fence_.ConsumeReplacementStop()' \
    core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRScrollerView.cpp && return 125
  python3 tools/scroll-transaction-audit/check.py
}

mutate_ohos_replacement_stop_fence_reset() {
  test "$(grep -Fc 'replacement_stop_event_fence_.Reset();' core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRScrollerView.cpp)" -eq 1 || return 125
  perl -0pi -e \
    's/    replacement_stop_event_fence_\.Reset\(\);\n//g' \
    core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRScrollerView.cpp
  grep -Fq 'replacement_stop_event_fence_.Reset();' \
    core-render-ohos/src/main/cpp/libohos_render/expand/components/scroller/KRScrollerView.cpp && return 125
  python3 tools/scroll-transaction-audit/check.py
}

copy_ohos_metadata() {
  local relative="core-render-ohos/.cxx/default/default/debug/hvigor/arm64-v8a/summary.cmake"
  test -f "$source_root/$relative"
  mkdir -p "$(dirname "$worktree/$relative")"
  cp "$source_root/$relative" "$worktree/$relative"
}

case "$group" in
  common-android)
    expect_mutation_killed common-attempt-limit mutate_common_attempt_limit
    expect_mutation_killed capability-replacement-invalidation mutate_capability_replacement_invalidation
    expect_mutation_killed capability-replacement-terminal mutate_capability_replacement_terminal
    expect_mutation_killed retry-interaction-filter mutate_retry_interaction_filter
    expect_mutation_killed child-frame-external-commit mutate_child_frame_external_commit
    expect_mutation_killed semantic-invalidation-terminal mutate_semantic_invalidation_terminal
    expect_mutation_killed bind-publication-order mutate_bind_publication_order
    expect_mutation_killed detach-publication-order mutate_detach_publication_order
    expect_mutation_killed retry-replacement-terminal mutate_retry_replacement_terminal
    expect_mutation_killed semantic-replacement-terminal mutate_semantic_replacement_terminal
    expect_mutation_killed binding-interaction-epoch-reset mutate_binding_interaction_epoch_reset
    expect_mutation_killed dispatch-raw-delta-capability-guard mutate_dispatch_raw_delta_capability_guard
    expect_mutation_killed nested-terminal-order mutate_nested_terminal_order
    expect_mutation_killed pager-request-authority mutate_pager_request_authority
    expect_mutation_killed drawer-request-authority mutate_drawer_request_authority
    expect_mutation_killed pager-delayed-claim-fence mutate_pager_delayed_claim_fence
    expect_mutation_killed drawer-delayed-claim-fence mutate_drawer_delayed_claim_fence
    expect_mutation_killed pager-null-delayed-claim-fence mutate_pager_null_delayed_claim_fence
    expect_mutation_killed drawer-null-delayed-claim-fence mutate_drawer_null_delayed_claim_fence
    expect_mutation_killed binding-reentrant-alignment-cleanup mutate_binding_reentrant_alignment_cleanup
    expect_mutation_killed pager-request-replacement-order mutate_pager_request_replacement_order
    expect_mutation_killed drawer-request-replacement-order mutate_drawer_request_replacement_order
    expect_mutation_killed state-held-scroll-write-retry-extraction mutate_state_held_scroll_write_retry_extraction
    expect_mutation_killed state-held-offset-delta-retry-extraction mutate_state_held_offset_delta_retry_extraction
    expect_mutation_killed bind-native-prepare-publication mutate_bind_native_prepare_publication
    expect_mutation_killed detach-native-prepare-publication mutate_detach_native_prepare_publication
    expect_mutation_killed bound-reuse-native-prepare-publication mutate_bound_reuse_native_prepare_publication
    expect_mutation_killed attempt-terminal-consumption mutate_attempt_terminal_consumption
    expect_mutation_killed zero-epoch-rejection mutate_zero_epoch_rejection
    expect_mutation_killed stale-native-event-rejection mutate_stale_native_event_rejection
    expect_mutation_killed scale-native-interaction-epoch mutate_scale_native_interaction_epoch
    expect_mutation_killed scale-layout-revision mutate_scale_layout_revision
    expect_mutation_killed scale-inset-revision mutate_scale_inset_revision
    expect_mutation_killed animated-offset-already-satisfied-phase mutate_animated_offset_already_satisfied_phase
    expect_mutation_killed legacy-animated-offset-already-satisfied-phase mutate_legacy_animated_offset_already_satisfied_phase
    expect_mutation_killed animated-inset-already-satisfied-phase mutate_animated_inset_already_satisfied_phase
    expect_mutation_killed animated-phase-restore-owner mutate_animated_phase_restore_owner
    expect_mutation_killed preinstall-phase-owner-rollback mutate_preinstall_phase_owner_rollback
    expect_mutation_killed preinstall-phase-owner-rollback-cas mutate_preinstall_phase_owner_rollback_cas
    expect_mutation_killed animated-phase-provenance mutate_animated_phase_provenance
    expect_mutation_killed immediate-phase-native-authority mutate_immediate_phase_native_authority
    expect_mutation_killed phase-event-operation-identity mutate_phase_event_operation_identity
    expect_mutation_killed scroll-end-operation-identity mutate_scroll_end_operation_identity
    expect_mutation_killed installed-phase-rollback-boundary mutate_installed_phase_rollback_boundary
    expect_mutation_killed installed-terminal-owner-recording mutate_installed_terminal_owner_recording
    expect_mutation_killed preinstall-immediate-phase-witness mutate_preinstall_immediate_phase_witness
    expect_mutation_killed list-reuse-tokenless-restore mutate_list_reuse_tokenless_restore
    expect_mutation_killed android-edge-barrier mutate_android_edge_barrier
    expect_mutation_killed android-preinstall-authority mutate_android_preinstall_authority
    ;;
  ios)
    expect_mutation_killed ios-animation-current-check mutate_ios_animation_current_check
    expect_mutation_killed ios-active-operation-scroll-end mutate_ios_active_operation_scroll_end
    expect_mutation_killed ios-direct-inset-scroll-end mutate_ios_direct_inset_scroll_end
    expect_mutation_killed ios-terminal-source-identity mutate_ios_terminal_source_identity
    ;;
  ohos)
    expect_mutation_killed ohos-current-identity mutate_ohos_current_identity
    expect_mutation_killed ohos-preinstall-authority mutate_ohos_preinstall_authority
    expect_mutation_killed ohos-inset-correction-stop mutate_ohos_inset_correction_stop
    expect_mutation_killed ohos-native-motion-expected-gate mutate_ohos_native_motion_expected_gate
    expect_mutation_killed ohos-active-axis-terminal-xy mutate_ohos_active_axis_terminal_xy
    expect_mutation_killed ohos-replacement-stop-fence-policy mutate_ohos_replacement_stop_fence_policy
    expect_mutation_killed ohos-replacement-stop-fence-coalescing mutate_ohos_replacement_stop_fence_coalescing
    expect_mutation_killed ohos-replacement-stop-fence-arm mutate_ohos_replacement_stop_fence_arm
    expect_mutation_killed ohos-replacement-stop-fence-consume mutate_ohos_replacement_stop_fence_consume
    expect_mutation_killed ohos-replacement-stop-fence-reset mutate_ohos_replacement_stop_fence_reset
    copy_ohos_metadata
    (
      cd "$worktree"
      OHOS_SDK_HOME="${OHOS_SDK_HOME:?}" \
        DEVECO_SDK_HOME="${DEVECO_SDK_HOME:?}" \
        tools/ohos-renderer-tests/run-arm64-direct-link.sh \
          "$worktree/build/ohos-mutation-restored-link"
    )
    ;;
esac

test "$(git -C "$source_root" rev-parse HEAD)" = "$source_head"
test "$(git -C "$source_root" rev-parse HEAD^{tree})" = "$source_tree"
git -C "$source_root" status --porcelain=v1 -z > "$worktree_parent/source-status-after"
cmp "$source_status" "$worktree_parent/source-status-after"
echo "mutation group PASS: $group"
