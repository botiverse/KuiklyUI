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

#import "KRScrollView.h"
#import "KRComponentDefine.h"
#import "KuiklyRenderView.h"
#import "KRWrapperView.h"
#import "KRMultiDelegateProxy.h"
#import "KRConvertUtil.h"
#import "KRScrollViewOffsetAnimator.h"
#import "KRScrollView+NestedScroll.h"
#import "NSObject+KR.h"
#import "KRContentOffsetAnimator.h"

typedef NS_ENUM(NSUInteger, KRSetContentOffsetAnimation) {
    KRSetContentOffsetAnimationSpring = 0,
    KRSetContentOffsetAnimationLinear = 1,
};

typedef NS_ENUM(NSInteger, KRScrollWriteResultCode) {
    KRScrollWriteResultCodeCommitted = 0,
    KRScrollWriteResultCodeAlreadySatisfied = 1,
    KRScrollWriteResultCodeBusy = 2,
    KRScrollWriteResultCodeNotReady = 3,
    KRScrollWriteResultCodeLayoutChanged = 4,
    KRScrollWriteResultCodeStale = 5,
    KRScrollWriteResultCodeReplaced = 6,
    KRScrollWriteResultCodeCanceled = 7,
    KRScrollWriteResultCodeDestroyed = 8,
    KRScrollWriteResultCodeOutOfRange = 9,
    KRScrollWriteResultCodeUnsupportedAxisOrNoLayout = 10,
    KRScrollWriteResultCodeInterrupted = 11,
    KRScrollWriteResultCodeAckTimeout = 12,
    KRScrollWriteResultCodeRollbackFailed = 13,
};

typedef NS_ENUM(NSUInteger, KRScrollWriteKind) {
    KRScrollWriteKindContentOffset,
    KRScrollWriteKindContentInset,
};

static BOOL KRScrollEventValueIsFinite(CGFloat value) {
    return !isnan(value) && !isinf(value);
}

static BOOL KRScrollEventPointIsFinite(CGPoint point) {
    return KRScrollEventValueIsFinite(point.x) && KRScrollEventValueIsFinite(point.y);
}

static void KRLogDroppedScrollEventValue(NSString *field,
                                         CGFloat value,
                                         NSString *action) {
    NSLog(@"[kuikly error][KRScrollView] non-finite scroll event value field=%@ value=%@ action=%@",
          field,
          @(value),
          action);
}

@interface KRScrollWriteOperation : NSObject
@property (nonatomic, assign) NSUInteger nativeSequence;
@property (nonatomic, assign) NSUInteger composeOperation;
@property (nonatomic, assign) NSInteger generation;
@property (nonatomic, assign) NSUInteger interactionEpoch;
@property (nonatomic, assign) NSUInteger layoutRevision;
@property (nonatomic, assign) NSUInteger insetRevision;
@property (nonatomic, assign) KRScrollWriteKind kind;
@property (nonatomic, assign) CGPoint targetOffset;
@property (nonatomic, assign) UIEdgeInsets targetInset;
@property (nonatomic, assign) BOOL animated;
@property (nonatomic, assign) BOOL replacedPrevious;
@property (nonatomic, assign) BOOL terminal;
@property (nonatomic, copy) KuiklyRenderCallback callback;
@end

@implementation KRScrollWriteOperation
@end

/*
 * @brief 暴露给Kotlin侧调用的Scoller组件
 */
@interface KRScrollView()<UIScrollViewDelegate, KRScrollViewOffsetAnimatorDelegate>

/** attr is bouncesEnable  */
@property (nonatomic, strong) NSNumber *KUIKLY_PROP(bouncesEnable);
/** attr is pagingEnabled  */
@property (nonatomic, strong) NSNumber *KUIKLY_PROP(pagingEnabled);
/** attr is isComposePager  */
@property (nonatomic, strong) NSNumber *KUIKLY_PROP(isComposePager);
/** attr is scrollEnabled  */
@property (nonatomic, strong) NSNumber *KUIKLY_PROP(scrollEnabled);
/** attr is showScrollerIndicator  */
@property (nonatomic, strong) NSNumber *KUIKLY_PROP(showScrollerIndicator);
/** attr is directionRow  */
@property (nonatomic, strong) NSNumber *KUIKLY_PROP(directionRow);
/** attr is css_dynamicSyncScrollDisable */
@property (nonatomic, strong) NSNumber *KUIKLY_PROP(dynamicSyncScrollDisable);
/** attr is minContentOffset */
@property (nonatomic, strong) NSNumber *KUIKLY_PROP(limitHeaderBounces);
/** attr is flingEnable */
@property (nonatomic, strong) NSNumber *KUIKLY_PROP(flingEnable);
/** attr nestedScroll */
@property (nonatomic, strong) NSString *KUIKLY_PROP(nestedScroll);
/** event is scroll  */
@property (nonatomic, strong) KuiklyRenderCallback KUIKLY_PROP(scroll);
/** event is dragBegin  */
@property (nonatomic, strong) KuiklyRenderCallback KUIKLY_PROP(dragBegin);
/** event is dragEnd  */
@property (nonatomic, strong) KuiklyRenderCallback KUIKLY_PROP(dragEnd);
/** event is willDragEnd  */
@property (nonatomic, strong) KuiklyRenderCallback KUIKLY_PROP(willDragEnd);
/** event is scrollEnd  */
@property (nonatomic, strong) KuiklyRenderCallback KUIKLY_PROP(scrollEnd);
/** event is scrollToTop  */
@property (nonatomic, strong) KuiklyRenderCallback KUIKLY_PROP(scrollToTop);

- (BOOL)css_contentOffsetWithParams:(NSString *)params callback:(KuiklyRenderCallback)callback;
- (BOOL)p_matchesExpectedContentSize:(CGFloat)expectedContentSize
                        viewportSize:(CGFloat)expectedViewportSize;
- (BOOL)p_matchesInteractionEpoch:(NSUInteger)interactionEpoch
                   layoutRevision:(NSUInteger)layoutRevision
                    insetRevision:(NSUInteger)insetRevision;
- (NSDictionary *)p_scrollWriteResult:(KRScrollWriteResultCode)resultCode;
- (NSDictionary *)p_scrollWriteResult:(KRScrollWriteResultCode)resultCode
                             operation:(KRScrollWriteOperation *)operation;
- (KRScrollWriteResultCode)p_validateComposeWriteWithGeneration:(NSInteger)generation
                                              requiresNativeIdle:(BOOL)requiresNativeIdle
                                                       operation:(NSUInteger)operation
                                              expectedContentSize:(CGFloat)expectedContentSize
                                             expectedViewportSize:(CGFloat)expectedViewportSize
                                                interactionEpoch:(NSUInteger)interactionEpoch
                                                   layoutRevision:(NSUInteger)layoutRevision
                                                    insetRevision:(NSUInteger)insetRevision;
- (KRScrollWriteOperation *)p_installScrollWriteWithGeneration:(NSInteger)generation
                                                     operation:(NSUInteger)operation
                                              interactionEpoch:(NSUInteger)interactionEpoch
                                                 layoutRevision:(NSUInteger)layoutRevision
                                                  insetRevision:(NSUInteger)insetRevision
                                                          kind:(KRScrollWriteKind)kind
                                                      callback:(KuiklyRenderCallback)callback;
- (dispatch_block_t)p_finalizeScrollWrite:(KRScrollWriteOperation *)operation
                                resultCode:(KRScrollWriteResultCode)resultCode;
- (dispatch_block_t)p_invalidateCurrentScrollWrite:(KRScrollWriteResultCode)resultCode;
- (BOOL)p_isCurrentScrollWrite:(KRScrollWriteOperation *)operation;
- (void)p_cancelNativeScrollMechanisms;
- (void)p_scheduleTerminalDeadlineForOperation:(KRScrollWriteOperation *)operation
                                    durationMs:(CGFloat)durationMs;
- (void)p_springAnimationWithContentOffset:(CGPoint)contentOffset
                                  duration:(CGFloat)duration
                                   damping:(CGFloat)damping
                                  velocity:(CGFloat)velocity
                                     curve:(int)curve
                                 operation:(KRScrollWriteOperation *)operation;
- (void)p_completeOffsetAnimation:(KRScrollViewOffsetAnimator *)animator
                       generation:(NSUInteger)generation
                        operation:(KRScrollWriteOperation *)operation;

@end

@implementation KRScrollView {
    /** scrollEventCallback */
    KuiklyRenderCallback _scrollEventCallback;
    /** 松手时offsetY小于insetTop设置该contentInset for 下拉刷新组件 */
    UIEdgeInsets _contentInsetWhenEndDrag;
    NSInteger _contentInsetWhenEndDragGeneration;
    NSUInteger _contentInsetWhenEndDragOperation;
    CGFloat _contentInsetWhenEndDragExpectedContentSize;
    CGFloat _contentInsetWhenEndDragExpectedViewportSize;
    NSUInteger _contentInsetWhenEndDragInteractionEpoch;
    NSUInteger _contentInsetWhenEndDragLayoutRevision;
    NSUInteger _contentInsetWhenEndDragInsetRevision;
    /* wrapper self view*/
    __weak KRWrapperView *_wrapperView;
    /* 一对多代理转发 */
    KRMultiDelegateProxy *_delegateProxy;
    /** 松手时吸附位置 */
    CGPoint *_targetContentOffset;
    /** is first layout */
    BOOL _didLayout;
    /**是否正在拖拽中，因系统isDragging不准，所以独立维护**/
    BOOL _isCurrentlyDragging;
    /** displaylink驱动的offset动画器 */
    KRScrollViewOffsetAnimator *_offsetAnimator;
    /** Invalidates stale UIKit animation completions after replacement/reuse/cancel. */
    NSUInteger _offsetAnimationGeneration;
    /**忽略分发ScrollEvent**/
    BOOL _ignoreDispatchScrollEvent;
    KRContentOffsetAnimator *_ku_coreAnimator;
    NSInteger _composeOffsetWriteGeneration;
    NSUInteger _nativeWriteOperationSequence;
    NSUInteger _latestComposeWriteOperation;
    NSUInteger _minimumComposeWriteOperation;
    NSUInteger _nativeInteractionEpoch;
    NSUInteger _nativeLayoutRevision;
    NSUInteger _nativeInsetRevision;
    CGRect _lastRevisionBounds;
    CGSize _lastRevisionContentSize;
    KRScrollWriteOperation *_currentScrollWriteOperation;
}
@synthesize hr_rootView;
@synthesize lastContentOffset = _lastContentOffset;
KUIKLY_NESTEDSCROLL_PROTOCOL_PROPERTY_IMP

