#import <AppKit/AppKit.h>
#import "KRMemoryCacheModule.h"
#import "KRImageView.h"

NSString *const KRImageBase64Prefix = @"data:image";
NSString *const KRImageAssetsPrefix = @"assets://";
NSString *const KRImageLocalPathPrefix = @"file://";
NSString *const KR_PARAM_KEY = @"param";
NSString *const KR_CALLBACK_KEY = @"callback";
@implementation TDFBaseModule
@end
@implementation KRBaseModule
@synthesize hr_rootView;
@end
@implementation NSString (CacheFixture)
- (NSString *)kr_md5String { return self; }
@end
@implementation NSDictionary (CacheFixture)
- (NSString *)hr_dictionaryToString {
    return [[NSString alloc] initWithData:[NSJSONSerialization dataWithJSONObject:self options:0 error:nil] encoding:NSUTF8StringEncoding];
}
@end

static NSPointerArray *loads; // Observes loaders without extending their lifetime.
@interface KRImageView ()
@property(nonatomic, copy) KuiklyRenderCallback success;
@property(nonatomic, copy) KuiklyRenderCallback failure;
@end
@implementation KRImageView
@synthesize hr_rootView;
- (void)hrv_setPropWithKey:(NSString *)key propValue:(id)value {
    if ([key isEqualToString:@"loadSuccess"]) self.success = value;
    else if ([key isEqualToString:@"loadFailure"]) self.failure = value;
    else if ([key isEqualToString:@"src"]) [loads addPointer:(__bridge void *)self];
}
@end
@interface KRMemoryCacheModule (Fixture)
- (NSString *)cacheImage:(NSString *)src sync:(BOOL)sync callback:(KuiklyRenderCallback)callback;
@end
static void Check(BOOL ok, NSString *message) {
    if (!ok) { NSLog(@"FAIL: %@", message); exit(1); }
}
static KRImageView *Start(KRMemoryCacheModule *cache, NSString *key, KuiklyRenderCallback cb) {
    NSUInteger before = loads.count;
    NSString *receipt = [cache cacheImage:key sync:NO callback:cb];
    Check([receipt containsString:@"InProgress"], @"async starts in progress");
    NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:2];
    while (loads.count == before && deadline.timeIntervalSinceNow > 0)
        [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
    Check(loads.count == before + 1, @"loader installed");
    return (__bridge KRImageView *)[loads pointerAtIndex:loads.count - 1];
}
int main(void) {
    @autoreleasepool {
        loads = [NSPointerArray weakObjectsPointerArray];
        KRMemoryCacheModule *cache = [KRMemoryCacheModule new];
        __block NSUInteger count = 0;
        __block NSDictionary *out;
        KRImageView *failed = Start(cache, @"remote-a", ^(id r) { count++; out = r; });
        Check(failed.failure != nil, @"failure handler registered");
        KuiklyRenderCallback lateSuccess = failed.success;
        failed.failure(@{@"errorCode": @404});
        lateSuccess(@{});
        Check(count == 1 && [out[@"state"] isEqual:@"Complete"] && [out[@"errorCode"] intValue] != 0, @"failure terminates once");
        Check([cache imageWithKey:@"remote-a"] == nil && failed.failure == nil && failed.success == nil, @"failure clears callbacks and cache");

        KRImageView *retry = Start(cache, @"remote-a", ^(id r) { count++; out = r; });
        retry.image = [[UIImage alloc] initWithSize:NSMakeSize(3,4)];
        retry.success(@{});
        Check(count == 2 && [out[@"errorCode"] intValue] == 0 && [out[@"width"] intValue] == 3 && [cache imageWithKey:@"remote-a"] == retry.image, @"retry succeeds and caches dimensions");

        KRImageView *old = Start(cache, @"same-key", nil);
        KRImageView *fresh = Start(cache, @"same-key", nil);
        fresh.image = [[UIImage alloc] initWithSize:NSMakeSize(5,6)];
        fresh.success(@{});
        old.failure(@{});
        Check([cache imageWithKey:@"same-key"] == fresh.image, @"old failure preserves newer cached result; nil callback still settles");

        // No fixture-owned strong view references: replacing a cache slot
        // must leave both callers alive, then release each view at completion.
        for (NSNumber *oldSucceeds in @[@NO, @YES]) {
            NSString *key = [NSString stringWithFormat:@"replacement-%@", oldSucceeds];
            __weak KRImageView *oldView;
            __weak KRImageView *newView;
            __block NSUInteger oldCalls = 0, newCalls = 0;
            __block NSDictionary *oldResult;
            @autoreleasepool {
                oldView = Start(cache, key, ^(id r) { oldCalls++; oldResult = r; });
            }
            @autoreleasepool {
                newView = Start(cache, key, ^(id r) { newCalls++; });
            }
            Check(oldView != nil && newView != nil, @"same-key callers retain independent inflight ownership");
            UIImage *newImage = [[UIImage alloc] initWithSize:NSMakeSize(7,8)];
            @autoreleasepool {
                newView.image = newImage;
                newView.success(@{});
            }
            Check(newCalls == 1 && newView == nil, @"new request settles and releases its view");
            @autoreleasepool {
                if (oldSucceeds.boolValue) {
                    oldView.image = [[UIImage alloc] initWithSize:NSMakeSize(1,2)];
                    oldView.success(@{});
                } else {
                    oldView.failure(@{});
                }
            }
            Check(oldCalls == 1 && [oldResult[@"state"] isEqual:@"Complete"], @"old nonempty caller receives terminal");
            Check(([oldResult[@"errorCode"] intValue] == 0) == oldSucceeds.boolValue, @"old caller receives its own outcome");
            Check(oldView == nil && [cache imageWithKey:key] == newImage, @"old request releases without replacing or evicting newer image");
        }

        KRImageView *empty = Start(cache, @"empty-success", ^(id r) { out = r; });
        empty.success(@{});
        Check([out[@"errorCode"] intValue] != 0, @"success without image is failure");

        __weak KRMemoryCacheModule *weakCache;
        @autoreleasepool {
            KRMemoryCacheModule *temporary = [KRMemoryCacheModule new];
            weakCache = temporary;
            Start(temporary, @"never-finishes", ^(id r) {});
        }
        Check(weakCache == nil, @"inflight callbacks do not retain module");
        NSLog(@"PASS: memory cache failure, retry, stale completion, nil callback and lifetime");
    }
    return 0;
}
