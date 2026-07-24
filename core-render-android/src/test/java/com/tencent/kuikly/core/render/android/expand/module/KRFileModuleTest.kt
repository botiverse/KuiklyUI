/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2026 Tencent. All rights reserved.
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

package com.tencent.kuikly.core.render.android.expand.module

import android.content.Context
import com.tencent.kuikly.core.render.android.IKuiklyRenderContext
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class KRFileModuleTest {

    @Test
    fun duplicateOperationIdAcrossPagerModulesAppendsOnlyOnce() {
        val context = RuntimeEnvironment.getApplication().baseContext
        val moduleA = fileModule(context)
        val moduleB = fileModule(context)
        val suffix = System.nanoTime().toString()
        val filename = "file-module-dedupe-$suffix.jsonl"
        val operationId = "android-file-operation-$suffix"
        val params =
            "{\"filename\":\"$filename\",\"content\":\"frame\"," +
                "\"operationId\":\"$operationId\"}"
        val callbacks = CopyOnWriteArrayList<Map<*, *>>()
        val callbackLatch = CountDownLatch(2)

        val callback: (Any?) -> Unit = { result ->
            callbacks.add(result as Map<*, *>)
            callbackLatch.countDown()
        }

        moduleA.call("appendFile", params, callback)
        moduleB.call("appendFile", params, callback)

        assertTrue("native file callbacks timed out", callbackLatch.await(5, TimeUnit.SECONDS))
        assertEquals(2, callbacks.size)
        assertTrue(callbacks.all { it["error"] == null })
        val paths = callbacks.map { it["path"] as String }.toSet()
        assertEquals(1, paths.size)

        val file = File(paths.single())
        try {
            assertEquals("frame\n", file.readText(Charsets.UTF_8))
        } finally {
            file.delete()
        }
    }

    private fun fileModule(context: Context): KRFileModule =
        KRFileModule().also { module ->
            module.kuiklyRenderContext = Proxy.newProxyInstance(
                IKuiklyRenderContext::class.java.classLoader,
                arrayOf(IKuiklyRenderContext::class.java)
            ) { _, method, _ ->
                when (method.name) {
                    "getContext" -> context
                    "useHostDisplayMetrics" -> false
                    else -> null
                }
            } as IKuiklyRenderContext
        }
}
