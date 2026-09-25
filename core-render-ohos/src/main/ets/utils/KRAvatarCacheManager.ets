/*
 * Typed-avatar extension of Kuikly's existing OHOS image owner.
 *
 * Only descriptors carrying the shared slock_avatar_cache_v1 contract enter this manager. Ordinary
 * Kuikly image URLs continue through KRNetworkCacheUtil unchanged. Files live in one avatar-only
 * subnamespace under cacheDir and are indexed solely by opaque partition/subject/bytes keys.
 */
import { common } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import util from '@ohos.util';
import { KuiklyRenderCallback } from './KRDataTypes';
import { KRRenderLog } from '../adapter/KRRenderLog';
import {
  KRAvatarCacheRuntime,
  KRAvatarFileStat,
  KRAvatarHttpResult,
  KRAvatarSystemCacheRuntime
} from './KRAvatarCacheRuntime';

export interface KRAvatarCacheBridgePayload {
  status: string;
  managedSet: string;
  managedIndex: string;
  budgetScope: string;
  indexedRetirement: boolean;
  managedBytes: string;
  bytesAvailable?: boolean;
  bytesDecodable?: boolean;
  sizeBytes?: string;
  eTag?: string;
  lastModified?: string;
  contentType?: string;
  code?: string;
  operationReceipt?: string;
  finalizeReceipt?: string;
}

export interface KRAvatarDescriptor {
  protocol: string;
  managedSet: string;
  managedIndex: string;
  budgetScope: string;
  indexedRetirementRequired: boolean;
  maxEntryBytes: string;
  maxManagedBytes: string;
  remoteUrl: string;
  nativePartitionKey: string;
  nativeSubjectKey: string;
  nativeBytesKey: string;
  readMode: string;
  commitGeneration: string;
  callerAuthority: string;
  retryAtMillis?: string;
  eTag?: string;
  lastModified?: string;
}

class KRAvatarEmptyHeaders {
}

export interface KRAvatarManagedFile {
  path: string;
  name: string;
  partitionKey: string;
  subjectKey: string;
  bytesKey: string;
  sizeBytes: number;
  lastAccess: number;
}

type KRAvatarResultWaiter = (payload: KRAvatarCacheBridgePayload) => void;

interface KRAvatarFetchToken {
  operation: string;
  lifecycleId: number;
  operationReceipt: string;
  descriptor: KRAvatarDescriptor;
  globalEpoch: number;
  ownerEpoch: number;
  subjectEpoch: number;
  identityEpoch: number;
  callback: KuiklyRenderCallback | null;
}

interface KRAvatarFinalizeToken {
  identity: string;
  lifecycleId: number;
  attemptId: number;
  operationReceipt: string;
  finalizeAttempt: string;
  finalizeReceipt: string;
  descriptor: KRAvatarDescriptor;
  globalEpoch: number;
  ownerEpoch: number;
  subjectEpoch: number;
  identityEpoch: number;
  stagePath: string;
  finalPath: string;
  backupPath: string;
  priorAcceptedGeneration?: string;
  priorAcceptedCallerAuthority?: string;
  transactionMarkerCreated: boolean;
  priorBackedUp: boolean;
  candidateInstalled: boolean;
  preparedForSharedAck: boolean;
  sharedAcked: boolean;
  rollbackDeadlineMillis: number;
}

interface KRAvatarFinalizeReceipt {
  lifecycleId: number;
  operationReceipt: string;
  identity: string;
  generation: string;
  callerAuthority: string;
  status: string;
  globalEpoch: number;
  ownerEpoch: number;
  subjectEpoch: number;
  identityEpoch: number;
  finalized: boolean;
}

interface KRAvatarStageIdentity {
  directory: string;
  partitionKey: string;
  subjectKey: string;
  bytesKey: string;
}

interface KRAvatarRenderTerminal {
  payload: KRAvatarCacheBridgePayload;
  renderPath: string | null;
}

interface KRAvatarOperationLifecycle {
  id: number;
  operationReceipt: string;
  operation: string;
  descriptor: KRAvatarDescriptor;
  leader: KRAvatarFetchToken;
  settled: boolean;
  terminal: KRAvatarRenderTerminal | null;
}

export class KRAvatarCacheManager {
  static readonly MODEL_PREFIX = 'slock-avatar-cache-v1:';
  private static readonly PROTOCOL = 'slock_avatar_cache_v1';
  private static readonly MANAGED_SET = 'typed_avatar_only';
  private static readonly MANAGED_INDEX = 'opaque_partition_subject_bytes_v1';
  private static readonly BUDGET_SCOPE = 'avatar_only';
  private static readonly DIRECTORY_NAME = 'kuikly-avatar-managed-v1';
  private static readonly FINAL_SUFFIX = '.avatar';
  private static readonly MAX_ENTRY_BYTES = 5 * 1024 * 1024;
  private static readonly MAX_MANAGED_BYTES = 64 * 1024 * 1024;
  private static readonly resultByOperation: Map<string, KRAvatarCacheBridgePayload> = new Map();
  private static readonly waitersByOperation: Map<string, Array<KRAvatarResultWaiter>> = new Map();
  private static readonly renderJoinersByLifecycle: Map<number, Array<KRAvatarFetchToken>> = new Map();
  private static readonly lifecycleById: Map<number, KRAvatarOperationLifecycle> = new Map();
  private static readonly currentLifecycleByOperation: Map<string, KRAvatarOperationLifecycle> = new Map();
  private static readonly accessByIdentity: Map<string, number> = new Map();
  private static readonly activeFetches: Map<string, KRAvatarFetchToken> = new Map();
  private static readonly activeOperations: Set<KRAvatarFetchToken> = new Set();
  private static readonly descriptorByOperation: Map<string, KRAvatarDescriptor> = new Map();
  private static readonly identityByOperation: Map<string, string> = new Map();
  private static readonly operationsByIdentity: Map<string, Set<string>> = new Map();
  private static readonly finalizeByIdentity: Map<string, KRAvatarFinalizeToken> = new Map();
  private static readonly finalizeReceiptByOperation: Map<string, KRAvatarFinalizeReceipt> = new Map();
  private static readonly ackedFinalizeGenerationByIdentity: Map<string, string> = new Map();
  private static readonly acceptedGenerationByIdentity: Map<string, string> = new Map();
  private static readonly acceptedCallerAuthorityByIdentity: Map<string, string> = new Map();
  private static readonly ownerEpoch: Map<string, number> = new Map();
  private static readonly subjectEpoch: Map<string, number> = new Map();
  private static readonly identityEpoch: Map<string, number> = new Map();
  private static readonly retirementDepthByScope: Map<string, number> = new Map();
  private static readonly quarantineLeaseCountByPath: Map<string, number> = new Map();
  private static readonly quarantineLeaseFramesByScope: Map<string, Array<Set<string>>> = new Map();
  private static nextLifecycleId: number = 1;
  private static nextOperationReceiptId: number = 1;
  private static nextFinalizeAttemptId: number = 1;
  private static globalEpoch: number = 0;
  private static readonly FINALIZE_ROLLBACK_WINDOW_MILLIS: number = 15000;
  private static runtime: KRAvatarCacheRuntime = new KRAvatarSystemCacheRuntime();

  /** Test-only owner seam. Tests still drive the production manager methods below. */
  static installRuntimeForTesting(runtime: KRAvatarCacheRuntime): void {
    KRAvatarCacheManager.runtime = runtime;
    KRAvatarCacheManager.resetVolatileStateForTesting();
  }

  static restoreSystemRuntimeAfterTesting(): void {
    KRAvatarCacheManager.runtime = new KRAvatarSystemCacheRuntime();
    KRAvatarCacheManager.resetVolatileStateForTesting();
  }

  static resetVolatileStateForTesting(): void {
    KRAvatarCacheManager.resultByOperation.clear();
    KRAvatarCacheManager.waitersByOperation.clear();
    KRAvatarCacheManager.renderJoinersByLifecycle.clear();
    KRAvatarCacheManager.lifecycleById.clear();
    KRAvatarCacheManager.currentLifecycleByOperation.clear();
    KRAvatarCacheManager.accessByIdentity.clear();
    KRAvatarCacheManager.activeFetches.clear();
    KRAvatarCacheManager.activeOperations.clear();
    KRAvatarCacheManager.descriptorByOperation.clear();
    KRAvatarCacheManager.identityByOperation.clear();
    KRAvatarCacheManager.operationsByIdentity.clear();
    KRAvatarCacheManager.finalizeByIdentity.clear();
    KRAvatarCacheManager.finalizeReceiptByOperation.clear();
    KRAvatarCacheManager.ackedFinalizeGenerationByIdentity.clear();
    KRAvatarCacheManager.acceptedGenerationByIdentity.clear();
    KRAvatarCacheManager.acceptedCallerAuthorityByIdentity.clear();
    KRAvatarCacheManager.ownerEpoch.clear();
    KRAvatarCacheManager.subjectEpoch.clear();
    KRAvatarCacheManager.identityEpoch.clear();
    KRAvatarCacheManager.retirementDepthByScope.clear();
    KRAvatarCacheManager.quarantineLeaseCountByPath.clear();
    KRAvatarCacheManager.quarantineLeaseFramesByScope.clear();
    KRAvatarCacheManager.nextLifecycleId = 1;
    KRAvatarCacheManager.nextOperationReceiptId = 1;
    KRAvatarCacheManager.nextFinalizeAttemptId = 1;
    KRAvatarCacheManager.globalEpoch = 0;
  }

  static isAvatarModel(value: string): boolean {
    return value.startsWith(KRAvatarCacheManager.MODEL_PREFIX);
  }

  static parseModel(value: string): KRAvatarDescriptor | null {
    if (!KRAvatarCacheManager.isAvatarModel(value)) return null;
    try {
      const encoded = value.substring(KRAvatarCacheManager.MODEL_PREFIX.length);
      const bytes = new util.Base64Helper().decodeSync(encoded);
      const json = new util.TextDecoder('utf-8').decodeToString(bytes);
      return KRAvatarCacheManager.parseDescriptorJson(json);
    } catch (error) {
      KRRenderLog.e('KRAvatarCacheManager', `descriptor rejected: ${KRAvatarCacheManager.errorMessage(error)}`);
      return null;
    }
  }

  static parseDescriptorJson(json: string): KRAvatarDescriptor | null {
    try {
      return KRAvatarCacheManager.validateDescriptor(JSON.parse(json) as KRAvatarDescriptor);
    } catch (_error) {
      return null;
    }
  }

  static fetchFile(
    context: common.UIAbilityContext,
    model: string,
    callback: KuiklyRenderCallback
  ): boolean {
    if (!KRAvatarCacheManager.isAvatarModel(model)) return false;
    const descriptor = KRAvatarCacheManager.parseModel(model);
    if (descriptor === null) {
      callback(null);
      return true;
    }
    void KRAvatarCacheManager.load(context, descriptor, callback);
    return true;
  }

  static fetchDescriptorForTesting(
    context: common.UIAbilityContext,
    descriptorJson: string,
    callback: KuiklyRenderCallback
  ): boolean {
    const descriptor = KRAvatarCacheManager.parseDescriptorJson(descriptorJson);
    if (descriptor === null) {
      callback(null);
      return false;
    }
    void KRAvatarCacheManager.load(context, descriptor, callback);
    return true;
  }

  static async handleBridge(
    context: common.UIAbilityContext,
    command: Record<string, Object>,
    callback: KuiklyRenderCallback
  ): Promise<void> {
    try {
      const action = KRAvatarCacheManager.stringValue(command['action']);
      switch (action) {
        case 'inspect':
          KRAvatarCacheManager.deliverProtocolWaiter(
            callback as KRAvatarResultWaiter,
            await KRAvatarCacheManager.inspect(context, command)
          );
          return;
        case 'awaitResult':
          KRAvatarCacheManager.awaitResult(command, callback);
          return;
        case 'finalize':
          KRAvatarCacheManager.deliverProtocolWaiter(
            callback as KRAvatarResultWaiter,
            await KRAvatarCacheManager.finalize(context, command)
          );
          return;
        case 'ackFinalize':
          KRAvatarCacheManager.deliverProtocolWaiter(
            callback as KRAvatarResultWaiter,
            KRAvatarCacheManager.ackFinalize(context, command)
          );
          return;
        case 'rollback':
          KRAvatarCacheManager.deliverProtocolWaiter(
            callback as KRAvatarResultWaiter,
            KRAvatarCacheManager.rollback(context, command)
          );
          return;
        case 'retireOwner':
          KRAvatarCacheManager.deliverProtocolWaiter(
            callback as KRAvatarResultWaiter,
            KRAvatarCacheManager.retire(context, command, false)
          );
          return;
        case 'retireSubject':
          KRAvatarCacheManager.deliverProtocolWaiter(
            callback as KRAvatarResultWaiter,
            KRAvatarCacheManager.retire(context, command, true)
          );
          return;
        default:
          KRAvatarCacheManager.deliverProtocolWaiter(
            callback as KRAvatarResultWaiter,
            KRAvatarCacheManager.payload('unsupported', context)
          );
      }
    } catch (error) {
      KRRenderLog.e('KRAvatarCacheManager', `bridge action failed: ${KRAvatarCacheManager.errorMessage(error)}`);
      const fallback = KRAvatarCacheManager.payloadWithoutContext('error');
      fallback.code = 'avatar_cache_native_failed';
      KRAvatarCacheManager.deliverProtocolWaiter(callback as KRAvatarResultWaiter, fallback);
    }
  }

  static clearAll(context: common.UIAbilityContext): boolean {
    const retirementScope = KRAvatarCacheManager.globalRetirementScope();
    KRAvatarCacheManager.enterRetirementScope(retirementScope);
    try {
      if (!KRAvatarCacheManager.prequarantineManagedFiles(
        context,
        (_partition: string, _subject: string): boolean => true,
        retirementScope
      )) return false;
      KRAvatarCacheManager.globalEpoch += 1;
      const matches = (_descriptor: KRAvatarDescriptor): boolean => true;
      let success = KRAvatarCacheManager.retireFinalizations(matches);
    KRAvatarCacheManager.finalizeReceiptByOperation.clear();
    KRAvatarCacheManager.ackedFinalizeGenerationByIdentity.clear();
    if (!KRAvatarCacheManager.cancelActiveOperations(matches, context, 'retired')) success = false;
    KRAvatarCacheManager.retireOperationState(matches);
    try {
      const managed = KRAvatarCacheManager.managedFiles(context);
      for (const entry of managed) {
        try {
          if (!KRAvatarCacheManager.deleteManagedFile(context, entry)) success = false;
        } catch (error) {
          success = false;
          KRRenderLog.e('KRAvatarCacheManager', `clear managed failed: ${KRAvatarCacheManager.errorMessage(error)}`);
        }
      }
    } catch (error) {
      success = false;
      KRRenderLog.e('KRAvatarCacheManager', `clear managed scan failed: ${KRAvatarCacheManager.errorMessage(error)}`);
    }
    try {
      const stages = KRAvatarCacheManager.stageFiles(context);
      for (const path of stages) {
        try {
          if (!KRAvatarCacheManager.deleteRetiredStageFile(path)) success = false;
        } catch (error) {
          success = false;
          KRRenderLog.e('KRAvatarCacheManager', `clear stage failed: ${KRAvatarCacheManager.errorMessage(error)}`);
        }
      }
    } catch (error) {
      success = false;
      KRRenderLog.e('KRAvatarCacheManager', `clear stage scan failed: ${KRAvatarCacheManager.errorMessage(error)}`);
    }
    KRAvatarCacheManager.activeFetches.clear();
    KRAvatarCacheManager.activeOperations.clear();
    KRAvatarCacheManager.currentLifecycleByOperation.clear();
    KRAvatarCacheManager.lifecycleById.clear();
    KRAvatarCacheManager.renderJoinersByLifecycle.clear();
    KRAvatarCacheManager.acceptedGenerationByIdentity.clear();
    return success;
    } finally {
      KRAvatarCacheManager.leaveRetirementScope(retirementScope);
    }
  }

