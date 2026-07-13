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

#import "KRRichTextView.h"
#import "KRComponentDefine.h"
#import "KRConvertUtil.h"
#import "KuiklyRenderBridge.h"
#import "NSObject+KR.h"
#import <CoreText/CoreText.h>

NSString *const KuiklyIndexAttributeName = @"KuiklyIndexAttributeName";
NSString *const kGradientInfoKeyCSSGradient = @"cssGradient";
NSString *const kGradientInfoKeyFont = @"font";
NSString *const kGradientInfoKeyGlobalRange = @"globalRange";

static const CGFloat kKRSlockInlineCodeHorizontalPaddingRatio = 4.0 / 15.0;
static const CGFloat kKRSlockInlineCodeHorizontalMarginRatio = 2.0 / 15.0;
static const CGFloat kKRSlockInlineCodeLineHeightRatio = 1.5;
static const NSUInteger kKRSlockInlineCodeAtomizeThreshold = 16;

static BOOL KRIsNumericReferenceInlineBox(NSString *semanticText) {
    if (semanticText.length < 2 || [semanticText characterAtIndex:0] != '#') {
        return NO;
    }
    for (NSUInteger index = 1; index < semanticText.length; index++) {
        unichar character = [semanticText characterAtIndex:index];
        if (character < '0' || character > '9') {
            return NO;
        }
    }
    return YES;
}

static CGFloat KRInlineBoxTrailingCavityCompensation(NSAttributedString *group,
                                                      NSRange visibleRange) {
    if (group.length == 0 || visibleRange.length == 0 || NSMaxRange(visibleRange) > group.length) {
        return 0;
    }
    NSTextStorage *storage = [[NSTextStorage alloc] initWithAttributedString:group];
    KRLayoutManager *layoutManager = [KRLayoutManager new];
    NSTextContainer *container = [[NSTextContainer alloc] initWithSize:CGSizeMake(10000, 1000)];
    container.lineFragmentPadding = 0;
    [layoutManager addTextContainer:container];
    [storage addLayoutManager:layoutManager];
    [layoutManager ensureLayoutForTextContainer:container];

    NSRange groupGlyphRange = [layoutManager glyphRangeForTextContainer:container];
    NSRange visibleGlyphRange = [layoutManager glyphRangeForCharacterRange:visibleRange
                                                     actualCharacterRange:NULL];
    if (groupGlyphRange.length == 0 || visibleGlyphRange.length == 0) {
        return 0;
    }
    CGRect groupBounds = [layoutManager boundingRectForGlyphRange:groupGlyphRange
                                                  inTextContainer:container];
    CGRect visibleLayoutBounds = [layoutManager boundingRectForGlyphRange:visibleGlyphRange
                                                           inTextContainer:container];
    NSAttributedString *visibleText = [group attributedSubstringFromRange:visibleRange];
    CTLineRef line = CTLineCreateWithAttributedString((CFAttributedStringRef)visibleText);
    CGRect inkBounds = CTLineGetBoundsWithOptions(line, kCTLineBoundsUseGlyphPathBounds);
    CFRelease(line);
    if (CGRectIsEmpty(groupBounds) || CGRectIsEmpty(visibleLayoutBounds) ||
        CGRectIsNull(inkBounds) || CGRectIsInfinite(inkBounds)) {
        return 0;
    }
    CGFloat inkLeft = CGRectGetMinX(visibleLayoutBounds) + CGRectGetMinX(inkBounds);
    CGFloat inkRight = CGRectGetMinX(visibleLayoutBounds) + CGRectGetMaxX(inkBounds);
    CGFloat leadingCavity = inkLeft - CGRectGetMinX(groupBounds);
    CGFloat trailingCavity = CGRectGetMaxX(groupBounds) - inkRight;
    return MAX(0, trailingCavity - leadingCavity);
}

@interface KRInlineBoxAttachment : NSTextAttachment <KRTextAttachmentStringProtocol>

@property (nonatomic, copy) NSString *originalText;

- (instancetype)initWithText:(NSString *)text
                         font:(UIFont *)font
                    textColor:(UIColor *)textColor
                        style:(NSDictionary<NSString *, id> *)style
                letterSpacing:(CGFloat)letterSpacing;

@end

@implementation KRInlineBoxAttachment

- (instancetype)initWithText:(NSString *)text
                         font:(UIFont *)font
                    textColor:(UIColor *)textColor
                        style:(NSDictionary<NSString *, id> *)style
                letterSpacing:(CGFloat)letterSpacing {
    if (self = [super init]) {
        _originalText = [text copy] ?: @"";
        UIFont *resolvedFont = font ?: [UIFont systemFontOfSize:15.0];
        UIColor *resolvedTextColor = textColor ?: [UIColor blackColor];
        UIColor *backgroundColor = style[@"backgroundColor"] ?: [UIColor clearColor];
        UIColor *borderColor = style[@"borderColor"] ?: [UIColor clearColor];
        CGFloat borderWidth = [style[@"borderWidth"] doubleValue];
        CGFloat paddingStart = [style[@"paddingStart"] doubleValue];
        CGFloat paddingEnd = [style[@"paddingEnd"] doubleValue];
        CGFloat paddingTop = [style[@"paddingTop"] doubleValue];
        CGFloat paddingBottom = [style[@"paddingBottom"] doubleValue];
        CGFloat marginStart = [style[@"marginStart"] doubleValue];
        CGFloat marginEnd = [style[@"marginEnd"] doubleValue];
        CGFloat cornerRadius = [style[@"cornerRadius"] doubleValue];
        NSMutableDictionary<NSAttributedStringKey, id> *attributes = [@{
            NSFontAttributeName: resolvedFont,
            NSForegroundColorAttributeName: resolvedTextColor,
        } mutableCopy];
        if (letterSpacing != 0) {
            attributes[NSKernAttributeName] = @(letterSpacing);
        }
        NSAttributedString *displayText = [[NSAttributedString alloc] initWithString:_originalText attributes:attributes];
        CTLineRef line = CTLineCreateWithAttributedString((CFAttributedStringRef)displayText);
        CGFloat ascent = 0;
        CGFloat descent = 0;
        CGFloat leading = 0;
        CGFloat textWidth = (CGFloat)CTLineGetTypographicBounds(line, &ascent, &descent, &leading);
        CGFloat contentHeight = ascent + descent;
        CGFloat boxHeight = contentHeight + paddingTop + paddingBottom + borderWidth * 2.0;
        CGFloat totalWidth = textWidth + marginStart + marginEnd + paddingStart + paddingEnd + borderWidth * 2.0;
        CGFloat boxLeft = marginStart;
        CGFloat boxWidth = totalWidth - marginStart - marginEnd;

        UIGraphicsBeginImageContextWithOptions(CGSizeMake(totalWidth, boxHeight), NO, 0.0);
        CGContextRef context = UIGraphicsGetCurrentContext();
        if (context) {
            CGRect boxRect = CGRectMake(boxLeft, 0, boxWidth, boxHeight);
            CGPathRef boxPath = CGPathCreateWithRoundedRect(boxRect, cornerRadius, cornerRadius, NULL);
            CGContextAddPath(context, boxPath);
            CGContextSetFillColorWithColor(context, backgroundColor.CGColor);
            CGContextFillPath(context);
            if (borderWidth > 0 && borderColor) {
                CGRect strokeRect = CGRectInset(boxRect, borderWidth / 2.0, borderWidth / 2.0);
                CGPathRef strokePath = CGPathCreateWithRoundedRect(
                    strokeRect,
                    MAX(0, cornerRadius - borderWidth / 2.0),
                    MAX(0, cornerRadius - borderWidth / 2.0),
                    NULL
                );
                CGContextAddPath(context, strokePath);
                CGContextSetStrokeColorWithColor(context, borderColor.CGColor);
                CGContextSetLineWidth(context, borderWidth);
                CGContextStrokePath(context);
                CGPathRelease(strokePath);
            }
            CGPathRelease(boxPath);

            CGContextSaveGState(context);
            CGContextTranslateCTM(context, 0, boxHeight);
            CGContextScaleCTM(context, 1.0, -1.0);
            CGContextSetTextMatrix(context, CGAffineTransformIdentity);
            CGContextSetTextPosition(
                context,
                marginStart + borderWidth + paddingStart,
                borderWidth + paddingBottom + descent
            );
            CTLineDraw(line, context);
            CGContextRestoreGState(context);
        }
        UIImage *image = UIGraphicsGetImageFromCurrentImageContext();
        UIGraphicsEndImageContext();
        CFRelease(line);

        self.image = image;
        CGFloat baselineOffset = (resolvedFont.ascender + resolvedFont.descender) / 2.0 - boxHeight / 2.0;
        self.bounds = CGRectMake(0, baselineOffset, totalWidth, boxHeight);
    }
    return self;
}

