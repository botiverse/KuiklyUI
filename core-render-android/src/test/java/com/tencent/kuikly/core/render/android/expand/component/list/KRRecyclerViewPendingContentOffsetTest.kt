/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI.
 */

package com.tencent.kuikly.core.render.android.expand.component.list

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.NestedScrollingParent2
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regression fixture for task #990 / Hands 91b9e3f1.
 *
 * A first-IME viewport shrink can replay a Compose-owned offset before the newly focused row has
 * expanded the Android content range. The native list must keep that write pending across every
 * still-not-ready layout and apply it once the content range catches up. Dropping the write after
 * the first layout leaves all Compose-positioned rows outside the physical viewport and produces
 * a white list while the surrounding header, composer, and IME remain visible.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class KRRecyclerViewPendingContentOffsetTest {

    @Test
    fun rangeNotReadyReplaySurvivesFirstLayoutAndAppliesAfterFocusedRowIsPlaced() {
        val (recyclerView, contentView) = createRecycler(INITIAL_CONTENT_HEIGHT_PX)

        assertEquals(INITIAL_CONTENT_HEIGHT_PX, contentView.height)
        assertEquals(0, -contentView.top)

        recyclerView.call(
            "contentOffset",
            "0 $PENDING_OFFSET_PX 0",
            null,
        )
        assertTrue(
            "the focused offset must wait while its target row is still outside the native range",
            recyclerView.hasPendingContentOffset(),
        )

        // This is the first shrunken-frame layout from the failing production window. The target
        // is not placed yet, so the range is still too small and the write must remain pending.
        layoutRecycler(recyclerView)
        assertTrue(
            "a still-not-ready layout must not discard the current pending offset owner",
            recyclerView.hasPendingContentOffset(),
        )
        assertEquals(0, -contentView.top)

        // Focused-origin loading completes and places the target row, expanding the native range.
        advanceContentRangeAndRetry(recyclerView, contentView)

        assertEquals(READY_CONTENT_HEIGHT_PX, contentView.height)
        assertEquals(PENDING_OFFSET_PX, -contentView.top)
        assertTrue(
            "the write is consumed only after the physical scroller reaches the Compose target",
            !recyclerView.hasPendingContentOffset(),
        )
    }

    @Test
    fun newerProgrammaticOwnerReplacesOlderPendingOffsetWhileRangeIsNotReady() {
        val (recyclerView, contentView) = createRecycler(INITIAL_CONTENT_HEIGHT_PX)

        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)
        layoutRecycler(recyclerView)
        assertTrue(recyclerView.pendingContentOffset().contains(" $PENDING_OFFSET_PX "))

        val newerOffset = PENDING_OFFSET_PX + 100
        recyclerView.call("contentOffset", "0 $newerOffset 0", null)
        layoutRecycler(recyclerView)
        assertTrue(
            "the current owner, not the superseded target, must survive another not-ready layout",
            recyclerView.pendingContentOffset().contains(" $newerOffset "),
        )

        advanceContentRangeAndRetry(recyclerView, contentView)

        assertEquals(newerOffset, -contentView.top)
        assertTrue(!recyclerView.hasPendingContentOffset())
    }

    @Test
    fun readyApplyDoesNotEraseNewOwnerInstalledReentrantlyByScrollCallback() {
        val (recyclerView, contentView) = createRecycler(PARTIALLY_SCROLLABLE_CONTENT_HEIGHT_PX)
        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)
        assertTrue(recyclerView.hasPendingContentOffset())

        layoutContentRange(contentView, READY_CONTENT_HEIGHT_PX)
        var callbackCount = 0
        val scrollCallback: KuiklyRenderCallback = {
            callbackCount++
            recyclerView.call("contentOffset", "0 $REENTRANT_PENDING_OFFSET_PX 0", null)
        }
        recyclerView.setProp("scroll", scrollCallback)

        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)

        assertEquals(1, callbackCount)
        assertEquals(PENDING_OFFSET_PX, -contentView.top)
        assertTrue(
            recyclerView.pendingContentOffset().contains(" $REENTRANT_PENDING_OFFSET_PX "),
        )
    }

    @Test
    fun readyRangeAppliesOffsetImmediatelyWithoutLeavingPendingOwner() {
        val (recyclerView, contentView) = createRecycler(READY_CONTENT_HEIGHT_PX)

        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)

        assertEquals(PENDING_OFFSET_PX, -contentView.top)
        assertTrue(!recyclerView.hasPendingContentOffset())
    }

    @Test
    fun linearAnimationFrameDoesNotEraseNewOwnerInstalledBySettlingCallback() {
        assertCustomAnimationFrameDoesNotEraseNewOwner(
            animationDamping = 0f,
            animationCurve = 1,
        )
    }

    @Test
    fun springAnimationFrameDoesNotEraseNewOwnerInstalledBySettlingCallback() {
        assertCustomAnimationFrameDoesNotEraseNewOwner(
            animationDamping = 0.5f,
            animationCurve = 0,
        )
    }

    @Test
    fun newerDeferredOffsetRemainsFinalWinnerAfterLinearAnimationQuiesces() {
        assertDeferredOffsetRemainsFinalWinnerAfterCustomAnimationQuiesces(
            animationDamping = 0f,
            animationCurve = 1,
        )
    }

    @Test
    fun newerDeferredOffsetRemainsFinalWinnerAfterSpringAnimationQuiesces() {
        assertDeferredOffsetRemainsFinalWinnerAfterCustomAnimationQuiesces(
            animationDamping = 0.5f,
            animationCurve = 0,
        )
    }

    @Test
    fun readyOffsetInstalledBySettlingCallbackSupersedesLinearAnimationBeforeFirstFrame() {
        assertReadyOffsetInstalledBySettlingCallbackRemainsFinalWinner(
            animationDamping = 0f,
            animationCurve = 1,
        )
    }

    @Test
    fun readyOffsetInstalledBySettlingCallbackSupersedesSpringAnimationBeforeFirstFrame() {
        assertReadyOffsetInstalledBySettlingCallbackRemainsFinalWinner(
            animationDamping = 0.5f,
            animationCurve = 0,
        )
    }

    @Test
    fun readyRecyclerViewAnimationInstalledBySettlingCallbackSupersedesCustomAnimation() {
        val (recyclerView, contentView) = createRecycler(FINAL_CONTENT_HEIGHT_PX)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(recyclerView)
        layoutRecycler(recyclerView)
        var scrollEndCount = 0
        val scrollEndCallback: KuiklyRenderCallback = { scrollEndCount++ }
        recyclerView.setProp("scrollEnd", scrollEndCallback)
        var settlingCallbackCount = 0
        recyclerView.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(view: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_SETTLING &&
                        settlingCallbackCount == 0
                    ) {
                        settlingCallbackCount++
                        (view as KRRecyclerView).call(
                            "contentOffset",
                            "0 $REENTRANT_PENDING_OFFSET_PX 1 200 1 0 0",
                            null,
                        )
                    }
                }
            },
        )

        recyclerView.call(
            "contentOffset",
            "0 $PENDING_OFFSET_PX 1 200 0 0 1",
            null,
        )
        assertEquals(1, settlingCallbackCount)
        assertTrue(!recyclerView.hasRunningCustomScrollAnimation())

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300L))

        assertEquals(REENTRANT_PENDING_OFFSET_PX, -contentView.top)
        assertEquals(RecyclerView.SCROLL_STATE_IDLE, recyclerView.scrollState)
        assertEquals(1, scrollEndCount)
        activity.finish()
    }

    @Test
    fun readyImmediateOffsetStopsOlderRecyclerViewAnimationAndRemainsFinalWinner() {
        val (activity, recyclerView, contentView) = createAttachedRecycler()
        var scrollEndCount = 0
        val scrollEndCallback: KuiklyRenderCallback = { scrollEndCount++ }
        recyclerView.setProp("scrollEnd", scrollEndCallback)

        startDefaultRecyclerViewContentOffsetAnimation(recyclerView)

        recyclerView.call(
            "contentOffset",
            "0 $REENTRANT_PENDING_OFFSET_PX 0",
            null,
        )
        assertEquals(
            "the newer immediate absolute write must land synchronously",
            REENTRANT_PENDING_OFFSET_PX,
            -contentView.top,
        )

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2L))

        assertEquals(
            "an older RecyclerView ViewFlinger must not become the final physical writer",
            listOf(REENTRANT_PENDING_OFFSET_PX, RecyclerView.SCROLL_STATE_IDLE, 1, false),
            listOf(
                -contentView.top,
                recyclerView.scrollState,
                scrollEndCount,
                recyclerView.hasActiveRecyclerViewContentOffsetOwner(),
            ),
        )
        activity.finish()
    }

    @Test
    fun readyCustomOffsetStopsOlderRecyclerViewAnimationAndRemainsFinalWinner() {
        val (activity, recyclerView, contentView) = createAttachedRecycler()
        var scrollEndCount = 0
        val scrollEndCallback: KuiklyRenderCallback = { scrollEndCount++ }
        recyclerView.setProp("scrollEnd", scrollEndCallback)

        startDefaultRecyclerViewContentOffsetAnimation(recyclerView)

        recyclerView.call(
            "contentOffset",
            "0 $REENTRANT_PENDING_OFFSET_PX 1 200 0 0 1",
            null,
        )
        assertTrue(recyclerView.hasRunningCustomScrollAnimation())

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2L))

        assertEquals(
            "the newer custom absolute write must remain authoritative after all transports stop",
            listOf(
                REENTRANT_PENDING_OFFSET_PX,
                RecyclerView.SCROLL_STATE_IDLE,
                1,
                false,
                false,
            ),
            listOf(
                -contentView.top,
                recyclerView.scrollState,
                scrollEndCount,
                recyclerView.hasRunningCustomScrollAnimation(),
                recyclerView.hasActiveRecyclerViewContentOffsetOwner(),
            ),
        )
        activity.finish()
    }

    @Test
    fun readyRecyclerViewOffsetReplacesOlderRecyclerViewAnimationAndRemainsFinalWinner() {
        val (activity, recyclerView, contentView) = createAttachedRecycler()
        var scrollEndCount = 0
        val scrollEndCallback: KuiklyRenderCallback = { scrollEndCount++ }
        recyclerView.setProp("scrollEnd", scrollEndCallback)

        startDefaultRecyclerViewContentOffsetAnimation(recyclerView)

        recyclerView.call(
            "contentOffset",
            "0 $REENTRANT_PENDING_OFFSET_PX 1",
            null,
        )
        assertEquals(RecyclerView.SCROLL_STATE_SETTLING, recyclerView.scrollState)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2L))

        assertEquals(
            "the newer RecyclerView absolute write must replace the old ViewFlinger generation",
            listOf(REENTRANT_PENDING_OFFSET_PX, RecyclerView.SCROLL_STATE_IDLE, 1, false),
            listOf(
                -contentView.top,
                recyclerView.scrollState,
                scrollEndCount,
                recyclerView.hasActiveRecyclerViewContentOffsetOwner(),
            ),
        )
        activity.finish()
    }

    @Test
    fun stopCallbackCustomOffsetSupersedesTheOuterRecyclerViewTakeover() {
        assertStopCallbackOffsetSupersedesOuterTakeover(
            callbackOffset =
                "0 $CALLBACK_FINAL_OFFSET_PX 1 200 0 0 1",
            assertTransportStarted = { recyclerView ->
                assertTrue(recyclerView.hasRunningCustomScrollAnimation())
            },
        )
    }

    @Test
    fun stopCallbackRecyclerViewOffsetSupersedesTheOuterRecyclerViewTakeover() {
        assertStopCallbackOffsetSupersedesOuterTakeover(
            callbackOffset = "0 $CALLBACK_FINAL_OFFSET_PX 1",
            assertTransportStarted = { recyclerView ->
                assertEquals(RecyclerView.SCROLL_STATE_SETTLING, recyclerView.scrollState)
                assertTrue(recyclerView.hasActiveRecyclerViewContentOffsetOwner())
            },
        )
    }

    @Test
    fun inheritedNonTouchHistoryIsClosedBeforeImmediateTakeoverPublishesIdle() {
        val history = createInheritedNonTouchRecyclerViewHistory()
        val (activity, recyclerView, contentView, nestedParent) = history
        var scrollEndCount = 0
        val scrollEndCallback: KuiklyRenderCallback = { scrollEndCount++ }
        recyclerView.setProp("scrollEnd", scrollEndCallback)

        startRecyclerViewWithInheritedNonTouchConnection(history)

        recyclerView.call(
            "contentOffset",
            "0 $CALLBACK_FINAL_OFFSET_PX 0",
            null,
        )
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2L))

        assertEquals(
            "an immediate takeover must close the custom-inherited child transport",
            listOf(
                CALLBACK_FINAL_OFFSET_PX,
                RecyclerView.SCROLL_STATE_IDLE,
                1,
                false,
                false,
                false,
                1,
                1,
            ),
            listOf(
                -contentView.top,
                recyclerView.scrollState,
                scrollEndCount,
                recyclerView.hasRunningCustomScrollAnimation(),
                recyclerView.hasActiveRecyclerViewContentOffsetOwner(),
                recyclerView.hasNestedScrollingParent(ViewCompat.TYPE_NON_TOUCH),
                nestedParent.nonTouchStartCount,
                nestedParent.nonTouchStopCount,
            ),
        )
        activity.finish()
    }

    @Test
    fun inheritedNonTouchHistoryIsClosedBeforeCustomTakeoverStartsFreshTransport() {
        val history = createInheritedNonTouchRecyclerViewHistory()
        val (activity, recyclerView, contentView, nestedParent) = history
        var scrollEndCount = 0
        val scrollEndCallback: KuiklyRenderCallback = { scrollEndCount++ }
        recyclerView.setProp("scrollEnd", scrollEndCallback)

        startRecyclerViewWithInheritedNonTouchConnection(history)

        recyclerView.call(
            "contentOffset",
            "0 $CALLBACK_FINAL_OFFSET_PX 1 200 0 0 1",
            null,
        )
        assertEquals(
            "the old connection must close before the new custom owner starts a fresh one",
            listOf(true, true, 2, 1),
            listOf(
                recyclerView.hasRunningCustomScrollAnimation(),
                recyclerView.hasNestedScrollingParent(ViewCompat.TYPE_NON_TOUCH),
                nestedParent.nonTouchStartCount,
                nestedParent.nonTouchStopCount,
            ),
        )

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2L))

        assertEquals(
            "the fresh custom transport must own and close only its own connection",
            listOf(
                CALLBACK_FINAL_OFFSET_PX,
                RecyclerView.SCROLL_STATE_IDLE,
                1,
                false,
                false,
                false,
                2,
                2,
            ),
            listOf(
                -contentView.top,
                recyclerView.scrollState,
                scrollEndCount,
                recyclerView.hasRunningCustomScrollAnimation(),
                recyclerView.hasActiveRecyclerViewContentOffsetOwner(),
                recyclerView.hasNestedScrollingParent(ViewCompat.TYPE_NON_TOUCH),
                nestedParent.nonTouchStartCount,
                nestedParent.nonTouchStopCount,
            ),
        )
        activity.finish()
    }

    @Test
    fun inheritedNonTouchHistoryIsClosedBeforeRecyclerViewTakeover() {
        val history = createInheritedNonTouchRecyclerViewHistory()
        val (activity, recyclerView, contentView, nestedParent) = history
        var scrollEndCount = 0
        val scrollEndCallback: KuiklyRenderCallback = { scrollEndCount++ }
        recyclerView.setProp("scrollEnd", scrollEndCallback)

        startRecyclerViewWithInheritedNonTouchConnection(history)

        recyclerView.call(
            "contentOffset",
            "0 $CALLBACK_FINAL_OFFSET_PX 1",
            null,
        )
        assertEquals(
            "the replacement ViewFlinger must not retain the predecessor's child connection",
            listOf(RecyclerView.SCROLL_STATE_SETTLING, true, false, 1, 1),
            listOf(
                recyclerView.scrollState,
                recyclerView.hasActiveRecyclerViewContentOffsetOwner(),
                recyclerView.hasNestedScrollingParent(ViewCompat.TYPE_NON_TOUCH),
                nestedParent.nonTouchStartCount,
                nestedParent.nonTouchStopCount,
            ),
        )

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2L))

        assertEquals(
            "the replacement RecyclerView transport must quiesce without reopening nesting",
            listOf(
                CALLBACK_FINAL_OFFSET_PX,
                RecyclerView.SCROLL_STATE_IDLE,
                1,
                false,
                false,
                false,
                1,
                1,
            ),
            listOf(
                -contentView.top,
                recyclerView.scrollState,
                scrollEndCount,
                recyclerView.hasRunningCustomScrollAnimation(),
                recyclerView.hasActiveRecyclerViewContentOffsetOwner(),
                recyclerView.hasNestedScrollingParent(ViewCompat.TYPE_NON_TOUCH),
                nestedParent.nonTouchStartCount,
                nestedParent.nonTouchStopCount,
            ),
        )
        activity.finish()
    }

    @Test
    fun inheritedNonTouchIsClosedBeforeIdleCallbackInstallsNewCustomTransport() {
        assertInheritedNonTouchIsClosedBeforeIdleCallbackInstallsNewTransport(
            callbackOffset = "0 $CALLBACK_FINAL_OFFSET_PX 1 10000 0 0 1",
            expectedStartCountAfterCallback = 2,
            expectedFinalStopCount = 2,
            expectedCustomRunningAfterCallback = true,
            expectedActiveRecyclerViewAfterCallback = false,
            expectedNestedAfterCallback = true,
            quiescenceDuration = Duration.ofMillis(11000L),
        )
    }

    @Test
    fun inheritedNonTouchIsClosedBeforeIdleCallbackInstallsNewRecyclerViewTransport() {
        assertInheritedNonTouchIsClosedBeforeIdleCallbackInstallsNewTransport(
            callbackOffset = "0 $CALLBACK_FINAL_OFFSET_PX 1",
            expectedStartCountAfterCallback = 1,
            expectedFinalStopCount = 1,
            expectedCustomRunningAfterCallback = false,
            expectedActiveRecyclerViewAfterCallback = true,
            expectedNestedAfterCallback = false,
            quiescenceDuration = Duration.ofSeconds(2L),
        )
    }

    @Test
    fun naturalRecyclerViewTerminalReconnectsCustomInstalledByIdleCallback() {
        assertNaturalRecyclerViewTerminalPreservesIdleCallbackTransport(
            callbackOffset = "0 $CALLBACK_FINAL_OFFSET_PX 1 10000 0 0 1",
            expectedStateInsideIdleCallback = listOf(
                RecyclerView.SCROLL_STATE_SETTLING,
                true,
                false,
                true,
                1,
                0,
            ),
            expectedFinalStartCount = 2,
            expectedFinalStopCount = 2,
            quiescenceDuration = Duration.ofMillis(11000L),
        )
    }

    @Test
    fun naturalRecyclerViewTerminalDoesNotGiveReplacementViewFlingerCustomNesting() {
        assertNaturalRecyclerViewTerminalPreservesIdleCallbackTransport(
            callbackOffset = "0 $CALLBACK_FINAL_OFFSET_PX 1",
            expectedStateInsideIdleCallback = listOf(
                RecyclerView.SCROLL_STATE_SETTLING,
                false,
                true,
                true,
                1,
                0,
            ),
            expectedFinalStartCount = 1,
            expectedFinalStopCount = 1,
            quiescenceDuration = Duration.ofSeconds(2L),
        )
    }

    @Test
    fun naturalCustomTerminalPreservesRecyclerViewInstalledByParentStopCallback() {
        assertNaturalCustomTerminalPreservesParentStopCallbackOwner(
            callbackOffset = "0 $CALLBACK_FINAL_OFFSET_PX 1",
            expectedStateInsideParentCallback = listOf(
                RecyclerView.SCROLL_STATE_SETTLING,
                false,
                true,
                true,
                1,
                1,
            ),
            expectedFinalOffset = CALLBACK_FINAL_OFFSET_PX,
            expectedFinalStartCount = 1,
            expectedFinalStopCount = 1,
            quiescenceDuration = Duration.ofSeconds(2L),
        )
    }

    @Test
    fun naturalCustomTerminalPreservesCustomInstalledByParentStopCallback() {
        assertNaturalCustomTerminalPreservesParentStopCallbackOwner(
            callbackOffset = "0 $CALLBACK_FINAL_OFFSET_PX 1 10000 0 0 1",
            expectedStateInsideParentCallback = listOf(
                RecyclerView.SCROLL_STATE_SETTLING,
                true,
                false,
                true,
                1,
                1,
            ),
            expectedFinalOffset = CALLBACK_FINAL_OFFSET_PX,
            expectedFinalStartCount = 2,
            expectedFinalStopCount = 2,
            quiescenceDuration = Duration.ofMillis(11000L),
        )
    }

    @Test
    fun naturalCustomTerminalWithoutSuccessorStillStopsItsOwnTransport() {
        assertNaturalCustomTerminalPreservesParentStopCallbackOwner(
            callbackOffset = null,
            expectedStateInsideParentCallback = null,
            expectedFinalOffset = PENDING_OFFSET_PX,
            expectedFinalStartCount = 1,
            expectedFinalStopCount = 1,
            quiescenceDuration = Duration.ofMillis(120L),
        )
    }

    private fun assertNaturalCustomTerminalPreservesParentStopCallbackOwner(
        callbackOffset: String?,
        expectedStateInsideParentCallback: List<Any>?,
        expectedFinalOffset: Int,
        expectedFinalStartCount: Int,
        expectedFinalStopCount: Int,
        quiescenceDuration: Duration,
    ) {
        var parentStopCallbackCount = 0
        var stateInsideParentCallback: List<Any>? = null
        lateinit var nestedParent: RecordingNestedParent
        val history = createInheritedNonTouchRecyclerViewHistory { target ->
            if (parentStopCallbackCount == 0) {
                parentStopCallbackCount++
                val currentRecyclerView = target as KRRecyclerView
                if (callbackOffset != null) {
                    currentRecyclerView.call(
                        "contentOffset",
                        callbackOffset,
                        null,
                    )
                    stateInsideParentCallback = listOf(
                        currentRecyclerView.scrollState,
                        currentRecyclerView.hasRunningCustomScrollAnimation(),
                        currentRecyclerView.hasActiveRecyclerViewContentOffsetOwner(),
                        currentRecyclerView.hasNestedScrollingParent(ViewCompat.TYPE_NON_TOUCH),
                        nestedParent.nonTouchStartCount,
                        nestedParent.nonTouchStopCount,
                    )
                }
            }
        }
        val (activity, recyclerView, contentView, historyNestedParent) = history
        nestedParent = historyNestedParent
        var scrollEndCount = 0
        val scrollEndCallback: KuiklyRenderCallback = { scrollEndCount++ }
        recyclerView.setProp("scrollEnd", scrollEndCallback)

        recyclerView.call(
            "contentOffset",
            "0 $PENDING_OFFSET_PX 1 100 0 0 1",
            null,
        )
        assertEquals(
            "the natural custom history must own one accepted non-touch connection",
            listOf(true, RecyclerView.SCROLL_STATE_SETTLING, true, 1, 0),
            listOf(
                recyclerView.hasRunningCustomScrollAnimation(),
                recyclerView.scrollState,
                recyclerView.hasNestedScrollingParent(ViewCompat.TYPE_NON_TOUCH),
                nestedParent.nonTouchStartCount,
                nestedParent.nonTouchStopCount,
            ),
        )

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(120L))

        assertEquals(1, parentStopCallbackCount)
        assertEquals(
            "callback-installed successor must observe the predecessor's old pointer",
            expectedStateInsideParentCallback,
            stateInsideParentCallback,
        )

        shadowOf(Looper.getMainLooper()).idleFor(quiescenceDuration)

        assertEquals(
            "natural custom cleanup must preserve a callback owner or stop itself when absent",
            listOf(
                expectedFinalOffset,
                RecyclerView.SCROLL_STATE_IDLE,
                1,
                false,
                false,
                false,
                expectedFinalStartCount,
                expectedFinalStopCount,
            ),
            listOf(
                -contentView.top,
                recyclerView.scrollState,
                scrollEndCount,
                recyclerView.hasRunningCustomScrollAnimation(),
                recyclerView.hasActiveRecyclerViewContentOffsetOwner(),
                recyclerView.hasNestedScrollingParent(ViewCompat.TYPE_NON_TOUCH),
                nestedParent.nonTouchStartCount,
                nestedParent.nonTouchStopCount,
            ),
        )
        activity.finish()
    }

    private fun assertNaturalRecyclerViewTerminalPreservesIdleCallbackTransport(
        callbackOffset: String,
        expectedStateInsideIdleCallback: List<Any>,
        expectedFinalStartCount: Int,
        expectedFinalStopCount: Int,
        quiescenceDuration: Duration,
    ) {
        val history = createInheritedNonTouchRecyclerViewHistory()
        val (activity, recyclerView, contentView, nestedParent) = history
        var scrollEndCount = 0
        val scrollEndCallback: KuiklyRenderCallback = { scrollEndCount++ }
        recyclerView.setProp("scrollEnd", scrollEndCallback)
        startRecyclerViewWithInheritedNonTouchConnection(history)

        var idleCallbackCount = 0
        var stateInsideIdleCallback: List<Any>? = null
        recyclerView.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(view: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE && idleCallbackCount == 0) {
                        idleCallbackCount++
                        val currentRecyclerView = view as KRRecyclerView
                        currentRecyclerView.call(
                            "contentOffset",
                            callbackOffset,
                            null,
                        )
                        // RecyclerView's natural ViewFlinger terminal branch publishes IDLE before
                        // it closes TYPE_NON_TOUCH nesting. The callback-installed custom owner
                        // therefore sees the predecessor's doomed parent pointer as still open.
                        stateInsideIdleCallback = listOf(
                            currentRecyclerView.scrollState,
                            currentRecyclerView.hasRunningCustomScrollAnimation(),
                            currentRecyclerView.hasActiveRecyclerViewContentOffsetOwner(),
                            currentRecyclerView.hasNestedScrollingParent(ViewCompat.TYPE_NON_TOUCH),
                            nestedParent.nonTouchStartCount,
                            nestedParent.nonTouchStopCount,
                        )
                    }
                }
            },
        )

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2L))

        assertEquals(1, idleCallbackCount)
        assertEquals(
            "the IDLE callback must run before the predecessor closes its inherited connection",
            expectedStateInsideIdleCallback,
            stateInsideIdleCallback,
        )
        shadowOf(Looper.getMainLooper()).idleFor(quiescenceDuration)

        assertEquals(
            "natural ViewFlinger cleanup must preserve the callback-installed successor ownership",
            listOf(
                CALLBACK_FINAL_OFFSET_PX,
                RecyclerView.SCROLL_STATE_IDLE,
                1,
                false,
                false,
                false,
                expectedFinalStartCount,
                expectedFinalStopCount,
            ),
            listOf(
                -contentView.top,
                recyclerView.scrollState,
                scrollEndCount,
                recyclerView.hasRunningCustomScrollAnimation(),
                recyclerView.hasActiveRecyclerViewContentOffsetOwner(),
                recyclerView.hasNestedScrollingParent(ViewCompat.TYPE_NON_TOUCH),
                nestedParent.nonTouchStartCount,
                nestedParent.nonTouchStopCount,
            ),
        )
        activity.finish()
    }

    @Test
    fun inheritedParentStopCallbackCustomOffsetSupersedesOuterImmediateTakeover() {
        assertInheritedParentStopCallbackOffsetSupersedesOuterTakeover(
            callbackOffset = "0 $CALLBACK_FINAL_OFFSET_PX 1 10000 0 0 1",
            expectedStateInsideParentCallback = listOf(
                RecyclerView.SCROLL_STATE_SETTLING,
                true,
                false,
                true,
                1,
                1,
            ),
            expectedStateAfterCallback = listOf(
                RecyclerView.SCROLL_STATE_SETTLING,
                true,
                false,
                true,
                2,
                1,
            ),
            expectedFinalStartCount = 2,
            expectedFinalStopCount = 2,
            quiescenceDuration = Duration.ofMillis(11000L),
        )
    }

    @Test
    fun inheritedParentStopCallbackRecyclerViewOffsetSupersedesOuterImmediateTakeover() {
        assertInheritedParentStopCallbackOffsetSupersedesOuterTakeover(
            callbackOffset = "0 $CALLBACK_FINAL_OFFSET_PX 1",
            expectedStateInsideParentCallback = listOf(
                RecyclerView.SCROLL_STATE_SETTLING,
                false,
                true,
                true,
                1,
                1,
            ),
            expectedStateAfterCallback = listOf(
                RecyclerView.SCROLL_STATE_SETTLING,
                false,
                true,
                false,
                1,
                1,
            ),
            expectedFinalStartCount = 1,
            expectedFinalStopCount = 1,
            quiescenceDuration = Duration.ofSeconds(2L),
        )
    }

    @Test
    fun inheritedParentStopCallbackImmediateOffsetSupersedesOuterImmediateTakeover() {
        assertInheritedParentStopCallbackOffsetSupersedesOuterTakeover(
            callbackOffset = "0 $CALLBACK_FINAL_OFFSET_PX 0",
            expectedStateInsideParentCallback = listOf(
                RecyclerView.SCROLL_STATE_SETTLING,
                false,
                false,
                true,
                1,
                1,
            ),
            expectedStateAfterCallback = listOf(
                RecyclerView.SCROLL_STATE_IDLE,
                false,
                false,
                false,
                1,
                1,
            ),
            expectedFinalStartCount = 1,
            expectedFinalStopCount = 1,
            quiescenceDuration = Duration.ofSeconds(2L),
        )
    }

    @Test
    fun inheritedParentStopCallbackPendingOffsetSupersedesOuterImmediateTakeover() {
        var parentStopCallbackCount = 0
        var callbackSawPendingOwner = false
        lateinit var nestedParent: RecordingNestedParent
        val history = createInheritedNonTouchRecyclerViewHistory { target ->
            if (parentStopCallbackCount == 0) {
                parentStopCallbackCount++
                val currentRecyclerView = target as KRRecyclerView
                currentRecyclerView.call(
                    "contentOffset",
                    "0 $PARENT_STOP_PENDING_OFFSET_PX 0",
                    null,
                )
                callbackSawPendingOwner = currentRecyclerView.pendingContentOffset()
                    .contains(" $PARENT_STOP_PENDING_OFFSET_PX ")
            }
        }
        val (activity, recyclerView, contentView, historyNestedParent) = history
        nestedParent = historyNestedParent
        var scrollEndCount = 0
        val scrollEndCallback: KuiklyRenderCallback = { scrollEndCount++ }
        recyclerView.setProp("scrollEnd", scrollEndCallback)
        startRecyclerViewWithInheritedNonTouchConnection(history)

        recyclerView.call(
            "contentOffset",
            "0 $HISTORY_OUTER_OFFSET_PX 0",
            null,
        )
        val pendingSurvivedOuter = recyclerView.pendingContentOffset()
            .contains(" $PARENT_STOP_PENDING_OFFSET_PX ")

        assertEquals(
            "the callback-installed range-deferred generation must survive the older outer write",
            listOf(
                1,
                true,
                true,
                RecyclerView.SCROLL_STATE_IDLE,
                1,
                false,
                false,
                false,
                1,
                1,
            ),
            listOf(
                parentStopCallbackCount,
                callbackSawPendingOwner,
                pendingSurvivedOuter,
                recyclerView.scrollState,
                scrollEndCount,
                recyclerView.hasRunningCustomScrollAnimation(),
                recyclerView.hasActiveRecyclerViewContentOffsetOwner(),
                recyclerView.hasNestedScrollingParent(ViewCompat.TYPE_NON_TOUCH),
                nestedParent.nonTouchStartCount,
                nestedParent.nonTouchStopCount,
            ),
        )

        contentView.layoutParams.height = PARENT_STOP_READY_CONTENT_HEIGHT_PX
        layoutContentRange(contentView, PARENT_STOP_READY_CONTENT_HEIGHT_PX)
        recyclerView.retryPendingContentOffset()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2L))

        assertEquals(
            "the range-deferred callback owner must replay as the final physical winner",
            listOf(
                PARENT_STOP_PENDING_OFFSET_PX,
                RecyclerView.SCROLL_STATE_IDLE,
                1,
                false,
                false,
                false,
                1,
                1,
            ),
            listOf(
                -contentView.top,
                recyclerView.scrollState,
                scrollEndCount,
                recyclerView.hasPendingContentOffset(),
                recyclerView.hasRunningCustomScrollAnimation(),
                recyclerView.hasActiveRecyclerViewContentOffsetOwner(),
                nestedParent.nonTouchStartCount,
                nestedParent.nonTouchStopCount,
            ),
        )
        activity.finish()
    }

    @Test
    fun readyImmediateOffsetDoesNotCloseUnownedNonTouchConnection() {
        val (recyclerView, contentView) = createRecycler(FINAL_CONTENT_HEIGHT_PX)
        val nestedParent = attachNestedParentAndAcceptNonTouch(recyclerView, contentView)

        recyclerView.call(
            "contentOffset",
            "0 $PENDING_OFFSET_PX 0",
            null,
        )

        assertEquals(
            "only an active contentOffset ViewFlinger owns the inherited-close operation",
            listOf(PENDING_OFFSET_PX, true, 1, 0, false),
            listOf(
                -contentView.top,
                recyclerView.hasNestedScrollingParent(ViewCompat.TYPE_NON_TOUCH),
                nestedParent.nonTouchStartCount,
                nestedParent.nonTouchStopCount,
                recyclerView.hasActiveRecyclerViewContentOffsetOwner(),
            ),
        )

        recyclerView.stopNestedScroll(ViewCompat.TYPE_NON_TOUCH)
        assertEquals(1, nestedParent.nonTouchStopCount)
    }

    @Test
    fun abortLinearAnimationDoesNotApplyCompletionTail() {
        assertAbortCustomAnimationDoesNotApplyCompletionTail(
            animationDamping = 0f,
            animationCurve = 1,
        )
    }

    @Test
    fun abortSpringAnimationDoesNotApplyCompletionTail() {
        assertAbortCustomAnimationDoesNotApplyCompletionTail(
            animationDamping = 0.5f,
            animationCurve = 0,
        )
    }

    @Test
    fun noOpCustomReplacementCancelsOlderAnimationAndFinishesItsTransport() {
        val (recyclerView, contentView) = createRecycler(READY_CONTENT_HEIGHT_PX)
        val nestedParent = attachNestedParentAndAcceptNonTouch(recyclerView, contentView)
        var scrollEndCount = 0
        val scrollEndCallback: KuiklyRenderCallback = { scrollEndCount++ }
        recyclerView.setProp("scrollEnd", scrollEndCallback)

        recyclerView.call(
            "contentOffset",
            "0 $PENDING_OFFSET_PX 1 10000 0 0 1",
            null,
        )
        assertTrue(recyclerView.hasRunningCustomScrollAnimation())
        assertTrue(
            recyclerView.hasNestedScrollingParent(ViewCompat.TYPE_NON_TOUCH),
        )

        recyclerView.call("contentOffset", "0 0 1 10000 0 0 1", null)

        assertTrue(!recyclerView.hasRunningCustomScrollAnimation())
        assertEquals(RecyclerView.SCROLL_STATE_IDLE, recyclerView.scrollState)
        assertTrue(
            !recyclerView.hasNestedScrollingParent(ViewCompat.TYPE_NON_TOUCH),
        )
        assertEquals(1, nestedParent.nonTouchStopCount)
        assertEquals(1, scrollEndCount)
        assertEquals(0, -contentView.top)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(11000L))
        assertEquals(0, -contentView.top)
        assertEquals(1, scrollEndCount)
    }

    @Test
    fun noOpRecyclerViewReplacementCancelsOlderAnimationAndFinishesItsTransport() {
        val (recyclerView, contentView) = createRecycler(READY_CONTENT_HEIGHT_PX)
        val nestedParent = attachNestedParentAndAcceptNonTouch(recyclerView, contentView)
        var scrollEndCount = 0
        val scrollEndCallback: KuiklyRenderCallback = { scrollEndCount++ }
        recyclerView.setProp("scrollEnd", scrollEndCallback)

        recyclerView.call(
            "contentOffset",
            "0 $PENDING_OFFSET_PX 1 10000 0 0 1",
            null,
        )
        assertTrue(recyclerView.hasRunningCustomScrollAnimation())
        assertEquals(RecyclerView.SCROLL_STATE_SETTLING, recyclerView.scrollState)
        assertTrue(
            recyclerView.hasNestedScrollingParent(ViewCompat.TYPE_NON_TOUCH),
        )

        // Default RecyclerView animation with an already-current target requests no physical
        // flinger. It must still finish the canceled custom transport instead of preserving a
        // transport that never started.
        recyclerView.call("contentOffset", "0 0 1", null)

        assertEquals(
            "a requested-but-not-started RecyclerView animation must leave no old transport",
            listOf(RecyclerView.SCROLL_STATE_IDLE, false, 1, 1, false, 0),
            listOf(
                recyclerView.scrollState,
                recyclerView.hasNestedScrollingParent(ViewCompat.TYPE_NON_TOUCH),
                nestedParent.nonTouchStopCount,
                scrollEndCount,
                recyclerView.hasRunningCustomScrollAnimation(),
                -contentView.top,
            ),
        )

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(11000L))
        assertEquals(0, -contentView.top)
        assertEquals(1, scrollEndCount)
    }

    @Test
    fun suppressedRecyclerViewReplacementFinishesCanceledCustomNestedTransport() {
        val (recyclerView, contentView) = createRecycler(READY_CONTENT_HEIGHT_PX)
        val nestedParent = attachNestedParentAndAcceptNonTouch(recyclerView, contentView)
        var scrollEndCount = 0
        val scrollEndCallback: KuiklyRenderCallback = { scrollEndCount++ }
        recyclerView.setProp("scrollEnd", scrollEndCallback)

        recyclerView.call(
            "contentOffset",
            "0 $PENDING_OFFSET_PX 1 10000 0 0 1",
            null,
        )
        assertTrue(recyclerView.hasRunningCustomScrollAnimation())
        assertTrue(
            recyclerView.hasNestedScrollingParent(ViewCompat.TYPE_NON_TOUCH),
        )

        // RecyclerView suppressLayout() publishes IDLE but does not end the custom animation's
        // accepted TYPE_NON_TOUCH nesting. The suppressed replacement starts no ViewFlinger, so
        // canceling the custom manager must close that remaining transport without a second end.
        recyclerView.suppressLayout(true)
        try {
            recyclerView.call(
                "contentOffset",
                "0 $PENDING_OFFSET_PX 1",
                null,
            )

            assertEquals(
                "a layout-suppressed RecyclerView request must leave no canceled transport",
                listOf(RecyclerView.SCROLL_STATE_IDLE, false, 1, 1, false, 0),
                listOf(
                    recyclerView.scrollState,
                    recyclerView.hasNestedScrollingParent(ViewCompat.TYPE_NON_TOUCH),
                    nestedParent.nonTouchStopCount,
                    scrollEndCount,
                    recyclerView.hasRunningCustomScrollAnimation(),
                    -contentView.top,
                ),
            )
        } finally {
            recyclerView.suppressLayout(false)
        }

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(11000L))
        assertEquals(0, -contentView.top)
        assertEquals(1, scrollEndCount)
    }

    @Test
    fun reapplyingCurrentDirectionKeepsCurrentPendingOffsetOwner() {
        val (recyclerView, contentView) = createRecycler(INITIAL_CONTENT_HEIGHT_PX)
        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)
        assertTrue(recyclerView.hasPendingContentOffset())

        recyclerView.setProp("directionRow", 0)
        assertTrue(recyclerView.hasPendingContentOffset())

        advanceContentRangeAndRetry(recyclerView, contentView)
        assertEquals(PENDING_OFFSET_PX, -contentView.top)
    }

    @Test
    fun directionChangeCancelsOffsetOwnedByPreviousScrollAxis() {
        val (recyclerView, contentView) = createRecycler(INITIAL_CONTENT_HEIGHT_PX)
        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)
        assertTrue(recyclerView.hasPendingContentOffset())

        recyclerView.setProp("directionRow", 1)
        assertTrue(!recyclerView.hasPendingContentOffset())

        advanceContentRangeAndRetry(recyclerView, contentView)
        assertEquals(0, -contentView.top)
    }

    @Test
    fun composeReuseCancelsOffsetOwnedByPreviousBinding() {
        val (recyclerView, contentView) = createRecycler(INITIAL_CONTENT_HEIGHT_PX)
        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)
        assertTrue(recyclerView.hasPendingContentOffset())

        recyclerView.call("prepareForComposeReuse", null, null)
        assertTrue(!recyclerView.hasPendingContentOffset())

        advanceContentRangeAndRetry(recyclerView, contentView)
        assertEquals(0, -contentView.top)
    }

    @Test
    fun abortCancelsProgrammaticOffsetStillWaitingForRange() {
        val (recyclerView, contentView) = createRecycler(INITIAL_CONTENT_HEIGHT_PX)
        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 1", null)
        assertTrue(recyclerView.hasPendingContentOffset())

        recyclerView.call("abortContentOffsetAnimate", null, null)
        assertTrue(!recyclerView.hasPendingContentOffset())

        advanceContentRangeAndRetry(recyclerView, contentView)
        assertEquals(0, -contentView.top)
    }

    @Test
    fun physicalDragCancelsProgrammaticOffsetStillWaitingForRange() {
        val (recyclerView, contentView) = createRecycler(INITIAL_CONTENT_HEIGHT_PX)
        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)
        assertTrue(recyclerView.hasPendingContentOffset())

        recyclerView.javaClass.getDeclaredMethod("fireBeginDragEvent").apply {
            isAccessible = true
            invoke(recyclerView)
        }
        assertTrue(!recyclerView.hasPendingContentOffset())

        advanceContentRangeAndRetry(recyclerView, contentView)
        assertEquals(0, -contentView.top)
    }

    @Test
    fun physicalOverScrollDragWithoutCallbackCancelsPendingProgrammaticOffset() {
        val (recyclerView, contentView) = createRecycler(INITIAL_CONTENT_HEIGHT_PX)
        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)
        assertTrue(recyclerView.hasPendingContentOffset())

        recyclerView.javaClass.getDeclaredMethod(
            "fireOverScrollBeginDragEvent",
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        ).apply {
            isAccessible = true
            invoke(recyclerView, 0f, 0f, true)
        }
        assertTrue(!recyclerView.hasPendingContentOffset())

        advanceContentRangeAndRetry(recyclerView, contentView)
        assertEquals(0, -contentView.top)
    }

    @Test
    fun nestedPhysicalDragCancelsProgrammaticOffsetWaitingOnParentRange() {
        val (recyclerView, contentView) = createRecycler(INITIAL_CONTENT_HEIGHT_PX)
        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)
        assertTrue(recyclerView.hasPendingContentOffset())

        recyclerView.onNestedPreScroll(
            contentView,
            0,
            1,
            intArrayOf(0, 0),
            ViewCompat.TYPE_TOUCH,
        )
        assertTrue(!recyclerView.hasPendingContentOffset())

        advanceContentRangeAndRetry(recyclerView, contentView)
        assertEquals(0, -contentView.top)
    }

    @Test
    fun consumedNonTouchNestedMovementCancelsPendingOffsetOwner() {
        val (recyclerView, contentView) = createRecycler(INITIAL_CONTENT_HEIGHT_PX)
        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)
        assertTrue(recyclerView.hasPendingContentOffset())

        recyclerView.onNestedScroll(
            contentView,
            0,
            1,
            0,
            0,
            ViewCompat.TYPE_NON_TOUCH,
        )
        assertTrue(!recyclerView.hasPendingContentOffset())

        advanceContentRangeAndRetry(recyclerView, contentView)
        assertEquals(0, -contentView.top)
    }

    @Test
    fun unconsumedNonTouchNestedRequestDoesNotCancelPendingOffsetOwner() {
        val (recyclerView, contentView) = createRecycler(INITIAL_CONTENT_HEIGHT_PX)
        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)
        assertTrue(recyclerView.hasPendingContentOffset())

        recyclerView.onNestedPreScroll(
            contentView,
            0,
            1,
            intArrayOf(0, 0),
            ViewCompat.TYPE_NON_TOUCH,
        )
        assertTrue(recyclerView.hasPendingContentOffset())

        advanceContentRangeAndRetry(recyclerView, contentView)
        assertEquals(PENDING_OFFSET_PX, -contentView.top)
    }

    @Test
    fun acceptedAccessibilityScrollCancelsPendingOffsetOwner() {
        val (recyclerView, _) = createRecycler(PARTIALLY_SCROLLABLE_CONTENT_HEIGHT_PX)
        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)
        assertTrue(recyclerView.hasPendingContentOffset())

        assertTrue(
            recyclerView.performAccessibilityAction(
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                null,
            ),
        )
        assertTrue(!recyclerView.hasPendingContentOffset())
    }

    @Test
    fun rejectedAccessibilityScrollKeepsPendingOffsetOwner() {
        val (recyclerView, contentView) = createRecycler(VIEWPORT_HEIGHT_PX)
        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)
        assertTrue(recyclerView.hasPendingContentOffset())

        assertTrue(
            !recyclerView.performAccessibilityAction(
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                null,
            ),
        )
        assertTrue(recyclerView.hasPendingContentOffset())

        advanceContentRangeAndRetry(recyclerView, contentView)
        assertEquals(PENDING_OFFSET_PX, -contentView.top)
    }

    @Test
    fun accessibilityScrollDoesNotEraseNewOwnerInstalledReentrantlyByStateCallback() {
        val (recyclerView, _) = createRecycler(PARTIALLY_SCROLLABLE_CONTENT_HEIGHT_PX)
        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)
        assertTrue(recyclerView.hasPendingContentOffset())

        var settlingCallbackCount = 0
        recyclerView.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(view: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_SETTLING) {
                        settlingCallbackCount++
                        (view as KRRecyclerView).call(
                            "contentOffset",
                            "0 $REENTRANT_PENDING_OFFSET_PX 0",
                            null,
                        )
                    }
                }
            },
        )

        assertTrue(
            recyclerView.performAccessibilityAction(
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                null,
            ),
        )
        assertEquals(1, settlingCallbackCount)
        assertTrue(
            recyclerView.pendingContentOffset().contains(" $REENTRANT_PENDING_OFFSET_PX "),
        )
    }

    @Test
    fun directNativeScrollCancelsPendingOffsetOwner() {
        val (recyclerView, contentView) = createRecycler(PARTIALLY_SCROLLABLE_CONTENT_HEIGHT_PX)
        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)
        assertTrue(recyclerView.hasPendingContentOffset())

        recyclerView.scrollBy(0, 1)

        assertEquals(1, -contentView.top)
        assertTrue(!recyclerView.hasPendingContentOffset())
    }

    @Test
    fun zeroDistanceNativeScrollKeepsPendingOffsetOwner() {
        val (recyclerView, contentView) = createRecycler(INITIAL_CONTENT_HEIGHT_PX)
        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)
        assertTrue(recyclerView.hasPendingContentOffset())

        recyclerView.scrollBy(0, 0)
        assertTrue(recyclerView.hasPendingContentOffset())

        advanceContentRangeAndRetry(recyclerView, contentView)
        assertEquals(PENDING_OFFSET_PX, -contentView.top)
    }

    @Test
    fun pagerSnapHelperSmoothScrollWithoutTouchCancelsPendingOffsetOwner() {
        val (recyclerView, contentView) = createRecycler(PARTIALLY_SCROLLABLE_CONTENT_HEIGHT_PX)
        val page = View(RuntimeEnvironment.getApplication()).apply {
            layoutParams = ViewGroup.LayoutParams(VIEWPORT_WIDTH_PX, VIEWPORT_HEIGHT_PX)
        }
        contentView.addView(page)
        page.measure(
            View.MeasureSpec.makeMeasureSpec(VIEWPORT_WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(VIEWPORT_HEIGHT_PX, View.MeasureSpec.EXACTLY),
        )
        page.layout(
            0,
            VIEWPORT_HEIGHT_PX,
            VIEWPORT_WIDTH_PX,
            VIEWPORT_HEIGHT_PX * 2,
        )
        val snapHelper = KRPagerSnapHelper {}.apply {
            attachToRecyclerView(recyclerView)
        }

        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)
        assertTrue(recyclerView.hasPendingContentOffset())
        assertTrue(snapHelper.snapFromFling(0, recyclerView.minFlingVelocity + 1))
        assertEquals(RecyclerView.SCROLL_STATE_SETTLING, recyclerView.scrollState)
        assertTrue(!recyclerView.hasPendingContentOffset())
    }

    @Test
    fun directFlingWithoutTouchCancelsPendingOffsetOwner() {
        val (recyclerView, _) = createRecycler(PARTIALLY_SCROLLABLE_CONTENT_HEIGHT_PX)
        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)
        assertTrue(recyclerView.hasPendingContentOffset())

        assertTrue(recyclerView.fling(0, recyclerView.minFlingVelocity + 1))
        assertTrue(!recyclerView.hasPendingContentOffset())
    }

    @Test
    fun flingDoesNotEraseOwnerInstalledByWillEndDragCallback() {
        val (recyclerView, _) = createRecycler(PARTIALLY_SCROLLABLE_CONTENT_HEIGHT_PX)
        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)
        assertTrue(recyclerView.hasPendingContentOffset())
        val willEndDragCallback: KuiklyRenderCallback = {
            recyclerView.call("contentOffset", "0 $CALLBACK_PENDING_OFFSET_PX 0", null)
        }
        recyclerView.setProp("willDragEnd", willEndDragCallback)

        assertTrue(recyclerView.fling(0, recyclerView.minFlingVelocity + 1))
        assertTrue(
            recyclerView.pendingContentOffset().contains(" $CALLBACK_PENDING_OFFSET_PX "),
        )
    }

    @Test
    fun directSmoothScrollToPositionCancelsPendingOffsetOwner() {
        val (recyclerView, _) = createRecycler(INITIAL_CONTENT_HEIGHT_PX)
        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)
        assertTrue(recyclerView.hasPendingContentOffset())

        recyclerView.smoothScrollToPosition(0)
        assertTrue(!recyclerView.hasPendingContentOffset())
    }

    @Test
    fun directScrollToPositionCancelsPendingOffsetOwner() {
        val (recyclerView, _) = createRecycler(INITIAL_CONTENT_HEIGHT_PX)
        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)
        assertTrue(recyclerView.hasPendingContentOffset())

        recyclerView.scrollToPosition(0)
        assertTrue(!recyclerView.hasPendingContentOffset())
    }

    @Test
    fun mouseWheelScrollCancelsPendingOffsetOwner() {
        val (recyclerView, contentView) = createRecycler(PARTIALLY_SCROLLABLE_CONTENT_HEIGHT_PX)
        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)
        assertTrue(recyclerView.hasPendingContentOffset())

        genericScrollEvent(MotionEvent.AXIS_VSCROLL, -1f).also { event ->
            recyclerView.onGenericMotionEvent(event)
            event.recycle()
        }

        assertTrue(-contentView.top > 0)
        assertTrue(!recyclerView.hasPendingContentOffset())
    }

    @Test
    fun zeroAxisGenericScrollKeepsPendingOffsetOwner() {
        val (recyclerView, contentView) = createRecycler(INITIAL_CONTENT_HEIGHT_PX)
        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)
        assertTrue(recyclerView.hasPendingContentOffset())

        genericScrollEvent(MotionEvent.AXIS_VSCROLL, 0f).also { event ->
            recyclerView.onGenericMotionEvent(event)
            event.recycle()
        }
        assertTrue(recyclerView.hasPendingContentOffset())

        advanceContentRangeAndRetry(recyclerView, contentView)
        assertEquals(PENDING_OFFSET_PX, -contentView.top)
    }

    @Test
    fun disabledGenericScrollKeepsPendingOffsetOwner() {
        val (recyclerView, contentView) = createRecycler(PARTIALLY_SCROLLABLE_CONTENT_HEIGHT_PX)
        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)
        recyclerView.setProp("scrollEnabled", 0)
        assertTrue(recyclerView.hasPendingContentOffset())

        genericScrollEvent(MotionEvent.AXIS_VSCROLL, -1f).also { event ->
            recyclerView.onGenericMotionEvent(event)
            event.recycle()
        }
        assertEquals(0, -contentView.top)
        assertTrue(recyclerView.hasPendingContentOffset())
    }

    @Test
    fun acceptedChildRectangleScrollCancelsPendingOffsetOwner() {
        val (recyclerView, contentView) = createRecycler(PARTIALLY_SCROLLABLE_CONTENT_HEIGHT_PX)
        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)
        assertTrue(recyclerView.hasPendingContentOffset())

        assertTrue(
            recyclerView.requestChildRectangleOnScreen(
                contentView,
                Rect(0, PENDING_OFFSET_PX, 1, PENDING_OFFSET_PX + 1),
                true,
            ),
        )
        assertTrue(-contentView.top > 0)
        assertTrue(!recyclerView.hasPendingContentOffset())
    }

    @Test
    fun childRectangleScrollDoesNotEraseNewOwnerInstalledReentrantlyByScrollCallback() {
        val (recyclerView, contentView) = createRecycler(PARTIALLY_SCROLLABLE_CONTENT_HEIGHT_PX)
        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)
        assertTrue(recyclerView.hasPendingContentOffset())

        var callbackCount = 0
        val scrollCallback: KuiklyRenderCallback = {
            callbackCount++
            recyclerView.call("contentOffset", "0 $REENTRANT_PENDING_OFFSET_PX 0", null)
        }
        recyclerView.setProp("scroll", scrollCallback)

        assertTrue(
            recyclerView.requestChildRectangleOnScreen(
                contentView,
                Rect(0, PENDING_OFFSET_PX, 1, PENDING_OFFSET_PX + 1),
                true,
            ),
        )
        assertEquals(1, callbackCount)
        assertTrue(-contentView.top > 0)
        assertTrue(
            recyclerView.pendingContentOffset().contains(" $REENTRANT_PENDING_OFFSET_PX "),
        )
    }

    @Test
    fun childRectangleScrollDoesNotEraseSameTargetReinstalledByScrollCallback() {
        val (recyclerView, contentView) = createRecycler(PARTIALLY_SCROLLABLE_CONTENT_HEIGHT_PX)
        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)
        assertTrue(recyclerView.hasPendingContentOffset())

        var callbackCount = 0
        val scrollCallback: KuiklyRenderCallback = {
            callbackCount++
            recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)
        }
        recyclerView.setProp("scroll", scrollCallback)

        assertTrue(
            recyclerView.requestChildRectangleOnScreen(
                contentView,
                Rect(0, PENDING_OFFSET_PX, 1, PENDING_OFFSET_PX + 1),
                true,
            ),
        )
        assertEquals(1, callbackCount)
        assertTrue(-contentView.top > 0)
        assertTrue(recyclerView.pendingContentOffset().contains(" $PENDING_OFFSET_PX "))
    }

    @Test
    fun acceptedSmoothChildRectangleScrollCancelsPendingOffsetOwner() {
        val (recyclerView, contentView) = createRecycler(PARTIALLY_SCROLLABLE_CONTENT_HEIGHT_PX)
        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)
        assertTrue(recyclerView.hasPendingContentOffset())

        assertTrue(
            recyclerView.requestChildRectangleOnScreen(
                contentView,
                Rect(0, PENDING_OFFSET_PX, 1, PENDING_OFFSET_PX + 1),
                false,
            ),
        )
        assertTrue(!recyclerView.hasPendingContentOffset())
    }

    @Test
    fun smoothChildRectangleScrollDoesNotEraseNewOwnerInstalledReentrantlyByStateCallback() {
        val (recyclerView, contentView) = createRecycler(PARTIALLY_SCROLLABLE_CONTENT_HEIGHT_PX)
        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)
        assertTrue(recyclerView.hasPendingContentOffset())

        var settlingCallbackCount = 0
        recyclerView.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(view: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_SETTLING) {
                        settlingCallbackCount++
                        (view as KRRecyclerView).call(
                            "contentOffset",
                            "0 $REENTRANT_PENDING_OFFSET_PX 0",
                            null,
                        )
                    }
                }
            },
        )

        assertTrue(
            recyclerView.requestChildRectangleOnScreen(
                contentView,
                Rect(0, PENDING_OFFSET_PX, 1, PENDING_OFFSET_PX + 1),
                false,
            ),
        )
        assertEquals(1, settlingCallbackCount)
        assertTrue(
            recyclerView.pendingContentOffset().contains(" $REENTRANT_PENDING_OFFSET_PX "),
        )
    }

    @Test
    fun visibleChildRectangleRequestKeepsPendingOffsetOwner() {
        val (recyclerView, contentView) = createRecycler(PARTIALLY_SCROLLABLE_CONTENT_HEIGHT_PX)
        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)
        assertTrue(recyclerView.hasPendingContentOffset())

        assertTrue(
            !recyclerView.requestChildRectangleOnScreen(
                contentView,
                Rect(0, 0, 1, 1),
                true,
            ),
        )
        assertEquals(0, -contentView.top)
        assertTrue(recyclerView.hasPendingContentOffset())
    }

    @Test
    fun settlingStateWithoutNewMotionKeepsPendingOffsetOwner() {
        val (recyclerView, contentView) = createRecycler(INITIAL_CONTENT_HEIGHT_PX)
        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)
        assertTrue(recyclerView.hasPendingContentOffset())
        val scrollCallback: KuiklyRenderCallback = {}
        recyclerView.setProp("scroll", scrollCallback)

        recyclerView.javaClass.getDeclaredMethod(
            "forceSetScrollState",
            Int::class.javaPrimitiveType,
        ).apply {
            isAccessible = true
            invoke(recyclerView, RecyclerView.SCROLL_STATE_SETTLING)
        }
        assertTrue(recyclerView.hasPendingContentOffset())

        advanceContentRangeAndRetry(recyclerView, contentView)
        assertEquals(PENDING_OFFSET_PX, -contentView.top)
    }

    @Test
    fun destroyCancelsOffsetOwnedByDepartedPage() {
        val (recyclerView, contentView) = createRecycler(INITIAL_CONTENT_HEIGHT_PX)
        recyclerView.call("contentOffset", "0 $PENDING_OFFSET_PX 0", null)
        assertTrue(recyclerView.hasPendingContentOffset())

        recyclerView.onDestroy()
        assertTrue(!recyclerView.hasPendingContentOffset())

        advanceContentRangeAndRetry(recyclerView, contentView)
        assertEquals(0, -contentView.top)
    }

    private fun createRecycler(contentHeightPx: Int): Pair<KRRecyclerView, KRRecyclerContentView> {
        val recyclerView = KRRecyclerView(RuntimeEnvironment.getApplication())
        val contentView = KRRecyclerContentView(RuntimeEnvironment.getApplication()).apply {
            layoutParams = RecyclerView.LayoutParams(VIEWPORT_WIDTH_PX, contentHeightPx)
        }
        recyclerView.addView(contentView)
        layoutRecycler(recyclerView)
        return recyclerView to contentView
    }

    private fun createAttachedRecycler(): Triple<Activity, KRRecyclerView, KRRecyclerContentView> {
        val (recyclerView, contentView) = createRecycler(FINAL_CONTENT_HEIGHT_PX)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(recyclerView)
        layoutRecycler(recyclerView)
        return Triple(activity, recyclerView, contentView)
    }

    private fun startDefaultRecyclerViewContentOffsetAnimation(recyclerView: KRRecyclerView) {
        recyclerView.call(
            "contentOffset",
            "0 $PENDING_OFFSET_PX 1",
            null,
        )
        assertEquals(
            "the fixture must start a real RecyclerView ViewFlinger",
            RecyclerView.SCROLL_STATE_SETTLING,
            recyclerView.scrollState,
        )
        assertTrue(
            "the fixture must bind the running ViewFlinger to its opaque contentOffset owner",
            recyclerView.hasActiveRecyclerViewContentOffsetOwner(),
        )
    }

    private data class InheritedNonTouchRecyclerViewHistory(
        val activity: Activity,
        val recyclerView: KRRecyclerView,
        val contentView: KRRecyclerContentView,
        val nestedParent: RecordingNestedParent,
    )

    private fun createInheritedNonTouchRecyclerViewHistory(
        onNonTouchStop: ((View) -> Unit)? = null,
    ):
        InheritedNonTouchRecyclerViewHistory {
        val (recyclerView, contentView) = createRecycler(FINAL_CONTENT_HEIGHT_PX)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val nestedParent = attachNestedParentAndAcceptNonTouch(
            recyclerView,
            contentView,
            activity,
            onNonTouchStop,
        )
        return InheritedNonTouchRecyclerViewHistory(
            activity,
            recyclerView,
            contentView,
            nestedParent,
        )
    }

    private fun startRecyclerViewWithInheritedNonTouchConnection(
        history: InheritedNonTouchRecyclerViewHistory,
    ) {
        val recyclerView = history.recyclerView
        val nestedParent = history.nestedParent
        recyclerView.call(
            "contentOffset",
            "0 $PENDING_OFFSET_PX 1 10000 0 0 1",
            null,
        )
        assertEquals(
            "the history must begin with a real custom owner and one accepted connection",
            listOf(true, true, 1, 0),
            listOf(
                recyclerView.hasRunningCustomScrollAnimation(),
                recyclerView.hasNestedScrollingParent(ViewCompat.TYPE_NON_TOUCH),
                nestedParent.nonTouchStartCount,
                nestedParent.nonTouchStopCount,
            ),
        )

        recyclerView.call(
            "contentOffset",
            "0 $REENTRANT_PENDING_OFFSET_PX 1",
            null,
        )
        assertEquals(
            "the genuine ViewFlinger must inherit the custom owner's open connection",
            listOf(
                false,
                RecyclerView.SCROLL_STATE_SETTLING,
                true,
                true,
                1,
                0,
            ),
            listOf(
                recyclerView.hasRunningCustomScrollAnimation(),
                recyclerView.scrollState,
                recyclerView.hasActiveRecyclerViewContentOffsetOwner(),
                recyclerView.hasNestedScrollingParent(ViewCompat.TYPE_NON_TOUCH),
                nestedParent.nonTouchStartCount,
                nestedParent.nonTouchStopCount,
            ),
        )
    }

    private fun assertInheritedNonTouchIsClosedBeforeIdleCallbackInstallsNewTransport(
        callbackOffset: String,
        expectedStartCountAfterCallback: Int,
        expectedFinalStopCount: Int,
        expectedCustomRunningAfterCallback: Boolean,
        expectedActiveRecyclerViewAfterCallback: Boolean,
        expectedNestedAfterCallback: Boolean,
        quiescenceDuration: Duration,
    ) {
        val history = createInheritedNonTouchRecyclerViewHistory()
        val (activity, recyclerView, contentView, nestedParent) = history
        var scrollEndCount = 0
        val scrollEndCallback: KuiklyRenderCallback = { scrollEndCount++ }
        recyclerView.setProp("scrollEnd", scrollEndCallback)
        startRecyclerViewWithInheritedNonTouchConnection(history)

        var idleTakeoverCount = 0
        var inheritedStateBeforeCallbackInstall: List<Any>? = null
        var startCountAfterCallbackInstall = -1
        recyclerView.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(view: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE &&
                        idleTakeoverCount == 0
                    ) {
                        idleTakeoverCount++
                        val currentRecyclerView = view as KRRecyclerView
                        // Record inside the callback, before the new owner is installed. Some
                        // RecyclerView listener dispatchers isolate callback exceptions, so assert
                        // the recorded production state after the outer call returns.
                        inheritedStateBeforeCallbackInstall = listOf(
                            nestedParent.nonTouchStartCount,
                            nestedParent.nonTouchStopCount,
                            currentRecyclerView.hasNestedScrollingParent(
                                ViewCompat.TYPE_NON_TOUCH,
                            ),
                        )
                        currentRecyclerView.call(
                            "contentOffset",
                            callbackOffset,
                            null,
                        )
                        startCountAfterCallbackInstall = nestedParent.nonTouchStartCount
                    }
                }
            },
        )

        recyclerView.call(
            "contentOffset",
            "0 $HISTORY_OUTER_OFFSET_PX 0",
            null,
        )

        assertEquals(1, idleTakeoverCount)
        assertEquals(
            "old nesting must close before IDLE and outer cleanup must preserve the new owner",
            listOf(
                listOf(1, 1, false),
                expectedStartCountAfterCallback,
                RecyclerView.SCROLL_STATE_SETTLING,
                expectedCustomRunningAfterCallback,
                expectedActiveRecyclerViewAfterCallback,
                expectedNestedAfterCallback,
            ),
            listOf(
                inheritedStateBeforeCallbackInstall,
                startCountAfterCallbackInstall,
                recyclerView.scrollState,
                recyclerView.hasRunningCustomScrollAnimation(),
                recyclerView.hasActiveRecyclerViewContentOffsetOwner(),
                recyclerView.hasNestedScrollingParent(ViewCompat.TYPE_NON_TOUCH),
            ),
        )

        shadowOf(Looper.getMainLooper()).idleFor(quiescenceDuration)

        assertEquals(
            "outer cleanup must not stop the callback-installed newest transport",
            listOf(
                CALLBACK_FINAL_OFFSET_PX,
                RecyclerView.SCROLL_STATE_IDLE,
                1,
                false,
                false,
                false,
                expectedStartCountAfterCallback,
                expectedFinalStopCount,
            ),
            listOf(
                -contentView.top,
                recyclerView.scrollState,
                scrollEndCount,
                recyclerView.hasRunningCustomScrollAnimation(),
                recyclerView.hasActiveRecyclerViewContentOffsetOwner(),
                recyclerView.hasNestedScrollingParent(ViewCompat.TYPE_NON_TOUCH),
                nestedParent.nonTouchStartCount,
                nestedParent.nonTouchStopCount,
            ),
        )
        activity.finish()
    }

    private fun assertInheritedParentStopCallbackOffsetSupersedesOuterTakeover(
        callbackOffset: String,
        expectedStateInsideParentCallback: List<Any>,
        expectedStateAfterCallback: List<Any>,
        expectedFinalStartCount: Int,
        expectedFinalStopCount: Int,
        quiescenceDuration: Duration,
    ) {
        var parentStopCallbackCount = 0
        var parentStateBeforeInstall: List<Any>? = null
        var stateInsideParentCallback: List<Any>? = null
        lateinit var nestedParent: RecordingNestedParent
        val history = createInheritedNonTouchRecyclerViewHistory { target ->
            if (parentStopCallbackCount == 0) {
                parentStopCallbackCount++
                val currentRecyclerView = target as KRRecyclerView
                parentStateBeforeInstall = listOf(
                    currentRecyclerView.scrollState,
                    currentRecyclerView.hasNestedScrollingParent(ViewCompat.TYPE_NON_TOUCH),
                    nestedParent.nonTouchStartCount,
                    nestedParent.nonTouchStopCount,
                )
                currentRecyclerView.call(
                    "contentOffset",
                    callbackOffset,
                    null,
                )
                stateInsideParentCallback = listOf(
                    currentRecyclerView.scrollState,
                    currentRecyclerView.hasRunningCustomScrollAnimation(),
                    currentRecyclerView.hasActiveRecyclerViewContentOffsetOwner(),
                    currentRecyclerView.hasNestedScrollingParent(ViewCompat.TYPE_NON_TOUCH),
                    nestedParent.nonTouchStartCount,
                    nestedParent.nonTouchStopCount,
                )
            }
        }
        val (activity, recyclerView, contentView, historyNestedParent) = history
        nestedParent = historyNestedParent
        var scrollEndCount = 0
        val scrollEndCallback: KuiklyRenderCallback = { scrollEndCount++ }
        recyclerView.setProp("scrollEnd", scrollEndCallback)
        startRecyclerViewWithInheritedNonTouchConnection(history)

        // Closing the inherited connection calls the parent before NestedScrollingChildHelper
        // clears its old parent pointer. The parent installs a newer transport in that exact
        // window; the older outer immediate takeover must not publish IDLE or write afterward.
        recyclerView.call(
            "contentOffset",
            "0 $HISTORY_OUTER_OFFSET_PX 0",
            null,
        )

        assertEquals(1, parentStopCallbackCount)
        assertEquals(
            "child helper must still expose the doomed parent pointer inside parent stop",
            listOf(RecyclerView.SCROLL_STATE_SETTLING, true, 1, 1),
            parentStateBeforeInstall,
        )
        assertEquals(
            "callback-installed transport initially sees the not-yet-cleared old parent pointer",
            expectedStateInsideParentCallback,
            stateInsideParentCallback,
        )
        assertEquals(
            "parent-stop callback owner must survive outer forced-IDLE and own fresh transport",
            expectedStateAfterCallback,
            listOf(
                recyclerView.scrollState,
                recyclerView.hasRunningCustomScrollAnimation(),
                recyclerView.hasActiveRecyclerViewContentOffsetOwner(),
                recyclerView.hasNestedScrollingParent(ViewCompat.TYPE_NON_TOUCH),
                nestedParent.nonTouchStartCount,
                nestedParent.nonTouchStopCount,
            ),
        )

        shadowOf(Looper.getMainLooper()).idleFor(quiescenceDuration)

        assertEquals(
            "the parent-stop callback owner must remain the final winner through quiescence",
            listOf(
                CALLBACK_FINAL_OFFSET_PX,
                RecyclerView.SCROLL_STATE_IDLE,
                1,
                false,
                false,
                false,
                expectedFinalStartCount,
                expectedFinalStopCount,
            ),
            listOf(
                -contentView.top,
                recyclerView.scrollState,
                scrollEndCount,
                recyclerView.hasRunningCustomScrollAnimation(),
                recyclerView.hasActiveRecyclerViewContentOffsetOwner(),
                recyclerView.hasNestedScrollingParent(ViewCompat.TYPE_NON_TOUCH),
                nestedParent.nonTouchStartCount,
                nestedParent.nonTouchStopCount,
            ),
        )
        activity.finish()
    }

    private fun assertStopCallbackOffsetSupersedesOuterTakeover(
        callbackOffset: String,
        assertTransportStarted: (KRRecyclerView) -> Unit,
    ) {
        val (activity, recyclerView, contentView) = createAttachedRecycler()
        var scrollEndCount = 0
        val scrollEndCallback: KuiklyRenderCallback = { scrollEndCount++ }
        recyclerView.setProp("scrollEnd", scrollEndCallback)
        var idleTakeoverCount = 0
        recyclerView.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(view: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE && idleTakeoverCount == 0) {
                        idleTakeoverCount++
                        (view as KRRecyclerView).call(
                            "contentOffset",
                            callbackOffset,
                            null,
                        )
                    }
                }
            },
        )

        startDefaultRecyclerViewContentOffsetAnimation(recyclerView)

        // The middle immediate takeover must stop the old ViewFlinger. Its synchronous IDLE
        // callback installs an even newer transport, so the middle outer call must revalidate its
        // opaque owner and abort before writing its own target.
        recyclerView.call(
            "contentOffset",
            "0 $REENTRANT_PENDING_OFFSET_PX 0",
            null,
        )

        assertEquals(
            "stopping the old ViewFlinger must synchronously expose the IDLE callback",
            1,
            idleTakeoverCount,
        )
        assertTransportStarted(recyclerView)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2L))

        assertEquals(
            "the callback-installed newest transport must survive outer cleanup and win",
            listOf(CALLBACK_FINAL_OFFSET_PX, RecyclerView.SCROLL_STATE_IDLE, 1, false),
            listOf(
                -contentView.top,
                recyclerView.scrollState,
                scrollEndCount,
                recyclerView.hasActiveRecyclerViewContentOffsetOwner(),
            ),
        )
        assertTrue(!recyclerView.hasRunningCustomScrollAnimation())
        activity.finish()
    }

    private fun assertCustomAnimationFrameDoesNotEraseNewOwner(
        animationDamping: Float,
        animationCurve: Int,
    ) {
        val (recyclerView, contentView) = createRecycler(READY_CONTENT_HEIGHT_PX)
        var settlingCallbackCount = 0
        recyclerView.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(view: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_SETTLING) {
                        settlingCallbackCount++
                        (view as KRRecyclerView).call(
                            "contentOffset",
                            "0 $REENTRANT_PENDING_OFFSET_PX 0",
                            null,
                        )
                    }
                }
            },
        )

        recyclerView.call(
            "contentOffset",
            "0 $PENDING_OFFSET_PX 1 200 $animationDamping 0 $animationCurve",
            null,
        )
        assertEquals(1, settlingCallbackCount)
        assertTrue(
            "the callback-installed range-deferred owner must exist before animation frames run",
            recyclerView.pendingContentOffset().contains(" $REENTRANT_PENDING_OFFSET_PX "),
        )

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50L))

        assertTrue(
            "the fixture must execute a real custom-animation frame",
            -contentView.top > 0,
        )
        assertTrue(
            "an older custom-animation frame must not consume the callback-installed owner",
            recyclerView.pendingContentOffset().contains(" $REENTRANT_PENDING_OFFSET_PX "),
        )
    }

    private fun assertDeferredOffsetRemainsFinalWinnerAfterCustomAnimationQuiesces(
        animationDamping: Float,
        animationCurve: Int,
    ) {
        val (recyclerView, contentView) = createRecycler(READY_CONTENT_HEIGHT_PX)
        var scrollEndCount = 0
        val scrollEndCallback: KuiklyRenderCallback = { scrollEndCount++ }
        recyclerView.setProp("scrollEnd", scrollEndCallback)
        var settlingCallbackCount = 0
        recyclerView.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(view: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_SETTLING) {
                        settlingCallbackCount++
                        (view as KRRecyclerView).call(
                            "contentOffset",
                            "0 $REENTRANT_PENDING_OFFSET_PX 0",
                            null,
                        )
                    }
                }
            },
        )

        recyclerView.call(
            "contentOffset",
            "0 $PENDING_OFFSET_PX 1 200 $animationDamping 0 $animationCurve",
            null,
        )
        assertEquals(1, settlingCallbackCount)
        assertTrue(recyclerView.hasRunningCustomScrollAnimation())
        assertTrue(
            recyclerView.pendingContentOffset().contains(" $REENTRANT_PENDING_OFFSET_PX "),
        )

        layoutContentRange(contentView, FINAL_CONTENT_HEIGHT_PX)
        recyclerView.retryPendingContentOffset()

        assertEquals(REENTRANT_PENDING_OFFSET_PX, -contentView.top)
        assertTrue(!recyclerView.hasPendingContentOffset())

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300L))

        assertEquals(
            "the newer intent must remain the final physical winner after every old frame ends",
            REENTRANT_PENDING_OFFSET_PX,
            -contentView.top,
        )
        assertTrue(!recyclerView.hasRunningCustomScrollAnimation())
        assertTrue(!recyclerView.hasPendingContentOffset())
        assertEquals(1, scrollEndCount)
    }

    private fun assertAbortCustomAnimationDoesNotApplyCompletionTail(
        animationDamping: Float,
        animationCurve: Int,
    ) {
        val (recyclerView, contentView) = createRecycler(READY_CONTENT_HEIGHT_PX)
        val nestedParent = attachNestedParentAndAcceptNonTouch(recyclerView, contentView)
        var scrollEndCount = 0
        val scrollEndCallback: KuiklyRenderCallback = { scrollEndCount++ }
        recyclerView.setProp("scrollEnd", scrollEndCallback)
        recyclerView.call(
            "contentOffset",
            "0 $PENDING_OFFSET_PX 1 10000 $animationDamping 0 $animationCurve",
            null,
        )
        assertTrue(recyclerView.hasRunningCustomScrollAnimation())
        val offsetAtAbort = -contentView.top

        recyclerView.call("abortContentOffsetAnimate", null, null)
        assertTrue(!recyclerView.hasRunningCustomScrollAnimation())
        assertEquals(RecyclerView.SCROLL_STATE_IDLE, recyclerView.scrollState)
        assertTrue(
            !recyclerView.hasNestedScrollingParent(ViewCompat.TYPE_NON_TOUCH),
        )
        assertEquals(1, nestedParent.nonTouchStopCount)
        assertEquals(1, scrollEndCount)
        assertEquals(
            "cancel must stop at the current position instead of applying the completion tail",
            offsetAtAbort,
            -contentView.top,
        )

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(11000L))
        assertEquals(offsetAtAbort, -contentView.top)
        assertEquals(1, scrollEndCount)
    }

    private fun assertReadyOffsetInstalledBySettlingCallbackRemainsFinalWinner(
        animationDamping: Float,
        animationCurve: Int,
    ) {
        val (recyclerView, contentView) = createRecycler(FINAL_CONTENT_HEIGHT_PX)
        var scrollEndCount = 0
        val scrollEndCallback: KuiklyRenderCallback = { scrollEndCount++ }
        recyclerView.setProp("scrollEnd", scrollEndCallback)
        var settlingCallbackCount = 0
        recyclerView.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(view: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_SETTLING) {
                        settlingCallbackCount++
                        (view as KRRecyclerView).call(
                            "contentOffset",
                            "0 $REENTRANT_PENDING_OFFSET_PX 0",
                            null,
                        )
                    }
                }
            },
        )

        recyclerView.call(
            "contentOffset",
            "0 $PENDING_OFFSET_PX 1 200 $animationDamping 0 $animationCurve",
            null,
        )
        assertEquals(1, settlingCallbackCount)
        assertEquals(REENTRANT_PENDING_OFFSET_PX, -contentView.top)
        assertTrue(!recyclerView.hasPendingContentOffset())

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300L))

        assertEquals(REENTRANT_PENDING_OFFSET_PX, -contentView.top)
        assertTrue(!recyclerView.hasRunningCustomScrollAnimation())
        assertEquals(1, scrollEndCount)
    }

    private fun attachNestedParentAndAcceptNonTouch(
        recyclerView: KRRecyclerView,
        contentView: KRRecyclerContentView,
        activity: Activity? = null,
        onNonTouchStop: ((View) -> Unit)? = null,
    ): RecordingNestedParent {
        val nestedParent = RecordingNestedParent(
            activity ?: RuntimeEnvironment.getApplication(),
            onNonTouchStop,
        ).apply {
            addView(
                recyclerView,
                ViewGroup.LayoutParams(VIEWPORT_WIDTH_PX, VIEWPORT_HEIGHT_PX),
            )
            activity?.setContentView(this)
            measure(
                View.MeasureSpec.makeMeasureSpec(VIEWPORT_WIDTH_PX, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(VIEWPORT_HEIGHT_PX, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, VIEWPORT_WIDTH_PX, VIEWPORT_HEIGHT_PX)
        }
        assertTrue(
            recyclerView.onStartNestedScroll(
                contentView,
                contentView,
                ViewCompat.SCROLL_AXIS_VERTICAL,
                ViewCompat.TYPE_NON_TOUCH,
            ),
        )
        recyclerView.onNestedScrollAccepted(
            contentView,
            contentView,
            ViewCompat.SCROLL_AXIS_VERTICAL,
            ViewCompat.TYPE_NON_TOUCH,
        )
        assertTrue(nestedParent.getChildAt(0) === recyclerView)
        assertTrue(
            recyclerView.startNestedScroll(
                ViewCompat.SCROLL_AXIS_VERTICAL,
                ViewCompat.TYPE_NON_TOUCH,
            ),
        )
        assertTrue(
            recyclerView.hasNestedScrollingParent(ViewCompat.TYPE_NON_TOUCH),
        )
        return nestedParent
    }

    private class RecordingNestedParent(
        context: Context,
        private val onNonTouchStop: ((View) -> Unit)? = null,
    ) :
        ViewGroup(context),
        NestedScrollingParent2 {

        var nonTouchStartCount = 0
            private set

        var nonTouchStopCount = 0
            private set

        private var acceptedAxes = ViewCompat.SCROLL_AXIS_NONE

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            getChildAt(0)?.layout(0, 0, right - left, bottom - top)
        }

        override fun onStartNestedScroll(
            child: View,
            target: View,
            axes: Int,
            type: Int,
        ): Boolean = type == ViewCompat.TYPE_NON_TOUCH &&
            axes and ViewCompat.SCROLL_AXIS_VERTICAL != 0

        override fun onNestedScrollAccepted(
            child: View,
            target: View,
            axes: Int,
            type: Int,
        ) {
            if (type == ViewCompat.TYPE_NON_TOUCH) {
                nonTouchStartCount++
            }
            acceptedAxes = axes
        }

        override fun onStopNestedScroll(target: View, type: Int) {
            if (type == ViewCompat.TYPE_NON_TOUCH) {
                nonTouchStopCount++
                onNonTouchStop?.invoke(target)
            }
            acceptedAxes = ViewCompat.SCROLL_AXIS_NONE
        }

        override fun onNestedScroll(
            target: View,
            dxConsumed: Int,
            dyConsumed: Int,
            dxUnconsumed: Int,
            dyUnconsumed: Int,
            type: Int,
        ) = Unit

        override fun onNestedPreScroll(
            target: View,
            dx: Int,
            dy: Int,
            consumed: IntArray,
            type: Int,
        ) = Unit

        override fun onStartNestedScroll(child: View, target: View, axes: Int): Boolean = false

        override fun onNestedScrollAccepted(child: View, target: View, axes: Int) = Unit

        override fun onStopNestedScroll(target: View) = Unit

        override fun onNestedScroll(
            target: View,
            dxConsumed: Int,
            dyConsumed: Int,
            dxUnconsumed: Int,
            dyUnconsumed: Int,
        ) = Unit

        override fun onNestedPreScroll(
            target: View,
            dx: Int,
            dy: Int,
            consumed: IntArray,
        ) = Unit

        override fun onNestedFling(
            target: View,
            velocityX: Float,
            velocityY: Float,
            consumed: Boolean,
        ): Boolean = false

        override fun onNestedPreFling(
            target: View,
            velocityX: Float,
            velocityY: Float,
        ): Boolean = false

        override fun getNestedScrollAxes(): Int = acceptedAxes
    }

    private fun layoutRecycler(recyclerView: KRRecyclerView) {
        recyclerView.requestLayout()
        recyclerView.measure(
            View.MeasureSpec.makeMeasureSpec(VIEWPORT_WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(VIEWPORT_HEIGHT_PX, View.MeasureSpec.EXACTLY),
        )
        recyclerView.layout(0, 0, VIEWPORT_WIDTH_PX, VIEWPORT_HEIGHT_PX)
    }

    private fun advanceContentRangeAndRetry(
        recyclerView: KRRecyclerView,
        contentView: KRRecyclerContentView,
    ) {
        // Updating an adapter child's LayoutParams and redispatching RecyclerView.layout() is not
        // supported by RecyclerView 1.1's Robolectric shadow (it loses the synthetic ViewHolder).
        // Measure/layout the real content child to the post-placement range, then invoke the exact
        // production hook that KRRecyclerView.onLayout() calls after such a range commit.
        layoutContentRange(contentView, READY_CONTENT_HEIGHT_PX)
        recyclerView.retryPendingContentOffset()
    }

    private fun layoutContentRange(contentView: KRRecyclerContentView, contentHeightPx: Int) {
        val currentLeft = contentView.left
        val currentTop = contentView.top
        contentView.measure(
            View.MeasureSpec.makeMeasureSpec(VIEWPORT_WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(contentHeightPx, View.MeasureSpec.EXACTLY),
        )
        contentView.layout(
            currentLeft,
            currentTop,
            currentLeft + VIEWPORT_WIDTH_PX,
            currentTop + contentHeightPx,
        )
    }

    private fun KRRecyclerView.retryPendingContentOffset() {
        javaClass.getDeclaredMethod("tryApplyPendingSetContentOffset").apply {
            isAccessible = true
            invoke(this@retryPendingContentOffset)
        }
    }

    private fun genericScrollEvent(axis: Int, value: Float): MotionEvent {
        val pointerProperties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            },
        )
        val pointerCoordinates = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = 1f
                y = 1f
                setAxisValue(axis, value)
            },
        )
        return MotionEvent.obtain(
            0L,
            0L,
            MotionEvent.ACTION_SCROLL,
            1,
            pointerProperties,
            pointerCoordinates,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_MOUSE,
            0,
        )
    }

    private fun KRRecyclerView.pendingContentOffset(): String =
        javaClass.getDeclaredField("pendingContentOffsetOwner").let { field ->
            field.isAccessible = true
            val owner = field.get(this) ?: return@let ""
            owner.javaClass.getDeclaredField("value").let { valueField ->
                valueField.isAccessible = true
                valueField.get(owner) as String
            }
        }

    private fun KRRecyclerView.hasPendingContentOffset(): Boolean =
        pendingContentOffset().isNotEmpty()

    private fun KRRecyclerView.hasRunningCustomScrollAnimation(): Boolean =
        javaClass.getDeclaredField("scrollAnimationManager").let { field ->
            field.isAccessible = true
            val manager = field.get(this)
            manager.javaClass.getDeclaredMethod("hasRunningAnimation").let { method ->
                method.isAccessible = true
                method.invoke(manager) as Boolean
            }
        }

    private fun KRRecyclerView.hasActiveRecyclerViewContentOffsetOwner(): Boolean =
        javaClass.getDeclaredField("activeRecyclerViewContentOffsetOwner").let { field ->
            field.isAccessible = true
            field.get(this) != null
        }

    private companion object {
        const val VIEWPORT_WIDTH_PX = 300
        const val VIEWPORT_HEIGHT_PX = 315
        const val INITIAL_CONTENT_HEIGHT_PX = 400
        const val PARTIALLY_SCROLLABLE_CONTENT_HEIGHT_PX = 600
        const val READY_CONTENT_HEIGHT_PX = 1_200
        const val FINAL_CONTENT_HEIGHT_PX = 2_000
        const val PENDING_OFFSET_PX = 500
        const val CALLBACK_PENDING_OFFSET_PX = 700
        const val REENTRANT_PENDING_OFFSET_PX = 1_000
        const val HISTORY_OUTER_OFFSET_PX = 1_250
        const val CALLBACK_FINAL_OFFSET_PX = 1_500
        const val PARENT_STOP_PENDING_OFFSET_PX = 2_200
        const val PARENT_STOP_READY_CONTENT_HEIGHT_PX = 3_000
    }
}
