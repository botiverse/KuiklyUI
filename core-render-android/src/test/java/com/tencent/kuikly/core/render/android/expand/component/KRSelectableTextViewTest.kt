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

package com.tencent.kuikly.core.render.android.expand.component

import android.graphics.Color
import android.view.Gravity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Behavior contract of the system-selectable plain text surface:
 * always selectable (system ActionMode source), never editable, and props
 * arrive over the shared wire keys.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class KRSelectableTextViewTest {

    private fun createView() = KRSelectableTextView(RuntimeEnvironment.getApplication())

    @Test
    fun selectableAndReadOnlyByConstruction() {
        val view = createView()
        assertTrue(view.isTextSelectable)
        // TextView (not EditText): no input connection, no IME surface.
        assertFalse(view.onCheckIsTextEditor())
        // Selection must not leak across cell reuse.
        assertFalse(view.reusable)
    }

    @Test
    fun textPropRendersAndStaysSelectable() {
        val view = createView()
        assertTrue(view.setProp(KRSelectableTextView.PROP_TEXT, "hello selectable"))
        assertEquals("hello selectable", view.text.toString())
        assertTrue(view.isTextSelectable)
    }

    @Test
    fun colorPropParsesKuiklyColorString() {
        val view = createView()
        // 4278255360 == 0xFF00FF00 (opaque green) in the Kuikly wire format.
        assertTrue(view.setProp(KRSelectableTextView.PROP_COLOR, "4278255360"))
        assertEquals(Color.GREEN, view.currentTextColor)
    }

    @Test
    fun textAlignPropUpdatesHorizontalGravityOnly() {
        val view = createView()
        assertTrue(view.setProp(KRSelectableTextView.PROP_TEXT_ALIGN, "center"))
        assertEquals(
            Gravity.CENTER_HORIZONTAL,
            view.gravity and Gravity.HORIZONTAL_GRAVITY_MASK
        )
        assertEquals(Gravity.TOP, view.gravity and Gravity.VERTICAL_GRAVITY_MASK)

        assertTrue(view.setProp(KRSelectableTextView.PROP_TEXT_ALIGN, "right"))
        assertEquals(Gravity.RIGHT, view.gravity and Gravity.HORIZONTAL_GRAVITY_MASK)

        assertTrue(view.setProp(KRSelectableTextView.PROP_TEXT_ALIGN, "left"))
        assertEquals(Gravity.LEFT, view.gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
    }

    @Test
    fun handledPropsPinTheSharedWireContract() {
        assertEquals(
            setOf(
                "text",
                "fontSize",
                "fontWeight",
                "color",
                "lineHeight",
                "textAlign",
                "useDpFontSizeDim"
            ),
            KRSelectableTextView.HANDLED_PROPS
        )
    }
}
