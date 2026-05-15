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

package com.tencent.kuikly.android.demo.adapter

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import android.util.Log
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.caverock.androidsvg.SVG
import com.tencent.kuikly.android.demo.KRApplication
import com.tencent.kuikly.core.render.android.KuiklyRenderViewContext
import com.tencent.kuikly.core.render.android.adapter.HRImageLoadOption
import com.tencent.kuikly.core.render.android.adapter.IKRImageAdapter
import java.io.ByteArrayInputStream
import java.net.URL
import java.net.URLDecoder
import kotlin.math.roundToInt

/**
 * Created by kam on 2022/8/15.
 */
class KRImageAdapter(val context: Context) : IKRImageAdapter {

    override fun fetchDrawable(
        imageLoadOption: HRImageLoadOption,
        callback: (drawable: Drawable?) -> Unit,
    ) {
        if (imageLoadOption.isSvg()) {
            loadSvg(imageLoadOption, callback)
        } else if (imageLoadOption.isBase64()) {
            loadFromBase64(imageLoadOption, callback)
        } else if (imageLoadOption.isWebUrl() || imageLoadOption.isAssets() || imageLoadOption.isFile()) {
            // http/assets/file 图片使用 glide 加载
            requestImage(imageLoadOption, callback)
        }
    }

    override fun getDrawableWidth(
        kuiklyRenderViewContext: KuiklyRenderViewContext,
        drawable: Drawable
    ): Float {
        return drawable.intrinsicWidth.toFloat()
    }

    override fun getDrawableHeight(
        kuiklyRenderViewContext: KuiklyRenderViewContext,
        drawable: Drawable
    ): Float {
        return drawable.intrinsicHeight.toFloat()
    }


    private fun loadSvg(
        imageLoadOption: HRImageLoadOption,
        callback: (drawable: Drawable?) -> Unit,
    ) {
        execOnSubThread {
            try {
                openSvgInputStream(imageLoadOption).use { inputStream ->
                    val svg = SVG.getFromInputStream(inputStream)
                    val bitmapWidth = resolveSvgWidth(svg, imageLoadOption)
                    val bitmapHeight = resolveSvgHeight(svg, imageLoadOption, bitmapWidth)
                    svg.setDocumentWidth(bitmapWidth.toFloat())
                    svg.setDocumentHeight(bitmapHeight.toFloat())
                    val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                    svg.renderToCanvas(Canvas(bitmap))
                    callback.invoke(BitmapDrawable(context.resources, bitmap))
                }
            } catch (throwable: Throwable) {
                Log.d("ECHRImageAdapter", "loadSvg: $throwable")
                callback.invoke(null)
            }
        }
    }

    private fun openSvgInputStream(imageLoadOption: HRImageLoadOption) = when {
        imageLoadOption.isBase64() -> {
            val data = imageLoadOption.src.substringAfter(",")
            val bytes = if (imageLoadOption.src.substringBefore(",").contains(";base64")) {
                Base64.decode(data, Base64.DEFAULT)
            } else {
                URLDecoder.decode(data, "UTF-8").toByteArray(Charsets.UTF_8)
            }
            ByteArrayInputStream(bytes)
        }
        imageLoadOption.isAssets() -> {
            val assetPath = imageLoadOption.src.substring(HRImageLoadOption.SCHEME_ASSETS.length)
            context.assets.open(assetPath)
        }
        else -> URL(imageLoadOption.src).openStream()
    }

    private fun resolveSvgWidth(svg: SVG, imageLoadOption: HRImageLoadOption): Int {
        if (imageLoadOption.needResize && imageLoadOption.requestWidth > 0) {
            return imageLoadOption.requestWidth
        }
        return svg.documentWidth.takeIf { it > 0f }?.roundToInt()
            ?: svg.documentViewBox?.width?.roundToInt()
            ?: imageLoadOption.requestWidth.takeIf { it > 0 }
            ?: DEFAULT_SVG_SIZE
    }