  static managedBytes(context: common.UIAbilityContext): number {
    return KRAvatarCacheManager.managedFiles(context)
      .reduce((sum: number, entry: KRAvatarManagedFile): number => sum + entry.sizeBytes, 0);
  }

  private static validateDescriptor(candidate: KRAvatarDescriptor): KRAvatarDescriptor | null {
    if (
      candidate.protocol !== KRAvatarCacheManager.PROTOCOL ||
      candidate.managedSet !== KRAvatarCacheManager.MANAGED_SET ||
      candidate.managedIndex !== KRAvatarCacheManager.MANAGED_INDEX ||
      candidate.budgetScope !== KRAvatarCacheManager.BUDGET_SCOPE ||
      candidate.indexedRetirementRequired !== true ||
      candidate.maxEntryBytes !== `${KRAvatarCacheManager.MAX_ENTRY_BYTES}` ||
      candidate.maxManagedBytes !== `${KRAvatarCacheManager.MAX_MANAGED_BYTES}` ||
      !candidate.remoteUrl.startsWith('https://') && !candidate.remoteUrl.startsWith('http://') ||
      !KRAvatarCacheManager.isOpaqueKey(candidate.nativePartitionKey) ||
      !KRAvatarCacheManager.isOpaqueKey(candidate.nativeSubjectKey) ||
      !KRAvatarCacheManager.isOpaqueKey(candidate.nativeBytesKey) ||
      !KRAvatarCacheManager.isAllowedReadMode(candidate.readMode) ||
      !KRAvatarCacheManager.isPositiveGeneration(candidate.commitGeneration) ||
      !KRAvatarCacheManager.isOpaqueKey(candidate.callerAuthority)
    ) {
      return null;
    }
    return candidate;
  }

  private static async load(
    context: common.UIAbilityContext,
    descriptor: KRAvatarDescriptor,
    callback: KuiklyRenderCallback
  ): Promise<void> {
    if (KRAvatarCacheManager.isDescriptorRetiring(descriptor)) {
      try {
        callback(null);
      } catch (error) {
        KRRenderLog.e('KRAvatarCacheManager', `retiring render callback failed: ${KRAvatarCacheManager.errorMessage(error)}`);
      }
      return;
    }
    const operation = KRAvatarCacheManager.operationKey(descriptor);
    const token = KRAvatarCacheManager.newFetchToken(operation, descriptor, callback);
    KRAvatarCacheManager.registerOperation(operation, descriptor);
    KRAvatarCacheManager.activeOperations.add(token);
    let isLeader = false;
    try {
      const leader = KRAvatarCacheManager.admitOperation(operation, token);
      isLeader = leader === token;
      if (!isLeader && leader.descriptor.readMode !== descriptor.readMode) {
        KRAvatarCacheManager.deliverRenderOnce(token, null);
        return;
      }
      if (!isLeader && descriptor.readMode === 'fetch') {
        KRAvatarCacheManager.deliverTerminalOrJoin(token);
        return;
      }
      if (descriptor.readMode === 'suppressed') {
        if (isLeader) KRAvatarCacheManager.completeOperation(context, token, null, 'suppressed');
        else KRAvatarCacheManager.deliverRenderOnce(token, null);
        return;
      }
      const quarantined = KRAvatarCacheManager.isIdentityQuarantined(context, descriptor);
      if (quarantined && descriptor.readMode !== 'fetch') {
        if (isLeader) KRAvatarCacheManager.completeOperation(context, token, null, 'temporary_transport');
        else KRAvatarCacheManager.deliverRenderOnce(token, null);
        return;
      }
      const finalPath = KRAvatarCacheManager.finalPath(context, descriptor);
      const finalUsable = quarantined ? false : await KRAvatarCacheManager.runtime.isDecodable(finalPath);
      if (!KRAvatarCacheManager.isOperationTokenLive(token)) return;
      if (!isLeader) {
        KRAvatarCacheManager.renderFollower(context, token, finalPath, finalUsable);
        return;
      }
      switch (descriptor.readMode) {
      case 'render_fresh':
      case 'render_stale_suppressed':
        if (finalUsable) {
          KRAvatarCacheManager.touch(descriptor);
          const promoted = KRAvatarCacheManager.runtime.promoteDecoded(
            descriptor.nativeBytesKey,
            descriptor.commitGeneration,
            descriptor.callerAuthority
          );
          KRAvatarCacheManager.completeOperation(
            context,
            token,
            promoted ? finalPath : null,
            promoted ? 'rendered' : 'error',
            new KRAvatarEmptyHeaders(),
            promoted ? '' : 'decoded_promotion_failed'
          );
        } else {
          KRAvatarCacheManager.completeOperation(context, token, null, 'temporary_transport');
        }
        return;
      case 'render_stale_revalidate':
        if (finalUsable) {
          KRAvatarCacheManager.touch(descriptor);
          const renderAdmission = KRAvatarCacheManager.safePayload(context, 'rendered');
          if (renderAdmission.status === 'error') {
            KRAvatarCacheManager.completeOperation(context, token, null, 'rendered');
            return;
          }
          KRAvatarCacheManager.deliverRenderOnce(token, finalPath);
          const lifecycle = KRAvatarCacheManager.lifecycleById.get(token.lifecycleId);
          if (lifecycle !== undefined) {
            KRAvatarCacheManager.deliverRenderJoiners(lifecycle, renderAdmission, finalPath);
          }
          if (!KRAvatarCacheManager.runtime.promoteDecoded(
            descriptor.nativeBytesKey,
            descriptor.commitGeneration,
            descriptor.callerAuthority
          )) {
            KRAvatarCacheManager.completeOperation(
              context,
              token,
              null,
              'error',
              new KRAvatarEmptyHeaders(),
              'decoded_promotion_failed'
            );
            return;
          }
          await KRAvatarCacheManager.fetchAndStage(context, descriptor, true, token);
        } else {
          await KRAvatarCacheManager.fetchAndStage(context, descriptor, false, token);
        }
        return;
      case 'fetch':
        await KRAvatarCacheManager.fetchAndStage(context, descriptor, finalUsable, token);
        return;
      default:
        KRAvatarCacheManager.completeOperation(context, token, null, 'unsupported');
      }
    } catch (error) {
      if (KRAvatarCacheManager.isOperationTokenLive(token)) {
        KRRenderLog.e('KRAvatarCacheManager', `load failed: ${KRAvatarCacheManager.errorMessage(error)}`);
        if (isLeader) {
          KRAvatarCacheManager.completeOperation(
            context,
            token,
            null,
            'error',
            new KRAvatarEmptyHeaders(),
            'avatar_cache_native_failed'
          );
        } else {
          KRAvatarCacheManager.deliverRenderOnce(token, null);
        }
      }
    } finally {
      KRAvatarCacheManager.activeOperations.delete(token);
      if (KRAvatarCacheManager.activeFetches.get(operation) === token) {
        KRAvatarCacheManager.activeFetches.delete(operation);
      }
      KRAvatarCacheManager.releaseLifecycleIfUnused(token.lifecycleId);
    }
  }

  private static async fetchAndStage(
    context: common.UIAbilityContext,
    descriptor: KRAvatarDescriptor,
    allowValidators: boolean,
    token: KRAvatarFetchToken
  ): Promise<void> {
    const operation = KRAvatarCacheManager.operationKey(descriptor);
    if (KRAvatarCacheManager.activeFetches.get(operation) !== token) return;
    if (!KRAvatarCacheManager.isOperationTokenLive(token)) return;
    try {
      let result = await KRAvatarCacheManager.httpGet(descriptor, allowValidators);
      if (!KRAvatarCacheManager.isFetchTokenLive(token)) return;
      if (result.responseCode === 304) {
        const finalPath = KRAvatarCacheManager.finalPath(context, descriptor);
        const finalUsable = await KRAvatarCacheManager.runtime.isDecodable(finalPath);
        if (!KRAvatarCacheManager.isFetchTokenLive(token)) return;
        if (allowValidators && finalUsable) {
          const identity = KRAvatarCacheManager.identityKey(descriptor);
          const priorGeneration = KRAvatarCacheManager.acceptedGenerationByIdentity.get(identity);
          const priorCallerAuthority = KRAvatarCacheManager.acceptedCallerAuthorityByIdentity.get(identity);
          if (!KRAvatarCacheManager.runtime.retainDecoded(
            descriptor.nativeBytesKey,
            descriptor.commitGeneration,
            descriptor.callerAuthority,
            priorGeneration,
            priorCallerAuthority
          ) || !KRAvatarCacheManager.runtime.hasDecoded(
            descriptor.nativeBytesKey,
            descriptor.commitGeneration,
            descriptor.callerAuthority
          )) {
            KRAvatarCacheManager.completeOperation(
              context,
              token,
              null,
              'error',
              new KRAvatarEmptyHeaders(),
              'decoded_promotion_failed'
            );
            return;
          }
          KRAvatarCacheManager.completeOperation(context, token, finalPath, 'not_modified', result.headers);
          return;
        }
        // A corrupt/missing final cannot inherit metadata from a 304. Retry exactly once unconditionally.
        result = await KRAvatarCacheManager.httpGet(descriptor, false);
        if (!KRAvatarCacheManager.isFetchTokenLive(token)) return;
        if (result.responseCode === 304) {
          KRAvatarCacheManager.completeOperation(context, token, null, 'invalid_content');
          return;
        }
      }
      if (result.responseCode === 404) {
        KRAvatarCacheManager.completeOperation(context, token, null, 'resource_absent');
        return;
      }
      if (result.responseCode < 200 || result.responseCode >= 300 || result.body === null) {
        KRAvatarCacheManager.completeOperation(context, token, null, 'temporary_transport');
        return;
      }
      if (result.body.byteLength <= 0 || result.body.byteLength > KRAvatarCacheManager.MAX_ENTRY_BYTES) {
        KRAvatarCacheManager.completeOperation(context, token, null, 'invalid_content');
        return;
      }
      const stagePath = KRAvatarCacheManager.stagePath(context, descriptor);
      KRAvatarCacheManager.runtime.write(stagePath, result.body);
      if (!KRAvatarCacheManager.isFetchTokenLive(token)) {
        KRAvatarCacheManager.removeIfPresent(stagePath);
        KRAvatarCacheManager.runtime.retireDecoded(
          descriptor.nativeBytesKey, descriptor.commitGeneration, descriptor.callerAuthority
        );
        return;
      }
      if (!await KRAvatarCacheManager.runtime.isDecodable(stagePath)) {
        if (!KRAvatarCacheManager.removeIfPresent(stagePath)) {
          KRAvatarCacheManager.completeOperation(
            context,
            token,
            null,
            'error',
            new KRAvatarEmptyHeaders(),
            'stage_delete_failed'
          );
          return;
        }
        KRAvatarCacheManager.completeOperation(context, token, null, 'invalid_content');
        return;
      }
      if (!KRAvatarCacheManager.isFetchTokenLive(token)) {
        KRAvatarCacheManager.removeIfPresent(stagePath);
        KRAvatarCacheManager.runtime.retireDecoded(
          descriptor.nativeBytesKey, descriptor.commitGeneration, descriptor.callerAuthority
        );
        return;
      }
      KRAvatarCacheManager.completeOperation(
        context,
        token,
        stagePath,
        'success',
        result.headers,
        '',
        `${result.body.byteLength}`
      );
    } catch (error) {
      if (!KRAvatarCacheManager.isFetchTokenLive(token)) return;
      KRRenderLog.e('KRAvatarCacheManager', `fetch failed: ${KRAvatarCacheManager.errorMessage(error)}`);
      KRAvatarCacheManager.completeOperation(context, token, null, 'temporary_transport');
    } finally {
      if (KRAvatarCacheManager.activeFetches.get(operation) === token) {
        KRAvatarCacheManager.activeFetches.delete(operation);
      }
    }
  }

  private static httpGet(descriptor: KRAvatarDescriptor, allowValidators: boolean): Promise<KRAvatarHttpResult> {
    const headers: Record<string, string> = {};
    if (allowValidators && descriptor.eTag !== undefined && descriptor.eTag.length > 0) {
      headers['If-None-Match'] = descriptor.eTag;
    }
    if (allowValidators && descriptor.lastModified !== undefined && descriptor.lastModified.length > 0) {
      headers['If-Modified-Since'] = descriptor.lastModified;
    }
    return KRAvatarCacheManager.runtime.httpGet(descriptor.remoteUrl, headers);
  }

  private static async inspect(
    context: common.UIAbilityContext,
    command: Record<string, Object>
  ): Promise<KRAvatarCacheBridgePayload> {
    if (!KRAvatarCacheManager.validIndexedKeys(command, false)) {
      return KRAvatarCacheManager.payload('unsupported', context);
    }
    const descriptor = KRAvatarCacheManager.commandDescriptor(command);
    const path = KRAvatarCacheManager.finalPath(context, descriptor);
    const quarantined = KRAvatarCacheManager.isIdentityQuarantined(context, descriptor);
    const available = !quarantined && KRAvatarCacheManager.runtime.exists(path);
    const decodable = available && await KRAvatarCacheManager.runtime.isDecodable(path);
    const payload = KRAvatarCacheManager.payload('ok', context);
    payload.bytesAvailable = available;
    payload.bytesDecodable = decodable;
    return payload;
  }