- (NSString *)kr_originlTextBeforeTextAttachment {
    return self.originalText ?: @"";
}

@end

@interface KRInlineBoxEdgeAttachment : NSTextAttachment <KRTextAttachmentStringProtocol>
- (instancetype)initWithAdvance:(CGFloat)advance
                            font:(UIFont *)font
                      paddingTop:(CGFloat)paddingTop
                   paddingBottom:(CGFloat)paddingBottom
                     borderWidth:(CGFloat)borderWidth;
@end

@implementation KRInlineBoxEdgeAttachment

- (instancetype)initWithAdvance:(CGFloat)advance
                            font:(UIFont *)font
                      paddingTop:(CGFloat)paddingTop
                   paddingBottom:(CGFloat)paddingBottom
                     borderWidth:(CGFloat)borderWidth {
    if (self = [super init]) {
        UIFont *resolvedFont = font ?: [UIFont systemFontOfSize:15.0];
        CGFloat height = resolvedFont.ascender - resolvedFont.descender + paddingTop + paddingBottom + borderWidth * 2.0;
        CGFloat resolvedWidth = MAX(0, advance);
        CGFloat resolvedHeight = MAX(1, height);
        // TextKit may render a nil-image attachment as an opaque placeholder.
        // Edge attachments are layout-only advance; give them an explicit
        // transparent bitmap so the group chrome painted behind remains visible.
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(MAX(1, resolvedWidth), resolvedHeight), NO, 0.0);
        self.image = UIGraphicsGetImageFromCurrentImageContext();
        UIGraphicsEndImageContext();
        self.bounds = CGRectMake(0, resolvedFont.descender - paddingBottom, resolvedWidth, resolvedHeight);
    }
    return self;
}

- (NSString *)kr_originlTextBeforeTextAttachment {
    return @"";
}

@end

// Inline code uses the same atomic inline-box model as reference chips, at a
// finer granularity: one attachment per composed grapheme. Each atom owns its
// glyph measurement/drawing and original text, while KRLayoutManager paints one
// continuous chrome fragment after TextKit has chosen the final line breaks.
@interface KRSlockInlineCodeAtomAttachment : NSTextAttachment <KRTextAttachmentStringProtocol, KRSlockInlineCodeAtomProtocol>

@property (nonatomic, copy) NSString *originalText;
@property (nonatomic, assign) BOOL leadingEdge;
@property (nonatomic, assign) BOOL trailingEdge;

- (instancetype)initWithText:(NSString *)text
                         font:(UIFont *)font
                    textColor:(UIColor *)textColor
                 letterSpacing:(CGFloat)letterSpacing
                  leadingEdge:(BOOL)leadingEdge
                 trailingEdge:(BOOL)trailingEdge;

@end

@implementation KRSlockInlineCodeAtomAttachment

- (instancetype)initWithText:(NSString *)text
                         font:(UIFont *)font
                    textColor:(UIColor *)textColor
                letterSpacing:(CGFloat)letterSpacing
                 leadingEdge:(BOOL)leadingEdge
                trailingEdge:(BOOL)trailingEdge {
    if (self = [super init]) {
        _originalText = [text copy] ?: @"";
        _leadingEdge = leadingEdge;
        _trailingEdge = trailingEdge;
        UIFont *resolvedFont = font ?: [UIFont systemFontOfSize:15.0];
        UIColor *resolvedTextColor = textColor ?: [UIColor blackColor];
        NSMutableDictionary<NSAttributedStringKey, id> *attributes = [@{
            NSFontAttributeName: resolvedFont,
            NSForegroundColorAttributeName: resolvedTextColor,
        } mutableCopy];
        if (letterSpacing != 0) {
            attributes[NSKernAttributeName] = @(letterSpacing);
        }
        NSAttributedString *displayText = [[NSAttributedString alloc] initWithString:_originalText attributes:attributes];
        CTLineRef line = CTLineCreateWithAttributedString((CFAttributedStringRef)displayText);
        CGFloat ascent = 0;
        CGFloat descent = 0;
        CGFloat leading = 0;
        CGFloat textWidth = (CGFloat)CTLineGetTypographicBounds(line, &ascent, &descent, &leading);
        CGFloat textSize = resolvedFont.pointSize;
        CGFloat innerPadding = textSize * kKRSlockInlineCodeHorizontalPaddingRatio;
        CGFloat outerMargin = textSize * kKRSlockInlineCodeHorizontalMarginRatio;
        CGFloat edgeAdvance = innerPadding + outerMargin;
        CGFloat leadingAdvance = leadingEdge ? edgeAdvance : 0.0;
        CGFloat trailingAdvance = trailingEdge ? edgeAdvance : 0.0;
        CGFloat atomHeight = textSize * kKRSlockInlineCodeLineHeightRatio;
        CGFloat totalWidth = textWidth + leadingAdvance + trailingAdvance;

        UIGraphicsBeginImageContextWithOptions(CGSizeMake(totalWidth, atomHeight), NO, 0.0);
        CGContextRef context = UIGraphicsGetCurrentContext();
        if (context) {
            CGContextSaveGState(context);
            CGContextTranslateCTM(context, 0, atomHeight);
            CGContextScaleCTM(context, 1.0, -1.0);
            CGContextSetTextMatrix(context, CGAffineTransformIdentity);
            CGFloat baseline = (atomHeight - ascent + descent) / 2.0;
            CGContextSetTextPosition(context, leadingAdvance, baseline);
            CTLineDraw(line, context);
            CGContextRestoreGState(context);
        }
        UIImage *image = UIGraphicsGetImageFromCurrentImageContext();
        UIGraphicsEndImageContext();
        CFRelease(line);

        self.image = image;
        CGFloat baselineOffset = (resolvedFont.ascender + resolvedFont.descender) / 2.0 - atomHeight / 2.0;
        self.bounds = CGRectMake(0, baselineOffset, totalWidth, atomHeight);
    }
    return self;
}

- (NSString *)kr_originlTextBeforeTextAttachment {
    return self.originalText ?: @"";
}

- (BOOL)kr_slockInlineCodeLeadingEdge {
    return self.leadingEdge;
}

- (BOOL)kr_slockInlineCodeTrailingEdge {
    return self.trailingEdge;
}

@end

@interface KRRichTextView()

@property (nonatomic, strong) NSNumber *css_numberOfLines;
@property (nonatomic, strong) NSString *css_lineBreakMode;
@property (nonatomic, assign) NSInteger activeLongPressSpanIndex;

@end

@implementation KRRichTextView {
}
@synthesize hr_rootView;

#pragma mark - KuiklyRenderViewExportProtocol

- (instancetype)init {
    if (self = [super init]) {
        self.displaysAsynchronously = NO;
        [self kr_clearActiveLongPressSpanIndex];
    }
    return self;
}

- (void)hrv_setPropWithKey:(NSString *)propKey propValue:(id)propValue {
    KUIKLY_SET_CSS_COMMON_PROP;
}

- (void)hrv_prepareForeReuse {
    KUIKLY_RESET_CSS_COMMON_PROP;
    self.attributedText = nil;
    self.css_numberOfLines = nil;
    self.css_lineBreakMode = nil;
    [self kr_clearActiveLongPressSpanIndex];
}

+ (id<KuiklyRenderShadowProtocol>)hrv_createShadow {
    return [[KRRichTextShadow alloc] init];
}

- (void)hrv_setShadow:(id<KuiklyRenderShadowProtocol>)shadow {
    KRRichTextShadow * textShadow = (KRRichTextShadow *)shadow;
    self.attributedText = textShadow.attributedString;
}


#pragma mark - set prop

- (void)setCss_numberOfLines:(NSNumber *)css_numberOfLines {
    if (self.css_numberOfLines != css_numberOfLines) {
        _css_numberOfLines = css_numberOfLines;
        self.numberOfLines = [css_numberOfLines unsignedIntValue];
    }
}

- (void)setCss_lineBreakMode:(NSString *)css_lineBreakMode {
    if (self.css_lineBreakMode != css_lineBreakMode) {
        _css_lineBreakMode = css_lineBreakMode;
        self.lineBreakMode = [KRConvertUtil NSLineBreakMode:css_lineBreakMode];
    }
}

