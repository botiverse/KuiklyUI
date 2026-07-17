package com.tencent.kuikly.core.render.web.runtime.web.expand.components.list

import com.tencent.kuikly.core.render.web.collection.array.add
import com.tencent.kuikly.core.render.web.processor.KuiklyProcessor
import com.tencent.kuikly.core.render.web.const.KRAttrConst
import com.tencent.kuikly.core.render.web.const.KRCssConst
import com.tencent.kuikly.core.render.web.const.KREventConst
import com.tencent.kuikly.core.render.web.const.KRListConst
import com.tencent.kuikly.core.render.web.const.KRParamConst
import com.tencent.kuikly.core.render.web.const.KRStyleConst
import com.tencent.kuikly.core.render.web.expand.components.list.KRListViewContentInset
import com.tencent.kuikly.core.render.web.expand.components.list.WebScrollWriteKind
import com.tencent.kuikly.core.render.web.expand.components.list.WebScrollWriteOperation
import com.tencent.kuikly.core.render.web.expand.components.list.WebScrollWriteOperationArbiter
import com.tencent.kuikly.core.render.web.expand.components.list.WebScrollWriteResultCode
import com.tencent.kuikly.core.render.web.expand.components.list.canApplyWebOffsetWrite
import com.tencent.kuikly.core.render.web.expand.components.list.webScrollWriteResult
import com.tencent.kuikly.core.render.web.ktx.KuiklyRenderCallback
import com.tencent.kuikly.core.render.web.ktx.kuiklyDocument
import com.tencent.kuikly.core.render.web.ktx.kuiklyWindow
import com.tencent.kuikly.core.render.web.runtime.dom.element.ElementType
import com.tencent.kuikly.core.render.web.runtime.dom.element.IListElement
import com.tencent.kuikly.core.render.web.scheduler.KuiklyRenderCoreContextScheduler
import com.tencent.kuikly.core.render.web.utils.Log
import org.w3c.dom.AUTO
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.SMOOTH
import org.w3c.dom.ScrollBehavior
import org.w3c.dom.ScrollToOptions
import org.w3c.dom.TouchEvent
import org.w3c.dom.events.Event
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.WheelEvent
import org.w3c.dom.get
import kotlin.js.json
import kotlin.js.Date
import kotlin.math.abs
import kotlin.math.absoluteValue

/**
 * Web host abstract List element implementation
 */
class H5ListView : IListElement {
    // Scroll container element
    private val listEle = kuiklyDocument.createElement(ElementType.DIV).apply {
        // By default, allow scrolling in vertical direction. To hide scrollbars,
        // add 'list-no-scrollbar' class to the element
        this.unsafeCast<HTMLDivElement>().style.apply {
            // Due to bounce effect on iOS, non-scrolling direction should be set to "hidden"
            overflowX = KRStyleConst.OVERFLOW_HIDDEN
            overflowY = KRStyleConst.OVERFLOW_SCROLL
        }
        this.classList.add(KRListConst.IS_LIST)
    }
    // Scroll end event listener
    private var scrollEndEventTimer: Int = 0
    // Scroll offset Map
    private var offsetMap = mutableMapOf<String, Any>()
    // Starting horizontal scroll offset
    private var startX = 0f
    // Starting vertical scroll offset
    private var startY = 0f
    // Starting vertical touch position
    private var touchStartY = 0f
    // Current vertical touch position
    private var touchEndY = 0f
    // Starting horizontal touch position
    private var touchStartX = 0f
    // Current horizontal touch position
    private var touchEndX = 0f
    // Whether scrolling is enabled
    internal var scrollEnabled = true
        private set
    // Whether to show scrollbar
    private var showScrollerBar = true
    // Scroll direction
    private var scrollDirection = KRListConst.SCROLL_DIRECTION_COLUMN
    // Actual calculated scroll direction
    private var calculateDirection = KRListConst.SCROLL_DIRECTION_NONE
    // Whether currently dragging
    private var isDragging = 0
    // Whether paging is enabled
    var pagingEnabled = false
        private set
    // enable bounce effect, support Android Webview 63+ && iOS Safari 16+
    private var bounceEnabled = false
    // enable nest scroll effect
    var nestScrollEnabled = false
        private set
    // Whether in pre-pull-down state
    private var isPrePullDown = false
    // Pull-to-refresh height
    private var canPullRefreshHeight = 0f
    private var pullRefreshComposeOperation = 0L
    private var pullRefreshExpectedContentSize = -1f
    private var pullRefreshExpectedViewportSize = -1f
    // Whether it contains pull-to-refresh child node
    private var hasRefreshChild = false
    // Scroll distance threshold
    private val scrollThreshold = KRListConst.SCROLL_THRESHOLD
    // Whether in scrolling state
    private var isScrolling = ScrollingAxis.NONE
    // Decide whether the interaction should be treated as a click
    private var clickDetectionTimer: Int? = null
    // Delay invoking the single-click callback so a possible second click can be detected
    private var singleClickConfirmTimer: Int? = null
    // Whether it's a click event
    private var isClickEvent = false
    private var touchStartTime: Double = 0.0
    // Whether the wheel is rolling
    private var isWheelRolling = false
    private var nativeScrollIngressActive = false
    private var lastProgrammaticTerminalAt = 0.0
    // Whether the wheel is stopped
    private var wheelStopTimer: Int? = null
    // Count of clicks on the current element, used to determine whether it's a double click
    private var clickCount = 0

    // Set by [prepareForComposeReuse]; the next [setContentOffset] will proactively fire a
    // scroll event even if the underlying scroll position is unchanged. This compensates
    // for the browser/miniapp behavior of not dispatching `scroll` on no-op `scrollTo`.
    private var pendingFireScrollForReuse: Boolean = false
    private var composeOffsetWriteGeneration = 0L
    private var activeSmoothScroll: WebScrollWriteOperation? = null
    private var smoothScrollFrameId: Int? = null
    private var smoothScrollEndTimer: Int? = null
    private var pendingReuseScrollEventTimer: Int? = null
    private var insetWhenEndDragSequence = 0L
    private var writeOperationSequence = 0L
    private var latestComposeWriteOperation = 0L
    private var minimumComposeWriteOperation = 0L
    private var nativeInteractionEpoch = 0L
    private var nativeLayoutRevision = 0L
    private var nativeInsetRevision = 0L
    private var lastContentWidth = -1
    private var lastContentHeight = -1
    private var lastViewportWidth = -1
    private var lastViewportHeight = -1
    private val writeArbiter = WebScrollWriteOperationArbiter()

    override var nativeScrollPhase: Int = 0

    // real html element
    override var ele: HTMLElement = listEle.unsafeCast<HTMLElement>()

    init {
        ele.asDynamic().listView = this
    }

    // Scroll callback
    override var scrollEventCallback: KuiklyRenderCallback? = null
    // Drag begin callback
    override var dragBeginEventCallback: KuiklyRenderCallback? = null
    // Drag end callback
    override var dragEndEventCallback: KuiklyRenderCallback? = null
    // Will drag end callback
    override var willDragEndEventCallback: KuiklyRenderCallback? = null
    // Scroll end callback
    override var scrollEndEventCallback: KuiklyRenderCallback? = null
    // Click callback
    override var clickEventCallback: KuiklyRenderCallback? = null

    override var doubleClickEventCallback: KuiklyRenderCallback? = null
    
    // Whether this list has a pull-to-refresh child
    override var hasPullToRefresh: Boolean = false

    var listPagingHelper: H5ListPagingHelper = H5ListPagingHelper(ele, this)
        private set
    var nestScrollHelper: H5NestScrollHelper = H5NestScrollHelper(ele, this)
        internal set
    var pcScrollHelper: H5ListPCScrollHelper = H5ListPCScrollHelper(ele, this, this)
        private set