  private static awaitResult(command: Record<string, Object>, callback: KuiklyRenderCallback): void {
    if (!KRAvatarCacheManager.validCommand(command, true)) {
      KRAvatarCacheManager.deliverProtocolWaiter(
        callback as KRAvatarResultWaiter,
        KRAvatarCacheManager.payloadWithoutContext('unsupported')
      );
      return;
    }
    const descriptor = KRAvatarCacheManager.commandDescriptor(command);
    if (KRAvatarCacheManager.isDescriptorRetiring(descriptor)) {
      KRAvatarCacheManager.deliverProtocolWaiter(
        callback as KRAvatarResultWaiter,
        KRAvatarCacheManager.payloadWithoutContext('retired')
      );
      return;
    }
    const operation = KRAvatarCacheManager.operationKey(descriptor);
    KRAvatarCacheManager.registerOperation(operation, descriptor);
    const existing = KRAvatarCacheManager.resultByOperation.get(operation);
    if (existing !== undefined) {
      KRAvatarCacheManager.resultByOperation.delete(operation);
      KRAvatarCacheManager.unregisterOperation(operation);
      KRAvatarCacheManager.deliverProtocolWaiter(callback as KRAvatarResultWaiter, existing);
      return;
    }
    KRAvatarCacheManager.addWaiter(operation, callback as KRAvatarResultWaiter);
  }

  private static async finalize(
    context: common.UIAbilityContext,
    command: Record<string, Object>
  ): Promise<KRAvatarCacheBridgePayload> {
    if (!KRAvatarCacheManager.validCommand(command, true)) return KRAvatarCacheManager.payload('unsupported', context);
    const descriptor = KRAvatarCacheManager.commandDescriptor(command);
    if (KRAvatarCacheManager.isDescriptorRetiring(descriptor)) {
      return KRAvatarCacheManager.payload('error', context, new KRAvatarEmptyHeaders(), 'finalize_retired');
    }
    const outcome = KRAvatarCacheManager.stringValue(command['outcome']);
    const finalizeAttempt = KRAvatarCacheManager.stringValue(command['finalizeAttempt']);
    const operationReceipt = KRAvatarCacheManager.stringValue(command['operationReceipt']);
    if ((outcome === 'successful_bytes' || outcome === 'not_modified') &&
      (!KRAvatarCacheManager.isOpaqueKey(finalizeAttempt) ||
      !KRAvatarCacheManager.isOpaqueKey(operationReceipt))) {
      return KRAvatarCacheManager.payload(
        'error', context, new KRAvatarEmptyHeaders(), 'finalize_binding_required'
      );
    }
    let completedReservation: KRAvatarFinalizeToken | null = null;
    if (outcome === 'successful_bytes') {
      const identity = KRAvatarCacheManager.identityKey(descriptor);
      const operation = KRAvatarCacheManager.operationKey(descriptor);
      const receipt = KRAvatarCacheManager.finalizeReceiptByOperation.get(operation);
      if (receipt === undefined || !KRAvatarCacheManager.isFinalizeReceiptLive(
        receipt,
        descriptor,
        'success',
        operationReceipt
      )) {
        const existing = KRAvatarCacheManager.finalizeByIdentity.get(identity);
        const acknowledged = KRAvatarCacheManager.ackedFinalizeGenerationByIdentity.get(identity);
        if ((existing !== undefined &&
          existing.descriptor.commitGeneration === descriptor.commitGeneration &&
          existing.preparedForSharedAck &&
          existing.finalizeAttempt === finalizeAttempt)) {
          return KRAvatarCacheManager.finalizePayload(context, existing);
        }
        if (existing !== undefined &&
          existing.descriptor.commitGeneration === descriptor.commitGeneration) {
          return KRAvatarCacheManager.payload(
            'error', context, new KRAvatarEmptyHeaders(), 'finalize_in_progress'
          );
        }
        if (acknowledged === descriptor.commitGeneration) {
          return KRAvatarCacheManager.payload('ok', context);
        }
        return KRAvatarCacheManager.payload(
          'error', context, new KRAvatarEmptyHeaders(), 'successful_bytes_without_terminal'
        );
      }
      if (receipt.finalized) {
        const existing = KRAvatarCacheManager.finalizeByIdentity.get(identity);
        const acknowledged = KRAvatarCacheManager.ackedFinalizeGenerationByIdentity.get(identity);
        if ((existing !== undefined &&
          existing.descriptor.commitGeneration === descriptor.commitGeneration &&
          existing.preparedForSharedAck &&
          existing.finalizeAttempt === finalizeAttempt)) {
          return KRAvatarCacheManager.finalizePayload(context, existing);
        }
        if (existing !== undefined &&
          existing.descriptor.commitGeneration === descriptor.commitGeneration) {
          return KRAvatarCacheManager.payload(
            'error', context, new KRAvatarEmptyHeaders(), 'finalize_in_progress'
          );
        }
        if (acknowledged === descriptor.commitGeneration) {
          return KRAvatarCacheManager.payload('ok', context);
        }
        return KRAvatarCacheManager.payload(
          'error', context, new KRAvatarEmptyHeaders(), 'successful_bytes_transaction_missing'
        );
      }
      const stage = KRAvatarCacheManager.stagePath(context, descriptor);
      if (!KRAvatarCacheManager.runtime.exists(stage)) {
        return KRAvatarCacheManager.payload('error', context, new KRAvatarEmptyHeaders(), 'stage_missing');
      }
      if (!KRAvatarCacheManager.finalizeIdentityAvailable(identity)) {
        return KRAvatarCacheManager.payload('error', context, new KRAvatarEmptyHeaders(), 'finalize_in_progress');
      }
      const accepted = KRAvatarCacheManager.acceptedGenerationByIdentity.get(identity);
      if (accepted !== undefined && KRAvatarCacheManager.compareGeneration(descriptor.commitGeneration, accepted) < 0) {
        const removed = KRAvatarCacheManager.removeIfPresent(stage);
        KRAvatarCacheManager.runtime.retireDecoded(
          descriptor.nativeBytesKey, descriptor.commitGeneration, descriptor.callerAuthority
        );
        return KRAvatarCacheManager.payload(
          'error',
          context,
          new KRAvatarEmptyHeaders(),
          removed ? 'stale_generation' : 'stage_delete_failed'
        );
      }
      const finalPath = KRAvatarCacheManager.finalPath(context, descriptor);
      let incomingSize = 0;
      try {
        incomingSize = KRAvatarCacheManager.runtime.stat(stage).size;
      } catch (_error) {
        return KRAvatarCacheManager.payload('error', context, new KRAvatarEmptyHeaders(), 'stage_stat_failed');
      }
      if (incomingSize <= 0 || incomingSize > KRAvatarCacheManager.MAX_ENTRY_BYTES) {
        return KRAvatarCacheManager.payload('invalid_content', context, new KRAvatarEmptyHeaders(), 'stage_size_invalid');
      }
      const reservation = KRAvatarCacheManager.reserveFinalize(context, descriptor, receipt, finalizeAttempt);
      if (reservation === null) {
        const removed = KRAvatarCacheManager.removeIfPresent(stage);
        KRAvatarCacheManager.runtime.retireDecoded(
          descriptor.nativeBytesKey, descriptor.commitGeneration, descriptor.callerAuthority
        );
        return KRAvatarCacheManager.payload(
          'error', context, new KRAvatarEmptyHeaders(), removed ? 'stale_generation' : 'stage_delete_failed'
        );
      }
      if (!KRAvatarCacheManager.isFinalizeTokenLive(reservation)) {
        return KRAvatarCacheManager.payload('error', context, new KRAvatarEmptyHeaders(), 'finalize_retired');
      }
      if (!KRAvatarCacheManager.prepareBudgetForPromotion(context, descriptor, incomingSize)) {
        KRAvatarCacheManager.discardFinalizeReservation(reservation);
        return KRAvatarCacheManager.payload('error', context, new KRAvatarEmptyHeaders(), 'budget_eviction_failed');
      }
      const transactionQuarantinePath = KRAvatarCacheManager.quarantinePathForFinal(reservation.finalPath);
      const transactionMarkerExisted = KRAvatarCacheManager.runtime.exists(transactionQuarantinePath);
      if (!KRAvatarCacheManager.ensureQuarantineMarker(transactionQuarantinePath)) {
        KRAvatarCacheManager.discardFinalizeReservation(reservation);
        return KRAvatarCacheManager.payload(
          'error', context, new KRAvatarEmptyHeaders(), 'transaction_quarantine_failed'
        );
      }
      reservation.transactionMarkerCreated = !transactionMarkerExisted;
      try {
        if (KRAvatarCacheManager.runtime.exists(finalPath)) {
          KRAvatarCacheManager.runtime.rename(finalPath, reservation.backupPath);
          reservation.priorBackedUp = true;
        }
        KRAvatarCacheManager.runtime.rename(stage, finalPath);
        reservation.candidateInstalled = true;
      } catch (error) {
        KRRenderLog.e('KRAvatarCacheManager', `promotion failed: ${KRAvatarCacheManager.errorMessage(error)}`);
        if (!KRAvatarCacheManager.restoreFinalizeTransaction(reservation)) {
          return KRAvatarCacheManager.payload(
            'error', context, new KRAvatarEmptyHeaders(), 'promotion_restore_failed'
          );
        }
        return KRAvatarCacheManager.payload('error', context, new KRAvatarEmptyHeaders(), 'promotion_failed');
      }
      try {
        const stageAbsent = !KRAvatarCacheManager.runtime.exists(stage);
        const finalPresent = KRAvatarCacheManager.runtime.exists(finalPath);
        const finalSize = finalPresent ? KRAvatarCacheManager.runtime.stat(finalPath).size : -1;
        const finalDecodable = finalPresent && await KRAvatarCacheManager.runtime.isDecodable(finalPath);
        if (!KRAvatarCacheManager.isFinalizeTokenLive(reservation)) {
          return KRAvatarCacheManager.payload('error', context, new KRAvatarEmptyHeaders(), 'finalize_retired');
        }
        const latestAccepted = KRAvatarCacheManager.acceptedGenerationByIdentity.get(identity);
        if (latestAccepted !== undefined &&
          KRAvatarCacheManager.compareGeneration(descriptor.commitGeneration, latestAccepted) < 0) {
          if (!KRAvatarCacheManager.restoreFinalizeTransaction(reservation)) {
            return KRAvatarCacheManager.payload(
              'error', context, new KRAvatarEmptyHeaders(), 'promotion_restore_failed'
            );
          }
          return KRAvatarCacheManager.payload('error', context, new KRAvatarEmptyHeaders(), 'stale_generation');
        }
        if (!stageAbsent || !finalPresent || finalSize !== incomingSize || !finalDecodable) {
          if (!KRAvatarCacheManager.restoreFinalizeTransaction(reservation)) {
            return KRAvatarCacheManager.payload(
              'error', context, new KRAvatarEmptyHeaders(), 'promotion_restore_failed'
            );
          }
          return KRAvatarCacheManager.payload(
            'error', context, new KRAvatarEmptyHeaders(), 'promotion_postcondition_failed'
          );
        }
      } catch (error) {
        KRRenderLog.e(
          'KRAvatarCacheManager',
          `promotion verification failed: ${KRAvatarCacheManager.errorMessage(error)}`
        );
        if (!KRAvatarCacheManager.restoreFinalizeTransaction(reservation)) {
          return KRAvatarCacheManager.payload(
            'error', context, new KRAvatarEmptyHeaders(), 'promotion_restore_failed'
          );
        }
        return KRAvatarCacheManager.payload(
          'error', context, new KRAvatarEmptyHeaders(), 'promotion_postcondition_failed'
        );
      }
      if (!KRAvatarCacheManager.runtime.retainDecoded(
        descriptor.nativeBytesKey,
        descriptor.commitGeneration,
        descriptor.callerAuthority,
        reservation.priorAcceptedGeneration,
        reservation.priorAcceptedCallerAuthority
      )) {
        if (!KRAvatarCacheManager.restoreFinalizeTransaction(reservation)) {
          return KRAvatarCacheManager.payload(
            'error', context, new KRAvatarEmptyHeaders(), 'promotion_restore_failed'
          );
        }
        return KRAvatarCacheManager.payload('error', context, new KRAvatarEmptyHeaders(), 'decoded_promotion_failed');
      }
      KRAvatarCacheManager.acceptedGenerationByIdentity.set(identity, descriptor.commitGeneration);
      KRAvatarCacheManager.acceptedCallerAuthorityByIdentity.set(identity, descriptor.callerAuthority);
      KRAvatarCacheManager.touch(descriptor);
      receipt.finalized = true;
      reservation.preparedForSharedAck = true;
      reservation.rollbackDeadlineMillis = KRAvatarCacheManager.runtime.now() +
        KRAvatarCacheManager.FINALIZE_ROLLBACK_WINDOW_MILLIS;
      KRAvatarCacheManager.runtime.schedule(
        KRAvatarCacheManager.FINALIZE_ROLLBACK_WINDOW_MILLIS,
        (): void => { KRAvatarCacheManager.expireFinalizeTransaction(reservation); }
      );
      completedReservation = reservation;
    } else if (outcome === 'not_modified') {
      const identity = KRAvatarCacheManager.identityKey(descriptor);
      if (KRAvatarCacheManager.isIdentityQuarantined(context, descriptor)) {
        return KRAvatarCacheManager.payload(
          'error', context, new KRAvatarEmptyHeaders(), 'not_modified_final_quarantined'
        );
      }
      const operation = KRAvatarCacheManager.operationKey(descriptor);
      const receipt = KRAvatarCacheManager.finalizeReceiptByOperation.get(operation);
      if (receipt === undefined || !KRAvatarCacheManager.isFinalizeReceiptLive(
        receipt,
        descriptor,
        'not_modified',
        operationReceipt
      )) {
        return KRAvatarCacheManager.payload(
          'error', context, new KRAvatarEmptyHeaders(), 'not_modified_without_terminal'
        );
      }
      if (receipt.finalized) {
        const existing = KRAvatarCacheManager.finalizeByIdentity.get(identity);
        const acknowledged = KRAvatarCacheManager.ackedFinalizeGenerationByIdentity.get(identity);
        if ((existing !== undefined &&
          existing.descriptor.commitGeneration === descriptor.commitGeneration &&
          existing.preparedForSharedAck &&
          existing.finalizeAttempt === finalizeAttempt)) {
          return KRAvatarCacheManager.finalizePayload(context, existing);
        }
        if (existing !== undefined &&
          existing.descriptor.commitGeneration === descriptor.commitGeneration) {
          return KRAvatarCacheManager.payload(
            'error', context, new KRAvatarEmptyHeaders(), 'finalize_in_progress'
          );
        }
        if (acknowledged === descriptor.commitGeneration) {
          return KRAvatarCacheManager.payload('ok', context);
        }
        return KRAvatarCacheManager.payload(
          'error', context, new KRAvatarEmptyHeaders(), 'not_modified_transaction_missing'
        );
      }
      const finalPath = KRAvatarCacheManager.finalPath(context, descriptor);
      if (!KRAvatarCacheManager.runtime.exists(finalPath) ||
        !await KRAvatarCacheManager.runtime.isDecodable(finalPath) ||
        !KRAvatarCacheManager.runtime.hasDecoded(
          descriptor.nativeBytesKey,
          descriptor.commitGeneration,
          descriptor.callerAuthority
        )) {
        return KRAvatarCacheManager.payload(
          'error', context, new KRAvatarEmptyHeaders(), 'not_modified_final_unusable'
        );
      }
      const accepted = KRAvatarCacheManager.acceptedGenerationByIdentity.get(identity);
      if (accepted !== undefined && KRAvatarCacheManager.compareGeneration(descriptor.commitGeneration, accepted) < 0) {
        return KRAvatarCacheManager.payload('error', context, new KRAvatarEmptyHeaders(), 'stale_generation');
      }
      if (accepted !== undefined && accepted !== descriptor.commitGeneration &&
        !KRAvatarCacheManager.runtime.hasDecoded(
          descriptor.nativeBytesKey,
          accepted,
          KRAvatarCacheManager.acceptedCallerAuthorityByIdentity.get(identity) ?? ''
        )) {
        return KRAvatarCacheManager.payload(
          'error', context, new KRAvatarEmptyHeaders(), 'not_modified_prior_unavailable'
        );
      }
      if (!KRAvatarCacheManager.finalizeIdentityAvailable(identity)) {
        return KRAvatarCacheManager.payload(
          'error', context, new KRAvatarEmptyHeaders(), 'finalize_in_progress'
        );
      }
      if (!KRAvatarCacheManager.removeIfPresent(KRAvatarCacheManager.stagePath(context, descriptor))) {
        return KRAvatarCacheManager.payload('error', context, new KRAvatarEmptyHeaders(), 'stage_delete_failed');
      }
      const reservation = KRAvatarCacheManager.reserveFinalize(context, descriptor, receipt, finalizeAttempt);
      if (reservation === null) {
        return KRAvatarCacheManager.payload(
          'error', context, new KRAvatarEmptyHeaders(), 'finalize_in_progress'
        );
      }
      KRAvatarCacheManager.acceptedGenerationByIdentity.set(identity, descriptor.commitGeneration);
      KRAvatarCacheManager.acceptedCallerAuthorityByIdentity.set(identity, descriptor.callerAuthority);
      KRAvatarCacheManager.touch(descriptor);
      receipt.finalized = true;
      reservation.preparedForSharedAck = true;
      reservation.rollbackDeadlineMillis = KRAvatarCacheManager.runtime.now() +
        KRAvatarCacheManager.FINALIZE_ROLLBACK_WINDOW_MILLIS;
      KRAvatarCacheManager.runtime.schedule(
        KRAvatarCacheManager.FINALIZE_ROLLBACK_WINDOW_MILLIS,
        (): void => { KRAvatarCacheManager.expireFinalizeTransaction(reservation); }
      );
      completedReservation = reservation;
    } else {
      if (!KRAvatarCacheManager.removeIfPresent(KRAvatarCacheManager.stagePath(context, descriptor))) {
        return KRAvatarCacheManager.payload('error', context, new KRAvatarEmptyHeaders(), 'stage_delete_failed');
      }
      if (!KRAvatarCacheManager.runtime.retireDecoded(
        descriptor.nativeBytesKey, descriptor.commitGeneration, descriptor.callerAuthority
      )) {
        return KRAvatarCacheManager.payload('error', context, new KRAvatarEmptyHeaders(), 'decoded_retire_failed');
      }
      KRAvatarCacheManager.finalizeReceiptByOperation.delete(KRAvatarCacheManager.operationKey(descriptor));
    }
    KRAvatarCacheManager.resultByOperation.delete(KRAvatarCacheManager.operationKey(descriptor));
    if (!KRAvatarCacheManager.finalizeByIdentity.has(KRAvatarCacheManager.identityKey(descriptor))) {
      KRAvatarCacheManager.unregisterOperation(KRAvatarCacheManager.operationKey(descriptor));
    }
    if (completedReservation !== null) {
      return KRAvatarCacheManager.finalizePayload(context, completedReservation);
    }
    return KRAvatarCacheManager.payload('ok', context);
  }

