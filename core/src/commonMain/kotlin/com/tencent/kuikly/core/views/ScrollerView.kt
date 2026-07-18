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

package com.tencent.kuikly.core.views

import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.base.event.Event
import com.tencent.kuikly.core.base.event.Touch
import com.tencent.kuikly.core.collection.fastHashSetOf
import com.tencent.kuikly.core.collection.toFastMutableList
import com.tencent.kuikly.core.layout.FlexDirection
import com.tencent.kuikly.core.layout.Frame
import com.tencent.kuikly.core.layout.StyleSpace
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.IPagerLayoutEventObserver
import com.tencent.kuikly.core.views.internal.GroupEvent

interface IScrollerViewEventObserver {
    fun onContentOffsetDidChanged(
        contentOffsetX: Float,
        contentOffsetY: Float,
        params: ScrollParams
    )
    fun subViewsDidLayout()

    /**
     * Same as [scrollerScrollDidEnd], keep for compatibility.
     * Please use [scrollerScrollDidEnd] instead.
     */
    @Deprecated("Use scrollerScrollDidEnd instead")
    fun onScrollEnd(params: ScrollParams) {}

    fun contentViewDidSetFrameToRenderView() {}

    fun contentSizeDidChanged(width: Float, height: Float) { }

    fun scrollerDragBegin(params: ScrollParams) {}
    
    fun scrollerDragEnd(params: ScrollParams) {}

    fun scrollerScrollDidEnd(params: ScrollParams) {}

    fun scrollFrameDidChanged(frame: Frame) {}

    fun visibleAreaMarginChanged() {}
}

