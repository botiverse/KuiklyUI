/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI.
 */

package com.tencent.kuikly.core.views

import com.tencent.kuikly.core.base.RenderView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.manager.BridgeManager
import com.tencent.kuikly.core.manager.NativeMethod
import com.tencent.kuikly.core.manager.PagerManager
import com.tencent.kuikly.core.nvi.NativeBridge
import com.tencent.kuikly.core.pager.Pager
import kotlin.test.Test
import kotlin.test.assertEquals

class ScrollerViewAlreadySatisfiedPhaseTest {

    @Test
    fun transactionalAnimatedOffsetRestoresIdleAfterAlreadySatisfied() {
        val fixture = AlreadySatisfiedBridgeFixture("transactional-offset")

        fixture.view.setContentOffset(
            offsetX = 10f,
            offsetY = 0f,
            animated = true,
            writeToken = fixture.token(operation = 1L),
        )

        assertEquals(NativeScrollPhase.Idle, fixture.view.nativeScrollPhase)
    }

    @Test
    fun legacyAnimatedOffsetRestoresIdleAfterAlreadySatisfied() {
        val fixture = AlreadySatisfiedBridgeFixture("legacy-offset")

        fixture.view.setContentOffset(
            10f,
            0f,
            SetContentOffsetAnimation(300, 1f, 0f, 0),
        )

        assertEquals(NativeScrollPhase.Idle, fixture.view.nativeScrollPhase)
    }

    @Test
    fun animatedInsetRestoresIdleAndAdmitsNextIdleRequiredWrite() {
        val fixture = AlreadySatisfiedBridgeFixture("inset")

        fixture.view.setContentInset(
            top = 12f,
            animated = true,
            writeToken = fixture.token(operation = 1L),
        )
        fixture.view.setContentInset(
            top = 16f,
            animated = false,
            writeToken = fixture.token(operation = 2L, requiresNativeIdle = true),
        )

        assertEquals(NativeScrollPhase.Idle, fixture.view.nativeScrollPhase)
        assertEquals(listOf("contentInset", "contentInset"), fixture.calledMethods)
    }

    @Test
    fun alreadySatisfiedRestoresPreExistingDraggingPhase() {
        val fixture = AlreadySatisfiedBridgeFixture("dragging")
        fixture.view.nativeScrollPhase = NativeScrollPhase.Dragging

        fixture.view.setContentOffset(
            offsetX = 10f,
            offsetY = 0f,
            animated = true,
            writeToken = fixture.token(operation = 1L),
        )

        assertEquals(NativeScrollPhase.Dragging, fixture.view.nativeScrollPhase)
    }

    @Test
    fun lateAlreadySatisfiedCannotRestoreOverNewerAnimatedWrite() {
        val fixture = AlreadySatisfiedBridgeFixture("late", autoComplete = false)
        fixture.view.setContentOffset(
            offsetX = 10f,
            offsetY = 0f,
            animated = true,
            writeToken = fixture.token(operation = 1L),
        )
        fixture.view.nativeScrollPhase = NativeScrollPhase.Dragging
        fixture.view.setContentOffset(
            offsetX = 20f,
            offsetY = 0f,
            animated = true,
            writeToken = fixture.token(operation = 2L),
        )

        fixture.complete(0)
        assertEquals(NativeScrollPhase.SettlingOrAnimating, fixture.view.nativeScrollPhase)

        fixture.complete(1)
        assertEquals(NativeScrollPhase.Dragging, fixture.view.nativeScrollPhase)
    }

    @Test
    fun lateAnimatedInsetTerminalCannotRestoreOverNewerAnimatedOffset() {
        val fixture = AlreadySatisfiedBridgeFixture("inset-offset-late", autoComplete = false)
        fixture.view.setContentInset(
            top = 12f,
            animated = true,
            writeToken = fixture.token(operation = 1L),
        )
        fixture.view.setContentOffset(
            offsetX = 20f,
            offsetY = 0f,
            animated = true,
            writeToken = fixture.token(operation = 2L),
        )

        fixture.complete(
            index = 0,
            code = ScrollWriteResultCode.Replaced,
            installed = true,
        )

        assertEquals(NativeScrollPhase.SettlingOrAnimating, fixture.view.nativeScrollPhase)
    }

