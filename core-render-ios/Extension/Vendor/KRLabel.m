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

#import "KRLabel.h"
#import <pthread.h>
#import <libkern/OSAtomic.h>
#import "KRAsyncDeallocManager.h"
#import <objc/runtime.h>
#import "NSObject+KR.h"
#import "KuiklyRenderBridge.h"

#define KRAssertMainThread() NSAssert(0 != pthread_main_np(), @"This method must be called on the main thread!")
NSString *const KRHighlightAttributeKey = @"KRHighlightAttributeKey";
NSString *const KRBGAttributeKey = @"KRBGAttributeKey";
NSString *const KRSlockChromeAttributeName = @"KRSlockChromeAttributeName";

#pragma mark - Slock rich-text chip chrome (task #439)

// TEMPORARY BRIDGE TO TASK #442. These constants mirror the Android drawer
// (core-render-android KRRichTextViewDrawer.kt) and the shared token source
// SlockRichTextChromeStyleTokens.* / SLOCK_RICHTEXT_INLINE_CODE_* (mobile PR #435,
// commit 5ffc5a044). #442 will serialize the resolved token fields into the span
// prop so both drawers read prop data and these baked constants are deleted
// (acceptance: fork grep finds no SLOCK constants). Do NOT let these become a new
// long-term source of truth.
// Fill colors: SlockRichTextChromeStyleTokens.InlineCode.chipFill etc. (ARGB).
static const uint32_t kKRSlockInlineCodeFillARGB   = 0x66FFD440; // react bg-soft-signal/40 = #FFD440 @ 40% (was 0x66FFD84D, the Android outlier — SlockMarkdown.kt:1485-90)
static const uint32_t kKRSlockChannelFillARGB      = 0x4DFE7DA8; // Channel.chipFill (pink @ 30%)
static const uint32_t kKRSlockThreadFillARGB       = 0x4D27CCF3; // Thread.chipFill (cyan @ 30%)
static const uint32_t kKRSlockTaskFillARGB         = 0x66FFD440; // Task.chipFill (yellow @ 40%)
static const uint32_t kKRSlockSelfMentionFillARGB  = 0xFFFFD440; // SelfMention.chipFill (opaque yellow)
// Geometry ratios × textSize: SLOCK_RICHTEXT_INLINE_CODE_EDGE_PADDING / _CHAR_WRAP_BREAK et al.
static const CGFloat kKRSlockHorizontalPaddingRatio = 4.0 / 15.0; // React MSG_REF_CHIP px-1 (≈4px @ 15pt)
static const CGFloat kKRSlockLineHeightRatio        = 1.5;        // React MSG_REF_CHIP leading-[1.5]
static const CGFloat kKRSlockBorderWidthPt          = 1.0;       // 1dp black border (border-black)

static UIColor *KRSlockChromeFillColor(NSString *chrome) {
    uint32_t argb;
    if ([chrome isEqualToString:@"inlineCode"]) {
        argb = kKRSlockInlineCodeFillARGB;
    } else if ([chrome isEqualToString:@"channel"]) {
        argb = kKRSlockChannelFillARGB;
    } else if ([chrome isEqualToString:@"thread"]) {
        argb = kKRSlockThreadFillARGB;
    } else if ([chrome isEqualToString:@"task"]) {
        argb = kKRSlockTaskFillARGB;
    } else if ([chrome isEqualToString:@"selfMention"] || [chrome isEqualToString:@"active"]) {
        argb = kKRSlockSelfMentionFillARGB;
    } else {
        // ordinaryMention (and any @other/@agent) renders as an underline via the
        // existing text SpanStyle, NOT a chip — no fill/border here.
        return nil;
    }
    CGFloat a = ((argb >> 24) & 0xFF) / 255.0;
    CGFloat r = ((argb >> 16) & 0xFF) / 255.0;
    CGFloat g = ((argb >> 8) & 0xFF) / 255.0;
    CGFloat b = (argb & 0xFF) / 255.0;
    return [UIColor colorWithRed:r green:g blue:b alpha:a];
}

static NSString *KRRestoredTextAttachmentString(NSAttributedString *attributedString) {
    if (attributedString.length == 0) {
        return @"";
    }
    NSMutableString *result = [NSMutableString string];
    __block NSUInteger cursor = 0;
    [attributedString enumerateAttribute:NSAttachmentAttributeName
                                  inRange:NSMakeRange(0, attributedString.length)
                                  options:0
                               usingBlock:^(id value, NSRange range, BOOL *stop) {
        if (range.location > cursor) {
            [result appendString:[attributedString.string substringWithRange:NSMakeRange(cursor, range.location - cursor)]];
        }
        if ([value respondsToSelector:@selector(kr_originlTextBeforeTextAttachment)]) {
            id<KRTextAttachmentStringProtocol> attachment = (id<KRTextAttachmentStringProtocol>)value;
            [result appendString:[attachment kr_originlTextBeforeTextAttachment] ?: @""];
        } else {
            [result appendString:[attributedString.string substringWithRange:range]];
        }
        cursor = NSMaxRange(range);
    }];
    if (cursor < attributedString.length) {
        [result appendString:[attributedString.string substringWithRange:NSMakeRange(cursor, attributedString.length - cursor)]];
    }
    return result;
}