open class ScrollerView<A : ScrollerAttr, E : ScrollerEvent> :
    ViewContainer<A, E>() {
    var curOffsetX: Float = 0f
        private set
    var curOffsetY: Float = 0f
        private set
    var isDragging: Boolean = false
    var nativeScrollPhase: NativeScrollPhase = NativeScrollPhase.Idle
        internal set

    var nativeInteractionEpoch: Long = 0L
        internal set

    var nativeLayoutRevision: Long = 0L
        internal set

    var nativeInsetRevision: Long = 0L
        internal set
    var offsetWriteGeneration: Long = 0L
        private set
    private val contentOffsetWriteLedger = ContentOffsetWriteLedger()
    private var contentInsetWriteOperation = 0L
    private var contentInsetArmGeneration = 0L
    private val composeWriteOperationLedger = ComposeWriteOperationLedger()
    private var composeAnimatedUnderlyingPhase: NativeScrollPhase? = null
    private var composeAnimatedPhaseOwner: ComposeAnimatedPhaseOwner? = null
    private val composeAnimatedPhasePredecessors = mutableMapOf<
        ComposeAnimatedPhaseOwner,
        ComposeAnimatedPhaseOwner?,
    >()
    private val composeAnimatedPhaseUnderlyingPhases = mutableMapOf<
        ComposeAnimatedPhaseOwner,
        NativeScrollPhase,
    >()
    private val inactiveComposeAnimatedPhaseOwners = mutableSetOf<ComposeAnimatedPhaseOwner>()
    private var composeAnimatedPhaseAuthorityGeneration = 0L
    private var nativeRevisionWaiterSequence = 0L
    private val nativeRevisionWaiters = mutableMapOf<Long, NativeRevisionWaiter>()

    val contentViewOffsetX: Float
      get() {
          return contentView?.offsetX ?: 0f
      }

    val contentViewOffsetY: Float
        get() {
            return contentView?.offsetY ?: 0f
        }

    private var lastFrame = Frame.zero
    internal var shouldListenWillEndDrag = false

    var contentView: ScrollerContentView? = null
         private set
    private val scrollerViewEventObserverSet by lazy(LazyThreadSafetyMode.NONE) {
        fastHashSetOf<IScrollerViewEventObserver>()
    }

    fun addScrollerViewEventObserver(observer: IScrollerViewEventObserver) {
        scrollerViewEventObserverSet.add(observer)
    }

    fun removeScrollerViewEventObserver(observer: IScrollerViewEventObserver) {
        scrollerViewEventObserverSet.remove(observer)
    }

    open fun createContentView(): ScrollerContentView {
        return ScrollerContentView()
    }

    /**
     * 设置内容的偏移量。
     *
     * @param offsetX X轴的偏移量。
     * @param offsetY Y轴的偏移量。
     * @param animated 是否使用动画进行偏移，默认为 false。
     * @param springAnimation 弹簧动画参数，可为空，默认为 null。
     */
    fun setContentOffset(
        offsetX: Float,
        offsetY: Float,
        animated: Boolean = false,
        springAnimation: SpringAnimation? = null,
        writeToken: ScrollOffsetCommitToken? = null,
        onCommitResult: ((Boolean) -> Unit)? = null,
        onCommitResultDetailed: ((ScrollWriteResult) -> Unit)? = null,
    ) {
        val commitToken = writeToken ?: nextLegacyWriteToken(requiresNativeIdle = false)
        val contentOffset = transformInputSetContentOffset(offsetX, offsetY)
        if (!claimComposeWriteOperation(commitToken)) {
            val result = currentScrollWriteResult(ScrollWriteResultCode.Stale)
            onCommitResultDetailed?.invoke(result)
            onCommitResult?.invoke(false)
            return
        }
        performTaskWhenRenderViewDidLoad {
            if (!canCommitOffsetWrite(commitToken)) {
                val code = if (commitToken.requiresNativeIdle &&
                    nativeScrollPhase != NativeScrollPhase.Idle
                ) {
                    ScrollWriteResultCode.Busy
                } else {
                    ScrollWriteResultCode.Stale
                }
                val result = currentScrollWriteResult(code)
                onCommitResultDetailed?.invoke(result)
                onCommitResult?.invoke(false)
                return@performTaskWhenRenderViewDidLoad
            }
            val writeSequence = contentOffsetWriteLedger.beginWrite(
                currentOffset = contentViewOffsetX to contentViewOffsetY,
            )
            val updatesContentOffsetImmediately = !animated && springAnimation == null
            val phaseBeforeAnimatedWrite = beginComposeWritePhase(
                animated = !updatesContentOffsetImmediately,
                writeToken = commitToken,
            )
            if (updatesContentOffsetImmediately) {
                contentView?.contentOffsetWillChanged(contentOffset.first, contentOffset.second)
            }
            val commitResult = run {
                { result: ScrollWriteResult ->
                    updateNativeRevisions(result)
                    val committed = result.committed
                    val current = committed && isCurrentComposeWriteOperation(commitToken)
                    if (result.installed) {
                        markComposeWritePhaseTerminal(phaseBeforeAnimatedWrite)
                    }
                    if (!result.installed) {
                        rollbackComposeWritePhase(phaseBeforeAnimatedWrite)
                    } else if (updatesContentOffsetImmediately && current) {
                        commitImmediateComposeWritePhase(phaseBeforeAnimatedWrite)
                    }
                    if (current) {
                        contentOffsetWriteLedger.confirmWrite(writeSequence, contentOffset)
                    } else if (!committed &&
                        isCurrentComposeWriteOperation(commitToken) &&
                        updatesContentOffsetImmediately) {
                        contentOffsetWriteLedger.rollbackTarget(writeSequence)?.let { rollback ->
                            contentView?.contentOffsetWillChanged(rollback.first, rollback.second)
                        }
                    }
                    if (result.installed &&
                        !updatesContentOffsetImmediately &&
                        (!committed || result.code == ScrollWriteResultCode.AlreadySatisfied) &&
                        commitToken.generation == offsetWriteGeneration &&
                        nativeScrollPhase == NativeScrollPhase.SettlingOrAnimating
                    ) {
                        restoreComposeAnimatedPhase(phaseBeforeAnimatedWrite)
                    }
                    val currentResult = if (current) result else ScrollWriteResult(
                        code = if (result.committed) ScrollWriteResultCode.Stale else result.code,
                        nativeInteractionEpoch = result.nativeInteractionEpoch,
                        layoutRevision = result.layoutRevision,
                        insetRevision = result.insetRevision,
                        accepted = result.accepted,
                        installed = result.installed,
                        replacedPrevious = result.replacedPrevious,
                    )
                    onCommitResultDetailed?.invoke(currentResult)
                    onCommitResult?.invoke(currentResult.committed)
                    dispatchNativeRevisionWaiters()
                    Unit
                }
            }
            callContentOffset(
                contentOffset.first,
                contentOffset.second,
                animated,
                springAnimation,
                commitToken,
                commitResult,
            )
        }
    }

    /**
     * 设置内容的偏移量。
     *
     * @param offsetX X轴的偏移量。
     * @param offsetY Y轴的偏移量。
     * @param animation 动画参数，可为空。
     */
    fun setContentOffset(offsetX: Float, offsetY: Float, animation: SetContentOffsetAnimation?) {
        val commitToken = nextLegacyWriteToken(requiresNativeIdle = false)
        if (!claimComposeWriteOperation(commitToken)) return
        performTaskWhenRenderViewDidLoad {
            if (!canCommitOffsetWrite(commitToken)) return@performTaskWhenRenderViewDidLoad
            val animationString = animation?.toString() ?: " 0 0 0 0"
            val phaseBeforeAnimatedWrite = beginComposeWritePhase(
                animated = animation != null,
                writeToken = commitToken,
            )
            val tokenString = " ${commitToken.generation} ${commitToken.requiresNativeIdle.toInt()}" +
                " ${commitToken.operationGeneration} ${commitToken.expectedContentSize}" +
                " ${commitToken.expectedViewportSize}${commitToken.extendedIdentityWireSuffix()}"
            renderView?.callMethod(
                "contentOffset",
                "$offsetX $offsetY ${if (animation != null) 1 else 0}${animationString}$tokenString",
            ) { result ->
                val decoded = decodeScrollWriteResult(result)
                updateNativeRevisions(decoded)
                if (decoded.installed) {
                    markComposeWritePhaseTerminal(phaseBeforeAnimatedWrite)
                }
                if (!decoded.installed) {
                    rollbackComposeWritePhase(phaseBeforeAnimatedWrite)
                } else if (animation == null && decoded.committed &&
                    isCurrentComposeWriteOperation(commitToken)) {
                    commitImmediateComposeWritePhase(phaseBeforeAnimatedWrite)
                }
                if (decoded.installed &&
                    animation != null &&
                    (!decoded.committed || decoded.code == ScrollWriteResultCode.AlreadySatisfied) &&
                    nativeScrollPhase == NativeScrollPhase.SettlingOrAnimating
                ) {
                    restoreComposeAnimatedPhase(phaseBeforeAnimatedWrite)
                }
                dispatchNativeRevisionWaiters()
            }
        }
    }

    fun abortContentOffsetAnimate() {
        performTaskWhenRenderViewDidLoad {
            renderView?.callMethod("abortContentOffsetAnimate")
        }
    }

    /** Clear transient state for Compose DSL reuse (not the native reuse pool). */
    fun prepareForComposeReuse(beforeNativePrepare: () -> Unit = {}) {
        offsetWriteGeneration += 1L
        nativeInteractionEpoch += 1L
        nativeLayoutRevision += 1L
        nativeInsetRevision += 1L
        contentOffsetWriteLedger.invalidateWrites(
            currentOffset = contentViewOffsetX to contentViewOffsetY,
        )
        contentInsetWriteOperation += 1L
        contentInsetArmGeneration += 1L
        composeWriteOperationLedger.invalidate()
        invalidateComposeAnimatedPhase()
        nativeRevisionWaiters.clear()
        val generation = offsetWriteGeneration
        nativeScrollPhase = NativeScrollPhase.Idle
        beforeNativePrepare()
        performTaskWhenRenderViewDidLoad {
            renderView?.callMethod("prepareForComposeReuse", generation.toString())
        }
    }

    open fun callContentOffset(
        offsetX: Float,
        offsetY: Float,
        animated: Boolean = false,
        springAnimation: SpringAnimation? = null,
        writeToken: ScrollOffsetCommitToken? = null,
        onCommitResult: ((ScrollWriteResult) -> Unit)? = null,
    ) {
        val springAnimationString = springAnimation?.toString()
            ?: if (writeToken != null) " 0 0 0 0" else ""
        val tokenString = writeToken?.let {
            " ${it.generation} ${it.requiresNativeIdle.toInt()} ${it.operationGeneration}" +
                " ${it.expectedContentSize} ${it.expectedViewportSize}" +
                " ${it.bindingGeneration} ${it.capabilityKind} ${it.capabilityLeaseId}" +
                " ${it.semanticOperationId} ${it.attemptGeneration} ${it.nativeInteractionEpoch}" +
                " ${it.layoutRevision} ${it.anchorRevision} ${it.rangeRevision} ${it.insetRevision}"
        } ?: ""
        renderView?.callMethod(
            "contentOffset",
            "${offsetX} ${offsetY} ${animated.toInt()}${springAnimationString}$tokenString",
            onCommitResult?.let { callback ->
                { result -> callback(decodeScrollWriteResult(result)) }
            },
        )

    }

    private fun canCommitOffsetWrite(writeToken: ScrollOffsetCommitToken?): Boolean {
        if (writeToken == null) return true
        return writeToken.generation == offsetWriteGeneration &&
            (!writeToken.requiresNativeIdle || nativeScrollPhase == NativeScrollPhase.Idle) &&
            writeToken.nativeInteractionEpoch == nativeInteractionEpoch &&
            writeToken.layoutRevision == nativeLayoutRevision &&
            writeToken.insetRevision == nativeInsetRevision &&
            isCurrentComposeWriteOperation(writeToken)
    }

    private fun claimComposeWriteOperation(writeToken: ScrollOffsetCommitToken?): Boolean {
        val operation = writeToken?.operationGeneration ?: return true
        return composeWriteOperationLedger.claim(operation)
    }

    private fun nextLegacyWriteToken(requiresNativeIdle: Boolean): ScrollOffsetCommitToken {
        val operation = composeWriteOperationLedger.nextAfter(contentInsetWriteOperation)
        contentInsetWriteOperation = maxOf(contentInsetWriteOperation, operation)
        return ScrollOffsetCommitToken(
            generation = offsetWriteGeneration,
            requiresNativeIdle = requiresNativeIdle,
            operationGeneration = operation,
            nativeInteractionEpoch = nativeInteractionEpoch,
            layoutRevision = nativeLayoutRevision,
            insetRevision = nativeInsetRevision,
        )
    }

    private fun isCurrentComposeWriteOperation(writeToken: ScrollOffsetCommitToken?): Boolean {
        val operation = writeToken?.operationGeneration ?: return true
        return composeWriteOperationLedger.isCurrent(operation)
    }

    private fun beginComposeWritePhase(
        animated: Boolean,
        writeToken: ScrollOffsetCommitToken,
    ): ComposeAnimatedPhaseSnapshot {
        val owner = ComposeAnimatedPhaseOwner(
            operationGeneration = writeToken.operationGeneration,
            attemptGeneration = writeToken.attemptGeneration,
        )
        val underlyingPhase = if (
            nativeScrollPhase == NativeScrollPhase.SettlingOrAnimating &&
            composeAnimatedUnderlyingPhase != null
        ) {
            composeAnimatedUnderlyingPhase ?: nativeScrollPhase
        } else {
            nativeScrollPhase
        }
        val snapshot = ComposeAnimatedPhaseSnapshot(
            animated = animated,
            underlyingPhase = underlyingPhase,
            authorityGeneration = composeAnimatedPhaseAuthorityGeneration,
            owner = owner,
            previousOwner = composeAnimatedPhaseOwner,
        )
        if (animated) {
            composeAnimatedPhasePredecessors[owner] = composeAnimatedPhaseOwner
            composeAnimatedPhaseUnderlyingPhases[owner] = underlyingPhase
            inactiveComposeAnimatedPhaseOwners.remove(owner)
            composeAnimatedUnderlyingPhase = underlyingPhase
            composeAnimatedPhaseOwner = owner
            nativeScrollPhase = NativeScrollPhase.SettlingOrAnimating
        }
        return snapshot
    }

    private fun rollbackComposeWritePhase(phase: ComposeAnimatedPhaseSnapshot) {
        if (!phase.animated) return
        if (phase.authorityGeneration != composeAnimatedPhaseAuthorityGeneration) return
        inactiveComposeAnimatedPhaseOwners += phase.owner
        if (phase.owner != composeAnimatedPhaseOwner) return

        var predecessor = phase.previousOwner
        while (predecessor != null && predecessor in inactiveComposeAnimatedPhaseOwners) {
            predecessor = composeAnimatedPhasePredecessors[predecessor]
        }
        if (predecessor != null) {
            composeAnimatedPhaseOwner = predecessor
            composeAnimatedUnderlyingPhase =
                composeAnimatedPhaseUnderlyingPhases[predecessor] ?: phase.underlyingPhase
            nativeScrollPhase = NativeScrollPhase.SettlingOrAnimating
        } else {
            invalidateComposeAnimatedPhase()
            nativeScrollPhase = phase.underlyingPhase
        }
    }

    private fun markComposeWritePhaseTerminal(phase: ComposeAnimatedPhaseSnapshot) {
        if (!phase.animated) return
        if (phase.authorityGeneration != composeAnimatedPhaseAuthorityGeneration) return
        inactiveComposeAnimatedPhaseOwners += phase.owner
    }

    private fun commitImmediateComposeWritePhase(phase: ComposeAnimatedPhaseSnapshot) {
        if (phase.authorityGeneration != composeAnimatedPhaseAuthorityGeneration) return
        invalidateComposeAnimatedPhase()
        nativeScrollPhase = phase.underlyingPhase
    }

    private fun restoreComposeAnimatedPhase(phase: ComposeAnimatedPhaseSnapshot) {
        if (phase.authorityGeneration != composeAnimatedPhaseAuthorityGeneration) return
        if (phase.owner != composeAnimatedPhaseOwner) return
        nativeScrollPhase = phase.underlyingPhase
        invalidateComposeAnimatedPhase()
    }

    private fun invalidateComposeAnimatedPhase() {
        composeAnimatedUnderlyingPhase = null
        composeAnimatedPhaseOwner = null
        composeAnimatedPhasePredecessors.clear()
        composeAnimatedPhaseUnderlyingPhases.clear()
        inactiveComposeAnimatedPhaseOwners.clear()
        composeAnimatedPhaseAuthorityGeneration += 1L
    }

    internal fun updateNativeScrollPhaseFromNative(
        phase: NativeScrollPhase,
        sourceOperationGeneration: Long = 0L,
    ): Boolean {
        if (sourceOperationGeneration <= 0L) {
            invalidateComposeAnimatedPhase()
        } else if (sourceOperationGeneration != composeAnimatedPhaseOwner?.operationGeneration) {
            return false
        } else if (phase == NativeScrollPhase.Idle) {
            invalidateComposeAnimatedPhase()
        }
        nativeScrollPhase = phase
        return true
    }

    internal fun contentViewDidSetFrameToRenderView() {
        scrollerViewEventObserverSet.toFastMutableList().forEach {
            it.contentViewDidSetFrameToRenderView()
        }
    }

    /* 转换输入设置的滚动偏移量 */
    open fun transformInputSetContentOffset(offsetX: Float, offsetY: Float): Pair<Float, Float> {
        return Pair(offsetX, offsetY)
    }

    /* 转换输入设置的滚动偏移量 */
    open fun transformOutputContentOffset(offsetX: Float, offsetY: Float): Pair<Float, Float> {
        return Pair(offsetX, offsetY)
    }

    fun setContentInset(
        top: Float = 0f,
        left: Float = 0f,
        bottom: Float = 0f,
        right: Float = 0f,
        animated: Boolean = false,
        writeToken: ScrollOffsetCommitToken? = null,
        onCommitResult: ((Boolean) -> Unit)? = null,
        onCommitResultDetailed: ((ScrollWriteResult) -> Unit)? = null,
    ) {
        val commitToken = writeToken ?: nextLegacyWriteToken(requiresNativeIdle = true)
        contentInsetWriteOperation = maxOf(
            contentInsetWriteOperation,
            commitToken.operationGeneration,
        )
        if (!claimComposeWriteOperation(commitToken)) {
            onCommitResultDetailed?.invoke(currentScrollWriteResult(ScrollWriteResultCode.Stale))
            onCommitResult?.invoke(false)
            return
        }
        performTaskWhenRenderViewDidLoad {
            if (!canCommitOffsetWrite(commitToken)) {
                val code = if (commitToken.requiresNativeIdle &&
                    nativeScrollPhase != NativeScrollPhase.Idle
                ) {
                    ScrollWriteResultCode.Busy
                } else {
                    ScrollWriteResultCode.Stale
                }
                onCommitResultDetailed?.invoke(currentScrollWriteResult(code))
                onCommitResult?.invoke(false)
                return@performTaskWhenRenderViewDidLoad
            }
            val writeSequence = commitToken.operationGeneration
            val phaseBeforeAnimatedWrite = beginComposeWritePhase(
                animated = animated,
                writeToken = commitToken,
            )
            renderView?.callMethod(
                "contentInset",
                    "$top $left $bottom $right ${animated.toInt()} ${commitToken.generation} " +
                    "${commitToken.requiresNativeIdle.toInt()} ${commitToken.operationGeneration} " +
                    "${commitToken.expectedContentSize} ${commitToken.expectedViewportSize}" +
                    commitToken.extendedIdentityWireSuffix(),
                { result ->
                    val decoded = decodeScrollWriteResult(result)
                    updateNativeRevisions(decoded)
                    val committed = decoded.committed &&
                        isCurrentComposeWriteOperation(commitToken)
                    if (decoded.installed) {
                        markComposeWritePhaseTerminal(phaseBeforeAnimatedWrite)
                    }
                    if (!decoded.installed) {
                        rollbackComposeWritePhase(phaseBeforeAnimatedWrite)
                    } else if (!animated && committed) {
                        commitImmediateComposeWritePhase(phaseBeforeAnimatedWrite)
                    }
                    if (
                        decoded.installed &&
                        animated &&
                        (!decoded.committed || decoded.code == ScrollWriteResultCode.AlreadySatisfied) &&
                        commitToken.generation == offsetWriteGeneration &&
                        nativeScrollPhase == NativeScrollPhase.SettlingOrAnimating
                    ) {
                        restoreComposeAnimatedPhase(phaseBeforeAnimatedWrite)
                    }
                    onCommitResultDetailed?.invoke(
                        if (committed) decoded else decoded.copy(
                            code = if (decoded.committed) ScrollWriteResultCode.Stale else decoded.code,
                        ),
                    )
                    onCommitResult?.invoke(committed)
                    dispatchNativeRevisionWaiters()
                },
            )
        }
    }

    fun setContentInsetWhenEndDrag(
        top: Float = 0f,
        left: Float = 0f,
        bottom: Float = 0f,
        right: Float = 0f,
        writeToken: ScrollOffsetCommitToken? = null,
    ) {
        val armIdentity = ++contentInsetArmGeneration
        val commitToken = (writeToken ?: ScrollOffsetCommitToken(
            generation = offsetWriteGeneration,
            requiresNativeIdle = false,
            nativeInteractionEpoch = nativeInteractionEpoch,
            layoutRevision = nativeLayoutRevision,
            insetRevision = nativeInsetRevision,
            semanticOperationId = armIdentity,
            attemptGeneration = armIdentity,
        )).copy(operationGeneration = 0L)
        performTaskWhenRenderViewDidLoad {
            if (!canCommitOffsetWrite(commitToken)) return@performTaskWhenRenderViewDidLoad
            renderView?.callMethod(
                "contentInsetWhenEndDrag",
                "$top $left $bottom $right 0 ${commitToken.generation} " +
                    "${commitToken.requiresNativeIdle.toInt()} ${commitToken.operationGeneration} " +
                    "${commitToken.expectedContentSize} ${commitToken.expectedViewportSize}" +
                    commitToken.extendedIdentityWireSuffix(),
            )
        }
    }

    override fun <T : DeclarativeBaseView<*, *>> addChild(
        child: T,
        init: T.() -> Unit,
        index: Int
    ) {
        initScrollerContentComponentIfNeed()
        contentView!!.addChild(child, init, index)
    }

    override fun realContainerView(): ViewContainer<*, *> {
        if (contentView != null) {
            return contentView!!
        }
        return this
    }

    override fun willInit() {
        super.willInit()
        attr.overflow(true)
    }

    override fun didInit() {
        super.didInit()
        listenScrollEvent()
    }

    override fun createAttr(): A {
        return ScrollerAttr() as A
    }

    override fun createEvent(): E {
        return ScrollerEvent() as E
    }

    override fun viewName(): String {
        return ViewConst.TYPE_SCROLLER
    }

    override fun didRemoveFromParentView() {
        super.didRemoveFromParentView()
        scrollerViewEventObserverSet.clear()
    }

    override fun layoutFrameDidChanged(frame: Frame) {
        super.layoutFrameDidChanged(frame)
        if (lastFrame.isDefaultValue() || lastFrame.width != frame.width || lastFrame.height != frame.height) {
            nativeLayoutRevision += 1L
        }
        scrollerViewEventObserverSet.toFastMutableList().forEach {
            it.scrollFrameDidChanged(frame)
        }
        if (!lastFrame.isDefaultValue()
            && (lastFrame.width != frame.width || lastFrame.height != frame.height)) { // scrollView size非首次变化
            subViewsDidLayout()
        }
        lastFrame = frame
        dispatchNativeRevisionWaiters()
    }

    internal fun subViewsDidLayout() {
        scrollerViewEventObserverSet.toFastMutableList().forEach {
            it.subViewsDidLayout()
        }
    }

    fun initScrollerContentComponentIfNeed() {
        if (contentView === null) {
            contentView = createContentView()
            contentView?.also {
                it.pagerId = this.pagerId
                it.flexNode.flexDirection = flexNode.flexDirection
                it.flexNode.justifyContent = flexNode.justifyContent
                it.flexNode.alignItems = flexNode.alignItems
                it.flexNode.flexWrap = flexNode.flexWrap
                it.flexNode.setPadding(StyleSpace.Type.TOP, flexNode.getPadding(StyleSpace.Type.TOP))
                it.flexNode.setPadding(StyleSpace.Type.LEFT, flexNode.getPadding(StyleSpace.Type.LEFT))
                it.flexNode.setPadding(StyleSpace.Type.RIGHT, flexNode.getPadding(StyleSpace.Type.RIGHT))
                it.flexNode.setPadding(StyleSpace.Type.BOTTOM, flexNode.getPadding(StyleSpace.Type.BOTTOM))
            }
            if (flexNode.flexDirection == FlexDirection.ROW
                || flexNode.flexDirection == FlexDirection.ROW_REVERSE
            ) {
                super.addChild(contentView!!, {
                    attr {
                        absolutePosition(top = 0f, left = 0f, bottom = 0f)
                    }
                }, 0)
            } else {
                super.addChild(contentView!!, {
                    attr {
                        absolutePosition(top = 0f, left = 0f, right = 0f)
                    }
                }, 0)
            }
            insertDomSubView(contentView!!, 0)
            // 暂时的解决方案：清除ScrollerView的padding，保留ScollerContentView的padding
            flexNode.setPadding(StyleSpace.Type.ALL, 0f)
        }
    }

    private fun handleListDidScroll(offsetX: Float, offsetY: Float, params: ScrollParams) {
        updateNativeRevisions(params)
        curOffsetX = offsetX
        curOffsetY = offsetY
        contentOffsetWriteLedger.recordNativeOffset(offsetX to offsetY)
        contentView?.contentOffsetWillChanged(offsetX, offsetY)
        scrollerViewEventObserverSet.toFastMutableList().forEach {
            it.onContentOffsetDidChanged(curOffsetX, curOffsetY, params)
        }
        contentView?.contentOffsetDidChanged(offsetX, offsetY, params)
        dispatchNativeRevisionWaiters()
    }

    private fun handleListDidScrollEnd(params: ScrollParams) {
        updateNativeRevisions(params)
        curOffsetX = params.offsetX
        curOffsetY = params.offsetY
        contentOffsetWriteLedger.recordNativeOffset(params.offsetX to params.offsetY)
        contentView?.contentOffsetWillChanged(params.offsetX, params.offsetY)
        scrollerViewEventObserverSet.toFastMutableList().forEach {
            it.onScrollEnd(params)
            it.scrollerScrollDidEnd(params)
        }
        contentView?.contentOffsetDidChanged(params.offsetX, params.offsetY, params)
        dispatchNativeRevisionWaiters()
    }

    fun awaitNativeRevisionAdvance(
        interactionEpoch: Long,
        layoutRevision: Long,
        insetRevision: Long,
        callback: () -> Unit,
    ): Long {
        val id = ++nativeRevisionWaiterSequence
        nativeRevisionWaiters[id] = NativeRevisionWaiter(
            interactionEpoch = interactionEpoch,
            layoutRevision = layoutRevision,
            insetRevision = insetRevision,
            callback = callback,
        )
        return id
    }

    fun cancelNativeRevisionWaiter(id: Long) {
        nativeRevisionWaiters.remove(id)
    }

    private fun dispatchNativeRevisionWaiters() {
        val ready = nativeRevisionWaiters.mapNotNull { (id, waiter) ->
            if (nativeInteractionEpoch > waiter.interactionEpoch ||
                nativeLayoutRevision > waiter.layoutRevision ||
                nativeInsetRevision > waiter.insetRevision
            ) {
                id to waiter.callback
            } else {
                null
            }
        }
        ready.forEach { (id, _) -> nativeRevisionWaiters.remove(id) }
        ready.forEach { (_, callback) -> callback() }
    }

    private fun updateNativeRevisions(params: ScrollParams) {
        nativeInteractionEpoch = maxOf(nativeInteractionEpoch, params.nativeInteractionEpoch)
        nativeLayoutRevision = maxOf(nativeLayoutRevision, params.layoutRevision)
        nativeInsetRevision = maxOf(nativeInsetRevision, params.insetRevision)
    }

    internal fun acceptNativeScrollEvent(params: ScrollParams): Boolean {
        if (nativeInteractionEpoch > 0L && params.nativeInteractionEpoch <= 0L) {
            return false
        }
        if (params.nativeInteractionEpoch > 0L &&
            params.nativeInteractionEpoch < nativeInteractionEpoch
        ) {
            return false
        }
        updateNativeRevisions(params)
        return true
    }

    private fun updateNativeRevisions(result: ScrollWriteResult) {
        if (result.nativeInteractionEpoch >= 0L) {
            nativeInteractionEpoch = maxOf(nativeInteractionEpoch, result.nativeInteractionEpoch)
        }
        if (result.layoutRevision >= 0L) {
            nativeLayoutRevision = maxOf(nativeLayoutRevision, result.layoutRevision)
        }
        if (result.insetRevision >= 0L) {
            nativeInsetRevision = maxOf(nativeInsetRevision, result.insetRevision)
        }
    }

    private fun currentScrollWriteResult(code: ScrollWriteResultCode): ScrollWriteResult =
        ScrollWriteResult(
            code = code,
            nativeInteractionEpoch = nativeInteractionEpoch,
            layoutRevision = nativeLayoutRevision,
            insetRevision = nativeInsetRevision,
        )

    fun listenScrollEvent() {
        val ctx = this
        val scrollHandler = event.handlerWithEventName(ScrollerEvent.ScrollerEventConst.SCROLL)
        val scrollEndHandler = event.handlerWithEventName(ScrollerEvent.ScrollerEventConst.SCROLL_END)
        val dragBeginHandler = event.handlerWithEventName(ScrollerEvent.ScrollerEventConst.DRAG_BEGIN)
        val dragEndHandler = event.handlerWithEventName(ScrollerEvent.ScrollerEventConst.DRAG_END)
        event {
            scroll(ctx.attr.syncScroll || this.syncScroll) {
                val contentOffset = ctx.transformOutputContentOffset(it.offsetX, it.offsetY)
                it.offsetX = contentOffset.first
                it.offsetY = contentOffset.second
                it.also {
                    ctx.handleListDidScroll(it.offsetX, it.offsetY, it)
                }
                scrollHandler?.invoke(it)
                ctx.getExternalScrollEventHandler(ScrollerEvent.ScrollerEventConst.SCROLL)?.invoke(it)
            }
            dragBegin { scrollParam ->
                this@ScrollerView.scrollerViewEventObserverSet.toFastMutableList().forEach {
                    it.scrollerDragBegin(scrollParam)
                }
                dragBeginHandler?.invoke(scrollParam)
                ctx.getExternalScrollEventHandler(ScrollerEvent.ScrollerEventConst.DRAG_BEGIN)?.invoke(scrollParam)
            }
            dragEnd { scrollParam ->
                this@ScrollerView.scrollerViewEventObserverSet.toFastMutableList().forEach {
                    it.scrollerDragEnd(scrollParam)
                }
                dragEndHandler?.invoke(scrollParam)
                ctx.getExternalScrollEventHandler(ScrollerEvent.ScrollerEventConst.DRAG_END)?.invoke(scrollParam)
            }
            scrollEnd {
                ctx.handleListDidScrollEnd(it)
                scrollEndHandler?.invoke(it)
                ctx.getExternalScrollEventHandler(ScrollerEvent.ScrollerEventConst.SCROLL_END)?.invoke(it)
            }
        }
    }

    /**
     * 获取通过 [extProps] 注册的外部滚动事件 handler，避免直接 Event.register 覆盖 [listenScrollEvent] 的 wrapper chain。
     */
    @Suppress("UNCHECKED_CAST")
    fun getExternalScrollEventHandler(eventName: String): ((ScrollParams) -> Unit)? {
        return extProps[EXTERNAL_SCROLL_EVENT_PREFIX + eventName] as? ((ScrollParams) -> Unit)
    }

    /**
     * 设置外部滚动事件 handler 到 [extProps]，供 [listenScrollEvent] 的 wrapper 运行时读取。
     */
    fun setExternalScrollEventHandler(eventName: String, handler: ((ScrollParams) -> Unit)?) {
        if (handler != null) {
            extProps[EXTERNAL_SCROLL_EVENT_PREFIX + eventName] = handler as Any
        } else {
            extProps.remove(EXTERNAL_SCROLL_EVENT_PREFIX + eventName)
        }
    }

    companion object {
        private const val EXTERNAL_SCROLL_EVENT_PREFIX = "_ext_scroll_event_"
    }

    internal fun contentSizeDidChanged(width: Float, height: Float) {
        scrollerViewEventObserverSet.toFastMutableList().forEach {
            it.contentSizeDidChanged(width, height)
        }
    }

    internal fun notifyVisibleAreaMarginChanged() {
        scrollerViewEventObserverSet.toFastMutableList().forEach {
            it.visibleAreaMarginChanged()
        }
    }

    /**
     * 是否为横向布局
     */
    fun isRowFlexDirection(): Boolean {
        if ((contentView as? ScrollerContentView) != null) {
            return (contentView as ScrollerContentView).isRowFlexDirection()
        }
        return false
    }

    // 通知 render 层列表有下拉刷新
    fun setHasPullToRefresh(enabled: Boolean) {
        performTaskWhenRenderViewDidLoad {
            renderView?.callMethod("setHasPullToRefresh", if (enabled) "1" else "0", null)
        }
    }
}

