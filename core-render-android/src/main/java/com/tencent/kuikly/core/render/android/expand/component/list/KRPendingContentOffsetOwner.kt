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

/**
 * Ownership of a content offset that cannot be applied yet.
 *
 * task #990: a programmatic scroll can arrive before the list has a layout, or
 * while the native range is still shorter than the requested offset. The offset
 * is held until it can be applied.
 *
 * The retry consumes the held value *before* asking the applier to run, so that
 * an applier which re-suspends the same value keeps ownership. Clearing after
 * the call deleted that re-suspension, and when a later row finally grew the
 * range nothing owned the offset — the programmatic scroll silently never
 * happened, while the Compose side reported geometry, placement and draw all
 * successful.
 *
 * Extracted so the behaviour test drives this code rather than a copy of it: a
 * test that reimplemented the ordering would stay green if the production
 * ordering regressed.
 */
internal class KRPendingContentOffsetOwner {

    private var nextGeneration = 0L

    var pending: KRPendingContentOffset? = null
        private set

    val hasPending: Boolean
        get() = pending != null

    /**
     * Installs a new request, or reinstalls [retained] during a retry.
     *
     * A new request always receives a new opaque generation, even when its payload is byte-for-byte
     * equal to the previous one. That distinction is what prevents a callback replacement from
     * being misreported as the older request merely waiting for more range.
     */
    fun install(
        value: String,
        retained: KRPendingContentOffset? = null,
    ): KRPendingContentOffset {
        require(value.isNotEmpty()) { "a pending content offset must not be empty" }
        val owner =
            retained?.also {
                require(it.value == value) { "a retained owner cannot change its payload" }
            } ?: KRPendingContentOffset(
                generation = ++nextGeneration,
                value = value,
            )
        pending = owner
        return owner
    }

    /** Drops the current slot without making an older owner current again. */
    fun clear() {
        pending = null
    }

    /** Consumes [owner] before its physical apply, leaving any reentrant replacement untouched. */
    fun consume(owner: KRPendingContentOffset): Boolean {
        if (pending !== owner) return false
        pending = null
        return true
    }

    fun isLatest(owner: KRPendingContentOffset): Boolean =
        owner.generation == nextGeneration

    /**
     * Retries the held offset.
     *
     * [apply] performs the real work and is expected to call [install] again with the captured
     * owner if it still cannot scroll. The opaque generation distinguishes that same-owner retry
     * from a newer request installed reentrantly while the physical write runs.
     */
    fun retry(apply: (KRPendingContentOffset) -> Unit): KRPendingContentOffsetOutcome {
        val captured = pending
        if (captured == null) {
            return KRPendingContentOffsetOutcome.NothingPending
        }
        pending = null
        apply(captured)
        return when {
            !isLatest(captured) -> KRPendingContentOffsetOutcome.ReplacedByNewOwner
            pending === captured -> KRPendingContentOffsetOutcome.RetriedRangeShort
            pending == null -> KRPendingContentOffsetOutcome.AppliedRangeReady
            else -> KRPendingContentOffsetOutcome.ReplacedByNewOwner
        }
    }
}

internal class KRPendingContentOffset internal constructor(
    val generation: Long,
    val value: String,
)

internal enum class KRPendingContentOffsetOutcome {
    NothingPending,
    RetriedRangeShort,
    AppliedRangeReady,
    ReplacedByNewOwner,
}
