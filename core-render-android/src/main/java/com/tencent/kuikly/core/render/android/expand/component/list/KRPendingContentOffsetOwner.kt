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

    var pending: String = ""
        private set

    val hasPending: Boolean
        get() = pending.isNotEmpty()

    /** Holds [value] until something can apply it. */
    fun install(value: String) {
        pending = value
    }

    /** Drops ownership, e.g. once the offset has been applied. */
    fun clear() {
        pending = ""
    }

    /**
     * Retries the held offset.
     *
     * [apply] performs the real work and is expected to call [install] again if
     * it still cannot scroll. Returns the outcome so the caller can record which
     * of the three states this pass reached, since none of them are observable
     * from the Compose side.
     */
    fun retry(apply: (String) -> Unit): KRPendingContentOffsetOutcome {
        val captured = pending
        if (captured.isEmpty()) {
            return KRPendingContentOffsetOutcome.NothingPending
        }
        pending = ""
        apply(captured)
        return if (hasPending) {
            KRPendingContentOffsetOutcome.RetriedRangeShort
        } else {
            KRPendingContentOffsetOutcome.AppliedRangeReady
        }
    }
}

internal enum class KRPendingContentOffsetOutcome {
    NothingPending,
    RetriedRangeShort,
    AppliedRangeReady
}