private data class NativeRevisionWaiter(
    val interactionEpoch: Long,
    val layoutRevision: Long,
    val insetRevision: Long,
    val callback: () -> Unit,
)

private data class ComposeAnimatedPhaseSnapshot(
    val animated: Boolean,
    val underlyingPhase: NativeScrollPhase,
    val authorityGeneration: Long,
    val owner: ComposeAnimatedPhaseOwner,
    val previousOwner: ComposeAnimatedPhaseOwner?,
)

private data class ComposeAnimatedPhaseOwner(
    val operationGeneration: Long,
    val attemptGeneration: Long,
)

internal class ContentOffsetWriteLedger {
    private var latestWriteSequence = 0L
    private var confirmedWriteSequence = 0L
    private var confirmedOffset = 0f to 0f
    private var initialized = false

    fun beginWrite(currentOffset: Pair<Float, Float>): Long {
        ensureInitialized(currentOffset)
        latestWriteSequence += 1L
        return latestWriteSequence
    }

    fun confirmWrite(sequence: Long, offset: Pair<Float, Float>) {
        if (sequence >= confirmedWriteSequence) {
            confirmedWriteSequence = sequence
            confirmedOffset = offset
        }
    }

    fun rollbackTarget(sequence: Long): Pair<Float, Float>? {
        return confirmedOffset.takeIf { sequence == latestWriteSequence }
    }