#pragma mark - init

- (instancetype)initWithFrame:(CGRect)frame {
    if (self = [super initWithFrame: frame]) {
        #if !TARGET_OS_OSX // [macOS]
        if (@available(iOS 13.0, *)) {
            self.automaticallyAdjustsScrollIndicatorInsets = NO;
        }
        self.contentInsetAdjustmentBehavior = UIScrollViewContentInsetAdjustmentNever;
        self.delaysContentTouches = NO;
        #else // [macOS]
        // macOS: 启用 layer-backed 支持 clipPath
        self.wantsLayer = YES;
        #endif // [macOS]
        self.alwaysBounceVertical = YES;
        _delegateProxy = [KRMultiDelegateProxy alloc];
        [_delegateProxy addDelegate:self];
        self.delegate = (id<UIScrollViewDelegate>)_delegateProxy;
    }
    return self;
    
}

#pragma mark - KuiklyRenderViewExportProtocol

- (void)hrv_setPropWithKey:(NSString *)propKey propValue:(id)propValue {
    KUIKLY_SET_CSS_COMMON_PROP;
}

- (void)hrv_callWithMethod:(NSString *)method params:(NSString *)params callback:(KuiklyRenderCallback)callback {
    if ([method isEqualToString:@"contentOffset"]) {
        [self css_contentOffsetWithParams:params callback:callback];
    } else if ([method isEqualToString:@"contentInset"]) {
        [self css_contentInsetWithParams:params callback:callback];
    } else if ([method isEqualToString:@"contentInsetWhenEndDrag"]) {
        [self css_contentInsetWhenEndDragWithParams:params];
    } else if ([method isEqualToString:@"abortContentOffsetAnimate"]) {
        [self css_abortContentOffsetAnimate];
    } else if ([method isEqualToString:@"prepareForComposeReuse"]) {
        [self css_prepareForComposeReuse:params];
    }
}

#pragma mark - abort animate

- (void)css_abortContentOffsetAnimate {
    dispatch_block_t terminal = [self p_invalidateCurrentScrollWrite:KRScrollWriteResultCodeCanceled];
    _nativeWriteOperationSequence += 1;
    [self p_cancelNativeScrollMechanisms];
    if (terminal) terminal();
}

// Clear transient state for Compose DSL reuse. Kotlin side overwrites contentSize/contentOffset immediately after.
- (void)css_prepareForComposeReuse:(NSString *)params {
    NSInteger nextGeneration = params.length > 0 ? params.integerValue : _composeOffsetWriteGeneration + 1;
    _composeOffsetWriteGeneration = nextGeneration;
    _nativeInteractionEpoch += 1;
    _nativeLayoutRevision += 1;
    _nativeInsetRevision += 1;
    dispatch_block_t terminal = [self p_invalidateCurrentScrollWrite:KRScrollWriteResultCodeDestroyed];
    _nativeWriteOperationSequence += 1;
    [self p_cancelNativeScrollMechanisms];
    #if !TARGET_OS_OSX // [macOS]
    if (self.panGestureRecognizer.state == UIGestureRecognizerStateBegan ||
        self.panGestureRecognizer.state == UIGestureRecognizerStateChanged) {
        self.panGestureRecognizer.enabled = NO;
        self.panGestureRecognizer.enabled = YES;
    }
    #endif // [macOS]
    _isCurrentlyDragging = NO;
    // Required: ensures dispatchScrollEventWithCurOffset: fires on restored offset,
    // otherwise ignoreScrollOffset gets stuck and blocks all subsequent scrolling.
    _lastContentOffset = CGPointMake(-CGFLOAT_MAX, -CGFLOAT_MAX);
    // Defensive: clear pull-to-refresh residual from previous owner.
    if (!UIEdgeInsetsEqualToEdgeInsets(self.contentInset, UIEdgeInsetsZero)) {
        self.autoAdjustContentOffsetDisable = YES;
        self.contentInset = UIEdgeInsetsZero;
        self.autoAdjustContentOffsetDisable = NO;
    }
    _contentInsetWhenEndDrag = UIEdgeInsetsZero;
    _contentInsetWhenEndDragGeneration = _composeOffsetWriteGeneration;
    _contentInsetWhenEndDragOperation = 0;
    _contentInsetWhenEndDragExpectedContentSize = -1;
    _contentInsetWhenEndDragExpectedViewportSize = -1;
    _contentInsetWhenEndDragInteractionEpoch = _nativeInteractionEpoch;
    _contentInsetWhenEndDragLayoutRevision = _nativeLayoutRevision;
    _contentInsetWhenEndDragInsetRevision = _nativeInsetRevision;
    _latestComposeWriteOperation = 0;
    _minimumComposeWriteOperation = 0;
    // Reset nested scroll transient state.
    [self.nestedScrollCoordinator prepareForComposeReuse];
    self.shouldHaveActiveInner = NO;
    self.activeInnerScrollView = nil;
    self.activeOuterScrollView = nil;
    self.cascadeLockForNestedScroll = NO;
    self.isLockedInNestedScroll = NO;
    self.tempLastContentOffsetForMultiLayerNested = nil;
    if (terminal) terminal();
}

#pragma mark - pubilc

 
/*
 * 添加滚动监听
 */
- (void)addScrollViewDelegate:(id<UIScrollViewDelegate>)scrollViewDelegate {
    [_delegateProxy addDelegate:scrollViewDelegate];
}
/*
 * 删除滚动监听
 */
- (void)removeScrollViewDelegate:(id<UIScrollViewDelegate>)scrollViewDelegate {
    [_delegateProxy removeDelegate:scrollViewDelegate];
}

#pragma mark - override

- (void)layoutSubviews {
    [super layoutSubviews];
    if (!CGRectEqualToRect(_lastRevisionBounds, self.bounds) ||
        !CGSizeEqualToSize(_lastRevisionContentSize, self.contentSize)) {
        _nativeLayoutRevision += 1;
        _lastRevisionBounds = self.bounds;
        _lastRevisionContentSize = self.contentSize;
    }
    if (!_didLayout && [self.hr_rootView isKindOfClass:[KuiklyRenderView class]]) {
        _didLayout = YES;
        KuiklyRenderView *renderView = (KuiklyRenderView*)self.hr_rootView;
        if ([renderView.delegate respondsToSelector:@selector(scrollViewDidLayout:renderView:)]) {
            [renderView.delegate scrollViewDidLayout:self renderView:renderView];
        }
    }
}

- (void)didMoveToSuperview {
    [super didMoveToSuperview];
    if (self.superview && self.superview != _wrapperView) {
        [_wrapperView moveToSuperview:self.superview];
    }
}

- (void)insertSubview:(UIView *)view atIndex:(NSInteger)index {
    [super insertSubview:view atIndex:index];
}

- (void)removeFromSuperview {
    [super removeFromSuperview];
    if (_wrapperView.superview) {
        [_wrapperView removeFromSuperview];
    }
}

- (void)setContentOffset:(CGPoint)contentOffset {
    if (self.autoAdjustContentOffsetDisable) {
        return ;
    }
    if ([_css_limitHeaderBounces boolValue]) { // 禁止顶部回弹
        if ([_css_directionRow boolValue]) {
            contentOffset = CGPointMake(MAX(contentOffset.x, 0), contentOffset.y);
        } else {
            contentOffset = CGPointMake(contentOffset.x, MAX(contentOffset.y, 0));
        }
    }
    [super setContentOffset:contentOffset];
    [self p_dispatchScrollEventIfNeed];
}

- (void)setContentOffset:(CGPoint)contentOffset animated:(BOOL)animated {
    [_ku_coreAnimator stop];
    _ku_coreAnimator = nil;
    [super setContentOffset:contentOffset animated:animated];
}

- (void)setUserInteractionEnabled:(BOOL)userInteractionEnabled {
    [super setUserInteractionEnabled:userInteractionEnabled];
    [_wrapperView setUserInteractionEnabled:userInteractionEnabled];
}

#if !TARGET_OS_OSX // [macOS]
- (BOOL)touchesShouldCancelInContentView:(UIView *)view {
    BOOL cancel = [super touchesShouldCancelInContentView:view];
    if ([view isKindOfClass:[UIControl class]] || view.kr_canCancelInScrollView) {
        return YES;
    }
    return cancel;
}
#endif // [macOS]


#pragma mark - UIScrollViewDelegate

