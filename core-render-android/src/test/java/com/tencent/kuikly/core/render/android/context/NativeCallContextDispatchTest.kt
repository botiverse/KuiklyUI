package com.tencent.kuikly.core.render.android.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCallContextDispatchTest {

    @Test
    fun `off-context fire-and-forget calls preserve scheduler FIFO`() {
        val scheduled = mutableListOf<() -> Unit>()
        val calls = mutableListOf<Int>()
        val results = mutableListOf<Any?>()

        Thread {
            repeat(3) { index ->
                results +=
                    dispatchKuiklyNativeCall(
                        isContextThread = false,
                        requiresContextThread = false,
                        scheduleOnContextThread = scheduled::add,
                        call = { calls += index }
                    )
            }
        }.apply {
            start()
            join()
        }

        assertTrue(calls.isEmpty())
        assertEquals(listOf(null, null, null), results)
        scheduled.forEach { it() }
        assertEquals(listOf(0, 1, 2), calls)
    }

    @Test
    fun `context-thread call stays inline and returns result`() {
        var scheduled = false

        val result = dispatchKuiklyNativeCall(
            isContextThread = true,
            requiresContextThread = true,
            scheduleOnContextThread = { scheduled = true },
            call = { "result" }
        )

        assertEquals("result", result)
        assertFalse(scheduled)
    }

    @Test
    fun `off-context synchronous call fails without scheduling`() {
        var scheduled = false

        assertThrows(IllegalStateException::class.java) {
            dispatchKuiklyNativeCall(
                isContextThread = false,
                requiresContextThread = true,
                scheduleOnContextThread = { scheduled = true },
                call = { "unreachable" }
            )
        }

        assertFalse(scheduled)
    }

    @Test
    fun `native method classification matches renderer inline contract`() {
        val asyncModuleArgs = listOf(null, null, null, null, null, 0)
        val syncModuleArgs = listOf(null, null, null, null, null, 1)

        assertFalse(
            kuiklyNativeMethodRequiresContextThread(
                KuiklyRenderNativeMethod.KuiklyRenderNativeMethodCallModuleMethod,
                asyncModuleArgs
            )
        )
        assertTrue(
            kuiklyNativeMethodRequiresContextThread(
                KuiklyRenderNativeMethod.KuiklyRenderNativeMethodCallModuleMethod,
                syncModuleArgs
            )
        )
        assertFalse(
            kuiklyNativeMethodRequiresContextThread(
                KuiklyRenderNativeMethod.KuiklyRenderNativeMethodCallViewMethod,
                emptyList()
            )
        )
        assertTrue(
            kuiklyNativeMethodRequiresContextThread(
                KuiklyRenderNativeMethod.KuiklyRenderNativeMethodSetTimeout,
                emptyList()
            )
        )
        assertFalse(
            kuiklyNativeMethodRequiresContextThread(
                KuiklyRenderNativeMethod.KuiklyRenderNativeMethodCallTDFNativeMethod,
                asyncModuleArgs
            )
        )
        assertTrue(
            kuiklyNativeMethodRequiresContextThread(
                KuiklyRenderNativeMethod.KuiklyRenderNativeMethodCallTDFNativeMethod,
                syncModuleArgs
            )
        )
        assertFalse(
            kuiklyNativeMethodRequiresContextThread(
                KuiklyRenderNativeMethod.KuiklyRenderNativeMethodFireFatalException,
                emptyList()
            )
        )
        assertFalse(
            kuiklyNativeMethodRequiresContextThread(
                KuiklyRenderNativeMethod.KuiklyRenderNativeMethodCallTDFNativeMethod,
                emptyList()
            )
        )
    }
}