@interface KRLabel()

@end

@implementation KRLabel


- (void)setSelectedRange:(NSRange)selectedRange {
    if (NSEqualRanges(_selectedRange, selectedRange)) {
        return;
    }
    _selectedRange = selectedRange;
    [self setNeedsDisplay];
}

- (void)setSelectionColor:(UIColor *)selectionColor {
    if (_selectionColor == selectionColor) {
        return;
    }
    _selectionColor = selectionColor;
    [self setNeedsDisplay];
}

#pragma mark - override

- (NSString *)accessibilityLabel{
    NSString * res = [super accessibilityLabel];
    if (res.length <= 0) {
        return KRRestoredTextAttachmentString(self.attributedText);
    }
    return res;
}


- (void)setAttributedText:(NSAttributedString *)attributedText {
    [super setAttributedText:attributedText];
    self.textRender = attributedText.hr_textRender;
    self.attributedText.hr_textRender = self.textRender;
    [self setNeedsDisplay];
}


- (void)drawTextInRect:(CGRect)rect {
    // 使用TextKit绘制文本
    self.textRender.size = rect.size;
    if (self.textRender.lineBreakMargin > 0 && self.textRender.isBreakLine) {
        CGSize size = self.textRender.size;
        UIBezierPath * bezierPath = [UIBezierPath bezierPathWithRect:CGRectMake(size.width - self.textRender.lineBreakMargin, size.height - 10, self.textRender.lineBreakMargin, 10)];
        self.textRender.textContainer.exclusionPaths = @[bezierPath];
    }
	
	// Draw selection background.
    if (self.selectedRange.length > 0 && self.selectedRange.location != NSNotFound && self.selectedRange.location + self.selectedRange.length <= self.textRender.textStorage.length) {
        if (!self.selectionColor) {
            self.selectionColor = [[UIColor colorWithRed:0x00/255.0 green:0x99/255.0 blue:0xff/255.0 alpha:1.0] colorWithAlphaComponent:0.3];
        }
        [self.selectionColor setFill];
        
        NSRange glyphRange = [self.textRender.layoutManager glyphRangeForCharacterRange:self.selectedRange actualCharacterRange:nil];
        [self.textRender.layoutManager enumerateEnclosingRectsForGlyphRange:glyphRange withinSelectedGlyphRange:glyphRange inTextContainer:self.textRender.textContainer usingBlock:^(CGRect r, BOOL * _Nonnull stop) {
             CGRect drawRect = CGRectOffset(r, rect.origin.x, rect.origin.y);
#if TARGET_OS_OSX
             CGContextRef context = UIGraphicsGetCurrentContext();
             if (context) {
                 CGContextFillRect(context, drawRect);
             }
#else
             UIRectFill(drawRect);
#endif
        }];
    }
    
    [self.textRender drawTextAtPoint:rect.origin isCanceled:nil];

}

- (void)setBackgroundColor:(UIColor *)backgroundColor {
    if (backgroundColor == nil) {
        backgroundColor = [UIColor clearColor];
    }
    [super setBackgroundColor:backgroundColor];
}


#pragma mark - public

+ (CGSize)sizeThatFits:(CGSize)size attributedString:(NSAttributedString *)attString numberOfLines:(NSUInteger)lines lineBreakMode:(NSLineBreakMode)mode{
    return [self sizeThatFits:size attributedString:attString numberOfLines:lines lineBreakMode:mode lineBreakMarin:0];
}

+ (CGSize)sizeThatFits:(CGSize)size attributedString:(NSAttributedString *)attString numberOfLines:(NSUInteger)lines lineBreakMode:(NSLineBreakMode)mode lineBreakMarin:(CGFloat)marin {
    return [self sizeThatFits:size attributedString:attString numberOfLines:lines lineBreakMode:mode lineBreakMarin:0 lineHeight:0];
}