#if !TARGET_OS_OSX // [macOS]
- (void)touchesBegan:(NSSet<UITouch *> *)touches withEvent:(UIEvent *)event {
    [super touchesBegan:touches withEvent:event];
    [_ku_coreAnimator stop];
    _ku_coreAnimator = nil;
}
#else
- (void)mouseDown:(NSEvent *)event {
    [_ku_coreAnimator stop];
    _ku_coreAnimator = nil;
}
#endif // [macOS]

- (BOOL)scrollViewShouldScrollToTop:(UIScrollView *)scrollView {
    if (_css_scrollToTop) {
        _css_scrollToTop(nil);
        return NO; // Handled by Kotlin side
    }
    return YES;
}
    
- (void)scrollViewWillBeginDragging:(UIScrollView *)scrollView {
    self.skipNestScrollLock = NO;
    self.lContentOffset = scrollView.contentOffset;

    _isCurrentlyDragging = YES;
    _nativeInteractionEpoch += 1;
    _nativeWriteOperationSequence += 1;
    _minimumComposeWriteOperation = _latestComposeWriteOperation + 1;
    _contentInsetWhenEndDrag = UIEdgeInsetsZero;
    _contentInsetWhenEndDragOperation = 0;
    dispatch_block_t terminal = [self p_invalidateCurrentScrollWrite:KRScrollWriteResultCodeInterrupted];
    [self p_cancelNativeScrollMechanisms];
    if (terminal) terminal();
    if (_css_dragBegin) {
        NSDictionary *eventParams = [self p_generateEventBaseParams];
        if (eventParams) {
            _css_dragBegin(eventParams);
        }
    }
}

- (void)scrollViewDidEndDragging:(UIScrollView *)scrollView willDecelerate:(BOOL)decelerate {
    _isCurrentlyDragging = NO;
    BOOL shouldApplyEndDragInset =
        !UIEdgeInsetsEqualToEdgeInsets(_contentInsetWhenEndDrag, UIEdgeInsetsZero) &&
        _contentInsetWhenEndDragGeneration == _composeOffsetWriteGeneration &&
        (_contentInsetWhenEndDragOperation == 0 ||
         (_contentInsetWhenEndDragOperation == _latestComposeWriteOperation &&
          _contentInsetWhenEndDragOperation >= _minimumComposeWriteOperation)) &&
        [self p_matchesInteractionEpoch:_contentInsetWhenEndDragInteractionEpoch
                         layoutRevision:_contentInsetWhenEndDragLayoutRevision
                          insetRevision:_contentInsetWhenEndDragInsetRevision] &&
        [self p_matchesExpectedContentSize:_contentInsetWhenEndDragExpectedContentSize
                              viewportSize:_contentInsetWhenEndDragExpectedViewportSize] &&
        scrollView.contentOffset.y < -_contentInsetWhenEndDrag.top;
    UIEdgeInsets endDragInset = _contentInsetWhenEndDrag;
    _contentInsetWhenEndDrag = UIEdgeInsetsZero;
    _contentInsetWhenEndDragOperation = 0;
    if (shouldApplyEndDragInset && !UIEdgeInsetsEqualToEdgeInsets(self.contentInset, endDragInset)) {
        self.contentInset = endDragInset;
        _nativeInsetRevision += 1;
    }
    if (!decelerate) { // 滑动结束
        BOOL animating = _currentScrollWriteOperation != nil || _offsetAnimator != nil ||
            [_ku_coreAnimator isAnimating];
        if (_css_scrollEnd && !animating) {
            NSDictionary *eventParams = [self p_generateEventBaseParams];
            if (eventParams) {
                _css_scrollEnd(eventParams);
            }
        }
    }
    if (_css_dragEnd) {
        NSDictionary *eventParams = [self p_generateEventBaseParams];
        if (eventParams) {
            _css_dragEnd(eventParams);
        }
    }
}

- (void)scrollViewDidEndDecelerating:(UIScrollView *)scrollView {
    BOOL animating = _currentScrollWriteOperation != nil || _offsetAnimator != nil ||
        [_ku_coreAnimator isAnimating];
    if (_css_scrollEnd && !animating) {
        NSDictionary *eventParams = [self p_generateEventBaseParams];
        if (eventParams) {
            _css_scrollEnd(eventParams);
        }
    }
}

- (void)scrollViewDidScroll:(UIScrollView *)scrollView {
    #if !TARGET_OS_OSX // [macOS]
    // iOS: 用户滚动会触发 setContentOffset:，已在那里分发事件，这里保持空实现避免重复
    #else // [macOS]
    // macOS: 用户滚动不会调用 setContentOffset:，必须通过此 delegate 回调分发事件
    [self p_dispatchScrollEventIfNeed];
    #endif // macOS]
}

- (void)scrollViewDidEndScrollingAnimation:(UIScrollView *)scrollView {
    // Compose writes use tagged KRScrollViewOffsetAnimator completions. This untagged UIKit
    // callback is only authoritative for legacy native animations.
    if (_currentScrollWriteOperation == nil &&
        [KRScrollViewOffsetAnimator shouldEmitTerminalForNativePhase:[self p_nativeScrollPhase]] &&
        _css_scrollEnd) {
        NSDictionary *eventParams = [self p_generateEventBaseParams];
        if (eventParams) {
            _css_scrollEnd(eventParams);
        }
    }
}

- (void)scrollViewWillEndDragging:(UIScrollView *)scrollView withVelocity:(CGPoint)velocity targetContentOffset:(inout CGPoint *)targetContentOffset {

    // flingEnable: cancel system deceleration when disabled (default is YES)
    if (_css_flingEnable && ![_css_flingEnable boolValue] && targetContentOffset) {
        *targetContentOffset = scrollView.contentOffset;
    }

    BOOL isCompose = self.hr_rootView.contextParam.isCompose;
    if (isCompose && targetContentOffset && ![self.css_isComposePager boolValue]) {
        CGPoint proposed = *targetContentOffset;
        BOOL isHorizontal = [_css_directionRow boolValue];
        CGFloat startPrimary = isHorizontal ? scrollView.contentOffset.x : scrollView.contentOffset.y;

        // 计算边界，用于限制自定义动画（根据方向）
        UIEdgeInsets insets = scrollView.contentInset;
        CGFloat minPrimary = isHorizontal ? -insets.left : -insets.top;
        CGFloat maxPrimary = 0;
        if (isHorizontal) {
            maxPrimary = MAX(-insets.left, scrollView.contentSize.width - scrollView.bounds.size.width + insets.right);
        } else {
            maxPrimary = MAX(-insets.top, scrollView.contentSize.height - scrollView.bounds.size.height + insets.bottom);
        }
        CGFloat proposedPrimary = isHorizontal ? proposed.x : proposed.y;
        BOOL isCurrentlyBouncing = (startPrimary < minPrimary) || (startPrimary > maxPrimary);
        BOOL willOvershootBounds = (proposedPrimary < minPrimary) || (proposedPrimary > maxPrimary);

        CGFloat deltaPrimary = fabs(proposedPrimary - startPrimary);
        if (deltaPrimary > KRMaxAllowedDistance && !isCurrentlyBouncing && !willOvershootBounds) {
            CGFloat newPrimary = proposedPrimary;

            // 限制最大单次动画距离，避免远跳
            CGFloat distance = deltaPrimary;
            CGFloat maxDistance = KRMaxAllowedDistance;
            if (distance > maxDistance) {
                newPrimary = (newPrimary > startPrimary) ? (startPrimary + maxDistance) : (startPrimary - maxDistance);
            }

            // 取消系统惯性
            *targetContentOffset = isHorizontal ? CGPointMake(startPrimary, proposed.y) : CGPointMake(proposed.x, startPrimary);

            if (_ku_coreAnimator == nil) {
                _ku_coreAnimator = [[KRContentOffsetAnimator alloc] initWithScrollView:self];
            }
            CAMediaTimingFunction *tf = [[CAMediaTimingFunction alloc] initWithControlPoints:KRContentOffsetAnimatorP1x
                    :KRContentOffsetAnimatorP1y
                    :KRContentOffsetAnimatorP2x
                    :KRContentOffsetAnimatorP2y];
            __weak typeof(self) weakSelf = self;
            CGPoint target = isHorizontal ? CGPointMake(newPrimary, self.contentOffset.y) : CGPointMake(self.contentOffset.x, newPrimary);
            [_ku_coreAnimator animateToOffset:target
                                     duration:3.2
                               timingFunction:tf
                                   onProgress:^(CGFloat progress) { } completion:^(BOOL finished){
                        __strong typeof(weakSelf) strongSelf = weakSelf;
                        if (!strongSelf) return;
                        if (finished && strongSelf->_css_scrollEnd) {
                            NSDictionary *eventParams = [strongSelf p_generateEventBaseParams];
                            if (eventParams) {
                                strongSelf->_css_scrollEnd(eventParams);
                            }
                        }
                    }];
        }
    }

    if (_css_willDragEnd) {
        if ([_css_isComposePager boolValue]) {
            // 这里将惯性滑动最大距离限制为300
            CGPoint currentOffset = scrollView.contentOffset;
            CGPoint proposedOffset = *targetContentOffset;
            CGFloat maxDistance = 300.0;
            CGFloat dx = proposedOffset.x - currentOffset.x;
            CGFloat dy = proposedOffset.y - currentOffset.y;
            if (fabs(dx) > maxDistance) {
                proposedOffset.x = currentOffset.x + (dx > 0 ? maxDistance : -maxDistance);
            } else if (fabs(dy) > maxDistance) {
                proposedOffset.y = currentOffset.y + (dy > 0 ? maxDistance : -maxDistance);
            }
            // iOS 18会偶现出现大距离跳变，限制下最大的距离
            *targetContentOffset = proposedOffset;
        }

        _targetContentOffset = targetContentOffset;
        NSMutableDictionary *params = [[self p_generateEventBaseParams] mutableCopy];
        CGPoint target = targetContentOffset ? *targetContentOffset : CGPointZero;
        if (!KRScrollEventValueIsFinite(velocity.x)) {
            KRLogDroppedScrollEventValue(@"velocityX", velocity.x, @"drop_event");
            params = nil;
        } else if (!KRScrollEventValueIsFinite(velocity.y)) {
            KRLogDroppedScrollEventValue(@"velocityY", velocity.y, @"drop_event");
            params = nil;
        } else if (!KRScrollEventValueIsFinite(target.x)) {
            KRLogDroppedScrollEventValue(@"targetContentOffsetX", target.x, @"drop_event");
            params = nil;
        } else if (!KRScrollEventValueIsFinite(target.y)) {
            KRLogDroppedScrollEventValue(@"targetContentOffsetY", target.y, @"drop_event");
            params = nil;
        }
        if (params) {
            params[@"velocityX"] = @(velocity.x);
            params[@"velocityY"] = @(velocity.y);
            params[@"targetContentOffsetX"] = @(target.x);
            params[@"targetContentOffsetY"] = @(target.y);
            _css_willDragEnd(params); /// setContentOffset ()
        }
        _targetContentOffset = nil;
    }
}