    @Test
    fun replacingAnimatedWriteAlreadySatisfiedRestoresUnderlyingIdlePhase() {
        val fixture = AlreadySatisfiedBridgeFixture("replacement-phase", autoComplete = false)
        fixture.view.setContentOffset(
            offsetX = 10f,
            offsetY = 0f,
            animated = true,
            writeToken = fixture.token(operation = 1L),
        )
        fixture.view.setContentOffset(
            offsetX = 20f,
            offsetY = 0f,
            animated = true,
            writeToken = fixture.token(operation = 2L),
        )

        fixture.complete(0, ScrollWriteResultCode.Replaced)
        fixture.complete(1, ScrollWriteResultCode.AlreadySatisfied)

        assertEquals(NativeScrollPhase.Idle, fixture.view.nativeScrollPhase)
    }

    @Test
    fun animatedInsetReplacementRestoresUnderlyingIdlePhase() {
        val fixture = AlreadySatisfiedBridgeFixture("offset-inset-phase", autoComplete = false)
        fixture.view.setContentOffset(
            offsetX = 10f,
            offsetY = 0f,
            animated = true,
            writeToken = fixture.token(operation = 1L),
        )
        fixture.view.setContentInset(
            top = 12f,
            animated = true,
            writeToken = fixture.token(operation = 2L),
        )

        fixture.complete(0, ScrollWriteResultCode.Replaced)
        fixture.complete(1, ScrollWriteResultCode.AlreadySatisfied)

        assertEquals(NativeScrollPhase.Idle, fixture.view.nativeScrollPhase)
    }

    @Test
    fun legacyAnimatedReplacementRestoresUnderlyingIdlePhase() {
        val fixture = AlreadySatisfiedBridgeFixture("legacy-phase", autoComplete = false)
        fixture.view.setContentOffset(10f, 0f, SetContentOffsetAnimation(300, 1f, 0f, 0))
        fixture.view.setContentOffset(20f, 0f, SetContentOffsetAnimation(300, 1f, 0f, 0))

        fixture.complete(0, ScrollWriteResultCode.Replaced)
        fixture.complete(1, ScrollWriteResultCode.AlreadySatisfied)

        assertEquals(NativeScrollPhase.Idle, fixture.view.nativeScrollPhase)
    }

    @Test
    fun nativeSettlingPhaseReplacesComposeOwnedPhaseProvenance() {
        val fixture = AlreadySatisfiedBridgeFixture("native-settling", autoComplete = false)
        fixture.view.setContentOffset(
            offsetX = 10f,
            offsetY = 0f,
            animated = true,
            writeToken = fixture.token(operation = 1L),
        )
        fixture.view.updateNativeScrollPhaseFromNative(NativeScrollPhase.SettlingOrAnimating)
        fixture.view.setContentOffset(
            offsetX = 20f,
            offsetY = 0f,
            animated = true,
            writeToken = fixture.token(operation = 2L),
        )

        fixture.complete(0, ScrollWriteResultCode.Replaced)
        fixture.complete(1, ScrollWriteResultCode.AlreadySatisfied)

        assertEquals(NativeScrollPhase.SettlingOrAnimating, fixture.view.nativeScrollPhase)
    }

    @Test
    fun acceptedNativeSettlingAfterAnimatedWriteCannotBeRestoredByTerminal() {
        val fixture = AlreadySatisfiedBridgeFixture("native-settling-after-write", autoComplete = false)
        fixture.view.setContentOffset(
            offsetX = 10f,
            offsetY = 0f,
            animated = true,
            writeToken = fixture.token(operation = 1L),
        )

        fixture.view.updateNativeScrollPhaseFromNative(NativeScrollPhase.SettlingOrAnimating)
        fixture.complete(0, ScrollWriteResultCode.AlreadySatisfied)

        assertEquals(NativeScrollPhase.SettlingOrAnimating, fixture.view.nativeScrollPhase)
    }