#pragma mark - override

- (void)css_onClickTapWithSender:(UIGestureRecognizer *)sender {
    CGPoint location = [sender locationInView:self];
#if TARGET_OS_OSX // [macOS NSWindow is not a subclass of NSView, use contentView
    CGPoint pageLocation = [sender locationInView:self.window.contentView];
#else
    CGPoint pageLocation = [self kr_convertLocalPointToRenderRoot:location];
#endif // macOS]
    self.css_click([self kr_richTextParamsWithLocation:location pageLocation:pageLocation extraParams:nil]);
}

- (void)css_onLongPressWithSender:(UILongPressGestureRecognizer *)sender {
    NSDictionary *config = @{
            @(UIGestureRecognizerStateBegan): @"start",
            @(UIGestureRecognizerStateChanged): @"move",
    };
    CGPoint location = [sender locationInView:self];
#if TARGET_OS_OSX
    CGPoint pageLocation = [sender locationInView:nil];
#else
    CGPoint pageLocation = [self kr_convertLocalPointToRenderRoot:location];
#endif
    NSDictionary *extraParams = @{
            @"state": config[@(sender.state)] ? : @"end",
            @"isCancel": @(sender.state == UIGestureRecognizerStateCancelled)
    };
    if (self.css_longPress) {
        self.css_longPress([self kr_richTextLongPressParamsWithLocation:location pageLocation:pageLocation extraParams:extraParams]);
    }
}

- (NSDictionary *)kr_richTextParamsWithLocation:(CGPoint)location pageLocation:(CGPoint)pageLocation extraParams:(NSDictionary *)extraParams {
    NSInteger spanIndex = [self kr_findSpanIndexWithLocation:location];
    NSMutableDictionary *params = [@{
         @"x": @(location.x),
         @"y": @(location.y),
         @"pageX": @(pageLocation.x),
         @"pageY": @(pageLocation.y),
         @"index": @(spanIndex),
    } mutableCopy];
    if (extraParams.count > 0) {
        [params addEntriesFromDictionary:extraParams];
    }
    return params;
}

- (NSDictionary *)kr_richTextLongPressParamsWithLocation:(CGPoint)location pageLocation:(CGPoint)pageLocation extraParams:(NSDictionary *)extraParams {
    NSInteger spanIndex = [self kr_resolveLongPressSpanIndexWithLocation:location extraParams:extraParams];
    NSMutableDictionary *params = [@{
            @"x": @(location.x),
            @"y": @(location.y),
            @"pageX": @(pageLocation.x),
            @"pageY": @(pageLocation.y),
            @"index": @(spanIndex),
    } mutableCopy];
    if (extraParams.count > 0) {
        [params addEntriesFromDictionary:extraParams];
    }
    if ([self kr_isLongPressTerminalState:extraParams]) {
        [self kr_clearActiveLongPressSpanIndex];
    }
    return params;
}

- (NSInteger)kr_resolveLongPressSpanIndexWithLocation:(CGPoint)location extraParams:(NSDictionary *)extraParams {
    NSString *state = extraParams[@"state"];
    if ([state isEqualToString:@"start"]) {
        self.activeLongPressSpanIndex = [self kr_findSpanIndexWithLocation:location];
    }
    return self.activeLongPressSpanIndex;
}

- (BOOL)kr_isLongPressTerminalState:(NSDictionary *)extraParams {
    if ([extraParams[@"isCancel"] boolValue]) {
        return YES;
    }
    NSString *state = extraParams[@"state"];
    return [state isEqualToString:@"end"];
}

- (void)kr_clearActiveLongPressSpanIndex {
    self.activeLongPressSpanIndex = -1;
}

- (NSInteger)kr_findSpanIndexWithLocation:(CGPoint)location {
    NSInteger charIndex = [self.attributedText.hr_textRender characterIndexForPoint:location];
    NSInteger textLength = (NSInteger)self.attributedText.length;
    for (NSInteger probe = charIndex; probe >= 0 && probe > charIndex - 2; probe--) {
        if (probe >= 0 && probe < textLength) {
            NSNumber *spanIndex = [self.attributedText attribute:KuiklyIndexAttributeName
                                                          atIndex:probe
                                                   effectiveRange:nil];
            if (spanIndex != nil) {
                return spanIndex.integerValue;
            }
        }
    }
    return -1;
}

- (void)setBackgroundColor:(UIColor *)backgroundColor
{
    [super setBackgroundColor:backgroundColor];
    // 背景颜色会影响shodow，这里更新下shadow
    [self setCss_boxShadow:self.css_boxShadow];
}

- (void)setCss_boxShadow:(NSString *)css_boxShadow
{
    // 背景色为clear时，会变成textShadow，这里和安卓对齐，统一由textShadow属性来控制
    if (self.backgroundColor != UIColor.clearColor) {
        [super setCss_boxShadow:css_boxShadow];
    }
}

@end

/// KRRichTextShadow
@interface KRRichTextShadow()

@end

@implementation KRRichTextShadow {
    NSMutableDictionary<NSString *, id> *_props;
    NSArray<NSDictionary *> * _spans;
    NSMutableAttributedString *_mAttributedString;
    NSMutableArray<NSDictionary *> *_pendingGradients; // 延迟应用的渐变信息（需等待布局完成后获取总尺寸）
}

#pragma mark - KuiklyRenderShadowProtocol

- (void)hrv_setPropWithKey:(NSString *)propKey propValue:(id)propValue {
    if (!_props) {
        _props = [[NSMutableDictionary alloc] init];
    }
    _props[propKey] = propValue;
}


- (CGSize)hrv_calculateRenderViewSizeWithConstraintSize:(CGSize)constraintSize {
    _mAttributedString = [self p_buildAttributedString];

    CGFloat height = constraintSize.height > 0 ? constraintSize.height : MAXFLOAT;
    NSInteger numberOfLines = [KRConvertUtil NSInteger:_props[@"numberOfLines"]];
    NSLineBreakMode lineBreakMode = [KRConvertUtil NSLineBreakMode:_props[@"lineBreakMode"]];
    CGFloat lineBreakMargin = [KRConvertUtil CGFloat:_props[@"lineBreakMargin"]];
    CGFloat lineHeight = [KRConvertUtil CGFloat:_props[@"lineHeight"]];
    CGSize fitSize = [KRLabel sizeThatFits:CGSizeMake(constraintSize.width, height) attributedString:_mAttributedString numberOfLines:numberOfLines lineBreakMode:lineBreakMode lineBreakMarin:lineBreakMargin lineHeight:lineHeight];

    // 渐变色延迟应用：需在布局完成后获取总尺寸，才能绘制跨行连续的渐变效果
    if (_pendingGradients.count > 0) {
        for (NSDictionary *gradientInfo in _pendingGradients) {
            NSString *cssGradient = gradientInfo[kGradientInfoKeyCSSGradient];
            UIFont *font = gradientInfo[kGradientInfoKeyFont];
            NSRange globalRange = [gradientInfo[kGradientInfoKeyGlobalRange] rangeValue];

            [TextGradientHandler applyGlobalGradientToAttributedString:_mAttributedString
                                                                 range:globalRange
                                                           cssGradient:cssGradient
                                                                  font:font
                                                        totalLayoutSize:fitSize];
        }

        // 渐变应用后重建 TextRender
        NSTextStorage *textStorage = [[NSTextStorage alloc] initWithAttributedString:_mAttributedString];
        textStorage.hr_hasAttachmentViews = _mAttributedString.hr_hasAttachmentViews;
        KRTextRender *textRender = [[KRTextRender alloc] initWithTextStorage:textStorage lineHeight:lineHeight];
        textRender.lineBreakMargin = lineBreakMargin;
        textRender.maximumNumberOfLines = numberOfLines;
        textRender.lineBreakMode = lineBreakMode;
        
        if (lineBreakMargin > 0 && numberOfLines) {
            textRender.maximumNumberOfLines = 0;
            CGSize newSize = [textRender textSizeWithRenderWidth:constraintSize.width];
            textRender.isBreakLine = !CGSizeEqualToSize(fitSize, newSize);
            textRender.maximumNumberOfLines = numberOfLines;
        }
        _mAttributedString.hr_textRender = textRender;
        _mAttributedString.hr_size = fitSize;
        [_pendingGradients removeAllObjects];
    }

    return fitSize;
}

