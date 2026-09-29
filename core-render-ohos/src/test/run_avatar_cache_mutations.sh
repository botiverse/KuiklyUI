#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
manager="$repo_root/core-render-ohos/src/main/ets/utils/KRAvatarCacheManager.ets"
runtime="$repo_root/core-render-ohos/src/main/ets/utils/KRAvatarCacheRuntime.ets"
model_header="$repo_root/core-render-ohos/src/main/cpp/libohos_render/expand/modules/cache/KRAvatarImageModel.h"
focused="$repo_root/core-render-ohos/src/test/run_avatar_cache_contract_test.sh"
cpp_fixture="$repo_root/core-render-ohos/src/test/cpp/run_avatar_image_model_test.sh"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kuikly-avatar-mutations.XXXXXX")"
mutation_log_dir="${AVATAR_MUTATION_LOG_DIR:-}"
if [[ -n "$mutation_log_dir" ]]; then
  mkdir -p "$mutation_log_dir"
  : >"$mutation_log_dir/mutation-matrix.log"
fi
cp "$manager" "$tmp_dir/manager"
cp "$runtime" "$tmp_dir/runtime"
cp "$model_header" "$tmp_dir/model_header"

restore() {
  cp "$tmp_dir/manager" "$manager"
  cp "$tmp_dir/runtime" "$runtime"
  cp "$tmp_dir/model_header" "$model_header"
}
trap 'restore; rm -rf "$tmp_dir"' EXIT

record_line() {
  local line="$1"
  echo "$line"
  if [[ -n "$mutation_log_dir" ]]; then
    printf '%s\n' "$line" >>"$mutation_log_dir/mutation-matrix.log"
  fi
}

expect_behavior_red() {
  local name="$1"
  local marker="$2"
  shift 2
  local log="$tmp_dir/$name.log"
  if "$@" >"$log" 2>&1; then
    echo "mutation unexpectedly GREEN: $name" >&2
    exit 1
  fi
  # Hvigor can stop after UnitTestArkTS in GenerateUnitTestResult with code 00308018 before
  # emitting Hypium assertions. That is infrastructure NOT RUN, never mutation evidence.
  if ! grep -F "$marker" "$log" >/dev/null && grep -F 'Error Code: 00308018' "$log" >/dev/null; then
    if "$@" >"$log" 2>&1; then
      echo "mutation unexpectedly GREEN after GenerateUnitTestResult retry: $name" >&2
      exit 1
    fi
  fi
  if ! grep -F "$marker" "$log" >/dev/null; then
    echo "mutation missed target behavior marker: $name ($marker)" >&2
    cat "$log" >&2
    exit 1
  fi
  if grep -E 'ArkTS Compiler Error|COMPILE RESULT:FAIL|unused parameter.*-Werror|syntax error|command not found|No such file or directory|execute timeout|timed out|missing fixture' "$log" >/dev/null; then
    echo "mutation failed in compile/setup instead of target behavior: $name" >&2
    cat "$log" >&2
    exit 1
  fi
  if [[ -n "$mutation_log_dir" ]]; then
    cp "$log" "$mutation_log_dir/$name.log"
  fi
  record_line "mutation behavior RED: $name"
  restore
}

# Typed descriptors must never use a pre-owner decoded hit.
perl -0pi -e 's/return !model\.valid;/return model.valid ? true : !model.valid;/' "$model_header"
expect_behavior_red decoded_hit_bypass 'Assertion failed' "$cpp_fixture"

# The native model parser must reject requests that lack pre-fetch caller authority.
perl -0pi -e 's/KRIsOpaqueAvatarKey\(caller_authority\) \&\&/true \&\&/' "$model_header"
expect_behavior_red caller_authority_model_required 'Assertion failed' "$cpp_fixture"

# Stale-revalidate must execute fetch even when stale bytes rendered.
perl -0pi -e "s/await KRAvatarCacheManager\.fetchAndStage\(context, descriptor, true, token\);/KRAvatarCacheManager.completeOperation(context, token, null, 'rendered');/" "$manager"
expect_behavior_red stale_revalidation \
  'Error in keeps stale follower immediate during pending failure and rejects mixed follower policy' "$focused"

# Older finalize must be rejected after a newer accepted generation.
perl -0pi -e 's/compareGeneration\(descriptor\.commitGeneration, accepted\) < 0/compareGeneration(descriptor.commitGeneration, accepted) > 0/' "$manager"
expect_behavior_red stale_generation \
  'Error in rolls back a post-rename delayed finalize without late promotion and permits retry' "$focused"

# Retirement must remove the admission and apply the epoch fence even before the first decode resumes.
perl -0pi -e 's/private static isOperationTokenLive\(token: KRAvatarFetchToken\): boolean \{[\s\S]*?\n  \}/private static isOperationTokenLive(_token: KRAvatarFetchToken): boolean {\n    return true;\n  }/' "$manager"
perl -0pi -e 's/if \(KRAvatarCacheManager\.activeFetches\.get\(operation\) !== token\) return;/if (false) return;/' "$manager"
expect_behavior_red retirement_fence \
  'Error in admits before initial decode and fences clear owner and subject retirement' "$focused"