    @Test
    fun acceptedNativeDraggingBeforeImmediateCommitCannotBeOverwritten() {
        val fixture = AlreadySatisfiedBridgeFixture("native-dragging-before-immediate", autoComplete = false)
        fixture.view.setContentOffset(
            offsetX = 10f,
            offsetY = 0f,
            animated = false,
            writeToken = fixture.token(operation = 1L),
        )
        fixture.fireScrollEvent(fixture.scrollEventPayload(NativeScrollPhase.Dragging))

        fixture.complete(0, ScrollWriteResultCode.Committed, installed = true)

        assertEquals(NativeScrollPhase.Dragging, fixture.view.nativeScrollPhase)
    }

    @Test
    fun sameOperationProgrammaticSettlingPreservesUnderlyingPhase() {
        val fixture = AlreadySatisfiedBridgeFixture("same-operation-event", autoComplete = false)
        fixture.view.setContentOffset(
            offsetX = 10f,
            offsetY = 0f,
            animated = true,
            writeToken = fixture.token(operation = 1L),
        )
        fixture.fireScrollEvent(
            fixture.scrollEventPayload(
                NativeScrollPhase.SettlingOrAnimating,
                sourceOperationGeneration = 1L,
            ),
        )
        fixture.view.setContentOffset(
            offsetX = 20f,
            offsetY = 0f,
            animated = true,
            writeToken = fixture.token(operation = 2L),
        )

        fixture.complete(0, ScrollWriteResultCode.Replaced)
        fixture.complete(1, ScrollWriteResultCode.AlreadySatisfied)

        assertEquals(NativeScrollPhase.Idle, fixture.view.nativeScrollPhase)
    }

    @Test
    fun latePredecessorSettlingEventCannotInvalidateReplacementPhaseRestore() {
        val fixture = AlreadySatisfiedBridgeFixture("late-predecessor-event", autoComplete = false)
        fixture.view.setContentOffset(
            offsetX = 10f,
            offsetY = 0f,
            animated = true,
            writeToken = fixture.token(operation = 1L),
        )
        val predecessorEvent = fixture.scrollEventPayload(
            NativeScrollPhase.SettlingOrAnimating,
            sourceOperationGeneration = 1L,
        )
        fixture.view.setContentOffset(
            offsetX = 20f,
            offsetY = 0f,
            animated = true,
            writeToken = fixture.token(operation = 2L),
        )

        fixture.fireScrollEvent(predecessorEvent)
        fixture.complete(0, ScrollWriteResultCode.Replaced)
        fixture.complete(1, ScrollWriteResultCode.AlreadySatisfied)

        assertEquals(NativeScrollPhase.Idle, fixture.view.nativeScrollPhase)
    }

    @Test
    fun rejectedAnimatedReplacementKeepsPredecessorMotionPhase() {
        val fixture = AlreadySatisfiedBridgeFixture("animated-preinstall-reject", autoComplete = false)
        fixture.view.setContentOffset(10f, 0f, true, writeToken = fixture.token(operation = 1L))
        fixture.view.setContentOffset(20f, 0f, true, writeToken = fixture.token(operation = 2L))

        fixture.complete(1, ScrollWriteResultCode.NotReady, installed = false)

        assertEquals(NativeScrollPhase.SettlingOrAnimating, fixture.view.nativeScrollPhase)
        fixture.complete(0, ScrollWriteResultCode.AlreadySatisfied, installed = true)

        assertEquals(NativeScrollPhase.Idle, fixture.view.nativeScrollPhase)
    }

    @Test
    fun rejectedImmediateReplacementDoesNotPublishIdleBeforeNativeAcceptance() {
        val fixture = AlreadySatisfiedBridgeFixture("immediate-preinstall-reject", autoComplete = false)
        fixture.view.setContentOffset(10f, 0f, true, writeToken = fixture.token(operation = 1L))
        fixture.view.setContentOffset(20f, 0f, false, writeToken = fixture.token(operation = 2L))

        assertEquals(NativeScrollPhase.SettlingOrAnimating, fixture.view.nativeScrollPhase)
        fixture.complete(1, ScrollWriteResultCode.NotReady, installed = false)

        assertEquals(NativeScrollPhase.SettlingOrAnimating, fixture.view.nativeScrollPhase)
        fixture.complete(0, ScrollWriteResultCode.AlreadySatisfied, installed = true)

        assertEquals(NativeScrollPhase.Idle, fixture.view.nativeScrollPhase)
    }