    /**
     * Set whether listView can scroll
     */
    override fun setScrollEnable(params: Any): Boolean {
        // Set the switch for whether scrolling is enabled
        scrollEnabled = params.unsafeCast<Int>() == KRListConst.ENABLED_FLAG
        // Set scrolling
        ele.style.apply {
            if (scrollDirection == KRListConst.SCROLL_DIRECTION_COLUMN) {
                overflowY = if (scrollEnabled) KRStyleConst.OVERFLOW_SCROLL else KRStyleConst.OVERFLOW_HIDDEN
                overflowX = KRStyleConst.OVERFLOW_HIDDEN
            } else {
                overflowX = if (scrollEnabled) KRStyleConst.OVERFLOW_SCROLL else KRStyleConst.OVERFLOW_HIDDEN
                overflowY = KRStyleConst.OVERFLOW_HIDDEN
            }
        }
        return true
    }

    override fun setBounceEnable(params: Any): Boolean {
        bounceEnabled = params.unsafeCast<Int>() == KRListConst.ENABLED_FLAG
        listPagingHelper.bounceEnabled = bounceEnabled
        // Apply overscroll-behavior to current scroll axis so non-paging mode is also controlled.
        // Browser support: Android WebView 63+, iOS Safari 16+. Lower versions silently ignore it.
        applyOverscrollBehavior()
        return true
    }

    /**
     * Sync `overscroll-behavior-x/y` with current scrollDirection and bounceEnabled.
     * - scroll axis: `auto` when bounceEnabled, otherwise `none` (disable native bounce / pull-to-refresh)
     * - cross axis: keep `auto` (no extra constraint)
     */
    private fun applyOverscrollBehavior() {
        val scrollAxisValue = if (bounceEnabled) OVERSCROLL_AUTO else OVERSCROLL_NONE
        ele.style.apply {
            if (scrollDirection == KRListConst.SCROLL_DIRECTION_COLUMN) {
                setProperty(OVERSCROLL_BEHAVIOR_Y, scrollAxisValue)
                setProperty(OVERSCROLL_BEHAVIOR_X, OVERSCROLL_AUTO)
            } else {
                setProperty(OVERSCROLL_BEHAVIOR_X, scrollAxisValue)
                setProperty(OVERSCROLL_BEHAVIOR_Y, OVERSCROLL_AUTO)
            }
        }
    }

    override fun setNestedScroll(propValue: Any): Boolean {
        nestScrollEnabled = true
        nestScrollHelper.setNestedScroll(propValue)
        return true
    }

    /**
     * Set whether to enable paging
     */
    override fun setPagingEnable(params: Any): Boolean {
        // Whether to enable paging
        pagingEnabled = params.unsafeCast<Int>() == KRListConst.ENABLED_FLAG
        return true
    }

    /**
     * Set the scroll direction of listView, 1 for horizontal, 0 for vertical
     */
    override fun setScrollDirection(params: Any): Boolean {
        val direction = if (params.unsafeCast<Int>() == KRListConst.ENABLED_FLAG) {
            KRListConst.SCROLL_DIRECTION_ROW
        } else {
            KRListConst.SCROLL_DIRECTION_COLUMN
        }
        // Set scroll direction
        ele.style.apply {
            if (direction == KRListConst.SCROLL_DIRECTION_COLUMN) {
                overflowX = KRStyleConst.OVERFLOW_HIDDEN
                overflowY = KRStyleConst.OVERFLOW_SCROLL
            } else {
                overflowX = KRStyleConst.OVERFLOW_SCROLL
                overflowY = KRStyleConst.OVERFLOW_HIDDEN
            }
        }
        scrollDirection = direction
        listPagingHelper.scrollDirection = scrollDirection
        nestScrollHelper.scrollDirection = scrollDirection
        // Re-apply overscroll-behavior to the new scroll axis
        applyOverscrollBehavior()
        return true
    }

    /**
     * Check if it contains pull-to-refresh child node
     */
    private fun checkHasRefreshChild(): Boolean {
        return hasPullToRefresh
    }

    override fun updateOffsetMap(offsetX: Float, offsetY: Float, isDragging: Int): MutableMap<String, Any> {
        refreshLayoutRevision()
        offsetMap[KRParamConst.OFFSET_X] = offsetX
        offsetMap[KRParamConst.OFFSET_Y] = offsetY
        offsetMap[KRParamConst.VIEW_WIDTH] = ele.offsetWidth
        offsetMap[KRParamConst.VIEW_HEIGHT] = ele.offsetHeight
        offsetMap[KRParamConst.CONTENT_WIDTH] = ele.scrollWidth
        offsetMap[KRParamConst.CONTENT_HEIGHT] = ele.scrollHeight
        offsetMap[KRParamConst.IS_DRAGGING] = isDragging
        offsetMap["nativeScrollPhase"] = nativeScrollPhase
        offsetMap["nativeInteractionEpoch"] = nativeInteractionEpoch
        offsetMap["layoutRevision"] = nativeLayoutRevision
        offsetMap["insetRevision"] = nativeInsetRevision
        return offsetMap
    }

    internal fun handleTouchStart(event: Event, isMouseEvent: Boolean = false) {
        Log.trace(LOG_SCROLL_EVENT_BEGIN)
        // Set as dragging
        isDragging = 1
        nativeScrollPhase = 1
        nativeInteractionEpoch += 1L
        writeOperationSequence += 1L
        minimumComposeWriteOperation = latestComposeWriteOperation + 1L
        invalidateWrite(WebScrollWriteResultCode.Interrupted)
        pendingReuseScrollEventTimer?.let { kuiklyWindow.clearTimeout(it) }
        pendingReuseScrollEventTimer = null
        if (scrollEndEventTimer > 0) {
            kuiklyWindow.clearTimeout(scrollEndEventTimer)
            scrollEndEventTimer = 0
        }
        // Clear pull-to-refresh height
        canPullRefreshHeight = 0f
        pullRefreshComposeOperation = 0L
        pullRefreshExpectedContentSize = -1f
        pullRefreshExpectedViewportSize = -1f
        // Check if it contains pull-to-refresh child node
        hasRefreshChild = checkHasRefreshChild()
        // Reset scrolling state
        isScrolling = ScrollingAxis.NONE
        if (isMouseEvent) pcScrollHelper.handleMouseDown(event as MouseEvent)
        // Get horizontal and vertical offset of the element during scroll event
        val offsetX = ele.scrollLeft.toFloat()
        val offsetY = ele.scrollTop.toFloat()
        // Record scrollbar position at start of sliding
        startX = offsetX
        startY = offsetY
        // Starting drag position map
        val eventsParams = event.getEventParams()
        // Record starting vertical drag position
        touchStartY = eventsParams[KRParamConst.Y].unsafeCast<Float>()
        // Record starting horizontal drag position
        touchStartX = eventsParams[KRParamConst.X].unsafeCast<Float>()
        // Current vertical offset of the list
        offsetMap[KRParamConst.OFFSET_X] = offsetX
        // Current horizontal offset of the list
        offsetMap[KRParamConst.OFFSET_Y] = offsetY
        val offsetMap = updateOffsetMap(offsetX, offsetY, isDragging)
        // If current scroll distance is 0, and not a PageList paging component, enter pre-pull-down state
        isPrePullDown = offsetY == 0f && !pagingEnabled

        // Event callback
        dragBeginEventCallback?.invoke(offsetMap)
    }