# Tokenless waiters, stored receipts, and accepted-generation watermarks are retirement state.
perl -0pi -e "s/const retired = KRAvatarCacheManager\.payloadWithoutContext\('retired'\);/const retired = KRAvatarCacheManager.payloadWithoutContext('success');/" "$manager"
expect_behavior_red tokenless_waiter_retirement \
  'Error in settles tokenless waiters once and requires a new lifecycle before a second await resolves' "$focused"

perl -0pi -e 's/KRAvatarCacheManager\.resultByOperation\.set\(operation, retired\);/if (!KRAvatarCacheManager.resultByOperation.has(operation)) KRAvatarCacheManager.resultByOperation.set(operation, retired);/' "$manager"
expect_behavior_red stored_receipt_retirement \
  'Error in settles tokenless waiters once and requires a new lifecycle before a second await resolves' "$focused"

perl -0pi -e 's/KRAvatarCacheManager\.acceptedGenerationByIdentity\.delete\(identity\);/KRAvatarCacheManager.acceptedGenerationByIdentity.get(identity);/' "$manager"
expect_behavior_red accepted_generation_retirement \
  'Error in settles tokenless waiters once and requires a new lifecycle before a second await resolves' "$focused"

# Bridge catch must never recurse through managed accounting.
perl -0pi -e "s/const fallback = KRAvatarCacheManager\.payloadWithoutContext\('error'\);/const fallback = KRAvatarCacheManager.payload('error', context);/" "$manager"
expect_behavior_red context_free_bridge_error \
  'Error in returns one context-free bridge error when retirement accounting stat fails' "$focused"

# Delivering a waiter must not retain a duplicate replay receipt.
perl -0pi -e 's/(private static settleOperation[\s\S]*?\} else \{\n      )KRAvatarCacheManager\.resultByOperation\.delete\(operation\);/${1}KRAvatarCacheManager.resultByOperation.set(operation, payload);/' "$manager"
expect_behavior_red exactly_once_waiter_receipt \
  'Error in keeps an old follower bound to its settled lifecycle across a new leader' "$focused"

# Unlink failure cannot be acknowledged as successful deletion.
perl -0pi -e 's/(private static removeIfPresent\(path: string\): boolean \{[\s\S]*?catch \(error\) \{[\s\S]*?)return false;/${1}return true;/' "$manager"
expect_behavior_red unlink_fail_closed \
  'Error in fails closed on retire and clear unlink failures' "$focused"

# Only a verified not-found stat is absence; listed-file stat errors cannot disappear from accounting.
perl -0pi -e 's/if \(!KRAvatarCacheManager\.runtime\.exists\(path\)\) return true;/try { if (!KRAvatarCacheManager.runtime.exists(path)) return true; } catch (_error) { return true; }/' "$manager"
expect_behavior_red exists_stat_fail_closed \
  'Error in settles active clear owner and subject retirement before fallible stage cleanup' "$focused"

perl -0pi -e 's/const stat: KRAvatarFileStat = KRAvatarCacheManager\.runtime\.stat\(path\);/const stat: KRAvatarFileStat = { size: 0, mtime: 0 };/' "$manager"
expect_behavior_red listed_stat_fail_closed \
  'Error in settles protocol once before render when unrelated accounting stat fails' "$focused"

# A non-throwing no-op or malformed rename cannot be accepted or mutate decoded state.
perl -0pi -e 's/if \((!stageAbsent \|\| !finalPresent \|\| finalSize !== incomingSize \|\| !finalDecodable)\)/if (($1) \&\& descriptor.commitGeneration.length < 0)/' "$manager"
expect_behavior_red promotion_postcondition \
  'Error in fails closed on existence stat and no-op or malformed rename postconditions' "$focused"

# A corrupt final cannot accept 304 or reuse validators.
perl -0pi -e 's/if \(allowValidators && finalUsable\)/if (allowValidators)/' "$manager"
perl -0pi -e 's/fetchAndStage\(context, descriptor, finalUsable, token\)/fetchAndStage(context, descriptor, true, token)/' "$manager"
expect_behavior_red corrupt_final_304 \
  'Error in rejects corrupt-final 304 and retries without validators' "$focused"

# Per-entry and avatar-only total budgets both retain executable teeth.
perl -0pi -e 's/result\.body\.byteLength > KRAvatarCacheManager\.MAX_ENTRY_BYTES/result.body.byteLength > 67108864/' "$manager"
expect_behavior_red max_entry_budget \
  'Error in rejects an oversized entry before staging' "$focused"

perl -0pi -e 's/if \(total <= KRAvatarCacheManager\.MAX_MANAGED_BYTES\) return true;/if (total <= KRAvatarCacheManager.MAX_MANAGED_BYTES * 10) return true;/' "$manager"
expect_behavior_red avatar_total_budget \
  'Error in evicts only proven avatar-managed bytes and fails closed if budget unlink fails' "$focused"

# Eviction must invalidate all generation authority for the bytes it physically removes.
perl -0pi -e 's/KRAvatarCacheManager\.invalidateIdentityAuthority\(identity\);/KRAvatarCacheManager.accessByIdentity.delete(identity);/' "$manager"
expect_behavior_red eviction_invalidates_identity_authority \
  'Error in invalidates evicted identity authority so a higher generation can recover' "$focused"

