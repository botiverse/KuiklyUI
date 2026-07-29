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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NativeSemanticsNodeRegistryTest {
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
