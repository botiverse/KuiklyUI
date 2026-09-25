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

import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.drawscope.CanvasDrawScope
import com.tencent.kuikly.compose.ui.graphics.drawscope.ContentDrawScope
import com.tencent.kuikly.compose.ui.graphics.drawscope.DrawScope
import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.views.DivView
import kotlin.test.Test
import kotlin.test.assertEquals

class DrawModifierNodeViewDispatchTest {

    @Test
    fun ordinaryOverrideHasEquivalentOrdinaryAndViewAwareDispatch() {
        var ordinaryDraws = 0
        val node =
            object : Modifier.Node(), DrawModifierNode {
                override fun ContentDrawScope.draw() {
                    ordinaryDraws += 1
                }
            }

        with(RecordingContentDrawScope()) {
            with(node) {
                draw()
                draw(DivView())
            }
        }

        assertEquals(2, ordinaryDraws)
    }

    @Test
    fun bareNodePreservesContentInViewAwareDispatch() {
        val node = object : Modifier.Node(), DrawModifierNode {}
        val drawScope = RecordingContentDrawScope()

        with(drawScope) {
            with(node) {
                draw(DivView())
            }
        }

        assertEquals(1, drawScope.contentDraws)
    }

    @Test
    fun explicitViewAwareOverrideStillTakesPrecedence() {
        var ordinaryDraws = 0
        var viewAwareDraws = 0
        val node =
            object : Modifier.Node(), DrawModifierNode {
                override fun ContentDrawScope.draw() {
                    ordinaryDraws += 1
                }

                override fun ContentDrawScope.draw(view: DeclarativeBaseView<*, *>?) {
                    viewAwareDraws += 1
                }
            }

        with(RecordingContentDrawScope()) {
            with(node) {
                draw(DivView())
            }
        }

        assertEquals(0, ordinaryDraws)
        assertEquals(1, viewAwareDraws)
    }

    private class RecordingContentDrawScope :
        ContentDrawScope,
        DrawScope by CanvasDrawScope() {
        var contentDraws = 0

        override fun drawContent() {
            contentDraws += 1
        }
    }
}