# Authority must be invalid before unlink can succeed and its post-stat can fail.
perl -0pi -e 's/(private static deleteManagedFile[\s\S]*?)KRAvatarCacheManager\.invalidateIdentityAuthority\(identity\);/${1}/' "$manager"
perl -0pi -e 's/if \(stagesDeleted && !KRAvatarCacheManager\.removeIfPresent\(entry\.path\)\) success = false;/if (stagesDeleted && !KRAvatarCacheManager.removeIfPresent(entry.path)) return false;\n      KRAvatarCacheManager.invalidateIdentityAuthority(identity);/' "$manager"
expect_behavior_red eviction_post_unlink_stat_authority \
  'Error in invalidates authority before unlink post-stat failure and admits a lower generation' "$focused"

# A failed native decoded retirement is a zero-effect precondition, not a half-retired identity.
perl -0pi -e 's/if \(!KRAvatarCacheManager\.runtime\.retireDecoded\(entry\.bytesKey\)\) \{/if (false) {/' "$manager"
expect_behavior_red eviction_decoded_retire_atomicity \
  'Error in invalidates evicted identity authority so a higher generation can recover' "$focused"

# The persistent marker is a precondition: no marker means no decoded or identity retirement.
perl -0pi -e 's/if \(!quarantineAlreadyPresent && !KRAvatarCacheManager\.ensureQuarantineMarker\(quarantinePath\)\) return false;/if (false) return false;/' "$manager"
expect_behavior_red quarantine_marker_durable_precondition \
  'Error in invalidates authority before unlink post-stat failure and admits a lower generation' "$focused"

# Residual finals cannot be decoded or revalidated while their identity marker exists.
perl -0pi -e "s/if \(quarantined \&\& descriptor\.readMode !== 'fetch'\)/if (false)/" "$manager"
expect_behavior_red quarantine_render_gate \
  'Error in quarantines residual bytes after unlink failure until an authorized replacement commits' "$focused"

# Merely observing a marker is not authority to clear it while residual bytes remain.
perl -0pi -e 's/if \(!KRAvatarCacheManager\.runtime\.exists\(quarantinePath\)\) return false;/if (KRAvatarCacheManager.runtime.exists(quarantinePath)) return false;/' "$manager"
expect_behavior_red quarantine_no_unauthorized_clear \
  'Error in settles fresh, stale-suppressed, and suppressed modes without HTTP' "$focused"

# A committed replacement must eventually clear the marker; cleanup failure remains retryable.
perl -0pi -e 's/if \(!KRAvatarCacheManager\.removeQuarantineMarker\(\n      KRAvatarCacheManager\.quarantinePathForFinal\(token\.finalPath\),\n      false\n    \)\) return false;/if (false) return false;/' "$manager"
expect_behavior_red quarantine_commit_cleanup \
  'Error in renders stale immediately, revalidates, finalizes, and exposes new accepted bytes' "$focused"

# An already-suspended decode cannot outlive the eviction epoch and bypass quarantine.
perl -0pi -e 's/private static isOperationTokenLive\(token: KRAvatarFetchToken\): boolean \{[\s\S]*?\n  \}/private static isOperationTokenLive(_token: KRAvatarFetchToken): boolean {\n    return true;\n  }/' "$manager"
expect_behavior_red quarantine_late_decode_epoch \
  'Error in keeps late decode callbacks fenced while residual bytes are quarantined' "$focused"

# Eviction must rewrite every indexed stored terminal before its render path is unlinked.
perl -0pi -e 's/private static retireIdentityOperationState\(identity: string\): void \{[\s\S]*?\n  \}/private static retireIdentityOperationState(_identity: string): void {\n    return;\n  }/' "$manager"
expect_behavior_red eviction_retires_stored_terminal \
  'Error in retires an evicted stored terminal instead of replaying a removed render path' "$focused"

# A suspended decode is cancelled and settled before eviction removes its final bytes.
perl -0pi -e "s/let success = KRAvatarCacheManager\.cancelActiveOperations\(matches, context, 'retired'\);/let success = KRAvatarCacheManager.cancelActiveOperations((_descriptor: KRAvatarDescriptor): boolean => false, context, 'retired');/" "$manager"
expect_behavior_red eviction_fences_suspended_decode \
  'Error in keeps late decode callbacks fenced while residual bytes are quarantined' "$focused"

# Finalize must re-admit actual staged bytes, not only the HTTP body.
perl -0pi -e 's/incomingSize > KRAvatarCacheManager\.MAX_ENTRY_BYTES/incomingSize > KRAvatarCacheManager.MAX_ENTRY_BYTES * 10/' "$manager"
expect_behavior_red finalize_actual_stage_cap \
  'Error in revalidates actual stage size at zero exact-cap and cap-plus-one before promotion' "$focused"

# Post-decode epoch/identity liveness is the retirement resurrection fence.
perl -0pi -e 's/if \(!KRAvatarCacheManager\.isFinalizeTokenLive\(reservation\)\)/if (false)/g' "$manager"
expect_behavior_red finalize_post_await_epoch \
  'Error in rolls back a post-rename delayed finalize without late promotion and permits retry' "$focused"

# An installed transaction owns the identity until shared ack, rollback, or bounded expiry.
perl -0pi -e 's/private static finalizeIdentityAvailable\(identity: string\): boolean \{[\s\S]*?\n  \}/private static finalizeIdentityAvailable(_identity: string): boolean {\n    return true;\n  }/' "$manager"
expect_behavior_red finalize_identity_monotonic \
  'Error in keeps g5 authoritative when installed g7 blocks g9 and g7 later fails' "$focused"

