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

package com.tencent.kuikly.compose.views

import com.tencent.kuikly.compose.ui.unit.IntOffset
import com.tencent.kuikly.compose.extension.toIntRect
import com.tencent.kuikly.compose.gestures.KuiklyScrollInfo
import com.tencent.kuikly.core.base.domChildren
import com.tencent.kuikly.core.layout.Frame
import com.tencent.kuikly.core.views.KRNestedScrollMode
import com.tencent.kuikly.core.views.ScrollerAttr
import com.tencent.kuikly.core.views.ScrollerAttr.Companion.NESTED_SCROLL
import com.tencent.kuikly.core.views.ScrollerEvent
import com.tencent.kuikly.core.views.ScrollerView
import com.tencent.kuikly.core.views.ScrollOffsetCommitToken
import com.tencent.kuikly.core.views.ScrollWriteOperationKey
import com.tencent.kuikly.core.views.ScrollWriteResourceCell
import com.tencent.kuikly.core.views.ScrollWriteResult
import com.tencent.kuikly.core.views.ScrollWriteResultCode
import com.tencent.kuikly.core.views.finalizeOwnedScrollWriteResources
import kotlin.math.max

internal val KuiklyInfoKey = "KuiklyInfoKey"

internal fun shouldShiftComposeChildren(composeOffset: Int, targetOffset: Int): Boolean =
    composeOffset != targetOffset

internal data class ChildFrameMutation(
    val cell: ScrollWriteResourceCell<Frame>,
    val operation: ScrollWriteOperationKey,
    val provisionalRevision: Long,
    val baseCommittedRevision: Long,
    val targetFrame: Frame,
    val currentFrame: () -> Frame,
    val applyFrame: (Frame) -> Unit,
    var physicalProvisionalFrame: Frame? = null,
) {
    fun apply() {
        applyFrame(targetFrame)
        physicalProvisionalFrame = targetFrame
    }

    fun rollback(): Boolean {
        val current = currentFrame()
        val physicalFrame = physicalProvisionalFrame
        if (physicalFrame != null && current != physicalFrame) {
            return isCoveredBySuccessor(current)
        }
        val rolledBack = cell.rollback(operation, provisionalRevision)
        if (rolledBack && physicalFrame != null) {
            cell.committedSnapshot().first.let(applyFrame)
            return true
        }
        if (rolledBack || physicalFrame == null) return true
        return isCoveredBySuccessor(current)
    }

    private fun isCoveredBySuccessor(current: Frame): Boolean {
        val snapshot = cell.snapshot()
        val successorIsProvisional = snapshot.provisionalWriter != null &&
            snapshot.provisionalWriter != operation
        val successorWasFinalized = snapshot.provisionalWriter == null &&
            snapshot.revision > baseCommittedRevision
        return (successorIsProvisional || successorWasFinalized) && current == snapshot.value
    }
}

internal fun ScrollerView<ScrollerAttr, ScrollerEvent>.calNewOffset(curOffset: IntOffset, delta: Int, kuiklyInfo: KuiklyScrollInfo): IntOffset {
    // 注意不能够越界
    val newOffset = if (kuiklyInfo.isVertical()) {
        IntOffset(curOffset.x, curOffset.y + delta)
    } else {
        IntOffset(curOffset.x + delta, curOffset.y)
    }
    return newOffset
}