- (NSString *)hrv_callWithMethod:(NSString *)method params:(NSString *)params {
    if ([method isEqualToString:@"spanRect"]) { // span所在的排版位置坐标
        return [self css_spanRectWithParams:params];
    } else if ([method isEqualToString:@"isLineBreakMargin"]) {
        return [self isLineBreakMargin];
    }
    return @"";
}

- (dispatch_block_t)hrv_taskToMainQueueWhenWillSetShadowToView {
    __weak typeof(self) weakSelf = self;
    NSMutableAttributedString *attrString = _mAttributedString;
    return ^{
        weakSelf.attributedString = attrString;
    };
}

#pragma mark - public

- (NSAttributedString *)buildAttributedString {
    return [self p_buildAttributedString];
}

#pragma mark - private

- (NSMutableAttributedString *)p_buildAttributedString {
    NSArray *spans = [KRConvertUtil hr_arrayWithJSONString:_props[@"values"]];
    if (!spans.count) {
        spans = @[_props ? : @{}];
    }
    _spans = spans;
    _pendingGradients = [NSMutableArray new];
    NSString *textPostProcessor = nil;
    NSMutableArray *richAttrArray = [NSMutableArray new];
    UIFont *mainFont = nil;
    for (NSInteger spanIndex = 0; spanIndex < spans.count; spanIndex++) {
        NSMutableDictionary *span = spans[spanIndex];
        if ([span[@"inlineBoxChildren"] isKindOfClass:[NSArray class]]) {
            NSAttributedString *group = [self p_createInlineBoxGroupAttributedStringWithSpan:span
                                                                                   spanIndex:spanIndex];
            if (group.length > 0) {
                [richAttrArray addObject:group];
            }
            continue;
        }
        if (span[@"placeholderWidth"]) { // 属于占位span
            NSAttributedString *placeholderSpanAttributedString = [self p_createPlaceholderSpanAttributedStringWithSpan:span];
            [richAttrArray addObject:placeholderSpanAttributedString];
            continue;
        }

        NSString *text = span[@"value"] ?: span[@"text"];
        if (!text.length) {
            continue;
        }
        NSMutableDictionary *propStyle = [(_props ? : @{}) mutableCopy];
        [propStyle addEntriesFromDictionary:span];

        // 批量解析与字体相关的属性
        UIFont *font = [KRConvertUtil UIFont:propStyle];
        UIColor * color = [UIView css_color:propStyle[@"color"]] ?: [UIColor blackColor];
        UIColor *backgroundColor = [UIView css_color:span[@"backgroundColor"]];
        NSString *cssGricent = propStyle[@"backgroundImage"];
        BOOL hasGradient = NO;
        if (cssGricent && [cssGricent hasPrefix:@"linear-gradient("]) {
            hasGradient = YES;
        }

        CGFloat letterSpacing = [KRConvertUtil CGFloat:propStyle[@"letterSpacing"]];
        KRTextDecorationLineType textDecoration = [KRConvertUtil KRTextDecorationLineType:propStyle[@"textDecoration"]];
        UIColor *textDecorationColor = [UIView css_color:propStyle[@"textDecorationColor"]];
        NSNumber *textDecorationThickness = propStyle[@"textDecorationThickness"] ? @([KRConvertUtil CGFloat:propStyle[@"textDecorationThickness"]]) : nil;
        NSNumber *textDecorationOffset = propStyle[@"textDecorationOffset"] ? @([KRConvertUtil CGFloat:propStyle[@"textDecorationOffset"]]) : nil;
        NSTextAlignment textAlign = [KRConvertUtil NSTextAlignment:propStyle[@"textAlign"]];
        NSNumber *lineHeight = nil;
        NSNumber *lineSpacing = nil;
        NSNumber *paragraphSpacing = propStyle[@"paragraphSpacing"] ? @([KRConvertUtil CGFloat:propStyle[@"paragraphSpacing"]]) : nil;
        if (propStyle[@"lineHeight"]) {
            lineHeight = @([KRConvertUtil CGFloat:propStyle[@"lineHeight"]]);
        } else {
            lineSpacing = @([KRConvertUtil CGFloat:propStyle[@"lineSpacing"]]);
        }
        CGFloat headIndent = [KRConvertUtil CGFloat:propStyle[@"headIndent"]];
        UIColor *strokeColor = [UIView css_color:propStyle[@"strokeColor"]];
        CGFloat strokeWidth = [KRConvertUtil CGFloat:propStyle[@"strokeWidth"]];
        NSShadow *textShadow = nil;
        NSString *cssTextShadow = propStyle[@"textShadow"];
        if ([cssTextShadow isKindOfClass:[NSString class]] && cssTextShadow.length > 0) {
            CSSBoxShadow *shadow = [[CSSBoxShadow alloc] initWithCSSBoxShadow:cssTextShadow];

            textShadow = [NSShadow new];
            textShadow.shadowColor = shadow.shadowColor;
            textShadow.shadowOffset = CGSizeMake(shadow.offsetX, shadow.offsetY);
            textShadow.shadowBlurRadius = shadow.shadowRadius;
        }
        if (propStyle[@"textPostProcessor"]) {
            textPostProcessor = propStyle[@"textPostProcessor"];
        }

        if (!mainFont) {
            mainFont = font;
        }
        if ([textPostProcessor isKindOfClass:[NSString class]] && textPostProcessor.length) {
            // 代理
            if ([[KuiklyRenderBridge componentExpandHandler] respondsToSelector:@selector(kr_customTextWithText:textPostProcessor:)]) {
                text = [[KuiklyRenderBridge componentExpandHandler] kr_customTextWithText:text textPostProcessor:textPostProcessor];
            }
        }

        // 创建 Span 属性对象
        KRSpanAttributes *spanAttrs = [[KRSpanAttributes alloc] init];
        spanAttrs.text = text;
        spanAttrs.spanIndex = spanIndex;
        spanAttrs.font = font;
        spanAttrs.color = color;
        spanAttrs.backgroundColor = backgroundColor;
        spanAttrs.hasGradient = hasGradient;
        spanAttrs.cssGradient = cssGricent;
        spanAttrs.letterSpacing = letterSpacing;
        spanAttrs.textDecoration = textDecoration;
        spanAttrs.textDecorationColor = textDecorationColor;
        spanAttrs.textDecorationThickness = textDecorationThickness;
        spanAttrs.textDecorationOffset = textDecorationOffset;
        spanAttrs.textAlign = textAlign;
        spanAttrs.lineSpacing = lineSpacing;
        spanAttrs.lineHeight = lineHeight;
        spanAttrs.paragraphSpacing = paragraphSpacing;
        spanAttrs.headIndent = headIndent;
        spanAttrs.strokeColor = strokeColor;
        spanAttrs.strokeWidth = strokeWidth;
        spanAttrs.shadow = textShadow;
        spanAttrs.richAttrArray = richAttrArray;
        if (propStyle[@"slockInlineCode"]) {
            spanAttrs.slockChrome = @"inlineCode";
        }
        BOOL hasInlineBoxStyle = propStyle[@"inlineBoxBackgroundColor"] ||
            propStyle[@"inlineBoxBorderColor"] || propStyle[@"inlineBoxBorderWidth"] ||
            propStyle[@"inlineBoxPaddingStart"] || propStyle[@"inlineBoxPaddingEnd"] ||
            propStyle[@"inlineBoxPaddingTop"] || propStyle[@"inlineBoxPaddingBottom"] ||
            propStyle[@"inlineBoxMarginStart"] || propStyle[@"inlineBoxMarginEnd"] ||
            propStyle[@"inlineBoxCornerRadius"];
        if (hasInlineBoxStyle) {
            NSMutableDictionary<NSString *, id> *box = [NSMutableDictionary new];
            UIColor *boxBackground = [UIView css_color:propStyle[@"inlineBoxBackgroundColor"]];
            UIColor *boxBorder = [UIView css_color:propStyle[@"inlineBoxBorderColor"]];
            if (boxBackground) box[@"backgroundColor"] = boxBackground;
            if (boxBorder) box[@"borderColor"] = boxBorder;
            box[@"borderWidth"] = @([KRConvertUtil CGFloat:propStyle[@"inlineBoxBorderWidth"]]);
            box[@"paddingStart"] = @([KRConvertUtil CGFloat:propStyle[@"inlineBoxPaddingStart"]]);
            box[@"paddingEnd"] = @([KRConvertUtil CGFloat:propStyle[@"inlineBoxPaddingEnd"]]);
            box[@"paddingTop"] = @([KRConvertUtil CGFloat:propStyle[@"inlineBoxPaddingTop"]]);
            box[@"paddingBottom"] = @([KRConvertUtil CGFloat:propStyle[@"inlineBoxPaddingBottom"]]);
            box[@"marginStart"] = @([KRConvertUtil CGFloat:propStyle[@"inlineBoxMarginStart"]]);
            box[@"marginEnd"] = @([KRConvertUtil CGFloat:propStyle[@"inlineBoxMarginEnd"]]);
            box[@"cornerRadius"] = @([KRConvertUtil CGFloat:propStyle[@"inlineBoxCornerRadius"]]);
            spanAttrs.inlineBoxStyle = box;
        }
        // 组合属性，生成这段Span对应的富文本
        NSMutableAttributedString *spanAttrString = [self p_createSpanAttributedStringWithAttributes:spanAttrs];
        if (spanAttrString) {
            [richAttrArray addObject:spanAttrString];
        }
    }

    NSMutableAttributedString *resAttr = [[NSMutableAttributedString alloc] init];
    for (NSAttributedString *attr in richAttrArray) {
        [resAttr appendAttributedString:attr];
    }
    if ([textPostProcessor isKindOfClass:[NSString class]] && textPostProcessor.length) {
        // 代理
        if ([[KuiklyRenderBridge componentExpandHandler] respondsToSelector:@selector(kr_customTextWithAttributedString:font:textPostProcessor:)]) {
            resAttr = [[KuiklyRenderBridge componentExpandHandler] kr_customTextWithAttributedString:resAttr font:mainFont textPostProcessor:textPostProcessor];
        }
    }
    if ([textPostProcessor isKindOfClass:[NSString class]] && textPostProcessor.length) {
        // 代理
        if ([[KuiklyRenderBridge componentExpandHandler] respondsToSelector:@selector(hr_customTextWithAttributedString:textPostProcessor:)]) {
            resAttr = [[KuiklyRenderBridge componentExpandHandler] hr_customTextWithAttributedString:resAttr textPostProcessor:textPostProcessor];
        }
    }
    return resAttr;
}