# ArkTS and C++ must reject the same read-mode values.
perl -0pi -e "s/return value === 'render_fresh' \|\| value === 'render_stale_revalidate' \|\|\n      value === 'render_stale_suppressed' \|\| value === 'fetch' \|\| value === 'suppressed';/return value.length > 0;/" "$manager"
expect_behavior_red arkts_read_mode_domain \
  'Error in keeps ArkTS model and bridge read modes identical to the native five-value domain' "$focused"

# Protocol terminal must be formed and published before single-shot render delivery.
perl -0pi -e 's#KRAvatarCacheManager\.settleLifecycle\(lifecycle, payload\);\n    KRAvatarCacheManager\.deliverRenderOnce\(token, payload\.status === '\''error'\'' \? null : renderPath\);#KRAvatarCacheManager.deliverRenderOnce(token, payload.status === '\''error'\'' ? null : renderPath);\n    KRAvatarCacheManager.settleLifecycle(lifecycle, payload);#' "$manager"
expect_behavior_red callback_before_terminal \
  'Error in settles protocol once before render when unrelated accounting stat fails' "$focused"

# Accounting failure must use a context-free fallback rather than recursively scanning managed files.
perl -0pi -e 's/(private static safePayload[\s\S]*?catch \(error\) \{[\s\S]*?)const fallback = KRAvatarCacheManager\.payloadWithoutContext\('\''error'\''\);/${1}const fallback = KRAvatarCacheManager.payload('\''error'\'', context);/' "$manager"
expect_behavior_red accounting_backed_terminal_catch \
  'Error in settles protocol once before render when unrelated accounting stat fails' "$focused"

# One throwing waiter cannot stop later waiters from receiving the same terminal.
perl -0pi -e 's/private static deliverProtocolWaiter\(\n    waiter: KRAvatarResultWaiter,\n    payload: KRAvatarCacheBridgePayload\n  \): boolean \{[\s\S]*?\n  \}/private static deliverProtocolWaiter(\n    waiter: KRAvatarResultWaiter,\n    payload: KRAvatarCacheBridgePayload\n  ): boolean {\n    waiter(payload);\n    return true;\n  }/' "$manager"
expect_behavior_red fail_fast_waiter_iteration \
  'Error in isolates the first throwing waiter and settles the second waiter once' "$focused"

# Stored-result replay must use the same exception isolation as deferred waiter settlement.
perl -0pi -e 's/KRAvatarCacheManager\.deliverProtocolWaiter\(callback as KRAvatarResultWaiter, existing\);/callback(existing);/' "$manager"
expect_behavior_red stored_receipt_callback_isolation \
  'Error in isolates a throwing stored-receipt callback without bridge redelivery' "$focused"

# Render fanout must not be registered as a protocol waiter or consume the late receipt.
perl -0pi -e 's/KRAvatarCacheManager\.addRenderJoiner\(token\);/KRAvatarCacheManager.addWaiter(token.operation, (_payload: KRAvatarCacheBridgePayload): void => {\n      KRAvatarCacheManager.deliverRenderOnce(token, null);\n    });/' "$manager"
expect_behavior_red render_protocol_waiter_conflation \
  'Error in fans out a deferred 200 render without consuming the late protocol receipt' "$focused"

# Per-operation leader admission must happen before the first suspended decoder preflight.
perl -0pi -e "s/const leader = KRAvatarCacheManager\.admitOperation\(operation, token\);/const delayAdmission = descriptor.readMode === 'fetch' \&\& KRAvatarCacheManager.currentLifecycleByOperation.get(operation) === undefined;\n      const leader = delayAdmission ? token : KRAvatarCacheManager.admitOperation(operation, token);/" "$manager"
perl -0pi -e 's/if \(!KRAvatarCacheManager\.isOperationTokenLive\(token\)\) return;\n      if \(!isLeader\) \{/if (!KRAvatarCacheManager.isOperationTokenLive(token)) return;\n      if (descriptor.readMode === '\''fetch'\'' && token.lifecycleId === 0) {\n        const delayedLeader = KRAvatarCacheManager.admitOperation(operation, token);\n        isLeader = delayedLeader === token;\n        if (!isLeader) {\n          KRAvatarCacheManager.deliverTerminalOrJoin(token);\n          return;\n        }\n      }\n      if (!isLeader) {/' "$manager"
expect_behavior_red leader_admission_after_initial_decode \
  'Error in admits one fetch leader before initial decode and publishes one terminal' "$focused"

# A follower must run its own read-mode render preflight instead of blindly waiting on leader terminal.
perl -0pi -e 's/(isLeader = leader === token;)/${1}\n      if (!isLeader) {\n        KRAvatarCacheManager.addRenderJoiner(token);\n        return;\n      }/' "$manager"
expect_behavior_red follower_blind_join_before_preflight \
  'Error in publishes one terminal for delayed fresh stale-suppressed and suppressed followers' "$focused"

# A follower must consult the lifecycle it joined, never the current lifecycle for the same operation key.
perl -0pi -e 's/(private static deliverTerminalOrJoin\(token: KRAvatarFetchToken\): void \{\n    )const lifecycle = KRAvatarCacheManager\.lifecycleById\.get\(token\.lifecycleId\);/${1}const lifecycle = KRAvatarCacheManager.currentLifecycleByOperation.get(token.operation);/' "$manager"
expect_behavior_red follower_cross_lifecycle_rebind \
  'Error in keeps an old follower bound to its settled lifecycle across a new leader' "$focused"

# Retirement callbacks cannot admit a half-live replacement lifecycle inside the retiring scope.
perl -0pi -e 's/private static isDescriptorRetiring\(descriptor: KRAvatarDescriptor\): boolean \{[\s\S]*?\n  \}/private static isDescriptorRetiring(_descriptor: KRAvatarDescriptor): boolean {\n    return false;\n  }/' "$manager"
expect_behavior_red retirement_callback_reentry \
  'Error in rejects retirement callback reentry without leaving a half-live lifecycle' "$focused"

# Leaving an inner retirement frame must decrement depth rather than collapsing the outer guard.
perl -0pi -e 's/private static leaveRetirementScope\(scope: string\): void \{[\s\S]*?\n  \}/private static leaveRetirementScope(scope: string): void {\n    KRAvatarCacheManager.retirementDepthByScope.delete(scope);\n  }/' "$manager"
expect_behavior_red retirement_nested_depth_collapse \
  'Error in keeps owner retirement admission closed across nested same-scope retirement' "$focused"

# Finalize admission must remain closed after retirement bumped epochs and took its snapshot.
perl -0pi -e 's/    if \(KRAvatarCacheManager\.isDescriptorRetiring\(descriptor\)\) \{\n      return KRAvatarCacheManager\.payload\('\''error'\'', context, new KRAvatarEmptyHeaders\(\), '\''finalize_retired'\''\);\n    \}\n//' "$manager"
expect_behavior_red retirement_callback_finalize_admission \
  'Error in rejects callback-reentrant finalize during owner subject and global retirement' "$focused"