+ (CGSize)sizeThatFits:(CGSize)size attributedString:(NSAttributedString *)attString numberOfLines:(NSUInteger)lines lineBreakMode:(NSLineBreakMode)mode lineBreakMarin:(CGFloat)marin lineHeight:(CGFloat)lineHeight {
    attString = [attString isKindOfClass:[NSAttributedString class]] ? attString : [[NSAttributedString alloc] initWithString:@""];
    NSTextStorage *textStorage = [[NSTextStorage alloc] initWithAttributedString:[attString copy]];
    textStorage.hr_hasAttachmentViews = attString.hr_hasAttachmentViews;
    KRTextRender *textRender = [[KRTextRender alloc] initWithTextStorage:textStorage lineHeight:lineHeight];
    textRender.lineBreakMargin = marin;
    textRender.maximumNumberOfLines = lines;
    textRender.lineBreakMode = mode;
    CGSize fitSize = [textRender textSizeWithRenderWidth:size.width];
    if (marin > 0 && lines) {
        textRender.maximumNumberOfLines = 0;
        CGSize newSize = [textRender textSizeWithRenderWidth:size.width];
        textRender.isBreakLine = !CGSizeEqualToSize(fitSize, newSize);
        textRender.maximumNumberOfLines = lines;//复原
    }
    
    // Fix the issue of missing ellipsis caused by hard line breaks (aligned with Android/HarmonyOS)
    // Remove this logic if Apple fixes this issue in the future.
    BOOL didModify = [self kr_fixEllipsisIfNeededForTextStorage:textStorage textRender:textRender lines:lines mode:mode fitSize:fitSize];
    if (didModify) {
        // Recalculate fitSize after modifying textStorage
        fitSize = [textRender textSizeWithRenderWidth:size.width];
    }
    
    attString.hr_textRender = textRender;
    attString.hr_size = fitSize;
    
    return fitSize;
}

/// Fix the issue where iOS TextKit does not display ellipsis after hard line breaks
/// When text is truncated by line count limit,
/// but the last line ends with \n instead of width overflow, the system does not add ellipsis.
/// This method detects this situation and manually adds ellipsis to align with Android/HarmonyOS behavior
/// @return YES if textStorage was modified, NO otherwise
+ (BOOL)kr_fixEllipsisIfNeededForTextStorage:(NSTextStorage *)textStorage
                                  textRender:(KRTextRender *)textRender
                                       lines:(NSUInteger)lines
                                        mode:(NSLineBreakMode)mode
                                     fitSize:(CGSize)fitSize {
    // Only handle the mode that requires truncation with ellipsis
    if (mode != NSLineBreakByTruncatingTail || lines == 0) {
        return NO;
    }
    
    NSLayoutManager *layoutManager = textRender.layoutManager;
    NSUInteger numberOfGlyphs = [layoutManager numberOfGlyphs];
    if (numberOfGlyphs == 0) {
        return NO;
    }
    
    // Enumerate lines and use rect to determine which lines are actually visible
    __block NSUInteger visibleLineCount = 0;
    __block NSRange lastVisibleLineGlyphRange = NSMakeRange(0, 0);
    __block BOOL hasMoreFragments = NO;
    [layoutManager enumerateLineFragmentsForGlyphRange:NSMakeRange(0, numberOfGlyphs)
                                            usingBlock:^(CGRect rect, CGRect usedRect,
                                                         NSTextContainer *container, NSRange glyphRange, BOOL *stop) {
        // Check if this line fragment is within the visible area
        if (rect.origin.y + rect.size.height <= fitSize.height + 0.5) { // Add small tolerance for floating point
            visibleLineCount++;
            lastVisibleLineGlyphRange = glyphRange;
            if (visibleLineCount >= lines) {
                *stop = YES;
            }
        } else {
            // This fragment is outside visible area
            hasMoreFragments = YES;
            *stop = YES;
        }
    }];
    
    // If no lines reached the limit and no more fragments, text is fully visible
    if (!hasMoreFragments && (lastVisibleLineGlyphRange.location + lastVisibleLineGlyphRange.length >= numberOfGlyphs)) {
        return NO;
    }
    
    // Check if system has already added ellipsis
    NSRange lastLineCharRange = [layoutManager characterRangeForGlyphRange:lastVisibleLineGlyphRange actualGlyphRange:nil];
    NSUInteger lastVisibleCharIndex = lastLineCharRange.location + lastLineCharRange.length;
    NSRange truncatedRange = [layoutManager truncatedGlyphRangeInLineFragmentForGlyphAtIndex:lastVisibleLineGlyphRange.location];
    if (truncatedRange.location != NSNotFound) {
        return NO;
    }
    
    // Ellipsis need to be added manually
    // Only remove the last newline character (not all trailing newlines)
    // to preserve line structure and show ellipsis on the correct line
    NSUInteger endIndex = lastVisibleCharIndex;
    NSString *text = textStorage.string;
    if (endIndex > 0 && ([text characterAtIndex:endIndex - 1] == '\n' || [text characterAtIndex:endIndex - 1] == '\r')) {
        endIndex--;
    }
    
    if (endIndex == 0) {
        return NO;
    }
    
    // Add ellipsis at the end of visible text
    NSDictionary *attrs = [textStorage attributesAtIndex:endIndex - 1 effectiveRange:nil];
    NSMutableAttributedString *newText = [[textStorage attributedSubstringFromRange:NSMakeRange(0, endIndex)] mutableCopy];
    [newText appendAttributedString:[[NSAttributedString alloc] initWithString:@"…" attributes:attrs]];
    [textStorage replaceCharactersInRange:NSMakeRange(0, textStorage.length) withAttributedString:newText];
    return YES;
}



