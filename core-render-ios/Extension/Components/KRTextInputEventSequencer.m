//
//  KRTextInputEventSequencer.m
//  Kuikly
//

#import "KRTextInputEventSequencer.h"

@implementation KRTextInputEventSequencer {
    BOOL _hasPendingMarkedText;
    NSUInteger _pendingMarkedRawTextLength;
    NSUInteger _editGeneration;
}

- (NSUInteger)editGeneration {
    return _editGeneration;
}

- (void)notifyState:(NSDictionary *)state
    completeCallback:(KRTextInputStateCallback)completeCallback
      legacyCallback:(KRTextInputStateCallback)legacyCallback {
    // Both callbacks are derived from the exact same immutable native snapshot. The complete
    // state must lead so Compose can pair and consume the following legacy echo atomically.
    if (completeCallback) {
        completeCallback(state);
    }
    if (legacyCallback) {
        legacyCallback(@{
            @"text": state[@"text"] ?: @"",
            @"length": state[@"length"] ?: @0
        });
    }
}

- (NSDictionary *)recordRawTextLength:(NSUInteger)rawTextLength
                         hasMarkedText:(BOOL)hasMarkedText {
    _editGeneration += 1;
    if (hasMarkedText) {
        _hasPendingMarkedText = YES;
        _pendingMarkedRawTextLength = rawTextLength;
        return nil;
    }
    if (!_hasPendingMarkedText) {
        return nil;
    }

    NSUInteger previousLength = _pendingMarkedRawTextLength;
    [self invalidatePendingMarkedText];
    if (previousLength <= rawTextLength || previousLength - rawTextLength < 8) {
        return nil;
    }
    return @{
        @"generation": @(_editGeneration),
        @"markedLength": @(previousLength),
        @"committedLength": @(rawTextLength)
    };
}

- (void)invalidatePendingMarkedText {
    _hasPendingMarkedText = NO;
    _pendingMarkedRawTextLength = 0;
}

@end