# Joined 304 renders must receive the real final path, not a success-only stage mapping.
perl -0pi -e "s/const joinedPath = payload\.status === 'error' \? null : renderPath;/const joinedPath = payload.status === 'success' ? renderPath : null;/" "$manager"
expect_behavior_red joined_not_modified_path \
  'Error in fans out a deferred valid-final 304 path without consuming the late protocol receipt' "$focused"

# Mandatory NotModified finalize is an accepted no-byte-change commit, never decoded rollback.
perl -0pi -e 's/(\} else if \(outcome === '\''not_modified'\''\) \{[\s\S]*?)(      KRAvatarCacheManager\.acceptedGenerationByIdentity\.set\(identity, descriptor\.commitGeneration\);)/${1}      KRAvatarCacheManager.runtime.retireDecoded(descriptor.nativeBytesKey, descriptor.commitGeneration);\n$2/' "$manager"
expect_behavior_red not_modified_finalize_preserves_decoded \
  'Error in keeps a 304 decoded generation drawable through mandatory finalize' "$focused"

# A decodable final is insufficient: 304 admission also requires the exact production PixelMap.
perl -0pi -e 's/if \(!KRAvatarCacheManager\.runtime\.retainDecoded\([\s\S]*?\) \|\| !KRAvatarCacheManager\.runtime\.hasDecoded\([\s\S]*?\)\) \{/if (!KRAvatarCacheManager.runtime.promoteDecoded(\n            descriptor.nativeBytesKey,\n            descriptor.commitGeneration,\n            descriptor.callerAuthority\n          )) {/' "$manager"
expect_behavior_red not_modified_requires_pixelmap \
  'Error in rejects a 304 generation without a production decoded PixelMap' "$focused"

# Rollback must restore the matching post-rename transaction before acknowledging cancellation.
perl -0pi -e 's/(private static rollback[\s\S]*?\} else \{\n        )if \(!KRAvatarCacheManager\.restoreFinalizeTransaction\(reservation\)\)/${1}if (false)/' "$manager"
expect_behavior_red rollback_restores_post_rename_transaction \
  'Error in rolls back a post-rename delayed finalize without late promotion and permits retry' "$focused"

# Decoded-promotion failure must restore prior bytes/decoded/watermark state.
perl -0pi -e 's/(if \(!KRAvatarCacheManager\.runtime\.retainDecoded\([\s\S]*?\)\) \{\n\s*)if \(!KRAvatarCacheManager\.restoreFinalizeTransaction\(reservation\)\)/${1}if (false)/' "$manager"
expect_behavior_red decoded_promotion_failure_restores_transaction \
  'Error in restores prior bytes decoded generation and watermark after decoded promotion failure' "$focused"

# Finalize success is only prepared until a separate shared acknowledgement commits cleanup.
perl -0pi -e 's/(reservation\.preparedForSharedAck = true;)/$1\n      KRAvatarCacheManager.finalizeByIdentity.delete(identity);/' "$manager"
expect_behavior_red finalize_journal_survives_callback \
  'Error in renders stale immediately, revalidates, finalizes, and exposes new accepted bytes' "$focused"

# NotModified must retain the same acknowledgement journal as byte-changing finalize.
perl -0pi -e 's/(\} else if \(outcome === '\''not_modified'\''\) \{[\s\S]*?receipt\.finalized = true;\n      )(reservation\.preparedForSharedAck = true;)/${1}KRAvatarCacheManager.discardFinalizeReservation(reservation);\n      ${2}/' "$manager"
expect_behavior_red not_modified_journal_survives_callback \
  'Error in restores prior decoded generation and watermark after a lost NotModified acknowledgement' "$focused"