#pragma mark - private

@end
//---------KRTextRender类分割线------------
@interface KRTextRender() <NSLayoutManagerDelegate> {
    CGRect _textBound;
}
@property (nonatomic, strong) KRLayoutManager * layoutManager;
@property (nonatomic, strong) NSTextContainer * textContainer;
@property (nonatomic, strong) NSTextStorage * textStorageOnRender;


@end
@implementation KRTextRender
@synthesize maximumNumberOfLines = _maximumNumberOfLines;

- (instancetype)init{
    if (self = [super init]) {
        _textContainer = [NSTextContainer new];
        _layoutManager = [KRLayoutManager new];
        _layoutManager.delegate = self;
        [_layoutManager addTextContainer:_textContainer];
        _textContainer.lineFragmentPadding = 0;
    }
    return self;
}

- (instancetype)initWithAttributedText:(NSAttributedString *)attributedText{
    if (self = [self initWithTextStorage:[[NSTextStorage alloc] initWithAttributedString:attributedText]]) {
        self.textStorage.hr_hasAttachmentViews = attributedText.hr_hasAttachmentViews;
    }
    return self;
}

- (instancetype)initWithTextStorage:(NSTextStorage *)textStorage lineHeight:(CGFloat)lineHeight {
    if (self = [self init]) {
        self.lineHeight = lineHeight;
        self.textStorage = textStorage;
    }
    return self;
}

- (instancetype)initWithTextStorage:(NSTextStorage *)textStorage{
    return [self initWithTextStorage:textStorage lineHeight:0];
}
#pragma mark - Getter && Setter

- (void)setTextStorage:(NSTextStorage *)textStorage{
    _textStorage = textStorage;
    self.textStorageOnRender = textStorage;
}

- (void)setTextStorageOnRender:(NSTextStorage *)textStorageOnRender{
    if (_textStorageOnRender != textStorageOnRender) {
        if (_textStorageOnRender) {
            [_textStorageOnRender removeLayoutManager:_layoutManager];
        }
        [textStorageOnRender addLayoutManager:_layoutManager];
        _textStorageOnRender = textStorageOnRender;
    }
}

- (void)setSize:(CGSize)size{
    if (isnan(size.width)) {
        size.width = 0;
    }
    if (isnan(size.height)) {
        size.height = 0;
    }
    _size = size;
    if (!CGSizeEqualToSize(_textContainer.size, size)) {
        _textContainer.size = size;
    }
}

- (void)setLineBreakMode:(NSLineBreakMode)lineBreakMode{
    if (_textContainer.lineBreakMode != lineBreakMode) {
        _textContainer.lineBreakMode = lineBreakMode;
    }
}

- (NSUInteger)maximumNumberOfLines{
    return _textContainer.maximumNumberOfLines;
}

- (void)setMaximumNumberOfLines:(NSUInteger)maximumNumberOfLines{
    if (_textContainer.maximumNumberOfLines != maximumNumberOfLines) {
        _textContainer.maximumNumberOfLines = maximumNumberOfLines;
    }
}

#pragma mark - Public

- (NSRange)visibleGlyphRange {
    return [_layoutManager glyphRangeForTextContainer:_textContainer];
}

- (NSRange)visibleCharacterRange {
    return [_layoutManager characterRangeForGlyphRange:[self visibleGlyphRange] actualGlyphRange:nil];
}

- (CGRect)boundingRectForCharacterRange:(NSRange)characterRange {
    NSRange glyphRange = [_layoutManager glyphRangeForCharacterRange:characterRange actualCharacterRange:nil];
    return [self boundingRectForGlyphRange:glyphRange];
}

- (CGRect)boundingRectForGlyphRange:(NSRange)glyphRange {
    return [_layoutManager boundingRectForGlyphRange:glyphRange inTextContainer:_textContainer];
}

- (CGRect)textBound {
    return [_layoutManager usedRectForTextContainer:_textContainer];
}

- (NSInteger)characterIndexForPoint:(CGPoint)point{
    CGFloat distanceToPoint = 1.0;
    NSUInteger index = [_layoutManager characterIndexForPoint:point inTextContainer:_textContainer fractionOfDistanceBetweenInsertionPoints:&distanceToPoint];
    return distanceToPoint < 1 ? index : -1;
}


- (CGSize)textSizeWithRenderWidth:(CGFloat)renderWidth{
    if (!_textStorageOnRender)  return CGSizeZero;
    _textContainer.size = CGSizeMake(renderWidth, MAXFLOAT);
#if TARGET_OS_OSX // [macOS NSLayoutManager needs explicit layout trigger
    // Force layout to ensure usedRectForTextContainer returns correct size
    [_layoutManager ensureLayoutForTextContainer:_textContainer];
#endif // macOS]
    CGSize textSize = [self textBound].size;
    CGSize res = CGSizeMake(ceil(textSize.width), ceil(textSize.height));
    return  res;
}
#pragma mark -  draw text


