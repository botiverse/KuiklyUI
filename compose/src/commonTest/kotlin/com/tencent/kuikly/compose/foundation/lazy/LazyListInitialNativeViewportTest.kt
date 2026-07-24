/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2026 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI.
 */

package com.tencent.kuikly.compose.foundation.lazy

import kotlin.test.Test
import kotlin.test.assertEquals

class LazyListInitialNativeViewportTest {

    @Test
    fun nativeViewportIsPreparedBeforeAnyChildFrameIsPlaced() {
        val commits = mutableListOf<String>()

        placeLazyListChildrenWithInitialNativeViewport(
            placementScope = Unit,
            prepareInitialNativeViewport = { commits += "native-offset" },
            placement = { commits += "child-frames" },
        )

        assertEquals(listOf("native-offset", "child-frames"), commits)
    }
}
