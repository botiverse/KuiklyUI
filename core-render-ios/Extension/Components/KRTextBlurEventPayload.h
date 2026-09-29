#import <Foundation/Foundation.h>
#if TARGET_OS_OSX
#import <AppKit/AppKit.h>
#else
#import <UIKit/UIKit.h>
#endif

NS_ASSUME_NONNULL_BEGIN

/// Restores attachment placeholders to their original text
/// (kr_originlTextBeforeTextAttachment) and returns the raw output string.
/// Falls back to `plainFallback` when `attributedText` is nil.
NSString *KRRawTextRestoringAttachments(NSAttributedString * _Nullable attributedText,
                                        NSString * _Nullable plainFallback);

/// Builds the input-blur event payload. The text is always the RAW output
/// text (never the display string), so a textPostProcessor field's visible
/// placeholder cannot overwrite the stored raw value on blur.
NSDictionary *KRBlurEventPayloadFromAttributedText(NSAttributedString * _Nullable attributedText,
                                                   NSString * _Nullable plainText,
                                                   NSNumber * _Nullable focusRequestId);

NS_ASSUME_NONNULL_END