    fun isLatestWrite(sequence: Long): Boolean = sequence == latestWriteSequence

    fun recordNativeOffset(offset: Pair<Float, Float>) {
        confirmedWriteSequence = latestWriteSequence
        confirmedOffset = offset
        initialized = true
    }

    fun invalidateWrites(currentOffset: Pair<Float, Float>) {
        latestWriteSequence += 1L
        confirmedWriteSequence = latestWriteSequence
        confirmedOffset = currentOffset
        initialized = true
    }

    private fun ensureInitialized(currentOffset: Pair<Float, Float>) {
        if (!initialized) {
            confirmedOffset = currentOffset
            initialized = true
        }
    }
}

enum class KRNestedScrollMode(val value: String){
    SELF_ONLY("SELF_ONLY"),
    SELF_FIRST("SELF_FIRST"),
    PARENT_FIRST("PARENT_FIRST"),
}

open class ScrollerAttr : ContainerAttr() {
    var syncScroll = false
    var visibleAreaIgnoreTopMargin = 0f
    var visibleAreaIgnoreBottomMargin = 0f
    internal var bouncesEnable = true

    override fun padding(top: Float, left: Float, bottom: Float, right: Float): ContainerAttr {
        val contentView = (view() as? ScrollerView)?.contentView
        if(contentView != null) {
            contentView.getViewAttr().padding(top, left, bottom, right)
            return this
        }
        return super.padding(top, left, bottom, right)
    }