#pragma mark - css method

- (BOOL)css_contentOffsetWithParams:(NSString *)params callback:(KuiklyRenderCallback)callback {
    NSArray<NSString *> *points = [params componentsSeparatedByString:@" "];
    NSInteger generation = points.count > 8 ? [points[7] integerValue] : -1;
    BOOL requiresNativeIdle = points.count > 8 && [points[8] boolValue];
    NSUInteger composeOperation = points.count > 9 ? (NSUInteger)[points[9] longLongValue] : 0;
    CGFloat expectedContentSize = points.count > 10 ? [points[10] doubleValue] : -1;
    CGFloat expectedViewportSize = points.count > 11 ? [points[11] doubleValue] : -1;
    NSUInteger interactionEpoch = points.count > 17 ? (NSUInteger)[points[17] longLongValue] : _nativeInteractionEpoch;
    NSUInteger layoutRevision = points.count > 18 ? (NSUInteger)[points[18] longLongValue] : _nativeLayoutRevision;
    NSUInteger insetRevision = points.count > 21 ? (NSUInteger)[points[21] longLongValue] : _nativeInsetRevision;
    KRScrollWriteResultCode validation = [self p_validateComposeWriteWithGeneration:generation
                                                                  requiresNativeIdle:requiresNativeIdle
                                                                           operation:composeOperation
                                                                 expectedContentSize:expectedContentSize
                                                                expectedViewportSize:expectedViewportSize
                                                                    interactionEpoch:interactionEpoch
                                                                       layoutRevision:layoutRevision
                                                                        insetRevision:insetRevision];
    if (validation != KRScrollWriteResultCodeCommitted) {
        if (callback) callback([self p_scrollWriteResult:validation]);
        return NO;
    }
    KRScrollWriteOperation *operation = [self p_installScrollWriteWithGeneration:generation
                                                                        operation:composeOperation
                                                                 interactionEpoch:interactionEpoch
                                                                    layoutRevision:layoutRevision
                                                                     insetRevision:insetRevision
                                                                             kind:KRScrollWriteKindContentOffset
                                                                         callback:callback];
    if (![self p_isCurrentScrollWrite:operation]) {
        return NO;
    }
    BOOL animated = [points count] > 2 ? [points[2] boolValue] : NO;
    operation.animated = animated;
    CGFloat duration = [points count] > 3 ? [points[3] floatValue] : 0;
    CGFloat damping = [points count] > 4 ? [points[4] floatValue] : 0;
    CGFloat velocity = [points count] > 5 ? [points[5] floatValue] : 0;
    BOOL curveSpecified = [points count] > 6;
    int curve = curveSpecified ? [points[6] intValue] : 0;
    CGPoint contentOffset = CGPointMake([points.firstObject doubleValue], [points[1] doubleValue]);
    operation.targetOffset = contentOffset;
    [self p_setTargetContentOffsetIfNeed:contentOffset];
    if (fabs(self.contentOffset.x - contentOffset.x) <= 0.5 &&
        fabs(self.contentOffset.y - contentOffset.y) <= 0.5) {
        dispatch_block_t terminal = [self p_finalizeScrollWrite:operation
                                                      resultCode:KRScrollWriteResultCodeAlreadySatisfied];
        if (terminal) terminal();
        return YES;
    }
    self.skipNestScrollLock = YES;
    if (damping || curveSpecified) {
        [self p_springAnimationWithContentOffset:contentOffset
                                        duration:duration
                                         damping:damping
                                        velocity:velocity
                                           curve:curve
                                       operation:operation];
        return [self p_isCurrentScrollWrite:operation];
    }
    UIEdgeInsets newContentInsets = [self maxEdgeInsetsWithContentOffset:contentOffset];
    if (!UIEdgeInsetsEqualToEdgeInsets(self.contentInset, newContentInsets)) {
        self.contentInset = newContentInsets;
        _nativeInsetRevision += 1;
    }
    if (animated) {
        [self p_springAnimationWithContentOffset:contentOffset
                                        duration:250.0
                                         damping:1.0
                                        velocity:0.0
                                           curve:KRSetContentOffsetAnimationLinear
                                       operation:operation];
    } else {
        [self setContentOffset:contentOffset animated:NO];
    }
    if (!animated && [self p_isCurrentScrollWrite:operation]) {
        BOOL reachedTarget = fabs(self.contentOffset.x - contentOffset.x) <= 1.0 &&
            fabs(self.contentOffset.y - contentOffset.y) <= 1.0;
        dispatch_block_t terminal = [self p_finalizeScrollWrite:operation
                                                      resultCode:reachedTarget
                                                          ? KRScrollWriteResultCodeCommitted
                                                          : KRScrollWriteResultCodeInterrupted];
        if (terminal) terminal();
    }
    return YES;
}

- (BOOL)css_contentInsetWithParams:(NSString *)params callback:(KuiklyRenderCallback)callback {
    NSArray<NSString *> *points = [params componentsSeparatedByString:@" "];
    NSInteger generation = points.count > 6 ? [points[5] integerValue] : -1;
    BOOL requiresNativeIdle = points.count > 6 && [points[6] boolValue];
    NSUInteger composeOperation = points.count > 7 ? (NSUInteger)[points[7] longLongValue] : 0;
    CGFloat expectedContentSize = points.count > 8 ? [points[8] doubleValue] : -1;
    CGFloat expectedViewportSize = points.count > 9 ? [points[9] doubleValue] : -1;
    NSUInteger interactionEpoch = points.count > 15 ? (NSUInteger)[points[15] longLongValue] : _nativeInteractionEpoch;
    NSUInteger layoutRevision = points.count > 16 ? (NSUInteger)[points[16] longLongValue] : _nativeLayoutRevision;
    NSUInteger insetRevision = points.count > 19 ? (NSUInteger)[points[19] longLongValue] : _nativeInsetRevision;
    KRScrollWriteResultCode validation = [self p_validateComposeWriteWithGeneration:generation
                                                                  requiresNativeIdle:requiresNativeIdle
                                                                           operation:composeOperation
                                                                 expectedContentSize:expectedContentSize
                                                                expectedViewportSize:expectedViewportSize
                                                                    interactionEpoch:interactionEpoch
                                                                       layoutRevision:layoutRevision
                                                                        insetRevision:insetRevision];
    if (validation != KRScrollWriteResultCodeCommitted) {
        if (callback) callback([self p_scrollWriteResult:validation]);
        return NO;
    }
    KRScrollWriteOperation *operation = [self p_installScrollWriteWithGeneration:generation
                                                                        operation:composeOperation
                                                                 interactionEpoch:interactionEpoch
                                                                    layoutRevision:layoutRevision
                                                                     insetRevision:insetRevision
                                                                             kind:KRScrollWriteKindContentInset
                                                                         callback:callback];
    if (![self p_isCurrentScrollWrite:operation]) {
        return NO;
    }
    BOOL animated = [points count] > 4 ? [points[4] boolValue] : NO;
    operation.animated = animated;
    UIEdgeInsets contentInset = UIEdgeInsetsMake([points[0] doubleValue], [points[1] doubleValue], [points[2] doubleValue], [points[3] doubleValue]);
    operation.targetInset = contentInset;
    if (UIEdgeInsetsEqualToEdgeInsets(self.contentInset, contentInset)) {
        dispatch_block_t terminal = [self p_finalizeScrollWrite:operation
                                                      resultCode:KRScrollWriteResultCodeAlreadySatisfied];
        if (terminal) terminal();
        return YES;
    }
    if (animated) {
        CGPoint maxContentOffset = [self p_maxContentOffsetInContentInset:contentInset];
        if (!CGPointEqualToPoint(self.contentOffset, maxContentOffset)) {
            operation.targetOffset = maxContentOffset;
            [self p_springAnimationWithContentOffset:maxContentOffset
                                            duration:250.0
                                             damping:1.0
                                            velocity:0.0
                                               curve:KRSetContentOffsetAnimationLinear
                                           operation:operation];
        } else {
            self.contentInset = contentInset;
            _nativeInsetRevision += 1;
            NSDictionary *eventParams = [self p_generateEventBaseParams];
            dispatch_block_t terminal = [self p_finalizeScrollWrite:operation
                                                          resultCode:KRScrollWriteResultCodeCommitted];
            if (_css_scrollEnd && eventParams) {
                _css_scrollEnd(eventParams);
            }
            if (terminal) terminal();
        }
    } else {
        self.autoAdjustContentOffsetDisable = YES;
        self.contentInset = contentInset;
        _nativeInsetRevision += 1;
        self.autoAdjustContentOffsetDisable = NO;
        dispatch_block_t terminal = [self p_finalizeScrollWrite:operation
                                                      resultCode:KRScrollWriteResultCodeCommitted];
        if (terminal) terminal();
    }
    return YES;
}
    