  private static ackFinalize(
    context: common.UIAbilityContext,
    command: Record<string, Object>
  ): KRAvatarCacheBridgePayload {
    if (!KRAvatarCacheManager.validCommand(command, true)) return KRAvatarCacheManager.payload('unsupported', context);
    const descriptor = KRAvatarCacheManager.commandDescriptor(command);
    const identity = KRAvatarCacheManager.identityKey(descriptor);
    const reservation = KRAvatarCacheManager.finalizeByIdentity.get(identity);
    if (reservation === undefined ||
      reservation.descriptor.commitGeneration !== descriptor.commitGeneration ||
      !KRAvatarCacheManager.commandMatchesFinalizeToken(command, reservation)) {
      return KRAvatarCacheManager.payload('ok', context);
    }
    if (!reservation.preparedForSharedAck) {
      return KRAvatarCacheManager.payload('error', context, new KRAvatarEmptyHeaders(), 'finalize_not_prepared');
    }
    reservation.sharedAcked = true;
    KRAvatarCacheManager.recordFinalizeAcknowledgement(reservation);
    if (!KRAvatarCacheManager.commitFinalizeTransaction(reservation)) {
      return KRAvatarCacheManager.payload(
        'error', context, new KRAvatarEmptyHeaders(), 'finalize_commit_failed'
      );
    }
    return KRAvatarCacheManager.payload('ok', context);
  }

  private static rollback(
    context: common.UIAbilityContext,
    command: Record<string, Object>
  ): KRAvatarCacheBridgePayload {
    if (!KRAvatarCacheManager.validCommand(command, true)) return KRAvatarCacheManager.payload('unsupported', context);
    const descriptor = KRAvatarCacheManager.commandDescriptor(command);
    const identity = KRAvatarCacheManager.identityKey(descriptor);
    const reservation = KRAvatarCacheManager.finalizeByIdentity.get(identity);
    let decodedRetired = false;
    if (reservation !== undefined &&
      reservation.descriptor.commitGeneration === descriptor.commitGeneration) {
      if (!KRAvatarCacheManager.commandMatchesFinalizeToken(command, reservation)) {
        return KRAvatarCacheManager.payload('ok', context);
      }
      if (reservation.sharedAcked) {
        KRAvatarCacheManager.commitFinalizeTransaction(reservation);
        decodedRetired = true;
      } else if (reservation.preparedForSharedAck &&
        KRAvatarCacheManager.runtime.now() >= reservation.rollbackDeadlineMillis) {
        reservation.sharedAcked = true;
        KRAvatarCacheManager.recordFinalizeAcknowledgement(reservation);
        KRAvatarCacheManager.commitFinalizeTransaction(reservation);
        decodedRetired = true;
      } else {
        if (!KRAvatarCacheManager.restoreFinalizeTransaction(reservation)) {
          return KRAvatarCacheManager.payload('error', context, new KRAvatarEmptyHeaders(), 'rollback_restore_failed');
        }
        decodedRetired = true;
      }
    }
    if (reservation === undefined &&
      KRAvatarCacheManager.stringValue(command['finalizeReceipt']).length > 0) {
      return KRAvatarCacheManager.payload('ok', context);
    }
    if (reservation === undefined) {
      const operation = KRAvatarCacheManager.operationKey(descriptor);
      const receipt = KRAvatarCacheManager.finalizeReceiptByOperation.get(operation);
      const operationReceipt = KRAvatarCacheManager.stringValue(command['operationReceipt']);
      const finalizeAttempt = KRAvatarCacheManager.stringValue(command['finalizeAttempt']);
      if (receipt === undefined ||
        !KRAvatarCacheManager.isOpaqueKey(finalizeAttempt) ||
        !KRAvatarCacheManager.isOpaqueKey(operationReceipt) ||
        !KRAvatarCacheManager.isFinalizeReceiptLive(
          receipt,
          descriptor,
          receipt.status,
          operationReceipt
        )) {
        return KRAvatarCacheManager.payload('ok', context);
      }
    }
    const ackedGeneration = KRAvatarCacheManager.ackedFinalizeGenerationByIdentity.get(identity);
    if (!decodedRetired && ackedGeneration !== undefined &&
      KRAvatarCacheManager.compareGeneration(descriptor.commitGeneration, ackedGeneration) <= 0) {
      decodedRetired = true;
    }
    if (!KRAvatarCacheManager.removeIfPresent(KRAvatarCacheManager.stagePath(context, descriptor))) {
      return KRAvatarCacheManager.payload('error', context, new KRAvatarEmptyHeaders(), 'stage_delete_failed');
    }
    if (!decodedRetired &&
      !KRAvatarCacheManager.runtime.retireDecoded(
        descriptor.nativeBytesKey, descriptor.commitGeneration, descriptor.callerAuthority
      )) {
      return KRAvatarCacheManager.payload('error', context, new KRAvatarEmptyHeaders(), 'decoded_retire_failed');
    }
    const operation = KRAvatarCacheManager.operationKey(descriptor);
    KRAvatarCacheManager.resultByOperation.delete(operation);
    KRAvatarCacheManager.finalizeReceiptByOperation.delete(operation);
    KRAvatarCacheManager.unregisterOperation(operation);
    return KRAvatarCacheManager.payload('ok', context);
  }

  private static retire(
    context: common.UIAbilityContext,
    command: Record<string, Object>,
    requireSubject: boolean
  ): KRAvatarCacheBridgePayload {
    if (!KRAvatarCacheManager.validCommand(command, false)) return KRAvatarCacheManager.payload('unsupported', context);
    const partition = KRAvatarCacheManager.stringValue(command['nativePartitionKey']);
    const subject = KRAvatarCacheManager.stringValue(command['nativeSubjectKey']);
    if (!KRAvatarCacheManager.isOpaqueKey(partition) || requireSubject && !KRAvatarCacheManager.isOpaqueKey(subject)) {
      return KRAvatarCacheManager.payload('unsupported', context);
    }
    const retirementScope = requireSubject ?
      KRAvatarCacheManager.subjectRetirementScope(partition, subject) :
      KRAvatarCacheManager.ownerRetirementScope(partition);
    KRAvatarCacheManager.enterRetirementScope(retirementScope);
    try {
      if (!KRAvatarCacheManager.prequarantineManagedFiles(
        context,
        (candidatePartition: string, candidateSubject: string): boolean =>
          candidatePartition === partition && (!requireSubject || candidateSubject === subject),
        retirementScope
      )) {
        return KRAvatarCacheManager.payload(
          'error', context, new KRAvatarEmptyHeaders(), 'retire_quarantine_failed'
        );
      }
      if (requireSubject) {
        const key = KRAvatarCacheManager.subjectIdentity(partition, subject);
        KRAvatarCacheManager.subjectEpoch.set(key, (KRAvatarCacheManager.subjectEpoch.get(key) ?? 0) + 1);
      } else {
        KRAvatarCacheManager.ownerEpoch.set(partition, (KRAvatarCacheManager.ownerEpoch.get(partition) ?? 0) + 1);
      }
      const matches = (descriptor: KRAvatarDescriptor): boolean => descriptor.nativePartitionKey === partition &&
        (!requireSubject || descriptor.nativeSubjectKey === subject);
      let success = KRAvatarCacheManager.retireFinalizations(matches);
    if (!KRAvatarCacheManager.cancelActiveOperations(
      matches,
      context,
      'retired'
    )) success = false;
    KRAvatarCacheManager.retireOperationState(matches);
    KRAvatarCacheManager.clearAcceptedGenerations(partition, subject, requireSubject);
    let failureCode = success ? '' : 'active_retire_failed';
    try {
      const managed = KRAvatarCacheManager.managedFiles(context);
      for (const entry of managed) {
        if (entry.partitionKey === partition && (!requireSubject || entry.subjectKey === subject)) {
          try {
            if (!KRAvatarCacheManager.deleteManagedFile(context, entry)) {
              success = false;
              if (failureCode.length === 0) failureCode = 'retire_delete_failed';
            }
          } catch (error) {
            success = false;
            if (failureCode.length === 0) failureCode = 'retire_delete_failed';
            KRRenderLog.e('KRAvatarCacheManager', `retire managed failed: ${KRAvatarCacheManager.errorMessage(error)}`);
          }
        }
      }
    } catch (error) {
      success = false;
      if (failureCode.length === 0) failureCode = 'retire_scan_failed';
      KRRenderLog.e('KRAvatarCacheManager', `retire managed scan failed: ${KRAvatarCacheManager.errorMessage(error)}`);
    }
    try {
      const stages = KRAvatarCacheManager.stageFiles(context);
      for (const path of stages) {
        const name = path.substring(path.lastIndexOf('/') + 1);
        if (name.startsWith(`${partition}.${requireSubject ? `${subject}.` : ''}`)) {
          try {
            if (!KRAvatarCacheManager.deleteRetiredStageFile(path)) {
              success = false;
              if (failureCode.length === 0) failureCode = 'retire_stage_failed';
            }
          } catch (error) {
            success = false;
            if (failureCode.length === 0) failureCode = 'retire_stage_failed';
            KRRenderLog.e('KRAvatarCacheManager', `retire stage failed: ${KRAvatarCacheManager.errorMessage(error)}`);
          }
        }
      }
    } catch (error) {
      success = false;
      if (failureCode.length === 0) failureCode = 'retire_stage_scan_failed';
      KRRenderLog.e('KRAvatarCacheManager', `retire stage scan failed: ${KRAvatarCacheManager.errorMessage(error)}`);
    }
    if (!success) {
      return KRAvatarCacheManager.payload('error', context, new KRAvatarEmptyHeaders(), failureCode);
    }
    return KRAvatarCacheManager.payload('ok', context);
    } finally {
      KRAvatarCacheManager.leaveRetirementScope(retirementScope);
    }
  }

  private static deleteManagedFile(
    context: common.UIAbilityContext,
    entry: KRAvatarManagedFile
  ): boolean {
    const identity = `${entry.partitionKey}:${entry.subjectKey}:${entry.bytesKey}`;
    if (KRAvatarCacheManager.finalizeByIdentity.has(identity)) return false;
    const retirementScope = KRAvatarCacheManager.identityRetirementScope(identity);
    KRAvatarCacheManager.enterRetirementScope(retirementScope);
    try {
      const quarantinePath = KRAvatarCacheManager.quarantinePathForFinal(entry.path);
      let quarantineAlreadyPresent = false;
      try {
        quarantineAlreadyPresent = KRAvatarCacheManager.runtime.exists(quarantinePath);
      } catch (error) {
        KRRenderLog.e(
          'KRAvatarCacheManager',
          `quarantine preflight failed: ${KRAvatarCacheManager.errorMessage(error)}`
        );
        return false;
      }
      if (!quarantineAlreadyPresent && !KRAvatarCacheManager.ensureQuarantineMarker(quarantinePath)) return false;
      // Native retirement is an atomic precondition. On false it has zero effect, so the
      // still-present file and all manager authority remain one coherent, retryable state.
      if (!KRAvatarCacheManager.runtime.retireDecoded(entry.bytesKey)) {
        if (!quarantineAlreadyPresent) KRAvatarCacheManager.removeIfPresent(quarantinePath);
        return false;
      }
      KRAvatarCacheManager.identityEpoch.set(identity, (KRAvatarCacheManager.identityEpoch.get(identity) ?? 0) + 1);
      const matches = (descriptor: KRAvatarDescriptor): boolean =>
        KRAvatarCacheManager.identityKey(descriptor) === identity;
      // Every live admission, terminal, generation watermark and native PixelMap is retired
      // before the first fallible unlink. Filesystem failures therefore leave only inert bytes.
      let success = KRAvatarCacheManager.cancelActiveOperations(matches, context, 'retired');
      KRAvatarCacheManager.retireIdentityOperationState(identity);
      KRAvatarCacheManager.invalidateIdentityAuthority(identity);
      // Keep the final enumerable until every completed stage for this identity is gone.
      // Otherwise a stage-only unlink failure can make the next budget retry lose both
      // accounting and discovery authority for the orphan stage.
      const stagesDeleted = KRAvatarCacheManager.deleteIdentityStages(entry);
      if (!stagesDeleted) success = false;
      if (stagesDeleted && !KRAvatarCacheManager.removeIfPresent(entry.path)) success = false;
      if (success && !KRAvatarCacheManager.removeQuarantineMarker(quarantinePath, true)) success = false;
      return success;
    } finally {
      KRAvatarCacheManager.leaveRetirementScope(retirementScope);
    }
  }

