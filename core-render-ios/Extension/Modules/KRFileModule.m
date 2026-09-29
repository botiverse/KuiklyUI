/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 */

#import "KRFileModule.h"
#import "NSObject+KR.h"

static dispatch_queue_t KRProfilerFileQueue(void) {
    static dispatch_queue_t queue;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        queue = dispatch_queue_create("com.tencent.kuikly.profiler.file", DISPATCH_QUEUE_SERIAL);
    });
    return queue;
}

// Accessed only from KRProfilerFileQueue(). A successful operation is recorded before its
// callback, so a retry through another Pager can safely acknowledge a lost callback without
// applying the same append or overwrite twice.
static NSMutableSet<NSString *> *KRCompletedProfilerOperationIds(void) {
    static NSMutableSet<NSString *> *operationIds;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        operationIds = [[NSMutableSet alloc] init];
    });
    return operationIds;
}

@implementation KRFileModule

/**
 * 返回 Profiler 专用的可写目录：Library/Caches/KuiklyProfiler/
 * 使用 Caches（而非 Documents）的原因：
 *   - 不计入 iCloud 备份
 *   - xcrun devicectl device copy from --domain-type appDataContainer 同样可读取
 *   - 多个页面（Pager）共享同一目录，同名文件后写覆盖前写
 */
- (NSString *)profilerDir {
    NSArray *paths = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, YES);
    NSString *cachesDir = paths.firstObject ?: NSTemporaryDirectory();
    NSString *profilerDir = [cachesDir stringByAppendingPathComponent:@"KuiklyProfiler"];
    [[NSFileManager defaultManager] createDirectoryAtPath:profilerDir
                              withIntermediateDirectories:YES
                                               attributes:nil
                                                    error:nil];
    return profilerDir;
}

- (void)getFilesDir:(NSDictionary *)args {
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *dir = [self profilerDir];
    if (callback) {
        callback(@{@"path": dir});
    }
}

- (void)appendFile:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];

    NSString *filename = params[@"filename"];
    NSString *content = params[@"content"];
    NSString *operationId = params[@"operationId"];

    if (!filename || !content) {
        if (callback) callback(@{@"error": @"missing filename or content"});
        return;
    }

    NSString *profilerDir = [self profilerDir];
    NSString *filePath = [profilerDir stringByAppendingPathComponent:filename];

    dispatch_async(KRProfilerFileQueue(), ^{
        NSMutableSet<NSString *> *completedOperationIds = KRCompletedProfilerOperationIds();
        if (operationId.length > 0 && [completedOperationIds containsObject:operationId]) {
            if (callback) callback(@{@"path": filePath});
            return;
        }
        NSError *error = nil;
        // 追加写：每行内容末尾加换行，适合 JSONL 格式
        NSString *line = [content stringByAppendingString:@"\n"];
        NSData *data = [line dataUsingEncoding:NSUTF8StringEncoding];
        NSFileManager *fm = [NSFileManager defaultManager];
        if ([fm fileExistsAtPath:filePath]) {
            NSFileHandle *handle = [NSFileHandle fileHandleForWritingAtPath:filePath];
            if (handle) {
                [handle seekToEndOfFile];
                [handle writeData:data];
                [handle closeFile];
                if (operationId.length > 0) {
                    [completedOperationIds addObject:operationId];
                }
                if (callback) callback(@{@"path": filePath});
            } else {
                if (callback) callback(@{@"error": @"failed to open file for appending"});
            }
        } else {
            BOOL success = [data writeToFile:filePath options:NSDataWritingAtomic error:&error];
            if (success && operationId.length > 0) {
                [completedOperationIds addObject:operationId];
            }
            if (callback) {
                if (success) {
                    callback(@{@"path": filePath});
                } else {
                    callback(@{@"error": error.localizedDescription ?: @"unknown error"});
                }
            }
        }
    });
}

- (void)writeFile:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];

    NSString *filename = params[@"filename"];
    NSString *content = params[@"content"];
    NSString *operationId = params[@"operationId"];

    if (!filename || !content) {
        if (callback) callback(@{@"error": @"missing filename or content"});
        return;
    }

    NSString *profilerDir = [self profilerDir];
    NSString *filePath = [profilerDir stringByAppendingPathComponent:filename];

    dispatch_async(KRProfilerFileQueue(), ^{
        NSMutableSet<NSString *> *completedOperationIds = KRCompletedProfilerOperationIds();
        if (operationId.length > 0 && [completedOperationIds containsObject:operationId]) {
            if (callback) callback(@{@"path": filePath});
            return;
        }
        NSError *error = nil;
        BOOL success = [content writeToFile:filePath
                                 atomically:YES
                                   encoding:NSUTF8StringEncoding
                                      error:&error];
        if (success && operationId.length > 0) {
            [completedOperationIds addObject:operationId];
        }
        if (callback) {
            if (success) {
                callback(@{@"path": filePath});
            } else {
                callback(@{@"error": error.localizedDescription ?: @"unknown error"});
            }
        }
    });
}

@end