    private fun handleMoveCommon(event: Event) {
        // Need to check if it contains pull-to-refresh component, if not, don't process todo fixme
        val eventsParams = event.getEventParams()
        var deltaY = eventsParams[KRParamConst.Y] as Float - touchStartY
        var deltaX = eventsParams[KRParamConst.X] as Float - touchStartX
        var absDeltaY = abs(deltaY)
        var absDeltaX = abs(deltaX)

        // If not yet in scrolling state, determine scroll direction, once determined don't change
        if (isScrolling == ScrollingAxis.NONE) {
            if (absDeltaY > scrollThreshold && absDeltaY > absDeltaX) {
                // Vertical scrolling
                isScrolling = ScrollingAxis.VERTICAL
            } else if (absDeltaX > scrollThreshold && absDeltaX > absDeltaY) {
                // Horizontal scrolling
                isScrolling = ScrollingAxis.HORIZONTAL
            }
        }
        if ((scrollDirection == KRListConst.SCROLL_DIRECTION_COLUMN && isScrolling == ScrollingAxis.VERTICAL) ||
            (scrollDirection == KRListConst.SCROLL_DIRECTION_ROW && isScrolling == ScrollingAxis.HORIZONTAL)) {
            // Scroll direction matches set direction, prevent bubbling to avoid affecting parent node's scroll events
            event.stopPropagation()
        }
        // If current scroll distance is 0, starting to drag down, contains pull-to-refresh child node,
        // and is vertical scrolling, handle pull-to-refresh logic, deltaY > 0 means pulling down
        if (isPrePullDown && deltaY > 0 && hasRefreshChild && isScrolling == ScrollingAxis.VERTICAL) {
            // Set end position before drag ends
            touchEndY = eventsParams[KRParamConst.Y].unsafeCast<Float>()
            // Set element's translate
            val contentEle = ele.firstElementChild.unsafeCast<HTMLElement?>()
            contentEle?.style?.transform = buildTranslateY(deltaY)
            val offsetMap = updateOffsetMap(ele.scrollLeft.toFloat(), -deltaY, isDragging)
            // Notify
            scrollEventCallback?.invoke(offsetMap)
        }
    }

    private fun handleTouchMove(it: TouchEvent) {
        handleMoveCommon(it)
    }

    internal fun handleTouchEnd() {
        isDragging = 0
        nativeScrollPhase = if (scrollEndEventTimer > 0) 2 else 0
        // Get horizontal and vertical offset of the element during scroll event
        val offsetX = ele.scrollLeft.toFloat()
        var offsetY = ele.scrollTop.toFloat()
        if (isPrePullDown) {
            // Special handling for pull-to-refresh
            val deltaY = touchEndY - touchStartY
            val currentPullRefreshHeight = canPullRefreshHeight.takeIf {
                isCurrentOffsetWrite(pullRefreshComposeOperation) &&
                    matchesExpectedLayout(
                        pullRefreshExpectedContentSize,
                        pullRefreshExpectedViewportSize,
                    )
            } ?: 0f
            if (currentPullRefreshHeight == 0f) {
                // If at pull-to-refresh release but not reaching pull-to-refresh position,
                // need to restore contentInset and scrolling
                val contentEle = ele.firstElementChild.unsafeCast<HTMLElement?>()
                contentEle?.style?.transform = KRListConst.TRANSFORM_RESET
                // Handle extreme sliding in static sliding scenarios
                if (scrollEnabled) {
                    if (scrollDirection == KRListConst.SCROLL_DIRECTION_COLUMN) {
                        ele.style.overflowY = KRStyleConst.OVERFLOW_SCROLL
                    } else {
                        ele.style.overflowX = KRStyleConst.OVERFLOW_SCROLL
                    }
                }

                // remove transform attribute after transform end
                kuiklyWindow.setTimeout({
                    contentEle?.style?.transform = KRCssConst.EMPTY_STRING
                }, KRListConst.IMMEDIATE_TIMEOUT)
            } else if (deltaY > currentPullRefreshHeight) {
                val contentEle = ele.firstElementChild.unsafeCast<HTMLElement?>()
                contentEle?.style?.transition = buildTransition()
                // If at pull-to-refresh release and exceeding pull-to-refresh height,
                // need to bounce back to pull-to-refresh height before refreshing
                contentEle?.style?.transform = buildTranslateY(currentPullRefreshHeight)
            }
            // If current scroll distance is 0 and starting to drag down, handle pull-to-refresh logic,
            // deltaY > 0 means pulling down
            if (deltaY > 0) {
                // Result is negative
                offsetY = -deltaY
            }
        }
        // Current vertical offset of the list
        offsetMap[KRParamConst.OFFSET_X] = offsetX
        // Current horizontal offset of the list
        offsetMap[KRParamConst.OFFSET_Y] = offsetY
        val offsetMap = updateOffsetMap(offsetX, offsetY, isDragging)
        // Event callback
        willDragEndEventCallback?.invoke(offsetMap)
        dragEndEventCallback?.invoke(offsetMap)
        scrollEventCallback?.invoke(offsetMap)
    }

    private fun handleTouchScroll() {
        if (isDragging == 0 && !isWheelRolling && activeSmoothScroll == null &&
            !nativeScrollIngressActive) {
            nativeScrollIngressActive = true
            nativeInteractionEpoch += 1L
            minimumComposeWriteOperation = latestComposeWriteOperation + 1L
        }
        nativeScrollPhase = if (isDragging == 1) 1 else 2
        // Get horizontal and vertical offset of the element during scroll event
        val offsetMap = updateOffsetMap(ele.scrollLeft.toFloat(), ele.scrollTop.toFloat(), isDragging)
        // Callback with offset
        scrollEventCallback?.invoke(offsetMap)
    }

    /**
     * 执行 click、doubleClick 回调
     */
    private fun invokeClickCallback(event: Event, isDoubleClick: Boolean) {
        val clickOffsetMap = if (event.isTouchEventOrNull() != null) {
            val touch = event.unsafeCast<TouchEvent>().changedTouches[0] ?: return
            val x = touch.clientX
            val y = touch.clientY
            // Calculate element position
            val position = ele.getBoundingClientRect()
            // Element distance from left side of page
            val eleX = position.left
            // Element distance from top of page
            val eleY = position.top
            // Calculate offset
            val offsetX = x.toDouble() - eleX
            val offsetY = y.toDouble() - eleY
            mapOf(KRParamConst.X to offsetX, KRParamConst.Y to offsetY)
        } else {
            mapOf(
                KRParamConst.X to event.unsafeCast<MouseEvent>().offsetX,
                KRParamConst.Y to event.unsafeCast<MouseEvent>().offsetY
            )
        }

        if (isDoubleClick) {
            doubleClickEventCallback?.invoke(clickOffsetMap)
        } else {
            clickEventCallback?.invoke(clickOffsetMap)
        }
    }

    /**
     * 处理 click、doubleClick 事件
     */
    internal fun handleClickEvent(it: Event) {
        // If it is considered as a click event
        // Record the current click count
        clickCount++
        // Whether the double-click event is registered
        if (!ele.asDynamic().hasDoubleClickListener as Boolean) {
            // If no double-click event is registered，invoke the click callback
            invokeClickCallback(it, false)
            // Reset the click count
            clickCount = 0
            return
        } else {
            // If a double click handler is registered
            if (clickCount == KRListConst.DOUBLE_CLICK_COUNT) {
                // Clear the timer to prevent the click callback from being invoked afterward
                val timer = singleClickConfirmTimer
                if (timer != null) {
                    kuiklyWindow.clearTimeout(timer)
                    singleClickConfirmTimer = null
                }
                // Reset the click count
                clickCount = 0
                // Invoke the double-click callback
                invokeClickCallback(it, true)
            } else {
                // If the timer exists , clear it (reset the timing)
                val prevTimer = singleClickConfirmTimer
                if (prevTimer != null) kuiklyWindow.clearTimeout(prevTimer)
                singleClickConfirmTimer = kuiklyWindow.setTimeout({
                    // If the double click callback is not triggered within timeout, invoke the click callback
                    // When double click callback triggered, the timer will be cleared
                    invokeClickCallback(it, false)
                    // Clear the timer
                    singleClickConfirmTimer = null
                    // Reset the click count
                    clickCount = 0
                }, KRListConst.DOUBLE_CLICK_TIMEOUT)
            }
        }
    }

