/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("DEPRECATION")

package com.tencent.kuikly.compose.ui.platform

import androidx.compose.runtime.Stable
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.AutoHeightTextAreaView

/**
 * Provide software keyboard control.
 */
@Stable
interface SoftwareKeyboardController {
    /**
     * Request that the system show a software keyboard.
     *
     * This request is best effort. If the system can currently show a software keyboard, it
     * will be shown. However, there is no guarantee that the system will be able to show a
     * software keyboard. If the system cannot show a software keyboard currently,
     * this call will be silently ignored.
     *
     * The software keyboard will never show if there is no composable that will accept text input,
     * such as a [TextField][androidx.compose.foundation.text.BasicTextField] when it is focused.
     * You may find it useful to ensure focus when calling this function.
     *
     * You do not need to call this function unless you also call [hide], as the
     * keyboard is automatically shown and hidden by focus events in the BasicTextField.
     *
     * Calling this function is considered a side-effect and should not be called directly from
     * recomposition.
     *
     * @sample androidx.compose.ui.samples.SoftwareKeyboardControllerSample
     */
    fun show()

    /**
     * Hide the software keyboard.
     *
     * This request is best effort, if the system cannot hide the software keyboard this call
     * will silently be ignored.
     *
     * Calling this function is considered a side-effect and should not be called directly from
     * recomposition.
     *
     * @sample androidx.compose.ui.samples.SoftwareKeyboardControllerSample
     */
    fun hide()
}

internal class KuiklySoftwareKeyboardController : SoftwareKeyboardController {
    private val focusReducer = InputFocusTargetReducer<AutoHeightTextAreaView>()
    private var reconcileScheduled = false
    private var keyboardHidden = false

    override fun show() {
        val target = focusReducer.desiredView ?: focusReducer.observedView
        target?.let { trace("show", it) }
        keyboardHidden = false
        target?.let { trace("showUnhidden", it) }
        scheduleReconcile(target)
    }

    override fun hide() {
        val target = focusReducer.desiredView ?: focusReducer.observedView
        target?.let { trace("hide", it) }
        keyboardHidden = true
        target?.let(focusReducer::onBlurRequested)
        target?.let { trace("hideAuthorityRevoked", it) }
        scheduleReconcile(target)
    }

    internal fun startInput(view: AutoHeightTextAreaView) {
        trace("startInput", view)
        keyboardHidden = false
        execute(focusReducer.start(view))
        trace("startInputReduced", view)
        scheduleReconcile(view)
    }

    internal fun stopInput(view: AutoHeightTextAreaView) {
        trace("stopInput", view)
        execute(focusReducer.stop(view))
        trace("stopInputReduced", view)
        scheduleReconcile(view)
    }

    internal fun onNativeFocus(
        view: AutoHeightTextAreaView,
        requestId: Long?,
    ): InputFocusTargetReducer.NativeFocusDecision {
        trace("onNativeFocus requestId=$requestId", view)
        val decision = focusReducer.onNativeFocus(view, requestId)
        trace("onNativeFocus decision=$decision", view)
        if (decision == InputFocusTargetReducer.NativeFocusDecision.IgnoreStale) {
            // The callback proves that native focus actually landed, even though the request no
            // longer belongs to the current generation. Do not publish the detached/old editor as
            // observed state; explicitly reject it so native first-responder state cannot survive
            // after common ownership moved on.
            rejectNativeFocus(view)
        }
        return decision
    }

    internal fun onNativeFocusIntent(
        view: AutoHeightTextAreaView,
    ): InputFocusTargetReducer.NativeFocusDecision {
        trace("onNativeFocusIntent", view)
        return focusReducer.onNativeFocusIntent(view).also { decision ->
            trace("onNativeFocusIntent decision=$decision", view)
        }
    }

    internal fun onNativeBlur(
        view: AutoHeightTextAreaView,
        requestId: Long?,
    ): InputFocusTargetReducer.NativeBlurDecision {
        trace("onNativeBlur requestId=$requestId", view)
        val decision = focusReducer.onNativeBlur(view, requestId)
        trace("onNativeBlur decision=$decision", view)
        scheduleReconcile(view)
        return decision
    }

    internal fun rejectNativeFocus(view: AutoHeightTextAreaView) {
        trace("rejectNativeFocus", view)
        focusReducer.rejectNativeFocus(view)
        view.blur(focusReducer.generation)
        trace("rejectNativeFocusBlurSent", view)
    }

    internal fun unregisterInput(view: AutoHeightTextAreaView) {
        trace("unregisterInput", view)
        execute(focusReducer.unregister(view))
        trace("unregisterInputReduced", view)
    }

    private fun scheduleReconcile(anchor: AutoHeightTextAreaView?) {
        val pagerId = anchor?.pagerId ?: return
        if (reconcileScheduled) {
            trace("scheduleReconcileSkipped keyboardHidden=$keyboardHidden", anchor)
            return
        }
        trace("scheduleReconcile keyboardHidden=$keyboardHidden", anchor)
        reconcileScheduled = true
        setTimeout(pagerId) {
            reconcileScheduled = false
            anchor?.let { trace("reconcileRun keyboardHidden=$keyboardHidden", it) }
            if (!keyboardHidden || focusReducer.desiredView == null) {
                execute(focusReducer.reconcile())
            }
            if (keyboardHidden) {
                focusReducer.observedView?.let { observedView ->
                    trace("hiddenBlur", observedView)
                    focusReducer.onBlurRequested(observedView)
                    trace("hiddenBlurAuthorityRevoked", observedView)
                    observedView.blur(focusReducer.generation)
                    trace("hiddenBlurSent", observedView)
                }
            }
        }
    }

    private fun execute(commands: List<InputFocusTargetReducer.Command<AutoHeightTextAreaView>>) {
        commands.forEach(::execute)
    }

    private fun execute(command: InputFocusTargetReducer.Command<AutoHeightTextAreaView>?) {
        command?.let { trace("execute ${it::class.simpleName} generation=${it.generation}", it.view) }
        when (command) {
            is InputFocusTargetReducer.Command.Focus ->
                executeFocus(command)
            is InputFocusTargetReducer.Command.Blur -> {
                focusReducer.onBlurRequested(command.view)
                trace("executeBlurAuthorityRevoked generation=${command.generation}", command.view)
                command.view.blur(command.generation)
                trace("executeBlurSent generation=${command.generation}", command.view)
            }
            is InputFocusTargetReducer.Command.CancelPendingFocus ->
                command.view.cancelPendingFocus(command.generation)
            null -> Unit
        }
    }

    private fun executeFocus(
        command: InputFocusTargetReducer.Command.Focus<AutoHeightTextAreaView>,
    ) {
        trace("executeFocus generation=${command.generation}", command.view)
        command.view.focus(command.generation)
        setTimeout(command.view.pagerId, FocusCompletionTimeoutMs) {
            val shouldRetry =
                focusReducer.onFocusRequestTimeout(command.view, command.generation)
            trace(
                "focusTimeout generation=${command.generation} shouldRetry=$shouldRetry",
                command.view,
            )
            if (shouldRetry) {
                scheduleReconcile(command.view)
            }
        }
    }

    private fun trace(event: String, view: AutoHeightTextAreaView) {
        println(
            "[KuiklyTextFocusTrace][common] event=$event keyboardHidden=$keyboardHidden " +
                "pager=${view.pagerId} nativeRef=${view.nativeRef} " +
                focusReducer.debugSnapshot(view)
        )
    }

    private companion object {
        const val FocusCompletionTimeoutMs = 120
    }
}
