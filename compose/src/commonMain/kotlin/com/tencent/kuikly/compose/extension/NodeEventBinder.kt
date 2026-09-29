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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Owns the current delegate behind a callback whose identity is retained by a native node.
 *
 * A `ComposeNode.factory` or `MakeKuiklyComposeNode.viewInit` block runs only when its node is
 * created. Native event callbacks installed there must therefore keep a stable identity while
 * dispatching to the latest composition values.
 */
internal class NodeEventBinder<P>(initialEvent: (P) -> Unit) {
    // Snapshot state is intentional: if recomposition is aborted, its delegate update must not
    // leak to a native callback before Compose commits that composition.
    private var currentEvent: (P) -> Unit by mutableStateOf(initialEvent)

    val event: (P) -> Unit = { parameter ->
        currentEvent(parameter)
    }

    fun update(event: (P) -> Unit) {
        currentEvent = event
    }
}

/**
 * Returns a stable one-argument native event callback that always delegates to [event] from the
 * latest composition.
 */
@Composable
internal fun <P> updatedNodeEvent(event: (P) -> Unit): (P) -> Unit {
    val binder = remember { NodeEventBinder(event) }
    binder.update(event)
    return binder.event
}