    // Helper methods for PC scroll helper to access click state
    internal fun isClickEvent(): Boolean = isClickEvent
    internal fun setClickEvent(value: Boolean) { isClickEvent = value }
    internal fun cancelClickDetectionTimer() {
        clickDetectionTimer?.let {
            kuiklyWindow.clearTimeout(it)
            clickDetectionTimer = null
        }
    }

    /**
     * Bind scroll-related events
     */
    override fun setScrollEvent() {
        // If it is a pointing device with limited precision, listen for touch events.
        if (kuiklyWindow.matchMedia(KRListConst.POINTER_COARSE_QUERY).matches) {
            // Start dragging
            ele.addEventListener(KREventConst.TOUCH_START, {
                isClickEvent = true
                // If the mousemove event is not triggered, it will be considered a click event
                clickDetectionTimer = kuiklyWindow.setTimeout({
                    isClickEvent = true
                }, KRListConst.CLICK_DETECTION_TIMEOUT_TOUCH)
                if (pagingEnabled) {
                    if (!scrollEnabled) return@addEventListener
                    listPagingHelper.handlePagerTouchStart(it as TouchEvent)
                    return@addEventListener
                }
                if (nestScrollEnabled) {
                    nestScrollHelper.handleNestScrollTouchStart(it as TouchEvent)
                    return@addEventListener
                }
                handleTouchStart(it as TouchEvent)
            }, json(KRAttrConst.PASSIVE to true))

            // Move event
            ele.addEventListener(KREventConst.TOUCH_MOVE, {
                clickDetectionTimer?.let {
                    kuiklyWindow.clearTimeout(it)
                    clickDetectionTimer = null
                }
                isClickEvent = false
                if (pagingEnabled) {
                    if (!scrollEnabled) return@addEventListener
                    listPagingHelper.handlePagerTouchMove(it as TouchEvent)
                    return@addEventListener
                }
                if (nestScrollEnabled) {
                    nestScrollHelper.handleNestScrollTouchMove(it as TouchEvent)
                    return@addEventListener
                }
                handleTouchMove(it as TouchEvent)
            }, json(KRAttrConst.PASSIVE to (!pagingEnabled && !nestScrollEnabled)))

            // End dragging
            ele.addEventListener(KREventConst.TOUCH_END, {
                if (isClickEvent) {
                    handleClickEvent(it)
                    return@addEventListener
                }
                if (pagingEnabled) {
                    listPagingHelper.handlePagerTouchEnd(it as TouchEvent)
                    return@addEventListener
                }
                if (nestScrollEnabled) {
                    nestScrollHelper.handleNestScrollTouchEnd(it as TouchEvent)
                    return@addEventListener
                }
                handleTouchEnd()
            }, json(KRAttrConst.PASSIVE to true))
            ele.addEventListener(KREventConst.TOUCH_CANCEL, {
                isClickEvent = false
                handleTouchEnd()
            }, json(KRAttrConst.PASSIVE to true))
        }

        // If it is a precise pointing device, listen for mouse events.
        if (kuiklyWindow.matchMedia(KRListConst.POINTER_FINE_QUERY).matches) {
            ele.addEventListener(KREventConst.MOUSE_DOWN, { event ->
                event as MouseEvent
                // Only left button
                if (event.button != KRListConst.LEFT_MOUSE_BUTTON) return@addEventListener
                pcScrollHelper.isMouseDown = true
                // Reset click flag
                isClickEvent = true
                // If the mousemove event is not triggered, it will be considered a click event
                clickDetectionTimer = kuiklyWindow.setTimeout({
                    isClickEvent = true
                }, KRListConst.CLICK_DETECTION_TIMEOUT_MOUSE)
                // Save the current element
                PCListScrollHandler.mouseDownEleIds.add(ele.id)
                // Filter elements belonging to ListView
                PCListScrollHandler.filterScrollElementIds()
                // Initialize canScroll state
                pcScrollHelper.initCanScroll(showScrollerBar)
                if (pagingEnabled) {
                    if (!scrollEnabled) return@addEventListener
                    listPagingHelper.handlePagerMouseDown(event)
                    return@addEventListener
                }
                if (nestScrollEnabled) {
                    nestScrollHelper.handleNestScrollMouseDown(event)
                    return@addEventListener
                }
                handleTouchStart(event, true)
            }, json(KRAttrConst.PASSIVE to true))

            // Prevent text selection
            if (KuiklyProcessor.preventDefaultSelect) {
                ele.addEventListener(KREventConst.SELECT_START, {
                    it.preventDefault()
                })
            }
            // Prevent image drag
            if (KuiklyProcessor.preventDefaultDrag) {
                ele.addEventListener(KREventConst.DRAG_START, {
                    it.preventDefault()
                })
            } else {
                // Defensive fallback: when native HTML5 drag is allowed (e.g. user disabled
                // [preventDefaultDrag] / [preventDefaultDragAndSelect] to support text copy),
                // a `dragstart` will cause the browser to stop dispatching mousemove/mouseup,
                // which would leave `pcScrollHelper.isMouseDown` stuck as true and the list
                // would keep following the cursor until the next click. So we proactively
                // finalize the PC scroll state when a drag starts.
                ele.addEventListener(KREventConst.DRAG_START, { evt ->
                    // dragstart inherits from MouseEvent.
                    val mouseEvt = evt as MouseEvent
                    pcScrollHelper.cancelMouseInteraction(mouseEvt)
                    PCListScrollHandler.cancelMouseInteraction(mouseEvt)
                })
            }
        }
        ele.addEventListener(KREventConst.WHEEL, { event ->
            // Handle paging mode with wheel event
            event as WheelEvent
            if (pagingEnabled) {
                if (!scrollEnabled) return@addEventListener
                var eps = 1.0; // depending on device sensitivity
                val isVerticalScroll = event.deltaY.absoluteValue > event.deltaX.absoluteValue + eps
                val isHorizontalScroll = event.deltaX.absoluteValue > event.deltaY.absoluteValue + eps
                val isWheelMatchDirection = (isVerticalScroll && scrollDirection == KRListConst.SCROLL_DIRECTION_COLUMN)
                        || (isHorizontalScroll && scrollDirection == KRListConst.SCROLL_DIRECTION_ROW)
                if (isWheelMatchDirection) {
                    listPagingHelper.handlePagerWheel(event)
                }
                return@addEventListener
            }

            // Normal scroll mode
            if (!isWheelRolling) {
                isWheelRolling = true
                // 滚动条触发尾部刷新（FooterRefreshView需要拖拽过一次才能进行加载更多）
                handleTouchStart(event)
            }
            // When the wheel is rolled, the previous timer is cleared and a new timer is set.
            wheelStopTimer?.let {
                kuiklyWindow.clearTimeout(it)
            }
            wheelStopTimer = kuiklyWindow.setTimeout({
                // The callback is executed when the timer expires.
                isWheelRolling = false
                handleTouchEnd()
            }, KRListConst.WHEEL_STOP_TIMEOUT)
        })
        // Scroll event
        ele.addEventListener(KREventConst.SCROLL, {
            if (pagingEnabled) {
                // In paging mode, no need to trigger scroll
                // Calculate offset through touchmove and touchend,
                // and callback scroll event to upper layer for processing
                return@addEventListener
            }
            if (nestScrollEnabled) {
                nestScrollHelper.handleNestScrollTouchScroll(it)
                return@addEventListener
            }
            handleTouchScroll()
        }, json(KRAttrConst.PASSIVE to false))
    }

    /**
     * Set scroll end callback event
     */
    override fun setScrollEndEvent() {
        ele.addEventListener("scrollend", {
            finishScrollEnd()
        }, json(KRAttrConst.PASSIVE to true))
        // Fallback for browsers without native scrollend.
        ele.addEventListener(KREventConst.SCROLL, {
            // Clear existing timer first
            if (scrollEndEventTimer > 0) {
                kuiklyWindow.clearTimeout(scrollEndEventTimer)
            }
            // Reset timer
            scrollEndEventTimer = kuiklyWindow.setTimeout({
                finishScrollEnd()
            }, KRListConst.SCROLL_END_OVERTIME)
        }, json(KRAttrConst.PASSIVE to true))
    }

