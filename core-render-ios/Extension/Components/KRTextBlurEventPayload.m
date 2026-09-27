#import "KRTextBlurEventPayload.h"
#import "KuiklyRenderBridge.h"

NSString *KRRawTextRestoringAttachments(NSAttributedString *attributedText,
                                        NSString *plainFallback) {
    if (!attributedText) {
        return plainFallback ?: @"";
    }
    NSMutableString *outputText = [NSMutableString stringWithString:attributedText.string ?: @""];
    __block NSInteger offset = 0;
    [attributedText enumerateAttribute:NSAttachmentAttributeName
                               inRange:NSMakeRange(0, attributedText.length)
                               options:0
                            usingBlock:^(NSObject *value, NSRange range, BOOL *stop) {
        if (![value respondsToSelector:@selector(kr_originlTextBeforeTextAttachment)]) {
            return;
        }
        id<KRTextAttachmentStringProtocol> attachment = (id<KRTextAttachmentStringProtocol>)value;
        NSString *replaceText = [attachment kr_originlTextBeforeTextAttachment];
        if (replaceText.length == 0) {
            return;
        }
        NSRange replaceRange = NSMakeRange(range.location + offset, range.length);
        [outputText replaceCharactersInRange:replaceRange withString:replaceText];
        offset += (NSInteger)replaceText.length - (NSInteger)range.length;
    }];
    return outputText;
}

NSDictionary *KRBlurEventPayloadFromAttributedText(NSAttributedString *attributedText,
                                                   NSString *plainText,
                                                   NSNumber *focusRequestId) {
    NSMutableDictionary *payload = [@{
        @"text": KRRawTextRestoringAttachments(attributedText, plainText),
    } mutableCopy];
    if (focusRequestId) {
        payload[@"focusRequestId"] = focusRequestId;
    }
    return payload;
}
