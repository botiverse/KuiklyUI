/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI.
 */

package com.tencent.kuikly.compose.gestures

import com.tencent.kuikly.core.base.RenderView
import com.tencent.kuikly.core.manager.BridgeManager
import com.tencent.kuikly.core.manager.NativeMethod
import com.tencent.kuikly.core.nvi.NativeBridge
import com.tencent.kuikly.core.views.ScrollerAttr
import com.tencent.kuikly.core.views.ScrollerEvent
import com.tencent.kuikly.core.views.ScrollerView
import kotlin.test.Test
import kotlin.test.assertEquals

class KuiklyScrollInfoBindingPrepareTest {

    @Test
    fun bindPublishesNewOwnerBeforeSynchronousNativePrepareTerminal() {
        val fixture = BindingPrepareFixture("bind")

        fixture.info.bindScrollView(fixture.newView)

        assertEquals(0, fixture.fallbackCount)
    }

    @Test
    fun detachPublishesNoOwnerBeforeSynchronousNativePrepareTerminal() {
        val fixture = BindingPrepareFixture("detach")

        fixture.info.detachScrollView(fixture.oldView, invalidateNativeWrites = true)

        assertEquals(0, fixture.fallbackCount)
    }

    @Test
    fun boundReusePublishesNewGenerationBeforeSynchronousNativePrepareTerminal() {
        val fixture = BindingPrepareFixture("restore")

        fixture.info.prepareBoundScrollViewForComposeReuse(fixture.oldView)

        assertEquals(0, fixture.fallbackCount)
    }

    private class BindingPrepareFixture(suffix: String) {
        val info = KuiklyScrollInfo()
        val oldView = ScrollerView<ScrollerAttr, ScrollerEvent>()
        val newView = ScrollerView<ScrollerAttr, ScrollerEvent>()
        var fallbackCount = 0
        private var pendingCompletion: ((Boolean) -> Unit)? = null
        private val pagerId = "binding-prepare-$suffix-${oldView.nativeRef}"

        init {
            val bridge = NativeBridge().also { nativeBridge ->
                nativeBridge.delegate = object : NativeBridge.NativeBridgeDelegate {
                    override fun callNative(
                        methodId: Int,
                        arg0: Any?,
                        arg1: Any?,
                        arg2: Any?,
                        arg3: Any?,
                        arg4: Any?,
                        arg5: Any?,
                    ): Any? {
                        if (methodId == NativeMethod.CALL_VIEW_METHOD &&
                            arg2 == "prepareForComposeReuse"
                        ) {
                            pendingCompletion?.invoke(false)
                        }
                        return null
                    }
                }
            }
            BridgeManager.registerNativeBridge(pagerId, bridge)
            oldView.pagerId = pagerId
            oldView.renderView = RenderView(pagerId, oldView.nativeRef, "KRScrollView")
            info.bindScrollView(oldView)
            info.enqueueHostEmergencySourceEvent(
                correction = { complete ->
                    pendingCompletion = complete
                    true
                },
                applyNormalPath = {
                    if (info.captureScrollOffsetOwnerToken()?.scrollView === oldView) {
                        fallbackCount += 1
                    }
                },
            )
        }
    }
}
