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

package com.tencent.kuikly.compose.extension

import com.tencent.kuikly.compose.ui.semantics.Role
import com.tencent.kuikly.core.base.attr.AccessibilityRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NativeSemanticsNodeRegistryTest {
    private data class ProjectionNode(
        val name: String,
        val hidden: Boolean = false,
        val parent: ProjectionNode? = null
    )

    private data class LayoutProjectionNode(
        val hidden: Boolean = false,
        val parent: LayoutProjectionNode? = null
    )

    private data class SemanticProjectionNode(
        val name: String,
        val layoutNode: LayoutProjectionNode,
        val semanticParent: SemanticProjectionNode? = null
    )

    @Test
    fun invisibleNodeExcludesItsNativeSubtree() {
        assertEquals(
            AccessibilityRole.HIDDEN,
            resolveNativeAccessibilityRole(isInvisibleToUser = true, hasAccessibilityText = false, role = null)
        )
    }

    @Test
    fun visibleEmptyContainerRestoresDescendantTraversal() {
        assertEquals(
            AccessibilityRole.NONE,
            resolveNativeAccessibilityRole(isInvisibleToUser = false, hasAccessibilityText = false, role = null)
        )
    }

    @Test
    fun visibleButtonKeepsItsNativeRole() {
        assertEquals(
            AccessibilityRole.BUTTON,
            resolveNativeAccessibilityRole(isInvisibleToUser = false, hasAccessibilityText = true, role = Role.Button)
        )
    }

    @Test
    fun hiddenAncestorProjectsToEveryFlattenedNativeDescendant() {
        val hiddenRoot = ProjectionNode(name = "content", hidden = true)
        val navigation = ProjectionNode(name = "navigation", parent = hiddenRoot)
        val search = ProjectionNode(name = "search", parent = navigation)
        val drawer = ProjectionNode(name = "drawer")

        val hiddenNodes = effectivelyHiddenNodes(
            nodes = listOf(hiddenRoot, navigation, search, drawer),
            isHidden = ProjectionNode::hidden,
            parentOf = ProjectionNode::parent
        )

        assertEquals(setOf(hiddenRoot, navigation, search), hiddenNodes)
    }

    @Test
    fun clearingHiddenAncestorRestoresTheProjectedSubtree() {
        val visibleRoot = ProjectionNode(name = "content")
        val search = ProjectionNode(name = "search", parent = visibleRoot)

        val hiddenNodes = effectivelyHiddenNodes(
            nodes = listOf(visibleRoot, search),
            isHidden = ProjectionNode::hidden,
            parentOf = ProjectionNode::parent
        )

        assertTrue(hiddenNodes.isEmpty())
    }

    @Test
    fun layoutAncestryProjectsHiddenAcrossDisconnectedSemanticBranches() {
        val hiddenLayoutRoot = LayoutProjectionNode(hidden = true)
        val contentLayout = LayoutProjectionNode(parent = hiddenLayoutRoot)
        val searchLayout = LayoutProjectionNode(parent = contentLayout)
        val drawerLayout = LayoutProjectionNode()
        val hiddenRoot = SemanticProjectionNode(name = "content", layoutNode = hiddenLayoutRoot)
        val search = SemanticProjectionNode(
            name = "search",
            layoutNode = searchLayout,
            semanticParent = null
        )
        val drawer = SemanticProjectionNode(name = "drawer", layoutNode = drawerLayout)

        val hiddenNodes = effectivelyHiddenNodes(
            nodes = listOf(hiddenRoot, search, drawer),
            firstAncestor = SemanticProjectionNode::layoutNode,
            isHidden = LayoutProjectionNode::hidden,
            parentOf = LayoutProjectionNode::parent
        )

        assertEquals(setOf(hiddenRoot, search), hiddenNodes)
    }

    @Test
    fun reconcileReturnsOnlyNodesRemovedFromCurrentGeneration() {
        val registry = NativeSemanticsNodeRegistry<Any>()
        val retained = Any()
        val removed = Any()

        assertTrue(registry.reconcile(mapOf(1 to retained, 2 to removed)).isEmpty())

        val removedNodes = registry.reconcile(mapOf(1 to retained))

        assertEquals(1, removedNodes.size)
        assertSame(removed, removedNodes.single())
    }

    @Test
    fun reconcileTreatsReusedIdWithNewNodeAsReplacement() {
        val registry = NativeSemanticsNodeRegistry<Any>()
        val previous = Any()
        val replacement = Any()
        registry.reconcile(mapOf(1 to previous))

        val removedNodes = registry.reconcile(mapOf(1 to replacement))

        assertEquals(1, removedNodes.size)
        assertSame(previous, removedNodes.single())
        assertTrue(registry.reconcile(mapOf(1 to replacement)).isEmpty())
    }

    @Test
    fun removedIdCanBeAddedAgainAsAFirstGenerationNode() {
        val registry = NativeSemanticsNodeRegistry<Any>()
        val node = Any()
        registry.reconcile(mapOf(1 to node))
        assertEquals(listOf(node), registry.reconcile(emptyMap()))

        assertTrue(registry.reconcile(mapOf(1 to node)).isEmpty())
    }

    @Test
    fun clearReturnsRetainedNodesAndEmptiesRegistry() {
        val registry = NativeSemanticsNodeRegistry<Any>()
        val first = Any()
        val second = Any()
        registry.reconcile(mapOf(1 to first, 2 to second))

        assertEquals(listOf(first, second), registry.clear())
        assertTrue(registry.clear().isEmpty())
        assertTrue(registry.reconcile(mapOf(1 to first)).isEmpty())
    }

    @Test
    fun removeForgetsOnlyDetachedNode() {
        val registry = NativeSemanticsNodeRegistry<Any>()
        val detached = Any()
        val retained = Any()
        registry.reconcile(mapOf(1 to detached, 2 to retained))

        assertSame(detached, registry.remove(1))
        assertEquals(listOf(retained), registry.clear())
    }
}