    /**
     * Scroll element to specified position
     */
    override fun setContentOffset(params: String?, callback: KuiklyRenderCallback?) {
        if (params == null) {
            callback?.invoke(writeResult(WebScrollWriteResultCode.OutOfRange))
            return
        }
        val contentOffsetSplits = params.split(KRCssConst.BLANK_SEPARATOR)
        val offsetX = contentOffsetSplits[0].toFloat()
        val offsetY = contentOffsetSplits[1].toFloat()
        val animate = contentOffsetSplits[2] == KRListConst.ANIMATE_FLAG
        val duration = contentOffsetSplits.getOrNull(3)?.toLongOrNull() ?: 0L
        val generation = if (contentOffsetSplits.size > 8) contentOffsetSplits[7].toLong() else -1L
        val requiresNativeIdle = contentOffsetSplits.size > 8 && contentOffsetSplits[8] == "1"
        val composeOperation = contentOffsetSplits.getOrNull(9)?.toLong() ?: 0L
        val expectedContentSize = contentOffsetSplits.getOrNull(10)?.toFloat() ?: -1f
        val expectedViewportSize = contentOffsetSplits.getOrNull(11)?.toFloat() ?: -1f
        val interactionEpoch = contentOffsetSplits.getOrNull(17)?.toLongOrNull()
            ?: nativeInteractionEpoch
        val layoutRevision = contentOffsetSplits.getOrNull(18)?.toLongOrNull()
            ?: nativeLayoutRevision
        val bindingGeneration = contentOffsetSplits.getOrNull(12)?.toLongOrNull() ?: 0L
        val capabilityKind = contentOffsetSplits.getOrNull(13)?.toIntOrNull() ?: -1
        val capabilityLeaseId = contentOffsetSplits.getOrNull(14)?.toLongOrNull() ?: 0L
        val semanticOperationId = contentOffsetSplits.getOrNull(15)?.toLongOrNull() ?: 0L
        val attemptGeneration = contentOffsetSplits.getOrNull(16)?.toLongOrNull() ?: 0L
        val anchorRevision = contentOffsetSplits.getOrNull(19)?.toLongOrNull() ?: 0L
        val rangeRevision = contentOffsetSplits.getOrNull(20)?.toLongOrNull() ?: 0L
        val insetRevision = contentOffsetSplits.getOrNull(21)?.toLongOrNull()
            ?: nativeInsetRevision
        refreshLayoutRevision()
        val validation = validateWrite(
            generation, requiresNativeIdle, composeOperation,
            interactionEpoch, layoutRevision, insetRevision,
            expectedContentSize, expectedViewportSize,
            offsetX.isNaN() || offsetY.isNaN(),
        )
        if (validation != WebScrollWriteResultCode.Committed) {
            callback?.invoke(writeResult(validation))
            return
        }
        pendingReuseScrollEventTimer?.let { kuiklyWindow.clearTimeout(it) }
        pendingReuseScrollEventTimer = null
        val operation = installWrite(
            WebScrollWriteKind.ContentOffset, callback, generation, composeOperation,
            interactionEpoch, layoutRevision, insetRevision,
            bindingGeneration, capabilityKind, capabilityLeaseId,
            semanticOperationId, attemptGeneration, anchorRevision, rangeRevision,
        )
        if (!writeArbiter.isCurrent(operation)) return
        operation.targetX = offsetX
        operation.targetY = offsetY
        operation.started = true
        if (pagingEnabled) {
            val shouldRefireReuseScroll = pendingFireScrollForReuse
            pendingFireScrollForReuse = false
            val scheduled = listPagingHelper.setContentOffset(
                offsetX = offsetX,
                offsetY = offsetY,
                animate = animate,
                completion = { mutationCommitted ->
                    if (!writeArbiter.isCurrent(operation)) return@setContentOffset
                    refreshLayoutRevision()
                    val committed = mutationCommitted && operation.generation.let {
                        it < 0 || it == composeOffsetWriteGeneration
                    } && operation.interactionEpoch == nativeInteractionEpoch &&
                        operation.layoutRevision == nativeLayoutRevision &&
                        operation.insetRevision == nativeInsetRevision &&
                        isCurrentOffsetWrite(operation.composeOperation) &&
                        matchesExpectedLayout(expectedContentSize, expectedViewportSize)
                    if (shouldRefireReuseScroll && committed) {
                        pendingReuseScrollEventTimer?.let { kuiklyWindow.clearTimeout(it) }
                        pendingReuseScrollEventTimer = kuiklyWindow.setTimeout({
                            pendingReuseScrollEventTimer = null
                            val cb = scrollEventCallback ?: return@setTimeout
                            val map = updateOffsetMap(
                                abs(listPagingHelper.currentTranslateX),
                                abs(listPagingHelper.currentTranslateY),
                                isDragging,
                            )
                            cb.invoke(map)
                        }, KRListConst.IMMEDIATE_TIMEOUT)
                    }
                    finishWrite(
                        operation,
                        if (committed) WebScrollWriteResultCode.Committed
                        else WebScrollWriteResultCode.Interrupted,
                        animate,
                    )
                },
                isCurrent = { writeArbiter.isCurrent(operation) },
            )
            if (!scheduled) {
                finishWrite(operation, WebScrollWriteResultCode.UnsupportedAxisOrNoLayout, false)
            } else if (writeArbiter.isCurrent(operation)) {
                nativeScrollPhase = if (animate) 2 else 0
            }
            return
        }
        val alreadySatisfied = abs(ele.scrollLeft.toFloat() - offsetX) <= 1f &&
            abs(ele.scrollTop.toFloat() - offsetY) <= 1f
        if (alreadySatisfied) {
            finishWrite(operation, WebScrollWriteResultCode.AlreadySatisfied, false)
            return
        }
        ele.scrollTo(
            ScrollToOptions(
                offsetX.toDouble(),
                offsetY.toDouble(),
                if (animate) ScrollBehavior.SMOOTH else ScrollBehavior.AUTO
            )
        )
        if (animate) {
            nativeScrollPhase = 2
            activeSmoothScroll = operation
            scheduleSmoothTargetCheck(operation)
            val acceptedDuration = if (duration > 0L) duration else 300L
            val slack = kotlin.math.max(1_000L, acceptedDuration / 4L)
            smoothScrollEndTimer = kuiklyWindow.setTimeout({
                if (writeArbiter.isCurrent(operation)) {
                    ele.scrollTo(ScrollToOptions(ele.scrollLeft, ele.scrollTop, ScrollBehavior.AUTO))
                    finishWrite(operation, WebScrollWriteResultCode.AckTimeout, true)
                }
            }, (acceptedDuration + slack).toInt())
        } else {
            refreshLayoutRevision()
            val committed = abs(ele.scrollLeft.toFloat() - offsetX) <= 1f &&
                abs(ele.scrollTop.toFloat() - offsetY) <= 1f
            finishWrite(
                operation,
                if (committed) WebScrollWriteResultCode.Committed
                else WebScrollWriteResultCode.Interrupted,
                false,
            )
        }
        // After Compose DSL reuse, the upper layer sets `ignoreScrollOffset` and expects the
        // next setContentOffset to fire a scroll event so the flag can be cleared. However,
        // when the target offset equals the current scrollTop/scrollLeft, browsers won't
        // dispatch a `scroll` event at all. To match iOS/Android semantics ("setContentOffset
        // always triggers a scroll callback"), proactively fire one async scroll event.
        if (pendingFireScrollForReuse) {
            pendingFireScrollForReuse = false
            pendingReuseScrollEventTimer?.let { kuiklyWindow.clearTimeout(it) }
            pendingReuseScrollEventTimer = kuiklyWindow.setTimeout({
                pendingReuseScrollEventTimer = null
                val cb = scrollEventCallback ?: return@setTimeout
                val map = updateOffsetMap(ele.scrollLeft.toFloat(), ele.scrollTop.toFloat(), isDragging)
                cb.invoke(map)
            }, KRListConst.IMMEDIATE_TIMEOUT)
        }
    }

