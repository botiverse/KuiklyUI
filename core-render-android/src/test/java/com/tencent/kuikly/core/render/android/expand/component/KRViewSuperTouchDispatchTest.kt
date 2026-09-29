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

package com.tencent.kuikly.core.render.android.expand.component

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Wiring contract of the failure found on device (task #69 runtime reopen):
 * a compose-hosted root (KRView with superTouch) either withholds the whole
 * MotionEvent stream from native children (barrier resolved: capture) or
 * delivers it completely (SelectableText region resolved: release). The
 * capture decision is latched at ACTION_DOWN and stays sticky per gesture.
 *
 * The compose side of the seam — real release/capture modifier nodes
 * resolving to the boolean this test drives — is pinned by
 * NativeDispatchPolicyTest in compose commonTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class KRViewSuperTouchDispatchTest {

    private class TouchRecorder {
        var dispatchCount = 0
        var lastAction = -1
    }

    private fun buildTree(): Triple<TouchRecorder, KRView, KRSelectableTextView> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = KRView(activity)
        root.setProp(SUPER_TOUCH_PROP, true)
        val child = KRSelectableTextView(activity)
        val recorder = TouchRecorder()
        // Observes the stream without subclassing the production class; the
        // listener returns false so the TextView's real selection handling
        // (long-press etc.) still runs.
        child.setOnTouchListener { _, event ->
            recorder.dispatchCount++
            recorder.lastAction = event.actionMasked
            false
        }
        child.setProp(KRSelectableTextView.PROP_TEXT, "selectable body text")
        root.addView(
            child,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        activity.setContentView(root)
        shadowOf(Looper.getMainLooper()).idle()
        return Triple(recorder, root, child)
    }

    private fun motion(downTime: Long, action: Int, x: Float = 5f, y: Float = 5f): MotionEvent =
        MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)

    @Test
    fun captureRequestedWithholdsTheEntireStreamFromTheNativeChild() {
        val (recorder, root, _) = buildTree()
        // What SuperTouchManager writes when HitPathTracker resolves CAPTURE
        // (barrier hit outside any release region).
        root.setProp(NATIVE_DISPATCH_CAPTURE_PROP, true)

        val downTime = SystemClock.uptimeMillis()
        assertTrue(root.dispatchTouchEvent(motion(downTime, MotionEvent.ACTION_DOWN)))
        root.dispatchTouchEvent(motion(downTime, MotionEvent.ACTION_MOVE))
        root.dispatchTouchEvent(motion(downTime, MotionEvent.ACTION_UP))

        assertEquals(0, recorder.dispatchCount)
    }

    @Test
    fun captureDecisionIsStickyForTheWholeGesture() {
        val (recorder, root, _) = buildTree()
        root.setProp(NATIVE_DISPATCH_CAPTURE_PROP, true)

        val downTime = SystemClock.uptimeMillis()
        root.dispatchTouchEvent(motion(downTime, MotionEvent.ACTION_DOWN))
        // A mid-gesture request change must not leak events into this gesture.
        root.setProp(NATIVE_DISPATCH_CAPTURE_PROP, false)
        root.dispatchTouchEvent(motion(downTime, MotionEvent.ACTION_MOVE))
        root.dispatchTouchEvent(motion(downTime, MotionEvent.ACTION_UP))
        assertEquals(0, recorder.dispatchCount)

        // The next gesture consumes the updated (released) decision.
        val secondDown = SystemClock.uptimeMillis()
        root.dispatchTouchEvent(motion(secondDown, MotionEvent.ACTION_DOWN))
        assertTrue(recorder.dispatchCount > 0)
        root.dispatchTouchEvent(motion(secondDown, MotionEvent.ACTION_UP))
    }

    @Test
    fun releaseResolvedDeliversTheFullStreamAndLongPressStartsSelection() {
        val (recorder, root, child) = buildTree()
        // What SuperTouchManager writes when the SelectableText release region
        // is on the hit branch (HitPathTracker resolves no capture).
        root.setProp(NATIVE_DISPATCH_CAPTURE_PROP, false)

        val downTime = SystemClock.uptimeMillis()
        root.dispatchTouchEvent(motion(downTime, MotionEvent.ACTION_DOWN))
        assertTrue(recorder.dispatchCount > 0)
        assertEquals(MotionEvent.ACTION_DOWN, recorder.lastAction)

        shadowOf(Looper.getMainLooper())
            .idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 100L))
        root.dispatchTouchEvent(motion(downTime, MotionEvent.ACTION_UP))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(MotionEvent.ACTION_UP, recorder.lastAction)
        // Dispatch/state certification only: the complete stream reached the
        // native child through the superTouch root and word selection started.
        assertTrue(child.hasSelection())
    }

    private companion object {
        // Wire keys consumed by KRView.setProp (companion consts are private
        // in KRView; the wire strings are the cross-layer contract).
        const val SUPER_TOUCH_PROP = "superTouch"
        const val NATIVE_DISPATCH_CAPTURE_PROP = "nativeDispatchCapture"
    }
}
