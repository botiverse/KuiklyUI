package com.tencent.kuikly.compose.foundation.text

import com.tencent.kuikly.compose.ui.text.TextRange
import com.tencent.kuikly.compose.ui.text.input.TextFieldValue
import com.tencent.kuikly.core.views.TextInputState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextInputCallbackArbiterTest {
    @Test
    fun delayedLegacyTextCannotOverwriteNewerCompleteState() {
        val arbiter = TextInputCallbackArbiter()

        arbiter.onCompleteState(state(text = "1234567890123456789012", selection = 22))
        val complete = arbiter.onCompleteState(state(text = "1234567890123456789012345", selection = 25))
        val delayedLegacy = arbiter.onLegacyTextChange(
            text = "1234567890123456789012",
            lastSyncedState = state(text = complete.text, selection = complete.selection.end),
        )

        assertEquals(25, complete.text.length)
        assertEquals(25, complete.selection.end)
        assertNull(delayedLegacy)
    }

    @Test
    fun matchingLegacyTextAfterCompleteStateDoesNotEmitTwice() {
        val arbiter = TextInputCallbackArbiter()
        val complete = arbiter.onCompleteState(state(text = "current", selection = 7))

        assertEquals("current", complete.text)
        assertNull(arbiter.onLegacyTextChange("current", state("current", 7)))
    }

    @Test
    fun markedCompleteStateKeepsCompositionAndConsumesFollowingLegacyEcho() {
        val arbiter = TextInputCallbackArbiter()
        val markedState = TextInputState(
            text = "english",
            selectionStart = 7,
            selectionEnd = 7,
            compositionStart = 0,
            compositionEnd = 7,
        )

        val complete = arbiter.onCompleteState(markedState)

        assertEquals(TextRange(0, 7), complete.composition)
        assertNull(arbiter.onLegacyTextChange(markedState.text, markedState))
    }

    @Test
    fun largeMarkedImeCommitPublishesFinalSelectionWithoutControlledWriteback() {
        val callbackArbiter = TextInputCallbackArbiter()
        val controlledStateArbiter = TextInputControlledStateArbiter()
        val markedText = "p".repeat(31)
        val markedState = TextInputState(
            text = markedText,
            selectionStart = markedText.length,
            selectionEnd = markedText.length,
            compositionStart = 0,
            compositionEnd = markedText.length,
        )

        val markedValue = callbackArbiter.onCompleteState(markedState)
        assertEquals(TextRange(0, 31), markedValue.composition)
        assertNull(callbackArbiter.onLegacyTextChange(markedText, markedState))

        // KRTextAreaView publishes this complete candidate-commit state before the legacy text
        // callback. The legacy echo is consumed instead of transiently exposing selection=0.
        val committedState = state(text = "中文输", selection = 3)
        val committedValue = callbackArbiter.onCompleteState(committedState)
        controlledStateArbiter.recordNativeValue(committedValue)

        assertEquals(3, committedValue.text.length)
        assertEquals(TextRange(3), committedValue.selection)
        assertNull(committedValue.composition)
        assertNull(callbackArbiter.onLegacyTextChange(committedValue.text, committedState))
        assertTrue(
            controlledStateArbiter.shouldSuppressControlledUpdate(
                value = committedValue,
            ),
        )
    }

    @Test
    fun legacyOnlyPlatformStillUpdatesText() {
        val arbiter = TextInputCallbackArbiter()

        val legacy = arbiter.onLegacyTextChange(
            text = "legacy edit",
            lastSyncedState = null,
        )

        assertEquals("legacy edit", legacy?.text)
        assertEquals(0, legacy?.selection?.start)
        assertEquals(0, legacy?.selection?.end)
    }

    @Test
    fun sameTextUnmatchedLegacyPreservesSelectionAndComposition() {
        val arbiter = TextInputCallbackArbiter()
        val lastSyncedState = TextInputState(
            text = "marked text",
            selectionStart = 2,
            selectionEnd = 7,
            compositionStart = 1,
            compositionEnd = 8,
        )

        val legacy = arbiter.onLegacyTextChange(
            text = lastSyncedState.text,
            lastSyncedState = lastSyncedState,
        )

        assertEquals(TextRange(2, 7), legacy?.selection)
        assertEquals(TextRange(1, 8), legacy?.composition)
    }

    @Test
    fun unmatchedLegacyMarkedTextRemainsSupportedAfterCompleteState() {
        val arbiter = TextInputCallbackArbiter()
        arbiter.onCompleteState(state(text = "committed", selection = 9))

        val markedText = arbiter.onLegacyTextChange(
            text = "committedp",
            lastSyncedState = state("committed", 9),
        )

        assertEquals("committedp", markedText?.text)
    }

    @Test
    fun unmatchedLegacyDoesNotInvalidateOtherPendingCompleteCallbacks() {
        val arbiter = TextInputCallbackArbiter()
        arbiter.onCompleteState(state(text = "complete-a", selection = 10))
        arbiter.onCompleteState(state(text = "complete-b", selection = 10))

        val legacyBeforeComplete = arbiter.onLegacyTextChange(
            text = "marked-c",
            lastSyncedState = state(text = "complete-b", selection = 10),
        )

        assertEquals("marked-c", legacyBeforeComplete?.text)
        assertNull(arbiter.onLegacyTextChange("complete-a", state("marked-c", 8)))
        assertNull(arbiter.onLegacyTextChange("complete-b", state("marked-c", 8)))

        val completeAfterLegacy = arbiter.onCompleteState(
            TextInputState(
                text = "marked-c",
                selectionStart = 2,
                selectionEnd = 7,
                compositionStart = 1,
                compositionEnd = 8,
            ),
        )

        assertEquals(TextRange(2, 7), completeAfterLegacy.selection)
        assertEquals(TextRange(1, 8), completeAfterLegacy.composition)
    }

    @Test
    fun sameTextSelectionUpdateIsNotResetByLegacyCallback() {
        val arbiter = TextInputCallbackArbiter()
        val selectionUpdate = arbiter.onCompleteState(state(text = "abcdef", selection = 3))

        assertEquals(3, selectionUpdate.selection.start)
        assertEquals(3, selectionUpdate.selection.end)
        assertNull(arbiter.onLegacyTextChange("abcdef", state("abcdef", 3)))
    }

    @Test
    fun rapidDeleteKeepsCompleteStateOrderAndNativeSelection() {
        val arbiter = TextInputCallbackArbiter()
        val emitted = mutableListOf<Pair<Int, Int>>()

        listOf(9, 8, 7, 6, 5, 4).forEachIndexed { index, length ->
            val value = arbiter.onCompleteState(
                state(text = "x".repeat(length), selection = length),
            )
            emitted += value.text.length to value.selection.end
            if (index > 0) {
                val previousLength = length + 1
                assertNull(
                    arbiter.onLegacyTextChange(
                        text = "x".repeat(previousLength),
                        lastSyncedState = state(value.text, value.selection.end),
                    ),
                )
            }
        }

        assertEquals(listOf(9 to 9, 8 to 8, 7 to 7, 6 to 6, 5 to 5, 4 to 4), emitted)
    }

    @Test
    fun queuedNativeEchoCannotRollbackNewerNativeEdit() {
        val arbiter = TextInputControlledStateArbiter()
        val firstNativeValue = value(text = "1234567", selection = 7)
        val secondNativeValue = value(text = "123456789", selection = 9)
        arbiter.recordNativeValue(firstNativeValue)
        arbiter.recordNativeValue(secondNativeValue)

        assertTrue(
            arbiter.shouldSuppressControlledUpdate(
                value = firstNativeValue,
            ),
        )
        assertTrue(
            arbiter.shouldSuppressControlledUpdate(
                value = secondNativeValue,
            ),
        )
    }

    @Test
    fun staleNativeIdentityCannotRollbackNewerNativeEditingState() {
        val scenarios = listOf(
            "Android selection" to Pair(
                TextFieldValue(text = "a", selection = TextRange(1)),
                TextFieldValue(text = "ab", selection = TextRange(2)),
            ),
            "iOS marked text" to Pair(
                TextFieldValue(
                    text = "n",
                    selection = TextRange(1),
                    composition = TextRange(0, 1),
                ),
                TextFieldValue(
                    text = "ni",
                    selection = TextRange(2),
                    composition = TextRange(0, 2),
                ),
            ),
            "OHOS full editing state" to Pair(
                TextFieldValue(
                    text = "中",
                    selection = TextRange(0, 1),
                    composition = TextRange(0, 1),
                ),
                TextFieldValue(
                    text = "中文",
                    selection = TextRange(1, 2),
                    composition = TextRange(0, 2),
                ),
            ),
        )

        scenarios.forEach { (platform, nativeValues) ->
            val arbiter = TextInputControlledStateArbiter()
            val firstNativeValue = nativeValues.first
            val latestNativeValue = nativeValues.second
            arbiter.recordNativeValue(firstNativeValue)
            arbiter.recordNativeValue(latestNativeValue)

            // The caller can still expose an earlier exact callback object after native state has
            // advanced. That object is still a direct echo of the older native snapshot and
            // must not write text, selection, or composition back over the latest editor state.
            val shouldSuppress = arbiter.shouldSuppressControlledUpdate(
                value = firstNativeValue,
            )
            var survivingNativeValue = latestNativeValue
            if (!shouldSuppress) {
                survivingNativeValue = firstNativeValue
            }

            assertTrue(shouldSuppress, "$platform stale native token must be fenced")
            assertEquals(
                latestNativeValue,
                survivingNativeValue,
                "$platform latest text/selection/composition must survive",
            )

            val laterText = latestNativeValue.text + "!"
            val laterNativeValue = TextFieldValue(
                text = laterText,
                selection = TextRange(laterText.length),
                composition = latestNativeValue.composition?.let {
                    TextRange(it.start, laterText.length)
                },
            )
            arbiter.recordNativeValue(laterNativeValue)
            assertTrue(
                arbiter.shouldSuppressControlledUpdate(
                    value = firstNativeValue,
                ),
                "$platform stale token must remain fenced across multiple native callbacks",
            )

            val lateArbiter = TextInputControlledStateArbiter()
            lateArbiter.recordNativeValue(firstNativeValue)
            lateArbiter.recordNativeValue(latestNativeValue)
            assertTrue(
                lateArbiter.shouldSuppressControlledUpdate(
                    value = latestNativeValue,
                ),
                "$platform latest direct echo must not write back",
            )
            assertTrue(
                lateArbiter.shouldSuppressControlledUpdate(
                    value = firstNativeValue,
                ),
                "$platform late stale token must stay fenced after the latest echo",
            )
        }
    }

    @Test
    fun newControlledEditingStateRemainsAuthoritativeAfterLatestNativeSnapshot() {
        val arbiter = TextInputControlledStateArbiter()
        val nativeValue = TextFieldValue(
            text = "marked",
            selection = TextRange(6),
            composition = TextRange(0, 6),
        )
        arbiter.recordNativeValue(nativeValue)

        val externalReplacement = TextFieldValue(
            text = nativeValue.text,
            selection = TextRange(1, 4),
            composition = null,
        )

        assertFalse(nativeValue === externalReplacement)
        assertFalse(
            arbiter.shouldSuppressControlledUpdate(
                value = externalReplacement,
            ),
        )
    }

    @Test
    fun coalescedLatestEchoKeepsEarlierDirectEchoToken() {
        val arbiter = TextInputControlledStateArbiter()
        val firstNativeValue = value(text = "1234567", selection = 7)
        val secondNativeValue = value(text = "123456789", selection = 9)
        arbiter.recordNativeValue(firstNativeValue)
        arbiter.recordNativeValue(secondNativeValue)

        assertTrue(
            arbiter.shouldSuppressControlledUpdate(
                value = secondNativeValue,
            ),
        )
        assertTrue(
            arbiter.shouldSuppressControlledUpdate(
                value = firstNativeValue,
            ),
        )
    }

    @Test
    fun legacyZeroSelectionEchoIsNotWrittenBackToNative() {
        val arbiter = TextInputControlledStateArbiter()
        val nativeValue = value(text = "1234567", selection = 0)
        arbiter.recordNativeValue(nativeValue)

        assertTrue(
            arbiter.shouldSuppressControlledUpdate(
                value = nativeValue,
            ),
        )
    }

    @Test
    fun transformedControlledValueRemainsAuthoritative() {
        val arbiter = TextInputControlledStateArbiter()
        arbiter.recordNativeValue(value(text = "draft", selection = 5))

        assertFalse(
            arbiter.shouldSuppressControlledUpdate(
                value = value(text = "DRAFT", selection = 5),
            ),
        )
    }

    @Test
    fun distinctEquivalentHistoricalBusinessValueIsNotSuppressedByIdentityFence() {
        val arbiter = TextInputControlledStateArbiter()
        val firstNativeValue = value(text = "draft", selection = 1)
        val secondNativeValue = value(text = "draft", selection = 2)
        arbiter.recordNativeValue(firstNativeValue)
        arbiter.recordNativeValue(secondNativeValue)

        val normalizedBusinessValue = value(text = "draft", selection = 1)

        assertEquals(firstNativeValue, normalizedBusinessValue)
        assertFalse(firstNativeValue === normalizedBusinessValue)
        assertFalse(
            arbiter.shouldSuppressControlledUpdate(
                value = normalizedBusinessValue,
            ),
        )
        assertTrue(
            arbiter.shouldSuppressControlledUpdate(
                value = firstNativeValue,
            ),
        )
        assertTrue(
            arbiter.shouldSuppressControlledUpdate(
                value = secondNativeValue,
            ),
        )
    }

    @Test
    fun retainedHistoricalNativeInstanceIsFailClosedInMountedSession() {
        val arbiter = TextInputControlledStateArbiter()
        val firstNativeValue = value(text = "draft", selection = 1)
        val secondNativeValue = value(text = "draft", selection = 2)
        arbiter.recordNativeValue(firstNativeValue)
        arbiter.recordNativeValue(secondNativeValue)

        assertTrue(
            arbiter.shouldSuppressControlledUpdate(
                value = firstNativeValue,
            ),
        )

        // Reusing the exact native callback instance is ambiguous after native state advances.
        // A distinguishable business replacement remains authoritative as a new object/editing state.
        val explicitBusinessReplacement = firstNativeValue.copy(selection = TextRange(0, 1))
        assertFalse(firstNativeValue === explicitBusinessReplacement)
        assertFalse(
            arbiter.shouldSuppressControlledUpdate(
                value = explicitBusinessReplacement,
            ),
        )
        assertTrue(
            arbiter.shouldSuppressControlledUpdate(
                value = secondNativeValue,
            ),
        )
    }

    @Test
    fun remountedSessionCannotMatchPreviousSessionToken() {
        val oldSessionArbiter = TextInputControlledStateArbiter()
        val oldSessionValue = value(text = "draft", selection = 5)
        oldSessionArbiter.recordNativeValue(oldSessionValue)

        // CoreTextField remembers the arbiter inside the editor session, so a true remount creates
        // a fresh token scope rather than trying to infer session identity from event ordering.
        val remountedSessionArbiter = TextInputControlledStateArbiter()

        assertFalse(
            remountedSessionArbiter.shouldSuppressControlledUpdate(
                value = oldSessionValue,
            ),
        )
    }

    @Test
    fun nativeIdentityProvenanceWindowIsBounded() {
        val arbiter = TextInputControlledStateArbiter()
        val nativeValues = (0..64).map { index ->
            val text = "draft-$index"
            value(text = text, selection = text.length)
        }
        nativeValues.forEach(arbiter::recordNativeValue)
        // Retain enough history to cover rapid snapshot convergence without retaining every text
        // object for the full lifetime of a long-lived editor.
        assertFalse(
            arbiter.shouldSuppressControlledUpdate(
                value = nativeValues.first(),
            ),
        )
        assertTrue(
            arbiter.shouldSuppressControlledUpdate(
                value = nativeValues[1],
            ),
        )
        assertTrue(
            arbiter.shouldSuppressControlledUpdate(
                value = nativeValues.last(),
            ),
        )
    }

    private fun state(text: String, selection: Int): TextInputState = TextInputState(
        text = text,
        selectionStart = selection,
        selectionEnd = selection,
    )

    private fun value(text: String, selection: Int): TextFieldValue = TextFieldValue(
        text = text,
        selection = TextRange(selection),
    )
}