    // 是否允许手势滚动
    fun scrollEnable(value: Boolean) {
        SCROLL_ENABLED with value.toInt()
    }
    /*
     * 是否允许边界回弹效果
     * @param bouncesEnable 是否允许边界回弹
     * @param limitHeaderBounces 是否禁止顶部回弹(如bouncesEnable为false，该值就无效)
     */
    fun bouncesEnable(bouncesEnable: Boolean, limitHeaderBounces: Boolean = false) {
        this.bouncesEnable = bouncesEnable
        BOUNCES_ENABLE with bouncesEnable.toInt()
        LIMIT_BOUNCES_ENABLE with limitHeaderBounces.toInt()
    }
    // 是否显示滚动指示进度条（默认显示）
    fun showScrollerIndicator(value: Boolean) {
        SHOW_SCROLLER_INDICATOR with value.toInt()
    }
    // 是否开启分页效果
    fun pagingEnable(enable: Boolean) {
        PAGING_ENABLED with enable.toInt()
    }

    /**
     * 设置计算可见性面积时忽略顶部距离。
     * @param margin 顶部距离。
     */
    fun visibleAreaIgnoreTopMargin(margin: Float) {
        if (visibleAreaIgnoreTopMargin != margin) {
            visibleAreaIgnoreTopMargin = margin
            (view() as? ScrollerView<*, *>)?.notifyVisibleAreaMarginChanged()
        }
    }

