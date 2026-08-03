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

package com.tencent.kuikly.compose.ui.layout

import androidx.compose.runtime.Recomposer
import com.tencent.kuikly.compose.ui.focus.FocusOwner
import com.tencent.kuikly.compose.ui.graphics.Canvas
import com.tencent.kuikly.compose.ui.input.InputModeManager
import com.tencent.kuikly.compose.ui.modifier.ModifierLocalManager
import com.tencent.kuikly.compose.ui.node.KNode
import com.tencent.kuikly.compose.ui.node.LayoutNode
import com.tencent.kuikly.compose.ui.node.Owner
import com.tencent.kuikly.compose.ui.node.OwnerSnapshotObserver
import com.tencent.kuikly.compose.ui.node.OwnedLayer
import com.tencent.kuikly.compose.ui.node.RootForTest
import com.tencent.kuikly.compose.ui.platform.KuiklySoftwareKeyboardController
import com.tencent.kuikly.compose.ui.platform.ViewConfiguration
import com.tencent.kuikly.compose.ui.unit.Constraints
import com.tencent.kuikly.compose.ui.unit.Density
import com.tencent.kuikly.compose.ui.unit.LayoutDirection
import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.manager.BridgeManager
import com.tencent.kuikly.core.manager.PagerManager
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.views.DivView
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SubcomposeSlotDrawReactivationTest {

    @Test
    fun retainedSlotActualReuseEntryWakesAncestorsForSameAndCompatibleKeysOnly() {
        val pagerId = "subcompose-slot-draw-reactivation"
        val pageName = "SubcomposeSlotDrawReactivationTest"
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

        val rootView = DivView().also { it.pagerId = pagerId }
        val root = KNode<DeclarativeBaseView<*, *>>(rootView)
        val owner = TestOwner(root)
        root.attach(owner)
        val recomposer = Recomposer(EmptyCoroutineContext)
        val state =
            LayoutNodeSubcompositionsState(
                root = root,
                slotReusePolicy = RetainAllCompatibleSlots
            ).also {
                it.compositionContext = recomposer
            }

        try {
            val firstHandle = state.precompose("message-a") {}
            val retainedSlot = root.foldedChildren.single() as KNode<*>
            val retainedLeaf = KNode(DivView())
            retainedSlot.insertAt(0, retainedLeaf)
            root.clearDrawInvalidationForTest()
            retainedSlot.clearDrawInvalidationForTest()
            retainedLeaf.clearDrawInvalidationForTest()

            firstHandle.dispose()

            assertEquals(true, retainedLeaf.viewVisible, "retention must hide and remember the native leaf")
            root.clearDrawInvalidationForTest()
            retainedSlot.clearDrawInvalidationForTest()
            retainedLeaf.clearDrawInvalidationForTest()
            retainedLeaf.invalidateDraw()
            retainedSlot.clearDrawInvalidationForTest()
            root.clearDrawInvalidationForTest()

            val sameKeyHandle = state.precompose("message-a") {}

            assertSame(retainedSlot, root.foldedChildren.single())
            assertTrue(retainedLeaf.isDrawInvalidatedForTest())
            assertTrue(retainedSlot.isDrawInvalidatedForTest())
            assertTrue(root.isDrawInvalidatedForTest())

            // An already-active/precomposed key does not pass through takeNodeFromReusables and
            // must therefore leave a clean draw tree untouched.
            root.clearDrawInvalidationForTest()
            retainedSlot.clearDrawInvalidationForTest()
            retainedLeaf.clearDrawInvalidationForTest()
            state.precompose("message-a") {}
            assertFalse(retainedSlot.isDrawInvalidatedForTest())
            assertFalse(root.isDrawInvalidatedForTest())

            sameKeyHandle.dispose()
            root.clearDrawInvalidationForTest()
            retainedSlot.clearDrawInvalidationForTest()
            retainedLeaf.clearDrawInvalidationForTest()
            retainedLeaf.invalidateDraw()
            retainedSlot.clearDrawInvalidationForTest()
            root.clearDrawInvalidationForTest()

            state.precompose("message-b") {}

            assertSame(retainedSlot, root.foldedChildren.single())
            assertTrue(retainedSlot.isDrawInvalidatedForTest())
            assertTrue(root.isDrawInvalidatedForTest())

            // A precomposed slot can be drawn past while it is still unplaced. The render root is
            // then clean even though the slot's descendants remain dirty. Consuming that exact
            // precomposed key through the real measure/subcompose path must wake the ancestors
            // again; otherwise the now-visible lazy item is placed in Compose but stays absent in
            // the native render tree.
            retainedLeaf.invalidateDraw()
            retainedSlot.clearDrawInvalidationForTest()
            root.clearDrawInvalidationForTest()
            root.measurePolicy = state.createMeasurePolicy {
                subcompose("message-b") {}
                layout(1, 1) {}
            }
            assertTrue(root.remeasure(Constraints.fixed(1, 1)))

            assertSame(retainedSlot, root.foldedChildren.single())
            assertTrue(retainedLeaf.isDrawInvalidatedForTest())
            assertTrue(retainedSlot.isDrawInvalidatedForTest())
            assertTrue(root.isDrawInvalidatedForTest())
        } finally {
            recomposer.close()
            PagerManager.destroyPager(pagerId)
            setCurrentPageId(previousPageId)
        }
    }

    private object RetainAllCompatibleSlots : SubcomposeSlotReusePolicy {
        override fun getSlotsToRetain(slotIds: SubcomposeSlotReusePolicy.SlotIdsSet) = Unit

        override fun areCompatible(slotId: Any?, reusableSlotId: Any?): Boolean = true
    }

    private class TestOwner(
        override val root: KNode<DeclarativeBaseView<*, *>>
    ) : Owner {
        override val sharedDrawScope: com.tencent.kuikly.compose.ui.node.LayoutNodeDrawScope
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
            scheduleMeasureAndLayout: Boolean
        ) = Unit

        override fun onRequestRelayout(
            layoutNode: LayoutNode,
            affectsLookahead: Boolean,
            forceRequest: Boolean
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
            view: DeclarativeBaseView<*, *>?
        ): OwnedLayer = error("not used")

        override fun onSemanticsChange() = Unit
        override fun onLayoutChange(layoutNode: LayoutNode) = Unit
        override fun onZIndexChange(layoutNode: LayoutNode) = Unit
        override fun registerOnEndApplyChangesListener(listener: () -> Unit) = Unit
        override fun onEndApplyChanges() = Unit
        override fun registerOnLayoutCompletedListener(listener: Owner.OnLayoutCompletedListener) = Unit
    }
}