# NotModified finalize must consume a terminal receipt from a real matching 304 lifecycle.
perl -0pi -e "s/const receipt = KRAvatarCacheManager\.finalizeReceiptByOperation\.get\(operation\);\n      if \(receipt === undefined \|\| !KRAvatarCacheManager\.isFinalizeReceiptLive\(\n        receipt,\n        descriptor,\n        'not_modified',\n        operationReceipt\n      \)\) \{/const receipt = KRAvatarCacheManager.finalizeReceiptByOperation.get(operation) ?? { lifecycleId: 0, operationReceipt: operationReceipt, identity: identity, generation: descriptor.commitGeneration, callerAuthority: descriptor.callerAuthority, status: 'not_modified', globalEpoch: 0, ownerEpoch: 0, subjectEpoch: 0, identityEpoch: 0, finalized: false };\n      if (false) {/" "$manager"
expect_behavior_red not_modified_lifecycle_receipt \
  'Error in invalidates a 304 receipt when a new same-operation lifecycle terminates differently' "$focused"

# Successful bytes need their own real terminal receipt, not merely a staged filename.
perl -0pi -e "s/payload\.status === 'success' \|\| payload\.status === 'not_modified'/payload.status === 'not_modified'/" "$manager"
expect_behavior_red successful_bytes_terminal_receipt \
  'Error in rejects callback-reentrant finalize during owner subject and global retirement' "$focused"

# A successful finalize cannot bypass receipt identity/generation/lifecycle/epoch admission.
perl -0pi -e "s/const receipt = KRAvatarCacheManager\.finalizeReceiptByOperation\.get\(operation\);\n      if \(receipt === undefined \|\| !KRAvatarCacheManager\.isFinalizeReceiptLive\(\n        receipt,\n        descriptor,\n        'success',\n        operationReceipt\n      \)\) \{[\s\S]*?\n      \}\n      if \(receipt\.finalized\)/const receipt: KRAvatarFinalizeReceipt = KRAvatarCacheManager.finalizeReceiptByOperation.get(operation) ?? { lifecycleId: 0, operationReceipt: operationReceipt, identity: identity, generation: descriptor.commitGeneration, callerAuthority: descriptor.callerAuthority, status: 'success', globalEpoch: 0, ownerEpoch: 0, subjectEpoch: 0, identityEpoch: 0, finalized: false };\n      if (receipt.finalized)/" "$manager"
expect_behavior_red successful_bytes_receipt_admission \
  'Error in invalidates a completed pre-eviction stage and requires a new fetch receipt before replacement' "$focused"

# Budget retirement removes every completed stage for the evicted identity before marker cleanup.
perl -0pi -e 's/const stagesDeleted = KRAvatarCacheManager\.deleteIdentityStages\(entry\);/const stagesDeleted = true;/' "$manager"
expect_behavior_red budget_identity_stage_cleanup \
  'Error in invalidates a completed pre-eviction stage and requires a new fetch receipt before replacement' "$focused"

# A budget retry must retain final-path discovery until a failed completed-stage unlink succeeds.
perl -0pi -e 's/const stagesDeleted = KRAvatarCacheManager\.deleteIdentityStages\(entry\);\n      if \(!stagesDeleted\) success = false;\n      if \(stagesDeleted && !KRAvatarCacheManager\.removeIfPresent\(entry\.path\)\) success = false;/const stagesDeleted = KRAvatarCacheManager.deleteIdentityStages(entry);\n      if (!KRAvatarCacheManager.removeIfPresent(entry.path)) success = false;\n      if (!stagesDeleted) success = false;/' "$manager"
expect_behavior_red budget_stage_retry_discovery \
  'Error in keeps a budget identity enumerable until failed completed-stage cleanup can retry' "$focused"

# A retirement retry may withdraw only the marker it created in that attempt, never a persisted quarantine.
perl -0pi -e 's/if \(!quarantineAlreadyPresent\) KRAvatarCacheManager\.removeIfPresent\(quarantinePath\);/KRAvatarCacheManager.removeIfPresent(quarantinePath);/' "$manager"
expect_behavior_red pre_existing_quarantine_retry \
  'Error in preserves a pre-existing quarantine when a repeated budget retirement cannot retire decoded state' "$focused"

# A rollback from an old lifecycle/attempt cannot select a new same-generation transaction.
perl -0pi -e 's/(private static rollback\([\s\S]*?if \()!KRAvatarCacheManager\.commandMatchesFinalizeToken\(command, reservation\)/${1}false/' "$manager"
expect_behavior_red rollback_exact_finalize_receipt \
  'Error in makes an old rollback a no-op against a new same-generation finalize transaction' "$focused"

# An ack from an old lifecycle/attempt cannot commit new bytes or clear their quarantine.
perl -0pi -e 's/(private static ackFinalize\([\s\S]*?reservation\.descriptor\.commitGeneration !== descriptor\.commitGeneration \|\|\n      )!KRAvatarCacheManager\.commandMatchesFinalizeToken\(command, reservation\)/${1}false/' "$manager"
expect_behavior_red ack_exact_finalize_receipt \
  'Error in makes an old ack a no-op against a new same-generation quarantine recovery' "$focused"