  private static prepareBudgetForPromotion(
    context: common.UIAbilityContext,
    retainedDescriptor: KRAvatarDescriptor,
    incomingSize: number
  ): boolean {
    let files = KRAvatarCacheManager.managedFiles(context);
    const retainedIdentity = KRAvatarCacheManager.identityKey(retainedDescriptor);
    let total = incomingSize;
    files.forEach((entry: KRAvatarManagedFile): void => {
      if (`${entry.partitionKey}:${entry.subjectKey}:${entry.bytesKey}` !== retainedIdentity) total += entry.sizeBytes;
    });
    if (total <= KRAvatarCacheManager.MAX_MANAGED_BYTES) return true;
    files = files.sort((left: KRAvatarManagedFile, right: KRAvatarManagedFile): number => left.lastAccess - right.lastAccess);
    for (const entry of files) {
      if (total <= KRAvatarCacheManager.MAX_MANAGED_BYTES ||
        `${entry.partitionKey}:${entry.subjectKey}:${entry.bytesKey}` === retainedIdentity) continue;
      if (!KRAvatarCacheManager.deleteManagedFile(context, entry)) return false;
      total -= entry.sizeBytes;
    }
    return total <= KRAvatarCacheManager.MAX_MANAGED_BYTES;
  }

  private static payload(
    status: string,
    context: common.UIAbilityContext,
    headers: Object = new KRAvatarEmptyHeaders(),
    code: string = ''
  ): KRAvatarCacheBridgePayload {
    const payload = KRAvatarCacheManager.payloadWithoutContext(status);
    payload.managedBytes = `${KRAvatarCacheManager.managedBytes(context)}`;
    payload.eTag = KRAvatarCacheManager.header(headers, 'etag');
    payload.lastModified = KRAvatarCacheManager.header(headers, 'last-modified');
    payload.contentType = KRAvatarCacheManager.header(headers, 'content-type');
    if (code.length > 0) payload.code = code;
    return payload;
  }

  private static payloadWithoutContext(status: string): KRAvatarCacheBridgePayload {
    return {
      status: status,
      managedSet: KRAvatarCacheManager.MANAGED_SET,
      managedIndex: KRAvatarCacheManager.MANAGED_INDEX,
      budgetScope: KRAvatarCacheManager.BUDGET_SCOPE,
      indexedRetirement: true,
      managedBytes: '0'
    };
  }

  private static safePayload(
    context: common.UIAbilityContext,
    status: string,
    headers: Object = new KRAvatarEmptyHeaders(),
    code: string = '',
    sizeBytes: string = ''
  ): KRAvatarCacheBridgePayload {
    try {
      const payload = KRAvatarCacheManager.payload(status, context, headers, code);
      if (sizeBytes.length > 0) payload.sizeBytes = sizeBytes;
      return payload;
    } catch (error) {
      KRRenderLog.e(
        'KRAvatarCacheManager',
        `terminal accounting failed: ${KRAvatarCacheManager.errorMessage(error)}`
      );
      const fallback = KRAvatarCacheManager.payloadWithoutContext('error');
      fallback.code = 'avatar_cache_native_failed';
      return fallback;
    }
  }

  private static completeOperation(
    context: common.UIAbilityContext,
    token: KRAvatarFetchToken,
    renderPath: string | null,
    status: string,
    headers: Object = new KRAvatarEmptyHeaders(),
    code: string = '',
    sizeBytes: string = ''
  ): void {
    const payload = KRAvatarCacheManager.safePayload(context, status, headers, code, sizeBytes);
    // Protocol settlement always precedes render delivery. Accounting failure turns the render into null.
    const lifecycle = KRAvatarCacheManager.lifecycleById.get(token.lifecycleId);
    if (lifecycle === undefined || lifecycle.settled) return;
    lifecycle.terminal = {
      payload: payload,
      renderPath: payload.status === 'error' ? null : renderPath
    };
    if (payload.status === 'success' || payload.status === 'not_modified') {
      payload.operationReceipt = token.operationReceipt;
      KRAvatarCacheManager.finalizeReceiptByOperation.set(token.operation, {
        lifecycleId: token.lifecycleId,
        operationReceipt: token.operationReceipt,
        identity: KRAvatarCacheManager.identityKey(token.descriptor),
        generation: token.descriptor.commitGeneration,
        callerAuthority: token.descriptor.callerAuthority,
        status: payload.status,
        globalEpoch: token.globalEpoch,
        ownerEpoch: token.ownerEpoch,
        subjectEpoch: token.subjectEpoch,
        identityEpoch: token.identityEpoch,
        finalized: false
      });
    }
    KRAvatarCacheManager.settleLifecycle(lifecycle, payload);
    KRAvatarCacheManager.deliverRenderOnce(token, payload.status === 'error' ? null : renderPath);
    KRAvatarCacheManager.deliverRenderJoiners(lifecycle, payload, renderPath);
  }

  private static deliverRenderOnce(token: KRAvatarFetchToken, renderPath: string | null): void {
    const callback = token.callback;
    token.callback = null;
    if (callback === null) return;
    try {
      callback(renderPath);
    } catch (error) {
      KRRenderLog.e('KRAvatarCacheManager', `render callback failed: ${KRAvatarCacheManager.errorMessage(error)}`);
    }
  }

  private static addWaiter(operation: string, waiter: KRAvatarResultWaiter): void {
    const waiters = KRAvatarCacheManager.waitersByOperation.get(operation) ?? [];
    waiters.push(waiter);
    KRAvatarCacheManager.waitersByOperation.set(operation, waiters);
  }

  private static addRenderJoiner(token: KRAvatarFetchToken): void {
    const joiners = KRAvatarCacheManager.renderJoinersByLifecycle.get(token.lifecycleId) ?? [];
    joiners.push(token);
    KRAvatarCacheManager.renderJoinersByLifecycle.set(token.lifecycleId, joiners);
  }

  private static admitOperation(
    operation: string,
    token: KRAvatarFetchToken
  ): KRAvatarFetchToken {
    const active = KRAvatarCacheManager.currentLifecycleByOperation.get(operation);
    if (active !== undefined && KRAvatarCacheManager.activeFetches.get(operation) === active.leader) {
      token.lifecycleId = active.id;
      token.operationReceipt = active.operationReceipt;
      return active.leader;
    }
    KRAvatarCacheManager.finalizeReceiptByOperation.delete(operation);
    const operationReceipt = KRAvatarCacheManager.nextOpaqueReceipt(
      KRAvatarCacheManager.nextOperationReceiptId++
    );
    const lifecycle: KRAvatarOperationLifecycle = {
      id: KRAvatarCacheManager.nextLifecycleId,
      operationReceipt: operationReceipt,
      operation: operation,
      descriptor: token.descriptor,
      leader: token,
      settled: false,
      terminal: null
    };
    KRAvatarCacheManager.nextLifecycleId += 1;
    token.lifecycleId = lifecycle.id;
    token.operationReceipt = operationReceipt;
    KRAvatarCacheManager.lifecycleById.set(lifecycle.id, lifecycle);
    KRAvatarCacheManager.currentLifecycleByOperation.set(operation, lifecycle);
    KRAvatarCacheManager.activeFetches.set(operation, token);
    return token;
  }

  private static deliverTerminalOrJoin(token: KRAvatarFetchToken): void {
    const lifecycle = KRAvatarCacheManager.lifecycleById.get(token.lifecycleId);
    if (lifecycle === undefined) {
      KRAvatarCacheManager.deliverRenderOnce(token, null);
      return;
    }
    if (lifecycle.terminal !== null) {
      KRAvatarCacheManager.deliverRenderOnce(token, lifecycle.terminal.renderPath);
      return;
    }
    KRAvatarCacheManager.addRenderJoiner(token);
  }

  private static renderFollower(
    context: common.UIAbilityContext,
    token: KRAvatarFetchToken,
    finalPath: string,
    finalUsable: boolean
  ): void {
    const mode = token.descriptor.readMode;
    if (mode === 'render_fresh' || mode === 'render_stale_suppressed' || mode === 'render_stale_revalidate') {
      if (finalUsable) {
        KRAvatarCacheManager.touch(token.descriptor);
        const promoted = KRAvatarCacheManager.runtime.promoteDecoded(
          token.descriptor.nativeBytesKey,
          token.descriptor.commitGeneration,
          token.descriptor.callerAuthority
        );
        KRAvatarCacheManager.deliverRenderOnce(token, promoted ? finalPath : null);
        return;
      }
      if (mode === 'render_stale_revalidate') {
        KRAvatarCacheManager.deliverTerminalOrJoin(token);
        return;
      }
    }
    KRAvatarCacheManager.deliverRenderOnce(token, null);
  }

  private static deliverRenderJoiners(
    lifecycle: KRAvatarOperationLifecycle,
    payload: KRAvatarCacheBridgePayload,
    renderPath: string | null
  ): void {
    const joiners = KRAvatarCacheManager.renderJoinersByLifecycle.get(lifecycle.id) ?? [];
    KRAvatarCacheManager.renderJoinersByLifecycle.delete(lifecycle.id);
    const joinedPath = payload.status === 'error' ? null : renderPath;
    joiners.forEach((joiner: KRAvatarFetchToken): void => {
      KRAvatarCacheManager.deliverRenderOnce(joiner, joinedPath);
    });
  }

  private static settleLifecycle(
    lifecycle: KRAvatarOperationLifecycle,
    payload: KRAvatarCacheBridgePayload
  ): boolean {
    if (lifecycle.settled) return true;
    // Mark the lifecycle before invoking protocol callbacks so retirement/reentrancy cannot publish it twice.
    lifecycle.settled = true;
    return KRAvatarCacheManager.settleOperation(lifecycle.operation, payload);
  }

  private static releaseLifecycleIfUnused(lifecycleId: number): void {
    const lifecycle = KRAvatarCacheManager.lifecycleById.get(lifecycleId);
    if (lifecycle === undefined || !lifecycle.settled) return;
    let active = false;
    KRAvatarCacheManager.activeOperations.forEach((token: KRAvatarFetchToken): void => {
      if (token.lifecycleId === lifecycleId) active = true;
    });
    if (active || (KRAvatarCacheManager.renderJoinersByLifecycle.get(lifecycleId) ?? []).length > 0) return;
    KRAvatarCacheManager.lifecycleById.delete(lifecycleId);
  }

  private static isDescriptorRetiring(descriptor: KRAvatarDescriptor): boolean {
    return KRAvatarCacheManager.retirementDepth(KRAvatarCacheManager.globalRetirementScope()) > 0 ||
      KRAvatarCacheManager.retirementDepth(
        KRAvatarCacheManager.ownerRetirementScope(descriptor.nativePartitionKey)
      ) > 0 ||
      KRAvatarCacheManager.retirementDepth(KRAvatarCacheManager.subjectRetirementScope(
        descriptor.nativePartitionKey,
        descriptor.nativeSubjectKey
      )) > 0 ||
      KRAvatarCacheManager.retirementDepth(
        KRAvatarCacheManager.identityRetirementScope(KRAvatarCacheManager.identityKey(descriptor))
      ) > 0;
  }

  private static globalRetirementScope(): string {
    return 'all';
  }

  private static ownerRetirementScope(partition: string): string {
    return `owner:${partition}`;
  }

  private static subjectRetirementScope(partition: string, subject: string): string {
    return `subject:${KRAvatarCacheManager.subjectIdentity(partition, subject)}`;
  }

  private static identityRetirementScope(identity: string): string {
    return `identity:${identity}`;
  }

  private static retirementDepth(scope: string): number {
    return KRAvatarCacheManager.retirementDepthByScope.get(scope) ?? 0;
  }

  private static enterRetirementScope(scope: string): void {
    KRAvatarCacheManager.retirementDepthByScope.set(scope, KRAvatarCacheManager.retirementDepth(scope) + 1);
    const frames = KRAvatarCacheManager.quarantineLeaseFramesByScope.get(scope) ?? new Array<Set<string>>();
    frames.push(new Set<string>());
    KRAvatarCacheManager.quarantineLeaseFramesByScope.set(scope, frames);
  }

  private static leaveRetirementScope(scope: string): void {
    KRAvatarCacheManager.releaseQuarantineLeases(scope);
    const depth = KRAvatarCacheManager.retirementDepth(scope);
    if (depth <= 1) {
      KRAvatarCacheManager.retirementDepthByScope.delete(scope);
      return;
    }
    KRAvatarCacheManager.retirementDepthByScope.set(scope, depth - 1);
  }

  private static deliverProtocolWaiter(
    waiter: KRAvatarResultWaiter,
    payload: KRAvatarCacheBridgePayload
  ): boolean {
    try {
      waiter(payload);
      return true;
    } catch (error) {
      KRRenderLog.e('KRAvatarCacheManager', `protocol settlement failed: ${KRAvatarCacheManager.errorMessage(error)}`);
      return false;
    }
  }

  private static commandDescriptor(command: Record<string, Object>): KRAvatarDescriptor {
    return {
      protocol: KRAvatarCacheManager.stringValue(command['protocol']),
      managedSet: KRAvatarCacheManager.stringValue(command['managedSet']),
      managedIndex: KRAvatarCacheManager.stringValue(command['managedIndex']),
      budgetScope: KRAvatarCacheManager.stringValue(command['budgetScope']),
      indexedRetirementRequired: command['indexedRetirementRequired'] === true,
      maxEntryBytes: KRAvatarCacheManager.stringValue(command['maxEntryBytes']),
      maxManagedBytes: KRAvatarCacheManager.stringValue(command['maxManagedBytes']),
      remoteUrl: KRAvatarCacheManager.stringValue(command['remoteUrl']),
      nativePartitionKey: KRAvatarCacheManager.stringValue(command['nativePartitionKey']),
      nativeSubjectKey: KRAvatarCacheManager.stringValue(command['nativeSubjectKey']),
      nativeBytesKey: KRAvatarCacheManager.stringValue(command['nativeBytesKey']),
      readMode: KRAvatarCacheManager.stringValue(command['readMode']),
      commitGeneration: KRAvatarCacheManager.stringValue(command['commitGeneration']),
      callerAuthority: KRAvatarCacheManager.stringValue(command['callerAuthority']),
      retryAtMillis: KRAvatarCacheManager.optionalString(command['retryAtMillis']),
      eTag: KRAvatarCacheManager.optionalString(command['eTag']),
      lastModified: KRAvatarCacheManager.optionalString(command['lastModified'])
    };
  }

