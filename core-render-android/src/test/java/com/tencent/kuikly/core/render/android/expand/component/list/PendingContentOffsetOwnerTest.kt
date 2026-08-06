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

package com.tencent.kuikly.core.render.android.expand.component.list

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * task #990 — a deferred content offset must survive a retry that still finds
 * the range too short.
 *
 * The production sequence: a programmatic scroll arrives before the list has a
 * layout, so it is installed as pending; a later layout retries it while the
 * native range is still short, so the applier re-suspends the same value; a row
 * then grows the range and the offset is finally applied.
 *
 * The defect was that the retry cleared the pending value *after* calling the
 * applier, deleting the re-suspension the applier had just made. Nothing owned
 * the offset when the range grew, so the scroll silently never happened — and it
 * is invisible from the Compose side, which reports geometry, placement and draw
 * all successful.
 *
 * These tests drive [KRPendingContentOffsetOwner] itself rather than a copy of
 * its ordering, so restoring the old clear-after-call would fail them.
 */
class PendingContentOffsetOwnerTest {

    /** Stands in only for the applier, which is the view's own setContentOffset. */
    private class Applier(var rangeReady: Boolean) {
        var applied: String? = null

        fun apply(owner: KRPendingContentOffsetOwner, value: String) {
            if (rangeReady) {
                applied = value
            } else {
                // What setContentOffset does when canScrollImmediately is false.
                owner.install(value)
            }
        }
    }

    @Test
    fun aRetryThatFindsTheRangeStillShortKeepsTheOffsetOwned() {
        val owner = KRPendingContentOffsetOwner()
        val applier = Applier(rangeReady = false)

        // 1. Requested before the list can scroll there.
        owner.install("0 231 0")
        assertEquals("0 231 0", owner.pending)

        // 2. Retried while the range is still short: the applier re-suspends,
        //    and that re-suspension must survive the retry.
        val short = owner.retry { applier.apply(owner, it) }
        assertEquals(KRPendingContentOffsetOutcome.RetriedRangeShort, short)
        assertEquals(
            "the offset must still be owned after a short-range retry",
            "0 231 0",
            owner.pending
        )
        assertNull(applier.applied)

        // 3. A row grows the range; the offset is finally applied.
        applier.rangeReady = true
        val ready = owner.retry { applier.apply(owner, it) }
        assertEquals(KRPendingContentOffsetOutcome.AppliedRangeReady, ready)
        assertEquals("0 231 0", applier.applied)
        assertEquals("applying it releases ownership", "", owner.pending)
    }

    @Test
    fun anAlreadyReadyRangeAppliesOnTheFirstRetry() {
        val owner = KRPendingContentOffsetOwner()
        val applier = Applier(rangeReady = true)
        owner.install("0 54 0")

        assertEquals(
            KRPendingContentOffsetOutcome.AppliedRangeReady,
            owner.retry { applier.apply(owner, it) }
        )
        assertEquals("0 54 0", applier.applied)
        assertEquals("", owner.pending)
    }

    @Test
    fun aRetryWithNothingHeldDoesNotCallTheApplier() {
        val owner = KRPendingContentOffsetOwner()
        var calls = 0

        assertEquals(
            KRPendingContentOffsetOutcome.NothingPending,
            owner.retry { calls += 1 }
        )
        assertEquals("an empty owner must not invoke the applier", 0, calls)
    }

    @Test
    fun repeatedShortRangeRetriesNeverLoseTheOffset() {
        // The failure was permanent rather than transient: once the value was
        // dropped no later range growth could recover it. Several short retries
        // in a row must still leave it owned.
        val owner = KRPendingContentOffsetOwner()
        val applier = Applier(rangeReady = false)
        owner.install("0 231 0")

        repeat(5) {
            owner.retry { applier.apply(owner, it) }
            assertEquals("0 231 0", owner.pending)
        }

        applier.rangeReady = true
        owner.retry { applier.apply(owner, it) }
        assertEquals("0 231 0", applier.applied)
    }
}