- (void)css_contentInsetWhenEndDragWithParams:(NSString *)params {
    NSArray<NSString *> *points = [params componentsSeparatedByString:@" "];
    UIEdgeInsets contentInset = UIEdgeInsetsMake([points[0] doubleValue], [points[1] doubleValue], [points[2] doubleValue], [points[3] doubleValue]);
    NSInteger generation = points.count > 6 ? [points[5] integerValue] : _composeOffsetWriteGeneration;
    BOOL requiresNativeIdle = points.count > 6 && [points[6] boolValue];
    NSUInteger composeOperation = points.count > 7 ? (NSUInteger)[points[7] longLongValue] : 0;
    CGFloat expectedContentSize = points.count > 8 ? [points[8] doubleValue] : -1;
    CGFloat expectedViewportSize = points.count > 9 ? [points[9] doubleValue] : -1;
    NSUInteger interactionEpoch = points.count > 15 ? (NSUInteger)[points[15] longLongValue] : _nativeInteractionEpoch;
    NSUInteger layoutRevision = points.count > 16 ? (NSUInteger)[points[16] longLongValue] : _nativeLayoutRevision;
    NSUInteger insetRevision = points.count > 19 ? (NSUInteger)[points[19] longLongValue] : _nativeInsetRevision;
    KRScrollWriteResultCode validation = [self p_validateComposeWriteWithGeneration:generation
                                                                  requiresNativeIdle:requiresNativeIdle
                                                                           operation:composeOperation
                                                                 expectedContentSize:expectedContentSize
                                                                expectedViewportSize:expectedViewportSize
                                                                    interactionEpoch:interactionEpoch
                                                                       layoutRevision:layoutRevision
                                                                        insetRevision:insetRevision];
    if (validation != KRScrollWriteResultCodeCommitted) {
        return;
    }
    if (composeOperation > 0) {
        _latestComposeWriteOperation = composeOperation;
    }
    _contentInsetWhenEndDrag = contentInset;
    _contentInsetWhenEndDragGeneration = generation;
    _contentInsetWhenEndDragOperation = composeOperation;
    _contentInsetWhenEndDragExpectedContentSize = expectedContentSize;
    _contentInsetWhenEndDragExpectedViewportSize = expectedViewportSize;
    _contentInsetWhenEndDragInteractionEpoch = interactionEpoch;
    _contentInsetWhenEndDragLayoutRevision = layoutRevision;
    _contentInsetWhenEndDragInsetRevision = insetRevision;
}

- (BOOL)p_matchesExpectedContentSize:(CGFloat)expectedContentSize
                        viewportSize:(CGFloat)expectedViewportSize {
    if (expectedContentSize < 0 || expectedViewportSize < 0) {
        return YES;
    }
    CGFloat actualContentSize = [_css_directionRow boolValue] ? self.contentSize.width : self.contentSize.height;
    CGFloat actualViewportSize = [_css_directionRow boolValue] ? CGRectGetWidth(self.frame) : CGRectGetHeight(self.frame);
    return fabs(actualContentSize - expectedContentSize) <= 1.0 &&
        fabs(actualViewportSize - expectedViewportSize) <= 1.0;
}

- (BOOL)p_matchesInteractionEpoch:(NSUInteger)interactionEpoch
                   layoutRevision:(NSUInteger)layoutRevision
                    insetRevision:(NSUInteger)insetRevision {
    return interactionEpoch == _nativeInteractionEpoch &&
        layoutRevision == _nativeLayoutRevision &&
        insetRevision == _nativeInsetRevision;
}

- (NSDictionary *)p_scrollWriteResult:(KRScrollWriteResultCode)resultCode {
    BOOL committed = resultCode == KRScrollWriteResultCodeCommitted ||
        resultCode == KRScrollWriteResultCodeAlreadySatisfied;
    return @{
        @"committed": @(committed ? 1 : 0),
        @"resultCode": @(resultCode),
        @"accepted": @(committed ? 1 : 0),
        @"installed": @(committed ? 1 : 0),
        @"replacedPrevious": @0,
        @"nativeInteractionEpoch": @(_nativeInteractionEpoch),
        @"layoutRevision": @(_nativeLayoutRevision),
        @"insetRevision": @(_nativeInsetRevision),
    };
}

- (NSDictionary *)p_scrollWriteResult:(KRScrollWriteResultCode)resultCode
                             operation:(KRScrollWriteOperation *)operation {
    BOOL committed = resultCode == KRScrollWriteResultCodeCommitted ||
        resultCode == KRScrollWriteResultCodeAlreadySatisfied;
    return @{
        @"committed": @(committed ? 1 : 0),
        @"resultCode": @(resultCode),
        @"accepted": @(operation ? 1 : 0),
        @"installed": @(operation ? 1 : 0),
        @"replacedPrevious": @(operation.replacedPrevious ? 1 : 0),
        @"nativeInteractionEpoch": @(_nativeInteractionEpoch),
        @"layoutRevision": @(_nativeLayoutRevision),
        @"insetRevision": @(_nativeInsetRevision),
    };
}

- (KRScrollWriteResultCode)p_validateComposeWriteWithGeneration:(NSInteger)generation
                                              requiresNativeIdle:(BOOL)requiresNativeIdle
                                                       operation:(NSUInteger)operation
                                             expectedContentSize:(CGFloat)expectedContentSize
                                            expectedViewportSize:(CGFloat)expectedViewportSize
                                                interactionEpoch:(NSUInteger)interactionEpoch
                                                   layoutRevision:(NSUInteger)layoutRevision
                                                    insetRevision:(NSUInteger)insetRevision {
    if (generation >= 0 && generation != _composeOffsetWriteGeneration) {
        return KRScrollWriteResultCodeStale;
    }
    if (requiresNativeIdle && [self p_nativeScrollPhase] != 0) {
        return KRScrollWriteResultCodeBusy;
    }
    if (operation > 0 &&
        (operation < _minimumComposeWriteOperation || operation < _latestComposeWriteOperation)) {
        return KRScrollWriteResultCodeStale;
    }
    if (interactionEpoch != _nativeInteractionEpoch) {
        return KRScrollWriteResultCodeInterrupted;
    }
    if (layoutRevision != _nativeLayoutRevision ||
        ![self p_matchesExpectedContentSize:expectedContentSize viewportSize:expectedViewportSize]) {
        return CGRectIsEmpty(self.bounds) ? KRScrollWriteResultCodeNotReady : KRScrollWriteResultCodeLayoutChanged;
    }
    if (insetRevision != _nativeInsetRevision) {
        return KRScrollWriteResultCodeStale;
    }
    return KRScrollWriteResultCodeCommitted;
}

- (KRScrollWriteOperation *)p_installScrollWriteWithGeneration:(NSInteger)generation
                                                     operation:(NSUInteger)operation
                                              interactionEpoch:(NSUInteger)interactionEpoch
                                                 layoutRevision:(NSUInteger)layoutRevision
                                                  insetRevision:(NSUInteger)insetRevision
                                                          kind:(KRScrollWriteKind)kind
                                                      callback:(KuiklyRenderCallback)callback {
    KRScrollWriteOperation *next = [KRScrollWriteOperation new];
    next.nativeSequence = ++_nativeWriteOperationSequence;
    next.composeOperation = operation;
    next.generation = generation;
    next.interactionEpoch = interactionEpoch;
    next.layoutRevision = layoutRevision;
    next.insetRevision = insetRevision;
    next.kind = kind;
    next.callback = callback;

    KRScrollWriteOperation *previous = _currentScrollWriteOperation;
    _currentScrollWriteOperation = next;
    next.replacedPrevious = previous != nil;
    if (operation > 0) {
        _latestComposeWriteOperation = operation;
    }
    dispatch_block_t previousTerminal = [self p_finalizeScrollWrite:previous
                                                          resultCode:KRScrollWriteResultCodeReplaced];
    if (previous || kind == KRScrollWriteKindContentOffset || _offsetAnimator != nil) {
        [self p_cancelNativeScrollMechanisms];
    }
    if (previousTerminal) previousTerminal();
    return next;
}

- (dispatch_block_t)p_finalizeScrollWrite:(KRScrollWriteOperation *)operation
                                resultCode:(KRScrollWriteResultCode)resultCode {
    if (!operation || operation.terminal) {
        return nil;
    }
    operation.terminal = YES;
    if (_currentScrollWriteOperation == operation) {
        _currentScrollWriteOperation = nil;
    }
    KuiklyRenderCallback callback = operation.callback;
    operation.callback = nil;
    NSDictionary *result = [self p_scrollWriteResult:resultCode operation:operation];
    if (!callback) {
        return nil;
    }
    return [^{ callback(result); } copy];
}

- (dispatch_block_t)p_invalidateCurrentScrollWrite:(KRScrollWriteResultCode)resultCode {
    return [self p_finalizeScrollWrite:_currentScrollWriteOperation resultCode:resultCode];
}

