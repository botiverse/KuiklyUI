import { BusinessError } from '@kit.BasicServicesKit';
import { image } from '@kit.ImageKit';
import { http } from '@kit.NetworkKit';
import fs from '@ohos.file.fs';
import render from 'libkuikly.so';

export interface KRAvatarFileStat {
  size: number;
  mtime: number;
}

export interface KRAvatarHttpResult {
  responseCode: number;
  body: ArrayBuffer | null;
  headers: Object;
  error: string;
}

/** Injectable owner boundary used by production and by executable Hypium state-machine tests. */
export interface KRAvatarCacheRuntime {
  now(): number;
  schedule(delayMillis: number, callback: () => void): void;
  exists(path: string): boolean;
  ensureDirectory(path: string): void;
  list(directory: string): Array<string>;
  stat(path: string): KRAvatarFileStat;
  write(path: string, body: ArrayBuffer): void;
  unlink(path: string): void;
  rename(from: string, to: string): void;
  isDecodable(path: string): Promise<boolean>;
  httpGet(url: string, headers: Record<string, string>): Promise<KRAvatarHttpResult>;
  /** Atomically retires matching native PixelMaps; false guarantees zero native state change. */
  retireDecoded(nativeBytesKey: string, commitGeneration?: string, callerAuthority?: string): boolean;
  promoteDecoded(nativeBytesKey: string, commitGeneration: string, callerAuthority: string): boolean;
  retainDecoded(
    nativeBytesKey: string,
    commitGeneration: string,
    callerAuthority: string,
    priorGeneration?: string,
    priorCallerAuthority?: string
  ): boolean;
  hasDecoded(nativeBytesKey: string, commitGeneration: string, callerAuthority: string): boolean;
}

export class KRAvatarSystemCacheRuntime implements KRAvatarCacheRuntime {
  now(): number {
    return Date.now();
  }

  schedule(delayMillis: number, callback: () => void): void {
    setTimeout(callback, delayMillis);
  }

  exists(path: string): boolean {
    try {
      fs.statSync(path);
      return true;
    } catch (error) {
      const candidate = error as BusinessError;
      // Only a verified missing path is absence. Permission/I/O/stat failures remain failures.
      if (candidate.code === 13900002) return false;
      throw new Error(`avatar stat failed: ${candidate.code ?? -1}`);
    }
  }

  ensureDirectory(path: string): void {
    if (!this.exists(path)) fs.mkdirSync(path, true);
  }

  list(directory: string): Array<string> {
    return fs.listFileSync(directory);
  }

  stat(path: string): KRAvatarFileStat {
    const value = fs.statSync(path);
    return { size: value.size, mtime: value.mtime };
  }

  write(path: string, body: ArrayBuffer): void {
    const file = fs.openSync(path, fs.OpenMode.CREATE | fs.OpenMode.TRUNC | fs.OpenMode.WRITE_ONLY);
    try {
      fs.writeSync(file.fd, body, { length: body.byteLength });
    } finally {
      fs.closeSync(file);
    }
  }

  unlink(path: string): void {
    fs.unlinkSync(path);
  }

  rename(from: string, to: string): void {
    // POSIX/Harmony rename is the accepted atomic replacement primitive. Do not pre-delete `to`.
    fs.renameSync(from, to);
  }

  async isDecodable(path: string): Promise<boolean> {
    let file: fs.File | null = null;
    let source: image.ImageSource | null = null;
    try {
      if (!this.exists(path)) return false;
      file = fs.openSync(path, fs.OpenMode.READ_ONLY);
      source = image.createImageSource(file.fd);
      const info = await source.getImageInfo();
      return info.size.width > 0 && info.size.height > 0;
    } catch (_error) {
      return false;
    } finally {
      if (source !== null) await source.release();
      if (file !== null) fs.closeSync(file);
    }
  }

  httpGet(url: string, headers: Record<string, string>): Promise<KRAvatarHttpResult> {
    return new Promise<KRAvatarHttpResult>((resolve: (value: KRAvatarHttpResult) => void): void => {
      const request = http.createHttp();
      const options: http.HttpRequestOptions = {
        method: http.RequestMethod.GET,
        header: headers,
        expectDataType: http.HttpDataType.ARRAY_BUFFER,
        connectTimeout: 15000,
        readTimeout: 30000
      };
      request.request(url, options, (error: BusinessError, response: http.HttpResponse): void => {
        const body = response?.result instanceof ArrayBuffer ? response.result : null;
        resolve({
          responseCode: response?.responseCode ?? 0,
          body: body,
          headers: response?.header ?? {},
          error: error ? `${error.code}:${error.message}` : ''
        });
        request.destroy();
      });
    });
  }

  retireDecoded(nativeBytesKey: string, commitGeneration?: string, callerAuthority?: string): boolean {
    if (commitGeneration === undefined) return render.avatarCacheRetireDecoded(nativeBytesKey);
    return render.avatarCacheRetireDecoded(nativeBytesKey, commitGeneration, callerAuthority ?? '');
  }

  promoteDecoded(nativeBytesKey: string, commitGeneration: string, callerAuthority: string): boolean {
    return render.avatarCachePromoteDecoded(nativeBytesKey, commitGeneration, callerAuthority);
  }

  retainDecoded(
    nativeBytesKey: string,
    commitGeneration: string,
    callerAuthority: string,
    priorGeneration?: string,
    priorCallerAuthority?: string
  ): boolean {
    return render.avatarCacheRetainDecoded(
      nativeBytesKey,
      commitGeneration,
      callerAuthority,
      priorGeneration ?? '',
      priorCallerAuthority ?? ''
    );
  }

  hasDecoded(nativeBytesKey: string, commitGeneration: string, callerAuthority: string): boolean {
    return render.avatarCacheHasDecoded(nativeBytesKey, commitGeneration, callerAuthority);
  }
}
