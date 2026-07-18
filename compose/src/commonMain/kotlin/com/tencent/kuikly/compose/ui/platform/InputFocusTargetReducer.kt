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

/**
 * Reduces logical Compose focus into one desired native editor target.
 *
 * Focus is state, not an ordered stream of start/stop commands. In particular, a late stop for
 * editor A must not erase a newer start for editor B. Native focus events are observations; events
 * without a request id are treated as user focus intents that still need Compose FocusOwner
 * approval.
 */
internal class InputFocusTargetReducer<T : Any> {
    internal sealed interface Command<T> {
        val view: T
        val generation: Long

        data class Focus<T>(
            override val view: T,
            override val generation: Long,
        ) : Command<T>

        data class Blur<T>(
            override val view: T,
            override val generation: Long,
        ) : Command<T>

        data class CancelPendingFocus<T>(
            override val view: T,
            override val generation: Long,
        ) : Command<T>
    }

    internal enum class NativeFocusDecision {
        Confirmed,
        RequestComposeFocus,
        IgnoreStale,
    }

    internal enum class NativeBlurDecision {
        Confirmed,
        RequestComposeClear,
    }

    internal var desiredView: T? = null
        private set
    internal var observedView: T? = null
        private set
    internal var generation: Long = 0L
        private set

    private var pendingFocusView: T? = null
    private var pendingFocusGeneration: Long? = null
    private var completionAuthorityView: T? = null
    private var completionAuthorityGeneration: Long? = null
    private var focusAttemptCount = 0
    private var pendingBlurView: T? = null

    internal fun start(view: T): List<Command<T>> {
        if (desiredView === view) return emptyList()
        generation += 1
        desiredView = view
        focusAttemptCount = 0
        val commands = cancelSupersededPendingFocus(view)
        revokeCompletionAuthority()
        return commands
    }

    internal fun stop(view: T): List<Command<T>> {
        if (desiredView !== view) return emptyList()
        generation += 1
        desiredView = null
        focusAttemptCount = 0
        val commands = cancelSupersededPendingFocus(null)
        revokeCompletionAuthority()
        return commands
    }

    internal fun reconcile(): Command<T>? {
        val target = desiredView
        if (target == null) {
            val active = observedView ?: return null
            if (pendingBlurView === active) return null
            onBlurRequested(active)
            pendingBlurView = active
            return Command.Blur(active, generation)
        }
        if (observedView === target) return null
        if (pendingFocusView === target && pendingFocusGeneration == generation) return null
        if (focusAttemptCount >= MaxFocusAttemptsPerGeneration) return null
        pendingFocusView = target
        pendingFocusGeneration = generation
        completionAuthorityView = target
        completionAuthorityGeneration = generation
        focusAttemptCount += 1
        pendingBlurView = null
        return Command.Focus(target, generation)
    }

    internal fun onNativeFocus(view: T, requestId: Long?): NativeFocusDecision {
        if (requestId != null) {
            // A request id identifies the logical focus generation, not one transport attempt.
            // The pending slot may already have been consumed by an earlier user/native focus
            // observation for this same target, or released by a retry timeout, before the
            // programmatic completion crosses the bridge. Completion authority therefore lives
            // independently from the retry slot, but is revoked by blur intent so a late callback
            // cannot revive an editor after the keyboard or user dismissed it.
            val matchesCurrentGeneration =
                requestId == generation &&
                    desiredView === view &&
                    completionAuthorityView === view &&
                    completionAuthorityGeneration == requestId
            if (!matchesCurrentGeneration) {
                return NativeFocusDecision.IgnoreStale
            }
            observedView = view
            pendingBlurView = null
            pendingFocusView = null
            pendingFocusGeneration = null
            focusAttemptCount = 0
            return NativeFocusDecision.Confirmed
        }

        // A native focus event without a request id came from a platform/user focus action. It is
        // an intent, not authority: Compose FocusOwner still has to accept it.
        observedView = view
        pendingBlurView = null
        if (desiredView === view) {
            pendingFocusView = null
            pendingFocusGeneration = null
            focusAttemptCount = 0
            return NativeFocusDecision.Confirmed
        }
        return NativeFocusDecision.RequestComposeFocus
    }

