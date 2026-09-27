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

package com.tencent.kuikly.demo.pages.compose

import com.tencent.kuikly.compose.ComposeContainer
import com.tencent.kuikly.compose.extension.MakeKuiklyComposeNode
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.views.CanvasView

/**
 * Repro for the orphan canvas reset in the Compose interop path
 * (`KuiklyCanvas.view` setter sending a bare `reset`).
 *
 * The canvas below is a [CanvasView] hosted via [MakeKuiklyComposeNode] with its
 * own `drawCallback`: drawing commands are produced inside the view's own draw
 * pass (reset+refill managed by the view), not by the Compose traversal.
 *
 * Steps: open this page, press HOME, wait a second, then return to the app.
 * Before the fix the Compose traversal's bare `reset` wipes the queued ops and
 * the canvas comes back blank. With the fix the drawing persists.
 */
@Page("canvas_draw_callback_reset_demo")
class CanvasDrawCallbackResetDemo : ComposeContainer() {
    override fun willInit() {
        super.willInit()
        setContent {
            ComposeNavigationBar {
                Column(Modifier.fillMaxSize().background(Color.White)) {
                    Text(
                        text = "Repro: press HOME, wait a second, then return. " +
                            "Broken: the canvas below comes back blank. " +
                            "Fixed: the red frame, diagonal and block persist.",
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                    MakeKuiklyComposeNode<CanvasView>(
                        factory = { CanvasView() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(horizontal = 16.dp)
                            .background(Color(0xFFF5F5F5)),
                        viewInit = {
                            drawCallback = { context, width, height ->
                                // Red frame.
                                context.beginPath()
                                context.moveTo(4f, 4f)
                                context.lineTo(width - 4f, 4f)
                                context.lineTo(width - 4f, height - 4f)
                                context.lineTo(4f, height - 4f)
                                context.closePath()
                                context.strokeStyle(com.tencent.kuikly.core.base.Color.RED)
                                context.lineWidth(4f)
                                context.stroke()
                                // Gray diagonal.
                                context.beginPath()
                                context.moveTo(0f, 0f)
                                context.lineTo(width, height)
                                context.strokeStyle(com.tencent.kuikly.core.base.Color.GRAY)
                                context.lineWidth(2f)
                                context.stroke()
                                // Green block in the center.
                                val left = width / 2f - 30f
                                val top = height / 2f - 30f
                                context.beginPath()
                                context.moveTo(left, top)
                                context.lineTo(left + 60f, top)
                                context.lineTo(left + 60f, top + 60f)
                                context.lineTo(left, top + 60f)
                                context.closePath()
                                context.fillStyle(com.tencent.kuikly.core.base.Color.GREEN)
                                context.fill()
                            }
                        }
                    )
                }
            }
        }
    }
}
