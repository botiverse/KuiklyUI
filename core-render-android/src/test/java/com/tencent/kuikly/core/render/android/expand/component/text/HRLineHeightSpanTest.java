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

package com.tencent.kuikly.core.render.android.expand.component.text;

import static org.junit.Assert.assertEquals;

import android.graphics.Paint;
import org.junit.Test;

public class HRLineHeightSpanTest {

    @Test
    public void oddLineHeightLeadingDoesNotPushBaselineDown() {
        Paint.FontMetricsInt metrics = new Paint.FontMetricsInt();
        metrics.top = -13;
        metrics.ascent = -13;
        metrics.descent = 4;
        metrics.bottom = 4;

        new HRLineHeightSpan(22).chooseHeight("", 0, 0, 0, 0, metrics);

        assertEquals(-15, metrics.top);
        assertEquals(-15, metrics.ascent);
        assertEquals(7, metrics.bottom);
        assertEquals(7, metrics.descent);
        assertEquals(22, metrics.bottom - metrics.top);
    }
}
