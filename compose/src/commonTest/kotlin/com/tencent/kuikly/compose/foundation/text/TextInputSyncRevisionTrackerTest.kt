package com.tencent.kuikly.compose.foundation.text

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.views.TextInputState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextInputSyncRevisionTrackerTest {
    @Test
    fun delayedCallbackFromBeforeHostClearRemainsStaleAcrossSchedulingTurns() {
        val tracker = TextInputSyncRevisionTracker()
        val editRevision = tracker.issue()
        assertFalse(tracker.isStale(editRevision))

        val clearRevision = tracker.issue()
        repeat(3) {
            assertTrue(tracker.isStale(editRevision))
        }
        assertFalse(tracker.isStale(clearRevision))
    }

    @Test
    fun currentRevisionAcceptsAckDifferentImeCommitAndSameTextReentry() {
        val tracker = TextInputSyncRevisionTracker()
        tracker.issue()
        val clearRevision = tracker.issue()

        assertFalse(tracker.isStale(clearRevision)) // programmatic clear ack
        assertFalse(tracker.isStale(clearRevision)) // an IME composition update
        assertFalse(tracker.isStale(clearRevision)) // an IME commit with different text
        assertFalse(tracker.isStale(clearRevision)) // the submitted text pasted again
    }

    @Test
    fun missingRevisionRemainsBackwardCompatible() {
        val tracker = TextInputSyncRevisionTracker()
        tracker.issue()
        val legacyPayload = TextInputState.decode(JSONObject("""{"text":"legacy edit"}"""))

        assertEquals(null, legacyPayload.syncRevision)
        assertFalse(tracker.isStale(legacyPayload.syncRevision))
    }

    @Test
    fun zeroRevisionIsStaleAfterControlledStateHasBeenIssued() {
        val tracker = TextInputSyncRevisionTracker()
        tracker.issue()

        assertTrue(tracker.isStale(0))
    }

    @Test
    fun newerRevisionIsNotDiscardedAsStale() {
        val tracker = TextInputSyncRevisionTracker()
        tracker.issue()

        assertFalse(tracker.isStale(2))
    }

    @Test
    fun textInputStateCarriesRevisionAcrossRenderProtocol() {
        val encoded = TextInputState(text = "sent body", syncRevision = 7).encode()

        val decoded = TextInputState.decode(JSONObject(encoded))

        assertEquals(7, decoded.syncRevision)
    }
}
