package com.tencent.kuikly.compose.gestures

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class ScrollViewBindingGateTest {
    @Test
    fun layoutReadyWithoutBindingDoesNotSubmitUntilCurrentHandleArrives() = runTest {
        val gate = ScrollViewBindingGate<FakeHandle>()
        val submitted = CompletableDeferred<FakeHandle>()
        var calls = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withCurrentBinding { handle ->
                calls += 1
                submitted.complete(handle)
            }
        }

        assertEquals(0, calls)
        val current = FakeHandle("current")
        gate.update(current)

        assertSame(current, submitted.await())
        assertEquals(1, calls)
        job.join()
    }

    @Test
    fun cancellationBeforeBindingProducesZeroSubmission() = runTest {
        val gate = ScrollViewBindingGate<FakeHandle>()
        var calls = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withCurrentBinding { calls += 1 }
        }

        job.cancelAndJoin()
        gate.update(FakeHandle("late"))

        assertEquals(0, calls)
    }

    @Test
    fun clearedOldBindingCannotReceiveSuccessorSubmission() = runTest {
        val gate = ScrollViewBindingGate<FakeHandle>()
        val old = FakeHandle("old")
        val current = FakeHandle("current")
        gate.update(old)
        gate.update(null)
        gate.update(current)

        var submitted: FakeHandle? = null
        gate.withCurrentBinding {
            submitted = it
            it.calls += 1
        }

        assertSame(current, submitted)
        assertEquals(0, old.calls)
        assertEquals(1, current.calls)
    }

    private data class FakeHandle(
        val name: String,
        var calls: Int = 0
    )
}
