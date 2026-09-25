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

package com.tencent.kuikly.core.render.android.scheduler

/**
 * Token-keyed last-wins registry for posted runnables.
 *
 * Registering a new runnable under an already-used token evicts the pending
 * previous one (via [removePosted], e.g. `Handler.removeCallbacks`) so stale
 * work never executes. Used to coalesce touch-MOVE dispatches: only the
 * latest position matters, so queued-but-unstarted moves are replaced rather
 * than replayed after the context thread recovers.
 *
 * Pure Kotlin for unit-testability; thread-safe.
 */
internal class ReplaceableTaskRegistry(
    private val removePosted: (Runnable) -> Unit,
) {

    private val lock = Any()
    private val tasks = HashMap<String, Runnable>()

    /**
     * Registers [task] under [token], evicting any pending task with the same
     * token. Returns the runnable to post; it unregisters itself before
     * running (unless already superseded).
     */
    fun register(token: String, task: () -> Unit): Runnable {
        val wrapped = TokenRunnable(token, task)
        registerRunnable(token, wrapped)
        return wrapped
    }

    private inner class TokenRunnable(
        private val token: String,
        private val task: () -> Unit,
    ) : Runnable {
        override fun run() {
            synchronized(lock) {
                if (tasks[token] === this) {
                    tasks.remove(token)
                }
            }
            task()
        }
    }

    /**
     * Registers an already-built [runnable] under [token], evicting any
     * pending same-token runnable. Used for sync dispatches whose posted
     * runnable must remain evictable after their waiter timed out.
     */
    fun registerRunnable(token: String, runnable: Runnable) {
        synchronized(lock) {
            tasks.put(token, runnable)?.also(removePosted)
        }
    }

    /** Drops the registration for [token] if it still holds [runnable]. */
    fun unregisterIfCurrent(token: String, runnable: Runnable) {
        synchronized(lock) {
            if (tasks[token] === runnable) {
                tasks.remove(token)
            }
        }
    }

    /** Evicts the pending runnable for [token] without running it, if any. */
    fun evict(token: String) {
        synchronized(lock) {
            tasks.remove(token)?.also(removePosted)
        }
    }

    /** Evicts every pending runnable whose token starts with [prefix]. */
    fun evictByPrefix(prefix: String) {
        val evicted = mutableListOf<Runnable>()
        synchronized(lock) {
            val iterator = tasks.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.startsWith(prefix)) {
                    evicted += entry.value
                    iterator.remove()
                }
            }
        }
        evicted.forEach(removePosted)
    }
}
