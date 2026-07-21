/*
 * Diagnostic-only regression coverage for task #37. This branch is never merged.
 */
package com.tencent.kuikly.compose.foundation.drawer

import kotlin.test.Test
import kotlin.test.assertTrue

class DrawerInternalPagerStateDiagnosticTest {
    @Test
    fun diagnosticOwnerIdDoesNotReadScrollableStateDuringConstruction() {
        val state = object : DrawerInternalPagerState(
            tracker = DrawerSizeTracker { _, availableSpace -> availableSpace }
        ) {
            override val pageCount: Int = 2
        }

        assertTrue(state.diagnosticOwnerId > 0L)
    }
}
