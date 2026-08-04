package com.tencent.kuikly.core.render.android.expand.module

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardHeightListenerRegistryTest {

    @Test
    fun replacementListenerReceivesVisibleHeightBeforeDismissal() {
        val registry = KeyboardHeightListenerRegistry()
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
        val registry = KeyboardHeightListenerRegistry()
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
        val registry = KeyboardHeightListenerRegistry()
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
        val registry = KeyboardHeightListenerRegistry()
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
        val registry = KeyboardHeightListenerRegistry()
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
}