- (void)drawTextAtPoint:(CGPoint)point isCanceled:(BOOL (^)(void))isCanceled{
    NSRange glyphRange = [_layoutManager glyphRangeForTextContainer:_textContainer];
    // drawing text
    [_layoutManager enumerateLineFragmentsForGlyphRange:glyphRange usingBlock:^(CGRect rect, CGRect usedRect, NSTextContainer * _Nonnull textContainer, NSRange glyphRange, BOOL * _Nonnull stop) {
        [self->_layoutManager drawBackgroundForGlyphRange:glyphRange atPoint:point];
        if (isCanceled && isCanceled()) {*stop = YES; return ;};
        [self->_layoutManager drawGlyphsForGlyphRange:glyphRange atPoint:point];
        if (isCanceled && isCanceled()) {*stop = YES; return ;};
    }];
}

- (void)dealloc{
    [[KRAsyncDeallocManager shareManager] asyncDeallocWithObject:_textStorageOnRender];
    if (_textStorage != _textStorageOnRender) {
        [[KRAsyncDeallocManager shareManager] asyncDeallocWithObject:_textStorage];
    }
    [[KRAsyncDeallocManager shareManager] asyncDeallocWithObject:_layoutManager];
    [[KRAsyncDeallocManager shareManager] asyncDeallocWithObject:_textContainer];

}

#pragma mark - layout manager delegate
- (BOOL)layoutManager:(NSLayoutManager *)layoutManager shouldSetLineFragmentRect:(inout CGRect *)lineFragmentRect lineFragmentUsedRect:(inout CGRect *)lineFragmentUsedRect baselineOffset:(inout CGFloat *)baselineOffset inTextContainer:(NSTextContainer *)textContainer forGlyphRange:(NSRange)glyphRange {
    
    if (_lineHeight > 0) {
        UIFont *font;
        NSParagraphStyle *style;
        NSArray *attrsList = [self attributesListForGlyphRange:glyphRange layoutManager:layoutManager];
        [self getFont:&font paragraphStyle:&style fromAttibutesList:attrsList];

        if (![font isKindOfClass:[UIFont class]]) {
            return NO;
        }

        UIFont *defaultFont = [self systemDefaultFontForFont:font];

        CGRect rect = *lineFragmentRect;
        CGRect usedRect = *lineFragmentUsedRect;
        
        CGFloat textLineHeight = _lineHeight;
        CGFloat fixedBaseLineOffset = [self.class baseLineOffsetForLineHeight:textLineHeight font:defaultFont];
        
        rect.size.height = textLineHeight;
        usedRect.size.height = MAX(textLineHeight, usedRect.size.height);
        
        *lineFragmentRect = rect;
        *lineFragmentUsedRect = usedRect;
        *baselineOffset = fixedBaseLineOffset;
    }
    
    return YES;
}

+ (CGFloat)lineHeightForFont:(UIFont *)font paragraphStyle:(NSParagraphStyle *)style  {
    CGFloat lineHeight = font.lineHeight;
    if (!style) {
        return lineHeight;
    }
    if (style.lineHeightMultiple > 0) {
        lineHeight *= style.lineHeightMultiple;
    }
    if (style.minimumLineHeight > 0) {
        lineHeight = MAX(style.minimumLineHeight, lineHeight);
    }
    if (style.maximumLineHeight > 0) {
        lineHeight = MIN(style.maximumLineHeight, lineHeight);
    }
    return lineHeight;
}


+ (CGFloat)baseLineOffsetForLineHeight:(CGFloat)lineHeight font:(UIFont *)font {
    CGFloat baseLine = lineHeight + font.descender / 2;
    return baseLine;
}

/// get system default font of size
- (UIFont *)systemDefaultFontForFont:(UIFont *)font {
    return [UIFont systemFontOfSize:font.pointSize];
}


- (NSArray<NSDictionary *> *)attributesListForGlyphRange:(NSRange)glyphRange layoutManager:(NSLayoutManager *)layoutManager {

    // exclude the line break. System doesn't calucate the line rect with it.
    if (glyphRange.length > 1) {
        NSGlyphProperty property = [layoutManager propertyForGlyphAtIndex:glyphRange.location + glyphRange.length - 1];
        if (property & NSGlyphPropertyControlCharacter) {
            glyphRange = NSMakeRange(glyphRange.location, glyphRange.length - 1);
        }
    }

    
    NSTextStorage *textStorage = layoutManager.textStorage;
    NSRange targetRange = [layoutManager characterRangeForGlyphRange:glyphRange actualGlyphRange:nil];
    NSMutableArray *dicts = [NSMutableArray arrayWithCapacity:2];

    NSInteger last = -1;
    NSRange effectRange = NSMakeRange(targetRange.location, 0);

    while (effectRange.location + effectRange.length < targetRange.location + targetRange.length) {
        NSInteger current = effectRange.location + effectRange.length;
        // if effectRange didn't advanced, we manuly add 1 to avoid infinate loop.
        if (current <= last) {
            current += 1;
        }
        NSDictionary *attributes = [textStorage attributesAtIndex:current effectiveRange:&effectRange];
        if (attributes) {
            [dicts addObject:attributes];
        }
        last = current;
    }

    return dicts;
}