  private static validCommand(command: Record<string, Object>, full: boolean): boolean {
    if (
      KRAvatarCacheManager.stringValue(command['protocol']) !== KRAvatarCacheManager.PROTOCOL ||
      KRAvatarCacheManager.stringValue(command['managedSet']) !== KRAvatarCacheManager.MANAGED_SET ||
      KRAvatarCacheManager.stringValue(command['managedIndex']) !== KRAvatarCacheManager.MANAGED_INDEX ||
      KRAvatarCacheManager.stringValue(command['budgetScope']) !== KRAvatarCacheManager.BUDGET_SCOPE ||
      command['indexedRetirementRequired'] !== true ||
      KRAvatarCacheManager.stringValue(command['maxEntryBytes']) !== `${KRAvatarCacheManager.MAX_ENTRY_BYTES}` ||
      KRAvatarCacheManager.stringValue(command['maxManagedBytes']) !== `${KRAvatarCacheManager.MAX_MANAGED_BYTES}`
    ) return false;
    return !full || KRAvatarCacheManager.validateDescriptor(KRAvatarCacheManager.commandDescriptor(command)) !== null;
  }

  private static validIndexedKeys(command: Record<string, Object>, requireGeneration: boolean): boolean {
    if (!KRAvatarCacheManager.validCommand(command, false)) return false;
    if (
      !KRAvatarCacheManager.isOpaqueKey(KRAvatarCacheManager.stringValue(command['nativePartitionKey'])) ||
      !KRAvatarCacheManager.isOpaqueKey(KRAvatarCacheManager.stringValue(command['nativeSubjectKey'])) ||
      !KRAvatarCacheManager.isOpaqueKey(KRAvatarCacheManager.stringValue(command['nativeBytesKey']))
    ) return false;
    return !requireGeneration ||
      KRAvatarCacheManager.isPositiveGeneration(KRAvatarCacheManager.stringValue(command['commitGeneration']));
  }

  private static finalPath(context: common.UIAbilityContext, descriptor: KRAvatarDescriptor): string {
    return `${KRAvatarCacheManager.directory(context)}/${descriptor.nativePartitionKey}.${descriptor.nativeSubjectKey}.${descriptor.nativeBytesKey}${KRAvatarCacheManager.FINAL_SUFFIX}`;
  }

  private static stagePath(context: common.UIAbilityContext, descriptor: KRAvatarDescriptor): string {
    return `${KRAvatarCacheManager.directory(context)}/${descriptor.nativePartitionKey}.${descriptor.nativeSubjectKey}.` +
      `${descriptor.nativeBytesKey}.g${descriptor.commitGeneration}.a${descriptor.callerAuthority}.stage`;
  }

  private static finalizeBackupPath(context: common.UIAbilityContext, descriptor: KRAvatarDescriptor): string {
    return `${KRAvatarCacheManager.directory(context)}/${descriptor.nativePartitionKey}.${descriptor.nativeSubjectKey}.` +
      `${descriptor.nativeBytesKey}.g${descriptor.commitGeneration}.a${descriptor.callerAuthority}.prior.stage`;
  }

  private static directory(context: common.UIAbilityContext): string {
    const path = `${context.cacheDir}/${KRAvatarCacheManager.DIRECTORY_NAME}`;
    KRAvatarCacheManager.runtime.ensureDirectory(path);
    return path;
  }

  private static managedFiles(context: common.UIAbilityContext): Array<KRAvatarManagedFile> {
    const directory = KRAvatarCacheManager.directory(context);
    const files: Array<KRAvatarManagedFile> = [];
    KRAvatarCacheManager.runtime.list(directory).forEach((name: string): void => {
      if (!name.endsWith(KRAvatarCacheManager.FINAL_SUFFIX)) return;
      const parts = name.substring(0, name.length - KRAvatarCacheManager.FINAL_SUFFIX.length).split('.');
      if (parts.length !== 3 || parts.some((part: string): boolean => !KRAvatarCacheManager.isOpaqueKey(part))) return;
      const path = `${directory}/${name}`;
      const stat: KRAvatarFileStat = KRAvatarCacheManager.runtime.stat(path);
      files.push({
        path: path,
        name: name,
        partitionKey: parts[0],
        subjectKey: parts[1],
        bytesKey: parts[2],
        sizeBytes: stat.size,
        lastAccess: KRAvatarCacheManager.accessByIdentity.get(`${parts[0]}:${parts[1]}:${parts[2]}`) ?? stat.mtime
      });
    });
    return files;
  }

  private static prequarantineManagedFiles(
    context: common.UIAbilityContext,
    matches: (partitionKey: string, subjectKey: string) => boolean,
    retirementScope: string
  ): boolean {
    try {
      const directory = KRAvatarCacheManager.directory(context);
      const names = KRAvatarCacheManager.runtime.list(directory);
      for (const name of names) {
        if (!name.endsWith(KRAvatarCacheManager.FINAL_SUFFIX)) continue;
        const parts = name.substring(0, name.length - KRAvatarCacheManager.FINAL_SUFFIX.length).split('.');
        if (parts.length !== 3 ||
          parts.some((part: string): boolean => !KRAvatarCacheManager.isOpaqueKey(part)) ||
          !matches(parts[0], parts[1])) continue;
        const finalPath = `${directory}/${name}`;
        const quarantinePath = KRAvatarCacheManager.quarantinePathForFinal(finalPath);
        if (!KRAvatarCacheManager.ensureQuarantineMarker(quarantinePath)) return false;
        KRAvatarCacheManager.acquireQuarantineLease(retirementScope, quarantinePath);
      }
      return true;
    } catch (error) {
      KRRenderLog.e(
        'KRAvatarCacheManager',
        `prequarantine scan failed: ${KRAvatarCacheManager.errorMessage(error)}`
      );
      return false;
    }
  }

  private static stageFiles(context: common.UIAbilityContext): Array<string> {
    const directory = KRAvatarCacheManager.directory(context);
    return KRAvatarCacheManager.runtime.list(directory)
      .filter((name: string): boolean => name.endsWith('.stage'))
      .map((name: string): string => `${directory}/${name}`);
  }

  private static stageIdentity(path: string): KRAvatarStageIdentity | null {
    const separator = path.lastIndexOf('/');
    const directory = separator < 0 ? '' : path.substring(0, separator);
    const name = separator < 0 ? path : path.substring(separator + 1);
    const parts = name.split('.');
    if (directory.length === 0 || parts.length < 6 || parts[parts.length - 1] !== 'stage' ||
      !KRAvatarCacheManager.isOpaqueKey(parts[0]) ||
      !KRAvatarCacheManager.isOpaqueKey(parts[1]) ||
      !KRAvatarCacheManager.isOpaqueKey(parts[2]) ||
      !parts[3].startsWith('g') ||
      !KRAvatarCacheManager.isPositiveGeneration(parts[3].substring(1)) ||
      !parts[4].startsWith('a') ||
      !KRAvatarCacheManager.isOpaqueKey(parts[4].substring(1))) return null;
    return {
      directory: directory,
      partitionKey: parts[0],
      subjectKey: parts[1],
      bytesKey: parts[2]
    };
  }

  private static stageMatchesIdentity(name: string, identity: KRAvatarStageIdentity): boolean {
    return name.startsWith(`${identity.partitionKey}.${identity.subjectKey}.${identity.bytesKey}.g`) &&
      name.endsWith('.stage');
  }

  private static identityHasStage(identity: KRAvatarStageIdentity): boolean {
    return KRAvatarCacheManager.runtime.list(identity.directory).some((name: string): boolean =>
      KRAvatarCacheManager.stageMatchesIdentity(name, identity)
    );
  }

  private static finalPathForStageIdentity(identity: KRAvatarStageIdentity): string {
    return `${identity.directory}/${identity.partitionKey}.${identity.subjectKey}.${identity.bytesKey}` +
      KRAvatarCacheManager.FINAL_SUFFIX;
  }

  private static deleteRetiredStageFile(path: string): boolean {
    // A prepared finalize token owns both its candidate stage and recovery backup. Generic
    // retirement scans run even after transaction cleanup reports a retryable failure, so they
    // must not consume either path out from under the next exact restore/abort attempt.
    if (KRAvatarCacheManager.finalizeOwnsStagePath(path)) return false;
    const identity = KRAvatarCacheManager.stageIdentity(path);
    if (identity === null) return KRAvatarCacheManager.deleteStageFile(path);
    const finalPath = KRAvatarCacheManager.finalPathForStageIdentity(identity);
    const quarantinePath = KRAvatarCacheManager.quarantinePathForFinal(finalPath);
    if (!KRAvatarCacheManager.ensureQuarantineMarker(quarantinePath)) return false;
    if (!KRAvatarCacheManager.deleteStageFile(path)) return false;
    try {
      if (KRAvatarCacheManager.runtime.exists(finalPath) || KRAvatarCacheManager.identityHasStage(identity)) return true;
      return KRAvatarCacheManager.removeQuarantineMarker(quarantinePath, true);
    } catch (error) {
      KRRenderLog.e('KRAvatarCacheManager', `stage quarantine verify failed: ${KRAvatarCacheManager.errorMessage(error)}`);
      return false;
    }
  }

  private static deleteIdentityStages(entry: KRAvatarManagedFile): boolean {
    const separator = entry.path.lastIndexOf('/');
    const identity: KRAvatarStageIdentity = {
      directory: entry.path.substring(0, separator),
      partitionKey: entry.partitionKey,
      subjectKey: entry.subjectKey,
      bytesKey: entry.bytesKey
    };
    try {
      const stages = KRAvatarCacheManager.runtime.list(identity.directory)
        .filter((name: string): boolean => KRAvatarCacheManager.stageMatchesIdentity(name, identity))
        .map((name: string): string => `${identity.directory}/${name}`);
      let success = true;
      stages.forEach((path: string): void => {
        if (!KRAvatarCacheManager.deleteRetiredStageFile(path)) success = false;
      });
      return success && !KRAvatarCacheManager.identityHasStage(identity);
    } catch (error) {
      KRRenderLog.e('KRAvatarCacheManager', `identity stage retirement failed: ${KRAvatarCacheManager.errorMessage(error)}`);
      return false;
    }
  }

  private static deleteStageFile(path: string): boolean {
    const name = path.substring(path.lastIndexOf('/') + 1);
    const parts = name.split('.');
    if (
      parts.length === 6 &&
      parts[5] === 'stage' &&
      KRAvatarCacheManager.isOpaqueKey(parts[2]) &&
      parts[3].startsWith('g') &&
      KRAvatarCacheManager.isPositiveGeneration(parts[3].substring(1)) &&
      parts[4].startsWith('a') &&
      KRAvatarCacheManager.isOpaqueKey(parts[4].substring(1))
    ) {
      if (!KRAvatarCacheManager.runtime.retireDecoded(
        parts[2], parts[3].substring(1), parts[4].substring(1)
      )) return false;
    }
    return KRAvatarCacheManager.removeIfPresent(path);
  }

  private static finalizeOwnsStagePath(path: string): boolean {
    let owned = false;
    KRAvatarCacheManager.finalizeByIdentity.forEach((token: KRAvatarFinalizeToken): void => {
      if (token.stagePath === path || token.backupPath === path) owned = true;
    });
    return owned;
  }

  private static touch(descriptor: KRAvatarDescriptor): void {
    KRAvatarCacheManager.accessByIdentity.set(
      KRAvatarCacheManager.identityKey(descriptor),
      KRAvatarCacheManager.runtime.now()
    );
  }

  private static operationKey(descriptor: KRAvatarDescriptor): string {
    return `${KRAvatarCacheManager.identityKey(descriptor)}:${descriptor.commitGeneration}:${descriptor.callerAuthority}`;
  }

  private static identityKey(descriptor: KRAvatarDescriptor): string {
    return `${descriptor.nativePartitionKey}:${descriptor.nativeSubjectKey}:${descriptor.nativeBytesKey}`;
  }

  private static subjectIdentity(partition: string, subject: string): string {
    return `${partition}:${subject}`;
  }

  private static compareGeneration(left: string, right: string): number {
    if (left.length !== right.length) return left.length < right.length ? -1 : 1;
    if (left === right) return 0;
    return left < right ? -1 : 1;
  }

  private static newFetchToken(
    operation: string,
    descriptor: KRAvatarDescriptor,
    callback: KuiklyRenderCallback | null
  ): KRAvatarFetchToken {
    return {
      operation: operation,
      lifecycleId: 0,
      operationReceipt: '',
      descriptor: descriptor,
      globalEpoch: KRAvatarCacheManager.globalEpoch,
      ownerEpoch: KRAvatarCacheManager.ownerEpoch.get(descriptor.nativePartitionKey) ?? 0,
      subjectEpoch: KRAvatarCacheManager.subjectEpoch.get(
        KRAvatarCacheManager.subjectIdentity(descriptor.nativePartitionKey, descriptor.nativeSubjectKey)
      ) ?? 0,
      identityEpoch: KRAvatarCacheManager.identityEpoch.get(KRAvatarCacheManager.identityKey(descriptor)) ?? 0,
      callback: callback
    };
  }

  private static isFetchTokenLive(token: KRAvatarFetchToken): boolean {
    return KRAvatarCacheManager.activeFetches.get(token.operation) === token &&
      KRAvatarCacheManager.isOperationTokenLive(token);
  }

  private static isOperationTokenLive(token: KRAvatarFetchToken): boolean {
    return KRAvatarCacheManager.activeOperations.has(token) &&
      token.globalEpoch === KRAvatarCacheManager.globalEpoch &&
      token.ownerEpoch === (KRAvatarCacheManager.ownerEpoch.get(token.descriptor.nativePartitionKey) ?? 0) &&
      token.subjectEpoch === (KRAvatarCacheManager.subjectEpoch.get(
        KRAvatarCacheManager.subjectIdentity(
          token.descriptor.nativePartitionKey,
          token.descriptor.nativeSubjectKey
        )
      ) ?? 0) &&
      token.identityEpoch === (KRAvatarCacheManager.identityEpoch.get(
        KRAvatarCacheManager.identityKey(token.descriptor)
      ) ?? 0);
  }

