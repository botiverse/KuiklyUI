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

package com.tencent.kuikly.compose.ui.node

import com.tencent.kuikly.compose.ui.focus.FocusOwner
import com.tencent.kuikly.compose.ui.graphics.Canvas
import com.tencent.kuikly.compose.ui.input.InputModeManager
import com.tencent.kuikly.compose.ui.modifier.ModifierLocalManager
import com.tencent.kuikly.compose.ui.platform.KuiklySoftwareKeyboardController
import com.tencent.kuikly.compose.ui.platform.ViewConfiguration
import com.tencent.kuikly.compose.ui.unit.Constraints
import com.tencent.kuikly.compose.ui.unit.Density
import com.tencent.kuikly.compose.ui.unit.LayoutDirection
import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.views.DivView
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KNodeDrawInvalidationTest {

    @Test
    fun reusePropagatesDirtyChildInvalidationToItsNewParent() {
        val root = KNode<DeclarativeBaseView<*, *>>(DivView())
        val parent = KNode(DivView())
        val child = KNode(DivView())
        root.insertAt(0, parent)
        parent.insertAt(0, child)
        val owner = TestOwner(root)
        root.attach(owner)
        root.clearDrawInvalidationForTest()

        // Both KNodes start dirty. Normal invalidation coalesces at the child and therefore cannot
        // wake a clean ancestor after this subtree crosses a reuse boundary.
        child.invalidateDraw()
        assertFalse(root.isDrawInvalidatedForTest())

        child.onReuse()
        assertTrue(root.isDrawInvalidatedForTest())
    }

    private class TestOwner(
        override val root: KNode<DeclarativeBaseView<*, *>>
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