    @Test
    fun lateRejectedAttemptCannotRollbackNewerAttemptPhaseOwner() {
        val fixture = AlreadySatisfiedBridgeFixture("late-attempt-preinstall-reject", autoComplete = false)
        fixture.view.setContentOffset(
            10f,
            0f,
            true,
            writeToken = fixture.token(operation = 1L),
        )
        fixture.view.setContentOffset(
            20f,
            0f,
            true,
            writeToken = fixture.token(operation = 2L, attempt = 1L),
        )
        fixture.view.setContentOffset(
            30f,
            0f,
            true,
            writeToken = fixture.token(operation = 2L, attempt = 2L),
        )

        fixture.complete(1, ScrollWriteResultCode.NotReady, installed = false)
        fixture.complete(2, ScrollWriteResultCode.AlreadySatisfied, installed = true)

        assertEquals(NativeScrollPhase.Idle, fixture.view.nativeScrollPhase)
    }

    @Test
    fun chainedPreinstallRejectionsDoNotRestoreRejectedProvisionalOwner() {
        val fixture = AlreadySatisfiedBridgeFixture("chained-preinstall-reject", autoComplete = false)
        fixture.view.setContentOffset(10f, 0f, true, writeToken = fixture.token(operation = 1L))
        fixture.view.setContentOffset(20f, 0f, true, writeToken = fixture.token(operation = 2L))
        fixture.view.setContentOffset(30f, 0f, true, writeToken = fixture.token(operation = 3L))

        fixture.complete(0, ScrollWriteResultCode.NotReady, installed = false)
        fixture.complete(1, ScrollWriteResultCode.NotReady, installed = false)
        fixture.complete(2, ScrollWriteResultCode.NotReady, installed = false)

        assertEquals(NativeScrollPhase.Idle, fixture.view.nativeScrollPhase)
    }

    @Test
    fun rejectedSuccessorDoesNotReviveInstalledPredecessorAfterTerminal() {
        val fixture = AlreadySatisfiedBridgeFixture("terminal-predecessor-reject", autoComplete = false)
        fixture.view.setContentOffset(10f, 0f, true, writeToken = fixture.token(operation = 1L))
        fixture.view.setContentOffset(20f, 0f, true, writeToken = fixture.token(operation = 2L))

        fixture.fireScrollEndEvent(
            fixture.scrollEventPayload(
                NativeScrollPhase.Idle,
                sourceOperationGeneration = 1L,
            ),
        )
        fixture.complete(0, ScrollWriteResultCode.Committed, installed = true)
        fixture.complete(1, ScrollWriteResultCode.NotReady, installed = false)

        assertEquals(NativeScrollPhase.Idle, fixture.view.nativeScrollPhase)
    }

    @Test
    fun rejectedLegacySuccessorDoesNotReviveInstalledPredecessorAfterTerminal() {
        val fixture = AlreadySatisfiedBridgeFixture("legacy-terminal-predecessor-reject", autoComplete = false)
        fixture.view.setContentOffset(10f, 0f, SetContentOffsetAnimation(300, 1f, 0f, 0))
        fixture.view.setContentOffset(20f, 0f, SetContentOffsetAnimation(300, 1f, 0f, 0))

        fixture.complete(0, ScrollWriteResultCode.AlreadySatisfied, installed = true)
        fixture.complete(1, ScrollWriteResultCode.NotReady, installed = false)

        assertEquals(NativeScrollPhase.Idle, fixture.view.nativeScrollPhase)
    }