  private static cancelActiveOperations(
    matches: (descriptor: KRAvatarDescriptor) => boolean,
    context: common.UIAbilityContext,
    status: string
  ): boolean {
    let success = true;
    const cancelled: Array<KRAvatarFetchToken> = [];
    KRAvatarCacheManager.activeOperations.forEach((token: KRAvatarFetchToken): void => {
      if (matches(token.descriptor)) cancelled.push(token);
    });
    // Remove every admission before invoking any caller code. A callback cannot observe a sibling as live.
    cancelled.forEach((token: KRAvatarFetchToken): void => {
      if (KRAvatarCacheManager.activeFetches.get(token.operation) === token) {
        KRAvatarCacheManager.activeFetches.delete(token.operation);
      }
      KRAvatarCacheManager.activeOperations.delete(token);
    });
    // Settlement is deliberately before every fallible filesystem/decoded cleanup.
    const cancelledLifecycles: Set<number> = new Set();
    cancelled.forEach((token: KRAvatarFetchToken): void => {
      cancelledLifecycles.add(token.lifecycleId);
    });
    cancelledLifecycles.forEach((lifecycleId: number): void => {
      const lifecycle = KRAvatarCacheManager.lifecycleById.get(lifecycleId);
      if (lifecycle === undefined) return;
      const terminal = KRAvatarCacheManager.payloadWithoutContext(status);
      if (!lifecycle.settled) {
        lifecycle.terminal = {
          payload: terminal,
          renderPath: null
        };
        if (!KRAvatarCacheManager.settleLifecycle(lifecycle, terminal)) success = false;
      }
      KRAvatarCacheManager.deliverRenderJoiners(lifecycle, terminal, null);
      if (KRAvatarCacheManager.currentLifecycleByOperation.get(lifecycle.operation) === lifecycle) {
        KRAvatarCacheManager.currentLifecycleByOperation.delete(lifecycle.operation);
      }
    });
    cancelled.forEach((token: KRAvatarFetchToken): void => {
      KRAvatarCacheManager.deliverRenderOnce(token, null);
    });
    cancelled.forEach((token: KRAvatarFetchToken): void => {
      try {
        if (!KRAvatarCacheManager.removeIfPresent(KRAvatarCacheManager.stagePath(context, token.descriptor))) {
          success = false;
        }
      } catch (error) {
        success = false;
        KRRenderLog.e('KRAvatarCacheManager', `active stage retirement failed: ${KRAvatarCacheManager.errorMessage(error)}`);
      }
      try {
        if (!KRAvatarCacheManager.runtime.retireDecoded(
          token.descriptor.nativeBytesKey,
          token.descriptor.commitGeneration,
          token.descriptor.callerAuthority
        )) success = false;
      } catch (error) {
        success = false;
        KRRenderLog.e('KRAvatarCacheManager', `active decoded retirement failed: ${KRAvatarCacheManager.errorMessage(error)}`);
      }
      KRAvatarCacheManager.releaseLifecycleIfUnused(token.lifecycleId);
    });
    return success;
  }

  private static retireOperationState(
    matches: (descriptor: KRAvatarDescriptor) => boolean
  ): void {
    // Settle protocol state before any later filesystem failure can abort retirement.
    const retired = KRAvatarCacheManager.payloadWithoutContext('retired');
    KRAvatarCacheManager.descriptorByOperation.forEach((descriptor: KRAvatarDescriptor, operation: string): void => {
      if (!matches(descriptor)) return;
      KRAvatarCacheManager.finalizeReceiptByOperation.delete(operation);
      const lifecycle = KRAvatarCacheManager.currentLifecycleByOperation.get(operation);
      if (lifecycle !== undefined && !lifecycle.settled) {
        lifecycle.terminal = { payload: retired, renderPath: null };
        KRAvatarCacheManager.settleLifecycle(lifecycle, retired);
        KRAvatarCacheManager.deliverRenderJoiners(lifecycle, retired, null);
        return;
      }
      if (KRAvatarCacheManager.resultByOperation.has(operation)) {
        KRAvatarCacheManager.resultByOperation.set(operation, retired);
        return;
      }
      if ((KRAvatarCacheManager.waitersByOperation.get(operation) ?? []).length > 0) {
        KRAvatarCacheManager.settleOperation(operation, retired);
      }
    });
  }

  private static settleOperation(operation: string, payload: KRAvatarCacheBridgePayload): boolean {
    let success = true;
    const waiters = KRAvatarCacheManager.waitersByOperation.get(operation) ?? [];
    KRAvatarCacheManager.waitersByOperation.delete(operation);
    if (waiters.length === 0) {
      KRAvatarCacheManager.resultByOperation.set(operation, payload);
    } else {
      KRAvatarCacheManager.resultByOperation.delete(operation);
      KRAvatarCacheManager.unregisterOperation(operation);
    }
    waiters.forEach((waiter: KRAvatarResultWaiter): void => {
      if (!KRAvatarCacheManager.deliverProtocolWaiter(waiter, payload)) success = false;
    });
    return success;
  }

  private static clearAcceptedGenerations(partition: string, subject: string, requireSubject: boolean): void {
    const prefix = requireSubject ? `${partition}:${subject}:` : `${partition}:`;
    const identities: Array<string> = [];
    KRAvatarCacheManager.acceptedGenerationByIdentity.forEach((_generation: string, identity: string): void => {
      if (identity.startsWith(prefix)) identities.push(identity);
    });
    identities.forEach((identity: string): void => {
      KRAvatarCacheManager.invalidateIdentityAuthority(identity);
    });
  }

  private static invalidateIdentityAuthority(identity: string): void {
    KRAvatarCacheManager.accessByIdentity.delete(identity);
    KRAvatarCacheManager.acceptedGenerationByIdentity.delete(identity);
    KRAvatarCacheManager.acceptedCallerAuthorityByIdentity.delete(identity);
    KRAvatarCacheManager.ackedFinalizeGenerationByIdentity.delete(identity);
    KRAvatarCacheManager.retireIdentityOperationState(identity);
    const operations = Array.from(KRAvatarCacheManager.operationsByIdentity.get(identity) ?? new Set<string>());
    operations.forEach((operation: string): void => {
      KRAvatarCacheManager.finalizeReceiptByOperation.delete(operation);
    });
  }

  private static registerOperation(operation: string, descriptor: KRAvatarDescriptor): void {
    const identity = KRAvatarCacheManager.identityKey(descriptor);
    const priorIdentity = KRAvatarCacheManager.identityByOperation.get(operation);
    if (priorIdentity !== undefined && priorIdentity !== identity) {
      const priorOperations = KRAvatarCacheManager.operationsByIdentity.get(priorIdentity);
      priorOperations?.delete(operation);
      if ((priorOperations?.size ?? 0) === 0) KRAvatarCacheManager.operationsByIdentity.delete(priorIdentity);
    }
    KRAvatarCacheManager.descriptorByOperation.set(operation, descriptor);
    KRAvatarCacheManager.identityByOperation.set(operation, identity);
    const operations = KRAvatarCacheManager.operationsByIdentity.get(identity) ?? new Set<string>();
    operations.add(operation);
    KRAvatarCacheManager.operationsByIdentity.set(identity, operations);
  }

  private static unregisterOperation(operation: string): void {
    // A terminal fetch receipt remains retirement authority until finalize/rollback consumes it.
    // Keep its identity index even after the shared awaitResult waiter has consumed the payload.
    if (KRAvatarCacheManager.finalizeReceiptByOperation.has(operation)) return;
    KRAvatarCacheManager.descriptorByOperation.delete(operation);
    const identity = KRAvatarCacheManager.identityByOperation.get(operation);
    KRAvatarCacheManager.identityByOperation.delete(operation);
    if (identity === undefined) return;
    const operations = KRAvatarCacheManager.operationsByIdentity.get(identity);
    operations?.delete(operation);
    if ((operations?.size ?? 0) === 0) KRAvatarCacheManager.operationsByIdentity.delete(identity);
  }

  private static retireIdentityOperationState(identity: string): void {
    const retired = KRAvatarCacheManager.payloadWithoutContext('retired');
    const operations = Array.from(KRAvatarCacheManager.operationsByIdentity.get(identity) ?? new Set<string>());
    operations.forEach((operation: string): void => {
      KRAvatarCacheManager.finalizeReceiptByOperation.delete(operation);
      const lifecycle = KRAvatarCacheManager.currentLifecycleByOperation.get(operation);
      if (lifecycle !== undefined && !lifecycle.settled) {
        lifecycle.terminal = { payload: retired, renderPath: null };
        KRAvatarCacheManager.settleLifecycle(lifecycle, retired);
        KRAvatarCacheManager.deliverRenderJoiners(lifecycle, retired, null);
      } else if (KRAvatarCacheManager.resultByOperation.has(operation)) {
        KRAvatarCacheManager.resultByOperation.set(operation, retired);
      } else if ((KRAvatarCacheManager.waitersByOperation.get(operation) ?? []).length > 0) {
        KRAvatarCacheManager.settleOperation(operation, retired);
      }
      if (KRAvatarCacheManager.currentLifecycleByOperation.get(operation) === lifecycle) {
        KRAvatarCacheManager.currentLifecycleByOperation.delete(operation);
      }
    });
  }

  private static isFinalizeReceiptLive(
    receipt: KRAvatarFinalizeReceipt,
    descriptor: KRAvatarDescriptor,
    status: string,
    operationReceipt: string
  ): boolean {
    const operation = KRAvatarCacheManager.operationKey(descriptor);
    const lifecycle = KRAvatarCacheManager.currentLifecycleByOperation.get(operation);
    return receipt.status === status &&
      receipt.operationReceipt === operationReceipt &&
      receipt.identity === KRAvatarCacheManager.identityKey(descriptor) &&
      receipt.generation === descriptor.commitGeneration &&
      receipt.callerAuthority === descriptor.callerAuthority &&
      lifecycle !== undefined && lifecycle.id === receipt.lifecycleId &&
      KRAvatarCacheManager.operationKey(lifecycle.descriptor) === operation &&
      receipt.globalEpoch === KRAvatarCacheManager.globalEpoch &&
      receipt.ownerEpoch === (KRAvatarCacheManager.ownerEpoch.get(descriptor.nativePartitionKey) ?? 0) &&
      receipt.subjectEpoch === (KRAvatarCacheManager.subjectEpoch.get(
        KRAvatarCacheManager.subjectIdentity(descriptor.nativePartitionKey, descriptor.nativeSubjectKey)
      ) ?? 0) &&
      receipt.identityEpoch === (KRAvatarCacheManager.identityEpoch.get(receipt.identity) ?? 0);
  }

  private static reserveFinalize(
    context: common.UIAbilityContext,
    descriptor: KRAvatarDescriptor,
    receipt: KRAvatarFinalizeReceipt,
    finalizeAttempt: string
  ): KRAvatarFinalizeToken | null {
    const identity = KRAvatarCacheManager.identityKey(descriptor);
    const existing = KRAvatarCacheManager.finalizeByIdentity.get(identity);
    if (existing !== undefined) return null;
    const attemptId = KRAvatarCacheManager.nextFinalizeAttemptId++;
    const finalizeReceipt = [
      `${receipt.lifecycleId}`,
      receipt.operationReceipt,
      `${receipt.globalEpoch}`,
      `${receipt.ownerEpoch}`,
      `${receipt.subjectEpoch}`,
      `${receipt.identityEpoch}`,
      `${attemptId}`
    ].join(':');
    const token: KRAvatarFinalizeToken = {
      identity: identity,
      lifecycleId: receipt.lifecycleId,
      attemptId: attemptId,
      operationReceipt: receipt.operationReceipt,
      finalizeAttempt: finalizeAttempt,
      finalizeReceipt: finalizeReceipt,
      descriptor: descriptor,
      globalEpoch: KRAvatarCacheManager.globalEpoch,
      ownerEpoch: KRAvatarCacheManager.ownerEpoch.get(descriptor.nativePartitionKey) ?? 0,
      subjectEpoch: KRAvatarCacheManager.subjectEpoch.get(
        KRAvatarCacheManager.subjectIdentity(descriptor.nativePartitionKey, descriptor.nativeSubjectKey)
      ) ?? 0,
      identityEpoch: KRAvatarCacheManager.identityEpoch.get(identity) ?? 0,
      stagePath: KRAvatarCacheManager.stagePath(context, descriptor),
      finalPath: KRAvatarCacheManager.finalPath(context, descriptor),
      backupPath: KRAvatarCacheManager.finalizeBackupPath(context, descriptor),
      priorAcceptedGeneration: KRAvatarCacheManager.acceptedGenerationByIdentity.get(identity),
      priorAcceptedCallerAuthority: KRAvatarCacheManager.acceptedCallerAuthorityByIdentity.get(identity),
      transactionMarkerCreated: false,
      priorBackedUp: false,
      candidateInstalled: false,
      preparedForSharedAck: false,
      sharedAcked: false,
      rollbackDeadlineMillis: 0
    };
    KRAvatarCacheManager.finalizeByIdentity.set(identity, token);
    KRAvatarCacheManager.registerOperation(KRAvatarCacheManager.operationKey(descriptor), descriptor);
    return token;
  }

  private static finalizePayload(
    context: common.UIAbilityContext,
    token: KRAvatarFinalizeToken
  ): KRAvatarCacheBridgePayload {
    const payload = KRAvatarCacheManager.payload('ok', context);
    payload.finalizeReceipt = token.finalizeReceipt;
    return payload;
  }

  private static commandMatchesFinalizeToken(
    command: Record<string, Object>,
    token: KRAvatarFinalizeToken
  ): boolean {
    const exactReceipt = KRAvatarCacheManager.stringValue(command['finalizeReceipt']);
    const pendingAttempt = KRAvatarCacheManager.stringValue(command['finalizeAttempt']);
    const operationReceipt = KRAvatarCacheManager.stringValue(command['operationReceipt']);
    const bindingMatches = exactReceipt === token.finalizeReceipt ||
      !token.preparedForSharedAck && pendingAttempt === token.finalizeAttempt &&
      operationReceipt === token.operationReceipt;
    return bindingMatches && token.lifecycleId > 0 && token.attemptId > 0 &&
      token.finalizeAttempt.length > 0 && KRAvatarCacheManager.isFinalizeTokenLive(token);
  }

  private static isFinalizeTokenLive(token: KRAvatarFinalizeToken): boolean {
    const descriptor = token.descriptor;
    return KRAvatarCacheManager.finalizeByIdentity.get(token.identity) === token &&
      token.globalEpoch === KRAvatarCacheManager.globalEpoch &&
      token.ownerEpoch === (KRAvatarCacheManager.ownerEpoch.get(descriptor.nativePartitionKey) ?? 0) &&
      token.subjectEpoch === (KRAvatarCacheManager.subjectEpoch.get(
        KRAvatarCacheManager.subjectIdentity(descriptor.nativePartitionKey, descriptor.nativeSubjectKey)
      ) ?? 0) &&
      token.identityEpoch === (KRAvatarCacheManager.identityEpoch.get(token.identity) ?? 0);
  }

