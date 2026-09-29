/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2025 THL A29 Limited, a Tencent company. All rights reserved.
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

package com.tencent.kuikly.compose.ui.input.pointer

import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.node.ModifierNodeElement
import com.tencent.kuikly.compose.ui.node.NativeDispatchPolicy
import com.tencent.kuikly.compose.ui.node.PointerInputModifierNode
import com.tencent.kuikly.compose.ui.unit.IntSize

/**
 * Marks this pointer region as an explicit native-dispatch capture boundary.
 *
 * Use this only for overlay/barrier surfaces that must prevent Android native
 * child views underneath the Compose root from receiving the same MotionEvent.
 */
fun Modifier.nativeDispatchCapture(): Modifier = this.then(NativeDispatchCaptureElement)

/**
 * Marks this pointer region as a native-dispatch release boundary: native
 * views in this region keep receiving MotionEvents even when a hit-path
 * ancestor (for example an overlay barrier) captures native dispatch.
 *
 * Branch-scoped by design: the release only neutralizes capture nodes that
 * are this region's own hit-path ancestors. Touches that hit a capturing
 * surface without passing through this region stay captured, so overlay
 * barriers keep blocking click-through everywhere else.
 */
internal fun Modifier.nativeDispatchRelease(): Modifier = this.then(NativeDispatchReleaseElement)

/**
 * Internal marker: a [PointerInputModifierNode] implementing this releases
 * native dispatch for its hit branch. Kept internal so the framework's public
 * ABI stays at [PointerInputModifierNode.captureNativeDispatch].
 */
internal interface NativeDispatchReleasingNode

/**
 * Internal stance resolution for one pointer-input modifier node: the release
 * marker wins, then the public capture contract, else no stance.
 */
internal fun PointerInputModifierNode.resolvedNativeDispatchPolicy(): NativeDispatchPolicy = when {
    this is NativeDispatchReleasingNode -> NativeDispatchPolicy.RELEASE
    captureNativeDispatch() -> NativeDispatchPolicy.CAPTURE
    else -> NativeDispatchPolicy.INHERIT
}

private object NativeDispatchCaptureElement : ModifierNodeElement<NativeDispatchCaptureNode>() {
    override fun create(): NativeDispatchCaptureNode = NativeDispatchCaptureNode()

    override fun update(node: NativeDispatchCaptureNode) = Unit

    override fun hashCode(): Int = NativeDispatchCaptureElement::class.hashCode()

    override fun equals(other: Any?): Boolean = other === this
}

internal class NativeDispatchCaptureNode : Modifier.Node(), PointerInputModifierNode {
    override fun captureNativeDispatch(): Boolean = true

    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize
    ) = Unit

    override fun onCancelPointerInput() = Unit
}

private object NativeDispatchReleaseElement : ModifierNodeElement<NativeDispatchReleaseNode>() {
    override fun create(): NativeDispatchReleaseNode = NativeDispatchReleaseNode()

    override fun update(node: NativeDispatchReleaseNode) = Unit

    override fun hashCode(): Int = NativeDispatchReleaseElement::class.hashCode()

    override fun equals(other: Any?): Boolean = other === this
}

internal class NativeDispatchReleaseNode :
    Modifier.Node(), PointerInputModifierNode, NativeDispatchReleasingNode {

    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize
    ) = Unit

    override fun onCancelPointerInput() = Unit
}

/**
 * A node in the native-dispatch policy tree. HitPathTracker's real hit tree
 * implements this so the SAME traversal below runs in production and in the
 * policy-tree tests (no mirrored re-implementation).
 */
internal interface NativeDispatchPolicyTreeNode {
    /** This node's own stance from its pointer-input modifiers. */
    val ownNativeDispatchStance: NativeDispatchPolicy

    /** Iterates this node's hit children. */
    fun forEachPolicyChild(action: (NativeDispatchPolicyTreeNode) -> Unit)
}

/**
 * The production resolution: the ancestor stance flows top-down per branch
 * BEFORE sibling reduction. Each root-to-leaf path resolves to its deepest
 * non-INHERIT stance (leaves return the effective path stance), then sibling
 * branches combine with any-capture-wins — so a RELEASE only neutralizes
 * capture ancestors on its own path, and a shared CAPTURE ancestor keeps
 * capturing for every branch that does not release it itself.
 */
internal fun resolveNativeDispatchPolicyTree(
    node: NativeDispatchPolicyTreeNode,
    inherited: NativeDispatchPolicy,
): NativeDispatchPolicy {
    val effective = combineChainNativeDispatchPolicies(
        deeper = node.ownNativeDispatchStance,
        own = inherited,
    )
    var hasChildren = false
    var combined = NativeDispatchPolicy.INHERIT
    node.forEachPolicyChild { child ->
        hasChildren = true
        combined = combineSiblingNativeDispatchPolicies(
            combined,
            resolveNativeDispatchPolicyTree(child, effective)
        )
    }
    return if (hasChildren) combined else effective
}

/**
 * Combines two independent sibling hit branches: any capturing branch keeps
 * the root capturing; a release on one branch never neutralizes a capture on
 * another branch.
 */
internal fun combineSiblingNativeDispatchPolicies(
    left: NativeDispatchPolicy,
    right: NativeDispatchPolicy,
): NativeDispatchPolicy = when {
    left == NativeDispatchPolicy.CAPTURE || right == NativeDispatchPolicy.CAPTURE ->
        NativeDispatchPolicy.CAPTURE
    left == NativeDispatchPolicy.RELEASE || right == NativeDispatchPolicy.RELEASE ->
        NativeDispatchPolicy.RELEASE
    else -> NativeDispatchPolicy.INHERIT
}

/**
 * Combines stances along one hit branch: the deeper stance wins, so a RELEASE
 * overrides its capture ancestors (and a deeper CAPTURE symmetrically
 * overrides a release ancestor).
 */
internal fun combineChainNativeDispatchPolicies(
    deeper: NativeDispatchPolicy,
    own: NativeDispatchPolicy,
): NativeDispatchPolicy =
    if (deeper != NativeDispatchPolicy.INHERIT) deeper else own

/**
 * Combines stances declared by multiple pointer-input modifiers on the same
 * layout node: RELEASE dominates because it is the more specific opt-out.
 */
internal fun combineSameNodeNativeDispatchPolicies(
    left: NativeDispatchPolicy,
    right: NativeDispatchPolicy,
): NativeDispatchPolicy = when {
    left == NativeDispatchPolicy.RELEASE || right == NativeDispatchPolicy.RELEASE ->
        NativeDispatchPolicy.RELEASE
    left == NativeDispatchPolicy.CAPTURE || right == NativeDispatchPolicy.CAPTURE ->
        NativeDispatchPolicy.CAPTURE
    else -> NativeDispatchPolicy.INHERIT
}