- (NSMutableDictionary<NSString *, id> *)p_inlineBoxStyleFromSpan:(NSDictionary *)span {
    NSMutableDictionary<NSString *, id> *box = [NSMutableDictionary new];
    UIColor *background = [UIView css_color:span[@"inlineBoxBackgroundColor"]];
    UIColor *border = [UIView css_color:span[@"inlineBoxBorderColor"]];
    if (background) box[@"backgroundColor"] = background;
    if (border) box[@"borderColor"] = border;
    box[@"borderWidth"] = @([KRConvertUtil CGFloat:span[@"inlineBoxBorderWidth"]]);
    box[@"paddingStart"] = @([KRConvertUtil CGFloat:span[@"inlineBoxPaddingStart"]]);
    box[@"paddingEnd"] = @([KRConvertUtil CGFloat:span[@"inlineBoxPaddingEnd"]]);
    box[@"paddingTop"] = @([KRConvertUtil CGFloat:span[@"inlineBoxPaddingTop"]]);
    box[@"paddingBottom"] = @([KRConvertUtil CGFloat:span[@"inlineBoxPaddingBottom"]]);
    box[@"marginStart"] = @([KRConvertUtil CGFloat:span[@"inlineBoxMarginStart"]]);
    box[@"marginEnd"] = @([KRConvertUtil CGFloat:span[@"inlineBoxMarginEnd"]]);
    box[@"cornerRadius"] = @([KRConvertUtil CGFloat:span[@"inlineBoxCornerRadius"]]);
    return box;
}

- (NSString *)p_inlineBoxLayoutText:(NSString *)text {
    if (text.length < 2) return text;
    NSMutableString *joined = [NSMutableString string];
    __block BOOL first = YES;
    [text enumerateSubstringsInRange:NSMakeRange(0, text.length)
                             options:NSStringEnumerationByComposedCharacterSequences
                          usingBlock:^(NSString *substring, NSRange substringRange, NSRange enclosingRange, BOOL *stop) {
        if (!first) [joined appendString:@"\u2060"];
        [joined appendString:substring];
        first = NO;
    }];
    return joined;
}

