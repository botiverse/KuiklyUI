/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI.
 */

package com.tencent.kuikly.compose.scroller

import com.tencent.kuikly.compose.foundation.gestures.ScrollableState
import com.tencent.kuikly.compose.foundation.lazy.LazyListState
import com.tencent.kuikly.compose.gestures.KuiklyScrollInfo
import com.tencent.kuikly.core.log.KLog

internal const val SLOCK_KUIKLY_SCROLL_DIAGNOSTICS_TAG = "SlockKuiklyScroll"

internal fun KuiklyScrollInfo.logScrollDiagnostic(
    event: String,
    details: String = ""
) {
    val maxOffset = (currentContentSize - viewportSize).coerceAtLeast(0)
    val suffix = details.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
    KLog.i(
        SLOCK_KUIKLY_SCROLL_DIAGNOSTICS_TAG,
        "event=$event owner=${hashCode()} view=${scrollView?.hashCode()} pagerId=${scrollView?.pagerId.orEmpty()} " +
            "nativeOffset=$contentOffset composeOffset=$composeOffset maxOffset=$maxOffset " +
            "contentSize=$currentContentSize viewport=$viewportSize nativeDragging=${scrollView?.isDragging} " +
            "offsetDirty=$offsetDirty$suffix"
    )
}

internal fun ScrollableState.logScrollDiagnostic(
    event: String,
    details: String = ""
) {
    val lazyDetails =
        if (this is LazyListState) {
            val visible =
                layoutInfo.visibleItemsInfo
                    .take(4)
                    .joinToString(separator = ",") { item ->
                        "${item.index}:${item.key}:${item.offset}:${item.size}"
                    }
            "composeScrolling=$isScrollInProgress first=$firstVisibleItemIndex:$firstVisibleItemScrollOffset " +
                "total=${layoutInfo.totalItemsCount} visible=[$visible]"
        } else {
            "composeScrolling=$isScrollInProgress"
        }
    kuiklyInfo.logScrollDiagnostic(event, "$lazyDetails $details".trim())
}
