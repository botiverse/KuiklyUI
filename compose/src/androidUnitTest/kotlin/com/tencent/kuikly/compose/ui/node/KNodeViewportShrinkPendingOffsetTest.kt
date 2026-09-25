/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI.
 */

package com.tencent.kuikly.compose.ui.node

import com.tencent.kuikly.compose.ui.focus.FocusOwner
import com.tencent.kuikly.compose.ui.graphics.Canvas
import com.tencent.kuikly.compose.ui.input.InputModeManager
import com.tencent.kuikly.compose.ui.layout.MeasurePolicy
import com.tencent.kuikly.compose.ui.modifier.ModifierLocalManager
import com.tencent.kuikly.compose.ui.platform.KuiklySoftwareKeyboardController
import com.tencent.kuikly.compose.ui.platform.ViewConfiguration
import com.tencent.kuikly.compose.ui.unit.Constraints
import com.tencent.kuikly.compose.ui.unit.Density
import com.tencent.kuikly.compose.ui.unit.IntOffset
import com.tencent.kuikly.compose.ui.unit.LayoutDirection
import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.manager.BridgeManager
import com.tencent.kuikly.core.manager.PagerManager
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.views.ScrollParams
import com.tencent.kuikly.core.views.ScrollerAttr
import com.tencent.kuikly.core.views.ScrollerEvent
import com.tencent.kuikly.core.views.ScrollerView
import com.tencent.kuikly.core.views.SpringAnimation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class KNodeViewportShrinkPendingOffsetTest {

    @Test
    fun shrinkReplaysPendingProgrammaticOffsetAfterFrameCommitBeforePixelsAreSampled() {
        withViewportFixture(pagerId = "viewport-shrink-pending-offset") {
            val density = scrollInfo.getDensity()
            assertEquals(622f / density, scrollerView.renderView?.currentFrame?.height)

            val nativeOffsetPx = 4_000
            val pendingTargetPx = 5_000
            setPendingProgrammaticOffset(
                nativeOffsetPx = nativeOffsetPx,
                pendingTargetPx = pendingTargetPx,
            )

            resizeViewportTo(315)

            assertEquals(
                listOf(PendingOffsetReplay(pendingTargetPx, 315)),
                scrollerView.offsetReplays,
                "the pending target must be replayed only after the shrunken native frame is committed",
            )
            val nonWhiteCount = visiblePixelCount(
                viewportStart = scrollerView.appliedNativeOffsetPx,
                viewportSize = 315,
                placedRowStart = pendingTargetPx,
                placedRowSize = 100,
                rowWidth = 300,
            )
            assertTrue(nonWhiteCount > 0, "shrink frame must retain at least one drawable row pixel")
        }
    }

    @Test
    fun shrinkDoesNotResurrectPendingTargetConsumedDuringFrameCommit() {
        withViewportFixture(pagerId = "viewport-shrink-consumed-offset") {
            val pendingTargetPx = 5_000
            setPendingProgrammaticOffset(
                nativeOffsetPx = 4_000,
                pendingTargetPx = pendingTargetPx,
            )
            scrollerView.onFrameCommitted = {
                scrollerView.appliedNativeOffsetPx = pendingTargetPx
                scrollInfo.ignoreScrollOffset = null
            }

            resizeViewportTo(315)

            assertEquals(315, scrollerView.committedFrameHeightPx())
            assertNull(scrollInfo.ignoreScrollOffset)
            assertTrue(
                scrollerView.offsetReplays.isEmpty(),
                "a synchronously consumed target must not be replayed after frame commit",
            )
        }
    }

    @Test
    fun shrinkDoesNotOverwriteNewPendingOwnerCreatedDuringFrameCommit() {
        withViewportFixture(pagerId = "viewport-shrink-newer-offset") {
            setPendingProgrammaticOffset(
                nativeOffsetPx = 4_000,
                pendingTargetPx = 5_000,
            )
            val newerTarget = IntOffset(0, 5_200)
            scrollerView.onFrameCommitted = {
                scrollInfo.composeOffset = newerTarget.y.toFloat()
                scrollInfo.ignoreScrollOffset = newerTarget
            }

            resizeViewportTo(315)

            assertEquals(newerTarget, scrollInfo.ignoreScrollOffset)
            assertEquals(newerTarget.y.toFloat(), scrollInfo.composeOffset)
            assertTrue(
                scrollerView.offsetReplays.isEmpty(),
                "the captured target must not overwrite a newer programmatic owner",
            )
        }
    }

    @Test
    fun expansionDoesNotReplayPendingTargetThroughShrinkOnlyPath() {
        withViewportFixture(
            pagerId = "viewport-expansion-pending-offset",
            initialViewportHeight = 315,
        ) {
            val pendingTarget = IntOffset(0, 5_000)
            setPendingProgrammaticOffset(
                nativeOffsetPx = 4_000,
                pendingTargetPx = pendingTarget.y,
            )

            resizeViewportTo(622)

            assertEquals(pendingTarget, scrollInfo.ignoreScrollOffset)
            assertTrue(
                scrollerView.offsetReplays.isEmpty(),
                "viewport expansion must not use the shrink-only pending replay path",
            )
        }
    }

    private fun withViewportFixture(
        pagerId: String,
        initialViewportHeight: Int = 622,
        block: ViewportFixture.() -> Unit,
    ) {
        val pageName = "KNodeViewportShrinkPendingOffsetTest"
        @Suppress("DEPRECATION")
        val previousPageId = BridgeManager.currentPageId
        @Suppress("DEPRECATION")
        fun setCurrentPageId(value: String) {
            BridgeManager.currentPageId = value
        }
        PagerManager.registerPageRouter(pageName) {
            object : Pager() {
                override fun body(): ViewBuilder = {}
            }
        }
        setCurrentPageId(pagerId)
        PagerManager.createPager(pagerId, pageName, "{}")

        val rootView = PagerManager.getPager(pagerId) as Pager
        val root = KNode<DeclarativeBaseView<*, *>>(rootView)
        val scrollerView = RecordingScrollerView()
        val scroller = KNode<DeclarativeBaseView<*, *>>(scrollerView)
        val owner = TestOwner(root)
        root.attach(owner)
        root.insertTopDown(0, scroller)
        root.insertAt(0, scroller)

        val scrollInfo = com.tencent.kuikly.compose.gestures.KuiklyScrollInfo().also { info ->
            info.scrollView = scrollerView
            info.currentContentSize = 6_000
        }
        scrollerView.renderProperties = RenderProperties().also { properties ->
            properties.kuiklyScrollInfo = scrollInfo
        }
        scroller.measurePolicy = MeasurePolicy { _, constraints ->
            layout(constraints.maxWidth, constraints.maxHeight) {}
        }

        var viewportHeight = initialViewportHeight
        root.measurePolicy = MeasurePolicy { measurables, constraints ->
            val placeable = measurables.single().measure(
                Constraints.fixed(constraints.maxWidth, viewportHeight)
            )
            layout(constraints.maxWidth, viewportHeight) {
                placeable.place(0, 0)
            }
        }

        try {
            assertTrue(root.remeasure(Constraints.fixed(300, viewportHeight)))
            root.place(0, 0)
            ViewportFixture(
                scrollerView = scrollerView,
                scrollInfo = scrollInfo,
                initialViewportHeight = viewportHeight,
                resize = { newHeight ->
                    viewportHeight = newHeight
                    assertTrue(root.remeasure(Constraints.fixed(300, newHeight)))
                    root.place(0, 0)
                },
            ).block()
        } finally {
            PagerManager.destroyPager(pagerId)
            setCurrentPageId(previousPageId)
        }
    }

    private data class PendingOffsetReplay(
        val offsetPx: Int,
        val frameHeightPx: Int,
    )

    private class ViewportFixture(
        val scrollerView: RecordingScrollerView,
        val scrollInfo: com.tencent.kuikly.compose.gestures.KuiklyScrollInfo,
        initialViewportHeight: Int,
        private val resize: (Int) -> Unit,
    ) {
        private var viewportHeight = initialViewportHeight

        fun setPendingProgrammaticOffset(
            nativeOffsetPx: Int,
            pendingTargetPx: Int,
        ) {
            val density = scrollInfo.getDensity()
            val scrollHandler = requireNotNull(
                scrollerView.getViewEvent()
                    .handlerWithEventName(ScrollerEvent.ScrollerEventConst.SCROLL)
            )
            scrollHandler(
                ScrollParams(
                    offsetX = 0f,
                    offsetY = nativeOffsetPx / density,
                    contentWidth = 300f,
                    contentHeight = 6_000f / density,
                    viewWidth = 300f,
                    viewHeight = viewportHeight.toFloat(),
                    isDragging = false,
                )
            )
            scrollInfo.composeOffset = pendingTargetPx.toFloat()
            scrollInfo.ignoreScrollOffset = IntOffset(0, pendingTargetPx)
            scrollerView.appliedNativeOffsetPx = nativeOffsetPx
            scrollerView.offsetReplays.clear()
        }

        fun resizeViewportTo(newHeight: Int) {
            viewportHeight = newHeight
            resize(newHeight)
        }
    }

    private class RecordingScrollerView : ScrollerView<ScrollerAttr, ScrollerEvent>() {
        var appliedNativeOffsetPx: Int = 0
        val offsetReplays = mutableListOf<PendingOffsetReplay>()
        var onFrameCommitted: (() -> Unit)? = null

        override fun setFrameToRenderView(frame: com.tencent.kuikly.core.layout.Frame) {
            super.setFrameToRenderView(frame)
            onFrameCommitted?.invoke()
        }

        fun committedFrameHeightPx(): Int {
            val density = getPager().pagerDensity()
            return ((renderView?.currentFrame?.height ?: 0f) * density).toInt()
        }

        override fun callContentOffset(
            offsetX: Float,
            offsetY: Float,
            animated: Boolean,
            springAnimation: SpringAnimation?,
        ) {
            val density = getPager().pagerDensity()
            val offsetPx = (offsetY * density).toInt()
            appliedNativeOffsetPx = offsetPx
            offsetReplays += PendingOffsetReplay(
                offsetPx = offsetPx,
                frameHeightPx = ((renderView?.currentFrame?.height ?: 0f) * density).toInt(),
            )
        }
    }

    private fun visiblePixelCount(
        viewportStart: Int,
        viewportSize: Int,
        placedRowStart: Int,
        placedRowSize: Int,
        rowWidth: Int,
    ): Int {
        val visibleStart = max(viewportStart, placedRowStart)
        val visibleEnd = min(viewportStart + viewportSize, placedRowStart + placedRowSize)
        return (visibleEnd - visibleStart).coerceAtLeast(0) * rowWidth
    }

    private class TestOwner(
        override val root: KNode<DeclarativeBaseView<*, *>>,
    ) : Owner {
        override val sharedDrawScope: LayoutNodeDrawScope
            get() = error("not used")
        override val rootForTest: RootForTest
            get() = error("not used")
        override val inputModeManager: InputModeManager
            get() = error("not used")
        override val density: Density = Density(1f)
        override val softwareKeyboardController: KuiklySoftwareKeyboardController
            get() = error("not used")
        override val focusOwner: FocusOwner
            get() = error("not used")
        override val layoutDirection: LayoutDirection = LayoutDirection.Ltr
        override var showLayoutBounds: Boolean = false
        override val measureIteration: Long = 0L
        override val viewConfiguration: ViewConfiguration
            get() = error("not used")
        override val snapshotObserver = OwnerSnapshotObserver { callback -> callback() }
        override val modifierLocalManager: ModifierLocalManager
            get() = error("not used")
        override val coroutineContext: CoroutineContext = EmptyCoroutineContext

        override fun onRequestMeasure(
            layoutNode: LayoutNode,
            affectsLookahead: Boolean,
            forceRequest: Boolean,
            scheduleMeasureAndLayout: Boolean,
        ) = Unit

        override fun onRequestRelayout(
            layoutNode: LayoutNode,
            affectsLookahead: Boolean,
            forceRequest: Boolean,
        ) = Unit

        override fun requestOnPositionedCallback(layoutNode: LayoutNode) = Unit
        override fun onAttach(node: LayoutNode) = Unit
        override fun onDetach(node: LayoutNode) = Unit
        override fun measureAndLayout(sendPointerUpdate: Boolean) = Unit
        override fun measureAndLayout(layoutNode: LayoutNode, constraints: Constraints) = Unit
        override fun forceMeasureTheSubtree(layoutNode: LayoutNode, affectsLookahead: Boolean) = Unit

        override fun createLayer(
            drawBlock: (Canvas) -> Unit,
            invalidateParentLayer: () -> Unit,
            view: DeclarativeBaseView<*, *>?,
        ): OwnedLayer = error("not used")

        override fun onSemanticsChange() = Unit
        override fun onLayoutChange(layoutNode: LayoutNode) = Unit
        override fun onZIndexChange(layoutNode: LayoutNode) = Unit
        override fun registerOnEndApplyChangesListener(listener: () -> Unit) = Unit
        override fun onEndApplyChanges() = Unit
        override fun registerOnLayoutCompletedListener(listener: Owner.OnLayoutCompletedListener) = Unit
    }
}