- (NSMutableAttributedString *)p_createInlineBoxGroupAttributedStringWithSpan:(NSMutableDictionary *)span
                                                                      spanIndex:(NSInteger)spanIndex {
    NSArray<NSMutableDictionary *> *children = span[@"inlineBoxChildren"];
    if (children.count == 0) return [NSMutableAttributedString new];
    NSString *semantic = span[@"inlineBoxSemanticText"];
    BOOL tightenNumericReference =
        [semantic isKindOfClass:[NSString class]] && KRIsNumericReferenceInlineBox(semantic);
    NSMutableDictionary<NSString *, id> *style = [self p_inlineBoxStyleFromSpan:span];
    NSMutableDictionary *base = [(_props ?: @{}) mutableCopy];
    UIFont *baseFont = [KRConvertUtil UIFont:base] ?: [UIFont systemFontOfSize:15.0];
    CGFloat maxContentHeight = baseFont.lineHeight;
    for (NSDictionary *child in children) {
        if (child[@"placeholderHeight"]) {
            maxContentHeight = MAX(maxContentHeight, [KRConvertUtil CGFloat:child[@"placeholderHeight"]]);
            continue;
        }
        NSMutableDictionary *childStyle = [base mutableCopy];
        [childStyle addEntriesFromDictionary:child];
        UIFont *childFont = [KRConvertUtil UIFont:childStyle];
        if (childFont.lineHeight > maxContentHeight) {
            maxContentHeight = childFont.lineHeight;
            baseFont = childFont;
        }
    }
    CGFloat borderWidth = [style[@"borderWidth"] doubleValue];
    CGFloat leadingAdvance = [style[@"marginStart"] doubleValue] + borderWidth + [style[@"paddingStart"] doubleValue];
    CGFloat trailingAdvance = [style[@"paddingEnd"] doubleValue] + borderWidth + [style[@"marginEnd"] doubleValue];
    CGFloat paddingTop = [style[@"paddingTop"] doubleValue];
    CGFloat paddingBottom = [style[@"paddingBottom"] doubleValue];
    style[@"boxHeight"] = @(maxContentHeight + paddingTop + paddingBottom + borderWidth * 2.0);

    NSMutableAttributedString *group = [NSMutableAttributedString new];
    KRInlineBoxEdgeAttachment *leading = [[KRInlineBoxEdgeAttachment alloc]
        initWithAdvance:leadingAdvance
                    font:baseFont
              paddingTop:paddingTop
           paddingBottom:paddingBottom
             borderWidth:borderWidth];
    [group appendAttributedString:[NSAttributedString attributedStringWithAttachment:leading]];

    NSMutableString *visibleText = [NSMutableString string];
    NSUInteger visibleStart = NSNotFound;
    NSUInteger visibleEnd = NSNotFound;
    BOOL hasPlaceholder = NO;
    for (NSUInteger childIndex = 0; childIndex < children.count; childIndex++) {
        NSMutableDictionary *child = children[childIndex];
        [group appendAttributedString:[[NSAttributedString alloc] initWithString:@"\u2060"]];
        if (child[@"placeholderWidth"] || child[@"placeholderHeight"]) {
            hasPlaceholder = YES;
            [group appendAttributedString:[self p_createPlaceholderSpanAttributedStringWithSpan:child]];
            continue;
        }
        NSString *text = child[@"value"] ?: child[@"text"];
        if (text.length == 0) continue;
        [visibleText appendString:text];
        NSMutableDictionary *propStyle = [base mutableCopy];
        [propStyle addEntriesFromDictionary:child];
        KRSpanAttributes *attrs = [KRSpanAttributes new];
        // Treat an explicit inline-box group as one native word when it fits. TextKit
        // otherwise considers punctuation such as '-' a preferred break point and
        // fragments a group even though the complete group fits on the next line.
        // U+2060 is layout-only: group semantic text remains authoritative for
        // selection/copy/accessibility and KRLabel strips the glue on restoration.
        attrs.text = [self p_inlineBoxLayoutText:text];
        attrs.spanIndex = spanIndex;
        attrs.font = [KRConvertUtil UIFont:propStyle];
        attrs.color = [UIView css_color:propStyle[@"color"]] ?: [UIColor blackColor];
        attrs.backgroundColor = [UIView css_color:child[@"backgroundColor"]];
        NSString *cssGradient = propStyle[@"backgroundImage"];
        attrs.hasGradient = [cssGradient isKindOfClass:[NSString class]] && [cssGradient hasPrefix:@"linear-gradient("];
        attrs.cssGradient = cssGradient;
        attrs.letterSpacing = [KRConvertUtil CGFloat:propStyle[@"letterSpacing"]];
        attrs.textDecoration = [KRConvertUtil KRTextDecorationLineType:propStyle[@"textDecoration"]];
        attrs.textDecorationColor = [UIView css_color:propStyle[@"textDecorationColor"]];
        attrs.textDecorationThickness = propStyle[@"textDecorationThickness"] ? @([KRConvertUtil CGFloat:propStyle[@"textDecorationThickness"]]) : nil;
        attrs.textDecorationOffset = propStyle[@"textDecorationOffset"] ? @([KRConvertUtil CGFloat:propStyle[@"textDecorationOffset"]]) : nil;
        attrs.textAlign = [KRConvertUtil NSTextAlignment:propStyle[@"textAlign"]];
        attrs.lineHeight = propStyle[@"lineHeight"] ? @([KRConvertUtil CGFloat:propStyle[@"lineHeight"]]) : nil;
        attrs.lineSpacing = attrs.lineHeight ? nil : @([KRConvertUtil CGFloat:propStyle[@"lineSpacing"]]);
        attrs.paragraphSpacing = propStyle[@"paragraphSpacing"] ? @([KRConvertUtil CGFloat:propStyle[@"paragraphSpacing"]]) : nil;
        attrs.headIndent = [KRConvertUtil CGFloat:propStyle[@"headIndent"]];
        attrs.strokeColor = [UIView css_color:propStyle[@"strokeColor"]];
        attrs.strokeWidth = [KRConvertUtil CGFloat:propStyle[@"strokeWidth"]];
        NSString *cssTextShadow = propStyle[@"textShadow"];
        if ([cssTextShadow isKindOfClass:[NSString class]] && cssTextShadow.length > 0) {
            CSSBoxShadow *shadow = [[CSSBoxShadow alloc] initWithCSSBoxShadow:cssTextShadow];
            NSShadow *textShadow = [NSShadow new];
            textShadow.shadowColor = shadow.shadowColor;
            textShadow.shadowOffset = CGSizeMake(shadow.offsetX, shadow.offsetY);
            textShadow.shadowBlurRadius = shadow.shadowRadius;
            attrs.shadow = textShadow;
        }
        attrs.richAttrArray = @[];
        NSMutableAttributedString *childString = [self p_createSpanAttributedStringWithAttributes:attrs];
        if (childString.length > 0) {
            if (visibleStart == NSNotFound) {
                visibleStart = group.length;
            }
            [group appendAttributedString:childString];
            visibleEnd = group.length;
        }
    }
    [group appendAttributedString:[[NSAttributedString alloc] initWithString:@"\u2060"]];
    KRInlineBoxEdgeAttachment *trailing = [[KRInlineBoxEdgeAttachment alloc]
        initWithAdvance:trailingAdvance
                    font:baseFont
              paddingTop:paddingTop
           paddingBottom:paddingBottom
             borderWidth:borderWidth];
    [group appendAttributedString:[NSAttributedString attributedStringWithAttachment:trailing]];
    if (tightenNumericReference && !hasPlaceholder && [visibleText isEqualToString:semantic] &&
        visibleStart != NSNotFound && visibleEnd > visibleStart) {
        NSRange visibleRange = NSMakeRange(visibleStart, visibleEnd - visibleStart);
        CGFloat compensation = KRInlineBoxTrailingCavityCompensation(group, visibleRange);
        CGFloat adjustedTrailingAdvance = MAX(0, trailingAdvance - compensation);
        if (adjustedTrailingAdvance < trailingAdvance) {
            KRInlineBoxEdgeAttachment *adjustedTrailing = [[KRInlineBoxEdgeAttachment alloc]
                initWithAdvance:adjustedTrailingAdvance
                            font:baseFont
                      paddingTop:paddingTop
                   paddingBottom:paddingBottom
                     borderWidth:borderWidth];
            [group replaceCharactersInRange:NSMakeRange(group.length - 1, 1)
                        withAttributedString:[NSAttributedString attributedStringWithAttachment:adjustedTrailing]];
        }
    }

    NSRange range = NSMakeRange(0, group.length);
    [group addAttribute:KRInlineBoxStyleAttributeName value:style range:range];
    if ([semantic isKindOfClass:[NSString class]] && semantic.length > 0) {
        [group addAttribute:KRInlineBoxSemanticAttributeName value:semantic range:range];
    }
    [group addAttribute:KuiklyIndexAttributeName value:@(spanIndex) range:range];
    return group;
}

- (nullable NSMutableAttributedString *)p_createSlockInlineCodeAtomChainWithAttributes:(KRSpanAttributes *)attrs {
    if (attrs.text.length == 0) {
        return nil;
    }
    NSMutableArray<NSString *> *graphemes = [NSMutableArray new];
    [attrs.text enumerateSubstringsInRange:NSMakeRange(0, attrs.text.length)
                                   options:NSStringEnumerationByComposedCharacterSequences
                                usingBlock:^(NSString *substring, NSRange substringRange, NSRange enclosingRange, BOOL *stop) {
        if (substring.length > 0 && ![substring isEqualToString:@"\u200B"]) {
            [graphemes addObject:substring];
        }
    }];
    if (graphemes.count == 0) {
        return nil;
    }
    NSArray<NSString *> *atoms = graphemes.count <= kKRSlockInlineCodeAtomizeThreshold
        ? @[ [graphemes componentsJoinedByString:@""] ]
        : graphemes;

    NSMutableAttributedString *chain = [[NSMutableAttributedString alloc] init];
    [atoms enumerateObjectsUsingBlock:^(NSString *atomText, NSUInteger atomIndex, BOOL *stop) {
        BOOL leadingEdge = atomIndex == 0;
        BOOL trailingEdge = atomIndex == atoms.count - 1;
        KRSlockInlineCodeAtomAttachment *attachment = [[KRSlockInlineCodeAtomAttachment alloc]
            initWithText:atomText
                    font:attrs.font
               textColor:attrs.color
            letterSpacing:attrs.letterSpacing
             leadingEdge:leadingEdge
            trailingEdge:trailingEdge];
        NSMutableAttributedString *atom = [[NSMutableAttributedString alloc]
            initWithAttributedString:[NSAttributedString attributedStringWithAttachment:attachment]];
        NSRange atomRange = NSMakeRange(0, atom.length);
        [atom addAttribute:NSWritingDirectionAttributeName
                    value:@[@((NSInteger)NSWritingDirectionLeftToRight | (NSInteger)NSWritingDirectionOverride)]
                    range:atomRange];
        [atom addAttribute:NSFontAttributeName value:attrs.font ?: [UIFont systemFontOfSize:15.0] range:atomRange];
        [atom addAttribute:KRSlockChromeAttributeName value:@"inlineCode" range:atomRange];
        [atom addAttribute:KuiklyIndexAttributeName value:@(attrs.spanIndex) range:atomRange];
        [chain appendAttributedString:atom];
    }];
    NSRange chainRange = NSMakeRange(0, chain.length);
    [self p_applyTextAttributeWithAttr:chain
                            textAliment:attrs.textAlign
                           lineSpacing:attrs.lineSpacing
                      paragraphSpacing:attrs.paragraphSpacing
                            lineHeight:attrs.lineHeight
                                 range:chainRange
                              fontSize:attrs.font.pointSize
                            headIndent:attrs.headIndent
                                  font:attrs.font ?: [UIFont systemFontOfSize:15.0]];
    return chain;
}