    /**
     * 设置计算可见性面积时忽略底部距离。
     * @param margin 底部距离。
     */
    fun visibleAreaIgnoreBottomMargin(margin: Float) {
        if (visibleAreaIgnoreBottomMargin != margin) {
            visibleAreaIgnoreBottomMargin = margin
            (view() as? ScrollerView<*, *>)?.notifyVisibleAreaMarginChanged()
        }
    }

    /**
     * 是否允许fling（支持Android/iOS/OHOS，默认值为true，若设置false，则列表松手时则停止惯性滚动）
     */
    fun flingEnable(enable: Boolean) {
        FLING_ENABLE with enable.toInt()
    }

    /**
     * Limit max initial fling speed after drag ends (HarmonyOS: NODE_SCROLL_FLING_SPEED_LIMIT, API 18+).
     * Unit: vp/s. Pass value <= 0 to restore system default. No-op on API < 18.
     */
    fun flingSpeedLimit(speedLimit: Float) {
        FLING_SPEED_LIMIT with speedLimit
    }

    /**
     * 设置是否同步滚动, 也可以通过Event.scroll(sync=true){}开启同步滚动
     * @param syncEnable 同步滚动启用状态(当前kotlin线程ui操作与ui线程同步更新)。
     */
    fun syncScroll(syncEnable: Boolean) {
        syncScroll = syncEnable
    }

    /**
     * 设置是否父组件滑动联动，即自身滑动到目标方向的边缘时，触发父组件滑动，默认 true
     * @param enable 是否允许父组件滑动联动
     */
    fun scrollWithParent(enable: Boolean) {
        SCROLL_WITH_PARENT with enable.toInt()
    }

    override fun flexDirection(flexDirection: FlexDirection): ContainerAttr {
        DIRECTION_ROW with (flexDirection == FlexDirection.ROW || flexDirection == FlexDirection.ROW_REVERSE).toInt()
        return super.flexDirection(flexDirection)
    }

    fun nestedScroll(forward: KRNestedScrollMode, backward: KRNestedScrollMode){
        val param = JSONObject()
        param.put("forward", forward.value)
        param.put("backward", backward.value)
        NESTED_SCROLL with param.toString()
    }

    companion object {
        const val SCROLL_ENABLED = "scrollEnabled"
        const val BOUNCES_ENABLE = "bouncesEnable"
        const val LIMIT_BOUNCES_ENABLE = "limitHeaderBounces"
        const val SHOW_SCROLLER_INDICATOR = "showScrollerIndicator"
        const val PAGING_ENABLED = "pagingEnabled"
        const val DIRECTION_ROW =  "directionRow"
        const val FLING_ENABLE = "flingEnable"
        const val FLING_SPEED_LIMIT = "flingSpeedLimit"
        const val SCROLL_WITH_PARENT = "scrollWithParent"
        const val NESTED_SCROLL = "nestedScroll"
    }

}

open class ScrollerEvent : Event() {
    internal var syncScroll = false
    internal var contentSizeChangedHandlerFn: ((width: Float, height: Float) -> Unit)? = null
    /**
     * 设置滚动事件处理器。当滚动事件发生时，会调用传入的处理器函数。
     *
     * @param handler 一个接收 ScrollParams 参数的函数，当滚动事件发生时被调用。
     */
    open fun scroll(handler: (ScrollParams) -> Unit) {
        scroll(false, handler)
    }

    /**
     * 设置滚动事件处理器，并指定是否同步滚动。当滚动事件发生时，会调用传入的处理器函数。
     *
     * @param sync 是否同步滚动（默认false，若为true，则使得当前kotlin线程对ui的操作与平台UI线程同步生效更新）
     * @param handler 一个接收 ScrollParams 参数的函数，当滚动事件发生时被调用。
     */
    open fun scroll(sync: Boolean, handler: (ScrollParams) -> Unit) {
        syncScroll = sync
        registerScrollerEvent(ScrollerEventConst.SCROLL, handler = {
            val view = getView() as ScrollerView<*, *>
            if (!view.acceptNativeScrollEvent(it)) return@registerScrollerEvent
            view.updateNativeScrollPhaseFromNative(
                it.nativeScrollPhase,
                it.sourceOperationGeneration,
            )
            handler(it)
        }, sync = sync)
    }

