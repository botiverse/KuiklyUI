/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI;
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://github.com/Tencent-TDS/KuiklyUI/blob/main/LICENSE
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { KuiklyRenderCallback } from '../utils/KRDataTypes';
import { common } from '@kit.AbilityKit';
import { BusinessError, request } from '@kit.BasicServicesKit';

import fs from '@ohos.file.fs';
import util from '@ohos.util';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { KRRenderLog } from '../adapter/KRRenderLog';

export class  KRNetworkCacheUtil {
  // 保存正在进行的下载任务
  private static downloadTasks: Map<string, KuiklyRenderCallback[]> = new Map();
  /**
   * 通用下载或者本地缓存来返回url对应的本地磁盘文件路径
   * @param url
   * @param callback
   */
  static fetchFileByDownloadOrCache(context: common.UIAbilityContext, url: string, callback: KuiklyRenderCallback) {
    let cacheFilePath = ''
    try {
      cacheFilePath = KRNetworkCacheUtil.generateCacheFilePathByUrl(context, url)
    } catch (e) {
      // A failure to derive the cache path is a failed fetch for this url,
      // not a reason to let the exception escape the module call and take
      // the whole page down.
      const err = e as BusinessError;
      KRRenderLog.i('KRNetworkCacheUtil', `cache file path failed with error message: ${err.message}, error code: ${err.code}`)
      callback(null)
      return
    }
    if (cacheFilePath.length) {
      if (!(KRNetworkCacheUtil.downloadTasks.has(url))) {
        fs.access(cacheFilePath, (err, res) => {
          if (err) {//mhttps://developer.huawei.com/consumer/cn/doc/harmonyos-references-V2/js-apis-file-fs-0000001451843016-V2
            KRRenderLog.i('KRNetworkCacheUtil', `access failed with error message: ${err.message}, error code: ${err.code}`)
            // Nothing has been registered in downloadTasks on this path, so
            // handleLoadFailure would find no callbacks and the caller would
            // never hear back; finish the request directly.
            callback(null);
          } else {
            if (res) {
              callback(cacheFilePath);
              return ;
            }
            KRNetworkCacheUtil.downloadFile(context, cacheFilePath, url, callback)
          }
        });
      } else {
        KRNetworkCacheUtil.downloadFile(context, cacheFilePath, url, callback)
      }
    } else {
      KRRenderLog.i('KRNetworkCacheUtil', `cache file path is empty by url: ${url}`)
      callback(null)
    }
  }

  static downloadFile(context: common.UIAbilityContext, cacheFilePath: string, url: string, callback: KuiklyRenderCallback) {
    // 如果已经有一个下载任务在进行中，就将回调函数添加到任务的回调函数数组中
    if (KRNetworkCacheUtil.downloadTasks.has(url)) {
      KRNetworkCacheUtil.downloadTasks.get(url)!.push(callback);
    } else {
      // 创建一个新的下载任务，并将回调函数添加到任务的回调函数数组中
      KRNetworkCacheUtil.downloadTasks.set(url, [callback]);
      request.downloadFile(context, {
        url: url,
        filePath: cacheFilePath
      }).then((downloadTask) => {
        downloadTask.on('complete', () => {
          KRRenderLog.i('KRNetworkCacheUtil', `donwload success with url:${url} cacheFilePath:${cacheFilePath}`)
          const callbacks = KRNetworkCacheUtil.downloadTasks.get(url);
          if (callbacks) {
            KRNetworkCacheUtil.downloadTasks.delete(url);
            callbacks.forEach((cb) => cb(cacheFilePath));
          }
        })

        downloadTask.on('fail', (err: number) => {
          KRRenderLog.i('KRNetworkCacheUtil', `donwload fail with url:${url} cacheFilePath:${cacheFilePath} err:${err}`)
          KRNetworkCacheUtil.handleLoadFailure(url, cacheFilePath);
        });

      }).catch((err: BusinessError) => {
        KRRenderLog.i('KRNetworkCacheUtil', `Invoke downloadTask failed, code is ${err.code}, message is ${err.message} url is ${url}`)
        KRNetworkCacheUtil.handleLoadFailure(url, cacheFilePath);
      });
    }
  }

  private static handleLoadFailure(url: string, cacheFilePath: string) {
    try {
      // 清理下载失败的路径的遗留资源
      fs.unlinkSync(cacheFilePath);
    } catch (e) {
      // ignore unlink errors (file may not exist)
    }
    const callbacks = KRNetworkCacheUtil.downloadTasks.get(url);
    if (callbacks) {
      KRNetworkCacheUtil.downloadTasks.delete(url);
      callbacks.forEach((cb) => cb(null));
    }
  }

  /**
   * Cache file name for a url: a fixed-length SHA-256 hex digest of the
   * complete url (scheme, host, path and query all participate, so two
   * signed urls for the same object with different signatures stay distinct)
   * plus a bounded extension. Earlier versions used the url itself with
   * punctuation replaced, which has no length limit and makes fs.access /
   * download fail with ENAMETOOLONG (13900030) on long signed urls.
   */
  static urlToFilename(url: string): string {
    return KRNetworkCacheUtil.sha256Hex(url);
  }

  /**
   * 根据网络url生成本地的文件磁盘文件路径
   * @param url
   */
  static generateCacheFilePathByUrl(context: common.UIAbilityContext, url: string): string {
    return `${context.cacheDir}/${KRNetworkCacheUtil.generateCacheFileNameByUrl(url)}`;
  }

  /**
   * File name component only (no directory); kept separate so it can be
   * checked without a UIAbilityContext.
   */
  static generateCacheFileNameByUrl(url: string): string {
    const type = KRNetworkCacheUtil.getPathExtension(url) ?? 'data';
    return `${KRNetworkCacheUtil.urlToFilename(url)}.${type}`;
  }

  static  getPathExtension(url: string): string | null {
    const pathWithoutQuery = url.split('?')[0]; // 先去掉查询参数部分
    const pathParts = pathWithoutQuery.split('/');
    const fileName = pathParts[pathParts.length - 1];
    const fileParts = fileName.split('.');

    if (fileParts.length > 1) {
      const ext = fileParts[fileParts.length - 1];
      // The extension is the only url-derived text left in the file name;
      // keep it to a short alphanumeric token so it cannot reintroduce an
      // over-long or unsafe name.
      if (/^[a-zA-Z0-9]{1,16}$/.test(ext)) {
        return ext;
      }
      return null;
    } else {
      return null;
    }
  }

  private static sha256Hex(text: string): string {
    const md = cryptoFramework.createMd('SHA256');
    // Encode with TextEncoder rather than @ohos.buffer: Buffer.buffer does not
    // expose the pooled backing store with the Buffer's own byteOffset, so
    // viewing it as Uint8Array(buffer, byteOffset, length) threw
    // "RangeError: The newByteLength is out of range" (1.13.0 11300009).
    md.updateSync({ data: new util.TextEncoder().encodeInto(text) });
    const digest = md.digestSync().data;
    let hex = '';
    for (let i = 0; i < digest.length; i++) {
      hex += digest[i].toString(16).padStart(2, '0');
    }
    return hex;
  }
}