    /**
     * Clear transient state for Compose DSL reuse.
     *
     * The actual "reset" web side needs is much smaller than native (no native cell pool here);
     * the critical part is to make sure the *next* [setContentOffset] still fires a scroll
     * event even if scrollTop/scrollLeft do not change, so that the upper-layer
     * `ignoreScrollOffset` flag can be cleared.
     */
    override fun prepareForComposeReuse(generation: Long) {
        writeOperationSequence += 1L
        composeOffsetWriteGeneration = generation
        nativeInteractionEpoch += 1L
        nativeLayoutRevision += 1L
        nativeInsetRevision += 1L
        latestComposeWriteOperation = 0L
        minimumComposeWriteOperation = 0L
        invalidateWrite(WebScrollWriteResultCode.Destroyed)
        insetWhenEndDragSequence += 1L
        pendingReuseScrollEventTimer?.let { kuiklyWindow.clearTimeout(it) }
        pendingReuseScrollEventTimer = null
        if (scrollEndEventTimer > 0) {
            kuiklyWindow.clearTimeout(scrollEndEventTimer)
            scrollEndEventTimer = 0
        }
        nativeScrollPhase = 0
        isDragging = 0
        pendingFireScrollForReuse = true
    }

    private fun canApplyOffsetWrite(generation: Long, requiresNativeIdle: Boolean): Boolean {
        return canApplyWebOffsetWrite(
            generation,
            requiresNativeIdle,
            composeOffsetWriteGeneration,
            nativeScrollPhase,
        )
    }

    private fun claimOffsetWrite(
        generation: Long,
        requiresNativeIdle: Boolean,
        composeOperation: Long,
    ): Boolean {
        if (!canApplyOffsetWrite(generation, requiresNativeIdle)) return false
        if (composeOperation <= 0L) return true
        if (composeOperation < minimumComposeWriteOperation ||
            composeOperation < latestComposeWriteOperation) return false
        latestComposeWriteOperation = composeOperation
        return true
    }

    private fun validateWrite(
        generation: Long,
        requiresNativeIdle: Boolean,
        composeOperation: Long,
        interactionEpoch: Long,
        layoutRevision: Long,
        insetRevision: Long,
        expectedContentSize: Float,
        expectedViewportSize: Float,
        outOfRange: Boolean,
    ): WebScrollWriteResultCode {
        if (outOfRange) return WebScrollWriteResultCode.OutOfRange
        if (generation >= 0 && generation != composeOffsetWriteGeneration) {
            return WebScrollWriteResultCode.Stale
        }
        if (requiresNativeIdle && nativeScrollPhase != 0) return WebScrollWriteResultCode.Busy
        if (composeOperation > 0L &&
            (composeOperation < minimumComposeWriteOperation ||
                composeOperation < latestComposeWriteOperation)) {
            return WebScrollWriteResultCode.Stale
        }
        if (interactionEpoch != nativeInteractionEpoch) return WebScrollWriteResultCode.Interrupted
        if (layoutRevision != nativeLayoutRevision) {
            return if (ele.offsetWidth == 0 && ele.offsetHeight == 0) {
                WebScrollWriteResultCode.NotReady
            } else {
                WebScrollWriteResultCode.LayoutChanged
            }
        }
        if (insetRevision != nativeInsetRevision) return WebScrollWriteResultCode.Stale
        if (!matchesExpectedLayout(expectedContentSize, expectedViewportSize)) {
            return if (ele.offsetWidth == 0 && ele.offsetHeight == 0) {
                WebScrollWriteResultCode.NotReady
            } else {
                WebScrollWriteResultCode.LayoutChanged
            }
        }
        if (composeOperation > 0L) latestComposeWriteOperation = composeOperation
        return WebScrollWriteResultCode.Committed
    }

    private fun isCurrentOffsetWrite(composeOperation: Long): Boolean {
        return composeOperation <= 0L ||
            (composeOperation == latestComposeWriteOperation &&
                composeOperation >= minimumComposeWriteOperation)
    }

    private fun matchesExpectedLayout(
        expectedContentSize: Float,
        expectedViewportSize: Float,
    ): Boolean {
        if (expectedContentSize < 0f || expectedViewportSize < 0f) return true
        val vertical = scrollDirection == KRListConst.SCROLL_DIRECTION_COLUMN
        val actualContentSize = if (vertical) ele.scrollHeight.toFloat() else ele.scrollWidth.toFloat()
        val actualViewportSize = if (vertical) ele.offsetHeight.toFloat() else ele.offsetWidth.toFloat()
        return kotlin.math.abs(actualContentSize - expectedContentSize) <= 1f &&
            kotlin.math.abs(actualViewportSize - expectedViewportSize) <= 1f
    }

    private fun refreshLayoutRevision() {
        val contentWidth = ele.scrollWidth
        val contentHeight = ele.scrollHeight
        val viewportWidth = ele.offsetWidth
        val viewportHeight = ele.offsetHeight
        if (lastContentWidth < 0) {
            lastContentWidth = contentWidth
            lastContentHeight = contentHeight
            lastViewportWidth = viewportWidth
            lastViewportHeight = viewportHeight
        } else if (contentWidth != lastContentWidth || contentHeight != lastContentHeight ||
            viewportWidth != lastViewportWidth || viewportHeight != lastViewportHeight) {
            nativeLayoutRevision += 1L
            lastContentWidth = contentWidth
            lastContentHeight = contentHeight
            lastViewportWidth = viewportWidth
            lastViewportHeight = viewportHeight
        }
    }

    private fun writeResult(code: WebScrollWriteResultCode): Map<String, Any> =
        webScrollWriteResult(code, nativeInteractionEpoch, nativeLayoutRevision, nativeInsetRevision)

    private fun installWrite(
        kind: WebScrollWriteKind,
        callback: KuiklyRenderCallback?,
        generation: Long,
        composeOperation: Long,
        interactionEpoch: Long,
        layoutRevision: Long,
        insetRevision: Long,
        bindingGeneration: Long = 0,
        capabilityKind: Int = -1,
        capabilityLeaseId: Long = 0,
        semanticOperationId: Long = 0,
        attemptGeneration: Long = 0,
        anchorRevision: Long = 0,
        rangeRevision: Long = 0,
    ): WebScrollWriteOperation {
        val operation = WebScrollWriteOperation(
            sequence = ++writeOperationSequence,
            kind = kind,
            callback = callback,
            generation = generation,
            composeOperation = composeOperation,
            interactionEpoch = interactionEpoch,
            layoutRevision = layoutRevision,
            insetRevision = insetRevision,
            bindingGeneration = bindingGeneration,
            capabilityKind = capabilityKind,
            capabilityLeaseId = capabilityLeaseId,
            semanticOperationId = semanticOperationId,
            attemptGeneration = attemptGeneration,
            anchorRevision = anchorRevision,
            rangeRevision = rangeRevision,
        )
        val previous = writeArbiter.install(operation)
        abortSmoothScroll()
        val previousResult = previous?.callback?.let { writeResult(WebScrollWriteResultCode.Replaced) }
        previous?.callback?.let { it.invoke(previousResult!!) }
        return operation
    }