    /**
     * 设置滚动结束事件的处理器。当滚动结束时，会调用传入的处理器函数。
     *
     * @param handler 一个接收 ScrollParams 参数的函数，当滚动结束时被调用。
     */
    open fun scrollEnd(handler: (ScrollParams) -> Unit) {
        registerScrollerEvent(ScrollerEventConst.SCROLL_END, handler = {
            val view = getView() as ScrollerView<*, *>
            if (!view.acceptNativeScrollEvent(it)) return@registerScrollerEvent
            if (!view.updateNativeScrollPhaseFromNative(
                    NativeScrollPhase.Idle,
                    it.sourceOperationGeneration,
                )) {
                return@registerScrollerEvent
            }
            handler(it)
        }, sync = false)
    }

    /**
     * 设置开始拖拽滚动事件的处理器。当开始拖拽滚动时，会调用传入的处理器函数。
     *
     * @param handler 一个接收 ScrollParams 参数的函数，当开始拖拽滚动时被调用。
     */
    open fun dragBegin(handler: (ScrollParams) -> Unit) {
        registerScrollerEvent(ScrollerEventConst.DRAG_BEGIN, handler = {
            val view = getView() as ScrollerView<*, *>
            if (!view.acceptNativeScrollEvent(it)) return@registerScrollerEvent
            view.isDragging = true
            view.updateNativeScrollPhaseFromNative(NativeScrollPhase.Dragging)
            handler.invoke(it)
        }, sync = false)
    }

    /**
     * 设置结束拖拽滚动事件的处理器。当结束拖拽滚动时，会调用传入的处理器函数。
     *
     * @param handler 一个接收 ScrollParams 参数的函数，当结束拖拽滚动时被调用。
     */
    open fun dragEnd(handler: (ScrollParams) -> Unit) {
        registerScrollerEvent(ScrollerEventConst.DRAG_END, handler = {
            val view = getView() as ScrollerView<*, *>
            if (!view.acceptNativeScrollEvent(it)) return@registerScrollerEvent
            view.isDragging = false
            view.updateNativeScrollPhaseFromNative(it.nativeScrollPhase)
            handler.invoke(it)
        }, sync = false)
    }

    /**
     * 设置将要结束拖拽滚动事件的处理器。当将要结束拖拽滚动时，会调用传入的处理器函数。此方法会在平台主线程中同步回调。
     * 该方法常用于手松时指定滚动偏移量（setContentOffset）来实现自定义吸附位置
     * @param handler 一个接收 WillEndDragParams 参数的函数，当将要结束拖拽滚动时被调用。
     */
    fun willDragEndBySync(handler: (WillEndDragParams) -> Unit, isSync: Boolean) {
        this.register(ScrollerEventConst.WILL_DRAG_END, {
            if (it is JSONObject) {
                handler(WillEndDragParams.decode(it))
            } else if (it is WillEndDragParams) {
                handler(it)
            }
        }, isSync) // 平台主线程成会同步回调
    }

    open fun willDragEndBySync(handler: (WillEndDragParams) -> Unit) {
        willDragEndBySync(isSync = true, handler = handler)
    }

    /**
     * Listen to native "scroll to top" event.
     * Note: This is triggered by the status bar tap on iOS or Android(Oppo)
     */
    open fun scrollToTop(handler: () -> Unit) {
        register(ScrollerEventConst.SCROLL_TO_TOP, {
            handler.invoke()
        }, false)
    }

    /**
     * 设置内容尺寸变化事件的处理器。当内容尺寸发生变化时，会调用传入的处理器函数。
     * 一般使用该时机初始化initContentOffset位置
     * @param handler 一个接收宽度和高度参数的函数，当内容尺寸发生变化时被调用。
     */
    open fun contentSizeChanged(handler: (width: Float, height: Float) -> Unit) {
        contentSizeChangedHandlerFn = handler
    }

    private fun registerScrollerEvent(eventName: String, handler: (ScrollParams) -> Unit, sync: Boolean) {
        register(eventName, {
            if (it is JSONObject) {
                handler(ScrollParams.decode(it))
            } else if (it is ScrollParams) {
                handler(it)
            }
        }, sync)
    }

    object ScrollerEventConst {
        const val SCROLL = "scroll"
        const val SCROLL_END = "scrollEnd"
        const val DRAG_BEGIN = "dragBegin"
        const val DRAG_END = "dragEnd"
        const val WILL_DRAG_END = "willDragEnd"
        const val SCROLL_TO_TOP = "scrollToTop"
    }
}

fun ViewContainer<*, *>.Scroller(init: ScrollerView<*, *>.() -> Unit) {
    addChild(ScrollerView<ScrollerAttr, ScrollerEvent>(), init)
}

/** 内容视图 */
open class ScrollerContentView : ViewContainer<ContainerAttr, GroupEvent>(), IPagerLayoutEventObserver {
    var offsetX: Float = 0f
    var offsetY: Float = 0f
    protected var needLayout = true
    override fun viewName(): String {
        return ViewConst.TYPE_SCROLL_CONTENT_VIEW
    }

    override fun createAttr(): ContainerAttr {
        return ContainerAttr()
    }

    override fun createEvent(): GroupEvent {
        return GroupEvent()
    }

    override fun createFlexNode() {
        super.createFlexNode()
        flexNode.setNeedDirtyCallback = {
            needLayout = true
        }
    }

    override fun didMoveToParentView() {
        super.didMoveToParentView()
        getPager().addPagerLayoutEventObserver(this)
    }

    override fun didRemoveFromParentView() {
        super.didRemoveFromParentView()
        getPager().removePagerLayoutEventObserver(this)
        flexNode.setNeedDirtyCallback = null
    }

    override fun layoutFrameDidChanged(frame: Frame) {
        super.layoutFrameDidChanged(frame)
        (parent as? ScrollerView<*, *>)?.also {
            it.getViewEvent().contentSizeChangedHandlerFn?.invoke(
                frame.width,
                frame.height
            )
            it.contentSizeDidChanged(width = frame.width, height = frame.height)
        }
    }

    open fun contentOffsetWillChanged(offsetX: Float, offsetY: Float) {
        this.offsetX = offsetX
        this.offsetY = offsetY
    }

    open fun contentOffsetDidChanged(offsetX: Float, offsetY: Float, params: ScrollParams) {
    }

    override fun onPagerWillCalculateLayoutFinish() {

    }

    override fun onPagerCalculateLayoutFinish() {

    }

    override fun onPagerDidLayout() {
        if (needLayout) {
            parent?.also {
                if (parent is ScrollerView<*, *>) {
                    (parent as ScrollerView<*, *>).subViewsDidLayout()
                }
            }
            needLayout = false
        }

    }

    override fun didSetFrameToRenderView() {
        super.didSetFrameToRenderView()
        (parent as? ScrollerView<*, *>)?.contentViewDidSetFrameToRenderView()
    }

    fun isRowFlexDirection(): Boolean {
        return flexNode.flexDirection == FlexDirection.ROW || flexNode.flexDirection == FlexDirection.ROW_REVERSE
    }

}

