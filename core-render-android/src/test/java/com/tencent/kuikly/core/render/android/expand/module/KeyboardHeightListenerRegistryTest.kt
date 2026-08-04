package com.tencent.kuikly.core.render.android.expand.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class KeyboardHeightListenerRegistryTest {

    @Test
    fun replacementListenerReceivesVisibleHeightBeforeDismissal() {
        val registry = registryWithoutThreadGuard()
        val firstValues = mutableListOf<Int>()
        val firstListener = recordingListener(firstValues)

        registry.addListener(firstListener)
        registry.dispatchHeight(900)
        registry.removeListener(firstListener)

        val replacementValues = mutableListOf<Int>()
        registry.addListener(recordingListener(replacementValues))
        registry.dispatchHeight(0)

        assertEquals(listOf(0, 900), firstValues)
        assertEquals(listOf(900, 0), replacementValues)
    }

    @Test
    fun unchangedHeightIsNotRedispatched() {
        val registry = registryWithoutThreadGuard()
        val values = mutableListOf<Int>()

        registry.addListener(recordingListener(values))
        registry.dispatchHeight(640)
        registry.dispatchHeight(640)
        registry.dispatchHeight(0)
        registry.dispatchHeight(0)

        assertEquals(listOf(0, 640, 0), values)
    }

    @Test
    fun removedListenerDoesNotReceiveLaterTransitions() {
        val registry = registryWithoutThreadGuard()
        val removedValues = mutableListOf<Int>()
        val removedListener = recordingListener(removedValues)

        registry.addListener(removedListener)
        registry.dispatchHeight(720)
        registry.removeListener(removedListener)
        registry.dispatchHeight(0)

        assertEquals(listOf(0, 720), removedValues)
    }

    @Test
    fun replacementForwardsZeroWhenDismissalHappenedWithoutListener() {
        val registry = registryWithoutThreadGuard()
        var pageInset = 900

        registry.dispatchHeight(900)
        val firstListener = deduplicatingListener { pageInset = it }
        registry.addListener(firstListener)
        registry.removeListener(firstListener)

        registry.dispatchHeight(0)
        assertEquals(900, pageInset)

        registry.addListener(deduplicatingListener { pageInset = it })

        assertEquals(0, pageInset)
    }

    @Test
    fun firstHeightIsForwardedThenDuplicatesAreDeduplicated() {
        val gate = KeyboardHeightDispatchGate()

        assertEquals(true, gate.accept(0))
        assertEquals(false, gate.accept(0))
        assertEquals(true, gate.accept(900))
        assertEquals(false, gate.accept(900))
    }

    @Test
    fun successiveReplacementListenersEachForwardTheirFirstReplay() {
        val registry = registryWithoutThreadGuard()
        registry.dispatchHeight(900)

        val firstValues = mutableListOf<Int>()
        val firstListener = deduplicatingListener { firstValues += it }
        registry.addListener(firstListener)
        registry.removeListener(firstListener)

        val secondValues = mutableListOf<Int>()
        val secondListener = deduplicatingListener { secondValues += it }
        registry.addListener(secondListener)
        registry.removeListener(secondListener)

        val thirdValues = mutableListOf<Int>()
        registry.addListener(deduplicatingListener { thirdValues += it })

        assertEquals(listOf(900), firstValues)
        assertEquals(listOf(900), secondValues)
        assertEquals(listOf(900), thirdValues)
    }

    private fun recordingListener(values: MutableList<Int>): KeyboardStatusListener =
        object : KeyboardStatusListener {
            override fun onHeightChanged(height: Int) {
                values += height
            }
        }

    private fun deduplicatingListener(onHeightChanged: (Int) -> Unit): KeyboardStatusListener {
        val gate = KeyboardHeightDispatchGate()
        return object : KeyboardStatusListener {
            override fun onHeightChanged(height: Int) {
                if (gate.accept(height)) onHeightChanged(height)
            }
        }
    }

    private fun registryWithoutThreadGuard(): KeyboardHeightListenerRegistry =
        KeyboardHeightListenerRegistry(
            failFastOnThreadViolation = false,
            isOnMainThread = { true }
        )
}

@RunWith(RobolectricTestRunner::class)
class KeyboardHeightListenerRegistryMainThreadTest {

    @Test
    fun addListenerRejectsWorkerThread() {
        assertWorkerThreadRejected("addListener") { registry ->
            registry.addListener(recordingListener())
        }
    }

    @Test
    fun removeListenerRejectsWorkerThread() {
        assertWorkerThreadRejected("removeListener") { registry ->
            registry.removeListener(recordingListener())
        }
    }

    @Test
    fun dispatchHeightRejectsWorkerThread() {
        assertWorkerThreadRejected("dispatchHeight") { registry ->
            registry.dispatchHeight(640)
        }
    }

    @Test
    fun clearRejectsWorkerThread() {
        assertWorkerThreadRejected("clear") { registry ->
            registry.clear()
        }
    }

    @Test
    fun releaseModeReportsEveryWorkerThreadEntryWithoutThrowing() {
        val violations = mutableListOf<String>()
        val registry =
            KeyboardHeightListenerRegistry(
                failFastOnThreadViolation = false,
                isOnMainThread = { false },
                reportThreadViolation = { violations += it }
            )
        val listener = recordingListener()

        registry.addListener(listener)
        registry.dispatchHeight(640)
        registry.removeListener(listener)
        registry.clear()

        assertEquals(4, violations.size)
        assertTrue(violations[0].contains("addListener"))
        assertTrue(violations[1].contains("dispatchHeight"))
        assertTrue(violations[2].contains("removeListener"))
        assertTrue(violations[3].contains("clear"))
    }

    private fun assertWorkerThreadRejected(
        operation: String,
        call: (KeyboardHeightListenerRegistry) -> Unit
    ) {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "keyboard-registry-worker")
        }
        try {
            val failure =
                try {
                    executor.submit {
                        call(
                            KeyboardHeightListenerRegistry(
                                failFastOnThreadViolation = true
                            )
                        )
                    }
                        .get(5, TimeUnit.SECONDS)
                    null
                } catch (error: ExecutionException) {
                    error.cause
                }

            assertTrue(failure is IllegalStateException)
            assertTrue(failure?.message.orEmpty().contains(operation))
            assertTrue(failure?.message.orEmpty().contains("keyboard-registry-worker"))
        } finally {
            executor.shutdownNow()
        }
    }

    private fun recordingListener(): KeyboardStatusListener =
        object : KeyboardStatusListener {
            override fun onHeightChanged(height: Int) = Unit
        }
}