- (nullable NSMutableAttributedString *)p_createSpanAttributedStringWithAttributes:(KRSpanAttributes *)attrs {
    if (attrs.inlineBoxStyle && attrs.text.length > 0) {
        KRInlineBoxAttachment *attachment = [[KRInlineBoxAttachment alloc]
            initWithText:attrs.text
                    font:attrs.font
               textColor:attrs.color
                   style:attrs.inlineBoxStyle
           letterSpacing:attrs.letterSpacing];
        NSMutableAttributedString *atomicBox = [[NSMutableAttributedString alloc]
            initWithAttributedString:[NSAttributedString attributedStringWithAttachment:attachment]];
        NSRange atomicRange = NSMakeRange(0, atomicBox.length);
        [atomicBox addAttribute:NSWritingDirectionAttributeName
                         value:@[@((NSInteger)NSWritingDirectionLeftToRight | (NSInteger)NSWritingDirectionOverride)]
                         range:atomicRange];
        [atomicBox addAttribute:NSFontAttributeName value:attrs.font ?: [UIFont systemFontOfSize:15.0] range:atomicRange];
        [atomicBox addAttribute:KuiklyIndexAttributeName value:@(attrs.spanIndex) range:atomicRange];
        [self p_applyTextAttributeWithAttr:atomicBox
                                textAliment:attrs.textAlign
                               lineSpacing:attrs.lineSpacing
                          paragraphSpacing:attrs.paragraphSpacing
                                lineHeight:attrs.lineHeight
                                     range:atomicRange
                                  fontSize:attrs.font.pointSize
                                headIndent:attrs.headIndent
                                      font:attrs.font ?: [UIFont systemFontOfSize:15.0]];
        return atomicBox;
    }
    if ([attrs.slockChrome isEqualToString:@"inlineCode"] && attrs.text.length > 0) {
        return [self p_createSlockInlineCodeAtomChainWithAttributes:attrs];
    }
    NSMutableAttributedString *attributedString = [[NSMutableAttributedString alloc] initWithString:attrs.text attributes:@{}];
    NSRange range = NSMakeRange(0, attributedString.length);

    // 设置字体
    if (attrs.font) {
        [attributedString addAttribute:NSFontAttributeName value:attrs.font range:range];
    }
    
    // 渐变色：先用纯色占位，记录渐变信息到 _pendingGradients，布局完成后统一应用
    if (attrs.hasGradient && attrs.cssGradient && attrs.font) {
        [attributedString addAttribute:NSForegroundColorAttributeName value:attrs.color range:range];

        NSUInteger currentLength = 0;
        for (NSAttributedString *attr in attrs.richAttrArray) {
            currentLength += attr.length;
        }

        NSDictionary *gradientInfo = @{
            kGradientInfoKeyCSSGradient: attrs.cssGradient,
            kGradientInfoKeyFont: attrs.font,
            kGradientInfoKeyGlobalRange: [NSValue valueWithRange:NSMakeRange(currentLength, attrs.text.length)]
        };
        [_pendingGradients addObject:gradientInfo];
    } else {
        [attributedString addAttribute:NSForegroundColorAttributeName value:attrs.color range:range];
    }

    // 强制使用LTR文本方向
    [attributedString addAttribute:NSWritingDirectionAttributeName value:@[@((NSInteger)NSWritingDirectionLeftToRight | (NSInteger)NSWritingDirectionOverride)] range:range];

    if (attrs.letterSpacing) {
        [attributedString addAttribute:NSKernAttributeName value:@(attrs.letterSpacing) range:range];
    }

    if (attrs.backgroundColor) {
        [attributedString addAttribute:NSBackgroundColorAttributeName value:attrs.backgroundColor range:range];
    }

    if (attrs.textDecoration == KRTextDecorationLineTypeUnderline) {
        NSUnderlineStyle underlineStyle = attrs.textDecorationThickness ? NSUnderlineStyleThick : NSUnderlineStyleSingle;
        [attributedString addAttribute:NSUnderlineStyleAttributeName value:@(underlineStyle) range:range];
        if (attrs.textDecorationColor) {
            [attributedString addAttribute:NSUnderlineColorAttributeName value:attrs.textDecorationColor range:range];
        }
    }
    if (attrs.textDecoration == KRTextDecorationLineTypeStrikethrough) {
        [attributedString addAttribute:NSStrikethroughStyleAttributeName value:@(NSUnderlineStyleSingle) range:range];
    }

    [self p_applyTextAttributeWithAttr:attributedString
                            textAliment:attrs.textAlign
                           lineSpacing:attrs.lineSpacing
                      paragraphSpacing:attrs.paragraphSpacing
                            lineHeight:attrs.lineHeight
                                 range:range
                              fontSize:attrs.font.pointSize
                            headIndent:attrs.headIndent
                                  font:attrs.font];

    if (attrs.strokeColor) {
        [attributedString addAttribute:NSStrokeColorAttributeName value:attrs.strokeColor range:range];
        NSNumber *width = _strokeAndFill ? @(-attrs.strokeWidth) : @(attrs.strokeWidth);
        [attributedString addAttribute:NSStrokeWidthAttributeName value:width range:range];
    }

    [attributedString addAttribute:KuiklyIndexAttributeName value:@(attrs.spanIndex) range:range];

    if (attrs.shadow) {
        [attributedString addAttribute:NSShadowAttributeName value:attrs.shadow range:range];
    }

    return attributedString;
}

- (NSAttributedString *)p_createPlaceholderSpanAttributedStringWithSpan:(NSMutableDictionary *)span {
    KRRichTextAttachment *attachment = [[KRRichTextAttachment alloc] init];
    CGFloat height = [span[@"placeholderHeight"] doubleValue];
    CGFloat width = [span[@"placeholderWidth"] doubleValue];
    NSMutableDictionary *propStyle = [(_props ? : @{}) mutableCopy];
    [propStyle addEntriesFromDictionary:span];
    if (!propStyle[@"fontSize"]) {
        for (NSDictionary * inSpan in _spans) {
            if (inSpan[@"fontSize"]) {
                [propStyle addEntriesFromDictionary:inSpan];
                break;
            }
        }
    }
    UIFont *font = [KRConvertUtil UIFont:propStyle];

    CGFloat lineHeight = [KRConvertUtil CGFloat:propStyle[@"lineHeight"]];
    if (lineHeight > 0) {
        attachment.offsetY = - font.descender;
    } else {
        attachment.offsetY = ( height - font.capHeight ) / 2.0;
    }

    attachment.bounds = CGRectMake(0, -attachment.offsetY, width, height);
    if ([span isKindOfClass:[NSMutableDictionary class]]) {
        ((NSMutableDictionary *)span)[@"attachment"] = attachment;
    }

    NSAttributedString *attrString = [NSAttributedString attributedStringWithAttachment:attachment];
    NSMutableAttributedString *mutableAttrString = [[NSMutableAttributedString alloc] initWithAttributedString:attrString];
    [mutableAttrString kr_addAttribute:NSWritingDirectionAttributeName value:@[@((NSInteger)NSWritingDirectionLeftToRight | (NSInteger)NSWritingDirectionOverride)] range:NSMakeRange(0, mutableAttrString.length)];
    return mutableAttrString;
}


- (void)p_applyTextAttributeWithAttr:(NSMutableAttributedString *)attributedString
                         textAliment:(NSTextAlignment)textAliment
                         lineSpacing:(NSNumber *)lineSpacing
                    paragraphSpacing: (NSNumber *)paragraphSpacing
                          lineHeight:(NSNumber *)lineHeight
                               range:(NSRange)range
                            fontSize:(CGFloat)fontSize
                          headIndent:(CGFloat)headIndent
                                font:(UIFont *)font {
    NSMutableParagraphStyle *style  = [[NSMutableParagraphStyle alloc] init];
    style.alignment = textAliment;
    // 强制使用LTR文本方向，确保文本始终从左到右显示
    style.baseWritingDirection = NSWritingDirectionLeftToRight;
    if (lineSpacing) {
         style.lineSpacing = ceil([lineSpacing floatValue]) ;
    }
    if (lineHeight) {
        style.maximumLineHeight = [lineHeight floatValue];
        style.minimumLineHeight = [lineHeight floatValue];
        CGFloat baselineOffset = ([lineHeight floatValue]  - font.pointSize) / 2;
        [attributedString addAttribute:NSBaselineOffsetAttributeName value:@(baselineOffset) range:range];
    }
    if (paragraphSpacing) {
        style.paragraphSpacing = ceil([paragraphSpacing floatValue]) ;
    }
    if (headIndent) {
        style.firstLineHeadIndent = headIndent;
    }
    [attributedString addAttribute:NSParagraphStyleAttributeName value:style range:range];
}

#pragma mark css - method
/*
 * 返回span所在的文本排版坐标
 */