    @Test
    fun rejectedInsetSuccessorDoesNotReviveInstalledPredecessorAfterTerminal() {
        val fixture = AlreadySatisfiedBridgeFixture("inset-terminal-predecessor-reject", autoComplete = false)
        fixture.view.setContentInset(10f, animated = true, writeToken = fixture.token(operation = 1L))
        fixture.view.setContentInset(20f, animated = true, writeToken = fixture.token(operation = 2L))

        fixture.complete(0, ScrollWriteResultCode.AlreadySatisfied, installed = true)
        fixture.complete(1, ScrollWriteResultCode.NotReady, installed = false)

        assertEquals(NativeScrollPhase.Idle, fixture.view.nativeScrollPhase)
    }

    @Test
    fun operationOwnedScrollEndCannotClearNewerAnimatedOwner() {
        val fixture = AlreadySatisfiedBridgeFixture("operation-owned-scroll-end", autoComplete = false)
        fixture.view.setContentOffset(10f, 0f, true, writeToken = fixture.token(operation = 1L))
        fixture.view.setContentOffset(20f, 0f, true, writeToken = fixture.token(operation = 2L))

        fixture.fireScrollEndEvent(
            fixture.scrollEventPayload(
                NativeScrollPhase.Idle,
                sourceOperationGeneration = 1L,
            ),
        )
        assertEquals(NativeScrollPhase.SettlingOrAnimating, fixture.view.nativeScrollPhase)

        fixture.fireScrollEndEvent(
            fixture.scrollEventPayload(
                NativeScrollPhase.Idle,
                sourceOperationGeneration = 2L,
            ),
        )
        assertEquals(NativeScrollPhase.Idle, fixture.view.nativeScrollPhase)
    }

    @Test
    fun postInstallFailureDoesNotRollbackPredecessorPhaseOwner() {
        val fixture = AlreadySatisfiedBridgeFixture("postinstall-failure-owner", autoComplete = false)
        fixture.view.setContentOffset(10f, 0f, true, writeToken = fixture.token(operation = 1L))
        fixture.view.setContentOffset(20f, 0f, true, writeToken = fixture.token(operation = 2L))

        fixture.complete(
            index = 1,
            code = ScrollWriteResultCode.LayoutChanged,
            installed = true,
            replacedPrevious = true,
        )

        assertEquals(NativeScrollPhase.Idle, fixture.view.nativeScrollPhase)
        fixture.complete(0, ScrollWriteResultCode.Replaced, installed = true)

        assertEquals(NativeScrollPhase.Idle, fixture.view.nativeScrollPhase)
    }

    @Test
    fun immediateReplacementClearsComposeOwnedSyntheticSettlingPhase() {
        val fixture = AlreadySatisfiedBridgeFixture("immediate-replacement", autoComplete = false)
        fixture.view.setContentOffset(
            offsetX = 10f,
            offsetY = 0f,
            animated = true,
            writeToken = fixture.token(operation = 1L),
        )
        fixture.view.setContentOffset(
            offsetX = 20f,
            offsetY = 0f,
            animated = false,
            writeToken = fixture.token(operation = 2L),
        )

        assertEquals(NativeScrollPhase.SettlingOrAnimating, fixture.view.nativeScrollPhase)
        fixture.complete(0, ScrollWriteResultCode.Replaced)
        fixture.complete(1, ScrollWriteResultCode.Committed, installed = true, replacedPrevious = true)

        assertEquals(NativeScrollPhase.Idle, fixture.view.nativeScrollPhase)
    }

    @Test
    fun composeReuseClearsAnimatedPhaseProvenance() {
        val fixture = AlreadySatisfiedBridgeFixture("reuse-phase", autoComplete = false)
        fixture.view.setContentOffset(
            offsetX = 10f,
            offsetY = 0f,
            animated = true,
            writeToken = fixture.token(operation = 1L),
        )

        fixture.view.prepareForComposeReuse()
        fixture.complete(0, ScrollWriteResultCode.Replaced)

        assertEquals(NativeScrollPhase.Idle, fixture.view.nativeScrollPhase)
    }