- (void)getFont:(UIFont **)returnFont paragraphStyle:(NSParagraphStyle **)returnStyle fromAttibutesList:(NSArray<NSDictionary *> *)attributesList {

    if (attributesList.count == 0) {
        return;
    }

    UIFont *findedFont = nil;
    NSParagraphStyle *findedStyle = nil;
    CGFloat lastHeight = -CGFLOAT_MAX;

    // find the attributes with max line height
    for (NSInteger i = 0; i < attributesList.count; i++) {
        NSDictionary *attrs = attributesList[i];

        NSParagraphStyle *style = attrs[NSParagraphStyleAttributeName];
        UIFont *font = attrs[NSFontAttributeName];

        if ([font isKindOfClass:[UIFont class]] &&
            (!style || [style isKindOfClass:[NSParagraphStyle class]]) ) {

            CGFloat height = [self.class lineHeightForFont:font paragraphStyle:style];
            if (height > lastHeight) {
                lastHeight = height;
                findedFont = font;
                findedStyle = style;
            }
        }
    }

    *returnFont = findedFont;
    *returnStyle = findedStyle;
}


@end

//------KRLayoutManager类分割线-----

@implementation KRLayoutManager{
    CGPoint _drawAtPoint;
}

- (void)drawBackgroundForGlyphRange:(NSRange)glyphsToShow atPoint:(CGPoint)origin {
    _drawAtPoint = origin;
    [super drawBackgroundForGlyphRange:glyphsToShow atPoint:origin];
    // Slock chip chrome (task #439). Drawn in drawBackground (before glyphs) so the
    // fill sits behind the text; the border is inset from the glyphs by the leading/
    // trailing NBSP padding reserved on the shared side, so it never overlaps glyphs.
    [self kr_drawSlockChipChromeForGlyphRange:glyphsToShow atPoint:origin];
    _drawAtPoint = CGPointZero;
}

