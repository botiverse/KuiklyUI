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
import com.tencent.kuikly.compose.ui.node.PointerInputModifierNode
import com.tencent.kuikly.compose.ui.unit.IntSize

/**
 * Marks this pointer region as an explicit native-dispatch capture boundary.
 *
 * Use this only for overlay/barrier surfaces that must prevent Android native
 * child views underneath the Compose root from receiving the same MotionEvent.
 */
fun Modifier.nativeDispatchCapture(): Modifier = this.then(NativeDispatchCaptureElement)

private object NativeDispatchCaptureElement : ModifierNodeElement<NativeDispatchCaptureNode>() {
    override fun create(): NativeDispatchCaptureNode = NativeDispatchCaptureNode()

    override fun update(node: NativeDispatchCaptureNode) = Unit

    override fun hashCode(): Int = NativeDispatchCaptureElement::class.hashCode()

    override fun equals(other: Any?): Boolean = other === this
}

private class NativeDispatchCaptureNode : Modifier.Node(), PointerInputModifierNode {
    override fun captureNativeDispatch(): Boolean = true

    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize
    ) = Unit

    override fun onCancelPointerInput() = Unit
}
