package com.tencent.kuikly.core.render.android.expand.component

import org.junit.Assert.assertEquals
import org.junit.Test

class TextInputSyncRevisionStateTest {
    @Test
    fun eventPayloadKeepsRevisionCapturedBeforeNewControlledWrite() {
        val state = TextInputSyncRevisionState()
        state.apply(1)
        val delayedEventPayload = state.snapshot(mutableMapOf("text" to "sent body"))

        state.apply(2)

        assertEquals(1, delayedEventPayload["syncRevision"])
    }

    @Test
    fun currentUserEditCarriesLatestControlledRevision() {
        val state = TextInputSyncRevisionState()
        state.apply(1)
        state.apply(2)

        val currentEventPayload = state.snapshot(mutableMapOf("text" to "new draft"))

        assertEquals(2, currentEventPayload["syncRevision"])
    }

    @Test
    fun legacyControlledWriteDoesNotResetRevision() {
        val state = TextInputSyncRevisionState()
        state.apply(2)

        state.apply(null)

        assertEquals(2, state.current)
    }
}
