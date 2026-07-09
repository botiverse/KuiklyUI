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

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * task #476: values the bridge cannot represent must never vanish silently.
 * Pins the marshal contract of [toJSONObject]/[toJSONArray]:
 * already-JSON values pass through, null keeps the absent-key contract,
 * and unsupported types are dropped loudly (logged) without corrupting
 * the rest of the payload.
 */
class KuiklyRenderExtensionMarshalTest {

    @Test
    fun jsonObjectAndArrayValuesPassThrough() {
        val nestedArray = JSONArray().put("a").put(1)
        val nestedObject = JSONObject().put("k", "v")
        val result = mapOf<String, Any?>(
            "status" to "ok",
            "entries" to nestedArray,
            "meta" to nestedObject
        ).toJSONObject()

        assertEquals("ok", result.getString("status"))
        // The #484 bug shape: these two keys used to vanish.
        assertEquals(nestedArray.toString(), result.getJSONArray("entries").toString())
        assertEquals(nestedObject.toString(), result.getJSONObject("meta").toString())
    }

    @Test
    fun nullValuesKeepTheAbsentKeyContract() {
        val result = mapOf<String, Any?>(
            "present" to 1,
            "absent" to null
        ).toJSONObject()

        assertEquals(1, result.getInt("present"))
        assertFalse(result.has("absent"))
    }

    @Test
    fun unsupportedValueIsDroppedWithoutCorruptingSiblings() {
        val result = mapOf<String, Any?>(
            "good" to "value",
            "bad" to Any(),
            "alsoGood" to true
        ).toJSONObject()

        assertEquals("value", result.getString("good"))
        assertTrue(result.getBoolean("alsoGood"))
        assertFalse(result.has("bad"))
    }

    @Test
    fun nestedContainersStillRecurse() {
        val result = mapOf<String, Any?>(
            "map" to mapOf("inner" to 2),
            "list" to listOf("x", mapOf("y" to 3))
        ).toJSONObject()

        assertEquals(2, result.getJSONObject("map").getInt("inner"))
        val list = result.getJSONArray("list")
        assertEquals("x", list.getString(0))
        assertEquals(3, list.getJSONObject(1).getInt("y"))
    }

    @Test
    fun arrayMarshalPassesJsonThroughAndDropsUnsupportedLoudly() {
        val nested = JSONObject().put("id", 7)
        val result = listOf<Any>("s", 1, nested, Any(), JSONArray().put(false)).toJSONArray()

        // Unsupported Any() is dropped; everything else survives in order.
        assertEquals(4, result.length())
        assertEquals("s", result.getString(0))
        assertEquals(1, result.getInt(1))
        assertEquals(nested.toString(), result.getJSONObject(2).toString())
        assertEquals(false, result.getJSONArray(3).getBoolean(0))
    }
}