    private fun finishWrite(
        operation: WebScrollWriteOperation,
        code: WebScrollWriteResultCode,
        fireScrollEnd: Boolean,
    ) {
        val callback = writeArbiter.complete(operation) ?: return
        if (activeSmoothScroll === operation) activeSmoothScroll = null
        smoothScrollFrameId?.let { kuiklyWindow.cancelAnimationFrame(it) }
        smoothScrollFrameId = null
        smoothScrollEndTimer?.let { kuiklyWindow.clearTimeout(it) }
        smoothScrollEndTimer = null
        if (nativeScrollPhase == 2 && isDragging == 0) nativeScrollPhase = 0
        refreshLayoutRevision()
        val result = writeResult(code)
        val eventParams = updateOffsetMap(ele.scrollLeft.toFloat(), ele.scrollTop.toFloat(), isDragging)
        if (fireScrollEnd) {
            lastProgrammaticTerminalAt = Date.now()
            scrollEndEventCallback?.invoke(eventParams)
        }
        callback.invoke(result)
    }

    private fun invalidateWrite(code: WebScrollWriteResultCode) {
        val operation = writeArbiter.invalidate() ?: return
        abortSmoothScroll()
        val result = operation.callback?.let { writeResult(code) }
        operation.callback?.let { it.invoke(result!!) }
    }

    private fun scheduleSmoothTargetCheck(operation: WebScrollWriteOperation) {
        smoothScrollFrameId?.let { kuiklyWindow.cancelAnimationFrame(it) }
        fun checkFrame(timestamp: Double) {
            if (!writeArbiter.isCurrent(operation)) return
            val reached = abs(ele.scrollLeft.toFloat() - operation.targetX) <= 1f &&
                abs(ele.scrollTop.toFloat() - operation.targetY) <= 1f
            operation.observedFrames = if (reached) operation.observedFrames + 1 else 0
            if (operation.observedFrames >= 3) {
                finishWrite(operation, WebScrollWriteResultCode.Committed, true)
            } else {
                smoothScrollFrameId = kuiklyWindow.requestAnimationFrame(::checkFrame)
            }
        }
        smoothScrollFrameId = kuiklyWindow.requestAnimationFrame(::checkFrame)
    }

    private fun abortSmoothScroll() {
        listPagingHelper.cancelProgrammaticScroll()
        smoothScrollFrameId?.let { kuiklyWindow.cancelAnimationFrame(it) }
        smoothScrollFrameId = null
        smoothScrollEndTimer?.let { kuiklyWindow.clearTimeout(it) }
        smoothScrollEndTimer = null
        val ownedSmoothScroll = activeSmoothScroll != null
        if (ownedSmoothScroll) {
            ele.scrollTo(ScrollToOptions(ele.scrollLeft, ele.scrollTop, ScrollBehavior.AUTO))
            if (scrollEndEventTimer > 0) {
                kuiklyWindow.clearTimeout(scrollEndEventTimer)
                scrollEndEventTimer = 0
            }
        }
        activeSmoothScroll = null
        if (ownedSmoothScroll && isDragging == 0) {
            nativeScrollPhase = 0
        }
    }

    private fun finishScrollEnd() {
        if (scrollEndEventTimer > 0) {
            kuiklyWindow.clearTimeout(scrollEndEventTimer)
            scrollEndEventTimer = 0
        }
        val operation = activeSmoothScroll
        if (operation != null && writeArbiter.isCurrent(operation)) {
            scheduleSmoothTargetCheck(operation)
            return
        }
        if (Date.now() - lastProgrammaticTerminalAt <= 250.0) return
        nativeScrollIngressActive = false
        nativeScrollPhase = 0
        scrollEndEventCallback?.invoke(
            updateOffsetMap(ele.scrollLeft.toFloat(), ele.scrollTop.toFloat(), isDragging)
        )
    }

    /**
     * Set whether listView needs scrollbars
     */
    override fun setShowScrollIndicator(params: Any): Boolean {
        // Whether to show scrollbars
        showScrollerBar = params.unsafeCast<Int>() == KRListConst.ENABLED_FLAG
        if (showScrollerBar) {
            // Remove the class that hides scrollbars
            ele.classList.remove(KRListConst.NO_SCROLL_BAR_CLASS)
        } else {
            // Add the class that hides scrollbars
            ele.classList.add(KRListConst.NO_SCROLL_BAR_CLASS)
        }
        return true
    }

    /**
     * Set content inset with animation
     */
    override fun setContentInset(params: String?, callback: KuiklyRenderCallback?) {
        val contentInsetString = params ?: run {
            callback?.invoke(writeResult(WebScrollWriteResultCode.OutOfRange))
            return
        }
        val contentInset = KRListViewContentInset(contentInsetString)
        val splits = contentInsetString.split(KRCssConst.BLANK_SEPARATOR)
        val generation = if (splits.size > 6) splits[5].toLong() else -1L
        val requiresNativeIdle = splits.size > 6 && splits[6] == "1"
        val composeOperation = splits.getOrNull(7)?.toLong() ?: 0L
        val expectedContentSize = splits.getOrNull(8)?.toFloat() ?: -1f
        val expectedViewportSize = splits.getOrNull(9)?.toFloat() ?: -1f
        val bindingGeneration = splits.getOrNull(10)?.toLongOrNull() ?: 0L
        val capabilityKind = splits.getOrNull(11)?.toIntOrNull() ?: -1
        val capabilityLeaseId = splits.getOrNull(12)?.toLongOrNull() ?: 0L
        val semanticOperationId = splits.getOrNull(13)?.toLongOrNull() ?: 0L
        val attemptGeneration = splits.getOrNull(14)?.toLongOrNull() ?: 0L
        val interactionEpoch = splits.getOrNull(15)?.toLongOrNull() ?: nativeInteractionEpoch
        val layoutRevision = splits.getOrNull(16)?.toLongOrNull() ?: nativeLayoutRevision
        val anchorRevision = splits.getOrNull(17)?.toLongOrNull() ?: 0L
        val rangeRevision = splits.getOrNull(18)?.toLongOrNull() ?: 0L
        val insetRevision = splits.getOrNull(19)?.toLongOrNull() ?: nativeInsetRevision
        refreshLayoutRevision()
        val validation = validateWrite(
            generation, requiresNativeIdle, composeOperation,
            interactionEpoch, layoutRevision, insetRevision,
            expectedContentSize, expectedViewportSize, false,
        )
        if (validation != WebScrollWriteResultCode.Committed) {
            callback?.invoke(writeResult(validation))
            return
        }
        val operation = installWrite(
            WebScrollWriteKind.ContentInset, callback, generation, composeOperation,
            interactionEpoch, layoutRevision, insetRevision,
            bindingGeneration, capabilityKind, capabilityLeaseId,
            semanticOperationId, attemptGeneration, anchorRevision, rangeRevision,
        )
        if (!writeArbiter.isCurrent(operation)) return
        operation.started = true
        KuiklyRenderCoreContextScheduler.scheduleTask(KRListConst.IMMEDIATE_TIMEOUT) {
            if (!writeArbiter.isCurrent(operation)) return@scheduleTask
            refreshLayoutRevision()
            if (operation.interactionEpoch != nativeInteractionEpoch) {
                finishWrite(operation, WebScrollWriteResultCode.Interrupted, false)
                return@scheduleTask
            }
            val contentEle = ele.firstElementChild.unsafeCast<HTMLElement?>()
            if (contentEle == null) {
                finishWrite(operation, WebScrollWriteResultCode.NotReady, false)
                return@scheduleTask
            }
            val targetTransform = buildTranslate(contentInset.left, contentInset.top)
            if (contentEle.style.transform == targetTransform) {
                finishWrite(operation, WebScrollWriteResultCode.AlreadySatisfied, false)
                return@scheduleTask
            }
            contentEle.style.transition = if (contentInset.animate) {
                buildTransition()
            } else {
                KRCssConst.EMPTY_STRING
            }
            contentEle.style.transform = targetTransform
            if (!contentInset.animate) {
                nativeInsetRevision += 1L
                finishWrite(operation, WebScrollWriteResultCode.Committed, false)
                return@scheduleTask
            }
            nativeScrollPhase = 2
            var insetRevisionCommitted = false
            fun completeIfCurrent() {
                if (!writeArbiter.isCurrent(operation)) return
                val committed = contentEle.style.transform == targetTransform &&
                    (operation.generation < 0 || operation.generation == composeOffsetWriteGeneration) &&
                    operation.interactionEpoch == nativeInteractionEpoch &&
                    isCurrentOffsetWrite(operation.composeOperation) &&
                    matchesExpectedLayout(expectedContentSize, expectedViewportSize)
                if (committed && !insetRevisionCommitted) {
                    insetRevisionCommitted = true
                    nativeInsetRevision += 1L
                }
                contentEle.style.transition = KRCssConst.EMPTY_STRING
                finishWrite(
                    operation,
                    if (committed) WebScrollWriteResultCode.Committed
                    else WebScrollWriteResultCode.Interrupted,
                    true,
                )
            }
            lateinit var transitionListener: (Event) -> Unit
            transitionListener = { event ->
                if (event.asDynamic().propertyName == TRANSFORM_PROPERTY) {
                    contentEle.removeEventListener("transitionend", transitionListener)
                    completeIfCurrent()
                }
            }
            contentEle.addEventListener("transitionend", transitionListener)
            smoothScrollEndTimer = kuiklyWindow.setTimeout({
                contentEle.removeEventListener("transitionend", transitionListener)
                if (!writeArbiter.isCurrent(operation)) return@setTimeout
                if (contentEle.style.transform == targetTransform) {
                    completeIfCurrent()
                } else {
                    finishWrite(operation, WebScrollWriteResultCode.AckTimeout, true)
                }
            }, KRListConst.BOUND_BACK_DURATION.toInt() + 1_000)
        }
    }

