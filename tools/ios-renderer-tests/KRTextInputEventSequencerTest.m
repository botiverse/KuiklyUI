#import <Foundation/Foundation.h>

#import "KRTextInputEventSequencer.h"

static void KRAssert(BOOL condition, NSString *message) {
    if (!condition) {
        NSLog(@"KRTextInputEventSequencerTest failed: %@", message);
        exit(1);
    }
}

static NSDictionary *KRState(NSString *text, NSUInteger selection, NSInteger compositionStart, NSInteger compositionEnd) {
    return @{
        @"text": text,
        @"length": @(text.length),
        @"selectionStart": @(selection),
        @"selectionEnd": @(selection),
        @"compositionStart": @(compositionStart),
        @"compositionEnd": @(compositionEnd),
        @"syncRevision": @7
    };
}

static void testCompleteStateLeadsLegacyFromOneSnapshot(void) {
    KRTextInputEventSequencer *sequencer = [KRTextInputEventSequencer new];
    NSString *markedText = [@"" stringByPaddingToLength:31 withString:@"p" startingAtIndex:0];
    NSDictionary *markedState = KRState(markedText, 31, 0, 31);
    NSDictionary *committedState = KRState(@"abc", 3, -1, -1);
    NSMutableArray<NSString *> *order = [NSMutableArray array];
    __block NSDictionary *receivedMarkedCompleteState = nil;
    __block NSDictionary *receivedCompleteState = nil;
    __block NSDictionary *receivedLegacyState = nil;

    [sequencer notifyState:markedState
          completeCallback:^(NSDictionary *state) {
              [order addObject:@"marked-complete"];
              receivedMarkedCompleteState = state;
          }
            legacyCallback:^(NSDictionary *state) {
                [order addObject:@"marked-legacy"];
            }];
    [sequencer notifyState:committedState
          completeCallback:^(NSDictionary *state) {
              [order addObject:@"commit-complete"];
              receivedCompleteState = state;
          }
            legacyCallback:^(NSDictionary *state) {
                [order addObject:@"commit-legacy"];
                receivedLegacyState = state;
            }];

    KRAssert(
        [order isEqualToArray:@[@"marked-complete", @"marked-legacy", @"commit-complete", @"commit-legacy"]],
        @"31 marked -> 3 committed must publish complete before legacy in both phases"
    );
    KRAssert(receivedMarkedCompleteState == markedState, @"marked complete must receive its production snapshot instance");
    KRAssert(receivedCompleteState == committedState, @"complete must receive the production snapshot instance");
    KRAssert([receivedLegacyState[@"text"] isEqual:committedState[@"text"]], @"legacy text must derive from snapshot");
    KRAssert([receivedLegacyState[@"length"] isEqual:committedState[@"length"]], @"legacy length must derive from snapshot");
    KRAssert([receivedLegacyState[@"syncRevision"] isEqual:committedState[@"syncRevision"]], @"legacy revision must derive from snapshot");
    KRAssert([committedState[@"selectionStart"] unsignedIntegerValue] == 3, @"final selection must remain 3");
    KRAssert([committedState[@"compositionStart"] integerValue] == -1, @"committed state must clear composition");
}

static void testMarkedContractionIsBoundToOneEditSession(void) {
    KRTextInputEventSequencer *sequencer = [KRTextInputEventSequencer new];

    KRAssert([sequencer recordRawTextLength:31 hasMarkedText:YES] == nil, @"marked growth is not a commit");
    NSDictionary *sameSession = [sequencer recordRawTextLength:3 hasMarkedText:NO];
    KRAssert([sameSession[@"markedLength"] unsignedIntegerValue] == 31, @"same-session marked length");
    KRAssert([sameSession[@"committedLength"] unsignedIntegerValue] == 3, @"same-session committed length");

    [sequencer recordRawTextLength:31 hasMarkedText:YES];
    [sequencer invalidatePendingMarkedText]; // textViewDidEndEditing
    KRAssert([sequencer recordRawTextLength:3 hasMarkedText:NO] == nil, @"blur must end pending marked attribution");

    [sequencer recordRawTextLength:31 hasMarkedText:YES];
    [sequencer invalidatePendingMarkedText]; // css_setTextInputState / setCss_text / css_setText / setCss_values
    KRAssert([sequencer recordRawTextLength:3 hasMarkedText:NO] == nil, @"controlled sync must end pending marked attribution");
}

int main(void) {
    @autoreleasepool {
        testCompleteStateLeadsLegacyFromOneSnapshot();
        testMarkedContractionIsBoundToOneEditSession();
        NSLog(@"KRTextInputEventSequencerTest passed");
    }
    return 0;
}