    /**
     * Handles a native request to acquire Compose focus before native focus has landed.
     *
     * Unlike [onNativeFocus], this must not update [observedView]. Compose FocusOwner approval
     * calls start(), then reconcile() emits the generation-scoped native focus command. Only the
     * later native focus callback may confirm observed state.
     */
    internal fun onNativeFocusIntent(view: T): NativeFocusDecision =
        if (desiredView === view) {
            NativeFocusDecision.Confirmed
        } else {
            NativeFocusDecision.RequestComposeFocus
        }

    internal fun onNativeBlur(view: T, requestId: Long?): NativeBlurDecision {
        val shouldClearComposeFocus = requestId == null && desiredView === view
        if (observedView === view) observedView = null
        if (pendingBlurView === view) pendingBlurView = null
        onBlurRequested(view)
        return if (shouldClearComposeFocus) {
            NativeBlurDecision.RequestComposeClear
        } else {
            NativeBlurDecision.Confirmed
        }
    }

    internal fun unregister(view: T): List<Command<T>> {
        val commands = mutableListOf<Command<T>>()
        if (desiredView === view) {
            generation += 1
            desiredView = null
            focusAttemptCount = 0
        }
        if (pendingFocusView === view) {
            commands += Command.CancelPendingFocus(view, generation)
            pendingFocusView = null
            pendingFocusGeneration = null
        }
        revokeCompletionAuthority(view)
        if (observedView === view) {
            // Disposal removes the common callback surface immediately, but the native editor can
            // still be first responder until it is explicitly blurred. Clear the observation only
            // after emitting that terminal command so a detached/recreated field cannot leave the
            // software keyboard visible without a logical focus owner.
            commands += Command.Blur(view, generation)
            observedView = null
        }
        if (pendingBlurView === view) pendingBlurView = null
        return commands
    }

    internal fun rejectNativeFocus(view: T) {
        onBlurRequested(view)
        if (observedView === view) observedView = null
    }

    /**
     * Revokes permission for an in-flight native completion before an explicit blur is sent.
     *
     * Blur can preserve [desiredView] (for example, SoftwareKeyboardController.hide()), so the
     * logical generation alone cannot distinguish an obsolete completion from one that is still
     * allowed to establish native focus. A later [reconcile] call may emit a fresh Focus command
     * in the same generation, which reopens authority for hide -> show recovery.
     */
    internal fun onBlurRequested(view: T) {
        revokeCompletionAuthority(view)
        if (pendingFocusView === view) {
            pendingFocusView = null
            pendingFocusGeneration = null
        }
        if (desiredView === view) focusAttemptCount = 0
    }

    /**
     * Releases a programmatic focus request that produced no native completion callback.
     *
     * Native renderers can reject a request because their node/window is not ready. The bridge
     * command itself has no completion callback, so use a bounded generation-scoped timeout to
     * retry without turning a permanently unavailable editor into an unbounded focus storm.
     */
    internal fun onFocusRequestTimeout(view: T, requestGeneration: Long): Boolean {
        val matchesPendingRequest =
            pendingFocusView === view && pendingFocusGeneration == requestGeneration
        if (!matchesPendingRequest) return false
        pendingFocusView = null
        pendingFocusGeneration = null
        return desiredView === view &&
            observedView !== view &&
            generation == requestGeneration &&
            focusAttemptCount < MaxFocusAttemptsPerGeneration
    }

    internal fun debugSnapshot(view: T): String =
        "generation=$generation desiredSelf=${desiredView === view} observedSelf=${observedView === view} " +
            "pendingFocusSelf=${pendingFocusView === view} pendingFocusGeneration=$pendingFocusGeneration " +
            "completionAuthoritySelf=${completionAuthorityView === view} " +
            "completionAuthorityGeneration=$completionAuthorityGeneration " +
            "pendingBlurSelf=${pendingBlurView === view} focusAttemptCount=$focusAttemptCount"

    private fun cancelSupersededPendingFocus(nextView: T?): List<Command<T>> {
        val pending = pendingFocusView ?: return emptyList()
        if (pending === nextView) return emptyList()
        pendingFocusView = null
        pendingFocusGeneration = null
        return listOf(Command.CancelPendingFocus(pending, generation))
    }

    private fun revokeCompletionAuthority(view: T? = null) {
        if (view != null && completionAuthorityView !== view) return
        completionAuthorityView = null
        completionAuthorityGeneration = null
    }

    private companion object {
        const val MaxFocusAttemptsPerGeneration = 3
    }
}
