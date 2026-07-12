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
        val firstGeneration = generation()
        val secondGeneration = generation()
        arbiter.recordNativeValue(firstNativeValue, firstGeneration)
        arbiter.recordNativeValue(secondNativeValue, secondGeneration)

        assertTrue(
            arbiter.shouldSuppressControlledUpdate(
                value = firstNativeValue,
                updateGeneration = firstGeneration,
                latestGeneration = secondGeneration,
            ),
        )
        assertTrue(
            arbiter.shouldSuppressControlledUpdate(
                value = secondNativeValue,
                updateGeneration = secondGeneration,
                latestGeneration = secondGeneration,
            ),
        )
    }

    @Test
    fun coalescedLatestEchoKeepsEarlierDirectEchoToken() {
        val arbiter = TextInputControlledStateArbiter()
        val firstNativeValue = value(text = "1234567", selection = 7)
        val secondNativeValue = value(text = "123456789", selection = 9)
        val firstGeneration = generation()
        val secondGeneration = generation()
        arbiter.recordNativeValue(firstNativeValue, firstGeneration)
        arbiter.recordNativeValue(secondNativeValue, secondGeneration)

        assertTrue(
            arbiter.shouldSuppressControlledUpdate(
                value = secondNativeValue,
                updateGeneration = secondGeneration,
                latestGeneration = secondGeneration,
            ),
        )
        assertTrue(
            arbiter.shouldSuppressControlledUpdate(
                value = firstNativeValue,
                updateGeneration = firstGeneration,
                latestGeneration = secondGeneration,
            ),
        )
    }

    @Test
    fun legacyZeroSelectionEchoIsNotWrittenBackToNative() {
        val arbiter = TextInputControlledStateArbiter()
        val nativeValue = value(text = "1234567", selection = 0)
        val currentGeneration = generation()
        arbiter.recordNativeValue(nativeValue, currentGeneration)

        assertTrue(
            arbiter.shouldSuppressControlledUpdate(
                value = nativeValue,
                updateGeneration = currentGeneration,
                latestGeneration = currentGeneration,
            ),
        )
    }

    @Test
    fun transformedControlledValueRemainsAuthoritative() {
        val arbiter = TextInputControlledStateArbiter()
        val currentGeneration = generation()
        arbiter.recordNativeValue(
            value(text = "draft", selection = 5),
            currentGeneration,
        )

        assertFalse(
            arbiter.shouldSuppressControlledUpdate(
                value = value(text = "DRAFT", selection = 5),
                updateGeneration = currentGeneration,
                latestGeneration = currentGeneration,
            ),
        )
    }

    @Test
    fun equivalentHistoricalValueFromBusinessRemainsAuthoritative() {
        val arbiter = TextInputControlledStateArbiter()
        val firstNativeValue = value(text = "draft", selection = 1)
        val secondNativeValue = value(text = "draft", selection = 2)
        val firstGeneration = generation()
        val secondGeneration = generation()
        arbiter.recordNativeValue(firstNativeValue, firstGeneration)
        arbiter.recordNativeValue(secondNativeValue, secondGeneration)

        val normalizedBusinessValue = value(text = "draft", selection = 1)

        assertEquals(firstNativeValue, normalizedBusinessValue)
        assertFalse(firstNativeValue === normalizedBusinessValue)
        assertFalse(
            arbiter.shouldSuppressControlledUpdate(
                value = normalizedBusinessValue,
                updateGeneration = secondGeneration,
                latestGeneration = secondGeneration,
            ),
        )
        assertTrue(
            arbiter.shouldSuppressControlledUpdate(
                value = firstNativeValue,
                updateGeneration = firstGeneration,
                latestGeneration = secondGeneration,
            ),
        )
        assertTrue(
            arbiter.shouldSuppressControlledUpdate(
                value = secondNativeValue,
                updateGeneration = secondGeneration,
                latestGeneration = secondGeneration,
            ),
        )
    }

    @Test
    fun structurallyRetainedHistoricalCallbackIsAuthoritativeInCurrentGeneration() {
        val arbiter = TextInputControlledStateArbiter()
        val firstNativeValue = value(text = "draft", selection = 1)
        val secondNativeValue = value(text = "draft", selection = 2)
        val firstGeneration = generation()
        val secondGeneration = generation()
        arbiter.recordNativeValue(firstNativeValue, firstGeneration)
        arbiter.recordNativeValue(secondNativeValue, secondGeneration)

        assertFalse(
            arbiter.shouldSuppressControlledUpdate(
                value = firstNativeValue,
                updateGeneration = secondGeneration,
                latestGeneration = secondGeneration,
            ),
        )
        assertTrue(
            arbiter.shouldSuppressControlledUpdate(
                value = secondNativeValue,
                updateGeneration = secondGeneration,
                latestGeneration = secondGeneration,
            ),
        )
    }

    @Test
    fun remountedSessionCannotMatchPreviousSessionToken() {
        val arbiter = TextInputControlledStateArbiter()
        val oldSessionGeneration = generation()
        val remountedSessionGeneration = generation()
        val oldSessionValue = value(text = "draft", selection = 5)
        arbiter.recordNativeValue(oldSessionValue, oldSessionGeneration)

        assertFalse(
            arbiter.shouldSuppressControlledUpdate(
                value = oldSessionValue,
                updateGeneration = remountedSessionGeneration,
                latestGeneration = remountedSessionGeneration,
            ),
        )
    }

    @Test
    fun inputStateGenerationForcesEqualHistoricalValueReconciliation() {
        val historicalValue = value(text = "draft", selection = 1)
        val normalizedHistoricalValue = value(text = "draft", selection = 1)
        val previousGeneration = generation()
        val nextGeneration = generation()

        assertEquals(historicalValue, normalizedHistoricalValue)
        assertFalse(historicalValue === normalizedHistoricalValue)
        assertFalse(previousGeneration === nextGeneration)
        assertFalse(
            TextInputControlledUpdate(
                value = historicalValue,
                inputStateGeneration = previousGeneration,
            ) == TextInputControlledUpdate(
                value = normalizedHistoricalValue,
                inputStateGeneration = nextGeneration,
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

    private fun generation(): TextInputStateGeneration = TextInputStateGeneration()
}
