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

package com.tencent.kuikly.compose.ui.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InputFocusTargetReducerTest {
    private class View(val name: String)

    @Test
    fun lateStopForOldViewDoesNotEraseNewDesiredView() {
        val reducer = InputFocusTargetReducer<View>()
        val first = View("first")
        val second = View("second")

        reducer.start(first)
        val firstFocus = assertIs<InputFocusTargetReducer.Command.Focus<View>>(reducer.reconcile())
        reducer.onNativeFocus(first, firstFocus.generation)

        reducer.start(second)
        reducer.stop(first)

        assertSame(second, reducer.desiredView)
        val secondFocus = assertIs<InputFocusTargetReducer.Command.Focus<View>>(reducer.reconcile())
        assertSame(second, secondFocus.view)
    }

    @Test
    fun bothFocusTransferCallbackOrdersConvergeOnSecondView() {
        val first = View("first")
        val second = View("second")

        val stopThenStart = InputFocusTargetReducer<View>()
        stopThenStart.start(first)
        stopThenStart.onNativeFocus(
            first,
            assertIs<InputFocusTargetReducer.Command.Focus<View>>(stopThenStart.reconcile()).generation,
        )
        stopThenStart.stop(first)
        stopThenStart.start(second)

        val startThenStop = InputFocusTargetReducer<View>()
        startThenStop.start(first)
        startThenStop.onNativeFocus(
            first,
            assertIs<InputFocusTargetReducer.Command.Focus<View>>(startThenStop.reconcile()).generation,
        )
        startThenStop.start(second)
        startThenStop.stop(first)

        assertSame(second, stopThenStart.desiredView)
        assertSame(second, startThenStop.desiredView)
        assertSame(
            second,
            assertIs<InputFocusTargetReducer.Command.Focus<View>>(stopThenStart.reconcile()).view,
        )
        assertSame(
            second,
            assertIs<InputFocusTargetReducer.Command.Focus<View>>(startThenStop.reconcile()).view,
        )
    }

    @Test
    fun staleProgrammaticFocusIsRejectedAndCurrentTargetIsReconciled() {
        val reducer = InputFocusTargetReducer<View>()
        val first = View("first")
        val second = View("second")

        reducer.start(first)
        val firstRequest = assertIs<InputFocusTargetReducer.Command.Focus<View>>(reducer.reconcile())
        val cancelCommands = reducer.start(second)

        assertTrue(cancelCommands.any { it is InputFocusTargetReducer.Command.CancelPendingFocus })
        assertEquals(
            InputFocusTargetReducer.NativeFocusDecision.IgnoreStale,
            reducer.onNativeFocus(first, firstRequest.generation),
        )
        val currentRequest = assertIs<InputFocusTargetReducer.Command.Focus<View>>(reducer.reconcile())
        assertSame(second, currentRequest.view)
        assertEquals(reducer.generation, currentRequest.generation)
    }

    @Test
    fun nativeFocusFailureCanRetrySameDesiredViewAfterLifecycleRecovers() {
        val reducer = InputFocusTargetReducer<View>()
        val view = View("not-ready-then-ready")

        reducer.start(view)
        val failedRequest = assertIs<InputFocusTargetReducer.Command.Focus<View>>(reducer.reconcile())

        assertTrue(reducer.onFocusRequestTimeout(view, failedRequest.generation))
        val retry = assertIs<InputFocusTargetReducer.Command.Focus<View>>(reducer.reconcile())
        assertSame(view, retry.view)
        assertEquals(failedRequest.generation, retry.generation)

        reducer.onNativeFocus(view, retry.generation)
        assertSame(view, reducer.observedView)
        assertNull(reducer.reconcile())
    }

    @Test
    fun currentGenerationCompletionSurvivesEarlierUserFocusConfirmation() {
        val reducer = InputFocusTargetReducer<View>()
        val view = View("native-tap-then-programmatic-confirmation")

        reducer.start(view)
        val request = assertIs<InputFocusTargetReducer.Command.Focus<View>>(reducer.reconcile())

        // A native tap can report focus without a request id after Compose has already issued its
        // generation-scoped focus command. This confirms the same desired target and consumes the
        // pending slot, but the later programmatic completion is still current authority.
        assertEquals(
            InputFocusTargetReducer.NativeFocusDecision.Confirmed,
            reducer.onNativeFocus(view, requestId = null),
        )
        assertEquals(
            InputFocusTargetReducer.NativeFocusDecision.Confirmed,
            reducer.onNativeFocus(view, request.generation),
        )
        assertSame(view, reducer.observedView)
        assertNull(reducer.reconcile())
    }

    @Test
    fun lateCurrentGenerationCompletionSurvivesRetryTimeout() {
        val reducer = InputFocusTargetReducer<View>()
        val view = View("late-current-completion")

        reducer.start(view)
        val request = assertIs<InputFocusTargetReducer.Command.Focus<View>>(reducer.reconcile())
        assertTrue(reducer.onFocusRequestTimeout(view, request.generation))

        assertEquals(
            InputFocusTargetReducer.NativeFocusDecision.Confirmed,
            reducer.onNativeFocus(view, request.generation),
        )
        assertSame(view, reducer.observedView)
        assertNull(reducer.reconcile())
    }

    @Test
    fun currentGenerationCompletionCannotReviveAfterProgrammaticBlur() {
        val reducer = InputFocusTargetReducer<View>()
        val view = View("programmatically-blurred")

        reducer.start(view)
        val request = assertIs<InputFocusTargetReducer.Command.Focus<View>>(reducer.reconcile())
        reducer.onBlurRequested(view)

        assertEquals(
            InputFocusTargetReducer.NativeFocusDecision.IgnoreStale,
            reducer.onNativeFocus(view, request.generation),
        )
        assertNull(reducer.observedView)
    }

    @Test
    fun currentGenerationCompletionCannotReviveAfterUserBlurIntent() {
        val reducer = InputFocusTargetReducer<View>()
        val view = View("user-blurred")

        reducer.start(view)
        val request = assertIs<InputFocusTargetReducer.Command.Focus<View>>(reducer.reconcile())
        reducer.onNativeFocus(view, requestId = null)
        assertEquals(
            InputFocusTargetReducer.NativeBlurDecision.RequestComposeClear,
            reducer.onNativeBlur(view, requestId = null),
        )

        assertEquals(
            InputFocusTargetReducer.NativeFocusDecision.IgnoreStale,
            reducer.onNativeFocus(view, request.generation),
        )
        assertNull(reducer.observedView)
    }

    @Test
    fun nativeFocusFailureRetryIsBoundedWithinOneGeneration() {
        val reducer = InputFocusTargetReducer<View>()
        val view = View("permanently-unavailable")

        reducer.start(view)
        repeat(2) {
            val request = assertIs<InputFocusTargetReducer.Command.Focus<View>>(reducer.reconcile())
            assertTrue(reducer.onFocusRequestTimeout(view, request.generation))
        }
        val lastRequest = assertIs<InputFocusTargetReducer.Command.Focus<View>>(reducer.reconcile())
        assertEquals(false, reducer.onFocusRequestTimeout(view, lastRequest.generation))
        assertNull(reducer.reconcile())
    }

    @Test
    fun nativeUserFocusRequiresComposeApproval() {
        val reducer = InputFocusTargetReducer<View>()
        val first = View("first")
        val second = View("second")

        reducer.start(first)
        val firstRequest = assertIs<InputFocusTargetReducer.Command.Focus<View>>(reducer.reconcile())
        reducer.onNativeFocus(first, firstRequest.generation)

        assertEquals(
            InputFocusTargetReducer.NativeFocusDecision.RequestComposeFocus,
            reducer.onNativeFocus(second, requestId = null),
        )
        assertSame(first, reducer.desiredView)
        assertSame(second, reducer.observedView)

        reducer.start(second)
        assertNull(reducer.reconcile())
    }

    @Test
    fun programmaticFocusIntentWaitsForGenerationFocusBeforeBecomingObserved() {
        val reducer = InputFocusTargetReducer<View>()
        val view = View("autofocus-intent")

        assertEquals(
            InputFocusTargetReducer.NativeFocusDecision.RequestComposeFocus,
            reducer.onNativeFocusIntent(view),
        )
        assertNull(reducer.observedView)
        assertNull(reducer.desiredView)

        reducer.start(view)
        val focus = assertIs<InputFocusTargetReducer.Command.Focus<View>>(reducer.reconcile())
        assertSame(view, focus.view)
        assertNull(reducer.observedView)

        assertEquals(
            InputFocusTargetReducer.NativeFocusDecision.Confirmed,
            reducer.onNativeFocus(view, focus.generation),
        )
        assertSame(view, reducer.observedView)
    }

    @Test
    fun rejectedNativeUserFocusDoesNotBecomeObservedAuthority() {
        val reducer = InputFocusTargetReducer<View>()
        val approved = View("approved")
        val rejected = View("rejected")

        reducer.start(approved)
        val approvedRequest =
            assertIs<InputFocusTargetReducer.Command.Focus<View>>(reducer.reconcile())
        reducer.onNativeFocus(approved, approvedRequest.generation)

        assertEquals(
            InputFocusTargetReducer.NativeFocusDecision.RequestComposeFocus,
            reducer.onNativeFocus(rejected, requestId = null),
        )
        reducer.rejectNativeFocus(rejected)

        assertNull(reducer.observedView)
        assertSame(approved, reducer.desiredView)
        assertSame(
            approved,
            assertIs<InputFocusTargetReducer.Command.Focus<View>>(reducer.reconcile()).view,
        )
    }

    @Test
    fun nativeUserBlurOnlyClearsComposeWhenItStillOwnsDesiredFocus() {
        val reducer = InputFocusTargetReducer<View>()
        val first = View("first")
        val second = View("second")

        reducer.start(first)
        val firstRequest = assertIs<InputFocusTargetReducer.Command.Focus<View>>(reducer.reconcile())
        reducer.onNativeFocus(first, firstRequest.generation)

        assertEquals(
            InputFocusTargetReducer.NativeBlurDecision.RequestComposeClear,
            reducer.onNativeBlur(first, requestId = null),
        )

        reducer.start(second)
        assertEquals(
            InputFocusTargetReducer.NativeBlurDecision.Confirmed,
            reducer.onNativeBlur(first, requestId = null),
        )
    }

    @Test
    fun unregisterBlursDetachedObservedViewAndClearsFocusState() {
        val reducer = InputFocusTargetReducer<View>()
        val view = View("detached")

        reducer.start(view)
        val request = assertIs<InputFocusTargetReducer.Command.Focus<View>>(reducer.reconcile())
        reducer.onNativeFocus(view, request.generation)

        val commands = reducer.unregister(view)

        val blur = assertIs<InputFocusTargetReducer.Command.Blur<View>>(commands.single())
        assertSame(view, blur.view)
        assertEquals(reducer.generation, blur.generation)
        assertNull(reducer.desiredView)
        assertNull(reducer.observedView)
        assertNull(reducer.reconcile())
    }

    @Test
    fun lateProgrammaticFocusAfterUnregisterCannotReviveDetachedView() {
        val reducer = InputFocusTargetReducer<View>()
        val view = View("detached-while-focus-queued")

        reducer.start(view)
        val request = assertIs<InputFocusTargetReducer.Command.Focus<View>>(reducer.reconcile())
        val commands = reducer.unregister(view)

        assertIs<InputFocusTargetReducer.Command.CancelPendingFocus<View>>(commands.single())
        assertEquals(
            InputFocusTargetReducer.NativeFocusDecision.IgnoreStale,
            reducer.onNativeFocus(view, request.generation),
        )
        assertNull(reducer.desiredView)
        assertNull(reducer.observedView)
        assertNull(reducer.reconcile())
    }

    @Test
    fun unregisterCancelsFocusQueuedForDetachedView() {
        val reducer = InputFocusTargetReducer<View>()
        val detached = View("detached")

        reducer.start(detached)
        reducer.reconcile()
        val commands = reducer.unregister(detached)

        assertTrue(commands.single() is InputFocusTargetReducer.Command.CancelPendingFocus)
        assertNull(reducer.desiredView)
        assertNull(reducer.observedView)
        assertNull(reducer.reconcile())
    }

    @Test
    fun hideShowStyleBlurKeepsDesiredTargetForRefocus() {
        val reducer = InputFocusTargetReducer<View>()
        val view = View("editor")

        reducer.start(view)
        val focus = assertIs<InputFocusTargetReducer.Command.Focus<View>>(reducer.reconcile())
        reducer.onNativeFocus(view, focus.generation)
        reducer.onNativeBlur(view, requestId = reducer.generation)

        assertSame(view, reducer.desiredView)
        assertEquals(
            InputFocusTargetReducer.NativeFocusDecision.IgnoreStale,
            reducer.onNativeFocus(view, focus.generation),
        )
        assertNull(reducer.observedView)

        val refocus = assertIs<InputFocusTargetReducer.Command.Focus<View>>(reducer.reconcile())
        assertSame(view, refocus.view)
        assertEquals(
            InputFocusTargetReducer.NativeFocusDecision.Confirmed,
            reducer.onNativeFocus(view, refocus.generation),
        )
        assertSame(view, reducer.observedView)
    }

    @Test
    fun blurWithoutProgrammaticCallbackStillLetsLaterUserBlurClearCompose() {
        val reducer = InputFocusTargetReducer<View>()
        val view = View("editor")

        reducer.start(view)
        val firstFocus = assertIs<InputFocusTargetReducer.Command.Focus<View>>(reducer.reconcile())
        reducer.onNativeFocus(view, firstFocus.generation)
        reducer.stop(view)
        assertIs<InputFocusTargetReducer.Command.Blur<View>>(reducer.reconcile())

        assertEquals(
            InputFocusTargetReducer.NativeFocusDecision.RequestComposeFocus,
            reducer.onNativeFocus(view, requestId = null),
        )
        reducer.start(view)
        assertEquals(
            InputFocusTargetReducer.NativeBlurDecision.RequestComposeClear,
            reducer.onNativeBlur(view, requestId = null),
        )
    }
}