- (BOOL)p_isCurrentScrollWrite:(KRScrollWriteOperation *)operation {
    return operation && !operation.terminal && _currentScrollWriteOperation == operation;
}

- (void)p_cancelNativeScrollMechanisms {
    [_ku_coreAnimator stop];
    _ku_coreAnimator = nil;
    [self p_invalidateOffsetAnimation];
    _ignoreDispatchScrollEvent = NO;
    CGPoint currentOffset = self.contentOffset;
    [super setContentOffset:currentOffset animated:NO];
}

- (void)p_scheduleTerminalDeadlineForOperation:(KRScrollWriteOperation *)operation
                                    durationMs:(CGFloat)durationMs {
    CGFloat normalizedDuration = MAX(0.0, durationMs);
    CGFloat slack = MAX(1000.0, normalizedDuration * 0.25);
    int64_t deadline = (int64_t)((normalizedDuration + slack) * NSEC_PER_MSEC);
    __weak typeof(self) weakSelf = self;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, deadline), dispatch_get_main_queue(), ^{
        __strong typeof(weakSelf) strongSelf = weakSelf;
        if (!strongSelf || ![strongSelf p_isCurrentScrollWrite:operation]) {
            return;
        }
        NSDictionary *eventParams = [strongSelf p_generateEventBaseParams];
        dispatch_block_t terminal = [strongSelf p_finalizeScrollWrite:operation
                                                            resultCode:KRScrollWriteResultCodeAckTimeout];
        [strongSelf p_cancelNativeScrollMechanisms];
        if (strongSelf->_css_scrollEnd && eventParams) {
            strongSelf->_css_scrollEnd(eventParams);
        }
        if (terminal) terminal();
    });
}


#pragma mark - setter (css property)

- (void)setCss_bouncesEnable:(NSNumber *)css_bouncesEnable {
    if (self.css_bouncesEnable != css_bouncesEnable) {
        _css_bouncesEnable = css_bouncesEnable;
        self.bounces = _css_bouncesEnable ? [css_bouncesEnable boolValue] : YES;
    }
}

- (void)setCss_pagingEnabled:(NSNumber *)css_pagingEnabled {
    if (self.css_pagingEnabled != css_pagingEnabled) {
        _css_pagingEnabled = css_pagingEnabled;
        self.pagingEnabled = [css_pagingEnabled boolValue];
    }
}

- (void)setCss_scrollEnabled:(NSNumber *)css_scrollEnabled {
    if (self.css_scrollEnabled != css_scrollEnabled) {
        _css_scrollEnabled = css_scrollEnabled;
        self.scrollEnabled = [css_scrollEnabled boolValue];
    }
}

- (void)setCss_isComposePager:(NSNumber *)css_isComposePager {
    if (self.css_isComposePager != css_isComposePager) {
        _css_isComposePager = css_isComposePager;
    }
}

- (void)setCss_flingEnable:(NSNumber *)css_flingEnable {
    if (self.css_flingEnable != css_flingEnable) {
        _css_flingEnable = css_flingEnable;
    }
}

- (void)setCss_showScrollerIndicator:(NSNumber *)css_showScrollerIndicator {
    if (self.css_showScrollerIndicator != css_showScrollerIndicator) {
        _css_showScrollerIndicator = css_showScrollerIndicator;
        self.showsVerticalScrollIndicator = [css_showScrollerIndicator boolValue];
        self.showsHorizontalScrollIndicator = [css_showScrollerIndicator boolValue];
    }
}

- (void)setCss_directionRow:(NSNumber *)css_directionRow {
    if (self.css_directionRow != css_directionRow) {
        _css_directionRow = css_directionRow;
        self.alwaysBounceHorizontal = [_css_directionRow boolValue];
        self.alwaysBounceVertical = !self.alwaysBounceHorizontal;
    }
}

- (void)parseScrollMode:(NSString *)modeStr forward:(BOOL)isForward {
    NestedScrollPriority pri = NestedScrollPriorityUndefined;
    if ([modeStr isEqualToString:@"SELF_ONLY"]) {
        pri = NestedScrollPrioritySelfOnly;
    } else if ([modeStr isEqualToString:@"SELF_FIRST"]) {
        pri = NestedScrollPrioritySelf;
    } else if ([modeStr isEqualToString:@"PARENT_FIRST"]) {
        pri = NestedScrollPriorityParent;
    }
    // 垂直
    if (isForward && ![self horizontal]) {
        [self setNestedScrollTopPriority:pri];
    } else if (isForward && [self horizontal]) {
        [self setNestedScrollLeftPriority:pri];
    } else if (!isForward && ![self horizontal]) {
        [self setNestedScrollBottomPriority:pri];
    } else if (!isForward && [self horizontal]) {
        [self setNestedScrollRightPriority:pri];
    }
}

- (void)setCss_nestedScroll:(NSString *)css_nestedScroll {
    if (![self.css_nestedScroll isEqualToString:css_nestedScroll]) {
        _css_nestedScroll = css_nestedScroll;
        NSDictionary *dic = [css_nestedScroll kr_stringToDictionary];
        NSString *forwardStr = [dic objectForKey:@"forward"];
        NSString *backwardStr = [dic objectForKey:@"backward"];
        [self parseScrollMode:forwardStr forward:YES];
        [self parseScrollMode:backwardStr forward:NO];
    }
}

- (void)setCss_dynamicSyncScrollDisable:(NSNumber *)css_dynamicSyncScrollDisable {
    if (self.css_dynamicSyncScrollDisable != css_dynamicSyncScrollDisable) {
        _css_dynamicSyncScrollDisable = css_dynamicSyncScrollDisable;
    }
}

- (void)setCss_frame:(NSValue *)css_frame {
    self.skipNestScrollLock = YES;
    [super setCss_frame:css_frame];
    self.skipNestScrollLock = NO;
    _wrapperView.frame = self.frame;
}


- (NSString *)css_borderRadius {
    if (_wrapperView.css_borderRadius) {
        return _wrapperView.css_borderRadius;
    }
    return [super css_borderRadius];
}

- (void)setCss_borderRadius:(NSString *)css_borderRadius {
    if (_wrapperView) { // 垫一层wrapperview来设置圆角，避免scrollView的layer.mask过裁内容
        _wrapperView.css_borderRadius = css_borderRadius;
    } else {
        [super setCss_borderRadius:css_borderRadius];
        if (self.layer.mask) {
            [super setCss_borderRadius:nil];
            [self p_generateWrapperViewIfNeed];
            _wrapperView.css_borderRadius = css_borderRadius;
        }
    }
}

#pragma mark - KRScrollViewOffsetAnimatorDelegate


- (void)animateContentOffsetDidChanged:(CGPoint)contentOffset {
    [self dispatchScrollEventWithCurOffset:contentOffset];
}

#pragma mark - private
/// 是否有足够多的可见内容视图
- (BOOL)p_hasEnoughVisibleContentViews {
    UIView *contentView = self.subviews.firstObject;
    if (!contentView
        || MAX(CGRectGetHeight(contentView.frame), CGRectGetWidth(contentView.frame))
        <=  MAX(CGRectGetHeight(self.frame), CGRectGetWidth(self.frame))) {
        return YES;
    }
    CGPoint offset = self.contentOffset;
    BOOL hasTopViewInVisibleFrame = NO;
    BOOL hasBottomViewInVisibleFrame = NO;
    CGRect visibleFrame = CGRectMake(0, 0, CGRectGetWidth(self.frame), CGRectGetHeight(self.frame));
    for (UIView *subView in contentView.subviews) {
        CGRect subViewFrame = subView.frame;
        subViewFrame.origin.x -= offset.x;
        subViewFrame.origin.y -= offset.y;
        
        if (CGRectGetWidth(contentView.frame) < CGRectGetHeight(contentView.frame)) { // 纵向布局
            CGRect topAreaRect = CGRectMake(1, 1, CGRectGetWidth(visibleFrame) - 2, CGRectGetHeight(visibleFrame) * 0.3);
            CGRect bottomAreaRect = CGRectMake(1,
                                               CGRectGetHeight(visibleFrame) * 0.5 - 1,
                                               CGRectGetWidth(visibleFrame) - 2,
                                               CGRectGetHeight(visibleFrame) * 0.5);
            
            if (CGRectContainsRect(subViewFrame, topAreaRect) || CGRectContainsRect(topAreaRect, subViewFrame)
                || CGRectIntersectsRect(subViewFrame, topAreaRect)){
                hasTopViewInVisibleFrame = YES;
            }
            if (CGRectContainsRect(subViewFrame, bottomAreaRect) || CGRectContainsRect(bottomAreaRect, subViewFrame)
                || CGRectIntersectsRect(subViewFrame, bottomAreaRect)){
                hasBottomViewInVisibleFrame = YES;
            }
            if (hasTopViewInVisibleFrame && hasBottomViewInVisibleFrame) {
                return YES;
            }
        } else { // 横向布局
            return YES;
        }
    }
    
    return hasTopViewInVisibleFrame && hasBottomViewInVisibleFrame;
}

// 生成wrapper view
- (void)p_generateWrapperViewIfNeed {
    if (!_wrapperView) {
        KRWrapperView *wrapperView = [[KRWrapperView alloc] initWithHostView:self];
        _wrapperView = wrapperView;
        dispatch_async(dispatch_get_main_queue(), ^{
            [wrapperView description]; // strong one loop
        });
    }
}
// 分发scroll变化事件到kotlin
- (void)p_dispatchScrollEventIfNeed {
    if (self.isLockedInNestedScroll) {
        self.isLockedInNestedScroll = NO; // reset
        return;
    }
    
    if (_ignoreDispatchScrollEvent) {
        return ;
    }
    
    if ([self p_shouldIgnoreScrollEventDuringAnimation]) {
        return;
    }
    
    [self dispatchScrollEventWithCurOffset:self.contentOffset];
}