    private class AlreadySatisfiedBridgeFixture(
        private val suffix: String,
        private val autoComplete: Boolean = true,
    ) {
        val view = ScrollerView<ScrollerAttr, ScrollerEvent>()
        val calledMethods = mutableListOf<String>()
        private val callbacks = mutableListOf<String>()
        private val pagerId = "already-satisfied-$suffix-${view.nativeRef}"

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
                        if (methodId == NativeMethod.CALL_VIEW_METHOD) {
                            calledMethods += arg2 as String
                            val callbackRef = arg4 as? String
                            if (callbackRef != null) {
                                callbacks += callbackRef
                                if (autoComplete) complete(callbacks.lastIndex)
                            }
                        }
                        return null
                    }
                }
            }
            BridgeManager.registerNativeBridge(pagerId, bridge)
            view.pagerId = pagerId
            view.renderView = RenderView(pagerId, view.nativeRef, "KRScrollView")
        }

        fun complete(
            index: Int,
            code: ScrollWriteResultCode = ScrollWriteResultCode.AlreadySatisfied,
            installed: Boolean = code == ScrollWriteResultCode.Committed ||
                code == ScrollWriteResultCode.AlreadySatisfied,
            replacedPrevious: Boolean = false,
        ) {
            val committed = code == ScrollWriteResultCode.Committed ||
                code == ScrollWriteResultCode.AlreadySatisfied
            PagerManager.fireCallBack(
                pagerId,
                callbacks[index],
                """{"committed":${if (committed) 1 else 0},"resultCode":${code.wireValue},"accepted":${if (installed) 1 else 0},"installed":${if (installed) 1 else 0},"replacedPrevious":${if (replacedPrevious) 1 else 0},"nativeInteractionEpoch":0,"layoutRevision":0,"insetRevision":0}""",
            )
        }

        fun scrollEventPayload(
            phase: NativeScrollPhase,
            sourceOperationGeneration: Long = 0L,
        ): String =
            """{"offsetX":0,"offsetY":0,"contentWidth":100,"contentHeight":100,"viewWidth":50,"viewHeight":50,"isDragging":0,"nativeScrollPhase":${phase.wireValue},"nativeInteractionEpoch":${view.nativeInteractionEpoch},"layoutRevision":${view.nativeLayoutRevision},"insetRevision":${view.nativeInsetRevision},"sourceOperationGeneration":$sourceOperationGeneration}"""

        fun fireScrollEvent(payload: String) {
            fireScrollerEvent(ScrollerEvent.ScrollerEventConst.SCROLL, payload)
        }

        fun fireScrollEndEvent(payload: String) {
            fireScrollerEvent(ScrollerEvent.ScrollerEventConst.SCROLL_END, payload)
        }

        private fun fireScrollerEvent(eventName: String, payload: String) {
            val pageName = "phase-event-$suffix-${view.nativeRef}"
            val eventPagerId = "$pagerId-event"
            PagerManager.registerPageRouter(pageName) { EventTestPager() }
            BridgeManager.currentPageId = eventPagerId
            PagerManager.createPager(eventPagerId, pageName, "{}")
            val pager = PagerManager.getPager(eventPagerId)
            view.pagerId = eventPagerId
            pager.putNativeViewRef(view.nativeRef, view)
            view.getViewEvent().init(eventPagerId, view.nativeRef)
            view.event {
                scroll { }
                scrollEnd { }
            }
            PagerManager.fireViewEvent(
                eventPagerId,
                view.nativeRef,
                eventName,
                payload,
            )
            PagerManager.destroyPager(eventPagerId)
        }

        fun token(
            operation: Long,
            requiresNativeIdle: Boolean = false,
            attempt: Long = 0L,
        ) = ScrollOffsetCommitToken(
            generation = view.offsetWriteGeneration,
            requiresNativeIdle = requiresNativeIdle,
            operationGeneration = operation,
            nativeInteractionEpoch = view.nativeInteractionEpoch,
            layoutRevision = view.nativeLayoutRevision,
            insetRevision = view.nativeInsetRevision,
            attemptGeneration = attempt,
        )
    }

    private class EventTestPager : Pager() {
        override fun body(): ViewBuilder = {}
    }
}
