// Regression: the blur event payload must carry the RAW output text
// (attachments restored via kr_originlTextBeforeTextAttachment), never the
// display string — otherwise a textPostProcessor field's blur would overwrite
// the stored raw value with its visible placeholder.
#import <Foundation/Foundation.h>
#import <AppKit/AppKit.h>
#import "KRTextBlurEventPayload.h"
#import "KuiklyRenderBridge.h"

@interface KRBlurTestAttachment : NSTextAttachment <KRTextAttachmentStringProtocol>
@end
@implementation KRBlurTestAttachment
- (NSString *)kr_originlTextBeforeTextAttachment { return @"@alice"; }
@end

static int failures = 0;
#define KRAssert(cond, msg) do { if (!(cond)) { \
    NSLog(@"FAIL: %s", msg); failures++; } else { NSLog(@"PASS: %s", msg); } } while (0)

int main(void) {
    @autoreleasepool {
        // 1) Attachment restored to raw text; placeholder never leaks.
        NSMutableAttributedString *display =
            [[NSMutableAttributedString alloc] initWithString:@"hi \uFFFC"];
        [display addAttribute:NSAttachmentAttributeName
                        value:[KRBlurTestAttachment new]
                        range:NSMakeRange(3, 1)];
        NSDictionary *payload =
            KRBlurEventPayloadFromAttributedText(display, nil, @(42));
        KRAssert([payload[@"text"] isEqualToString:@"hi @alice"],
                 "blur text is raw output text (attachment restored)");
        KRAssert(![payload[@"text"] containsString:@"\uFFFC"],
                 "blur text is not the display string");
        KRAssert([payload[@"focusRequestId"] isEqualToNumber:@(42)],
                 "focusRequestId carried through");

        // 2) Plain attributed text passes through unchanged.
        payload = KRBlurEventPayloadFromAttributedText(
            [[NSAttributedString alloc] initWithString:@"plain"], nil, nil);
        KRAssert([payload[@"text"] isEqualToString:@"plain"],
                 "plain text passes through");
        KRAssert(payload[@"focusRequestId"] == nil,
                 "nil focusRequestId omitted from payload");

        // 3) Empty string stays empty (explicit clear must not be lost).
        payload = KRBlurEventPayloadFromAttributedText(
            [[NSAttributedString alloc] initWithString:@""], nil, nil);
        KRAssert([payload[@"text"] isEqualToString:@""],
                 "explicit empty string preserved");

        // 4) No attributed text -> plain-text fallback (raw current text).
        payload = KRBlurEventPayloadFromAttributedText(nil, @"rawCurrent", nil);
        KRAssert([payload[@"text"] isEqualToString:@"rawCurrent"],
                 "nil attributedText falls back to plain text");

        NSLog(@"%@", failures == 0 ? @"ALL PASS" : @"FAILURES");
        return failures == 0 ? 0 : 1;
    }
}
