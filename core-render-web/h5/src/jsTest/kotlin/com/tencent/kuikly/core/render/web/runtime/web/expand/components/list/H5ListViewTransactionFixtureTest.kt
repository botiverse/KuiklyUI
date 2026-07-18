package com.tencent.kuikly.core.render.web.runtime.web.expand.components.list

import com.tencent.kuikly.core.render.web.ktx.KuiklyRenderCallback
import com.tencent.kuikly.core.render.web.ktx.kuiklyDocument
import com.tencent.kuikly.core.render.web.ktx.kuiklyWindow
import org.w3c.dom.HTMLElement
import org.w3c.dom.MutationObserver
import org.w3c.dom.MutationObserverInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class H5ListViewTransactionFixtureTest {

    @Test
    fun insetReplacementCallbackInstallsNewestWriteWithoutStaleMutation(): Promise<Unit> =
        Promise { resolve, reject ->
            try {
                js("globalThis.kuiklyDocument = document; globalThis.kuiklyWindow = window;")
                val list = H5ListView()
                list.ele.style.width = "100px"
                list.ele.style.height = "100px"
                val content = kuiklyDocument.createElement("div").unsafeCast<HTMLElement>()
                content.style.width = "100px"
                content.style.height = "400px"
                list.ele.appendChild(content)
                kuiklyDocument.body?.appendChild(list.ele)
                val staleTransforms = mutableListOf<String>()
                val observer = MutationObserver { records, _ ->
                    records.forEach { record ->
                        record.oldValue?.takeIf { it.contains("transform") }?.let(staleTransforms::add)
                    }
                }
                observer.observe(
                    content,
                    MutationObserverInit(
                        attributes = true,
                        attributeOldValue = true,
                        attributeFilter = arrayOf("style"),
                    ),
                )

                var aCode = -1
                var bCode = -1
                var cCode = -1
                var aTerminals = 0
                list.setContentInset("20 0 0 0 1", KuiklyRenderCallback { result ->
                    aTerminals += 1
                    aCode = (result as Map<*, *>)["resultCode"] as Int
                    list.setContentInset("30 0 0 0 0", KuiklyRenderCallback { cResult ->
                        cCode = (cResult as Map<*, *>)["resultCode"] as Int
                    })
                })
                list.setContentInset("10 0 0 0 0", KuiklyRenderCallback { result ->
                    bCode = (result as Map<*, *>)["resultCode"] as Int
                })

                kuiklyWindow.setTimeout({
                    try {
                        assertEquals(6, aCode)
                        assertEquals(1, aTerminals)
                        assertEquals(6, bCode)
                        assertEquals(0, cCode)
                        assertTrue(content.style.transform.contains("30px"))
                        assertTrue(staleTransforms.none { it.contains("20px") || it.contains("10px") })
                        observer.disconnect()
                        list.destroy()
                        list.ele.remove()
                        resolve(Unit)
                    } catch (throwable: Throwable) {
                        reject(throwable)
                    }
                }, 50)
            } catch (throwable: Throwable) {
                reject(throwable)
            }
        }
}