# A failed stage unlink never authorizes clearing the persistent quarantine marker.
perl -0pi -e "s/if \(!KRAvatarCacheManager\.deleteStageFile\(path\)\) return false;/if (!KRAvatarCacheManager.deleteStageFile(path)) return (path.includes('.g300.') || path.includes('.g301.') || path.includes('.g302.')) ? KRAvatarCacheManager.removeIfPresent(quarantinePath) : false;/" "$manager"
expect_behavior_red stage_cleanup_failure_keeps_quarantine \
  'Error in preserves stage quarantine on owner subject and global cleanup failure' "$focused"

# Owner/subject/global retirement must use tombstone-preserving stage cleanup.
perl -0pi -e 's/KRAvatarCacheManager\.deleteRetiredStageFile\(path\)/KRAvatarCacheManager.deleteStageFile(path)/g' "$manager"
expect_behavior_red scoped_stage_cleanup_uses_quarantine \
  'Error in preserves stage quarantine on owner subject and global cleanup failure' "$focused"

# A pre-reservation finalize must present the native receipt for the exact fetch lifecycle.
perl -0pi -e 's/receipt\.operationReceipt === operationReceipt &&/operationReceipt.length > 0 &&/' "$manager"
expect_behavior_red pre_reservation_finalize_operation_receipt \
  'Error in rejects an old same-generation finalize before replacement reservation' "$focused"

# Reservation-less rollback must not substitute the current lifecycle receipt for the caller receipt.
perl -0pi -e 's/(private static rollback[\s\S]*?receipt\.status,\n          )operationReceipt/${1}receipt.operationReceipt/' "$manager"
expect_behavior_red pre_reservation_rollback_operation_receipt \
  'Error in makes an old attempt-only rollback a no-op before replacement reservation' "$focused"

# A candidate may enter the ordinary final path only after its durable transaction fence exists.
perl -0pi -e 's/if \(!KRAvatarCacheManager\.ensureQuarantineMarker\(transactionQuarantinePath\)\) \{/if (!KRAvatarCacheManager.ensureQuarantineMarker(transactionQuarantinePath) \&\& descriptor.commitGeneration.length < 0) {/' "$manager"
expect_behavior_red candidate_install_requires_durable_fence \
  'Error in fences candidate installation when the durable transaction marker cannot be written' "$focused"

# Scoped retirement must quarantine listed physical finals before any fallible stat.
perl -0pi -e 's/if \(!KRAvatarCacheManager\.ensureQuarantineMarker\(quarantinePath\)\) return false;\n        KRAvatarCacheManager\.acquireQuarantineLease\(retirementScope, quarantinePath\);/if (false \&\& !KRAvatarCacheManager.ensureQuarantineMarker(quarantinePath)) return false;\n        KRAvatarCacheManager.acquireQuarantineLease(retirementScope, quarantinePath);/' "$manager"
expect_behavior_red scoped_stat_failure_keeps_prequarantine \
  'Error in keeps scoped-retirement quarantine when listed final stat fails' "$focused"

# A marker discovered during scoped prequarantine is pre-existing identity authority on retireDecoded failure.
perl -0pi -e 's/quarantineAlreadyPresent = KRAvatarCacheManager\.runtime\.exists\(quarantinePath\);/quarantineAlreadyPresent = false;/' "$manager"
expect_behavior_red scoped_decoded_failure_keeps_prequarantine \
  'Error in preserves a pre-existing quarantine when a repeated budget retirement cannot retire decoded state' "$focused"

# Shared-acked commit cleanup cannot remove a marker leased by its outer retirement scope.
perl -0pi -e 's/(private static commitFinalizeTransaction[\s\S]*?KRAvatarCacheManager\.quarantinePathForFinal\(token\.finalPath\),\n      )false/${1}true/' "$manager"
expect_behavior_red shared_acked_commit_respects_quarantine_lease \
  'Error in leases scoped quarantine across shared-acked commit before a listed final stat failure' "$focused"

# Unacked restore cleanup cannot remove a marker leased by its outer retirement scope.
perl -0pi -e 's/(private static restoreFinalizeTransaction[\s\S]*?removeQuarantineMarker\(quarantinePath, )false/${1}true/' "$manager"
expect_behavior_red unacked_restore_respects_quarantine_lease \
  'Error in leases scoped quarantine across unacked restore before a listed final stat failure' "$focused"

# Stored terminals and waiters are caller-authority scoped, not merely identity/generation scoped.
perl -0pi -e 's/:\$\{descriptor\.callerAuthority\}`;/`;/' "$manager"
expect_behavior_red late_waiter_caller_authority \
  'Error in prevents a late old waiter from borrowing a replacement terminal and operation receipt' "$focused"

# A failed pre-rename replacement may remove only a marker it created itself.
perl -0pi -e 's/reservation\.transactionMarkerCreated = !transactionMarkerExisted;/reservation.transactionMarkerCreated = true;/' "$manager"
expect_behavior_red pre_existing_marker_transaction_ownership \
  'Error in preserves a pre-existing retirement marker when replacement fails before its first rename' "$focused"

