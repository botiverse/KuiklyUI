//
//  KRTextInputEventSequencer.h
//  Kuikly
//

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

typedef void (^KRTextInputStateCallback)(NSDictionary *state);

/// Owns the ordering and edit-session lifetime of native text-input callback metadata.
/// This class intentionally has no UIKit dependency so its production behavior can be executed
/// by a small host-side fixture in CI.
@interface KRTextInputEventSequencer : NSObject

@property (nonatomic, assign, readonly) NSUInteger editGeneration;

- (void)notifyState:(NSDictionary *)state
    completeCallback:(nullable KRTextInputStateCallback)completeCallback
      legacyCallback:(nullable KRTextInputStateCallback)legacyCallback;

/// Returns content-free metadata only for a large marked -> committed contraction in the same
/// uninterrupted native edit session. Ordinary changes return nil.
- (nullable NSDictionary *)recordRawTextLength:(NSUInteger)rawTextLength
                                 hasMarkedText:(BOOL)hasMarkedText;

/// Ends the pending marked-text observation. Call this for blur and every programmatic text-state
/// mutation so a later edit cannot be attributed to an earlier IME session.
- (void)invalidatePendingMarkedText;

@end

NS_ASSUME_NONNULL_END
