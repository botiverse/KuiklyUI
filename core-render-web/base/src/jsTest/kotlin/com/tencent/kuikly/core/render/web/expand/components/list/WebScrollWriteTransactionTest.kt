package com.tencent.kuikly.core.render.web.expand.components.list

import com.tencent.kuikly.core.render.web.ktx.KuiklyRenderCallback
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebScrollWriteTransactionTest {
    @Test
    fun reentrantReplacementCallbackKeepsNewestCurrent() {
        val arbiter = WebScrollWriteOperationArbiter()
        lateinit var operationC: WebScrollWriteOperation
        val operationA = operation(1, KuiklyRenderCallback {
            operationC = operation(3, null)
            arbiter.install(operationC)
        })
        val operationB = operation(2, null)

        arbiter.install(operationA)
        val replaced = arbiter.install(operationB)
        replaced?.callback?.invoke(emptyMap<String, Any>())

        assertTrue(operationA.terminal)
        assertTrue(operationB.terminal)
        assertTrue(arbiter.isCurrent(operationC))
    }

    @Test
    fun terminalCanOnlyBeClaimedOnce() {
        val arbiter = WebScrollWriteOperationArbiter()
        val operation = operation(1, KuiklyRenderCallback { })
        arbiter.install(operation)

        assertTrue(arbiter.complete(operation) != null)
        assertTrue(arbiter.complete(operation) == null)
        assertFalse(arbiter.isCurrent(operation))
    }

    @Test
    fun structuredResultUsesFrozenWireCodeAndRevisions() {
        val result = webScrollWriteResult(WebScrollWriteResultCode.Replaced, 7, 8, 9)
        assertEquals(0, result["committed"])
        assertEquals(6, result["resultCode"])
        assertEquals(7L, result["nativeInteractionEpoch"])
        assertEquals(8L, result["layoutRevision"])
        assertEquals(9L, result["insetRevision"])
    }

    private fun operation(
        sequence: Long,
        callback: com.tencent.kuikly.core.render.web.ktx.KuiklyRenderCallback?,
    ) = WebScrollWriteOperation(
        sequence = sequence,
        kind = WebScrollWriteKind.ContentOffset,
        callback = callback,
        generation = 0,
        composeOperation = 0,
        interactionEpoch = 0,
        layoutRevision = 0,
        insetRevision = 0,
    )
}