/// Check if scroll event should be ignored during animation when setContentSize is called.
/// When setContentSize triggers UIKit to internally set offset directly to animation target position,
/// we should ignore this erroneous callback to prevent page flashing back.
- (BOOL)p_shouldIgnoreScrollEventDuringAnimation {
    if (!self.setContentSizeing) {
        return NO;
    }
    
    CGPoint animationTargetOffset;
    BOOL hasActiveAnimation = NO;
    
    // Check KRScrollViewOffsetAnimator (spring/damping animation from Kotlin side, e.g. animateScrollToPage)
    if (_offsetAnimator != nil) {
        animationTargetOffset = _offsetAnimator.toOffset;
        hasActiveAnimation = YES;
    }
    // Check KRContentOffsetAnimator (inertia animation after user gesture)
    else if ([_ku_coreAnimator isAnimating]) {
        animationTargetOffset = _ku_coreAnimator.targetOffset;
        hasActiveAnimation = YES;
    }
    
    if (!hasActiveAnimation) {
        return NO;
    }
    
    // Only ignore when current offset equals animation target position (allow 1px tolerance)
    CGFloat currentOffset = [_css_directionRow boolValue] ? self.contentOffset.x : self.contentOffset.y;
    CGFloat targetOffset = [_css_directionRow boolValue] ? animationTargetOffset.x : animationTargetOffset.y;
    
    return fabs(currentOffset - targetOffset) < 1.0;
}

- (void)dispatchScrollEventWithCurOffset:(CGPoint)curOffset {
    if (!CGPointEqualToPoint(curOffset, _lastContentOffset)) {
        _lastContentOffset = curOffset;
        if (_css_scroll) {
            dispatch_block_t block = ^{
                BOOL syncCallback = NO;
                if (![self.css_dynamicSyncScrollDisable boolValue] && !self.setContentSizeing) {
                    syncCallback = ![self p_hasEnoughVisibleContentViews];
                }
                NSMutableDictionary *param = [[self p_generateEventBaseParams] mutableCopy];
                if (param) {
                    param[KR_SYNC_CALLBACK_KEY] = @(syncCallback ? 1 : 0); // 同步加载
                    self.css_scroll(param);
                }
            };
            if (CGRectEqualToRect(self.frame, CGRectZero)) {
                // 首次setContentOffset->等自身frame在下一个runloop设置
                dispatch_async(dispatch_get_main_queue(), block);
            } else {
                block();
            }
           
        }
    }
}

// 在该contentInset下的列表最大可滚动偏移
- (CGPoint)p_maxContentOffsetInContentInset:(UIEdgeInsets)contentInset {
    // Check if content is smaller than frame (non-scrollable case)
    CGFloat frameSize = [_css_directionRow boolValue] ? CGRectGetWidth(self.frame) : CGRectGetHeight(self.frame);
    CGFloat contentSizeValue = [_css_directionRow boolValue] ? self.contentSize.width : self.contentSize.height;
    
    // If content is smaller than frame, no need to adjust offset
    if (contentSizeValue <= frameSize) {
        return CGPointZero;
    }
    
    CGFloat offsetTop = [_css_directionRow boolValue] ? self.contentOffset.x + contentInset.left : self.contentOffset.y + contentInset.top;
    CGFloat offsetBottom = [_css_directionRow boolValue]
        ? self.contentOffset.x + CGRectGetWidth(self.frame) - (self.contentSize.width + contentInset.right)
        : self.contentOffset.y + CGRectGetHeight(self.frame) - (self.contentSize.height + contentInset.bottom);
    if (offsetTop < 0) {
        if ([_css_directionRow boolValue]) {
            return CGPointMake(self.contentOffset.x - offsetTop, 0);
        } else {
            return CGPointMake(0, self.contentOffset.y - offsetTop);
        }
    } else if (offsetBottom > 0) {
        if ([_css_directionRow boolValue]) {
            return CGPointMake(self.contentOffset.x - offsetBottom, 0);
        } else {
            return CGPointMake(0, self.contentOffset.y - offsetBottom);
        }
    }
    return self.contentOffset;
}

- (UIEdgeInsets)maxEdgeInsetsWithContentOffset:(CGPoint)contentOffset {
    if ([_css_directionRow boolValue]) {
        if (contentOffset.x < -self.contentInset.left) {
            return UIEdgeInsetsMake(self.contentInset.top, -contentOffset.x, self.contentInset.bottom, self.contentInset.right);
        }
    } else {
        if (contentOffset.y < -self.contentInset.top) {
            return UIEdgeInsetsMake(-contentOffset.y, self.contentInset.left, self.contentInset.bottom, self.contentInset.right);
        }
    }
    return self.contentInset;
}


- (NSDictionary *)p_generateEventBaseParams {
    CGFloat coreValues[] = {
        _lastContentOffset.x,
        _lastContentOffset.y,
        self.contentSize.width,
        self.contentSize.height,
        self.frame.size.width,
        self.frame.size.height,
    };
    NSArray<NSString *> *coreFields = @[
        @"offsetX",
        @"offsetY",
        @"contentWidth",
        @"contentHeight",
        @"viewWidth",
        @"viewHeight",
    ];
    for (NSUInteger i = 0; i < coreFields.count; i++) {
        if (!KRScrollEventValueIsFinite(coreValues[i])) {
            KRLogDroppedScrollEventValue(coreFields[i], coreValues[i], @"drop_event");
            return nil;
        }
    }

    NSMutableArray *touchesParam = [NSMutableArray new];
    #if !TARGET_OS_OSX // [macOS]
    for (int i = 0; i < self.panGestureRecognizer.numberOfTouches; i++) {
        CGPoint pagePoint = [self.panGestureRecognizer locationOfTouch:i inView:self.hr_rootView];
        if (!KRScrollEventPointIsFinite(pagePoint)) {
            if (!KRScrollEventValueIsFinite(pagePoint.x)) {
                KRLogDroppedScrollEventValue([NSString stringWithFormat:@"touches[%d].pageX", i],
                                             pagePoint.x,
                                             @"drop_touch");
            }
            if (!KRScrollEventValueIsFinite(pagePoint.y)) {
                KRLogDroppedScrollEventValue([NSString stringWithFormat:@"touches[%d].pageY", i],
                                             pagePoint.y,
                                             @"drop_touch");
            }
            continue;
        }
        [touchesParam addObject:@{
            @"pageX" : @(pagePoint.x),
            @"pageY" : @(pagePoint.y)
        }];
    }
    #else // [macOS
    // On macOS, get mouse location to simulate single touch point
    CGPoint mousePoint = [self kr_mouseLocationInView:self.hr_rootView];
    if (KRScrollEventPointIsFinite(mousePoint)) {
        [touchesParam addObject:@{
            @"pageX" : @(mousePoint.x),
            @"pageY" : @(mousePoint.y)
        }];
    } else {
        if (!KRScrollEventValueIsFinite(mousePoint.x)) {
            KRLogDroppedScrollEventValue(@"touches[0].pageX", mousePoint.x, @"drop_touch");
        }
        if (!KRScrollEventValueIsFinite(mousePoint.y)) {
            KRLogDroppedScrollEventValue(@"touches[0].pageY", mousePoint.y, @"drop_touch");
        }
    }
    #endif // macOS]
    
    NSUInteger sourceOperation = (!_isCurrentlyDragging && _currentScrollWriteOperation.animated)
        ? _currentScrollWriteOperation.composeOperation : 0;
    return @{
        @"offsetX":@(_lastContentOffset.x),
        @"offsetY":@(_lastContentOffset.y),
        @"contentWidth": @(self.contentSize.width),
        @"contentHeight": @(self.contentSize.height),
        @"viewWidth": @(self.frame.size.width),
        @"viewHeight": @(self.frame.size.height),
        @"isDragging":@(_isCurrentlyDragging ? 1 : 0),
        @"nativeScrollPhase":@([self p_nativeScrollPhase]),
        @"nativeInteractionEpoch":@(_nativeInteractionEpoch),
        @"layoutRevision":@(_nativeLayoutRevision),
        @"insetRevision":@(_nativeInsetRevision),
        @"sourceOperationGeneration":@(sourceOperation),
        @"touches": touchesParam,
    };
}

- (NSInteger)p_nativeScrollPhase {
    if (_isCurrentlyDragging) {
        return 1;
    }
    if (self.decelerating || _currentScrollWriteOperation != nil ||
        _offsetAnimator != nil || [_ku_coreAnimator isAnimating]) {
        return 2;
    }
    return 0;
}

- (void)p_setTargetContentOffsetIfNeed:(CGPoint)contentOffset {
    if (_targetContentOffset) {
        *_targetContentOffset = contentOffset;
    }
}

