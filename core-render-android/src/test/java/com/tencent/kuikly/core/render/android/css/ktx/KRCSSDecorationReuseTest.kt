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

package com.tencent.kuikly.core.render.android.css.ktx

import android.view.View
import com.tencent.kuikly.core.render.android.const.KRCssConst
import com.tencent.kuikly.core.render.android.css.drawable.KRCSSBackgroundDrawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class KRCSSDecorationReuseTest {

    @Test
    fun radiusResetClearsForegroundClipAndAllowsSquareBorderOnSameViewRepeatedly() {
        val view = View(RuntimeEnvironment.getApplication())

        repeat(2) {
            applyRoundedBackground(view)
            val reusedIdentity = view

            assertNotNull(view.background)
            assertNotNull(view.foreground)
            assertNotNull(view.outlineProvider)

            assertEquals(true, view.resetCommonProp(KRCssConst.BORDER_RADIUS))

            assertSame(reusedIdentity, view)
            assertNull(view.background)
            assertNull(view.foreground)
            assertNull(view.optViewDecorator())

            assertEquals(true, view.setCommonProp(KRCssConst.BORDER, SQUARE_BORDER))
            val squareBorder = view.foreground as KRCSSBackgroundDrawable
            assertEquals(KRCssConst.EMPTY_STRING, squareBorder.borderRadius)
            assertEquals(SQUARE_BORDER, squareBorder.borderStyle)

            assertEquals(true, view.resetCommonProp(KRCssConst.BORDER))
            assertNull(view.foreground)
            assertNull(view.optViewDecorator())
        }
    }

    private fun applyRoundedBackground(view: View) {
        assertEquals(true, view.setCommonProp(KRCssConst.BACKGROUND_COLOR, YELLOW))
        assertEquals(true, view.setCommonProp(KRCssConst.BORDER_RADIUS, ROUNDED_RADIUS))
    }

    private companion object {
        const val YELLOW = "4294967040"
        const val ROUNDED_RADIUS = "10,10,10,10"
        const val SQUARE_BORDER = "2 solid 4294901760"
    }
}
