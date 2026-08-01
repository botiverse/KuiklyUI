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

package com.tencent.kuikly.demo.pages.demo.kit_demo.DeclarativeDemo

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.ScrollerView
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.views.compose.Button
import com.tencent.kuikly.demo.pages.base.BasePager
import com.tencent.kuikly.demo.pages.demo.base.NavBar

/**
 * Manual cross-platform scaffold for the horizontal contentInset contract.
 *
 * This page intentionally lives only on task-specific validation branches. It
 * makes a leading inset visually measurable and exercises the drag-end path in
 * which the OHOS renderer resolves an overscrolled offset before applying its
 * physical content margin.
 */
@Page("HorizontalContentInsetReproPage")
internal class HorizontalContentInsetReproPage : BasePager() {

    private var scrollerRef: ViewRef<ScrollerView<*, *>>? = null
    private var offsetX by observable(0f)
    private var lastDragEndX by observable(0f)
    private var leadingInset by observable(0f)
    private var trailingInset by observable(0f)
    private var armed by observable(false)
    private var phase by observable("RESET: red CONTENT X=0 stripe must align with viewport x=0")
    private var appliedOnLastDrag = false

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                backgroundColor(Color(0xFFF4F6F8))
                flexDirectionColumn()
            }

            NavBar {
                attr { title = "Horizontal contentInset A/B" }
            }

            Text {
                attr {
                    margin(left = 20f, top = 12f, right = 20f)
                    color(Color(0xFF222222))
                    fontSize(14f)
                    lines(3)
                    text(
                        "1 RESET  2 ARM LEFT 80  3 pull the content RIGHT past 80 and release. " +
                            "Expected: one 80px navy gap; the red content stripe rests at ruler x=80."
                    )
                }
            }

            View {
                attr {
                    margin(left = 20f, top = 10f, right = 20f)
                    padding(all = 10f)
                    backgroundColor(Color(0xFF101820))
                    borderRadius(6f)
                }
                Text {
                    attr {
                        color(Color.WHITE)
                        fontSize(13f)
                        lines(3)
                        text(
                            "phase=${ctx.phase}\n" +
                                "offsetX=${ctx.offsetX.toInt()}  dragEndX=${ctx.lastDragEndX.toInt()}  " +
                                "left=${ctx.leadingInset.toInt()}  right=${ctx.trailingInset.toInt()}"
                        )
                    }
                }
            }

            View {
                attr {
                    marginTop(12f)
                    alignSelfCenter()
                    size(width = VIEWPORT_WIDTH, height = 28f)
                    flexDirectionRow()
                }
                repeat(4) { index ->
                    View {
                        attr {
                            size(width = RULER_STEP, height = 28f)
                            backgroundColor(
                                if (index % 2 == 0) Color(0xFFD6E4FF) else Color(0xFFA8C7FA)
                            )
                            border(
                                Border(
                                    lineWidth = 1f,
                                    color = Color(0xFF315A8A),
                                    lineStyle = BorderStyle.SOLID
                                )
                            )
                            justifyContentCenter()
                            alignItemsCenter()
                        }
                        Text {
                            attr {
                                color(Color(0xFF102A43))
                                fontSize(11f)
                                text("${index * RULER_STEP.toInt()}-${(index + 1) * RULER_STEP.toInt()}")
                            }
                        }
                    }
                }
            }

            View {
                attr {
                    alignSelfCenter()
                    size(width = VIEWPORT_WIDTH + 4f, height = VIEWPORT_HEIGHT + 4f)
                    padding(all = 2f)
                    backgroundColor(Color(0xFF315A8A))
                }
                Scroller {
                    ref { ctx.scrollerRef = it }
                    attr {
                        size(width = VIEWPORT_WIDTH, height = VIEWPORT_HEIGHT)
                        flexDirectionRow()
                        bouncesEnable(true)
                        showScrollerIndicator(true)
                        backgroundColor(Color(0xFF17324D))
                    }
                    event {
                        scroll {
                            ctx.offsetX = it.offsetX
                        }
                        dragBegin {
                            ctx.appliedOnLastDrag = false
                            ctx.phase = if (ctx.armed) {
                                "DRAGGING: keep pulling right until offsetX < -80"
                            } else {
                                "DRAGGING (not armed)"
                            }
                        }
                        willDragEndBySync {
                            // Keep the reproducing call on the native drag-end boundary, before
                            // the platform starts its leading-edge rebound. An asynchronous
                            // dragEnd callback can observe the old negative offset but arrive
                            // after the native offset has already moved back toward zero.
                            if (ctx.armed && it.offsetX < -LEADING_INSET) {
                                ctx.appliedOnLastDrag = true
                                ctx.scrollerRef?.view?.setContentInset(
                                    left = LEADING_INSET,
                                    right = ctx.trailingInset,
                                    animated = true
                                )
                            }
                        }
                        dragEnd {
                            ctx.lastDragEndX = it.offsetX
                            if (ctx.appliedOnLastDrag) {
                                ctx.armed = false
                                ctx.leadingInset = LEADING_INSET
                                ctx.phase = "APPLY left=80 animated at dragEndX=${it.offsetX.toInt()}"
                            } else if (ctx.armed) {
                                ctx.phase = "MISS: dragEndX=${it.offsetX.toInt()}; pull past -80 and retry"
                            } else {
                                ctx.phase = "dragEndX=${it.offsetX.toInt()} (not armed)"
                            }
                        }
                        scrollEnd {
                            ctx.offsetX = it.offsetX
                        }
                    }

                    repeat(6) { index ->
                        View {
                            attr {
                                size(width = 160f, height = VIEWPORT_HEIGHT)
                                backgroundColor(
                                    if (index % 2 == 0) Color(0xFFFFE08A) else Color(0xFF98D8C8)
                                )
                                justifyContentCenter()
                                alignItemsCenter()
                            }
                            if (index == 0) {
                                View {
                                    attr {
                                        absolutePosition(left = 0f, top = 0f, bottom = 0f)
                                        width(8f)
                                        backgroundColor(Color(0xFFE11D48))
                                    }
                                }
                            }
                            Text {
                                attr {
                                    color(Color(0xFF172B4D))
                                    fontSize(14f)
                                    fontWeight700()
                                    text(if (index == 0) "CONTENT X=0" else "ITEM $index")
                                }
                            }
                        }
                    }
                }
            }

            View {
                attr {
                    margin(left = 20f, top = 14f, right = 20f)
                    flexDirectionRow()
                    justifyContentSpaceBetween()
                }
                Button {
                    attr {
                        size(width = 150f, height = 42f)
                        backgroundColor(Color(0xFF5C677D))
                        borderRadius(6f)
                        titleAttr {
                            text("RESET")
                            color(Color.WHITE)
                            fontSize(13f)
                        }
                    }
                    event {
                        click { ctx.resetScroller() }
                    }
                }
                Button {
                    attr {
                        size(width = 150f, height = 42f)
                        backgroundColor(Color(0xFF006D77))
                        borderRadius(6f)
                        titleAttr {
                            text("ARM LEFT 80")
                            color(Color.WHITE)
                            fontSize(13f)
                        }
                    }
                    event {
                        click { ctx.armLeadingInset() }
                    }
                }
            }

            View {
                attr {
                    margin(left = 20f, top = 10f, right = 20f)
                    flexDirectionRow()
                    justifyContentSpaceBetween()
                }
                Button {
                    attr {
                        size(width = 150f, height = 42f)
                        backgroundColor(Color(0xFF7B2CBF))
                        borderRadius(6f)
                        titleAttr {
                            text("FORCE -120 / LEFT 80")
                            color(Color.WHITE)
                            fontSize(11f)
                        }
                    }
                    event {
                        click { ctx.forceLeadingSequence() }
                    }
                }
                Button {
                    attr {
                        size(width = 150f, height = 42f)
                        backgroundColor(Color(0xFFB45309))
                        borderRadius(6f)
                        titleAttr {
                            text("RIGHT +60")
                            color(Color.WHITE)
                            fontSize(13f)
                        }
                    }
                    event {
                        click { ctx.applyTrailingInset() }
                    }
                }
            }
        }
    }

    private fun resetScroller() {
        armed = false
        leadingInset = 0f
        trailingInset = 0f
        lastDragEndX = 0f
        appliedOnLastDrag = false
        phase = "RESET: red CONTENT X=0 stripe must align with viewport x=0"
        scrollerRef?.view?.setContentInset(animated = false)
        scrollerRef?.view?.setContentOffset(0f, 0f, animated = false)
    }

    private fun armLeadingInset() {
        resetScroller()
        armed = true
        phase = "ARMED: pull content RIGHT until offsetX < -80, then release"
    }

    private fun forceLeadingSequence() {
        resetScroller()
        leadingInset = LEADING_INSET
        phase = "FORCE: set offsetX=-120, then animate left=80"
        scrollerRef?.view?.setContentOffset(-120f, 0f, animated = false)
        scrollerRef?.view?.setContentInset(left = LEADING_INSET, animated = true)
    }

    private fun applyTrailingInset() {
        trailingInset = TRAILING_INSET
        phase = "RIGHT=60: manually scroll to the trailing edge and verify one 60px gap"
        scrollerRef?.view?.setContentInset(
            left = leadingInset,
            right = TRAILING_INSET,
            animated = true
        )
    }

    private companion object {
        const val LEADING_INSET = 80f
        const val TRAILING_INSET = 60f
        const val VIEWPORT_WIDTH = 320f
        const val VIEWPORT_HEIGHT = 120f
        const val RULER_STEP = 80f
    }
}