# Candidate stage names must remain exact-caller scoped across same-generation replacement.
perl -0pi -e 's/\.a\$\{descriptor\.callerAuthority\}\.stage/\.a\$\{descriptor.commitGeneration === '\''502'\'' ? descriptor.nativeBytesKey : descriptor.callerAuthority\}.stage/' "$manager"
expect_behavior_red same_generation_stage_caller_authority \
  'Error in keeps a same-generation replacement stage and decoded candidate after old suspended decode resumes' "$focused"

# A late old decode cleanup must retire only its own exact native candidate.
perl -0pi -e 's/(if \(!KRAvatarCacheManager\.isFetchTokenLive\(token\)\) \{\n\s*KRAvatarCacheManager\.removeIfPresent\(stagePath\);\n\s*KRAvatarCacheManager\.runtime\.retireDecoded\(\n\s*descriptor\.nativeBytesKey, descriptor\.commitGeneration, )descriptor\.callerAuthority/${1}descriptor.commitGeneration === '\''502'\'' ? '\'''\'' : descriptor.callerAuthority/g' "$manager"
expect_behavior_red same_generation_decoded_caller_authority \
  'Error in keeps a same-generation replacement stage and decoded candidate after old suspended decode resumes' "$focused"

# Generic stage retirement must preserve the live token backup after candidate-final unlink failure.
perl -0pi -e 's/if \(KRAvatarCacheManager\.finalizeOwnsStagePath\(path\)\) return false;/if (KRAvatarCacheManager.finalizeOwnsStagePath(path) \&\& !path.includes('\''.g541.'\'')) return false;/' "$manager"
expect_behavior_red candidate_unlink_retry_preserves_token_backup \
  'Error in preserves a token-owned backup after candidate unlink failure and completes retirement on retry' "$focused"

# The same token ownership fence must preserve backup bytes after backup-to-final rename failure.
perl -0pi -e 's/if \(KRAvatarCacheManager\.finalizeOwnsStagePath\(path\)\) return false;/if (KRAvatarCacheManager.finalizeOwnsStagePath(path) \&\& !path.includes('\''.g551.'\'')) return false;/' "$manager"
expect_behavior_red restore_rename_retry_preserves_token_backup \
  'Error in preserves a token-owned backup after restore rename failure and completes retirement on retry' "$focused"

# A different disk identity sharing the bytes key must still exercise the native broad-retirement cause.
perl -0pi -e 's/if \(!KRAvatarCacheManager\.runtime\.retireDecoded\(entry\.bytesKey\)\) \{/if (entry.partitionKey === '\''eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee'\'' ? false : !KRAvatarCacheManager.runtime.retireDecoded(entry.bytesKey)) {/' "$manager"
expect_behavior_red same_bytes_different_identity_broad_retire \
  'Error in aborts a shared-acked transaction after a same-bytes different-identity retirement removes decoded' "$focused"

# Ack must propagate native commit failure instead of reporting a false durable success.
perl -0pi -e 's/(private static ackFinalize[\s\S]*?recordFinalizeAcknowledgement\(reservation\);\n    )if \(!KRAvatarCacheManager\.commitFinalizeTransaction\(reservation\)\)/${1}if (reservation.descriptor.commitGeneration === '\''561'\'' ? false : !KRAvatarCacheManager.commitFinalizeTransaction(reservation))/' "$manager"
expect_behavior_red ack_propagates_commit_failure \
  'Error in aborts a shared-acked transaction after a same-bytes different-identity retirement removes decoded' "$focused"

# Terminal retirement must abort a shared-acked token when its exact decoded candidate is already gone.
perl -0pi -e 's/\} else if \(!KRAvatarCacheManager\.abortFinalizeTransaction\(token\)\) \{/} else if (token.descriptor.commitGeneration === '\''561'\'' ? true : !KRAvatarCacheManager.abortFinalizeTransaction(token)) {/' "$manager"
expect_behavior_red missing_decoded_shared_ack_terminal_abort \
  'Error in aborts a shared-acked transaction after a same-bytes different-identity retirement removes decoded' "$focused"

# Native-module teardown reaches the same exact-candidate-missing abort branch independently of disk retirement.
perl -0pi -e 's/\} else if \(!KRAvatarCacheManager\.abortFinalizeTransaction\(token\)\) \{/} else if (token.descriptor.commitGeneration === '\''571'\'' ? true : !KRAvatarCacheManager.abortFinalizeTransaction(token)) {/' "$manager"
expect_behavior_red module_teardown_missing_decoded_terminal_abort \
  'Error in aborts a shared-acked transaction after native module teardown removes every decoded candidate' "$focused"

# Ordinary URL ownership must remain outside the typed manager.
perl -0pi -e "s/return value\.startsWith\(KRAvatarCacheManager\.MODEL_PREFIX\);/return value.startsWith('http');/" "$manager"
expect_behavior_red ordinary_url_non_interference \
  'Error in keeps ordinary URLs outside the typed-avatar owner' "$focused"

restore
if [[ -n "$mutation_log_dir" ]]; then
  "$cpp_fixture" 2>&1 | tee "$mutation_log_dir/restored-cpp.log" | tee -a "$mutation_log_dir/mutation-matrix.log"
  "$focused" 2>&1 | tee "$mutation_log_dir/restored-hypium.log" | tee -a "$mutation_log_dir/mutation-matrix.log"
else
  "$cpp_fixture"
  "$focused"
fi
record_line 'OHOS avatar-cache mutation matrix PASS'