// TEMPORARY BRIDGE TO TASK #442 — ports core-render-android KRRichTextViewDrawer.kt
// drawSlockInlineCodeChrome/drawSlockMarkdownTagChrome geometry to TextKit. #442 moves
// the resolved token values into span props so this reads prop data instead of the
// baked kKRSlock* constants above.
- (void)kr_drawSlockChipChromeForGlyphRange:(NSRange)glyphsToShow atPoint:(CGPoint)origin {
    NSTextStorage *textStorage = self.textStorage;
    if (textStorage.length == 0) {
        return;
    }
    NSTextContainer *container = self.textContainers.firstObject;
    if (!container) {
        return;
    }
    NSRange charRange = [self characterRangeForGlyphRange:glyphsToShow actualGlyphRange:NULL];
    if (charRange.length == 0) {
        return;
    }
    CGContextRef ctx = UIGraphicsGetCurrentContext();
    if (!ctx) {
        return;
    }
    [textStorage enumerateAttribute:KRSlockChromeAttributeName
                            inRange:charRange
                            options:0
                         usingBlock:^(id value, NSRange runRange, BOOL *stop) {
        if (![value isKindOfClass:[NSString class]] || [(NSString *)value length] == 0) {
            return;
        }
        UIColor *fillColor = KRSlockChromeFillColor((NSString *)value);
        if (!fillColor) {
            return; // underline-only kinds draw no chip
        }
        NSRange runGlyphRange = [self glyphRangeForCharacterRange:runRange actualCharacterRange:NULL];
        if (runGlyphRange.length == 0) {
            return;
        }
        NSUInteger runGlyphEnd = NSMaxRange(runGlyphRange);
        // Per line fragment the run spans: vertical from FONT METRICS (baseline ±
        // ascender/descender + vPadding, tight to the glyph box like Android/React) —
        // NOT the line-fragment rect (which includes line leading → chip too tall/high,
        // task #439 bug ①). Horizontal from boundingRectForGlyphRange (tight to the
        // glyphs on THIS line → no wrapped-segment right overhang).
        [self enumerateLineFragmentsForGlyphRange:runGlyphRange
                                       usingBlock:^(CGRect lineRect, CGRect usedRect, NSTextContainer *lineContainer, NSRange lineGlyphRange, BOOL *lineStop) {
            // enumerateLineFragmentsForGlyphRange gives the WHOLE line fragment's glyph
            // range, not the run's glyphs on that line — intersect with the run so the
            // chip fill bounds only THIS token's glyphs (task #439 bug: without this the
            // fill spanned the entire line instead of a discrete per-token chip).
            NSRange segmentGlyphRange = NSIntersectionRange(lineGlyphRange, runGlyphRange);
            if (segmentGlyphRange.length == 0) {
                return;
            }
            CGRect gb = [self boundingRectForGlyphRange:segmentGlyphRange inTextContainer:lineContainer];
            NSRange lineCharRange = [self characterRangeForGlyphRange:segmentGlyphRange actualGlyphRange:NULL];
            UIFont *font = lineCharRange.location < textStorage.length
                ? [textStorage attribute:NSFontAttributeName atIndex:lineCharRange.location effectiveRange:NULL]
                : nil;
            CGFloat textSize = font ? font.pointSize : 15.0;
            CGFloat ascender = font ? font.ascender : textSize * 0.75;   // > 0, above baseline
            CGFloat descender = font ? font.descender : -textSize * 0.25; // < 0, below baseline
            CGFloat hPadding = textSize * kKRSlockHorizontalPaddingRatio; // React px-1 ≈ 4px each side
            CGFloat chipHeight = textSize * kKRSlockLineHeightRatio;      // React leading-[1.5]
            CGPoint loc = [self locationForGlyphAtIndex:segmentGlyphRange.location];
            CGFloat baseline = lineRect.origin.y + loc.y + origin.y;
            BOOL isRunStart = (segmentGlyphRange.location == runGlyphRange.location);
            BOOL isRunEnd = (NSMaxRange(segmentGlyphRange) >= runGlyphEnd);
            // Use the first glyph's pen position for the LEFT edge, not gb.minX:
            // boundingRectForGlyphRange's minX includes leading space/context, which made
            // the left inner padding too big (~19px) and asymmetric vs the tight right
            // edge (XiShi round-2). locationForGlyphAtIndex gives the glyph's own origin.
            CGFloat glyphLeft = lineRect.origin.x + loc.x + origin.x;
            CGFloat glyphRight = CGRectGetMaxX(gb) + origin.x;
            // task #439 ⑥: the chip box outer edge = glyph ± (px-1 + 1px border). The
            // fill/border draw boxReserve beyond the glyphs on each outer run edge.
            //   - left (run start): boxReserve into the source space (no leading kern; the
            //     left external gap is the source space, per XiShi calibration).
            //   - right (run end): boxReserve past the glyph. KRRichTextView's TRAILING
            //     kern reserved this region in layout (pushing the next token to the box
            //     edge), and boundingRect does NOT include that kern (XiShi Case B), so we
            //     add boxReserve here to reach the box edge.
            //   - wrap-continuation edges: flush with the break.
            CGFloat boxReserve = hPadding + kKRSlockBorderWidthPt;
            CGFloat left = isRunStart ? (glyphLeft - boxReserve) : glyphLeft;
            CGFloat right = isRunEnd ? (glyphRight + boxReserve) : glyphRight;
            if (right <= left) {
                return;
            }
            // React leading-[1.5]: a 1.5·fontSize tall box centered on the font's vertical
            // center (baseline - (ascender+descender)/2) so the glyph is centered with
            // symmetric top/bottom padding (any residual low-sit is the systemic baseline).
            CGFloat centerY = baseline - (ascender + descender) / 2.0;
            CGFloat top = centerY - chipHeight / 2.0;
            CGFloat bottom = centerY + chipHeight / 2.0;
            if (bottom <= top) {
                return;
            }
            // CoreGraphics fills (portable across iOS + [macOS]; UIRectFill is iOS-only).
            CGContextSetFillColorWithColor(ctx, fillColor.CGColor);
            CGContextFillRect(ctx, CGRectMake(left, top, right - left, bottom - top));
            // Black 1dp border, square corners, four crisp edge rects
            // (SlockRichTextChromeStyleTokens border; KRRichTextViewDrawer.drawSlock*Border).
            CGFloat bw = kKRSlockBorderWidthPt;
            CGFloat bl = floor(left);
            CGFloat bt = floor(top);
            CGFloat br = ceil(right);
            CGFloat bb = ceil(bottom);
            CGContextSetFillColorWithColor(ctx, [UIColor blackColor].CGColor);
            CGContextFillRect(ctx, CGRectMake(bl, bt, br - bl, bw));
            CGContextFillRect(ctx, CGRectMake(bl, bb - bw, br - bl, bw));
            CGContextFillRect(ctx, CGRectMake(bl, bt, bw, bb - bt));
            CGContextFillRect(ctx, CGRectMake(br - bw, bt, bw, bb - bt));
        }];
    }];
}
- (void)dealloc{
#if DEBUG
    
    

#endif
}

@end
     

@interface KRTextAttachment ()
@property (nonatomic, assign) NSRange range;
@property (nonatomic, assign) CGPoint position;
@end

