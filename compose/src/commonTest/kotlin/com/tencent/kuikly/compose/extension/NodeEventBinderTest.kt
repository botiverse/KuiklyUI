/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI;
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://github.com/Tencent-TDS/KuiklyUI/blob/main/LICENSE
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tencent.kuikly.compose.extension

import androidx.compose.runtime.snapshots.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class NodeEventBinderTest {
    @Test
    fun retainedHandlerTracksInactiveActiveInactiveLifecycle() {
        val dispatches = mutableListOf<String>()
        val binder = NodeEventBinder<String> { dispatches += "inactive:$it" }
        val retainedHandler = binder.event

        retainedHandler("prewarm")
        binder.update { dispatches += "active:$it" }
        retainedHandler("visible")
        binder.update { dispatches += "inactive:$it" }
        retainedHandler("kept-alive")

        assertEquals(
            listOf("inactive:prewarm", "active:visible", "inactive:kept-alive"),
            dispatches,
        )
        assertSame(retainedHandler, binder.event)
    }

    @Test
    fun abortedSnapshotDoesNotPublishUncommittedDelegate() {
        val dispatches = mutableListOf<String>()
        val binder = NodeEventBinder<String> { dispatches += "committed:$it" }
        val abortedComposition = Snapshot.takeMutableSnapshot()

        try {
            abortedComposition.enter {
                binder.update { dispatches += "aborted:$it" }
            }
        } finally {
            abortedComposition.dispose()
        }

        binder.event("native-event")

        assertEquals(listOf("committed:native-event"), dispatches)
    }
}
