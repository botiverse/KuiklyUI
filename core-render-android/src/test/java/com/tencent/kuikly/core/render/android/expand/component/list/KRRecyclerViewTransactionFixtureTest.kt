/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI.
 */

package com.tencent.kuikly.core.render.android.expand.component.list

import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import com.tencent.kuikly.core.manager.BridgeManager
import com.tencent.kuikly.core.manager.NativeMethod
import com.tencent.kuikly.core.manager.PagerManager
import com.tencent.kuikly.core.nvi.NativeBridge
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.views.ListAttr
import com.tencent.kuikly.core.views.ListEvent
import com.tencent.kuikly.core.views.ListView
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class KRRecyclerViewTransactionFixtureTest {

    @Test
    fun realRecyclerDefersPhysicalEndUntilBarrierThenAcksAfterEvent() {
        val recyclerView = KRRecyclerView(RuntimeEnvironment.getApplication())
        val order = mutableListOf<String>()
        val operation = NativeScrollWriteOperation(1L) { result ->
            val resultCode = (result as Map<*, *>)["resultCode"]
            order += "ack:$resultCode"
        }.apply {
            started = true
            primaryPending = false
            edgePending = true
        }
        recyclerView.nativeWriteArbiter().install(operation)
        recyclerView.setScrollEndCallback { order += "scrollEnd" }

        recyclerView.invokeFireEndScrollEvent()
        assertTrue(order.isEmpty())
        assertTrue(recyclerView.nativeWriteArbiter().isCurrent(operation))

        operation.edgePending = false
        recyclerView.invokeFireEndScrollEvent()

        assertEquals(listOf("scrollEnd", "ack:0"), order)
        assertFalse(recyclerView.nativeWriteArbiter().isCurrent(operation))
    }

    @Test
    fun realRecyclerSuppressesCancellationTimePhysicalEnd() {
        val recyclerView = KRRecyclerView(RuntimeEnvironment.getApplication())
        var endEvents = 0
        recyclerView.setScrollEndCallback { endEvents += 1 }
        recyclerView.setPrivateField("suppressPhysicalScrollEnd", true)

        recyclerView.invokeFireEndScrollEvent()

        assertEquals(0, endEvents)
    }

    @Test
    fun rejectedPreinstallWriteDoesNotAdvanceLatestComposeAuthority() {
        val recyclerView = KRRecyclerView(RuntimeEnvironment.getApplication())
        recyclerView.setPrivateField("latestComposeWriteOperation", 1L)
        var resultCode = -1

        recyclerView.call(
            "contentOffset",
            "0 40 1 300 1 0 0 0 0 2 -1 -1 0 -1 0 2 1 0 0 0 0 0",
        ) { result ->
            resultCode = (result as Map<*, *>)["resultCode"] as Int
        }

        assertEquals(NativeScrollWriteResultCode.NotReady.wireValue, resultCode)
        assertEquals(1L, recyclerView.privateField("latestComposeWriteOperation"))
    }

    @Test
    fun contentInsetBeforeAndroidContentAttachmentReturnsNotReadyWithoutClaimingAuthority() {
        val recyclerView = recyclerWithUnattachedContent()
        recyclerView.setPrivateField("latestComposeWriteOperation", 1L)
        var resultCode = -1

        recyclerView.call("contentInset", contentInsetWrite(operation = 2L)) { result ->
            resultCode = (result as Map<*, *>)["resultCode"] as Int
        }

        assertEquals(NativeScrollWriteResultCode.NotReady.wireValue, resultCode)
        assertEquals(1L, recyclerView.privateField("latestComposeWriteOperation"))
    }

    @Test
    fun deferredContentInsetIsNotCurrentBeforeAndroidContentAttachment() {
        val recyclerView = recyclerWithUnattachedContent()

        recyclerView.call("contentInsetWhenEndDrag", contentInsetWrite(operation = 0L), null)

        val handler = recyclerView.privateField<OverScrollHandler>("overScrollHandler")
        assertFalse(handler.contentInsetWhenEndDragIsCurrent())
    }

    @Test
    fun listReuseRestoreRemainsPendingUntilAndroidContentIsAttached() {
        val recyclerView = KRRecyclerView(RuntimeEnvironment.getApplication())
        val listView = ListView<ListAttr, ListEvent>()
        val pagerId = "list-reuse-restore-${listView.nativeRef}"
        val nativeResults = mutableListOf<Int>()
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
                    if (methodId == NativeMethod.CALL_VIEW_METHOD && arg1 == listView.nativeRef) {
                        val callbackRef = arg4 as? String
                        recyclerView.call(
                            arg2 as String,
                            arg3 as? String,
                            callbackRef?.let { ref ->
                                { result ->
                                    val resultMap = result as Map<*, *>
                                    nativeResults += resultMap["resultCode"] as Int
                                    val json = JSONObject()
                                    resultMap.forEach { (key, value) -> json.put(key as String, value) }
                                    PagerManager.fireCallBack(pagerId, ref, json.toString())
                                }
                            },
                        )
                    }
                    return null
                }
            }
        }
        BridgeManager.registerNativeBridge(pagerId, bridge)
        BridgeManager.registerPageRouter(pagerId) {
            object : Pager() {
                override fun body(): ViewBuilder = {}
            }
        }
        BridgeManager.currentPageId = pagerId
        PagerManager.createPager(pagerId, pagerId, "{}")
        listView.pagerId = pagerId
        listView.createRenderView()
        ScrollerViewOffsetReflection.setCurrentOffsetY(listView, 42f)
        listView.removeRenderView()

        listView.createRenderView()

        assertTrue(nativeResults.isEmpty())
        assertTrue(recyclerView.hasPendingContentOffset())

        val contentView = KRRecyclerContentView(RuntimeEnvironment.getApplication()).apply {
            layoutParams = RecyclerView.LayoutParams(100, 500)
        }
        recyclerView.addView(contentView)
        recyclerView.measure(
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
        )
        recyclerView.layout(0, 0, 100, 100)

        assertFalse(recyclerView.hasPendingContentOffset())
        assertEquals(42, -contentView.top)

        recyclerView.layout(0, 0, 100, 100)
        assertFalse(recyclerView.hasPendingContentOffset())
        assertEquals(42, -contentView.top)
        assertTrue(nativeResults.isEmpty())
    }

    private fun KRRecyclerView.nativeWriteArbiter(): NativeScrollWriteOperationArbiter =
        privateField("nativeWriteArbiter")

    private fun KRRecyclerView.setScrollEndCallback(callback: KuiklyRenderCallback) {
        setPrivateField("scrollEndEventCallback", callback)
    }

    private fun KRRecyclerView.invokeFireEndScrollEvent() {
        javaClass.getDeclaredMethod("fireEndScrollEvent").apply {
            isAccessible = true
            invoke(this@invokeFireEndScrollEvent)
        }
    }

    private fun KRRecyclerView.setPrivateField(name: String, value: Any?) {
        javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(this@setPrivateField, value)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> KRRecyclerView.privateField(name: String): T =
        javaClass.getDeclaredField(name).let { field ->
            field.isAccessible = true
            field.get(this) as T
        }

    private fun KRRecyclerView.hasPendingContentOffset(): Boolean {
        val slot = privateField<Any>("pendingSetContentOffset")
        return slot.javaClass.getDeclaredField("pending").let { field ->
            field.isAccessible = true
            field.get(slot) != null
        }
    }

    private fun recyclerWithUnattachedContent(): KRRecyclerView =
        KRRecyclerView(RuntimeEnvironment.getApplication()).apply {
            addView(KRRecyclerContentView(RuntimeEnvironment.getApplication()))
            assertEquals(0, childCount)
            assertTrue(layoutManager != null)
        }

    private fun contentInsetWrite(operation: Long): String =
        "0 0 0 0 0 0 0 $operation 300 100 0 -1 0 $operation 1 0 0 0 0 0"

    private object ScrollerViewOffsetReflection {
        fun setCurrentOffsetY(listView: ListView<*, *>, offset: Float) {
            listView.javaClass.superclass.getDeclaredField("curOffsetY").apply {
                isAccessible = true
                setFloat(listView, offset)
            }
        }
    }
}
