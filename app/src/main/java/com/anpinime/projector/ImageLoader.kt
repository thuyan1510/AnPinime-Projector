package com.anpinime.projector

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.WeakHashMap

/**
 * Ultra-lightweight image loader specifically tuned for 1GB-2GB RAM projectors.
 * Uses RGB_565 (50% less RAM than ARGB_8888) and aggressive downsampling.
 */
object ImageLoader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = WeakHashMap<ImageView, Job>()

    // Cap cache strictly at 10MB so video playback always has plenty of free RAM
    private val maxCacheBytes = minOf(
        (Runtime.getRuntime().maxMemory() / 10).toInt(),
        10 * 1024 * 1024
    ).coerceAtLeast(4 * 1024 * 1024)

    private val cache = object : LruCache<String, Bitmap>(maxCacheBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(
        imageView: ImageView,
        url: String?,
        referer: String? = null,
        maxWidth: Int = 220,
        maxHeight: Int = 320
    ) {
        jobs.remove(imageView)?.cancel()
        if (url.isNullOrBlank()) {
            imageView.setImageBitmap(null)
            return
        }
        val cacheKey = "$url|${maxWidth}x$maxHeight"
        synchronized(cache) {
            cache.get(cacheKey)?.let { cached ->
                if (!cached.isRecycled) {
                    imageView.setImageBitmap(cached)
                    return
                }
            }
        }
        imageView.setImageBitmap(null)
        imageView.tag = cacheKey
        val job = scope.launch {
            val bmp = fetchBitmap(url, referer, maxWidth, maxHeight) ?: return@launch
            synchronized(cache) {
                cache.put(cacheKey, bmp)
            }
            withContext(Dispatchers.Main) {
                if (imageView.tag == cacheKey) {
                    imageView.setImageBitmap(bmp)
                }
            }
        }
        jobs[imageView] = job
    }

    fun trimMemory() {
        synchronized(cache) {
            cache.evictAll()
        }
    }

    private fun fetchBitmap(urlString: String, referer: String?, reqWidth: Int, reqHeight: Int): Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            val u = URL(urlString)
            conn = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 9000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", CatalogService.USER_AGENT)
                val defaultRef = if (urlString.contains("animevietsub", ignoreCase = true)) {
                    "${CatalogService.animeBaseUrl}/"
                } else {
                    "${u.protocol}://${u.host}/"
                }
                setRequestProperty("Referer", if (!referer.isNullOrBlank()) referer else defaultRef)
                WebViewHelper.getCookieHeader(urlString)?.let { setRequestProperty("Cookie", it) }
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) return null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sampleSize = 1
            if (bounds.outHeight > reqHeight || bounds.outWidth > reqWidth) {
                val halfH = bounds.outHeight / 2
                val halfW = bounds.outWidth / 2
                while ((halfH / sampleSize) >= reqHeight && (halfW / sampleSize) >= reqWidth) {
                    sampleSize *= 2
                }
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
        } catch (_: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