    private fun resolveSvgHeight(svg: SVG, imageLoadOption: HRImageLoadOption, width: Int): Int {
        if (imageLoadOption.needResize && imageLoadOption.requestHeight > 0) {
            return imageLoadOption.requestHeight
        }
        val height = svg.documentHeight.takeIf { it > 0f }?.roundToInt()
            ?: svg.documentViewBox?.height?.roundToInt()
            ?: imageLoadOption.requestHeight.takeIf { it > 0 }
        if (height != null) {
            return height
        }
        val documentWidth = svg.documentWidth.takeIf { it > 0f }
            ?: svg.documentViewBox?.width
        val documentHeight = svg.documentHeight.takeIf { it > 0f }
            ?: svg.documentViewBox?.height
        return if (documentWidth != null && documentHeight != null && documentWidth > 0f) {
            (width * documentHeight / documentWidth).roundToInt().coerceAtLeast(1)
        } else {
            DEFAULT_SVG_SIZE
        }
    }

    private fun requestImage(
        imageLoadOption: HRImageLoadOption,
        callback: (drawable: Drawable?) -> Unit,
    ) {
        val src = if (imageLoadOption.isAssets()) {
            val assetPath = imageLoadOption.src.substring(HRImageLoadOption.SCHEME_ASSETS.length)
            "file:///android_asset/$assetPath"
        } else {
            imageLoadOption.src
        }
        val requestBuilder = if (src.endsWith(".gif")) {
            Glide.with(KRApplication.application)
                .asGif()
                .load(src) as RequestBuilder<Drawable>
        } else {
            Glide.with(KRApplication.application)
                .asDrawable()
                .load(src)
        }

        if (imageLoadOption.needResize) {
            requestBuilder.override(imageLoadOption.requestWidth, imageLoadOption.requestHeight)
            when (imageLoadOption.scaleType) {
                ImageView.ScaleType.CENTER_CROP -> requestBuilder.centerCrop()
                ImageView.ScaleType.FIT_CENTER -> requestBuilder.fitCenter()
                else -> {}
            }
        }
        requestBuilder
            .into(object : CustomTarget<Drawable>() {

                override fun onLoadCleared(placeholder: Drawable?) {
                    callback.invoke(null)
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    super.onLoadFailed(errorDrawable)
                    callback.invoke(null)
                }

                override fun onResourceReady(
                    resource: Drawable,
                    transition: Transition<in Drawable>?,
                ) {
                    callback.invoke(resource)
                }
            })
    }

    private fun loadFromBase64(
        imageLoadOption: HRImageLoadOption,
        callback: (drawable: Drawable?) -> Unit,
    ) {
        execOnSubThread {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            val bytes = Base64.decode(imageLoadOption.src.split(",")[1], Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            try {
                options.inPreferredConfig = Bitmap.Config.ARGB_8888
                options.inJustDecodeBounds = false
                try {
                    options.inSampleSize = calculateInSampleSize(
                        options,
                        imageLoadOption.requestWidth,
                        imageLoadOption.requestHeight
                    )
                } catch (e: ArithmeticException) { // 偶现报除以0，可能是inSampleSize超过int的范围溢出了。这里catch兜底使用原始inSampleSize
                    Log.d("ECHRImageAdapter", "loadFromBase64: $e")
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                callback.invoke(BitmapDrawable(Resources.getSystem(), bitmap))
            } catch (e: OutOfMemoryError) {
                Log.d("ECHRImageAdapter", "oom happen: $e")
            }
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int,
    ): Int {
        return if (reqWidth != 0 && reqHeight != 0 && reqWidth != -1 && reqHeight != -1) {
            var height = options.outHeight
            var width = options.outWidth
            var inSampleSize: Int
            inSampleSize = 1
            while (height > reqHeight && width > reqWidth) {
                val heightRatio = (height.toFloat() / reqHeight.toFloat()).roundToInt()
                val widthRatio = (width.toFloat() / reqWidth.toFloat()).roundToInt()
                val ratio = if (heightRatio > widthRatio) heightRatio else widthRatio
                if (ratio < 2) {
                    break
                }
                width = width shr 1
                height = height shr 1
                inSampleSize = inSampleSize shl 1
            }
            inSampleSize
        } else {
            1
        }
    }


    private fun HRImageLoadOption.isSvg(): Boolean {
        if (src.startsWith(SCHEME_BASE64) && src.substringBefore(",").contains("image/svg+xml")) {
            return true
        }
        val path = src.substringBefore("?").substringBefore("#")
        return path.endsWith(".svg", ignoreCase = true)
    }

    companion object {
        private const val DEFAULT_SVG_SIZE = 100
    }

}
