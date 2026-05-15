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
import android.net.Uri
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
import java.io.FilterInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Created by kam on 2022/8/15.
 */
class KRImageAdapter(val context: Context) : IKRImageAdapter {

    override fun fetchDrawable(
        imageLoadOption: HRImageLoadOption,
        callback: (drawable: Drawable?) -> Unit,
    ) {
        if (imageLoadOption.isBase64()) {
            if (imageLoadOption.isSvg()) {
                loadSvg(imageLoadOption, callback)
            } else {
                loadFromBase64(imageLoadOption, callback)
            }
        } else if (imageLoadOption.isSvg()) {
            loadSvg(imageLoadOption, callback)
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
                    val resolvedWidth = resolveSvgWidth(svg, imageLoadOption)
                    val resolvedHeight = resolveSvgHeight(svg, imageLoadOption, resolvedWidth)
                    val (bitmapWidth, bitmapHeight) = limitSvgSize(resolvedWidth, resolvedHeight)
                    svg.setDocumentWidth(bitmapWidth.toFloat())
                    svg.setDocumentHeight(bitmapHeight.toFloat())
                    val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                    svg.renderToCanvas(Canvas(bitmap))
                    callback.invoke(BitmapDrawable(context.resources, bitmap))
                }
            } catch (e: Exception) {
                Log.d(TAG, "loadSvg: $e")
                callback.invoke(null)
            } catch (e: OutOfMemoryError) {
                Log.d(TAG, "OutOfMemoryError occurred during SVG loading: $e")
                callback.invoke(null)
            }
        }
    }

    private fun openSvgInputStream(imageLoadOption: HRImageLoadOption) = when {
        imageLoadOption.isBase64() -> {
            ByteArrayInputStream(decodeBase64ImageSource(imageLoadOption.src))
        }
        imageLoadOption.isAssets() -> {
            val assetPath = imageLoadOption.src.substring(HRImageLoadOption.SCHEME_ASSETS.length)
            context.assets.open(assetPath)
        }
        imageLoadOption.isWebUrl() || imageLoadOption.isFile() -> openUrlInputStream(imageLoadOption.src)
        else -> throw IllegalArgumentException("Unsupported SVG image source")
    }

    private fun openUrlInputStream(src: String) = URL(src).run {
        require(protocol == "http" || protocol == "https" || protocol == "file") {
            "Unsupported SVG URL protocol"
        }
        require(protocol == "file" || !host.isNullOrBlank()) {
            "SVG network URL must include a host"
        }
        openConnection().run {
            connectTimeout = URL_CONNECTION_TIMEOUT_MS
            readTimeout = URL_CONNECTION_TIMEOUT_MS
            if (contentLengthLong > MAX_SVG_SOURCE_BYTES) {
                throw IllegalArgumentException("SVG source is too large")
            }
            LimitedInputStream(this, getInputStream(), MAX_SVG_SOURCE_BYTES)
        }
    }

    private fun resolveSvgWidth(svg: SVG, imageLoadOption: HRImageLoadOption): Int {
        if (imageLoadOption.needResize && imageLoadOption.requestWidth > 0) {
            return imageLoadOption.requestWidth
        }
        return svg.documentWidth.toPositiveInt()
            ?: (svg.documentViewBox?.width).toPositiveInt()
            ?: imageLoadOption.requestWidth.takeIf { it > 0 }
            ?: DEFAULT_SVG_SIZE
    }

    private fun resolveSvgHeight(svg: SVG, imageLoadOption: HRImageLoadOption, width: Int): Int {
        if (imageLoadOption.needResize && imageLoadOption.requestHeight > 0) {
            return imageLoadOption.requestHeight
        }
        val height = svg.documentHeight.toPositiveInt()
            ?: (svg.documentViewBox?.height).toPositiveInt()
            ?: imageLoadOption.requestHeight.takeIf { it > 0 }
        if (height != null) {
            return height
        }
        val documentWidth = svg.documentWidth.takeIf { it > 0f }
            ?: svg.documentViewBox?.width
        val documentHeight = svg.documentHeight.takeIf { it > 0f }
            ?: svg.documentViewBox?.height
        return if (documentWidth != null && documentHeight != null) {
            (width * documentHeight / documentWidth).roundToInt().coerceAtLeast(1)
        } else {
            DEFAULT_SVG_SIZE
        }
    }

    private fun Float?.toPositiveInt(): Int? {
        return this?.roundToInt()?.takeIf { it > 0 }
    }

    private fun limitSvgSize(width: Int, height: Int): Pair<Int, Int> {
        val maxDimension = maxOf(width, height)
        if (maxDimension <= MAX_SVG_BITMAP_SIZE) {
            return width to height
        }
        val scale = MAX_SVG_BITMAP_SIZE.toFloat() / maxDimension.toFloat()
        return (width * scale).roundToInt().coerceAtLeast(1) to
            (height * scale).roundToInt().coerceAtLeast(1)
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
            val bytes = decodeBase64ImageSource(imageLoadOption.src)
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
                    Log.d(TAG, "loadFromBase64: $e")
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                callback.invoke(BitmapDrawable(Resources.getSystem(), bitmap))
            } catch (e: OutOfMemoryError) {
                Log.d(TAG, "OutOfMemoryError occurred during Base64 image loading: $e")
            }
        }
    }

    private fun decodeBase64ImageSource(src: String): ByteArray {
        require(src.contains(",")) {
            "Base64 image source must contain a comma separator between metadata and data"
        }
        val data = src.substringAfter(",")
        return if (src.substringBefore(",").contains(";base64")) {
            Base64.decode(data, Base64.DEFAULT)
        } else {
            // Supports data:image/svg+xml,<svg...> URI-encoded SVG sources.
            Uri.decode(data).toByteArray(Charsets.UTF_8)
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
        return if (src.startsWith(SCHEME_BASE64)) {
            src.substringBefore(",").contains("image/svg+xml")
        } else {
            val path = src.substringBefore("?").substringBefore("#")
            path.endsWith(".svg", ignoreCase = true)
        }
    }

    companion object {
        private const val TAG = "KRImageAdapter"
        // Pixels used when an SVG does not declare width, height, or viewBox.
        private const val DEFAULT_SVG_SIZE = 100
        // Keep generated bitmaps within a safe size for typical Android devices.
        private const val MAX_SVG_BITMAP_SIZE = 4096
        private const val MAX_SVG_SOURCE_BYTES = 10 * 1024 * 1024L
        private const val URL_CONNECTION_TIMEOUT_MS = 15000
    }

    private class LimitedInputStream(
        private val connection: java.net.URLConnection,
        inputStream: java.io.InputStream,
        private val maxBytes: Long,
    ) : FilterInputStream(inputStream) {

        private var bytesRead = 0L

        override fun read(): Int {
            val value = super.read()
            if (value != -1) {
                increaseBytesRead(1)
            }
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) {
                increaseBytesRead(read)
            }
            return read
        }

        override fun close() {
            try {
                super.close()
            } finally {
                (connection as? HttpURLConnection)?.disconnect()
            }
        }

        private fun increaseBytesRead(read: Int) {
            bytesRead += read.toLong()
            if (bytesRead > maxBytes) {
                throw IllegalArgumentException("SVG source is too large")
            }
        }
    }

}