- (void)p_springAnimationWithContentOffset:(CGPoint)contentOffset
                                  duration:(CGFloat)duration
                                   damping:(CGFloat)damping
                                  velocity:(CGFloat)velocity
                                     curve:(int)curve
                                 operation:(KRScrollWriteOperation *)operation {
    [self p_invalidateOffsetAnimation];
    [self setContentOffset:self.contentOffset animated:NO];
    if (![self p_isCurrentScrollWrite:operation]) {
        return;
    }
    _offsetAnimator = [[KRScrollViewOffsetAnimator alloc] initWithScrollView:self delegate:self];
    [_offsetAnimator animateToOffset:contentOffset withVelocity:CGPointZero];
    KRScrollViewOffsetAnimator *animator = _offsetAnimator;
    NSUInteger animationGeneration = _offsetAnimationGeneration;
    _ignoreDispatchScrollEvent = YES;
    
    switch (curve) {
        // linear animation curve
        case KRSetContentOffsetAnimationLinear:{
            [UIView animateWithDuration:duration / 1000.0
                                  delay:0 options:(UIViewAnimationOptionCurveLinear | UIViewAnimationOptionAllowUserInteraction)
                             animations:^{
                                            if (![self p_isCurrentScrollWrite:operation]) {
                                                return;
                                            }
                                            if (contentOffset.y < 0 || contentOffset.x < 0) {
                                                UIEdgeInsets targetInset = UIEdgeInsetsMake(-contentOffset.y, -contentOffset.x, 0, 0);
                                                if (!UIEdgeInsetsEqualToEdgeInsets(self.contentInset, targetInset)) {
                                                    self.contentInset = targetInset;
                                                    self->_nativeInsetRevision += 1;
                                                }
                                            }
                                            [self setContentOffset:contentOffset];
                                        }
                             completion:^(BOOL finished) {
                                            [self p_completeOffsetAnimation:animator
                                                                generation:animationGeneration
                                                                 operation:operation];
                                        }];
        }
            break;
        
        // defaults to spring animation
        case KRSetContentOffsetAnimationSpring:
        default: {
            [UIView animateWithDuration:duration / 1000.0 delay:0
                 usingSpringWithDamping:damping
                  initialSpringVelocity:velocity
                                options:(UIViewAnimationOptionCurveEaseOut | UIViewAnimationOptionAllowUserInteraction)
                             animations:^{
                    if (![self p_isCurrentScrollWrite:operation]) {
                        return;
                    }
                    if (contentOffset.y < 0 || contentOffset.x < 0) {
                        UIEdgeInsets targetInset = UIEdgeInsetsMake(-contentOffset.y, -contentOffset.x, 0, 0);
                        if (!UIEdgeInsetsEqualToEdgeInsets(self.contentInset, targetInset)) {
                            self.contentInset = targetInset;
                            self->_nativeInsetRevision += 1;
                        }
                    }
                    [self setContentOffset:contentOffset];
            } completion:^(BOOL finished) {
                [self p_completeOffsetAnimation:animator
                                      generation:animationGeneration
                                       operation:operation];
            }];
        }
            break;
    }

    _ignoreDispatchScrollEvent = NO;
    if ([self p_isCurrentScrollWrite:operation]) {
        [self p_scheduleTerminalDeadlineForOperation:operation durationMs:duration];
    }
}

- (void)p_invalidateOffsetAnimation {
    _offsetAnimationGeneration += 1;
    [_offsetAnimator cancel];
    _offsetAnimator = nil;
}

- (void)p_completeOffsetAnimation:(KRScrollViewOffsetAnimator *)animator
                       generation:(NSUInteger)generation
                        operation:(KRScrollWriteOperation *)operation {
    if (![KRScrollViewOffsetAnimator isCurrentAnimator:_offsetAnimator
                                              candidate:animator
                                      currentGeneration:_offsetAnimationGeneration
                                   completionGeneration:generation] ||
        ![animator claimCompletion] ||
        ![self p_isCurrentScrollWrite:operation]) {
        return;
    }
    _offsetAnimator = nil;
    BOOL reachedTarget = fabs(self.contentOffset.x - operation.targetOffset.x) <= 1.0 &&
        fabs(self.contentOffset.y - operation.targetOffset.y) <= 1.0;
    if (reachedTarget && operation.kind == KRScrollWriteKindContentInset &&
        !UIEdgeInsetsEqualToEdgeInsets(self.contentInset, operation.targetInset)) {
        self.contentInset = operation.targetInset;
        _nativeInsetRevision += 1;
    }
    NSDictionary *eventParams = [self p_generateEventBaseParams];
    dispatch_block_t terminal = [self p_finalizeScrollWrite:operation
                                                  resultCode:reachedTarget
                                                      ? KRScrollWriteResultCodeCommitted
                                                      : KRScrollWriteResultCodeInterrupted];
    // The display link may stop before observing UIKit's final presentation frame.
    [self dispatchScrollEventWithCurOffset:self.contentOffset];
    if ([KRScrollViewOffsetAnimator shouldEmitTerminalForNativePhase:[self p_nativeScrollPhase]] &&
        _css_scrollEnd && eventParams) {
        _css_scrollEnd(eventParams);
    }
    if (terminal) terminal();
}

- (void)dealloc {
    [self p_invalidateOffsetAnimation];
}

#pragma mark - KRTurboDisplayStateRestorableProtocol

- (void)applyTurboDisplayExtraCacheContent:(NSDictionary *)extraCacheProps {
    // 恢复 contentOffset
    if (extraCacheProps[@"contentOffsetX"] || extraCacheProps[@"contentOffsetY"]) {
        CGFloat offsetX = [extraCacheProps[@"contentOffsetX"] doubleValue];
        CGFloat offsetY = [extraCacheProps[@"contentOffsetY"] doubleValue];
        [self setContentOffset:CGPointMake(offsetX, offsetY) animated:NO];
    }
}

@end


@interface KRScrollContentView ()
@property (nonatomic, weak) id<KRScrollContentViewDelegate> delegate;
@end

@implementation KRScrollContentView {
    /* 一对多代理转发 */
    KRMultiDelegateProxy *_delegateProxy;
}


- (instancetype)initWithFrame:(CGRect)frame {
    if (self = [super initWithFrame:frame]) {
        _delegateProxy = [KRMultiDelegateProxy alloc];
        [_delegateProxy addDelegate:self];
        self.delegate = (id<KRScrollContentViewDelegate>)_delegateProxy;
    }
    return self;
}

#pragma mark - KuiklyRenderViewExportProtocol

- (void)hrv_setPropWithKey:(NSString *)propKey propValue:(id)propValue {
    KUIKLY_SET_CSS_COMMON_PROP
}

- (void)setFrame:(CGRect)frame {
#if TARGET_OS_OSX // [macOS]
    KRScrollView *scrollView = nil;
    // macOS: documentView.superview is NSClipView, need to find KRScrollView
    NSView *currentView = self.superview;
    while (currentView) {
        if ([currentView isKindOfClass:[KRScrollView class]]) {
            scrollView = (KRScrollView *)currentView;
            break;
        }
        currentView = currentView.superview;
    }
#else
    KRScrollView *scrollView = (KRScrollView *)self.superview;
#endif
    
    if (scrollView) {
        scrollView.skipNestScrollLock = YES;
    }
    [super setFrame:frame];
    [self syncScrollViewContentSize];
}
- (void)didMoveToSuperview {
    [super didMoveToSuperview];
    [self syncScrollViewContentSize];
}

- (void)syncScrollViewContentSize {
    if (self.superview) {
        #if TARGET_OS_OSX // [macOS]
        // On macOS, KRScrollContentView is added to documentView, not directly to KRScrollView
        // Need to traverse up the view hierarchy to find the scroll view
        KRScrollView *scrollView = nil;
        KRPlatformView *view = self.superview;
        while (view) {
            if ([view isKindOfClass:[KRScrollView class]]) {
                scrollView = (KRScrollView *)view;
                break;
            }
            view = view.superview;
        }
        #else // iOS
        // On iOS, KRScrollContentView is directly added to KRScrollView
        KRScrollView *scrollView = (KRScrollView *)self.superview;
        #endif // [macOS]
        
        if ([scrollView isKindOfClass:[KRScrollView class]]) {
            if ([scrollView p_nativeScrollPhase] != 0) {
                scrollView.autoAdjustContentOffsetDisable = YES;
            }
            scrollView.setContentSizeing = YES;
            scrollView.contentSize = CGSizeMake(CGRectGetWidth(self.frame), CGRectGetHeight(self.frame));
            scrollView.setContentSizeing = NO;
            scrollView.autoAdjustContentOffsetDisable = NO;
        }
    }
}

#pragma mark - pubilc

 
/*
 * 添加滚动监听
 */
- (void)addScrollContentViewDelegate:(id<KRScrollContentViewDelegate>)scrollContentViewDelegate {
    [_delegateProxy addDelegate:scrollContentViewDelegate];
}
/*
 * 删除滚动监听
 */
- (void)removeScrollContentViewDelegate:(id<KRScrollContentViewDelegate>)scrollContentViewDelegate {
    [_delegateProxy removeDelegate:scrollContentViewDelegate];
}

#pragma mark - override

- (BOOL)pointInside:(CGPoint)point withEvent:(UIEvent *)event {
    BOOL result = [super pointInside:point withEvent:event];
    KRScrollView *scrollView = (KRScrollView *)self.superview;
    if ([scrollView isKindOfClass:[KRScrollView class]]) {
        UIEdgeInsets insets = scrollView.contentInset;
        result = CGRectContainsPoint([KRConvertUtil hr_rectInset:self.bounds insets:insets], point);
    }
    return result;
}

- (void)insertSubview:(UIView *)view atIndex:(NSInteger)index {
    [super insertSubview:view atIndex:index];
    if ([self.delegate respondsToSelector:@selector(contentViewDidInsertSubview)]) {
        [self.delegate contentViewDidInsertSubview];
    }
}

@end
