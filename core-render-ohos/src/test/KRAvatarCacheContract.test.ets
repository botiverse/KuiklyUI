import { common } from '@kit.AbilityKit';
import { describe, it, expect } from '@ohos/hypium';
import {
  KRAvatarCacheBridgePayload,
  KRAvatarCacheManager
} from '../main/ets/utils/KRAvatarCacheManager';
import {
  KRAvatarCacheRuntime,
  KRAvatarFileStat,
  KRAvatarHttpResult
} from '../main/ets/utils/KRAvatarCacheRuntime';
import { KRAny } from '../main/ets/utils/KRDataTypes';

const PARTITION = 'a'.repeat(64);
const OTHER_PARTITION = 'e'.repeat(64);
const SUBJECT = 'b'.repeat(64);
const BYTES = 'c'.repeat(64);
const OTHER_BYTES = 'd'.repeat(64);
const DIRECTORY = '/cache/kuikly-avatar-managed-v1';
const CONTEXT = { cacheDir: '/cache' } as common.UIAbilityContext;

class TestHeaders {
  etag: string = '';
}

class TestBridgePayload implements KRAvatarCacheBridgePayload {
  status: string = '';
  managedSet: string = '';
  managedIndex: string = '';
  budgetScope: string = '';
  indexedRetirement: boolean = false;
  managedBytes: string = '';
}

class TestAvatarCommand {
  protocol: string = 'slock_avatar_cache_v1';
  managedSet: string = 'typed_avatar_only';
  managedIndex: string = 'opaque_partition_subject_bytes_v1';
  budgetScope: string = 'avatar_only';
  indexedRetirementRequired: boolean = true;
  maxEntryBytes: string = '5242880';
  maxManagedBytes: string = '67108864';
  remoteUrl: string = 'https://example.invalid/avatar.png';
  nativePartitionKey: string = PARTITION;
  nativeSubjectKey: string = SUBJECT;
  nativeBytesKey: string = BYTES;
  readMode: string = 'fetch';
  commitGeneration: string = '1';
  callerAuthority: string = '';
  eTag: string = '';
  action: string = '';
  outcome: string = '';
  operationReceipt: string = '';
  finalizeAttempt: string = '';
  finalizeReceipt: string = '';
}

class TestHttpResult implements KRAvatarHttpResult {
  responseCode: number;
  body: ArrayBuffer | null;
  headers: Object;
  error: string;

  constructor(code: number, body: ArrayBuffer | null, eTag: string = '', error: string = '') {
    this.responseCode = code;
    this.body = body;
    const headers = new TestHeaders();
    headers.etag = eTag;
    this.headers = headers;
    this.error = error;
  }
}

function descriptor(
  generation: string,
  mode: string = 'fetch',
  bytesKey: string = BYTES,
  eTag: string = ''
): TestAvatarCommand {
  const value = new TestAvatarCommand();
  value.commitGeneration = generation;
  value.readMode = mode;
  value.nativeBytesKey = bytesKey;
  value.eTag = eTag;
  value.callerAuthority = callerAuthorityFor(value);
  return value;
}

function descriptorJson(generation: string = '1', mode: string = 'fetch'): string {
  return JSON.stringify(descriptor(generation, mode));
}

function model(generation: string, mode: string = 'fetch', bytesKey: string = BYTES, eTag: string = ''): string {
  return JSON.stringify(descriptor(generation, mode, bytesKey, eTag));
}

function finalPath(bytesKey: string = BYTES): string {
  return `${DIRECTORY}/${PARTITION}.${SUBJECT}.${bytesKey}.avatar`;
}

function quarantinePath(bytesKey: string = BYTES): string {
  return `${finalPath(bytesKey)}.quarantine`;
}

function stagePath(generation: string, bytesKey: string = BYTES, callerAuthority: string = ''): string {
  const command = new TestAvatarCommand();
  command.commitGeneration = generation;
  command.nativeBytesKey = bytesKey;
  const authority = callerAuthority.length > 0 ? callerAuthority : callerAuthorityFor(command);
  return `${DIRECTORY}/${PARTITION}.${SUBJECT}.${bytesKey}.g${generation}.a${authority}.stage`;
}

function backupPath(generation: string, bytesKey: string = BYTES, callerAuthority: string = ''): string {
  const command = new TestAvatarCommand();
  command.commitGeneration = generation;
  command.nativeBytesKey = bytesKey;
  const authority = callerAuthority.length > 0 ? callerAuthority : callerAuthorityFor(command);
  return `${DIRECTORY}/${PARTITION}.${SUBJECT}.${bytesKey}.g${generation}.a${authority}.prior.stage`;
}

interface FakeScheduledCallback {
  deadline: number;
  callback: () => void;
}

class FakeAvatarRuntime implements KRAvatarCacheRuntime {
  files: Map<string, ArrayBuffer> = new Map();
  sizes: Map<string, number> = new Map();
  decodable: Set<string> = new Set();
  requests: Array<Record<string, string>> = [];
  responses: Array<KRAvatarHttpResult> = [];
  decodeRequests: Array<string> = [];
  retired: Array<string> = [];
  promoted: Array<string> = [];
  events: Array<string> = [];
  activeDecodedGeneration: Map<string, string> = new Map();
  decodedGenerations: Map<string, Set<string>> = new Map();
  failUnlink: Set<string> = new Set();
  failWrite: Set<string> = new Set();
  failExists: Set<string> = new Set();
  failExistsAfterUnlink: Set<string> = new Set();
  private failNextExists: Set<string> = new Set();
  failStat: Set<string> = new Set();
  failStatCount: Map<string, number> = new Map();
  failRename: boolean = false;
  noOpRename: boolean = false;
  malformedRename: boolean = false;
  noOpRenameAt: Set<number> = new Set();
  malformedRenameAt: Set<number> = new Set();
  failDecodedRetire: boolean = false;
  failDecodedPromote: Set<string> = new Set();
  renameCount: number = 0;
  private clock: number = 100;
  private deferHttp: boolean = false;
  private pendingResolve: ((value: KRAvatarHttpResult) => void) | null = null;
  private deferredDecodeCounts: Map<string, number> = new Map();
  private pendingDecodeResolves: Array<(value: boolean) => void> = [];
  private scheduled: Array<FakeScheduledCallback> = [];

  now(): number {
    this.clock += 1;
    return this.clock;
  }

  advanceClock(millis: number): void {
    this.clock += millis;
    const due = this.scheduled.filter((entry: FakeScheduledCallback): boolean =>
      entry.deadline <= this.clock
    );
    this.scheduled = this.scheduled.filter((entry: FakeScheduledCallback): boolean =>
      entry.deadline > this.clock
    );
    due.forEach((entry: FakeScheduledCallback): void => { entry.callback(); });
  }

  schedule(delayMillis: number, callback: () => void): void {
    this.scheduled.push({ deadline: this.clock + delayMillis, callback: callback });
  }

  exists(path: string): boolean {
    if (this.failNextExists.has(path)) {
      this.failNextExists.delete(path);
      throw new Error('post-unlink exists injected');
    }
    if (this.failExists.has(path)) throw new Error('exists injected');
    return path === DIRECTORY || this.files.has(path);
  }

  ensureDirectory(_path: string): void {
  }

  list(directory: string): Array<string> {
    const prefix = `${directory}/`;
    const result: Array<string> = [];
    this.files.forEach((_body: ArrayBuffer, path: string): void => {
      if (path.startsWith(prefix)) result.push(path.substring(prefix.length));
    });
    return result;
  }

  stat(path: string): KRAvatarFileStat {
    const remaining = this.failStatCount.get(path) ?? 0;
    if (remaining > 0) {
      if (remaining === 1) this.failStatCount.delete(path);
      else this.failStatCount.set(path, remaining - 1);
      throw new Error('stat count injected');
    }
    if (this.failStat.has(path)) throw new Error('stat injected');
    if (!this.files.has(path)) throw new Error('missing');
    return { size: this.sizes.get(path) ?? this.files.get(path)?.byteLength ?? 0, mtime: 1 };
  }

  write(path: string, body: ArrayBuffer): void {
    this.events.push(`write:${path}`);
    if (this.failWrite.has(path)) throw new Error('write injected');
    this.files.set(path, body);
    this.sizes.set(path, body.byteLength);
    this.decodable.add(path);
  }

  unlink(path: string): void {
    this.events.push(`unlink:${path}`);
    if (this.failUnlink.has(path)) throw new Error('unlink injected');
    this.files.delete(path);
    this.sizes.delete(path);
    this.decodable.delete(path);
    if (this.failExistsAfterUnlink.has(path)) {
      this.failExistsAfterUnlink.delete(path);
      this.failNextExists.add(path);
    }
  }

  rename(from: string, to: string): void {
    this.renameCount += 1;
    if (this.failRename) throw new Error('rename injected');
    if (this.noOpRename || this.noOpRenameAt.has(this.renameCount)) return;
    const body = this.files.get(from);
    if (body === undefined) throw new Error('stage missing');
    const size = this.sizes.get(from) ?? body.byteLength;
    const canDecode = this.decodable.has(from);
    this.files.set(to, body);
    const malformed = this.malformedRename || this.malformedRenameAt.has(this.renameCount);
    this.sizes.set(to, malformed ? size + 1 : size);
    if (canDecode && !malformed) this.decodable.add(to); else this.decodable.delete(to);
    this.files.delete(from);
    this.sizes.delete(from);
    this.decodable.delete(from);
  }

  isDecodable(path: string): Promise<boolean> {
    this.decodeRequests.push(path);
    const deferred = this.deferredDecodeCounts.get(path) ?? 0;
    if (deferred > 0) {
      if (deferred === 1) this.deferredDecodeCounts.delete(path);
      else this.deferredDecodeCounts.set(path, deferred - 1);
      return new Promise<boolean>((resolve: (value: boolean) => void): void => {
        this.pendingDecodeResolves.push(resolve);
      });
    }
    const result = this.files.has(path) && this.decodable.has(path);
    if (result) this.recordDecodedStage(path);
    return Promise.resolve(result);
  }

  httpGet(_url: string, headers: Record<string, string>): Promise<KRAvatarHttpResult> {
    this.requests.push(headers);
    if (this.deferHttp) {
      return new Promise<KRAvatarHttpResult>((resolve: (value: KRAvatarHttpResult) => void): void => {
        this.pendingResolve = resolve;
      });
    }
    const response = this.responses.shift();
    return Promise.resolve(response ?? new TestHttpResult(500, null, '', 'missing fixture'));
  }

  retireDecoded(nativeBytesKey: string, commitGeneration?: string, callerAuthority?: string): boolean {
    this.retired.push(`${nativeBytesKey}:${commitGeneration ?? '*'}`);
    if (this.failDecodedRetire) return false;
    const generations = this.decodedGenerations.get(nativeBytesKey) ?? new Set<string>();
    if (commitGeneration === undefined) {
      generations.clear();
    } else if (callerAuthority === undefined || callerAuthority.length === 0) {
      Array.from(generations).forEach((candidate: string): void => {
        if (candidate.startsWith(`${commitGeneration}:`)) generations.delete(candidate);
      });
    } else {
      generations.delete(`${commitGeneration}:${callerAuthority}`);
    }
    this.decodedGenerations.set(nativeBytesKey, generations);
    const active = this.activeDecodedGeneration.get(nativeBytesKey);
    if (commitGeneration === undefined || active === commitGeneration &&
      !Array.from(generations).some((candidate: string): boolean => candidate.startsWith(`${commitGeneration}:`))) {
      let replacement: string | undefined = undefined;
      generations.forEach((candidate: string): void => {
        const generation = candidate.substring(0, candidate.indexOf(':'));
        if (replacement === undefined || Number(generation) > Number(replacement)) replacement = generation;
      });
      if (replacement === undefined) this.activeDecodedGeneration.delete(nativeBytesKey);
      else this.activeDecodedGeneration.set(nativeBytesKey, replacement);
    }
    return true;
  }

  promoteDecoded(nativeBytesKey: string, commitGeneration: string, callerAuthority: string): boolean {
    this.promoted.push(`${nativeBytesKey}:${commitGeneration}`);
    if (this.failDecodedPromote.has(commitGeneration)) return false;
    const generations = this.decodedGenerations.get(nativeBytesKey) ?? new Set<string>();
    generations.add(`${commitGeneration}:${callerAuthority}`);
    this.decodedGenerations.set(nativeBytesKey, generations);
    this.decodedGenerations.set(nativeBytesKey, generations);
    this.activeDecodedGeneration.set(nativeBytesKey, commitGeneration);
    return true;
  }

  retainDecoded(
    nativeBytesKey: string,
    commitGeneration: string,
    callerAuthority: string,
    priorGeneration?: string,
    priorCallerAuthority?: string
  ): boolean {
    this.promoted.push(`${nativeBytesKey}:${commitGeneration}`);
    if (this.failDecodedPromote.has(commitGeneration)) return false;
    const existing = this.decodedGenerations.get(nativeBytesKey) ?? new Set<string>();
    if (!existing.has(`${commitGeneration}:${callerAuthority}`) ||
      priorGeneration !== undefined &&
      !existing.has(`${priorGeneration}:${priorCallerAuthority ?? ''}`)) return false;
    this.activeDecodedGeneration.set(nativeBytesKey, commitGeneration);
    return true;
  }

  hasDecoded(nativeBytesKey: string, commitGeneration: string, callerAuthority: string = ''): boolean {
    const command = new TestAvatarCommand();
    command.nativeBytesKey = nativeBytesKey;
    command.commitGeneration = commitGeneration;
    const authority = callerAuthority.length > 0 ? callerAuthority : callerAuthorityFor(command);
    return (this.decodedGenerations.get(nativeBytesKey) ?? new Set<string>())
      .has(`${commitGeneration}:${authority}`);
  }

  seedDecoded(nativeBytesKey: string, commitGeneration: string, callerAuthority: string = ''): void {
    const generations = this.decodedGenerations.get(nativeBytesKey) ?? new Set<string>();
    const command = new TestAvatarCommand();
    command.nativeBytesKey = nativeBytesKey;
    command.commitGeneration = commitGeneration;
    const authority = callerAuthority.length > 0 ? callerAuthority : callerAuthorityFor(command);
    generations.add(`${commitGeneration}:${authority}`);
    this.decodedGenerations.set(nativeBytesKey, generations);
    this.activeDecodedGeneration.set(nativeBytesKey, commitGeneration);
  }

  destroyDecodedStore(): void {
    this.decodedGenerations.clear();
    this.activeDecodedGeneration.clear();
  }

  seed(path: string, size: number = 3, canDecode: boolean = true): void {
    this.files.set(path, new ArrayBuffer(Math.min(size, 8)));
    this.sizes.set(path, size);
    if (canDecode) {
      this.decodable.add(path);
      this.recordDecodedStage(path);
    }
  }

  private recordDecodedStage(path: string): void {
    const match = path.match(/\.([0-9a-f]{64})\.g([1-9][0-9]*)\.a([0-9a-f]{64})\.stage$/);
    if (match === null) return;
    const bytesKey = match[1];
    const generation = match[2];
    const callerAuthority = match[3];
    const generations = this.decodedGenerations.get(bytesKey) ?? new Set<string>();
    generations.add(`${generation}:${callerAuthority}`);
    this.decodedGenerations.set(bytesKey, generations);
    this.activeDecodedGeneration.set(bytesKey, generation);
  }

  queue(code: number, size: number = 3): void {
    this.responses.push(new TestHttpResult(
      code,
      code >= 200 && code < 300 && code !== 204 ? new ArrayBuffer(size) : null,
      'next'
    ));
  }

  queueFront(code: number, size: number = 3): void {
    this.responses.unshift(new TestHttpResult(
      code,
      code >= 200 && code < 300 && code !== 204 ? new ArrayBuffer(size) : null,
      'next'
    ));
  }

  deferNextHttp(): void {
    this.deferHttp = true;
  }

  resolveDeferred(code: number, size: number = 3): void {
    const resolve = this.pendingResolve;
    this.pendingResolve = null;
    this.deferHttp = false;
    resolve?.(new TestHttpResult(
      code,
      code >= 200 && code < 300 ? new ArrayBuffer(size) : null
    ));
  }

  deferDecode(path: string): void {
    this.deferDecodes(path, 1);
  }

  deferDecodes(path: string, count: number): void {
    this.deferredDecodeCounts.set(path, count);
  }

  clearDeferredDecodes(path: string): void {
    this.deferredDecodeCounts.delete(path);
  }

  resolveDeferredDecode(value: boolean): void {
    this.resolveDeferredDecodeAt(0, value);
  }

  resolveDeferredDecodeAt(index: number, value: boolean): void {
    const resolve = this.pendingDecodeResolves[index];
    this.pendingDecodeResolves.splice(index, 1);
    resolve?.(value);
  }

  pendingDecodeCount(): number {
    return this.pendingDecodeResolves.length;
  }
}

let installedRuntime: FakeAvatarRuntime | null = null;
let nextFinalizeAttempt: number = 1;
let nextCallerAuthority: number = 1;
const operationReceiptByOperation: Map<string, string> = new Map();
const callerAuthorityByOperation: Map<string, string> = new Map();

function callerAuthorityKey(command: TestAvatarCommand): string {
  return `${command.nativePartitionKey}:${command.nativeSubjectKey}:${command.nativeBytesKey}:g${command.commitGeneration}`;
}

function newCallerAuthority(): string {
  const suffix = `${nextCallerAuthority++}`;
  return `${'0'.repeat(64 - suffix.length)}${suffix}`;
}

function callerAuthorityFor(command: TestAvatarCommand): string {
  const key = callerAuthorityKey(command);
  const existing = callerAuthorityByOperation.get(key);
  if (existing !== undefined) return existing;
  const authority = newCallerAuthority();
  callerAuthorityByOperation.set(key, authority);
  return authority;
}

function rotateCallerAuthority(command: TestAvatarCommand): string {
  const authority = newCallerAuthority();
  callerAuthorityByOperation.set(callerAuthorityKey(command), authority);
  command.callerAuthority = authority;
  return authority;
}

function operationKey(command: TestAvatarCommand): string {
  return `${callerAuthorityKey(command)}:${command.callerAuthority}`;
}

function assignFinalizeAttempt(command: TestAvatarCommand): void {
  if (command.operationReceipt.length === 0) {
    command.operationReceipt = operationReceiptByOperation.get(operationKey(command)) ?? '';
  }
  if (command.finalizeAttempt.length > 0) return;
  const suffix = `${nextFinalizeAttempt++}`;
  command.finalizeAttempt = `${'0'.repeat(64 - suffix.length)}${suffix}`;
  command.finalizeReceipt = '';
}

function install(): FakeAvatarRuntime {
  const runtime = new FakeAvatarRuntime();
  operationReceiptByOperation.clear();
  callerAuthorityByOperation.clear();
  KRAvatarCacheManager.installRuntimeForTesting(runtime);
  installedRuntime = runtime;
  return runtime;
}

