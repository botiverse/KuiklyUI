/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2026 Tencent. All rights reserved.
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

package com.tencent.kuikly.compose.material3

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PullToRefreshStateTest {
    @Test
    fun releaseKeepsRefreshingStateWhenInsetIsHeld() {
        assertEquals(
            PullState.REFRESHING,
            pullStateAfterRefreshRelease(holdRefreshInset = true)
        )
    }

    @Test
    fun releaseReturnsIdleWhenInsetIsNotHeld() {
        assertEquals(
            PullState.IDLE,
            pullStateAfterRefreshRelease(holdRefreshInset = false)
        )
    }

    @Test
    fun thresholdCrossingDoesNotScheduleEndDragInsetWhenHoldIsDisabled() {
        assertEquals(
            0f,
            pullRefreshEndDragInset(
                holdRefreshInset = false,
                refreshThreshold = 80f
            )
        )
    }

    @Test
    fun thresholdCrossingKeepsLegacyEndDragInsetByDefault() {
        assertEquals(
            80f,
            pullRefreshEndDragInset(
                holdRefreshInset = true,
                refreshThreshold = 80f
            )
        )
    }

    @Test
    fun noHoldReleaseClearsInsetsBeforeExactlyOnceRefreshCallback() {
        val state = PullToRefreshState(isRefreshing = false).apply {
            updatePullState(PullState.PULLING)
            updateProgress(1f)
        }
        val events = mutableListOf<String>()

        repeat(2) {
            state.releasePullToRefresh(
                snapshot = snapshot(holdRefreshInset = false),
                clearEndDragInset = { events += "end-drag-inset=0" },
                clearCurrentInset = { events += "current-inset=0" },
                onRefresh = { events += "refresh" }
            )
        }

        assertEquals(PullState.IDLE, state.pullState)
        assertEquals(0f, state.pullProgress)
        assertEquals(
            listOf("end-drag-inset=0", "current-inset=0", "refresh"),
            events
        )
    }

    @Test
    fun heldReleaseKeepsLegacyStateAndDispatchesExactlyOnce() {
        val state = PullToRefreshState(isRefreshing = false).apply {
            updatePullState(PullState.PULLING)
            updateProgress(1f)
        }
        val events = mutableListOf<String>()

        repeat(2) {
            state.releasePullToRefresh(
                snapshot = snapshot(holdRefreshInset = true),
                clearEndDragInset = { events += "unexpected-end-drag-clear" },
                clearCurrentInset = { events += "unexpected-current-clear" },
                onRefresh = { events += "refresh" }
            )
        }

        assertEquals(PullState.REFRESHING, state.pullState)
        assertEquals(1f, state.pullProgress)
        assertEquals(listOf("refresh"), events)
    }

    @Test
    fun sameCollectorAppliesTrueToFalseAndUpdatedThresholdOnNextGesture() {
        val snapshots = collectRuntimeSnapshots(
            initialConfig = PullToRefreshRuntimeConfig(
                holdRefreshInset = true,
                refreshThresholdPx = 80f,
                refreshThresholdLogical = 80f
            ),
            updatedConfig = PullToRefreshRuntimeConfig(
                holdRefreshInset = false,
                refreshThresholdPx = 120f,
                refreshThresholdLogical = 120f
            )
        )
        assertTrue(snapshots[0].isThresholdReached)
        assertEquals(80f, snapshots[0].endDragInset)
        assertFalse(snapshots[1].isThresholdReached)
        assertEquals(120f, snapshots[1].refreshThresholdPx)
        assertEquals(0f, snapshots[1].endDragInset)
        assertTrue(snapshots[2].isThresholdReached)
        assertEquals(0f, snapshots[2].endDragInset)

        val state = PullToRefreshState(isRefreshing = false)
        val events = mutableListOf<String>()
        val plannedInsets = mutableListOf<Float>()

        assertTrue(state.startPullToRefresh(snapshots[2], plannedInsets::add))
        repeat(2) {
            state.releasePullToRefresh(
                snapshot = snapshots[2],
                clearEndDragInset = { events += "end-drag-inset=0" },
                clearCurrentInset = { events += "current-inset=0" },
                onRefresh = { events += "no-hold-refresh" }
            )
        }

        assertEquals(PullState.IDLE, state.pullState)
        assertEquals(listOf(0f), plannedInsets)
        assertEquals(
            listOf(
                "end-drag-inset=0",
                "current-inset=0",
                "no-hold-refresh"
            ),
            events
        )
    }

    @Test
    fun sameCollectorAppliesFalseToTrueAndUpdatedThresholdOnNextGesture() {
        val snapshots = collectRuntimeSnapshots(
            initialConfig = PullToRefreshRuntimeConfig(
                holdRefreshInset = false,
                refreshThresholdPx = 80f,
                refreshThresholdLogical = 80f
            ),
            updatedConfig = PullToRefreshRuntimeConfig(
                holdRefreshInset = true,
                refreshThresholdPx = 120f,
                refreshThresholdLogical = 120f
            )
        )
        assertTrue(snapshots[0].isThresholdReached)
        assertEquals(0f, snapshots[0].endDragInset)
        assertFalse(snapshots[1].isThresholdReached)
        assertEquals(120f, snapshots[1].refreshThresholdPx)
        assertEquals(120f, snapshots[1].endDragInset)
        assertTrue(snapshots[2].isThresholdReached)
        assertEquals(120f, snapshots[2].endDragInset)

        val state = PullToRefreshState(isRefreshing = false)
        val events = mutableListOf<String>()
        val plannedInsets = mutableListOf<Float>()

        assertTrue(state.startPullToRefresh(snapshots[2], plannedInsets::add))
        repeat(2) {
            state.releasePullToRefresh(
                snapshot = snapshots[2],
                clearEndDragInset = { events += "unexpected-held-end-drag-clear" },
                clearCurrentInset = { events += "unexpected-held-current-clear" },
                onRefresh = { events += "held-refresh" }
            )
        }

        assertEquals(PullState.REFRESHING, state.pullState)
        assertEquals(listOf(120f), plannedInsets)
        assertEquals(listOf("held-refresh"), events)
    }

    private fun collectRuntimeSnapshots(
        initialConfig: PullToRefreshRuntimeConfig,
        updatedConfig: PullToRefreshRuntimeConfig
    ): List<PullToRefreshSnapshot> = runBlocking {
        val configState = mutableStateOf(initialConfig)
        val contentOffset = mutableStateOf(-100)
        val snapshotProvider = PullToRefreshSnapshotProvider(configState)
        val firstSnapshot = CompletableDeferred<Unit>()
        val updatedConfigSnapshot = CompletableDeferred<Unit>()
        var emissionCount = 0
        val snapshots = async(start = CoroutineStart.UNDISPATCHED) {
            snapshotFlow {
                snapshotProvider.snapshot(
                    contentOffset = contentOffset.value,
                    isAtTop = true,
                    isDragging = true,
                    isRefreshing = false
                )
            }
                .onEach {
                    emissionCount += 1
                    when (emissionCount) {
                        1 -> firstSnapshot.complete(Unit)
                        2 -> updatedConfigSnapshot.complete(Unit)
                    }
                }
                .take(3)
                .toList()
        }

        withTimeout(5_000) { firstSnapshot.await() }
        configState.value = updatedConfig
        Snapshot.sendApplyNotifications()
        withTimeout(5_000) { updatedConfigSnapshot.await() }
        contentOffset.value = -130
        Snapshot.sendApplyNotifications()
        withTimeout(5_000) { snapshots.await() }
    }

    private fun snapshot(
        holdRefreshInset: Boolean,
        refreshThreshold: Float = 80f,
        contentOffset: Int = -100
    ): PullToRefreshSnapshot = PullToRefreshSnapshot(
        contentOffset = contentOffset,
        isAtTop = true,
        isDragging = false,
        isRefreshing = false,
        holdRefreshInset = holdRefreshInset,
        refreshThresholdPx = refreshThreshold,
        refreshThresholdLogical = refreshThreshold
    )
}
