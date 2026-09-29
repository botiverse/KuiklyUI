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

package com.tencent.kuikly.compose.ui.input.pointer

import com.tencent.kuikly.compose.ui.node.NativeDispatchPolicy
import com.tencent.kuikly.compose.ui.node.NativeDispatchPolicy.CAPTURE
import com.tencent.kuikly.compose.ui.node.NativeDispatchPolicy.INHERIT
import com.tencent.kuikly.compose.ui.node.NativeDispatchPolicy.RELEASE
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The native-dispatch capture algebra used by HitPathTracker: the ancestor
 * stance flows top-down per branch BEFORE sibling reduction, each root-to-leaf
 * path resolves to its deepest non-INHERIT stance, and sibling branches
 * combine with any-capture-wins. These teeth pin the barrier-vs-selectable
 * region contract, including shared-prefix trees where a capture ancestor
 * fans out into release and non-release children.
 */
class NativeDispatchPolicyTest {

    /**
     * A test tree node running the PRODUCTION traversal
     * (resolveNativeDispatchPolicyTree) — the same function HitPathTracker's
     * real hit tree executes; nothing is re-implemented here.
     */
    private class PolicyNode(
        private val own: NativeDispatchPolicy,
        private val children: List<PolicyNode> = emptyList(),
    ) : NativeDispatchPolicyTreeNode {
        override val ownNativeDispatchStance: NativeDispatchPolicy get() = own

        override fun forEachPolicyChild(action: (NativeDispatchPolicyTreeNode) -> Unit) {
            children.forEach(action)
        }
    }

    private fun resolve(
        node: PolicyNode,
        inherited: NativeDispatchPolicy = INHERIT,
    ): NativeDispatchPolicy = resolveNativeDispatchPolicyTree(node, inherited)

    private fun rootCaptures(vararg branches: PolicyNode): Boolean =
        resolveNativeDispatchPolicyTree(
            PolicyNode(INHERIT, branches.toList()),
            INHERIT
        ) == CAPTURE

    @Test
    fun deeperReleaseOverridesItsOwnAncestorCapture() {
        // barrier -> plain node -> selectable text release leaf
        val chain = PolicyNode(CAPTURE, listOf(PolicyNode(INHERIT, listOf(PolicyNode(RELEASE)))))
        assertEquals(RELEASE, resolve(chain))
        assertEquals(false, rootCaptures(chain))
    }

    @Test
    fun sharedCaptureAncestorKeepsCapturingForItsNonReleaseChildBranch() {
        // The addHitPath shared-prefix shape: one CAPTURE ancestor fans out
        // into a RELEASE child branch and an INHERIT child branch. The release
        // must only neutralize its own path; the sibling path under the same
        // ancestor still resolves to CAPTURE, so the root captures.
        val tree = PolicyNode(
            CAPTURE,
            listOf(
                PolicyNode(RELEASE),
                PolicyNode(INHERIT),
            )
        )
        assertEquals(CAPTURE, resolve(tree))
        assertEquals(true, rootCaptures(tree))
    }

    @Test
    fun sharedCaptureAncestorWithAllBranchesReleasedDoesNotCapture() {
        val tree = PolicyNode(
            CAPTURE,
            listOf(
                PolicyNode(RELEASE),
                PolicyNode(INHERIT, listOf(PolicyNode(RELEASE))),
            )
        )
        assertEquals(RELEASE, resolve(tree))
        assertEquals(false, rootCaptures(tree))
    }

    @Test
    fun captureWithoutAnyReleaseStaysCaptured() {
        val chain = PolicyNode(INHERIT, listOf(PolicyNode(CAPTURE, listOf(PolicyNode(INHERIT)))))
        assertEquals(CAPTURE, resolve(chain))
        assertEquals(true, rootCaptures(chain))
    }

    @Test
    fun releaseOnOneBranchNeverNeutralizesCaptureOnAnIndependentSiblingBranch() {
        val overlayBranch = PolicyNode(CAPTURE)
        val releaseBranch = PolicyNode(RELEASE)
        assertEquals(true, rootCaptures(overlayBranch, releaseBranch))
        assertEquals(true, rootCaptures(releaseBranch, overlayBranch))
    }

    @Test
    fun releaseAloneDoesNotCapture() {
        assertEquals(false, rootCaptures(PolicyNode(INHERIT, listOf(PolicyNode(RELEASE)))))
        assertEquals(false, rootCaptures(PolicyNode(RELEASE)))
    }

    @Test
    fun deeperCaptureUnderAReleaseAncestorCapturesSymmetrically() {
        val chain = PolicyNode(RELEASE, listOf(PolicyNode(CAPTURE)))
        assertEquals(CAPTURE, resolve(chain))
        assertEquals(true, rootCaptures(chain))
    }

    @Test
    fun inheritOnlyTreeNeitherCapturesNorReleases() {
        val chain = PolicyNode(INHERIT, listOf(PolicyNode(INHERIT)))
        assertEquals(INHERIT, resolve(chain))
        assertEquals(false, rootCaptures(chain))
    }

    @Test
    fun realModifierNodesDriveTheResolvedPolicies() {
        // The actual production nodes, not stand-ins: the barrier node used by
        // Modifier.nativeDispatchCapture() and the release node installed by
        // SelectableText's Modifier.nativeDispatchRelease().
        assertEquals(CAPTURE, NativeDispatchCaptureNode().resolvedNativeDispatchPolicy())
        assertEquals(RELEASE, NativeDispatchReleaseNode().resolvedNativeDispatchPolicy())

        // Barrier ancestor with the SelectableText release region on its own
        // path: that path releases, so the root must not capture...
        val barrierOverText = PolicyNode(
            NativeDispatchCaptureNode().resolvedNativeDispatchPolicy(),
            listOf(PolicyNode(NativeDispatchReleaseNode().resolvedNativeDispatchPolicy()))
        )
        assertEquals(false, rootCaptures(barrierOverText))

        // ...while the same barrier ancestor fanning out into the release
        // region AND a sibling hit (shared prefix) keeps capturing for the
        // non-release branch.
        val barrierSharedPrefix = PolicyNode(
            NativeDispatchCaptureNode().resolvedNativeDispatchPolicy(),
            listOf(
                PolicyNode(NativeDispatchReleaseNode().resolvedNativeDispatchPolicy()),
                PolicyNode(INHERIT),
            )
        )
        assertEquals(true, rootCaptures(barrierSharedPrefix))
    }

    @Test
    fun sameNodeReleaseDominatesCapture() {
        assertEquals(
            RELEASE,
            combineSameNodeNativeDispatchPolicies(CAPTURE, RELEASE)
        )
        assertEquals(
            RELEASE,
            combineSameNodeNativeDispatchPolicies(RELEASE, CAPTURE)
        )
        assertEquals(
            CAPTURE,
            combineSameNodeNativeDispatchPolicies(INHERIT, CAPTURE)
        )
        assertEquals(
            INHERIT,
            combineSameNodeNativeDispatchPolicies(INHERIT, INHERIT)
        )
    }
}