- (NSString *)css_spanRectWithParams:(NSString *)params {
    if (!_mAttributedString) { // 文本还未排版，调用无效
        return @"";
    }
    NSArray<NSString *> *path = [params componentsSeparatedByString:@" "];
    NSInteger spanIndex = [path.firstObject integerValue];
    if (spanIndex < _spans.count ) {
        NSDictionary *span = _spans[spanIndex];
        NSDictionary *attachmentOwner = span;
        if (path.count > 1 && [span[@"inlineBoxChildren"] isKindOfClass:[NSArray class]]) {
            NSInteger childIndex = [path[1] integerValue];
            NSArray *children = span[@"inlineBoxChildren"];
            if (childIndex >= 0 && childIndex < children.count) {
                attachmentOwner = children[childIndex];
            }
        }
        KRRichTextAttachment *attachment = attachmentOwner[@"attachment"];
        if (!attachment) return @"";

        // 检查attachment是否在可见范围内
        NSInteger numberOfLines = [KRConvertUtil NSInteger:_props[@"numberOfLines"]];
        NSLayoutManager *layoutManager = _mAttributedString.hr_textRender.layoutManager;
        NSTextContainer *textContainer = _mAttributedString.hr_textRender.textContainer;

        if (numberOfLines > 0 && layoutManager && textContainer) {
            // 获取attachment对应的字形索引
            NSUInteger glyphIndex = [layoutManager glyphIndexForCharacterAtIndex:attachment.charIndex];
            // 获取截断的字形范围
            NSRange truncatedGlyphRange = [layoutManager truncatedGlyphRangeInLineFragmentForGlyphAtIndex:glyphIndex];

            // 如果有截断
            if (truncatedGlyphRange.location != NSNotFound && truncatedGlyphRange.length > 0) {
                // 判断attachment是否在截断范围内
                if (glyphIndex >= truncatedGlyphRange.location) {
                    return @"";
                }
            }
        }

        CGRect frame = [_mAttributedString.hr_textRender boundingRectForCharacterRange:NSMakeRange(attachment.charIndex, 1)];
        CGFloat offsetY = (CGRectGetHeight(frame) - attachment.bounds.size.height) / 2.0;
        return [NSString stringWithFormat:@"%.2lf %.2lf %.2lf %.2lf", CGRectGetMinX(frame), CGRectGetMinY(frame) + offsetY, attachment.bounds.size.width , attachment.bounds.size.height];
    }
    return @"";

}

- (NSString *)isLineBreakMargin {
    return _mAttributedString.hr_textRender.isBreakLine ? @"1" : @"0";
}


- (void)dealloc {

}

@end




@implementation KRRichTextAttachment


- (UIImage *)imageForBounds:(CGRect)imageBounds textContainer:(NSTextContainer *)textContainer characterIndex:(NSUInteger)charIndex {
    return nil;
}



- (CGRect)attachmentBoundsForTextContainer:(NSTextContainer *)textContainer proposedLineFragment:(CGRect)lineFrag glyphPosition:(CGPoint)position characterIndex:(NSUInteger)charIndex {
    _charIndex = charIndex;
    return CGRectMake(0, -self.offsetY, self.bounds.size.width, self.bounds.size.height);
}

@end

// Span属性参数对象实现
@implementation KRSpanAttributes

@end

// 文本渐变色处理类
@implementation TextGradientHandler

/// 将渐变色应用到富文本指定范围，使用总布局尺寸确保多行渐变连续
+ (void)applyGlobalGradientToAttributedString:(NSMutableAttributedString *)attributedString
                                         range:(NSRange)range
                                   cssGradient:(NSString *)cssGradient
                                          font:(UIFont *)font
                                totalLayoutSize:(CGSize)totalLayoutSize {
    CSSGradientInfo *gradientInfo = [self parseGradient:cssGradient];
    if (!gradientInfo) {
        return;
    }

    UIImage *gradientImage = [self createGradientImageWithInfo:gradientInfo size:totalLayoutSize];
    if (!gradientImage) {
        return;
    }

    UIColor *patternColor = [UIColor colorWithPatternImage:gradientImage];
    [attributedString addAttribute:NSForegroundColorAttributeName
                             value:patternColor
                             range:range];
}


/// 解析 CSS 渐变字符串，格式：linear-gradient(180deg, #FF0000 0%, #0000FF 100%)
+ (CSSGradientInfo *)parseGradient:(NSString *)cssGradient {
    NSString *lineargradientPrefix = @"linear-gradient(";
    if (![cssGradient hasPrefix:lineargradientPrefix] || cssGradient.length <= lineargradientPrefix.length) {
        return nil;
    }
    NSString *content = [cssGradient substringWithRange:NSMakeRange(lineargradientPrefix.length, cssGradient.length - lineargradientPrefix.length - 1)];
    NSArray<NSString *>* splits = [content componentsSeparatedByString:@","];
    
    if (splits.count < 3) {
        return nil;
    }

    CSSGradientInfo *info = [CSSGradientInfo new];
    info.direction = [splits.firstObject intValue];
    info.colors = [NSMutableArray array];
    info.locations = [NSMutableArray array];

    for (int i = 1; i < splits.count; i++) {
        NSString *colorStopStr = splits[i];
        NSArray<NSString *> *colorAndStop = [colorStopStr componentsSeparatedByString:@" "];
        UIColor *color = [UIView css_color:colorAndStop.firstObject];
        if (!color) {
            continue;
        }
        [info.colors addObject:color];
        CGFloat location = [colorAndStop.lastObject doubleValue];
        [info.locations addObject:@(location)];
    }
    
    if (info.colors.count < 2) {
        return nil;
    }

    return info;
}

// 根据渐变信息创建渐变图片
+ (UIImage *)createGradientImageWithInfo:(CSSGradientInfo *)info size:(CGSize)size {
    if (size.width <= 0 || size.height <= 0) {
        return nil;
    }
    
    // 在 Block 外部准备所有数据，避免 Block 内部访问 UIKit 对象导致的线程问题
    
    NSMutableArray *cgColors = [NSMutableArray arrayWithCapacity:info.colors.count];
    for (UIColor *color in info.colors) {
        [cgColors addObject:(__bridge id)(color.CGColor)];
    }
    
    NSUInteger locationsCount = info.locations.count;
    CGFloat *locations = (CGFloat *)malloc(sizeof(CGFloat) * locationsCount);
    if (!locations) {
        return nil;
    }
    for (NSUInteger i = 0; i < locationsCount; i++) {
        locations[i] = [info.locations[i] floatValue];
    }
    
    // 通过 CAGradientLayer 计算渐变方向对应的起点和终点
    CAGradientLayer *tempLayer = [CAGradientLayer layer];
    tempLayer.bounds = CGRectMake(0, 0, size.width, size.height);
    [KRConvertUtil hr_setStartPointAndEndPointWithLayer:tempLayer direction:info.direction];
    CGPoint startPoint = CGPointMake(tempLayer.startPoint.x * size.width,
                                     tempLayer.startPoint.y * size.height);
    CGPoint endPoint = CGPointMake(tempLayer.endPoint.x * size.width,
                                   tempLayer.endPoint.y * size.height);
    
#if TARGET_OS_OSX // [macOS
    KRUIGraphicsImageRenderer *renderer = [[KRUIGraphicsImageRenderer alloc] initWithSize:size];
    UIImage *image = [renderer imageWithActions:^(KRUIGraphicsImageRendererContext *rendererContext) {
        CGContextRef context = [rendererContext CGContext];
        CGContextTranslateCTM(context, 0, size.height);
        CGContextScaleCTM(context, 1.0, -1.0);
#else
    UIGraphicsImageRendererFormat *format = [[UIGraphicsImageRendererFormat alloc] init];
    format.scale = [UIScreen mainScreen].scale;
    format.opaque = NO;
    
    UIGraphicsImageRenderer *renderer = [[UIGraphicsImageRenderer alloc] initWithSize:size format:format];
    UIImage *image = [renderer imageWithActions:^(UIGraphicsImageRendererContext *rendererContext) {
        CGContextRef context = rendererContext.CGContext;
#endif // macOS]
        CGColorSpaceRef colorSpace = CGColorSpaceCreateDeviceRGB();
        CGGradientRef gradient = CGGradientCreateWithColors(colorSpace, (__bridge CFArrayRef)cgColors, locations);
        CGGradientDrawingOptions options = kCGGradientDrawsBeforeStartLocation | kCGGradientDrawsAfterEndLocation;
        CGContextDrawLinearGradient(context, gradient, startPoint, endPoint, options);
        CGGradientRelease(gradient);
        CGColorSpaceRelease(colorSpace);
    }];

    free(locations);
    return image;
}



@end


@implementation CSSGradientInfo

@end