    /**
     * Set inner padding when drag ends, i.e., translateX and Y values
     */
    override fun setContentInsetWhenEndDrag(params: String?) {
        // Inset value to set
        val contentInsetString = params ?: return
        // Format inset value
        val contentInset = KRListViewContentInset(contentInsetString)
        val splits = contentInsetString.split(KRCssConst.BLANK_SEPARATOR)
        val generation = if (splits.size > 6) splits[5].toLong() else composeOffsetWriteGeneration
        val requiresNativeIdle = splits.size > 6 && splits[6] == "1"
        val composeOperation = splits.getOrNull(7)?.toLong() ?: 0L
        val expectedContentSize = splits.getOrNull(8)?.toFloat() ?: -1f
        val expectedViewportSize = splits.getOrNull(9)?.toFloat() ?: -1f
        if (!claimOffsetWrite(generation, requiresNativeIdle, composeOperation) ||
            !matchesExpectedLayout(expectedContentSize, expectedViewportSize)) return
        pullRefreshComposeOperation = composeOperation
        pullRefreshExpectedContentSize = expectedContentSize
        pullRefreshExpectedViewportSize = expectedViewportSize
        insetWhenEndDragSequence += 1L
        val writeSequence = insetWhenEndDragSequence
        // Transform content to set
        val transform = buildTranslate(contentInset.left, contentInset.top)
        if (contentInset.top == 0f) {
            // Restore listView to scrollable
            if (scrollDirection == KRListConst.SCROLL_DIRECTION_COLUMN) {
                ele.style.overflowY = KRStyleConst.OVERFLOW_SCROLL
                ele.style.overflowX = KRStyleConst.OVERFLOW_HIDDEN
            } else {
                ele.style.overflowX = KRStyleConst.OVERFLOW_SCROLL
                ele.style.overflowY = KRStyleConst.OVERFLOW_HIDDEN
            }
            // When top > 0, it sets the terminal listView inset height when terminal pull-to-refresh,
            // web doesn't support pull bounce by default,
            // so this value is not processed, only handle the value when preparing for pull-to-refresh
            KuiklyRenderCoreContextScheduler.scheduleTask(KRListConst.BOUND_BACK_DURATION.toInt()) {
                if (writeSequence != insetWhenEndDragSequence ||
                    generation != composeOffsetWriteGeneration ||
                    !isCurrentOffsetWrite(composeOperation) ||
                    !matchesExpectedLayout(expectedContentSize, expectedViewportSize)) {
                    return@scheduleTask
                }
                // Clear animation
                val contentEle = ele.firstElementChild.unsafeCast<HTMLElement?>()
                contentEle?.style?.transition = KRCssConst.EMPTY_STRING
                // Delay setting inset value until pull-down animation completes
                contentEle?.style?.transform = if (contentInset.left == 0f && contentInset.top == 0f) {
                    KRCssConst.EMPTY_STRING
                } else {
                    transform
                }
            }
        } else {
            // This indicates it has been pulled down to a position where it can refresh,
            // record the pull-to-refresh position
            canPullRefreshHeight = contentInset.top
        }
    }


    /**
     * Clear existing timers and resources when component is destroyed
     */
    override fun destroy() {
        writeOperationSequence += 1L
        minimumComposeWriteOperation = latestComposeWriteOperation + 1L
        nativeInteractionEpoch += 1L
        nativeLayoutRevision += 1L
        nativeInsetRevision += 1L
        invalidateWrite(WebScrollWriteResultCode.Destroyed)
        insetWhenEndDragSequence += 1L
        pendingReuseScrollEventTimer?.let { kuiklyWindow.clearTimeout(it) }
        pendingReuseScrollEventTimer = null
        nativeScrollPhase = 0
        // Clear all timers
        if (scrollEndEventTimer > 0) {
            kuiklyWindow.clearTimeout(scrollEndEventTimer)
            scrollEndEventTimer = 0
        }
        
        clickDetectionTimer?.let {
            kuiklyWindow.clearTimeout(it)
        }
        clickDetectionTimer = null
        
        singleClickConfirmTimer?.let {
            kuiklyWindow.clearTimeout(it)
        }
        singleClickConfirmTimer = null
        
        wheelStopTimer?.let {
            kuiklyWindow.clearTimeout(it)
        }
        wheelStopTimer = null
        
        // Clear helper resources (timers and requestAnimationFrame)
        listPagingHelper.destroy()
        nestScrollHelper.destroy()
    }

    companion object {
        // Log messages
        private const val LOG_SCROLL_EVENT_BEGIN = "scroll direction event begin"

        // CSS property names
        private const val TRANSFORM_PROPERTY = "transform"

        // CSS overscroll-behavior property names and values, used to control native bounce/pull-to-refresh
        private const val OVERSCROLL_BEHAVIOR_X = "overscroll-behavior-x"
        private const val OVERSCROLL_BEHAVIOR_Y = "overscroll-behavior-y"
        private const val OVERSCROLL_AUTO = "auto"
        private const val OVERSCROLL_NONE = "none"

        // Helper functions for building CSS values
        private fun buildTranslateY(y: Any) = "translate(0, $y${KRStyleConst.PX_SUFFIX})"
        private fun buildTranslate(x: Any, y: Any) =
            "translate($x${KRStyleConst.PX_SUFFIX}, $y${KRStyleConst.PX_SUFFIX})"
        private fun buildTransition() =
            "$TRANSFORM_PROPERTY ${KRListConst.BOUND_BACK_DURATION}${KRStyleConst.MS_SUFFIX} ${KRStyleConst.EASE_IN}"
    }
}

enum class KRNestedScrollMode(val value: String) {
    SELF_ONLY("SELF_ONLY"),
    SELF_FIRST("SELF_FIRST"),
    PARENT_FIRST("PARENT_FIRST"),
}

enum class KRNestedScrollState(val value: String) {
    CAN_SCROLL("CAN_SCROLL"),
    SCROLL_BOUNDARY("SCROLL_BOUNDARY"),
    CANNOT_SCROLL("CANNOT_SCROLL"),
}