  private static finalizeIdentityAvailable(identity: string): boolean {
    const existing = KRAvatarCacheManager.finalizeByIdentity.get(identity);
    if (existing === undefined) return true;
    if (!existing.preparedForSharedAck || KRAvatarCacheManager.runtime.now() < existing.rollbackDeadlineMillis) {
      return false;
    }
    existing.sharedAcked = true;
    KRAvatarCacheManager.recordFinalizeAcknowledgement(existing);
    return KRAvatarCacheManager.commitFinalizeTransaction(existing);
  }

  private static expireFinalizeTransaction(token: KRAvatarFinalizeToken): void {
    if (KRAvatarCacheManager.finalizeByIdentity.get(token.identity) !== token ||
      !token.preparedForSharedAck ||
      KRAvatarCacheManager.runtime.now() < token.rollbackDeadlineMillis) return;
    if (!token.sharedAcked) {
      token.sharedAcked = true;
      KRAvatarCacheManager.recordFinalizeAcknowledgement(token);
    }
    KRAvatarCacheManager.commitFinalizeTransaction(token);
  }

  private static commitFinalizeTransaction(token: KRAvatarFinalizeToken): boolean {
    if (!token.preparedForSharedAck) return false;
    if (!KRAvatarCacheManager.runtime.hasDecoded(
      token.descriptor.nativeBytesKey,
      token.descriptor.commitGeneration,
      token.descriptor.callerAuthority
    )) return false;
    if (token.priorBackedUp && !KRAvatarCacheManager.removeIfPresent(token.backupPath)) return false;
    token.priorBackedUp = false;
    if (token.priorAcceptedGeneration !== undefined &&
      token.priorAcceptedGeneration !== token.descriptor.commitGeneration &&
      !KRAvatarCacheManager.runtime.retireDecoded(
        token.descriptor.nativeBytesKey,
        token.priorAcceptedGeneration,
        token.priorAcceptedCallerAuthority
      )) return false;
    if (!KRAvatarCacheManager.removeQuarantineMarker(
      KRAvatarCacheManager.quarantinePathForFinal(token.finalPath),
      false
    )) return false;
    token.candidateInstalled = false;
    KRAvatarCacheManager.acceptedCallerAuthorityByIdentity.set(
      token.identity, token.descriptor.callerAuthority
    );
    if (KRAvatarCacheManager.finalizeByIdentity.get(token.identity) === token) {
      KRAvatarCacheManager.finalizeByIdentity.delete(token.identity);
    }
    const operation = KRAvatarCacheManager.operationKey(token.descriptor);
    const receipt = KRAvatarCacheManager.finalizeReceiptByOperation.get(operation);
    const retainNotModifiedReceipt = receipt !== undefined && receipt.identity === token.identity &&
      receipt.generation === token.descriptor.commitGeneration && receipt.status === 'not_modified';
    if (!retainNotModifiedReceipt) {
      KRAvatarCacheManager.finalizeReceiptByOperation.delete(operation);
      KRAvatarCacheManager.unregisterOperation(operation);
    }
    return true;
  }

  private static recordFinalizeAcknowledgement(token: KRAvatarFinalizeToken): void {
    const existing = KRAvatarCacheManager.ackedFinalizeGenerationByIdentity.get(token.identity);
    if (existing === undefined ||
      KRAvatarCacheManager.compareGeneration(token.descriptor.commitGeneration, existing) > 0) {
      KRAvatarCacheManager.ackedFinalizeGenerationByIdentity.set(
        token.identity,
        token.descriptor.commitGeneration
      );
    }
  }

  private static discardFinalizeReservation(token: KRAvatarFinalizeToken): void {
    if (KRAvatarCacheManager.finalizeByIdentity.get(token.identity) === token) {
      KRAvatarCacheManager.finalizeByIdentity.delete(token.identity);
    }
    KRAvatarCacheManager.unregisterOperation(KRAvatarCacheManager.operationKey(token.descriptor));
  }

  private static restoreFinalizeTransaction(token: KRAvatarFinalizeToken): boolean {
    let success = true;
    const quarantinePath = KRAvatarCacheManager.quarantinePathForFinal(token.finalPath);
    if ((token.candidateInstalled || token.priorBackedUp) &&
      !KRAvatarCacheManager.ensureQuarantineMarker(quarantinePath)) return false;
    if (token.candidateInstalled) {
      if (!KRAvatarCacheManager.removeIfPresent(token.finalPath)) return false;
      token.candidateInstalled = false;
    }
    if (token.priorBackedUp) {
      try {
        if (!KRAvatarCacheManager.runtime.exists(token.backupPath)) {
          success = false;
        } else {
          KRAvatarCacheManager.runtime.rename(token.backupPath, token.finalPath);
          token.priorBackedUp = false;
        }
      } catch (error) {
        KRRenderLog.e(
          'KRAvatarCacheManager',
          `promotion restore failed: ${KRAvatarCacheManager.errorMessage(error)}`
        );
        success = false;
      }
    }
    if (token.priorAcceptedGeneration !== token.descriptor.commitGeneration &&
      !KRAvatarCacheManager.runtime.retireDecoded(
        token.descriptor.nativeBytesKey,
        token.descriptor.commitGeneration,
        token.descriptor.callerAuthority
      )) success = false;
    if (token.priorAcceptedGeneration !== undefined) {
      if (!KRAvatarCacheManager.runtime.hasDecoded(
        token.descriptor.nativeBytesKey,
        token.priorAcceptedGeneration,
        token.priorAcceptedCallerAuthority ?? ''
      )) success = false;
    }
    const accepted = KRAvatarCacheManager.acceptedGenerationByIdentity.get(token.identity);
    if (token.priorAcceptedGeneration !== undefined) {
      KRAvatarCacheManager.acceptedGenerationByIdentity.set(token.identity, token.priorAcceptedGeneration);
      if (token.priorAcceptedCallerAuthority !== undefined) {
        KRAvatarCacheManager.acceptedCallerAuthorityByIdentity.set(
          token.identity, token.priorAcceptedCallerAuthority
        );
      }
    } else if (accepted === token.descriptor.commitGeneration) {
      KRAvatarCacheManager.acceptedGenerationByIdentity.delete(token.identity);
      KRAvatarCacheManager.acceptedCallerAuthorityByIdentity.delete(token.identity);
    }
    if (success && token.transactionMarkerCreated &&
      !KRAvatarCacheManager.removeQuarantineMarker(quarantinePath, false)) success = false;
    if (!success) return false;
    if (KRAvatarCacheManager.finalizeByIdentity.get(token.identity) === token) {
      KRAvatarCacheManager.finalizeByIdentity.delete(token.identity);
    }
    const operation = KRAvatarCacheManager.operationKey(token.descriptor);
    const receipt = KRAvatarCacheManager.finalizeReceiptByOperation.get(operation);
    if (receipt !== undefined && receipt.identity === token.identity &&
      receipt.generation === token.descriptor.commitGeneration && receipt.status === 'not_modified') {
      receipt.finalized = false;
    } else {
      KRAvatarCacheManager.unregisterOperation(operation);
    }
    return true;
  }

  private static abortFinalizeTransaction(token: KRAvatarFinalizeToken): boolean {
    const quarantinePath = KRAvatarCacheManager.quarantinePathForFinal(token.finalPath);
    if (!KRAvatarCacheManager.ensureQuarantineMarker(quarantinePath)) return false;
    // Terminal retirement already scopes the whole identity. Native bytes are keyed more
    // broadly than disk identity, so retire every authority before removing the journal that
    // blocks managed deletion. False is atomic/no-effect and leaves the token retryable.
    if (!KRAvatarCacheManager.runtime.retireDecoded(token.descriptor.nativeBytesKey)) return false;
    if (token.candidateInstalled) {
      if (!KRAvatarCacheManager.removeIfPresent(token.finalPath)) return false;
      token.candidateInstalled = false;
    }
    if (token.priorBackedUp) {
      if (!KRAvatarCacheManager.removeIfPresent(token.backupPath)) return false;
      token.priorBackedUp = false;
    }
    if (!KRAvatarCacheManager.removeIfPresent(token.stagePath)) return false;
    if (!KRAvatarCacheManager.removeQuarantineMarker(quarantinePath, true)) return false;
    if (KRAvatarCacheManager.finalizeByIdentity.get(token.identity) === token) {
      KRAvatarCacheManager.finalizeByIdentity.delete(token.identity);
    }
    KRAvatarCacheManager.finalizeReceiptByOperation.delete(
      KRAvatarCacheManager.operationKey(token.descriptor)
    );
    KRAvatarCacheManager.unregisterOperation(KRAvatarCacheManager.operationKey(token.descriptor));
    return true;
  }

  private static retireFinalizations(matches: (descriptor: KRAvatarDescriptor) => boolean): boolean {
    let success = true;
    const tokens: Array<KRAvatarFinalizeToken> = [];
    KRAvatarCacheManager.finalizeByIdentity.forEach((token: KRAvatarFinalizeToken): void => {
      if (matches(token.descriptor)) tokens.push(token);
    });
    tokens.forEach((token: KRAvatarFinalizeToken): void => {
      if (token.sharedAcked) {
        const hasDecodedCandidate = KRAvatarCacheManager.runtime.hasDecoded(
          token.descriptor.nativeBytesKey,
          token.descriptor.commitGeneration,
          token.descriptor.callerAuthority
        );
        if (hasDecodedCandidate) {
          if (!KRAvatarCacheManager.commitFinalizeTransaction(token)) success = false;
        } else if (!KRAvatarCacheManager.abortFinalizeTransaction(token)) {
          success = false;
        }
      } else {
        if (!KRAvatarCacheManager.restoreFinalizeTransaction(token)) success = false;
      }
    });
    return success;
  }

  private static isOpaqueKey(value: string): boolean {
    return /^[0-9a-f]{64}$/.test(value);
  }

  private static nextOpaqueReceipt(value: number): string {
    const suffix = value.toString(16);
    return `${'0'.repeat(Math.max(0, 64 - suffix.length))}${suffix}`;
  }

  private static isAllowedReadMode(value: string): boolean {
    return value === 'render_fresh' || value === 'render_stale_revalidate' ||
      value === 'render_stale_suppressed' || value === 'fetch' || value === 'suppressed';
  }

  private static isPositiveGeneration(value: string): boolean {
    return /^[1-9][0-9]*$/.test(value);
  }

  private static removeIfPresent(path: string): boolean {
    if (!KRAvatarCacheManager.runtime.exists(path)) return true;
    try {
      KRAvatarCacheManager.runtime.unlink(path);
      return !KRAvatarCacheManager.runtime.exists(path);
    } catch (error) {
      KRRenderLog.e('KRAvatarCacheManager', `delete failed: ${KRAvatarCacheManager.errorMessage(error)}`);
      return false;
    }
  }

  private static quarantinePathForFinal(finalPath: string): string {
    return `${finalPath}.quarantine`;
  }

  private static ensureQuarantineMarker(path: string): boolean {
    try {
      if (KRAvatarCacheManager.runtime.exists(path)) return true;
      KRAvatarCacheManager.runtime.write(path, new ArrayBuffer(1));
      return KRAvatarCacheManager.runtime.exists(path);
    } catch (error) {
      KRRenderLog.e('KRAvatarCacheManager', `quarantine failed: ${KRAvatarCacheManager.errorMessage(error)}`);
      return false;
    }
  }

  private static acquireQuarantineLease(scope: string, path: string): void {
    const frames = KRAvatarCacheManager.quarantineLeaseFramesByScope.get(scope) ?? new Array<Set<string>>();
    if (frames.length === 0) frames.push(new Set<string>());
    const paths = frames[frames.length - 1];
    if (paths.has(path)) return;
    paths.add(path);
    KRAvatarCacheManager.quarantineLeaseFramesByScope.set(scope, frames);
    KRAvatarCacheManager.quarantineLeaseCountByPath.set(
      path,
      (KRAvatarCacheManager.quarantineLeaseCountByPath.get(path) ?? 0) + 1
    );
  }

  private static releaseQuarantineLeases(scope: string): void {
    const frames = KRAvatarCacheManager.quarantineLeaseFramesByScope.get(scope);
    const paths = frames?.pop();
    if ((frames?.length ?? 0) === 0) KRAvatarCacheManager.quarantineLeaseFramesByScope.delete(scope);
    paths?.forEach((path: string): void => {
      const count = KRAvatarCacheManager.quarantineLeaseCountByPath.get(path) ?? 0;
      if (count <= 1) KRAvatarCacheManager.quarantineLeaseCountByPath.delete(path);
      else KRAvatarCacheManager.quarantineLeaseCountByPath.set(path, count - 1);
    });
  }

  private static removeQuarantineMarker(path: string, verifiedDelete: boolean): boolean {
    if (!verifiedDelete && (KRAvatarCacheManager.quarantineLeaseCountByPath.get(path) ?? 0) > 0) {
      return true;
    }
    return KRAvatarCacheManager.removeIfPresent(path);
  }

  private static isIdentityQuarantined(
    context: common.UIAbilityContext,
    descriptor: KRAvatarDescriptor
  ): boolean {
    const finalPath = KRAvatarCacheManager.finalPath(context, descriptor);
    const quarantinePath = KRAvatarCacheManager.quarantinePathForFinal(finalPath);
    try {
      if (!KRAvatarCacheManager.runtime.exists(quarantinePath)) return false;
      if (KRAvatarCacheManager.runtime.exists(finalPath)) return true;
      const separator = finalPath.lastIndexOf('/');
      const stageIdentity: KRAvatarStageIdentity = {
        directory: finalPath.substring(0, separator),
        partitionKey: descriptor.nativePartitionKey,
        subjectKey: descriptor.nativeSubjectKey,
        bytesKey: descriptor.nativeBytesKey
      };
      if (KRAvatarCacheManager.identityHasStage(stageIdentity)) return true;
      return !KRAvatarCacheManager.removeIfPresent(quarantinePath);
    } catch (error) {
      KRRenderLog.e('KRAvatarCacheManager', `quarantine stat failed: ${KRAvatarCacheManager.errorMessage(error)}`);
      return true;
    }
  }

  private static stringValue(value: Object | undefined): string {
    return typeof value === 'string' ? value : `${value ?? ''}`;
  }

  private static optionalString(value: Object | undefined): string | undefined {
    const normalized = KRAvatarCacheManager.stringValue(value).trim();
    return normalized.length > 0 ? normalized : undefined;
  }

  private static header(headers: Object, requested: string): string | undefined {
    const records = headers as Record<string, Object>;
    const key = Object.keys(records).find((candidate: string): boolean => candidate.toLowerCase() === requested);
    return key === undefined ? undefined : KRAvatarCacheManager.stringValue(records[key]).trim();
  }

  private static errorMessage(error: Object): string {
    const candidate = error as BusinessError;
    return `${candidate.code ?? -1}:${candidate.message ?? 'unknown'}`;
  }
}