/** Low-level mutation used only after the shared scroll-offset write permit succeeds. */
internal fun ScrollerView<ScrollerAttr, ScrollerEvent>.applyOffsetDelta(
    delta: Int,
    kuiklyInfo: KuiklyScrollInfo,
    writeToken: ScrollOffsetCommitToken,
    isStillCurrent: () -> Boolean,
    onCommitResult: (ScrollWriteResult, IntOffset) -> Unit,
) {
    val density = kuiklyInfo.getDensity()

    val curOffset = IntOffset(
        (curOffsetX * density).toInt(),
        (curOffsetY * density).toInt()
    )
    val newOffset = calNewOffset(curOffset, delta, kuiklyInfo)
    val newOriOffset = if (kuiklyInfo.isVertical()) newOffset.y else newOffset.x
    val operation = ScrollWriteOperationKey(
        semanticOperationId = writeToken.semanticOperationId,
        attemptGeneration = writeToken.attemptGeneration,
    )

    kuiklyInfo.installIgnoreScrollOffset(operation, newOffset)

    // 避免嵌套滚动的影响
    val originNestSetting = getViewAttr().getProp(NESTED_SCROLL)
    if (originNestSetting != null) {
        getViewAttr().run {
            nestedScroll(KRNestedScrollMode.SELF_ONLY, KRNestedScrollMode.SELF_ONLY)
        }
    }
    var nestedScrollPolicyRestored = false

    fun restoreNestedScrollPolicy() {
        if (nestedScrollPolicyRestored) return
        nestedScrollPolicyRestored = true
        originNestSetting?.let { getViewAttr().setProp(NESTED_SCROLL, it) }
    }

    fun complete(nativeResult: ScrollWriteResult) {
        var terminalResult = nativeResult
        var shouldCommit = nativeResult.committed && isStillCurrent()
        if (shouldCommit && shouldShiftComposeChildren(kuiklyInfo.composeOffset.toInt(), newOriOffset)) {
            val mutations = mutableListOf<ChildFrameMutation>()
            contentView?.domChildren()?.forEach { subview ->
                if (!isStillCurrent()) {
                    shouldCommit = false
                    return@forEach
                }
                val renderView = subview.renderView ?: return@forEach
                val curRect = renderView.currentFrame.toIntRect(density)
                val newChildOffset = calNewOffset(
                    IntOffset(curRect.left, curRect.top),
                    delta,
                    kuiklyInfo,
                )
                val oldFrame = renderView.currentFrame
                val newFrame = Frame(
                    newChildOffset.x / density,
                    newChildOffset.y / density,
                    curRect.width / density,
                    curRect.height / density,
                )
                val applyFrame = { frame: Frame -> subview.setFrameToRenderView(frame) }
                val cell = kuiklyInfo.childFrameWriteCell(
                    resource = renderView,
                    currentFrame = oldFrame,
                    currentFrameProvider = { renderView.currentFrame },
                    applyFrame = applyFrame,
                )
                val snapshot = cell.snapshot()
                val dependency = snapshot.provisionalWriter
                val provisionalRevision = if (dependency == null) {
                    cell.begin(operation, snapshot.revision, newFrame)
                } else {
                    cell.inherit(
                        dependency = dependency,
                        operation = operation,
                        expectedProvisionalRevision = snapshot.provisionalRevision,
                        value = newFrame,
                    )
                }
                if (provisionalRevision == null) {
                    shouldCommit = false
                    return@forEach
                }
                mutations += ChildFrameMutation(
                    cell = cell,
                    operation = operation,
                    provisionalRevision = provisionalRevision,
                    baseCommittedRevision = snapshot.revision,
                    targetFrame = newFrame,
                    currentFrame = { renderView.currentFrame },
                    applyFrame = applyFrame,
                    physicalProvisionalFrame = snapshot.value.takeIf {
                        dependency != null && renderView.currentFrame == it
                    },
                )
            }
            if (shouldCommit) {
                for (mutation in mutations) {
                    if (!isStillCurrent()) {
                        shouldCommit = false
                        break
                    }
                    mutation.apply()
                    if (!isStillCurrent()) {
                        shouldCommit = false
                        break
                    }
                }
                if (shouldCommit) {
                    shouldCommit = finalizeOwnedScrollWriteResources(
                        mutations.map {
                            it.cell.claim(it.operation, it.provisionalRevision)
                        },
                    )
                    if (!shouldCommit && isStillCurrent()) {
                        terminalResult = terminalResult.copy(code = ScrollWriteResultCode.RollbackFailed)
                    }
                }
            }
            if (!shouldCommit) {
                val rollbackFailed = mutations.asReversed().any { !it.rollback() }
                if (rollbackFailed && isStillCurrent()) {
                    terminalResult = terminalResult.copy(code = ScrollWriteResultCode.RollbackFailed)
                }
            }
        }
        if (!shouldCommit) {
            kuiklyInfo.clearIgnoreScrollOffset(operation)
        }
        if (!shouldCommit && terminalResult.committed) {
            terminalResult = terminalResult.copy(code = ScrollWriteResultCode.Stale)
        }
        restoreNestedScrollPolicy()
        onCommitResult(terminalResult, newOffset)
    }
    // 更新offset
    if (contentView?.getPager()?.pageData?.isAndroid == true) {
        // 安卓有个bug，刚好滚到最末尾的时候，是不成功的，临时处理下
        setContentOffset(
            max(0f, newOffset.x / density - 0.01f),
            max(0f, newOffset.y / density - 0.01f),
            writeToken = writeToken,
            onCommitResultDetailed = ::complete,
        )
    } else {
        setContentOffset(
            newOffset.x / density,
            newOffset.y / density,
            writeToken = writeToken,
            onCommitResultDetailed = ::complete,
        )
    }

    // Async native writes restore immediately; synchronous terminals already restored before callback.
    restoreNestedScrollPolicy()

}