function fetchAvatar(value: string): Promise<KRAny> {
  return new Promise<KRAny>((resolve: (value: KRAny) => void): void => {
    expect(KRAvatarCacheManager.fetchDescriptorForTesting(CONTEXT, value, resolve)).assertTrue();
  });
}

function invokeBridge(
  action: string,
  command: TestAvatarCommand,
  throwAfterDelivery: boolean = false
): Promise<KRAvatarCacheBridgePayload> {
  command.action = action;
  const record = JSON.parse(JSON.stringify(command)) as Record<string, Object>;
  return new Promise<KRAvatarCacheBridgePayload>((resolve: (value: KRAvatarCacheBridgePayload) => void): void => {
    void KRAvatarCacheManager.handleBridge(
      CONTEXT,
      record,
      (result: KRAny): void => {
        resolve(result as KRAvatarCacheBridgePayload);
        if (throwAfterDelivery) throw new Error('response dropped after callback');
      }
    );
  });
}

async function authorizeSeededSuccessfulBytes(command: TestAvatarCommand): Promise<boolean> {
  const runtime = installedRuntime;
  if (runtime === null) return false;
  const path = stagePath(command.commitGeneration, command.nativeBytesKey);
  const body = runtime.files.get(path);
  if (body === undefined) return false;
  const size = runtime.sizes.get(path) ?? body.byteLength;
  const canDecode = runtime.decodable.has(path);
  runtime.queueFront(200, Math.max(1, Math.min(size, 8)));
  await fetchAvatar(model(
    command.commitGeneration,
    'fetch',
    command.nativeBytesKey,
    command.eTag
  ));
  await awaitResult(command.commitGeneration, command.nativeBytesKey);
  command.operationReceipt = operationReceiptByOperation.get(operationKey(command)) ?? '';
  runtime.files.set(path, body);
  runtime.sizes.set(path, size);
  if (canDecode) runtime.decodable.add(path); else runtime.decodable.delete(path);
  return true;
}

async function bridgeResult(
  action: string,
  command: TestAvatarCommand,
  authorizeSeededStage: boolean
): Promise<KRAvatarCacheBridgePayload> {
  if (action === 'finalize' &&
    (command.outcome === 'successful_bytes' || command.outcome === 'not_modified')) {
    assignFinalizeAttempt(command);
  }
  let payload = await invokeBridge(action, command);
  if (authorizeSeededStage && action === 'finalize' && command.outcome === 'successful_bytes' &&
    payload.status === 'error' &&
    (payload.code === 'successful_bytes_without_terminal' || payload.code === 'finalize_binding_required') &&
    await authorizeSeededSuccessfulBytes(command)) {
    assignFinalizeAttempt(command);
    payload = await invokeBridge(action, command);
  }
  if (action === 'finalize' && payload.status === 'ok' && payload.finalizeReceipt !== undefined) {
    command.finalizeReceipt = payload.finalizeReceipt;
  }
  return payload;
}

async function bridge(action: string, command: TestAvatarCommand): Promise<KRAvatarCacheBridgePayload> {
  const payload = await bridgeResult(action, command, true);
  if (action !== 'finalize' ||
    (command.outcome !== 'successful_bytes' && command.outcome !== 'not_modified') ||
    payload.status !== 'ok') return payload;
  await invokeBridge('ackFinalize', command);
  return payload;
}

function bridgeSync(
  action: string,
  command: TestAvatarCommand,
  callback: (value: KRAvatarCacheBridgePayload) => void
): void {
  if (action === 'finalize' &&
    (command.outcome === 'successful_bytes' || command.outcome === 'not_modified')) {
    assignFinalizeAttempt(command);
  }
  command.action = action;
  const record = JSON.parse(JSON.stringify(command)) as Record<string, Object>;
  void KRAvatarCacheManager.handleBridge(CONTEXT, record, (value: KRAny): void => {
    callback(value as KRAvatarCacheBridgePayload);
  });
}

async function bridgeWithoutAck(
  action: string,
  command: TestAvatarCommand,
  throwAfterDelivery: boolean = false,
  authorizeSeededStage: boolean = true
): Promise<KRAvatarCacheBridgePayload> {
  if (throwAfterDelivery && action === 'finalize' &&
    (command.outcome === 'successful_bytes' || command.outcome === 'not_modified')) {
    assignFinalizeAttempt(command);
  }
  if (throwAfterDelivery && authorizeSeededStage && action === 'finalize' &&
    command.outcome === 'successful_bytes') {
    await authorizeSeededSuccessfulBytes(command);
    assignFinalizeAttempt(command);
  }
  if (throwAfterDelivery) {
    const payload = await invokeBridge(action, command, true);
    if (action === 'finalize' && payload.status === 'ok' && payload.finalizeReceipt !== undefined) {
      command.finalizeReceipt = payload.finalizeReceipt;
    }
    return payload;
  }
  const payload = await bridgeResult(action, command, authorizeSeededStage);
  return payload;
}

async function awaitResult(generation: string, bytesKey: string = BYTES): Promise<KRAvatarCacheBridgePayload> {
  const command = descriptor(generation, 'fetch', bytesKey);
  const payload = await bridge('awaitResult', command);
  if (payload.operationReceipt !== undefined) {
    operationReceiptByOperation.set(operationKey(command), payload.operationReceipt);
  }
  return payload;
}

async function flushMicrotasks(count: number = 8): Promise<void> {
  for (let index = 0; index < count; index += 1) await Promise.resolve();
}

function seedBudgetFillers(runtime: FakeAvatarRuntime, count: number): void {
  for (let index = 1; index <= count; index += 1) {
    const key = `${index.toString(16)}`.repeat(64).substring(0, 64);
    runtime.seed(finalPath(key), 5 * 1024 * 1024, true);
  }
}