@implementation KRTextAttachment
- (void)setSize:(CGSize)size {
    _size = size;
    self.bounds = CGRectMake(0, _baseline, _size.width, _size.height);
}

- (void)setBounds:(CGRect)bounds {
    [super setBounds:bounds];
    _size = bounds.size;
}

- (void)setBaseline:(CGFloat)baseline {
    _baseline = baseline;
    self.bounds = CGRectMake(0, _baseline, _size.width, _size.height);
}

- (void)setImage:(UIImage *)image {
    [super setImage:image];
    if (_size.width == 0 && _size.height == 0 ) {
        self.size = image.size;
    }
}

- (void)setView:(UIView *)view {
    _view = view;
    if (_size.width == 0 && _size.height == 0 ) {
        self.size = view.frame.size;
    }
}


- (nullable UIImage *)imageForBounds:(CGRect)imageBounds textContainer:(nullable NSTextContainer *)textContainer characterIndex:(NSUInteger)charIndex {
    _position = CGPointMake(imageBounds.origin.x, imageBounds.origin.y - _size.height);
    return self.image;
}

- (CGRect)attachmentBoundsForTextContainer:(NSTextContainer *)textContainer proposedLineFragment:(CGRect)lineFrag glyphPosition:(CGPoint)position characterIndex:(NSUInteger)charIndex {
    if (_verticalAlignment == KRAttachmentAlignmentBaseline || self.bounds.origin.y > 0) {
        
        return CGRectMake(self.bounds.origin.x, self.bounds.origin.y - 2, self.bounds.size.width, self.bounds.size.height);
    }
    CGFloat offset = 0;
    UIFont *font = [textContainer.layoutManager.textStorage attribute:NSFontAttributeName atIndex:charIndex effectiveRange:nil];
    if (!font) {
        return self.bounds;
    }
    //    CGFloat pointSize = font.pointSize;
    //    CGFloat mid = font.descender + font.capHeight;
    //    CGFloat dd = font.descender ;
    //    CGFloat dd2 = font.ascender ;
    switch (_verticalAlignment) {
            //        case KRAttachmentAlignmentBaseline:
            //            offset = (font.capHeight - _size.height)/2;
            //            break;
        case KRAttachmentAlignmentCenter:
        {
            offset = (_size.height - font.capHeight)/2;
        }
            break;
        case KRAttachmentAlignmentBottom:
            offset = _size.height-font.pointSize + 2;
        default:
            break;
    }
    return CGRectMake(0, -offset, _size.width, _size.height);
}


@end

@implementation KRTextAttachment (Display)

- (void)setFrame:(CGRect)frame {
    _view.frame = frame;
}

- (void)addToSuperView:(UIView *)superView {
    if (_view) {
        [superView addSubview:_view];
    }
}
- (void)removeFromSuperView:(UIView *)superView {
    if (_view.superview == superView) {
        [_view removeFromSuperview];
    }
}

@end

@implementation NSAttributedString (KRTextAttachment)
- (BOOL)hr_hasAttachmentViews{
    NSNumber * value = objc_getAssociatedObject(self, @selector(hr_hasAttachmentViews));
    return [value boolValue];
}


- (void)setHr_hasAttachmentViews:(BOOL)hr_hasAttachmentViews{
    objc_setAssociatedObject(self, @selector(hr_hasAttachmentViews), @(hr_hasAttachmentViews), OBJC_ASSOCIATION_RETAIN);
}

- (NSArray<KRTextAttachment *> *)hr_viewAttachments{
    if (self.hr_hasAttachmentViews) {
        NSMutableArray *res = [NSMutableArray array];
        [self enumerateAttribute:NSAttachmentAttributeName inRange:NSMakeRange(0, self.length) options:kNilOptions usingBlock:^(KRTextAttachment *attribute, NSRange range, BOOL *stop) {
            if (attribute && [attribute isKindOfClass:[KRTextAttachment class]] && (attribute.view)) {
                attribute.range = range;
                [res addObject:attribute];
            }
        }];
        return res.count ? res : nil;
    }
    return nil;
}

@end

@implementation NSAttributedString(MIJAsync)
-(KRTextRender *)hr_textRender{
    return objc_getAssociatedObject(self, @selector(hr_textRender));
}

- (void)setHr_textRender:(KRTextRender *)hr_textRender{
    objc_setAssociatedObject(self, @selector(hr_textRender), hr_textRender, OBJC_ASSOCIATION_RETAIN);
}
- (CGSize)hr_size{
    return [objc_getAssociatedObject(self, @selector(hr_size)) CGSizeValue];
}

- (void)setHr_size:(CGSize)hr_size{
    objc_setAssociatedObject(self, @selector(hr_size), [NSValue valueWithCGSize:hr_size], OBJC_ASSOCIATION_RETAIN);
}
@end

