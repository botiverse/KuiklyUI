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

#import "KRSelectableTextView.h"
#import "KRComponentDefine.h"
#import "KRConvertUtil.h"
#import "UIView+CSS.h"

@interface KRSelectableTextView()
/** attr is text */
@property (nonatomic, copy) NSString *KUIKLY_PROP(text);
/** attr is fontSize */
@property (nonatomic, strong) NSNumber *KUIKLY_PROP(fontSize);
/** attr is fontWeight */
@property (nonatomic, strong) NSString *KUIKLY_PROP(fontWeight);
/** attr is color */
@property (nonatomic, strong) NSString *KUIKLY_PROP(color);
/** attr is lineHeight */
@property (nonatomic, strong) NSNumber *KUIKLY_PROP(lineHeight);
/** attr is textAlign */
@property (nonatomic, strong) NSString *KUIKLY_PROP(textAlign);
@end

@implementation KRSelectableTextView

@synthesize hr_rootView;
#if TARGET_OS_OSX
@synthesize css_clipPath = _css_clipPath;
#endif

#pragma mark - init

- (instancetype)init {
    if (self = [super init]) {
#if TARGET_OS_OSX // [macOS]
        self.textContainerInset = NSZeroSize;
        self.editable = NO;
        self.selectable = YES;
        [self setDrawsBackground:NO];
        [self setFocusRingType:NSFocusRingTypeNone];
#else // [macOS]
        self.editable = NO;
        self.selectable = YES;
        self.scrollEnabled = NO;
        self.textContainerInset = UIEdgeInsetsZero;
        self.dataDetectorTypes = UIDataDetectorTypeNone;
#endif // [macOS]
        self.textContainer.lineFragmentPadding = 0;
        self.backgroundColor = [UIColor clearColor];
    }
    return self;
}

#pragma mark - KuiklyRenderViewExportProtocol

- (void)hrv_setPropWithKey:(NSString *)propKey propValue:(id)propValue {
    KUIKLY_SET_CSS_COMMON_PROP;
}

- (void)hrv_callWithMethod:(NSString *)method params:(NSString *)params callback:(KuiklyRenderCallback)callback {
    KUIKLY_CALL_CSS_METHOD;
}

- (void)hrv_removeFromSuperview {
#if !TARGET_OS_OSX
    // A non-editable UITextView can remain first responder while its system
    // edit menu is visible. End that native selection session before Kuikly
    // detaches the view; otherwise the menu can outlive a dismissed modal.
    self.selectedTextRange = nil;
    [self resignFirstResponder];
#endif
    [super hrv_removeFromSuperview];
}

#pragma mark - setter (css property)

// View capability: this surface's accessibility truth comes from the system
// UITextView selection semantics. The compose semantics bridge derives its
// clickable/long-clickable mask from compose click semantics (absent here);
// applying it would overwrite the text view's native traits. Decline only
// this mask — every other accessibility prop applies normally.
- (void)setCss_accessibilityInfo:(NSString *)css_accessibilityInfo {
    // Intentionally ignored.
}

- (void)setCss_text:(NSString *)css_text {
    if (_css_text != css_text) {
        _css_text = css_text;
        [self p_applyContent];
    }
}

- (void)setCss_fontSize:(NSNumber *)css_fontSize {
    if (_css_fontSize != css_fontSize) {
        _css_fontSize = css_fontSize;
        [self p_applyContent];
    }
}

- (void)setCss_fontWeight:(NSString *)css_fontWeight {
    if (_css_fontWeight != css_fontWeight) {
        _css_fontWeight = css_fontWeight;
        [self p_applyContent];
    }
}

- (void)setCss_color:(NSString *)css_color {
    if (_css_color != css_color) {
        _css_color = css_color;
        [self p_applyContent];
    }
}

- (void)setCss_lineHeight:(NSNumber *)css_lineHeight {
    if (_css_lineHeight != css_lineHeight) {
        _css_lineHeight = css_lineHeight;
        [self p_applyContent];
    }
}

- (void)setCss_textAlign:(NSString *)css_textAlign {
    if (_css_textAlign != css_textAlign) {
        _css_textAlign = css_textAlign;
        [self p_applyContent];
    }
}

#pragma mark - private

- (UIFont *)p_font {
    return [KRConvertUtil UIFont:@{
        @"fontSize": _css_fontSize ?: @(15),
        @"fontWeight": _css_fontWeight ?: @"400"
    }];
}

- (void)p_applyContent {
    NSString *content = _css_text ?: @"";
    UIFont *font = [self p_font];
    UIColor *textColor = _css_color ? [UIView css_color:_css_color] : [UIColor blackColor];

    NSMutableDictionary<NSAttributedStringKey, id> *attributes = [NSMutableDictionary dictionary];
    attributes[NSFontAttributeName] = font;
    attributes[NSForegroundColorAttributeName] = textColor;

    NSMutableParagraphStyle *paragraphStyle = [[NSMutableParagraphStyle alloc] init];
    paragraphStyle.alignment = [KRConvertUtil NSTextAlignment:_css_textAlign];
    if (_css_lineHeight.floatValue > FLT_EPSILON) {
        paragraphStyle.minimumLineHeight = [_css_lineHeight floatValue];
        paragraphStyle.maximumLineHeight = [_css_lineHeight floatValue];
        CGFloat baselineOffset = ([_css_lineHeight floatValue] - font.pointSize) / 2;
        attributes[NSBaselineOffsetAttributeName] = @(baselineOffset);
    }
    attributes[NSParagraphStyleAttributeName] = paragraphStyle;

    self.attributedText = [[NSAttributedString alloc] initWithString:content attributes:attributes];
}

@end