export default function avatarCacheContractTest() {
  describe('KRAvatarCacheContract', () => {
    it('keeps ordinary URLs outside the typed-avatar owner', 0, () => {
      install();
      expect(KRAvatarCacheManager.isAvatarModel('https://example.invalid/image.png')).assertFalse();
      let called = false;
      expect(KRAvatarCacheManager.fetchFile(CONTEXT, 'https://example.invalid/image.png', (): void => {
        called = true;
      })).assertFalse();
      expect(called).assertFalse();
    });

    it('accepts only the exact opaque descriptor contract', 0, () => {
      install();
      expect(KRAvatarCacheManager.parseDescriptorJson(descriptorJson()) === null).assertFalse();
      expect(KRAvatarCacheManager.parseDescriptorJson('{') === null).assertTrue();
      expect(KRAvatarCacheManager.parseDescriptorJson(descriptorJson().replace('"aaaaaaaa', '"Aaaaaaaa')) === null)
        .assertTrue();
    });

    it('keeps ArkTS model and bridge read modes identical to the native five-value domain', 0, async () => {
      install();
      const allowed = [
        'render_fresh',
        'render_stale_revalidate',
        'render_stale_suppressed',
        'fetch',
        'suppressed'
      ];
      allowed.forEach((mode: string): void => {
        expect(KRAvatarCacheManager.parseDescriptorJson(descriptorJson('1', mode)) === null).assertFalse();
      });
      const rejected = ['', 'unknown', 'FETCH', ' fetch', 'fetch '];
      for (const mode of rejected) {
        expect(KRAvatarCacheManager.parseDescriptorJson(descriptorJson('1', mode)) === null).assertTrue();
        expect((await bridge('awaitResult', descriptor('1', mode))).status === 'unsupported').assertTrue();
        const finalize = descriptor('1', mode);
        finalize.outcome = 'successful_bytes';
        expect((await bridge('finalize', finalize)).status === 'unsupported').assertTrue();
        expect((await bridge('rollback', descriptor('1', mode))).status === 'unsupported').assertTrue();
      }
    });

    it('settles fresh, stale-suppressed, and suppressed modes without HTTP', 0, async () => {
      const runtime = install();
      runtime.seed(finalPath());
      const fresh = await fetchAvatar(model('1', 'render_fresh'));
      expect(fresh === finalPath()).assertTrue();
      expect((await awaitResult('1')).status === 'rendered').assertTrue();
      const stale = await fetchAvatar(model('2', 'render_stale_suppressed'));
      expect(stale === finalPath()).assertTrue();
      expect((await awaitResult('2')).status === 'rendered').assertTrue();
      const suppressed = await fetchAvatar(model('3', 'suppressed'));
      expect(suppressed === null).assertTrue();
      expect((await awaitResult('3')).status === 'suppressed').assertTrue();
      expect(runtime.requests.length === 0).assertTrue();
    });

    it('settles protocol once before render when unrelated accounting stat fails', 0, async () => {
      const modes = [
        'fetch',
        'render_fresh',
        'render_stale_revalidate',
        'render_stale_suppressed',
        'suppressed'
      ];
      let generation = 200;
      for (const mode of modes) {
        const runtime = install();
        const currentGeneration = `${generation}`;
        generation += 1;
        runtime.seed(finalPath(OTHER_BYTES), 3);
        runtime.failStat.add(finalPath(OTHER_BYTES));
        if (mode === 'render_fresh' || mode === 'render_stale_revalidate' || mode === 'render_stale_suppressed') {
          runtime.seed(finalPath(), 3);
        } else if (mode === 'fetch') {
          runtime.queue(200, 4);
        }
        let renderCount = 0;
        let rendered: KRAny = finalPath();
        let protocolSettled = false;
        let renderSawProtocol = false;
        const command = descriptor(currentGeneration);
        command.action = 'awaitResult';
        const record = JSON.parse(JSON.stringify(command)) as Record<string, Object>;
        let terminal: KRAvatarCacheBridgePayload = new TestBridgePayload();
        void KRAvatarCacheManager.handleBridge(CONTEXT, record, (value: KRAny): void => {
          protocolSettled = true;
          terminal = value as KRAvatarCacheBridgePayload;
        });
        expect(KRAvatarCacheManager.fetchDescriptorForTesting(
          CONTEXT,
          model(currentGeneration, mode),
          (value: KRAny): void => {
            renderCount += 1;
            rendered = value;
            renderSawProtocol = protocolSettled;
          }
        )).assertTrue();
        await Promise.resolve();
        await Promise.resolve();
        await Promise.resolve();
        expect(renderCount === 1).assertTrue();
        expect(renderSawProtocol).assertTrue();
        expect(rendered === null).assertTrue();
        expect(terminal.status === 'error' && terminal.code === 'avatar_cache_native_failed').assertTrue();
        expect(terminal.managedBytes === '0').assertTrue();
      }
    });

    it('isolates a throwing render callback after protocol settlement', 0, async () => {
      const runtime = install();
      runtime.queue(200, 4);
      const receipt = awaitResult('204');
      let renderCount = 0;
      expect(KRAvatarCacheManager.fetchDescriptorForTesting(
        CONTEXT,
        model('204'),
        (_value: KRAny): void => {
          renderCount += 1;
          throw new Error('render callback injected');
        }
      )).assertTrue();
      const terminal = await receipt;
      await Promise.resolve();
      expect(terminal.status === 'success').assertTrue();
      expect(renderCount === 1).assertTrue();
    });

    it('isolates the first throwing waiter and settles the second waiter once', 0, async () => {
      const runtime = install();
      runtime.queue(200, 4);
      const command = descriptor('205');
      command.action = 'awaitResult';
      const record = JSON.parse(JSON.stringify(command)) as Record<string, Object>;
      let firstCount = 0;
      let secondCount = 0;
      void KRAvatarCacheManager.handleBridge(CONTEXT, record, (_value: KRAny): void => {
        firstCount += 1;
        throw new Error('first waiter injected');
      });
      let secondPayload: KRAvatarCacheBridgePayload = new TestBridgePayload();
      void KRAvatarCacheManager.handleBridge(CONTEXT, record, (value: KRAny): void => {
        secondCount += 1;
        secondPayload = value as KRAvatarCacheBridgePayload;
      });
      let renderCount = 0;
      expect(KRAvatarCacheManager.fetchDescriptorForTesting(
        CONTEXT,
        model('205'),
        (_value: KRAny): void => {
          renderCount += 1;
        }
      )).assertTrue();
      await Promise.resolve();
      await Promise.resolve();
      await Promise.resolve();
      expect(secondPayload.status === 'success').assertTrue();
      expect(firstCount === 1 && secondCount === 1 && renderCount === 1).assertTrue();
    });

    it('isolates a throwing stored-receipt callback without bridge redelivery', 0, async () => {
      const runtime = install();
      runtime.queue(200, 4);
      expect((await fetchAvatar(model('206'))) === stagePath('206')).assertTrue();
      const command = descriptor('206');
      command.action = 'awaitResult';
      const record = JSON.parse(JSON.stringify(command)) as Record<string, Object>;
      let callbackCount = 0;
      await KRAvatarCacheManager.handleBridge(CONTEXT, record, (_value: KRAny): void => {
        callbackCount += 1;
        throw new Error('stored receipt callback injected');
      });
      await Promise.resolve();
      expect(callbackCount === 1).assertTrue();
    });

    it('fans out a deferred 200 render without consuming the late protocol receipt', 0, async () => {
      const runtime = install();
      runtime.deferNextHttp();
      const primary = fetchAvatar(model('207'));
      await Promise.resolve();
      await Promise.resolve();
      expect(runtime.requests.length === 1).assertTrue();
      const joined = fetchAvatar(model('207'));
      await Promise.resolve();
      await Promise.resolve();
      expect(runtime.requests.length === 1).assertTrue();
      runtime.resolveDeferred(200, 4);
      expect((await primary) === stagePath('207')).assertTrue();
      expect((await joined) === stagePath('207')).assertTrue();

      let receiptResolved = false;
      let receiptStatus = '';
      void awaitResult('207').then((payload: KRAvatarCacheBridgePayload): void => {
        receiptResolved = true;
        receiptStatus = payload.status;
      });
      await flushMicrotasks();
      expect(receiptResolved && receiptStatus === 'success').assertTrue();
    });

    it('fans out a deferred valid-final 304 path without consuming the late protocol receipt', 0, async () => {
      const runtime = install();
      runtime.seed(finalPath(), 4, true);
      runtime.seedDecoded(BYTES, '208');
      runtime.deferNextHttp();
      const primary = fetchAvatar(model('208', 'fetch', BYTES, 'valid'));
      await Promise.resolve();
      await Promise.resolve();
      expect(runtime.requests.length === 1).assertTrue();
      const joined = fetchAvatar(model('208', 'fetch', BYTES, 'valid'));
      await Promise.resolve();
      await Promise.resolve();
      expect(runtime.requests.length === 1).assertTrue();
      runtime.resolveDeferred(304);
      expect((await primary) === finalPath()).assertTrue();
      expect((await joined) === finalPath()).assertTrue();

      let receiptResolved = false;
      let receiptStatus = '';
      void awaitResult('208').then((payload: KRAvatarCacheBridgePayload): void => {
        receiptResolved = true;
        receiptStatus = payload.status;
      });
      await flushMicrotasks();
      expect(receiptResolved && receiptStatus === 'not_modified').assertTrue();
    });

    it('admits one fetch leader before initial decode and publishes one terminal', 0, async () => {
      const runtime = install();
      runtime.seed(finalPath(), 4, true);
      runtime.deferDecode(finalPath());
      runtime.queue(200, 5);
      runtime.queue(200, 6);
      const primary = fetchAvatar(model('209'));
      const terminal = awaitResult('209');
      await Promise.resolve();
      expect(runtime.pendingDecodeCount() === 1 && runtime.requests.length === 0).assertTrue();

      const joined = fetchAvatar(model('209'));
      await Promise.resolve();
      await Promise.resolve();
      runtime.resolveDeferredDecode(true);
      expect((await primary) === stagePath('209')).assertTrue();
      expect((await joined) === stagePath('209')).assertTrue();
      expect((await terminal).status === 'success').assertTrue();

      let duplicateResolved = false;
      void awaitResult('209').then((_payload: KRAvatarCacheBridgePayload): void => {
        duplicateResolved = true;
      });
      await Promise.resolve();
      expect(!(runtime.requests.length === 2 && duplicateResolved)).assertTrue();
      expect(runtime.requests.length === 1).assertTrue();
      expect(duplicateResolved).assertFalse();
    });

    it('publishes one terminal for delayed fresh stale-suppressed and suppressed followers', 0, async () => {
      const modes = ['render_fresh', 'render_stale_suppressed'];
      let generation = 210;
      for (const mode of modes) {
        const runtime = install();
        const currentGeneration = `${generation}`;
        generation += 1;
        runtime.seed(finalPath(), 4, true);
        runtime.deferDecode(finalPath());
        const primary = fetchAvatar(model(currentGeneration, mode));
        const terminal = awaitResult(currentGeneration);
        await Promise.resolve();
        expect(runtime.pendingDecodeCount() === 1).assertTrue();
        const follower = fetchAvatar(model(currentGeneration, mode));
        let followerResolved = false;
        let followerPath: KRAny = null;
        void follower.then((value: KRAny): void => {
          followerResolved = true;
          followerPath = value;
        });
        await Promise.resolve();
        await Promise.resolve();
        await Promise.resolve();
        expect(followerResolved && followerPath === finalPath()).assertTrue();
        runtime.resolveDeferredDecode(true);
        expect((await primary) === finalPath()).assertTrue();
        expect((await terminal).status === 'rendered').assertTrue();
        expect(runtime.requests.length === 0).assertTrue();
        let duplicateResolved = false;
        void awaitResult(currentGeneration).then((_payload: KRAvatarCacheBridgePayload): void => {
          duplicateResolved = true;
        });
        await Promise.resolve();
        expect(duplicateResolved).assertFalse();
      }

      const runtime = install();
      const terminal = awaitResult('212');
      let follower: Promise<KRAny> = Promise.resolve(finalPath());
      const primary = new Promise<KRAny>((resolve: (value: KRAny) => void): void => {
        expect(KRAvatarCacheManager.fetchDescriptorForTesting(
          CONTEXT,
          model('212', 'suppressed'),
          (value: KRAny): void => {
            follower = fetchAvatar(model('212', 'suppressed'));
            resolve(value);
          }
        )).assertTrue();
      });
      expect((await primary) === null).assertTrue();
      expect((await follower) === null).assertTrue();
      expect((await terminal).status === 'suppressed').assertTrue();
      expect(runtime.pendingDecodeCount() === 0 && runtime.requests.length === 0).assertTrue();
      let duplicateResolved = false;
      void awaitResult('212').then((_payload: KRAvatarCacheBridgePayload): void => {
        duplicateResolved = true;
      });
      await Promise.resolve();
      expect(duplicateResolved).assertFalse();
    });

    it('keeps stale follower immediate during pending failure and rejects mixed follower policy', 0, async () => {
      let runtime = install();
      runtime.seed(finalPath(), 4, true);
      runtime.deferNextHttp();
      const terminal = awaitResult('213');
      expect((await fetchAvatar(model('213', 'render_stale_revalidate'))) === finalPath()).assertTrue();
      expect(runtime.requests.length === 1).assertTrue();
      let staleResolved = false;
      let stalePath: KRAny = null;
      void fetchAvatar(model('213', 'render_stale_revalidate')).then((value: KRAny): void => {
        staleResolved = true;
        stalePath = value;
      });
      await Promise.resolve();
      await Promise.resolve();
      expect(staleResolved && stalePath === finalPath()).assertTrue();
      runtime.resolveDeferred(500);
      expect((await terminal).status === 'temporary_transport').assertTrue();
      expect(runtime.requests.length === 1).assertTrue();

      runtime = install();
      runtime.seed(finalPath(), 4, true);
      runtime.deferNextHttp();
      const mixedTerminal = awaitResult('214');
      expect((await fetchAvatar(model('214', 'render_stale_revalidate'))) === finalPath()).assertTrue();
      expect((await fetchAvatar(model('214', 'fetch'))) === null).assertTrue();
      runtime.resolveDeferred(500);
      expect((await mixedTerminal).status === 'temporary_transport').assertTrue();
      expect(runtime.requests.length === 1).assertTrue();
    });

    it('drains a settled lifecycle follower on retirement without publishing a second receipt', 0, async () => {
      const runtime = install();
      runtime.seed(finalPath(), 4, true);
      let follower: Promise<KRAny> = Promise.resolve(finalPath());
      const command = descriptor('215');
      command.action = 'awaitResult';
      const record = JSON.parse(JSON.stringify(command)) as Record<string, Object>;
      const terminal = new Promise<KRAvatarCacheBridgePayload>(
        (resolve: (value: KRAvatarCacheBridgePayload) => void): void => {
          void KRAvatarCacheManager.handleBridge(CONTEXT, record, (value: KRAny): void => {
            runtime.deferDecode(finalPath());
            follower = fetchAvatar(model('215', 'render_fresh'));
            resolve(value as KRAvatarCacheBridgePayload);
          });
        }
      );
      expect((await fetchAvatar(model('215', 'render_fresh'))) === finalPath()).assertTrue();
      expect((await terminal).status === 'rendered').assertTrue();
      await Promise.resolve();
      expect(runtime.pendingDecodeCount() === 1).assertTrue();
      expect((await bridge('retireOwner', descriptor('215'))).status === 'ok').assertTrue();
      expect((await follower) === null).assertTrue();
      let duplicateResolved = false;
      void awaitResult('215').then((_payload: KRAvatarCacheBridgePayload): void => {
        duplicateResolved = true;
      });
      runtime.resolveDeferredDecode(true);
      await Promise.resolve();
      await Promise.resolve();
      expect(duplicateResolved).assertFalse();
    });

    it('keeps an old follower bound to its settled lifecycle across a new leader', 0, async () => {
      const runtime = install();
      runtime.seed(finalPath(), 4, true);
      runtime.deferNextHttp();
      const firstTerminal = awaitResult('216');
      expect((await fetchAvatar(model('216', 'render_stale_revalidate'))) === finalPath()).assertTrue();
      runtime.deferDecode(finalPath());
      const oldFollower = fetchAvatar(model('216', 'render_stale_revalidate'));
      await Promise.resolve();
      expect(runtime.pendingDecodeCount() === 1).assertTrue();
      runtime.resolveDeferred(500);
      expect((await firstTerminal).status === 'temporary_transport').assertTrue();
      runtime.unlink(finalPath());
      runtime.deferNextHttp();
      const nextTerminal = awaitResult('216');
      const nextLeader = fetchAvatar(model('216'));
      await Promise.resolve();
      expect(runtime.requests.length === 2).assertTrue();
      let oldResolved = false;
      let oldPath: KRAny = finalPath();
      void oldFollower.then((value: KRAny): void => {
        oldResolved = true;
        oldPath = value;
      });
      runtime.resolveDeferredDecode(false);
      await Promise.resolve();
      await Promise.resolve();
      expect(oldResolved && oldPath === null).assertTrue();
      runtime.resolveDeferred(200, 5);
      expect((await nextLeader) === stagePath('216')).assertTrue();
      expect((await nextTerminal).status === 'success').assertTrue();
    });

    it('rejects retirement callback reentry without leaving a half-live lifecycle', 0, async () => {
      const runtime = install();
      runtime.deferNextHttp();
      let reentrantRender: Promise<KRAny> = Promise.resolve(finalPath());
      let reentrantReceipt: Promise<KRAvatarCacheBridgePayload> = Promise.resolve(new TestBridgePayload());
      const command = descriptor('217');
      command.action = 'awaitResult';
      const record = JSON.parse(JSON.stringify(command)) as Record<string, Object>;
      const firstReceipt = new Promise<KRAvatarCacheBridgePayload>(
        (resolve: (value: KRAvatarCacheBridgePayload) => void): void => {
          void KRAvatarCacheManager.handleBridge(CONTEXT, record, (value: KRAny): void => {
            reentrantRender = fetchAvatar(model('217'));
            reentrantReceipt = awaitResult('217');
            resolve(value as KRAvatarCacheBridgePayload);
          });
        }
      );
      const firstRender = fetchAvatar(model('217'));
      await Promise.resolve();
      expect((await bridge('retireOwner', descriptor('217'))).status === 'ok').assertTrue();
      expect((await firstReceipt).status === 'retired').assertTrue();
      expect((await firstRender) === null).assertTrue();
      expect((await reentrantReceipt).status === 'retired').assertTrue();
      let reentrantRenderResolved = false;
      let reentrantPath: KRAny = finalPath();
      void reentrantRender.then((value: KRAny): void => {
        reentrantRenderResolved = true;
        reentrantPath = value;
      });
      await Promise.resolve();
      await Promise.resolve();
      expect(reentrantRenderResolved && reentrantPath === null).assertTrue();
      runtime.resolveDeferred(200, 3);
      runtime.queue(200, 4);
      const restartedReceipt = awaitResult('217');
      expect((await fetchAvatar(model('217'))) === stagePath('217')).assertTrue();
      expect((await restartedReceipt).status === 'success').assertTrue();
    });

    it('keeps owner retirement admission closed across nested same-scope retirement', 0, async () => {
      const runtime = install();
      runtime.deferNextHttp();
      let innerStatus = '';
      let nestedRenderResolved = false;
      let nestedPath: KRAny = finalPath();
      let nestedReceiptResolved = false;
      let nestedReceiptStatus = '';
      const outerAwait = descriptor('218');
      const firstReceipt = new Promise<KRAvatarCacheBridgePayload>(
        (resolve: (value: KRAvatarCacheBridgePayload) => void): void => {
          bridgeSync('awaitResult', outerAwait, (value: KRAvatarCacheBridgePayload): void => {
            bridgeSync('retireOwner', descriptor('218'), (inner: KRAvatarCacheBridgePayload): void => {
              innerStatus = inner.status;
              void fetchAvatar(model('218')).then((path: KRAny): void => {
                nestedRenderResolved = true;
                nestedPath = path;
              });
              void awaitResult('218').then((receipt: KRAvatarCacheBridgePayload): void => {
                nestedReceiptResolved = true;
                nestedReceiptStatus = receipt.status;
              });
            });
            resolve(value);
          });
        }
      );
      const firstRender = fetchAvatar(model('218'));
      await Promise.resolve();
      expect(runtime.requests.length === 1).assertTrue();
      expect((await bridge('retireOwner', descriptor('218'))).status === 'ok').assertTrue();
      expect(innerStatus === 'ok').assertTrue();
      expect((await firstReceipt).status === 'retired').assertTrue();
      expect((await firstRender) === null).assertTrue();
      await Promise.resolve();
      await Promise.resolve();
      expect(nestedReceiptResolved && nestedReceiptStatus === 'retired').assertTrue();
      expect(nestedRenderResolved && nestedPath === null).assertTrue();
      expect(runtime.requests.length === 1 && !runtime.files.has(stagePath('218'))).assertTrue();
      runtime.resolveDeferred(200, 3);
      await Promise.resolve();
      await Promise.resolve();
      expect(!runtime.files.has(stagePath('218'))).assertTrue();
      runtime.queue(200, 4);
      const restartedReceipt = awaitResult('218');
      expect((await fetchAvatar(model('218'))) === stagePath('218')).assertTrue();
      expect((await restartedReceipt).status === 'success').assertTrue();
      expect(runtime.requests.length === 2).assertTrue();
    });

    it('keeps subject retirement admission closed across nested same-scope retirement', 0, async () => {
      const runtime = install();
      runtime.deferNextHttp();
      let innerStatus = '';
      let nestedRenderResolved = false;
      let nestedPath: KRAny = finalPath();
      let nestedReceiptResolved = false;
      let nestedReceiptStatus = '';
      const outerAwait = descriptor('219');
      const firstReceipt = new Promise<KRAvatarCacheBridgePayload>(
        (resolve: (value: KRAvatarCacheBridgePayload) => void): void => {
          bridgeSync('awaitResult', outerAwait, (value: KRAvatarCacheBridgePayload): void => {
            bridgeSync('retireSubject', descriptor('219'), (inner: KRAvatarCacheBridgePayload): void => {
              innerStatus = inner.status;
              void fetchAvatar(model('219')).then((path: KRAny): void => {
                nestedRenderResolved = true;
                nestedPath = path;
              });
              void awaitResult('219').then((receipt: KRAvatarCacheBridgePayload): void => {
                nestedReceiptResolved = true;
                nestedReceiptStatus = receipt.status;
              });
            });
            resolve(value);
          });
        }
      );
      const firstRender = fetchAvatar(model('219'));
      await Promise.resolve();
      expect(runtime.requests.length === 1).assertTrue();
      expect((await bridge('retireSubject', descriptor('219'))).status === 'ok').assertTrue();
      expect(innerStatus === 'ok').assertTrue();
      expect((await firstReceipt).status === 'retired').assertTrue();
      expect((await firstRender) === null).assertTrue();
      await Promise.resolve();
      await Promise.resolve();
      expect(nestedReceiptResolved && nestedReceiptStatus === 'retired').assertTrue();
      expect(nestedRenderResolved && nestedPath === null).assertTrue();
      expect(runtime.requests.length === 1 && !runtime.files.has(stagePath('219'))).assertTrue();
      runtime.resolveDeferred(200, 3);
      await Promise.resolve();
      await Promise.resolve();
      expect(!runtime.files.has(stagePath('219'))).assertTrue();
      runtime.queue(200, 4);
      const restartedReceipt = awaitResult('219');
      expect((await fetchAvatar(model('219'))) === stagePath('219')).assertTrue();
      expect((await restartedReceipt).status === 'success').assertTrue();
      expect(runtime.requests.length === 2).assertTrue();
    });

    it('keeps global retirement admission closed across nested clear all', 0, async () => {
      const runtime = install();
      runtime.deferNextHttp();
      let innerResult = false;
      let nestedRenderResolved = false;
      let nestedPath: KRAny = finalPath();
      let nestedReceiptResolved = false;
      let nestedReceiptStatus = '';
      const outerAwait = descriptor('220');
      const firstReceipt = new Promise<KRAvatarCacheBridgePayload>(
        (resolve: (value: KRAvatarCacheBridgePayload) => void): void => {
          bridgeSync('awaitResult', outerAwait, (value: KRAvatarCacheBridgePayload): void => {
            innerResult = KRAvatarCacheManager.clearAll(CONTEXT);
            void fetchAvatar(model('220')).then((path: KRAny): void => {
              nestedRenderResolved = true;
              nestedPath = path;
            });
            void awaitResult('220').then((receipt: KRAvatarCacheBridgePayload): void => {
              nestedReceiptResolved = true;
              nestedReceiptStatus = receipt.status;
            });
            resolve(value);
          });
        }
      );
      const firstRender = fetchAvatar(model('220'));
      await Promise.resolve();
      expect(runtime.requests.length === 1).assertTrue();
      expect(KRAvatarCacheManager.clearAll(CONTEXT)).assertTrue();
      expect(innerResult).assertTrue();
      expect((await firstReceipt).status === 'retired').assertTrue();
      expect((await firstRender) === null).assertTrue();
      await Promise.resolve();
      await Promise.resolve();
      expect(nestedReceiptResolved && nestedReceiptStatus === 'retired').assertTrue();
      expect(nestedRenderResolved && nestedPath === null).assertTrue();
      expect(runtime.requests.length === 1 && !runtime.files.has(stagePath('220'))).assertTrue();
      runtime.resolveDeferred(200, 3);
      await Promise.resolve();
      await Promise.resolve();
      expect(!runtime.files.has(stagePath('220'))).assertTrue();
      runtime.queue(200, 4);
      const restartedReceipt = awaitResult('220');
      expect((await fetchAvatar(model('220'))) === stagePath('220')).assertTrue();
      expect((await restartedReceipt).status === 'success').assertTrue();
      expect(runtime.requests.length === 2).assertTrue();
    });

    it('rejects callback-reentrant finalize during owner subject and global retirement', 0, async () => {
      const actions = ['retireOwner', 'retireSubject', 'clearAll'];
      for (let index = 0; index < actions.length; index += 1) {
        const runtime = install();
        const activeGeneration = `${221 + index}`;
        const finalizeGeneration = `${231 + index * 2}`;
        const lowerGeneration = `${230 + index * 2}`;
        runtime.deferNextHttp();
        runtime.seed(stagePath(finalizeGeneration, OTHER_BYTES), 4, true);
        runtime.deferDecode(finalPath(OTHER_BYTES));
        const finalizeCommand = descriptor(finalizeGeneration, 'fetch', OTHER_BYTES);
        finalizeCommand.outcome = 'successful_bytes';
        let callbackFinalize = Promise.resolve<KRAvatarCacheBridgePayload>(new TestBridgePayload());
        const outerAwait = descriptor(activeGeneration);
        const firstReceipt = new Promise<KRAvatarCacheBridgePayload>(
          (resolve: (value: KRAvatarCacheBridgePayload) => void): void => {
            bridgeSync('awaitResult', outerAwait, (value: KRAvatarCacheBridgePayload): void => {
              callbackFinalize = bridge('finalize', finalizeCommand);
              resolve(value);
            });
          }
        );
        const firstRender = fetchAvatar(model(activeGeneration));
        await Promise.resolve();
        expect(runtime.requests.length === 1).assertTrue();
        let retirementSucceeded = false;
        if (actions[index] === 'clearAll') {
          retirementSucceeded = KRAvatarCacheManager.clearAll(CONTEXT);
        } else {
          retirementSucceeded = (await bridge(actions[index], descriptor(activeGeneration))).status === 'ok';
        }
        expect(retirementSucceeded).assertTrue();
        expect((await firstReceipt).status === 'retired').assertTrue();
        expect((await firstRender) === null).assertTrue();
        if (runtime.pendingDecodeCount() > 0) runtime.resolveDeferredDecode(true);
        const reentrantResult = await callbackFinalize;
        runtime.resolveDeferred(200, 3);
        await Promise.resolve();
        await Promise.resolve();
        const promotedReentrant = runtime.promoted.includes(`${OTHER_BYTES}:${finalizeGeneration}`);
        const finalAbsentAfterRetirement = !runtime.files.has(finalPath(OTHER_BYTES));
        runtime.clearDeferredDecodes(finalPath(OTHER_BYTES));
        runtime.seed(stagePath(lowerGeneration, OTHER_BYTES), 4, true);
        const lowerCommand = descriptor(lowerGeneration, 'fetch', OTHER_BYTES);
        lowerCommand.outcome = 'successful_bytes';
        const lowerResult = await bridge('finalize', lowerCommand);
        expect(
          reentrantResult.status === 'error' && reentrantResult.code === 'finalize_retired' &&
          !promotedReentrant && finalAbsentAfterRetirement && lowerResult.status === 'ok' &&
          runtime.files.has(finalPath(OTHER_BYTES))
        ).assertTrue();
      }
    });

    it('inspects file availability and decoder admission independently', 0, async () => {
      const runtime = install();
      runtime.seed(finalPath(), 3, false);
      const corrupt = await bridge('inspect', descriptor('1'));
      expect(corrupt.bytesAvailable === true && corrupt.bytesDecodable === false).assertTrue();
      runtime.decodable.add(finalPath());
      const usable = await bridge('inspect', descriptor('1'));
      expect(usable.bytesAvailable === true && usable.bytesDecodable === true).assertTrue();
    });

    it('settles an HTTP 404 as resource_absent without staging', 0, async () => {
      const runtime = install();
      runtime.queue(404);
      expect((await fetchAvatar(model('31'))) === null).assertTrue();
      expect((await awaitResult('31')).status === 'resource_absent').assertTrue();
      expect(runtime.files.has(stagePath('31'))).assertFalse();
    });

    it('renders stale immediately, revalidates, finalizes, and exposes new accepted bytes', 0, async () => {
      const runtime = install();
      runtime.seed(finalPath(), 2);
      runtime.queue(200, 7);
      const rendered = await fetchAvatar(model('4', 'render_stale_revalidate', BYTES, 'old'));
      expect(rendered === finalPath()).assertTrue();
      const receipt = await awaitResult('4');
      expect(receipt.status === 'success').assertTrue();
      expect(runtime.requests.length === 1 && runtime.requests[0]['If-None-Match'] === 'old').assertTrue();
      const finalize = descriptor('4');
      finalize.outcome = 'successful_bytes';
      expect((await bridge('finalize', finalize)).status === 'ok').assertTrue();
      expect(runtime.promoted.includes(`${BYTES}:4`)).assertTrue();
      expect(runtime.stat(finalPath()).size === 7).assertTrue();
      const next = await fetchAvatar(model('5', 'render_fresh'));
      expect(next === finalPath()).assertTrue();
      expect((await awaitResult('5')).status === 'rendered').assertTrue();
    });

    it('rolls back one decoded generation without touching another', 0, async () => {
      const runtime = install();
      runtime.queue(200, 3);
      expect((await fetchAvatar(model('6'))) === stagePath('6')).assertTrue();
      expect((await awaitResult('6')).status === 'success').assertTrue();
      const rollback = descriptor('6');
      assignFinalizeAttempt(rollback);
      expect((await bridge('rollback', rollback)).status === 'ok').assertTrue();
      expect(runtime.files.has(stagePath('6'))).assertFalse();
      expect(runtime.retired.includes(`${BYTES}:6`)).assertTrue();
    });

    it('rejects stale out-of-order finalize and preserves newer accepted bytes', 0, async () => {
      const runtime = install();
      runtime.queue(200, 2);
      runtime.queue(200, 5);
      await fetchAvatar(model('7'));
      await awaitResult('7');
      await fetchAvatar(model('8'));
      await awaitResult('8');
      const newer = descriptor('8');
      newer.outcome = 'successful_bytes';
      expect((await bridge('finalize', newer)).status === 'ok').assertTrue();
      const older = descriptor('7');
      older.outcome = 'successful_bytes';
      const stale = await bridge('finalize', older);
      expect(stale.status === 'error' && stale.code === 'stale_generation').assertTrue();
      expect(runtime.stat(finalPath()).size === 5).assertTrue();
      expect(runtime.retired.includes(`${BYTES}:7`)).assertTrue();
    });

    it('fences a late response during clear and cannot resurrect stage or decoded state', 0, async () => {
      const runtime = install();
      runtime.deferNextHttp();
      const fetch = fetchAvatar(model('9'));
      const receipt = awaitResult('9');
      await Promise.resolve();
      expect(KRAvatarCacheManager.clearAll(CONTEXT)).assertTrue();
      expect((await receipt).status === 'retired').assertTrue();
      expect((await fetch) === null).assertTrue();
      runtime.resolveDeferred(200, 3);
      await Promise.resolve();
      expect(runtime.files.has(stagePath('9'))).assertFalse();
      expect(runtime.retired.includes(`${BYTES}:9`)).assertTrue();
    });

    it('admits before initial decode and fences clear owner and subject retirement', 0, async () => {
      let runtime = install();
      runtime.seed(finalPath());
      runtime.deferDecode(finalPath());
      const clearFetch = fetchAvatar(model('901', 'render_fresh'));
      const clearReceipt = awaitResult('901');
      await Promise.resolve();
      expect(KRAvatarCacheManager.clearAll(CONTEXT)).assertTrue();
      expect((await clearReceipt).status === 'retired').assertTrue();
      expect((await clearFetch) === null).assertTrue();
      runtime.resolveDeferredDecode(true);
      await Promise.resolve();

      runtime = install();
      runtime.deferDecode(finalPath());
      const ownerFetch = fetchAvatar(model('902'));
      const ownerReceipt = awaitResult('902');
      await Promise.resolve();
      expect((await bridge('retireOwner', descriptor('902'))).status === 'ok').assertTrue();
      expect((await ownerReceipt).status === 'retired').assertTrue();
      expect((await ownerFetch) === null).assertTrue();
      runtime.resolveDeferredDecode(false);
      await Promise.resolve();
      expect(runtime.requests.length === 0).assertTrue();

      runtime = install();
      runtime.deferDecode(finalPath());
      const subjectFetch = fetchAvatar(model('903'));
      const subjectReceipt = awaitResult('903');
      await Promise.resolve();
      expect((await bridge('retireSubject', descriptor('903'))).status === 'ok').assertTrue();
      expect((await subjectReceipt).status === 'retired').assertTrue();
      expect((await subjectFetch) === null).assertTrue();
      runtime.resolveDeferredDecode(false);
      await Promise.resolve();
      expect(runtime.requests.length === 0).assertTrue();
    });

    it('settles tokenless waiters once and requires a new lifecycle before a second await resolves', 0, async () => {
      let runtime = install();
      const waiter = awaitResult('904');
      expect((await bridge('retireOwner', descriptor('904'))).status === 'ok').assertTrue();
      expect((await waiter).status === 'retired').assertTrue();
      let replayResolved = false;
      const replay = awaitResult('904');
      void replay.then((_payload: KRAvatarCacheBridgePayload): void => {
        replayResolved = true;
      });
      await Promise.resolve();
      expect(replayResolved).assertFalse();
      runtime.queue(200, 3);
      expect((await fetchAvatar(model('904'))) === stagePath('904')).assertTrue();
      expect((await replay).status === 'success').assertTrue();

      runtime = install();
      runtime.queue(200, 3);
      expect((await fetchAvatar(model('905'))) === stagePath('905')).assertTrue();
      await Promise.resolve();
      expect((await bridge('retireSubject', descriptor('905'))).status === 'ok').assertTrue();
      expect((await awaitResult('905')).status === 'retired').assertTrue();
      let storedReplayResolved = false;
      const storedReplay = awaitResult('905');
      void storedReplay.then((_payload: KRAvatarCacheBridgePayload): void => {
        storedReplayResolved = true;
      });
      await Promise.resolve();
      expect(storedReplayResolved).assertFalse();
      runtime.queue(200, 4);
      expect((await fetchAvatar(model('905'))) === stagePath('905')).assertTrue();
      expect((await storedReplay).status === 'success').assertTrue();

      runtime = install();
      runtime.seed(stagePath('9'), 3);
      const accepted = descriptor('9');
      accepted.outcome = 'successful_bytes';
      expect((await bridge('finalize', accepted)).status === 'ok').assertTrue();
      expect((await bridge('retireOwner', descriptor('9'))).status === 'ok').assertTrue();
      runtime.seed(stagePath('1'), 4);
      const restarted = descriptor('1');
      restarted.outcome = 'successful_bytes';
      expect((await bridge('finalize', restarted)).status === 'ok').assertTrue();
      expect(runtime.stat(finalPath()).size === 4).assertTrue();
    });

    it('fences owner and subject retirement during active fetch', 0, async () => {
      let runtime = install();
      runtime.deferNextHttp();
      const ownerFetch = fetchAvatar(model('10'));
      const ownerReceipt = awaitResult('10');
      await Promise.resolve();
      expect((await bridge('retireOwner', descriptor('10'))).status === 'ok').assertTrue();
      expect((await ownerReceipt).status === 'retired').assertTrue();
      expect((await ownerFetch) === null).assertTrue();
      runtime.resolveDeferred(200);
      await Promise.resolve();
      expect(runtime.files.has(stagePath('10'))).assertFalse();

      runtime = install();
      runtime.deferNextHttp();
      const subjectFetch = fetchAvatar(model('11'));
      const subjectReceipt = awaitResult('11');
      await Promise.resolve();
      expect((await bridge('retireSubject', descriptor('11'))).status === 'ok').assertTrue();
      expect((await subjectReceipt).status === 'retired').assertTrue();
      expect((await subjectFetch) === null).assertTrue();
      runtime.resolveDeferred(200);
      await Promise.resolve();
      expect(runtime.files.has(stagePath('11'))).assertFalse();
    });

    it('settles active clear owner and subject retirement before fallible stage cleanup', 0, async () => {
      let runtime = install();
      runtime.deferNextHttp();
      const clearFetch = fetchAvatar(model('110'));
      const clearReceipt = awaitResult('110');
      await Promise.resolve();
      runtime.failExists.add(stagePath('110'));
      expect(KRAvatarCacheManager.clearAll(CONTEXT)).assertFalse();
      expect((await clearFetch) === null).assertTrue();
      expect((await clearReceipt).status === 'retired').assertTrue();
      runtime.resolveDeferred(200, 3);
      await Promise.resolve();
      expect(runtime.files.has(stagePath('110'))).assertFalse();

      runtime = install();
      runtime.deferNextHttp();
      const ownerFetch = fetchAvatar(model('111'));
      const ownerReceipt = awaitResult('111');
      await Promise.resolve();
      runtime.failExists.add(stagePath('111'));
      const ownerRetire = await bridge('retireOwner', descriptor('111'));
      expect(ownerRetire.status === 'error').assertTrue();
      expect((await ownerFetch) === null).assertTrue();
      expect((await ownerReceipt).status === 'retired').assertTrue();
      runtime.resolveDeferred(200, 3);
      await Promise.resolve();
      expect(runtime.files.has(stagePath('111'))).assertFalse();

      runtime = install();
      runtime.deferNextHttp();
      const subjectFetch = fetchAvatar(model('112'));
      const subjectReceipt = awaitResult('112');
      await Promise.resolve();
      runtime.failExists.add(stagePath('112'));
      const subjectRetire = await bridge('retireSubject', descriptor('112'));
      expect(subjectRetire.status === 'error').assertTrue();
      expect((await subjectFetch) === null).assertTrue();
      expect((await subjectReceipt).status === 'retired').assertTrue();
      runtime.resolveDeferred(200, 3);
      await Promise.resolve();
      expect(runtime.files.has(stagePath('112'))).assertFalse();
    });

    it('returns one context-free bridge error when retirement accounting stat fails', 0, async () => {
      const runtime = install();
      runtime.seed(finalPath(), 3);
      runtime.failStat.add(finalPath());
      const command = descriptor('113');
      command.action = 'retireOwner';
      const record = JSON.parse(JSON.stringify(command)) as Record<string, Object>;
      let callbacks = 0;
      let result: KRAvatarCacheBridgePayload = {
        status: '',
        managedSet: '',
        managedIndex: '',
        budgetScope: '',
        indexedRetirement: false,
        managedBytes: ''
      };
      try {
        await KRAvatarCacheManager.handleBridge(CONTEXT, record, (value: KRAny): void => {
          callbacks += 1;
          result = value as KRAvatarCacheBridgePayload;
        });
      } catch (_error) {
      }
      await Promise.resolve();
      expect(callbacks === 1).assertTrue();
      expect(result.status === 'error' && result.code === 'avatar_cache_native_failed').assertTrue();
      expect(result.managedBytes === '0').assertTrue();
    });

    it('fails closed on rollback unlink and promotion rename failure without deleting old final', 0, async () => {
      const runtime = install();
      runtime.seed(finalPath(), 4);
      runtime.seed(stagePath('12'), 6);
      runtime.failRename = true;
      const promote = descriptor('12');
      promote.outcome = 'successful_bytes';
      const failedPromotion = await bridge('finalize', promote);
      expect(failedPromotion.status === 'error' && failedPromotion.code === 'promotion_failed').assertTrue();
      expect(runtime.stat(finalPath()).size === 4).assertTrue();
      expect(runtime.files.has(stagePath('12'))).assertTrue();
      expect(runtime.promoted.includes(`${BYTES}:12`)).assertFalse();

      runtime.failUnlink.add(stagePath('12'));
      const failedRollback = await bridge('rollback', descriptor('12'));
      expect(failedRollback.status === 'ok').assertTrue();
      expect(runtime.files.has(stagePath('12'))).assertTrue();
    });

    it('fails closed on existence stat and no-op or malformed rename postconditions', 0, async () => {
      let runtime = install();
      runtime.seed(stagePath('121'), 6);
      runtime.failExists.add(stagePath('121'));
      const existsFailure = await bridge('rollback', descriptor('121'));
      expect(existsFailure.status === 'ok').assertTrue();
      expect(runtime.files.has(stagePath('121'))).assertTrue();

      runtime = install();
      runtime.seed(finalPath(), 3);
      runtime.failStat.add(finalPath());
      const statFailureWaiter = awaitResult('1211');
      expect(KRAvatarCacheManager.clearAll(CONTEXT)).assertFalse();
      expect((await statFailureWaiter).status === 'retired').assertTrue();
      expect(runtime.files.has(finalPath())).assertTrue();

      runtime = install();
      runtime.seed(stagePath('122'), 6);
      runtime.noOpRename = true;
      const noOp = descriptor('122');
      noOp.outcome = 'successful_bytes';
      const noOpResult = await bridge('finalize', noOp);
      expect(noOpResult.status === 'error' && noOpResult.code === 'promotion_postcondition_failed').assertTrue();
      expect(runtime.promoted.includes(`${BYTES}:122`)).assertFalse();

      runtime = install();
      runtime.seed(finalPath(), 3);
      runtime.seed(stagePath('123'), 6);
      runtime.malformedRenameAt.add(2);
      const malformed = descriptor('123');
      malformed.outcome = 'successful_bytes';
      const malformedResult = await bridge('finalize', malformed);
      expect(malformedResult.status === 'error' && malformedResult.code === 'promotion_postcondition_failed').assertTrue();
      expect(runtime.promoted.includes(`${BYTES}:123`)).assertFalse();
    });

    it('fails closed on retire and clear unlink failures', 0, async () => {
      let runtime = install();
      runtime.seed(finalPath(), 3);
      runtime.failUnlink.add(finalPath());
      const retired = await bridge('retireOwner', descriptor('13'));
      expect(retired.status === 'error' && retired.code === 'retire_delete_failed').assertTrue();
      expect(runtime.files.has(finalPath())).assertTrue();

      runtime = install();
      runtime.seed(finalPath(), 3);
      runtime.failUnlink.add(finalPath());
      const subjectRetired = await bridge('retireSubject', descriptor('13'));
      expect(subjectRetired.status === 'error' && subjectRetired.code === 'retire_delete_failed').assertTrue();
      expect(runtime.files.has(finalPath())).assertTrue();

      runtime = install();
      runtime.seed(finalPath(), 3);
      runtime.failUnlink.add(finalPath());
      expect(KRAvatarCacheManager.clearAll(CONTEXT)).assertFalse();
      expect(runtime.files.has(finalPath())).assertTrue();
    });

    it('rejects corrupt-final 304 and retries without validators', 0, async () => {
      const runtime = install();
      runtime.seed(finalPath(), 3, false);
      runtime.queue(304);
      runtime.queue(200, 4);
      const path = await fetchAvatar(model('14', 'fetch', BYTES, 'stale'));
      expect(path === stagePath('14')).assertTrue();
      expect((await awaitResult('14')).status === 'success').assertTrue();
      expect(runtime.requests.length === 2).assertTrue();
      expect(runtime.requests[0]['If-None-Match'] === undefined).assertTrue();
      expect(runtime.requests[1]['If-None-Match'] === undefined).assertTrue();
    });

    it('keeps a 304 decoded generation drawable through mandatory finalize', 0, async () => {
      const runtime = install();
      runtime.seed(finalPath(), 3, true);
      runtime.seedDecoded(BYTES, '141');
      runtime.queue(304);
      expect((await fetchAvatar(model('141', 'fetch', BYTES, 'valid'))) === finalPath()).assertTrue();
      expect((await awaitResult('141')).status === 'not_modified').assertTrue();
      expect(runtime.requests.length === 1 && runtime.requests[0]['If-None-Match'] === 'valid').assertTrue();
      expect(runtime.promoted.includes(`${BYTES}:141`)).assertTrue();
      const finalize = descriptor('141');
      finalize.outcome = 'not_modified';
      expect((await bridge('finalize', finalize)).status === 'ok').assertTrue();
      expect(runtime.retired.includes(`${BYTES}:141`)).assertFalse();
      expect(runtime.activeDecodedGeneration.get(BYTES) === '141').assertTrue();
      expect((await fetchAvatar(model('142', 'render_fresh'))) === finalPath()).assertTrue();
      expect((await awaitResult('142')).status === 'rendered').assertTrue();
    });

    it('rejects a 304 generation without a production decoded PixelMap', 0, async () => {
      const runtime = install();
      runtime.seed(finalPath(), 3, true);
      runtime.queue(304);
      expect((await fetchAvatar(model('143', 'fetch', BYTES, 'valid'))) === null).assertTrue();
      const terminal = await awaitResult('143');
      expect(terminal.status === 'error' && terminal.code === 'decoded_promotion_failed').assertTrue();
      expect(runtime.hasDecoded(BYTES, '143')).assertFalse();
    });

    it('rolls back a post-rename delayed finalize without late promotion and permits retry', 0, async () => {
      const runtime = install();
      runtime.seed(stagePath('200'), 4, true);
      const prior = descriptor('200');
      prior.outcome = 'successful_bytes';
      expect((await bridge('finalize', prior)).status === 'ok').assertTrue();
      expect(runtime.activeDecodedGeneration.get(BYTES) === '200').assertTrue();

      runtime.seed(stagePath('201'), 6, true);
      const candidate = descriptor('201');
      candidate.outcome = 'successful_bytes';
      expect(await authorizeSeededSuccessfulBytes(candidate)).assertTrue();
      runtime.deferDecode(finalPath());
      const pending = bridge('finalize', candidate);
      await flushMicrotasks();
      expect(runtime.pendingDecodeCount() === 1).assertTrue();
      expect(runtime.stat(finalPath()).size === 6).assertTrue();
      expect((await bridge('rollback', candidate)).status === 'ok').assertTrue();
      expect(runtime.stat(finalPath()).size === 4).assertTrue();
      expect(runtime.activeDecodedGeneration.get(BYTES) === '200').assertTrue();
      runtime.resolveDeferredDecode(true);
      const late = await pending;
      expect(late.status === 'error' && late.code === 'finalize_retired').assertTrue();
      expect(runtime.promoted.includes(`${BYTES}:201`)).assertFalse();

      runtime.seed(stagePath('201'), 7, true);
      expect((await bridge('finalize', candidate)).status === 'ok').assertTrue();
      expect(runtime.stat(finalPath()).size === 7).assertTrue();
      expect(runtime.activeDecodedGeneration.get(BYTES) === '201').assertTrue();
    });

    it('restores prior bytes decoded generation and watermark after postcondition failure', 0, async () => {
      const runtime = install();
      runtime.seed(stagePath('210'), 4, true);
      const prior = descriptor('210');
      prior.outcome = 'successful_bytes';
      expect((await bridge('finalize', prior)).status === 'ok').assertTrue();

      runtime.seed(stagePath('211'), 6, true);
      runtime.noOpRenameAt.add(3);
      const candidate = descriptor('211');
      candidate.outcome = 'successful_bytes';
      const failed = await bridge('finalize', candidate);
      expect(failed.status === 'error' && failed.code === 'promotion_postcondition_failed').assertTrue();
      expect(runtime.stat(finalPath()).size === 4).assertTrue();
      expect(runtime.activeDecodedGeneration.get(BYTES) === '210').assertTrue();
      runtime.seed(stagePath('209'), 5, true);
      const stale = descriptor('209');
      stale.outcome = 'successful_bytes';
      const staleResult = await bridge('finalize', stale);
      expect(staleResult.status === 'error' && staleResult.code === 'stale_generation').assertTrue();

      runtime.noOpRenameAt.clear();
      runtime.seed(stagePath('211'), 7, true);
      expect((await bridge('finalize', candidate)).status === 'ok').assertTrue();
      expect(runtime.stat(finalPath()).size === 7).assertTrue();
    });

    it('restores prior bytes decoded generation and watermark after decoded promotion failure', 0, async () => {
      const runtime = install();
      runtime.seed(stagePath('220'), 4, true);
      const prior = descriptor('220');
      prior.outcome = 'successful_bytes';
      expect((await bridge('finalize', prior)).status === 'ok').assertTrue();

      runtime.seed(stagePath('221'), 6, true);
      runtime.failDecodedPromote.add('221');
      const candidate = descriptor('221');
      candidate.outcome = 'successful_bytes';
      const failed = await bridge('finalize', candidate);
      expect(failed.status === 'error' && failed.code === 'decoded_promotion_failed').assertTrue();
      expect(runtime.stat(finalPath()).size === 4).assertTrue();
      expect(runtime.activeDecodedGeneration.get(BYTES) === '220').assertTrue();
      runtime.seed(stagePath('219'), 5, true);
      const stale = descriptor('219');
      stale.outcome = 'successful_bytes';
      const staleResult = await bridge('finalize', stale);
      expect(staleResult.status === 'error' && staleResult.code === 'stale_generation').assertTrue();

      runtime.failDecodedPromote.delete('221');
      runtime.seed(stagePath('221'), 7, true);
      expect((await bridge('finalize', candidate)).status === 'ok').assertTrue();
      expect(runtime.stat(finalPath()).size === 7).assertTrue();
    });

    it('keeps g5 authoritative when installed g7 blocks g9 and g7 later fails', 0, async () => {
      const runtime = install();
      runtime.seed(stagePath('5'), 4, true);
      const five = descriptor('5');
      five.outcome = 'successful_bytes';
      expect((await bridge('finalize', five)).status === 'ok').assertTrue();

      runtime.seed(stagePath('7'), 6, true);
      const seven = descriptor('7');
      seven.outcome = 'successful_bytes';
      expect(await authorizeSeededSuccessfulBytes(seven)).assertTrue();
      runtime.seed(stagePath('9'), 8, true);
      const nine = descriptor('9');
      nine.outcome = 'successful_bytes';
      expect(await authorizeSeededSuccessfulBytes(nine)).assertTrue();
      runtime.deferDecode(finalPath());
      const pendingSeven = bridgeWithoutAck('finalize', seven);
      await flushMicrotasks();
      const blockedNine = await bridge('finalize', nine);
      expect(blockedNine.status === 'error').assertTrue();
      expect(blockedNine.code === 'finalize_in_progress').assertTrue();
      runtime.resolveDeferredDecode(false);
      const failedSeven = await pendingSeven;
      expect(failedSeven.status === 'error' && failedSeven.code === 'promotion_postcondition_failed').assertTrue();
      expect(runtime.stat(finalPath()).size === 4).assertTrue();
      expect(runtime.files.has(backupPath('7'))).assertFalse();
      expect(runtime.hasDecoded(BYTES, '5')).assertTrue();
      expect(runtime.hasDecoded(BYTES, '7')).assertFalse();
      expect((await bridge('finalize', nine)).status === 'ok').assertTrue();
      expect(runtime.stat(finalPath()).size === 8).assertTrue();
      expect(runtime.files.has(backupPath('9'))).assertFalse();
    });

    it('restores after finalize callback is invoked but shared acknowledgement is lost', 0, async () => {
      const runtime = install();
      runtime.seed(stagePath('30'), 4, true);
      const prior = descriptor('30');
      prior.outcome = 'successful_bytes';
      expect((await bridge('finalize', prior)).status === 'ok').assertTrue();

      runtime.seed(stagePath('31'), 6, true);
      const candidate = descriptor('31');
      candidate.outcome = 'successful_bytes';
      expect((await bridgeWithoutAck('finalize', candidate, true)).status === 'ok').assertTrue();
      expect(runtime.files.has(backupPath('31'))).assertTrue();
      expect(runtime.hasDecoded(BYTES, '30') && runtime.hasDecoded(BYTES, '31')).assertTrue();
      runtime.advanceClock(14000);
      expect((await bridge('rollback', candidate)).status === 'ok').assertTrue();
      expect(runtime.stat(finalPath()).size === 4).assertTrue();
      expect(runtime.files.has(backupPath('31'))).assertFalse();
      expect(runtime.hasDecoded(BYTES, '30')).assertTrue();
      expect(runtime.hasDecoded(BYTES, '31')).assertFalse();

      runtime.seed(stagePath('31'), 7, true);
      expect((await bridge('finalize', candidate)).status === 'ok').assertTrue();
      expect(runtime.stat(finalPath()).size === 7).assertTrue();
    });

    it('restores prior decoded generation and watermark after a lost NotModified acknowledgement', 0, async () => {
      const runtime = install();
      runtime.seed(stagePath('60'), 4, true);
      const prior = descriptor('60');
      prior.outcome = 'successful_bytes';
      expect((await bridge('finalize', prior)).status === 'ok').assertTrue();

      runtime.seedDecoded(BYTES, '61');
      runtime.queue(304);
      expect((await fetchAvatar(model('61', 'fetch', BYTES, 'valid'))) === finalPath()).assertTrue();
      expect((await awaitResult('61')).status === 'not_modified').assertTrue();
      const candidate = descriptor('61');
      candidate.outcome = 'not_modified';
      expect((await bridgeWithoutAck('finalize', candidate, true)).status === 'ok').assertTrue();
      expect(runtime.hasDecoded(BYTES, '60') && runtime.hasDecoded(BYTES, '61')).assertTrue();
      expect((await bridge('rollback', candidate)).status === 'ok').assertTrue();
      expect(runtime.hasDecoded(BYTES, '60')).assertTrue();
      expect(runtime.hasDecoded(BYTES, '61')).assertFalse();

      runtime.seed(stagePath('59'), 5, true);
      const lower = descriptor('59');
      lower.outcome = 'successful_bytes';
      const stale = await bridge('finalize', lower);
      expect(stale.status === 'error' && stale.code === 'stale_generation').assertTrue();
      runtime.seed(stagePath('62'), 6, true);
      const successor = descriptor('62');
      successor.outcome = 'successful_bytes';
      expect((await bridge('finalize', successor)).status === 'ok').assertTrue();
      expect(runtime.hasDecoded(BYTES, '62')).assertTrue();
    });

    it('keeps real prior decoded bytes until ack cleanup and makes late rollback a no-op', 0, async () => {
      const runtime = install();
      runtime.seed(stagePath('40'), 4, true);
      const prior = descriptor('40');
      prior.outcome = 'successful_bytes';
      expect((await bridge('finalize', prior)).status === 'ok').assertTrue();

      runtime.seed(stagePath('41'), 6, true);
      const candidate = descriptor('41');
      candidate.outcome = 'successful_bytes';
      expect((await bridgeWithoutAck('finalize', candidate)).status === 'ok').assertTrue();
      runtime.failUnlink.add(backupPath('41'));
      expect((await bridge('ackFinalize', candidate)).code === 'finalize_commit_failed').assertTrue();
      expect(runtime.hasDecoded(BYTES, '40') && runtime.hasDecoded(BYTES, '41')).assertTrue();
      expect(runtime.files.has(backupPath('41'))).assertTrue();
      expect((await bridge('rollback', descriptor('41'))).status === 'ok').assertTrue();
      expect(runtime.stat(finalPath()).size === 6).assertTrue();
      expect(runtime.hasDecoded(BYTES, '41')).assertTrue();

      runtime.failUnlink.delete(backupPath('41'));
      expect((await bridge('ackFinalize', candidate)).status === 'ok').assertTrue();
      expect(runtime.files.has(backupPath('41'))).assertFalse();
      expect(runtime.hasDecoded(BYTES, '40')).assertFalse();
      expect(runtime.hasDecoded(BYTES, '41')).assertTrue();
      expect((await bridge('rollback', descriptor('41'))).status === 'ok').assertTrue();
      expect(runtime.hasDecoded(BYTES, '41')).assertTrue();
      expect(runtime.stat(finalPath()).size === 6).assertTrue();
    });

    it('keeps ack cleanup retryable when decoded pruning fails', 0, async () => {
      const runtime = install();
      runtime.seed(stagePath('42'), 4, true);
      const prior = descriptor('42');
      prior.outcome = 'successful_bytes';
      expect((await bridge('finalize', prior)).status === 'ok').assertTrue();

      runtime.seed(stagePath('43'), 6, true);
      const candidate = descriptor('43');
      candidate.outcome = 'successful_bytes';
      expect((await bridgeWithoutAck('finalize', candidate)).status === 'ok').assertTrue();
      runtime.failDecodedRetire = true;
      expect((await bridge('ackFinalize', candidate)).code === 'finalize_commit_failed').assertTrue();
      expect(runtime.files.has(backupPath('43'))).assertFalse();
      expect(runtime.hasDecoded(BYTES, '42') && runtime.hasDecoded(BYTES, '43')).assertTrue();
      expect((await bridge('rollback', descriptor('43'))).status === 'ok').assertTrue();
      expect(runtime.stat(finalPath()).size === 6).assertTrue();

      runtime.failDecodedRetire = false;
      expect((await bridge('ackFinalize', candidate)).status === 'ok').assertTrue();
      expect(runtime.hasDecoded(BYTES, '42')).assertFalse();
      expect(runtime.hasDecoded(BYTES, '43')).assertTrue();
    });

    it('seals an unacknowledged finalize after the bounded rollback window', 0, async () => {
      const runtime = install();
      runtime.seed(stagePath('44'), 4, true);
      const prior = descriptor('44');
      prior.outcome = 'successful_bytes';
      expect((await bridge('finalize', prior)).status === 'ok').assertTrue();

      runtime.seed(stagePath('45'), 6, true);
      const candidate = descriptor('45');
      candidate.outcome = 'successful_bytes';
      expect((await bridgeWithoutAck('finalize', candidate)).status === 'ok').assertTrue();
      expect(runtime.files.has(backupPath('45'))).assertTrue();
      runtime.advanceClock(15001);
      runtime.seed(stagePath('46'), 8, true);
      const successor = descriptor('46');
      successor.outcome = 'successful_bytes';
      expect((await bridge('finalize', successor)).status === 'ok').assertTrue();
      expect(runtime.files.has(backupPath('45'))).assertFalse();
      expect(runtime.hasDecoded(BYTES, '44')).assertFalse();
      expect(runtime.hasDecoded(BYTES, '46')).assertTrue();
      expect(runtime.stat(finalPath()).size === 8).assertTrue();
    });

    it('requires a matching 304 lifecycle and keeps duplicate NotModified finalize idempotent', 0, async () => {
      const runtime = install();
      runtime.seed(finalPath(), 3, true);
      const forged = descriptor('999');
      forged.outcome = 'not_modified';
      const rejected = await bridge('finalize', forged);
      expect(rejected.status === 'error' && rejected.code === 'finalize_binding_required').assertTrue();

      runtime.queue(304);
      runtime.seedDecoded(BYTES, '50');
      expect((await fetchAvatar(model('50', 'fetch', BYTES, 'valid'))) === finalPath()).assertTrue();
      expect((await awaitResult('50')).status === 'not_modified').assertTrue();
      const genuine = descriptor('50');
      genuine.outcome = 'not_modified';
      expect((await bridge('finalize', genuine)).status === 'ok').assertTrue();
      expect((await bridge('finalize', genuine)).status === 'ok').assertTrue();
      runtime.seed(stagePath('49'), 4, true);
      const lower = descriptor('49');
      lower.outcome = 'successful_bytes';
      const stale = await bridge('finalize', lower);
      expect(stale.status === 'error' && stale.code === 'stale_generation').assertTrue();
    });

    it('invalidates a 304 receipt when a new same-operation lifecycle terminates differently', 0, async () => {
      const runtime = install();
      runtime.seed(finalPath(), 3, true);
      runtime.seedDecoded(BYTES, '51');
      runtime.queue(304);
      expect((await fetchAvatar(model('51', 'fetch', BYTES, 'valid'))) === finalPath()).assertTrue();
      expect((await awaitResult('51')).status === 'not_modified').assertTrue();

      runtime.queue(404);
      expect((await fetchAvatar(model('51', 'fetch', BYTES, 'valid'))) === null).assertTrue();
      expect((await awaitResult('51')).status === 'resource_absent').assertTrue();
      const staleReceipt = descriptor('51');
      staleReceipt.outcome = 'not_modified';
      const rejected = await bridge('finalize', staleReceipt);
      expect(rejected.status === 'error' && rejected.code === 'not_modified_without_terminal').assertTrue();
    });

    it('rejects an oversized entry before staging', 0, async () => {
      const runtime = install();
      runtime.queue(200, 5 * 1024 * 1024 + 1);
      expect((await fetchAvatar(model('15'))) === null).assertTrue();
      expect((await awaitResult('15')).status === 'invalid_content').assertTrue();
      expect(runtime.files.has(stagePath('15'))).assertFalse();
    });

    it('revalidates actual stage size at zero exact-cap and cap-plus-one before promotion', 0, async () => {
      let runtime = install();
      runtime.seed(stagePath('150'), 5 * 1024 * 1024);
      const exact = descriptor('150');
      exact.outcome = 'successful_bytes';
      expect((await bridge('finalize', exact)).status === 'ok').assertTrue();
      expect(runtime.renameCount === 1).assertTrue();
      expect(runtime.stat(finalPath()).size === 5 * 1024 * 1024).assertTrue();
      expect(runtime.promoted.includes(`${BYTES}:150`)).assertTrue();

      runtime = install();
      runtime.seed(finalPath(OTHER_BYTES), 3);
      runtime.seed(stagePath('151'), 0);
      const zero = descriptor('151');
      zero.outcome = 'successful_bytes';
      const zeroResult = await bridge('finalize', zero);
      expect(zeroResult.status === 'invalid_content' && zeroResult.code === 'stage_size_invalid').assertTrue();
      expect(runtime.renameCount === 0).assertTrue();
      expect(runtime.promoted.includes(`${BYTES}:151`)).assertFalse();
      expect(runtime.files.has(finalPath(OTHER_BYTES))).assertTrue();
      runtime.seed(stagePath('1'), 4);
      const afterZero = descriptor('1');
      afterZero.outcome = 'successful_bytes';
      expect((await bridge('finalize', afterZero)).status === 'ok').assertTrue();

      runtime = install();
      runtime.seed(finalPath(OTHER_BYTES), 3);
      runtime.seed(stagePath('152'), 5 * 1024 * 1024 + 1);
      const oversized = descriptor('152');
      oversized.outcome = 'successful_bytes';
      const oversizedResult = await bridge('finalize', oversized);
      expect(oversizedResult.status === 'invalid_content' && oversizedResult.code === 'stage_size_invalid').assertTrue();
      expect(runtime.renameCount === 0).assertTrue();
      expect(runtime.promoted.includes(`${BYTES}:152`)).assertFalse();
      expect(runtime.files.has(finalPath(OTHER_BYTES))).assertTrue();
      runtime.seed(stagePath('1'), 4);
      const afterOversized = descriptor('1');
      afterOversized.outcome = 'successful_bytes';
      expect((await bridge('finalize', afterOversized)).status === 'ok').assertTrue();
    });

    it('fences post-rename finalize decode against clear owner and subject retirement', 0, async () => {
      let runtime = install();
      runtime.seed(stagePath('160'), 6);
      const clearCommand = descriptor('160');
      clearCommand.outcome = 'successful_bytes';
      expect(await authorizeSeededSuccessfulBytes(clearCommand)).assertTrue();
      runtime.deferDecode(finalPath());
      const clearFinalize = bridge('finalize', clearCommand);
      await flushMicrotasks();
      expect(runtime.pendingDecodeCount() === 1).assertTrue();
      expect(runtime.renameCount === 1).assertTrue();
      expect(KRAvatarCacheManager.clearAll(CONTEXT)).assertTrue();
      runtime.resolveDeferredDecode(true);
      const clearResult = await clearFinalize;
      expect(clearResult.status === 'error' && clearResult.code === 'finalize_retired').assertTrue();
      expect(runtime.promoted.includes(`${BYTES}:160`)).assertFalse();
      runtime.seed(stagePath('1'), 6);
      const clearRestart = descriptor('1');
      clearRestart.outcome = 'successful_bytes';
      expect((await bridge('finalize', clearRestart)).status === 'ok').assertTrue();

      runtime = install();
      runtime.seed(stagePath('161'), 6);
      const ownerCommand = descriptor('161');
      ownerCommand.outcome = 'successful_bytes';
      expect(await authorizeSeededSuccessfulBytes(ownerCommand)).assertTrue();
      runtime.deferDecode(finalPath());
      const ownerFinalize = bridge('finalize', ownerCommand);
      await flushMicrotasks();
      expect(runtime.pendingDecodeCount() === 1 && runtime.renameCount === 1).assertTrue();
      expect((await bridge('retireOwner', descriptor('161'))).status === 'ok').assertTrue();
      runtime.resolveDeferredDecode(true);
      const ownerResult = await ownerFinalize;
      expect(ownerResult.status === 'error' && ownerResult.code === 'finalize_retired').assertTrue();
      expect(runtime.promoted.includes(`${BYTES}:161`)).assertFalse();
      runtime.seed(stagePath('1'), 6);
      const ownerRestart = descriptor('1');
      ownerRestart.outcome = 'successful_bytes';
      expect((await bridge('finalize', ownerRestart)).status === 'ok').assertTrue();

      runtime = install();
      runtime.seed(stagePath('162'), 6);
      const subjectCommand = descriptor('162');
      subjectCommand.outcome = 'successful_bytes';
      expect(await authorizeSeededSuccessfulBytes(subjectCommand)).assertTrue();
      runtime.deferDecode(finalPath());
      const subjectFinalize = bridge('finalize', subjectCommand);
      await flushMicrotasks();
      expect(runtime.pendingDecodeCount() === 1 && runtime.renameCount === 1).assertTrue();
      expect((await bridge('retireSubject', descriptor('162'))).status === 'ok').assertTrue();
      runtime.resolveDeferredDecode(true);
      const subjectResult = await subjectFinalize;
      expect(subjectResult.status === 'error' && subjectResult.code === 'finalize_retired').assertTrue();
      expect(runtime.promoted.includes(`${BYTES}:162`)).assertFalse();
      runtime.seed(stagePath('1'), 6);
      const subjectRestart = descriptor('1');
      subjectRestart.outcome = 'successful_bytes';
      expect((await bridge('finalize', subjectRestart)).status === 'ok').assertTrue();
    });

    it('rejects g9 while g7 transaction is installed then permits g9 after explicit ack', 0, async () => {
      const runtime = install();
      runtime.seed(stagePath('7'), 6);
      runtime.seed(stagePath('9'), 6);
      const seven = descriptor('7');
      seven.outcome = 'successful_bytes';
      const nine = descriptor('9');
      nine.outcome = 'successful_bytes';
      expect(await authorizeSeededSuccessfulBytes(seven)).assertTrue();
      expect(await authorizeSeededSuccessfulBytes(nine)).assertTrue();
      runtime.deferDecode(finalPath());
      const finalizeSeven = bridge('finalize', seven);
      await flushMicrotasks();
      expect(runtime.pendingDecodeCount() === 1).assertTrue();
      const blockedNine = await bridge('finalize', nine);
      expect(blockedNine.status === 'error').assertTrue();
      expect(blockedNine.code === 'finalize_in_progress').assertTrue();
      expect(runtime.pendingDecodeCount() === 1 && runtime.renameCount === 1).assertTrue();
      runtime.resolveDeferredDecode(true);
      expect((await finalizeSeven).status === 'ok').assertTrue();
      expect(runtime.promoted.includes(`${BYTES}:7`)).assertTrue();
      expect((await bridge('finalize', nine)).status === 'ok').assertTrue();
      expect(runtime.promoted.includes(`${BYTES}:9`)).assertTrue();
      runtime.seed(stagePath('8'), 6);
      const eight = descriptor('8');
      eight.outcome = 'successful_bytes';
      const staleEight = await bridge('finalize', eight);
      expect(staleEight.status === 'error' && staleEight.code === 'stale_generation').assertTrue();
    });

    it('evicts only proven avatar-managed bytes and fails closed if budget unlink fails', 0, async () => {
      const runtime = install();
      for (let index = 0; index < 13; index += 1) {
        const key = index === 0 ? OTHER_BYTES : `${index.toString(16)}`.repeat(64).substring(0, 64);
        runtime.seed(finalPath(key), 5 * 1024 * 1024);
      }
      runtime.seed(stagePath('16'), 5 * 1024 * 1024);
      runtime.failUnlink.add(finalPath(OTHER_BYTES));
      const finalize = descriptor('16');
      finalize.outcome = 'successful_bytes';
      const result = await bridge('finalize', finalize);
      expect(result.status === 'error' && result.code === 'budget_eviction_failed').assertTrue();
      expect(runtime.files.has(finalPath(OTHER_BYTES))).assertTrue();
      runtime.failUnlink.delete(finalPath(OTHER_BYTES));
      expect((await bridge('finalize', finalize)).status === 'ok').assertTrue();
    });

    it('invalidates evicted identity authority so a higher generation can recover', 0, async () => {
      const runtime = install();
      runtime.seed(stagePath('1', OTHER_BYTES), 5 * 1024 * 1024, true);
      const first = descriptor('1', 'fetch', OTHER_BYTES);
      first.outcome = 'successful_bytes';
      expect((await bridge('finalize', first)).status === 'ok').assertTrue();

      for (let index = 1; index <= 12; index += 1) {
        const key = `${index}`.repeat(64).substring(0, 64);
        runtime.seed(finalPath(key), 5 * 1024 * 1024, true);
        expect((await fetchAvatar(model('2', 'render_fresh', key))) === finalPath(key)).assertTrue();
        expect((await awaitResult('2', key)).status === 'rendered').assertTrue();
      }

      runtime.seed(stagePath('70'), 5 * 1024 * 1024, true);
      const pressure = descriptor('70');
      pressure.outcome = 'successful_bytes';
      expect((await bridge('finalize', pressure)).status === 'ok').assertTrue();
      expect(runtime.files.has(finalPath(OTHER_BYTES))).assertFalse();
      expect(runtime.hasDecoded(OTHER_BYTES, '1')).assertFalse();

      runtime.seed(stagePath('2', OTHER_BYTES), 4, true);
      const recovered = descriptor('2', 'fetch', OTHER_BYTES);
      recovered.outcome = 'successful_bytes';
      expect((await bridge('finalize', recovered)).status === 'ok').assertTrue();
      expect(runtime.files.has(finalPath(OTHER_BYTES))).assertTrue();
      expect(runtime.hasDecoded(OTHER_BYTES, '2')).assertTrue();
    });

    it('invalidates authority before unlink post-stat failure and admits a lower generation', 0, async () => {
      const runtime = install();
      runtime.seed(stagePath('5', OTHER_BYTES), 5 * 1024 * 1024, true);
      const first = descriptor('5', 'fetch', OTHER_BYTES);
      first.outcome = 'successful_bytes';
      expect((await bridge('finalize', first)).status === 'ok').assertTrue();

      for (let index = 1; index <= 12; index += 1) {
        const key = `${index.toString(16)}`.repeat(64).substring(0, 64);
        runtime.seed(finalPath(key), 5 * 1024 * 1024, true);
        expect((await fetchAvatar(model('6', 'render_fresh', key))) === finalPath(key)).assertTrue();
        expect((await awaitResult('6', key)).status === 'rendered').assertTrue();
      }

      runtime.failExistsAfterUnlink.add(finalPath(OTHER_BYTES));
      runtime.seed(stagePath('70'), 5 * 1024 * 1024, true);
      const pressure = descriptor('70');
      pressure.outcome = 'successful_bytes';
      const failedPressure = await bridge('finalize', pressure);
      expect(failedPressure.status === 'error' && failedPressure.code === 'budget_eviction_failed').assertTrue();
      expect(runtime.files.has(finalPath(OTHER_BYTES))).assertFalse();
      expect(runtime.hasDecoded(OTHER_BYTES, '5')).assertFalse();
      expect(runtime.files.has(quarantinePath(OTHER_BYTES))).assertTrue();
      const inspected = await bridge('inspect', descriptor('4', 'fetch', OTHER_BYTES));
      expect(inspected.bytesAvailable === false && inspected.bytesDecodable === false).assertTrue();
      expect(runtime.files.has(quarantinePath(OTHER_BYTES))).assertFalse();

      runtime.seed(stagePath('4', OTHER_BYTES), 4, true);
      const recovered = descriptor('4', 'fetch', OTHER_BYTES);
      recovered.outcome = 'successful_bytes';
      expect((await bridge('finalize', recovered)).status === 'ok').assertTrue();
      expect((await bridge('ackFinalize', recovered)).status === 'ok').assertTrue();
      expect(runtime.files.has(finalPath(OTHER_BYTES))).assertTrue();
      expect(runtime.hasDecoded(OTHER_BYTES, '4')).assertTrue();
      expect(runtime.files.has(quarantinePath(OTHER_BYTES))).assertFalse();
    });

    it('keeps bytes and generation authority coherent when decoded eviction retirement fails', 0, async () => {
      const runtime = install();
      runtime.seed(stagePath('5', OTHER_BYTES), 5 * 1024 * 1024, true);
      const first = descriptor('5', 'fetch', OTHER_BYTES);
      first.outcome = 'successful_bytes';
      expect((await bridge('finalize', first)).status === 'ok').assertTrue();

      for (let index = 1; index <= 12; index += 1) {
        const key = `${index.toString(16)}`.repeat(64).substring(0, 64);
        runtime.seed(finalPath(key), 5 * 1024 * 1024, true);
        expect((await fetchAvatar(model('6', 'render_fresh', key))) === finalPath(key)).assertTrue();
        expect((await awaitResult('6', key)).status === 'rendered').assertTrue();
      }
      runtime.failDecodedRetire = true;
      runtime.seed(stagePath('71'), 5 * 1024 * 1024, true);
      const pressure = descriptor('71');
      pressure.outcome = 'successful_bytes';
      const failedPressure = await bridge('finalize', pressure);
      expect(failedPressure.status === 'error' && failedPressure.code === 'budget_eviction_failed').assertTrue();
      expect(runtime.files.has(finalPath(OTHER_BYTES))).assertTrue();
      expect(runtime.hasDecoded(OTHER_BYTES, '5')).assertTrue();
      expect(runtime.files.has(quarantinePath(OTHER_BYTES))).assertFalse();

      runtime.failDecodedRetire = false;
      runtime.seed(stagePath('4', OTHER_BYTES), 4, true);
      const lower = descriptor('4', 'fetch', OTHER_BYTES);
      lower.outcome = 'successful_bytes';
      const stale = await bridge('finalize', lower);
      expect(stale.status === 'error' && stale.code === 'stale_generation').assertTrue();
    });

    it('requires durable quarantine creation before decoded eviction retirement', 0, async () => {
      const runtime = install();
      runtime.seed(stagePath('5', OTHER_BYTES), 5 * 1024 * 1024, true);
      const first = descriptor('5', 'fetch', OTHER_BYTES);
      first.outcome = 'successful_bytes';
      expect((await bridge('finalize', first)).status === 'ok').assertTrue();
      expect((await bridge('ackFinalize', first)).status === 'ok').assertTrue();

      for (let index = 1; index <= 12; index += 1) {
        const key = `${index.toString(16)}`.repeat(64).substring(0, 64);
        runtime.seed(finalPath(key), 5 * 1024 * 1024, true);
        expect((await fetchAvatar(model('6', 'render_fresh', key))) === finalPath(key)).assertTrue();
        expect((await awaitResult('6', key)).status === 'rendered').assertTrue();
      }

      runtime.failWrite.add(quarantinePath(OTHER_BYTES));
      runtime.seed(stagePath('76'), 5 * 1024 * 1024, true);
      const pressure = descriptor('76');
      pressure.outcome = 'successful_bytes';
      const failedPressure = await bridge('finalize', pressure);
      expect(failedPressure.status === 'error' && failedPressure.code === 'budget_eviction_failed').assertTrue();
      expect(runtime.files.has(finalPath(OTHER_BYTES))).assertTrue();
      expect(runtime.hasDecoded(OTHER_BYTES, '5')).assertTrue();
      expect(runtime.files.has(quarantinePath(OTHER_BYTES))).assertFalse();
    });

    it('quarantines residual bytes after unlink failure until an authorized replacement commits', 0, async () => {
      const runtime = install();
      runtime.seed(stagePath('5', OTHER_BYTES), 5 * 1024 * 1024, true);
      const first = descriptor('5', 'fetch', OTHER_BYTES);
      first.outcome = 'successful_bytes';
      expect((await bridge('finalize', first)).status === 'ok').assertTrue();
      expect((await bridge('ackFinalize', first)).status === 'ok').assertTrue();

      for (let index = 1; index <= 12; index += 1) {
        const key = `${index.toString(16)}`.repeat(64).substring(0, 64);
        runtime.seed(finalPath(key), 5 * 1024 * 1024, true);
        expect((await fetchAvatar(model('6', 'render_fresh', key))) === finalPath(key)).assertTrue();
        expect((await awaitResult('6', key)).status === 'rendered').assertTrue();
      }

      runtime.failUnlink.add(finalPath(OTHER_BYTES));
      runtime.seed(stagePath('72'), 5 * 1024 * 1024, true);
      const pressure = descriptor('72');
      pressure.outcome = 'successful_bytes';
      expect((await bridge('finalize', pressure)).code === 'budget_eviction_failed').assertTrue();
      expect(runtime.files.has(finalPath(OTHER_BYTES))).assertTrue();
      expect(runtime.files.has(quarantinePath(OTHER_BYTES))).assertTrue();
      expect(runtime.hasDecoded(OTHER_BYTES, '5')).assertFalse();

      KRAvatarCacheManager.installRuntimeForTesting(runtime);
      const decodeCount = runtime.decodeRequests.length;
      expect((await fetchAvatar(model('4', 'render_fresh', OTHER_BYTES))) === null).assertTrue();
      expect((await awaitResult('4', OTHER_BYTES)).status === 'temporary_transport').assertTrue();
      expect(runtime.decodeRequests.length === decodeCount).assertTrue();
      expect(runtime.promoted.includes(`${OTHER_BYTES}:4`)).assertFalse();
      expect(runtime.files.has(quarantinePath(OTHER_BYTES))).assertTrue();

      const requestCount = runtime.requests.length;
      expect((await fetchAvatar(model('4', 'render_stale_revalidate', OTHER_BYTES))) === null).assertTrue();
      expect((await awaitResult('4', OTHER_BYTES)).status === 'temporary_transport').assertTrue();
      expect(runtime.requests.length === requestCount).assertTrue();
      expect(runtime.files.has(quarantinePath(OTHER_BYTES))).assertTrue();

      runtime.failExists.add(finalPath(OTHER_BYTES));
      expect((await fetchAvatar(model('3', 'render_fresh', OTHER_BYTES))) === null).assertTrue();
      expect((await awaitResult('3', OTHER_BYTES)).status === 'temporary_transport').assertTrue();
      expect(runtime.decodeRequests.length === decodeCount).assertTrue();
      expect(runtime.files.has(quarantinePath(OTHER_BYTES))).assertTrue();
      runtime.failExists.delete(finalPath(OTHER_BYTES));

      runtime.failUnlink.delete(finalPath(OTHER_BYTES));
      runtime.queue(200, 4);
      expect((await fetchAvatar(model('4', 'fetch', OTHER_BYTES))) === stagePath('4', OTHER_BYTES)).assertTrue();
      expect((await awaitResult('4', OTHER_BYTES)).status === 'success').assertTrue();
      const replacement = descriptor('4', 'fetch', OTHER_BYTES);
      replacement.outcome = 'successful_bytes';
      expect((await bridgeWithoutAck('finalize', replacement)).status === 'ok').assertTrue();
      expect(runtime.files.has(quarantinePath(OTHER_BYTES))).assertTrue();
      runtime.failUnlink.add(quarantinePath(OTHER_BYTES));
      expect((await bridge('ackFinalize', replacement)).code === 'finalize_commit_failed').assertTrue();
      expect(runtime.files.has(quarantinePath(OTHER_BYTES))).assertTrue();
      expect((await fetchAvatar(model('4', 'render_fresh', OTHER_BYTES))) === null).assertTrue();
      expect((await awaitResult('4', OTHER_BYTES)).status === 'temporary_transport').assertTrue();
      runtime.failUnlink.delete(quarantinePath(OTHER_BYTES));
      expect((await bridge('ackFinalize', replacement)).status === 'ok').assertTrue();
      expect(runtime.files.has(quarantinePath(OTHER_BYTES))).assertFalse();
      expect((await fetchAvatar(model('4', 'render_fresh', OTHER_BYTES))) === finalPath(OTHER_BYTES)).assertTrue();
      expect((await awaitResult('4', OTHER_BYTES)).status === 'rendered').assertTrue();
    });

    it('keeps late decode callbacks fenced while residual bytes are quarantined', 0, async () => {
      const runtime = install();
      for (let index = 1; index <= 12; index += 1) {
        const key = `${index.toString(16)}`.repeat(64).substring(0, 64);
        runtime.seed(finalPath(key), 5 * 1024 * 1024, true);
        expect((await fetchAvatar(model('73', 'render_fresh', key))) === finalPath(key)).assertTrue();
        expect((await awaitResult('73', key)).status === 'rendered').assertTrue();
      }

      runtime.seed(finalPath(OTHER_BYTES), 5 * 1024 * 1024, true);
      runtime.deferDecode(finalPath(OTHER_BYTES));
      let renderedPath: KRAny = finalPath(OTHER_BYTES);
      void fetchAvatar(model('74', 'render_fresh', OTHER_BYTES)).then((value: KRAny): void => {
        renderedPath = value;
      });
      await Promise.resolve();
      expect(runtime.pendingDecodeCount() === 1).assertTrue();

      runtime.failUnlink.add(finalPath(OTHER_BYTES));
      runtime.seed(stagePath('75'), 5 * 1024 * 1024, true);
      const pressure = descriptor('75');
      pressure.outcome = 'successful_bytes';
      expect((await bridge('finalize', pressure)).code === 'budget_eviction_failed').assertTrue();
      expect(runtime.files.has(quarantinePath(OTHER_BYTES))).assertTrue();

      runtime.resolveDeferredDecode(true);
      await Promise.resolve();
      await Promise.resolve();
      expect(renderedPath === null).assertTrue();
      expect(runtime.promoted.includes(`${OTHER_BYTES}:74`)).assertFalse();
      const decodeCount = runtime.decodeRequests.length;
      expect((await fetchAvatar(model('73', 'render_fresh', OTHER_BYTES))) === null).assertTrue();
      expect(runtime.decodeRequests.length === decodeCount).assertTrue();
    });

    it('retires an evicted stored terminal instead of replaying a removed render path', 0, async () => {
      const runtime = install();
      runtime.seed(finalPath(OTHER_BYTES), 5 * 1024 * 1024, true);
      expect((await fetchAvatar(model('80', 'render_fresh', OTHER_BYTES))) === finalPath(OTHER_BYTES)).assertTrue();

      for (let index = 1; index <= 12; index += 1) {
        const key = `${index.toString(16)}`.repeat(64).substring(0, 64);
        runtime.seed(finalPath(key), 5 * 1024 * 1024, true);
        expect((await fetchAvatar(model('81', 'render_fresh', key))) === finalPath(key)).assertTrue();
        expect((await awaitResult('81', key)).status === 'rendered').assertTrue();
      }

      runtime.seed(stagePath('82'), 5 * 1024 * 1024, true);
      const pressure = descriptor('82');
      pressure.outcome = 'successful_bytes';
      expect((await bridge('finalize', pressure)).status === 'ok').assertTrue();
      expect(runtime.files.has(finalPath(OTHER_BYTES))).assertFalse();
      const replay = await awaitResult('80', OTHER_BYTES);
      expect(replay.status === 'retired').assertTrue();
    });

    it('settles and fences a suspended decode before budget eviction unlinks its identity', 0, async () => {
      const runtime = install();
      for (let index = 1; index <= 12; index += 1) {
        const key = `${index.toString(16)}`.repeat(64).substring(0, 64);
        runtime.seed(finalPath(key), 5 * 1024 * 1024, true);
        expect((await fetchAvatar(model('83', 'render_fresh', key))) === finalPath(key)).assertTrue();
        expect((await awaitResult('83', key)).status === 'rendered').assertTrue();
      }

      runtime.seed(finalPath(OTHER_BYTES), 5 * 1024 * 1024, true);
      runtime.deferDecode(finalPath(OTHER_BYTES));
      let renderSettled = false;
      let renderedPath: KRAny = finalPath(OTHER_BYTES);
      void fetchAvatar(model('84', 'render_fresh', OTHER_BYTES)).then((value: KRAny): void => {
        renderSettled = true;
        renderedPath = value;
      });
      let protocolSettled = false;
      let protocolStatus = '';
      void awaitResult('84', OTHER_BYTES).then((payload: KRAvatarCacheBridgePayload): void => {
        protocolSettled = true;
        protocolStatus = payload.status;
      });
      await Promise.resolve();
      expect(runtime.pendingDecodeCount() === 1).assertTrue();

      runtime.seed(stagePath('85'), 5 * 1024 * 1024, true);
      const pressure = descriptor('85');
      pressure.outcome = 'successful_bytes';
      expect((await bridge('finalize', pressure)).status === 'ok').assertTrue();
      await Promise.resolve();
      expect(renderSettled && renderedPath === null && protocolSettled && protocolStatus === 'retired').assertTrue();
      expect(runtime.files.has(finalPath(OTHER_BYTES))).assertFalse();

      runtime.resolveDeferredDecode(true);
      await Promise.resolve();
      await Promise.resolve();
      expect(runtime.promoted.includes(`${OTHER_BYTES}:84`)).assertFalse();
      expect(renderSettled && renderedPath === null && protocolSettled && protocolStatus === 'retired').assertTrue();
    });

    it('invalidates a completed pre-eviction stage and requires a new fetch receipt before replacement', 0, async () => {
      const runtime = install();
      runtime.seed(finalPath(OTHER_BYTES), 5 * 1024 * 1024, true);

      runtime.queue(200, 6);
      expect((await fetchAvatar(model('6', 'fetch', OTHER_BYTES))) === stagePath('6', OTHER_BYTES)).assertTrue();
      expect((await awaitResult('6', OTHER_BYTES)).status === 'success').assertTrue();
      seedBudgetFillers(runtime, 12);

      runtime.seed(stagePath('90'), 5 * 1024 * 1024, true);
      const pressure = descriptor('90');
      pressure.outcome = 'successful_bytes';
      expect((await bridge('finalize', pressure)).status === 'ok').assertTrue();
      expect(runtime.files.has(finalPath(OTHER_BYTES))).assertFalse();
      expect(runtime.files.has(stagePath('6', OTHER_BYTES))).assertFalse();
      expect(runtime.files.has(quarantinePath(OTHER_BYTES))).assertFalse();

      const late = descriptor('6', 'fetch', OTHER_BYTES);
      late.outcome = 'successful_bytes';
      const rejected = await bridgeWithoutAck('finalize', late, false, false);
      expect(rejected.status === 'error' && rejected.code === 'successful_bytes_without_terminal').assertTrue();
      expect(runtime.files.has(finalPath(OTHER_BYTES))).assertFalse();

      runtime.queue(200, 7);
      expect((await fetchAvatar(model('6', 'fetch', OTHER_BYTES))) === stagePath('6', OTHER_BYTES)).assertTrue();
      expect((await awaitResult('6', OTHER_BYTES)).status === 'success').assertTrue();
      expect((await bridge('finalize', late)).status === 'ok').assertTrue();
      expect(runtime.files.has(finalPath(OTHER_BYTES))).assertTrue();
    });

    it('keeps a budget identity enumerable until failed completed-stage cleanup can retry', 0, async () => {
      const runtime = install();
      runtime.seed(finalPath(OTHER_BYTES), 5 * 1024 * 1024, true);

      runtime.queue(200, 6);
      expect((await fetchAvatar(model('581', 'fetch', OTHER_BYTES))) === stagePath('581', OTHER_BYTES)).assertTrue();
      expect((await awaitResult('581', OTHER_BYTES)).status === 'success').assertTrue();
      seedBudgetFillers(runtime, 12);
      runtime.failUnlink.add(stagePath('581', OTHER_BYTES));

      runtime.seed(stagePath('582'), 5 * 1024 * 1024, true);
      const pressure = descriptor('582');
      pressure.outcome = 'successful_bytes';
      const failed = await bridge('finalize', pressure);
      expect(failed.status === 'error' && failed.code === 'budget_eviction_failed').assertTrue();
      expect(failed.managedBytes === `${13 * 5 * 1024 * 1024}`).assertTrue();
      expect(runtime.files.has(finalPath(OTHER_BYTES))).assertTrue();
      expect(runtime.files.has(stagePath('581', OTHER_BYTES))).assertTrue();
      expect(runtime.files.has(quarantinePath(OTHER_BYTES))).assertTrue();

      runtime.failUnlink.delete(stagePath('581', OTHER_BYTES));
      const recovered = await bridge('finalize', pressure);
      expect(recovered.status === 'ok' && recovered.managedBytes === `${12 * 5 * 1024 * 1024}`).assertTrue();
      expect(runtime.files.has(finalPath(OTHER_BYTES))).assertFalse();
      expect(runtime.files.has(stagePath('581', OTHER_BYTES))).assertFalse();
      expect(runtime.files.has(quarantinePath(OTHER_BYTES))).assertFalse();
    });

    it('keeps quarantine through eviction unlink failure until a post-quarantine fetch is acknowledged', 0, async () => {
      const runtime = install();
      runtime.seed(finalPath(OTHER_BYTES), 5 * 1024 * 1024, true);

      runtime.queue(200, 6);
      expect((await fetchAvatar(model('6', 'fetch', OTHER_BYTES))) === stagePath('6', OTHER_BYTES)).assertTrue();
      expect((await awaitResult('6', OTHER_BYTES)).status === 'success').assertTrue();
      seedBudgetFillers(runtime, 12);
      runtime.failUnlink.add(finalPath(OTHER_BYTES));

      runtime.seed(stagePath('91'), 5 * 1024 * 1024, true);
      const pressure = descriptor('91');
      pressure.outcome = 'successful_bytes';
      const pressureResult = await bridge('finalize', pressure);
      expect(pressureResult.status === 'error' && pressureResult.code === 'budget_eviction_failed').assertTrue();
      expect(runtime.files.has(finalPath(OTHER_BYTES))).assertTrue();
      expect(runtime.files.has(stagePath('6', OTHER_BYTES))).assertFalse();
      expect(runtime.files.has(quarantinePath(OTHER_BYTES))).assertTrue();

      const late = descriptor('6', 'fetch', OTHER_BYTES);
      late.outcome = 'successful_bytes';
      const rejected = await bridgeWithoutAck('finalize', late, false, false);
      expect(rejected.status === 'error' && rejected.code === 'successful_bytes_without_terminal').assertTrue();
      expect((await bridge('ackFinalize', late)).status === 'ok').assertTrue();
      expect(runtime.files.has(quarantinePath(OTHER_BYTES))).assertTrue();

      runtime.failUnlink.delete(finalPath(OTHER_BYTES));
      runtime.queue(200, 7);
      expect((await fetchAvatar(model('6', 'fetch', OTHER_BYTES))) === stagePath('6', OTHER_BYTES)).assertTrue();
      expect((await awaitResult('6', OTHER_BYTES)).status === 'success').assertTrue();
      expect((await bridge('finalize', late)).status === 'ok').assertTrue();
      expect(runtime.files.has(quarantinePath(OTHER_BYTES))).assertFalse();
      expect(runtime.stat(finalPath(OTHER_BYTES)).size === 7).assertTrue();
    });

    it('preserves a pre-existing quarantine when a repeated budget retirement cannot retire decoded state', 0,
      async () => {
      const runtime = install();
      runtime.seed(finalPath(OTHER_BYTES), 5 * 1024 * 1024, true);
      runtime.seedDecoded(OTHER_BYTES, '5');
      seedBudgetFillers(runtime, 12);
      runtime.failUnlink.add(finalPath(OTHER_BYTES));

      runtime.seed(stagePath('92'), 5 * 1024 * 1024, true);
      const firstPressure = descriptor('92');
      firstPressure.outcome = 'successful_bytes';
      const firstFailure = await bridge('finalize', firstPressure);
      expect(firstFailure.status === 'error' && firstFailure.code === 'budget_eviction_failed').assertTrue();
      expect(runtime.files.has(finalPath(OTHER_BYTES))).assertTrue();
      expect(runtime.files.has(quarantinePath(OTHER_BYTES))).assertTrue();
      expect(runtime.hasDecoded(OTHER_BYTES, '5')).assertFalse();

      runtime.failDecodedRetire = true;
      runtime.seed(stagePath('93'), 5 * 1024 * 1024, true);
      const retryPressure = descriptor('93');
      retryPressure.outcome = 'successful_bytes';
      const retryFailure = await bridge('finalize', retryPressure);
      expect(retryFailure.status === 'error' && retryFailure.code === 'budget_eviction_failed').assertTrue();
      expect(runtime.files.has(finalPath(OTHER_BYTES))).assertTrue();
      expect(runtime.files.has(quarantinePath(OTHER_BYTES))).assertTrue();

      KRAvatarCacheManager.installRuntimeForTesting(runtime);
      const decodeCount = runtime.decodeRequests.length;
      expect((await fetchAvatar(model('4', 'render_fresh', OTHER_BYTES))) === null).assertTrue();
      expect((await awaitResult('4', OTHER_BYTES)).status === 'temporary_transport').assertTrue();
      expect(runtime.decodeRequests.length === decodeCount).assertTrue();
      expect(runtime.files.has(quarantinePath(OTHER_BYTES))).assertTrue();
    });

    it('makes an old rollback a no-op against a new same-generation finalize transaction', 0, async () => {
      const runtime = install();
      runtime.queue(200, 4);
      expect((await fetchAvatar(model('400'))) === stagePath('400')).assertTrue();
      expect((await awaitResult('400')).status === 'success').assertTrue();
      const oldCommand = descriptor('400');
      oldCommand.outcome = 'successful_bytes';
      expect((await bridgeWithoutAck('finalize', oldCommand)).status === 'ok').assertTrue();
      expect((await bridge('retireOwner', descriptor('400'))).status === 'ok').assertTrue();

      runtime.queue(200, 7);
      expect((await fetchAvatar(model('400'))) === stagePath('400')).assertTrue();
      expect((await awaitResult('400')).status === 'success').assertTrue();
      const replacement = descriptor('400');
      replacement.outcome = 'successful_bytes';
      expect((await bridgeWithoutAck('finalize', replacement)).status === 'ok').assertTrue();
      expect(runtime.stat(finalPath()).size === 7).assertTrue();

      expect((await bridge('rollback', oldCommand)).status === 'ok').assertTrue();
      expect(runtime.files.has(finalPath())).assertTrue();
      expect(runtime.stat(finalPath()).size === 7).assertTrue();
      expect(runtime.hasDecoded(BYTES, '400')).assertTrue();
      expect((await bridge('ackFinalize', replacement)).status === 'ok').assertTrue();
      expect(runtime.stat(finalPath()).size === 7).assertTrue();
    });

    it('makes an old ack a no-op against a new same-generation quarantine recovery', 0, async () => {
      const runtime = install();
      runtime.queue(200, 4);
      expect((await fetchAvatar(model('401'))) === stagePath('401')).assertTrue();
      expect((await awaitResult('401')).status === 'success').assertTrue();
      const oldCommand = descriptor('401');
      oldCommand.outcome = 'successful_bytes';
      expect((await bridgeWithoutAck('finalize', oldCommand)).status === 'ok').assertTrue();
      expect((await bridge('retireOwner', descriptor('401'))).status === 'ok').assertTrue();

      runtime.seed(finalPath(), 4, true);
      runtime.seed(quarantinePath(), 1, false);
      runtime.queue(200, 8);
      expect((await fetchAvatar(model('401'))) === stagePath('401')).assertTrue();
      expect((await awaitResult('401')).status === 'success').assertTrue();
      const replacement = descriptor('401');
      replacement.outcome = 'successful_bytes';
      expect((await bridgeWithoutAck('finalize', replacement)).status === 'ok').assertTrue();
      expect(runtime.files.has(quarantinePath())).assertTrue();

      const staleFinalize = await bridgeWithoutAck('finalize', oldCommand, false, false);
      expect(staleFinalize.status === 'error' && staleFinalize.code === 'finalize_in_progress').assertTrue();
      expect((await bridge('ackFinalize', oldCommand)).status === 'ok').assertTrue();
      expect(runtime.files.has(quarantinePath())).assertTrue();
      expect(runtime.stat(finalPath()).size === 8).assertTrue();
      expect((await bridge('ackFinalize', replacement)).status === 'ok').assertTrue();
      expect(runtime.files.has(quarantinePath())).assertFalse();
      expect(runtime.stat(finalPath()).size === 8).assertTrue();
    });

    it('preserves stage quarantine on owner subject and global cleanup failure', 0, async () => {
      const actions = ['retireOwner', 'retireSubject', 'clearAll'];
      for (let index = 0; index < actions.length; index += 1) {
        const runtime = install();
        const generation = `${300 + index}`;
        runtime.queue(200, 6);
        expect((await fetchAvatar(model(generation))) === stagePath(generation)).assertTrue();
        expect((await awaitResult(generation)).status === 'success').assertTrue();
        runtime.failUnlink.add(stagePath(generation));

        let retired = false;
        if (actions[index] === 'clearAll') {
          retired = KRAvatarCacheManager.clearAll(CONTEXT);
        } else {
          retired = (await bridge(actions[index], descriptor(generation))).status === 'ok';
        }
        expect(retired).assertFalse();
        expect(runtime.files.has(stagePath(generation))).assertTrue();
        expect(runtime.files.has(quarantinePath())).assertTrue();

        const late = descriptor(generation);
        late.outcome = 'successful_bytes';
        const rejected = await bridgeWithoutAck('finalize', late, false, false);
        expect(rejected.status === 'error' && rejected.code === 'successful_bytes_without_terminal').assertTrue();
        expect((await bridge('ackFinalize', late)).status === 'ok').assertTrue();
        expect(runtime.files.has(quarantinePath())).assertTrue();

        runtime.failUnlink.delete(stagePath(generation));
        runtime.queue(200, 7);
        expect((await fetchAvatar(model(generation))) === stagePath(generation)).assertTrue();
        expect((await awaitResult(generation)).status === 'success').assertTrue();
        expect((await bridge('finalize', late)).status === 'ok').assertTrue();
        expect(runtime.files.has(quarantinePath())).assertFalse();
      }
    });

    it('rejects an old same-generation finalize before replacement reservation', 0, async () => {
      const runtime = install();
      runtime.queue(200, 4);
      expect((await fetchAvatar(model('410'))) === stagePath('410')).assertTrue();
      expect((await awaitResult('410')).status === 'success').assertTrue();
      const oldFinalize = descriptor('410');
      oldFinalize.outcome = 'successful_bytes';
      assignFinalizeAttempt(oldFinalize);

      expect((await bridge('retireOwner', descriptor('410'))).status === 'ok').assertTrue();
      runtime.queue(200, 7);
      expect((await fetchAvatar(model('410'))) === stagePath('410')).assertTrue();
      expect((await awaitResult('410')).status === 'success').assertTrue();

      const stale = await bridgeWithoutAck('finalize', oldFinalize, false, false);
      expect(stale.status === 'error' && stale.code === 'successful_bytes_without_terminal').assertTrue();
      expect(runtime.files.has(stagePath('410'))).assertTrue();
      expect(runtime.stat(stagePath('410')).size === 7).assertTrue();
      expect(runtime.files.has(finalPath())).assertFalse();

      const replacement = descriptor('410');
      replacement.outcome = 'successful_bytes';
      expect((await bridge('finalize', replacement)).status === 'ok').assertTrue();
      expect(runtime.stat(finalPath()).size === 7).assertTrue();
    });

    it('makes an old attempt-only rollback a no-op before replacement reservation', 0, async () => {
      const runtime = install();
      runtime.queue(200, 4);
      expect((await fetchAvatar(model('411'))) === stagePath('411')).assertTrue();
      expect((await awaitResult('411')).status === 'success').assertTrue();
      const oldRollback = descriptor('411');
      assignFinalizeAttempt(oldRollback);

      expect((await bridge('retireOwner', descriptor('411'))).status === 'ok').assertTrue();
      runtime.queue(200, 8);
      expect((await fetchAvatar(model('411'))) === stagePath('411')).assertTrue();
      expect((await awaitResult('411')).status === 'success').assertTrue();

      expect((await bridge('rollback', oldRollback)).status === 'ok').assertTrue();
      expect(runtime.files.has(stagePath('411'))).assertTrue();
      expect(runtime.stat(stagePath('411')).size === 8).assertTrue();
      expect(runtime.hasDecoded(BYTES, '411')).assertTrue();

      const replacement = descriptor('411');
      replacement.outcome = 'successful_bytes';
      expect((await bridge('finalize', replacement)).status === 'ok').assertTrue();
      expect(runtime.stat(finalPath()).size === 8).assertTrue();
    });

    it('quarantines a first candidate when rollback cannot unlink its final', 0, async () => {
      const runtime = install();
      runtime.queue(200, 6);
      expect((await fetchAvatar(model('412'))) === stagePath('412')).assertTrue();
      expect((await awaitResult('412')).status === 'success').assertTrue();
      const candidate = descriptor('412');
      candidate.outcome = 'successful_bytes';
      expect((await bridgeWithoutAck('finalize', candidate)).status === 'ok').assertTrue();

      runtime.failUnlink.add(finalPath());
      const failed = await bridge('rollback', candidate);
      expect(failed.status === 'error' && failed.code === 'rollback_restore_failed').assertTrue();
      expect(runtime.files.has(finalPath())).assertTrue();
      expect(runtime.files.has(quarantinePath())).assertTrue();

      KRAvatarCacheManager.installRuntimeForTesting(runtime);
      const requestCount = runtime.requests.length;
      const decodeCount = runtime.decodeRequests.length;
      const promoteCount = runtime.promoted.length;
      expect((await fetchAvatar(model('1', 'render_fresh'))) === null).assertTrue();
      expect((await awaitResult('1')).status === 'temporary_transport').assertTrue();
      expect(runtime.requests.length === requestCount).assertTrue();
      expect(runtime.decodeRequests.length === decodeCount).assertTrue();
      expect(runtime.promoted.length === promoteCount).assertTrue();
      expect(runtime.files.has(quarantinePath())).assertTrue();

      runtime.failUnlink.delete(finalPath());
      expect((await bridge('retireOwner', descriptor('412'))).status === 'ok').assertTrue();
      expect(runtime.files.has(finalPath())).assertFalse();
      expect(runtime.files.has(quarantinePath())).assertFalse();
    });

    it('keeps scoped-retirement quarantine when listed final stat fails', 0, async () => {
      const runtime = install();
      runtime.seed(finalPath(), 6, true);
      runtime.failStat.add(finalPath());
      expect(KRAvatarCacheManager.clearAll(CONTEXT)).assertFalse();
      expect(runtime.files.has(finalPath())).assertTrue();
      expect(runtime.files.has(quarantinePath())).assertTrue();

      runtime.failStat.delete(finalPath());
      KRAvatarCacheManager.installRuntimeForTesting(runtime);
      const requestCount = runtime.requests.length;
      const decodeCount = runtime.decodeRequests.length;
      const promoteCount = runtime.promoted.length;
      expect((await fetchAvatar(model('1', 'render_fresh'))) === null).assertTrue();
      expect((await awaitResult('1')).status === 'temporary_transport').assertTrue();
      expect(runtime.requests.length === requestCount).assertTrue();
      expect(runtime.decodeRequests.length === decodeCount).assertTrue();
      expect(runtime.promoted.length === promoteCount).assertTrue();
      expect(runtime.files.has(quarantinePath())).assertTrue();

      expect(KRAvatarCacheManager.clearAll(CONTEXT)).assertTrue();
      expect(runtime.files.has(finalPath())).assertFalse();
      expect(runtime.files.has(quarantinePath())).assertFalse();
    });

    it('keeps scoped-retirement quarantine when decoded retirement fails', 0, async () => {
      const runtime = install();
      runtime.seed(finalPath(), 6, true);
      runtime.seedDecoded(BYTES, '414');
      runtime.failDecodedRetire = true;
      const retired = await bridge('retireSubject', descriptor('414'));
      expect(retired.status === 'error' && retired.code === 'retire_delete_failed').assertTrue();
      expect(runtime.files.has(finalPath())).assertTrue();
      expect(runtime.files.has(quarantinePath())).assertTrue();

      KRAvatarCacheManager.installRuntimeForTesting(runtime);
      const requestCount = runtime.requests.length;
      const decodeCount = runtime.decodeRequests.length;
      const promoteCount = runtime.promoted.length;
      expect((await fetchAvatar(model('1', 'render_fresh'))) === null).assertTrue();
      expect((await awaitResult('1')).status === 'temporary_transport').assertTrue();
      expect(runtime.requests.length === requestCount).assertTrue();
      expect(runtime.decodeRequests.length === decodeCount).assertTrue();
      expect(runtime.promoted.length === promoteCount).assertTrue();
      expect(runtime.files.has(quarantinePath())).assertTrue();

      runtime.failDecodedRetire = false;
      expect((await bridge('retireSubject', descriptor('414'))).status === 'ok').assertTrue();
      expect(runtime.files.has(finalPath())).assertFalse();
      expect(runtime.files.has(quarantinePath())).assertFalse();
    });

    it('keeps scoped-retirement quarantine when shared-acked cleanup fails', 0, async () => {
      const runtime = install();
      runtime.seed(finalPath(), 4, true);
      runtime.seedDecoded(BYTES, '415');
      runtime.queue(200, 9);
      expect((await fetchAvatar(model('416'))) === stagePath('416')).assertTrue();
      expect((await awaitResult('416')).status === 'success').assertTrue();
      const replacement = descriptor('416');
      replacement.outcome = 'successful_bytes';
      expect((await bridgeWithoutAck('finalize', replacement)).status === 'ok').assertTrue();
      expect(runtime.files.has(backupPath('416'))).assertTrue();

      runtime.failUnlink.add(backupPath('416'));
      expect((await bridge('ackFinalize', replacement)).code === 'finalize_commit_failed').assertTrue();
      expect(runtime.files.has(quarantinePath())).assertTrue();
      const retirementEventStart = runtime.events.length;
      const retired = await bridge('retireOwner', descriptor('416'));
      expect(retired.status === 'error').assertTrue();
      const retirementEvents = runtime.events.slice(retirementEventStart);
      const failedBackupCleanup = retirementEvents.indexOf(`unlink:${backupPath('416')}`);
      expect(failedBackupCleanup >= 0).assertTrue();
      expect(runtime.files.has(finalPath())).assertTrue();
      expect(runtime.files.has(quarantinePath())).assertTrue();

      KRAvatarCacheManager.installRuntimeForTesting(runtime);
      const requestCount = runtime.requests.length;
      const decodeCount = runtime.decodeRequests.length;
      const promoteCount = runtime.promoted.length;
      expect((await fetchAvatar(model('1', 'render_fresh'))) === null).assertTrue();
      expect((await awaitResult('1')).status === 'temporary_transport').assertTrue();
      expect(runtime.requests.length === requestCount).assertTrue();
      expect(runtime.decodeRequests.length === decodeCount).assertTrue();
      expect(runtime.promoted.length === promoteCount).assertTrue();
      expect(runtime.files.has(quarantinePath())).assertTrue();

      runtime.failUnlink.delete(backupPath('416'));
      expect((await bridge('retireOwner', descriptor('416'))).status === 'ok').assertTrue();
      expect(runtime.files.has(finalPath())).assertFalse();
      expect(runtime.files.has(quarantinePath())).assertFalse();
    });

    it('fences candidate installation when the durable transaction marker cannot be written', 0, async () => {
      const runtime = install();
      runtime.queue(200, 7);
      expect((await fetchAvatar(model('500'))) === stagePath('500')).assertTrue();
      expect((await awaitResult('500')).status === 'success').assertTrue();
      const candidate = descriptor('500');
      candidate.outcome = 'successful_bytes';
      runtime.failWrite.add(quarantinePath());

      const rejected = await bridgeWithoutAck('finalize', candidate, false, false);
      expect(rejected.status === 'error' && rejected.code === 'transaction_quarantine_failed').assertTrue();
      expect(runtime.renameCount === 0).assertTrue();
      expect(runtime.files.has(stagePath('500'))).assertTrue();
      expect(runtime.files.has(finalPath())).assertFalse();

      KRAvatarCacheManager.installRuntimeForTesting(runtime);
      const requestCount = runtime.requests.length;
      const promoteCount = runtime.promoted.length;
      expect((await fetchAvatar(model('1', 'render_fresh'))) === null).assertTrue();
      expect((await awaitResult('1')).status === 'temporary_transport').assertTrue();
      expect(runtime.requests.length === requestCount).assertTrue();
      expect(runtime.promoted.length === promoteCount).assertTrue();
      expect(runtime.files.has(finalPath())).assertFalse();

      runtime.failWrite.delete(quarantinePath());
      expect(await authorizeSeededSuccessfulBytes(candidate)).assertTrue();
      expect((await bridge('finalize', candidate)).status === 'ok').assertTrue();
      expect(runtime.files.has(finalPath())).assertTrue();
      expect(runtime.files.has(quarantinePath())).assertFalse();
    });

    it('preserves a pre-existing retirement marker when replacement fails before its first rename', 0, async () => {
      const runtime = install();
      runtime.seed(finalPath(), 4, true);
      runtime.seed(quarantinePath(), 1, false);
      runtime.queue(200, 7);
      expect((await fetchAvatar(model('501'))) === stagePath('501')).assertTrue();
      expect((await awaitResult('501')).status === 'success').assertTrue();
      const candidate = descriptor('501');
      candidate.outcome = 'successful_bytes';
      runtime.failRename = true;

      const rejected = await bridgeWithoutAck('finalize', candidate, false, false);
      expect(rejected.status === 'error' && rejected.code === 'promotion_failed').assertTrue();
      expect(runtime.files.has(finalPath())).assertTrue();
      expect(runtime.files.has(quarantinePath())).assertTrue();
      expect(runtime.files.has(stagePath('501'))).assertTrue();

      KRAvatarCacheManager.installRuntimeForTesting(runtime);
      const requestCount = runtime.requests.length;
      const decodeCount = runtime.decodeRequests.length;
      const promoteCount = runtime.promoted.length;
      expect((await fetchAvatar(model('1', 'render_fresh'))) === null).assertTrue();
      expect((await awaitResult('1')).status === 'temporary_transport').assertTrue();
      expect(runtime.requests.length === requestCount).assertTrue();
      expect(runtime.decodeRequests.length === decodeCount).assertTrue();
      expect(runtime.promoted.length === promoteCount).assertTrue();
      expect(runtime.files.has(quarantinePath())).assertTrue();

      runtime.failRename = false;
      expect(KRAvatarCacheManager.clearAll(CONTEXT)).assertTrue();
      expect(runtime.files.has(finalPath())).assertFalse();
      expect(runtime.files.has(quarantinePath())).assertFalse();
    });

    it('preserves a token-owned backup after candidate unlink failure and completes retirement on retry', 0,
      async () => {
        const runtime = install();
        runtime.seed(finalPath(), 4, true);
        runtime.seedDecoded(BYTES, '540');
        runtime.queue(200, 8);
        expect((await fetchAvatar(model('541'))) === stagePath('541')).assertTrue();
        expect((await awaitResult('541')).status === 'success').assertTrue();
        const replacement = descriptor('541');
        replacement.outcome = 'successful_bytes';
        expect((await bridgeWithoutAck('finalize', replacement)).status === 'ok').assertTrue();
        expect(runtime.files.has(backupPath('541'))).assertTrue();

        runtime.failUnlink.add(finalPath());
        const failed = await bridge('retireOwner', descriptor('541'));
        expect(failed.status === 'error').assertTrue();
        expect(runtime.files.has(finalPath())).assertTrue();
        expect(runtime.files.has(backupPath('541'))).assertTrue();
        expect(runtime.files.has(quarantinePath())).assertTrue();

        runtime.failUnlink.delete(finalPath());
        expect((await bridge('retireOwner', descriptor('541'))).status === 'ok').assertTrue();
        expect(runtime.files.has(finalPath())).assertFalse();
        expect(runtime.files.has(backupPath('541'))).assertFalse();
        expect(runtime.files.has(quarantinePath())).assertFalse();
      });

    it('preserves a token-owned backup after restore rename failure and completes retirement on retry', 0,
      async () => {
        const runtime = install();
        runtime.seed(finalPath(), 4, true);
        runtime.seedDecoded(BYTES, '550');
        runtime.queue(200, 9);
        expect((await fetchAvatar(model('551'))) === stagePath('551')).assertTrue();
        expect((await awaitResult('551')).status === 'success').assertTrue();
        const replacement = descriptor('551');
        replacement.outcome = 'successful_bytes';
        expect((await bridgeWithoutAck('finalize', replacement)).status === 'ok').assertTrue();
        expect(runtime.files.has(backupPath('551'))).assertTrue();

        runtime.failRename = true;
        const failed = await bridge('retireSubject', descriptor('551'));
        expect(failed.status === 'error').assertTrue();
        expect(runtime.files.has(finalPath())).assertFalse();
        expect(runtime.files.has(backupPath('551'))).assertTrue();
        expect(runtime.files.has(quarantinePath())).assertTrue();

        runtime.failRename = false;
        expect((await bridge('retireSubject', descriptor('551'))).status === 'ok').assertTrue();
        expect(runtime.files.has(finalPath())).assertFalse();
        expect(runtime.files.has(backupPath('551'))).assertFalse();
        expect(runtime.files.has(quarantinePath())).assertFalse();
      });

    it('aborts a shared-acked transaction after a same-bytes different-identity retirement removes decoded', 0,
      async () => {
        const runtime = install();
        runtime.seed(finalPath(), 4, true);
        runtime.seedDecoded(BYTES, '560');
        runtime.queue(200, 10);
        expect((await fetchAvatar(model('561'))) === stagePath('561')).assertTrue();
        expect((await awaitResult('561')).status === 'success').assertTrue();
        const replacement = descriptor('561');
        replacement.outcome = 'successful_bytes';
        expect((await bridgeWithoutAck('finalize', replacement)).status === 'ok').assertTrue();
        expect(runtime.hasDecoded(BYTES, '561', replacement.callerAuthority)).assertTrue();

        const otherOwner = descriptor('1');
        otherOwner.nativePartitionKey = OTHER_PARTITION;
        otherOwner.callerAuthority = callerAuthorityFor(otherOwner);
        const otherFinal = `${DIRECTORY}/${OTHER_PARTITION}.${SUBJECT}.${BYTES}.avatar`;
        runtime.seed(otherFinal, 3, true);
        expect((await bridge('retireOwner', otherOwner)).status === 'ok').assertTrue();
        expect(runtime.files.has(otherFinal)).assertFalse();
        expect(runtime.hasDecoded(BYTES, '561', replacement.callerAuthority)).assertFalse();

        const ack = await bridge('ackFinalize', replacement);
        expect(ack.status === 'error' && ack.code === 'finalize_commit_failed').assertTrue();
        expect(runtime.files.has(finalPath())).assertTrue();
        expect(runtime.files.has(backupPath('561'))).assertTrue();
        expect(runtime.files.has(quarantinePath())).assertTrue();

        expect((await bridge('retireOwner', descriptor('561'))).status === 'ok').assertTrue();
        expect(runtime.files.has(finalPath())).assertFalse();
        expect(runtime.files.has(backupPath('561'))).assertFalse();
        expect(runtime.files.has(quarantinePath())).assertFalse();
      });

    it('aborts a shared-acked transaction after native module teardown removes every decoded candidate', 0,
      async () => {
        const runtime = install();
        runtime.seed(finalPath(), 4, true);
        runtime.seedDecoded(BYTES, '570');
        runtime.queue(200, 11);
        expect((await fetchAvatar(model('571'))) === stagePath('571')).assertTrue();
        expect((await awaitResult('571')).status === 'success').assertTrue();
        const replacement = descriptor('571');
        replacement.outcome = 'successful_bytes';
        expect((await bridgeWithoutAck('finalize', replacement)).status === 'ok').assertTrue();
        expect(runtime.hasDecoded(BYTES, '571', replacement.callerAuthority)).assertTrue();

        runtime.destroyDecodedStore();
        expect(runtime.hasDecoded(BYTES, '571', replacement.callerAuthority)).assertFalse();
        const ack = await bridge('ackFinalize', replacement);
        expect(ack.status === 'error' && ack.code === 'finalize_commit_failed').assertTrue();
        expect(runtime.files.has(finalPath())).assertTrue();
        expect(runtime.files.has(backupPath('571'))).assertTrue();
        expect(runtime.files.has(quarantinePath())).assertTrue();

        expect((await bridge('retireSubject', descriptor('571'))).status === 'ok').assertTrue();
        expect(runtime.files.has(finalPath())).assertFalse();
        expect(runtime.files.has(backupPath('571'))).assertFalse();
        expect(runtime.files.has(quarantinePath())).assertFalse();
      });

    it('keeps a same-generation replacement stage and decoded candidate after old suspended decode resumes', 0,
      async () => {
        const runtime = install();
        const oldCaller = descriptor('502');
        const oldStage = stagePath('502', BYTES, oldCaller.callerAuthority);
        runtime.deferDecode(oldStage);
        runtime.queue(200, 6);
        const oldFetch = fetchAvatar(JSON.stringify(oldCaller));
        await flushMicrotasks();
        expect(runtime.pendingDecodeCount() === 1 && runtime.files.has(oldStage)).assertTrue();

        expect((await bridge('retireOwner', descriptor('502'))).status === 'ok').assertTrue();
        const replacement = descriptor('502');
        rotateCallerAuthority(replacement);
        const replacementStage = stagePath('502', BYTES, replacement.callerAuthority);
        runtime.queue(200, 8);
        expect((await fetchAvatar(JSON.stringify(replacement))) === replacementStage).assertTrue();
        expect((await invokeBridge('awaitResult', replacement)).status === 'success').assertTrue();
        expect(runtime.files.has(replacementStage)).assertTrue();
        expect(runtime.hasDecoded(BYTES, '502', replacement.callerAuthority)).assertTrue();

        runtime.resolveDeferredDecodeAt(0, true);
        await oldFetch;
        await flushMicrotasks();
        expect(runtime.files.has(replacementStage)).assertTrue();
        expect(runtime.hasDecoded(BYTES, '502', replacement.callerAuthority)).assertTrue();
        expect(runtime.hasDecoded(BYTES, '502', oldCaller.callerAuthority)).assertFalse();

        replacement.outcome = 'successful_bytes';
        expect((await bridge('finalize', replacement)).status === 'ok').assertTrue();
        expect(runtime.files.has(finalPath())).assertTrue();
        expect(runtime.files.has(quarantinePath())).assertFalse();
      });

    it('leases scoped quarantine across shared-acked commit before a listed final stat failure', 0, async () => {
      const runtime = install();
      runtime.seed(finalPath(), 4, true);
      runtime.seedDecoded(BYTES, '510');
      runtime.queue(200, 8);
      expect((await fetchAvatar(model('511'))) === stagePath('511')).assertTrue();
      expect((await awaitResult('511')).status === 'success').assertTrue();
      const replacement = descriptor('511');
      replacement.outcome = 'successful_bytes';
      expect((await bridgeWithoutAck('finalize', replacement)).status === 'ok').assertTrue();
      runtime.failUnlink.add(quarantinePath());
      expect((await bridge('ackFinalize', replacement)).code === 'finalize_commit_failed').assertTrue();
      expect(runtime.files.has(quarantinePath())).assertTrue();

      runtime.failUnlink.delete(quarantinePath());
      runtime.failStatCount.set(finalPath(), 1);
      const retired = await bridge('retireOwner', descriptor('511'));
      expect(retired.status === 'error' && retired.code === 'retire_scan_failed').assertTrue();
      expect(runtime.files.has(backupPath('511'))).assertFalse();
      expect(runtime.files.has(finalPath())).assertTrue();
      expect(runtime.files.has(quarantinePath())).assertTrue();

      KRAvatarCacheManager.installRuntimeForTesting(runtime);
      const requestCount = runtime.requests.length;
      const decodeCount = runtime.decodeRequests.length;
      const promoteCount = runtime.promoted.length;
      expect((await fetchAvatar(model('1', 'render_fresh'))) === null).assertTrue();
      expect((await awaitResult('1')).status === 'temporary_transport').assertTrue();
      expect(runtime.requests.length === requestCount).assertTrue();
      expect(runtime.decodeRequests.length === decodeCount).assertTrue();
      expect(runtime.promoted.length === promoteCount).assertTrue();

      expect((await bridge('retireOwner', descriptor('511'))).status === 'ok').assertTrue();
      expect(runtime.files.has(finalPath())).assertFalse();
      expect(runtime.files.has(quarantinePath())).assertFalse();
    });

    it('leases scoped quarantine across unacked restore before a listed final stat failure', 0, async () => {
      const runtime = install();
      runtime.seed(finalPath(), 4, true);
      runtime.queue(200, 9);
      expect((await fetchAvatar(model('520'))) === stagePath('520')).assertTrue();
      expect((await awaitResult('520')).status === 'success').assertTrue();
      const replacement = descriptor('520');
      replacement.outcome = 'successful_bytes';
      expect((await bridgeWithoutAck('finalize', replacement)).status === 'ok').assertTrue();
      runtime.failStatCount.set(finalPath(), 1);

      const retired = await bridge('retireSubject', descriptor('520'));
      expect(retired.status === 'error' && retired.code === 'retire_scan_failed').assertTrue();
      expect(runtime.files.has(backupPath('520'))).assertFalse();
      expect(runtime.files.has(finalPath())).assertTrue();
      expect(runtime.files.has(quarantinePath())).assertTrue();

      KRAvatarCacheManager.installRuntimeForTesting(runtime);
      const requestCount = runtime.requests.length;
      const decodeCount = runtime.decodeRequests.length;
      const promoteCount = runtime.promoted.length;
      expect((await fetchAvatar(model('1', 'render_fresh'))) === null).assertTrue();
      expect((await awaitResult('1')).status === 'temporary_transport').assertTrue();
      expect(runtime.requests.length === requestCount).assertTrue();
      expect(runtime.decodeRequests.length === decodeCount).assertTrue();
      expect(runtime.promoted.length === promoteCount).assertTrue();

      expect((await bridge('retireSubject', descriptor('520'))).status === 'ok').assertTrue();
      expect(runtime.files.has(finalPath())).assertFalse();
      expect(runtime.files.has(quarantinePath())).assertFalse();
    });

    it('prevents a late old waiter from borrowing a replacement terminal and operation receipt', 0, async () => {
      const runtime = install();
      const oldCaller = descriptor('530');
      runtime.queue(200, 6);
      expect((await fetchAvatar(JSON.stringify(oldCaller))) === stagePath('530')).assertTrue();
      expect((await bridge('retireOwner', descriptor('530'))).status === 'ok').assertTrue();

      const replacement = descriptor('530');
      rotateCallerAuthority(replacement);
      runtime.queue(200, 7);
      expect((await fetchAvatar(JSON.stringify(replacement))) === stagePath('530')).assertTrue();
      const requestCount = runtime.requests.length;
      const decodeCount = runtime.decodeRequests.length;
      const promoteCount = runtime.promoted.length;

      const oldTerminal = await invokeBridge('awaitResult', oldCaller);
      expect(oldTerminal.status === 'retired' && oldTerminal.operationReceipt === undefined).assertTrue();
      expect(runtime.requests.length === requestCount).assertTrue();
      expect(runtime.decodeRequests.length === decodeCount).assertTrue();
      expect(runtime.promoted.length === promoteCount).assertTrue();

      const replacementTerminal = await invokeBridge('awaitResult', replacement);
      expect(replacementTerminal.status === 'success' && replacementTerminal.operationReceipt !== undefined).assertTrue();
      oldCaller.outcome = 'successful_bytes';
      oldCaller.operationReceipt = replacementTerminal.operationReceipt ?? '';
      assignFinalizeAttempt(oldCaller);
      const borrowed = await invokeBridge('finalize', oldCaller);
      expect(borrowed.status === 'error' && borrowed.code === 'successful_bytes_without_terminal').assertTrue();
      expect(runtime.files.has(stagePath('530'))).assertTrue();
      expect(runtime.files.has(finalPath())).assertFalse();

      replacement.outcome = 'successful_bytes';
      replacement.operationReceipt = replacementTerminal.operationReceipt ?? '';
      expect((await bridge('finalize', replacement)).status === 'ok').assertTrue();
      expect(runtime.files.has(finalPath())).assertTrue();
      expect(runtime.files.has(quarantinePath())).assertFalse();
    });
  });
}