data class ScrollParams(
    var offsetX: Float,  // 列表当前纵轴偏移量
    var offsetY: Float,  // 列表当前横轴偏移量
    val contentWidth: Float, // 列表当前内容总宽度
    val contentHeight: Float, // 列表当前内容总高度
    val viewWidth: Float,  // 列表View宽度
    val viewHeight: Float, // 列表View高度
    val isDragging: Boolean, // 是否在dragging
    val nativeScrollPhase: NativeScrollPhase = if (isDragging) {
        NativeScrollPhase.Dragging
    } else {
        NativeScrollPhase.Idle
    },
    val touches: List<Touch> = listOf(),   // Touch触摸点信息列表
    val nativeInteractionEpoch: Long = 0L,
    val layoutRevision: Long = 0L,
    val insetRevision: Long = 0L,
    val sourceOperationGeneration: Long = 0L,
) { // 当前是否处于拖拽列表滚动中
    companion object {
        fun decode(params: JSONObject): ScrollParams {
            val offsetX = params.optDouble("offsetX").toFloat()
            val offsetY = params.optDouble("offsetY").toFloat()
            val contentWidth = params.optDouble("contentWidth").toFloat()
            val contentHeight = params.optDouble("contentHeight").toFloat()
            val viewWidth = params.optDouble("viewWidth").toFloat()
            val viewHeight = params.optDouble("viewHeight").toFloat()
            val isDragging = params.optInt("isDragging") == 1
            val nativeScrollPhase = if (params.has("nativeScrollPhase")) {
                NativeScrollPhase.fromWireValue(params.optInt("nativeScrollPhase"))
            } else if (isDragging) {
                NativeScrollPhase.Dragging
            } else {
                NativeScrollPhase.Idle
            }
            val jsonArray = params.optJSONArray("touches")
            val touches = mutableListOf<Touch>()
            jsonArray?.let {
                for (i in 0 until it.length()) {
                    val touch = Touch.decode(jsonArray.opt(i))
                    touches.add(touch)
                }
            }
            return ScrollParams(
                offsetX,
                offsetY,
                contentWidth,
                contentHeight,
                viewWidth,
                viewHeight,
                isDragging,
                nativeScrollPhase,
                touches,
                params.optDouble("nativeInteractionEpoch", 0.0).toLong(),
                params.optDouble("layoutRevision", 0.0).toLong(),
                params.optDouble("insetRevision", 0.0).toLong(),
                params.optDouble("sourceOperationGeneration", 0.0).toLong(),
            )
        }
    }

}

enum class NativeScrollPhase(internal val wireValue: Int) {
    Idle(0),
    Dragging(1),
    SettlingOrAnimating(2);

    companion object {
        internal fun fromWireValue(value: Int): NativeScrollPhase = when (value) {
            1 -> Dragging
            2 -> SettlingOrAnimating
            else -> Idle
        }
    }
}

data class ScrollOffsetCommitToken(
    val generation: Long,
    val requiresNativeIdle: Boolean,
    val operationGeneration: Long = 0L,
    val expectedContentSize: Float = -1f,
    val expectedViewportSize: Float = -1f,
    val bindingGeneration: Long = 0L,
    val capabilityKind: Int = -1,
    val capabilityLeaseId: Long = 0L,
    val semanticOperationId: Long = operationGeneration,
    val attemptGeneration: Long = operationGeneration,
    val nativeInteractionEpoch: Long = 0L,
    val layoutRevision: Long = 0L,
    val anchorRevision: Long = 0L,
    val rangeRevision: Long = 0L,
    val insetRevision: Long = 0L,
)

private fun decodeScrollWriteResult(result: JSONObject?): ScrollWriteResult {
    val committed = result?.optInt("committed", 0) == 1
    val defaultCode = if (committed) {
        ScrollWriteResultCode.Committed
    } else {
        ScrollWriteResultCode.Stale
    }
    return ScrollWriteResult(
        code = ScrollWriteResultCode.fromWireValue(
            result?.optInt("resultCode", defaultCode.wireValue) ?: defaultCode.wireValue,
        ),
        nativeInteractionEpoch = result?.optDouble("nativeInteractionEpoch", -1.0)?.toLong() ?: -1L,
        layoutRevision = result?.optDouble("layoutRevision", -1.0)?.toLong() ?: -1L,
        insetRevision = result?.optDouble("insetRevision", -1.0)?.toLong() ?: -1L,
        accepted = result?.optInt("accepted", if (committed) 1 else 0) == 1,
        installed = result?.optInt("installed", if (committed) 1 else 0) == 1,
        replacedPrevious = result?.optInt("replacedPrevious", 0) == 1,
    )
}

private fun ScrollOffsetCommitToken.extendedIdentityWireSuffix(): String =
    " $bindingGeneration $capabilityKind $capabilityLeaseId" +
        " $semanticOperationId $attemptGeneration $nativeInteractionEpoch" +
        " $layoutRevision $anchorRevision $rangeRevision $insetRevision"

class WillEndDragParams(
    val offsetX: Float,  // 列表当前纵轴偏移量
    val offsetY: Float,  // 列表当前横轴偏移量
    val contentWidth: Float, // 列表当前内容总宽度
    val contentHeight: Float, // 列表当前内容总高度
    val viewWidth: Float,  // 列表View宽度
    val viewHeight: Float, // 列表View高度
    val isDragging: Boolean,// 当前是否处于拖拽列表滚动中
    val velocityX: Float, // 纵轴加速度
    val velocityY: Float, // 横轴加速度
    val targetContentOffsetX: Float, // 松手时默认滚动的目标位置X
    val targetContentOffsetY: Float // 松手时默认滚动的目标位置Y
    ) {
    companion object {
        fun decode(params: JSONObject): WillEndDragParams {
            val offsetX = params.optDouble("offsetX").toFloat()
            val offsetY = params.optDouble("offsetY").toFloat()
            val contentWidth = params.optDouble("contentWidth").toFloat()
            val contentHeight = params.optDouble("contentHeight").toFloat()
            val viewWidth = params.optDouble("viewWidth").toFloat()
            val viewHeight = params.optDouble("viewHeight").toFloat()
            val isDragging = params.optInt("isDragging") == 1
            val velocityX = params.optDouble("velocityX").toFloat()
            val velocityY = params.optDouble("velocityY").toFloat()
            val targetContentOffsetX = params.optDouble("targetContentOffsetX").toFloat()
            val targetContentOffsetY = params.optDouble("targetContentOffsetY").toFloat()
            return WillEndDragParams(
                offsetX,
                offsetY,
                contentWidth,
                contentHeight,
                viewWidth,
                viewHeight,
                isDragging,
                velocityX,
                velocityY,
                targetContentOffsetX,
                targetContentOffsetY
            )
        }
    }

}

data class SpringAnimation(val durationMs: Int, val damping: Float, val velocity: Float) {

    override fun toString(): String {
        return " $durationMs $damping $velocity 0"
    }

}

data class SetContentOffsetAnimation(private val durationMs: Int, val damping: Float, val velocity: Float, val animationCurve: Int) {
    enum class AnimationCurve(val value: Int){
        Spring(0), Linear(1)
    }

    override fun toString(): String {
        return " $durationMs $damping $velocity $animationCurve"
    }

    // spring
    private constructor(durationMs: Int, damping: Float, velocity: Float) : this(durationMs, damping, velocity, AnimationCurve.Spring.value)

    // linear
    private constructor(durationMs: Int) : this(durationMs, 0f, 0f, AnimationCurve.Linear.value)

    companion object{
        fun linear(durationMs: Int) : SetContentOffsetAnimation {
            return SetContentOffsetAnimation(durationMs)
        }
        fun spring(durationMs: Int, damping: Float, velocity: Float) : SetContentOffsetAnimation {
            return SetContentOffsetAnimation(durationMs, damping, velocity);
        }
    }
}
