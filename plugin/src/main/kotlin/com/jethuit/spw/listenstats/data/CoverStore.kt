package com.jethuit.spw.listenstats.data

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.jethuit.spw.listenstats.PluginLog
import com.xuncorp.spw.workshop.api.WorkshopApi
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Decode covers on the IO side into the exact square thumbnail the row draws.
 * The Skia image returned by [Image.makeFromEncoded] is lazy, so drawing into a
 * raster surface is what forces the decode off the UI thread.
 */
object CoverStore {
    private data class Thumb(val bitmap: ImageBitmap, val bytes: Long)

    private val cache = object : LinkedHashMap<String, Thumb>(16, 0.75f, true) {}
    private var cacheBytes = 0L
    private const val MAX_CACHE_BYTES = 12L * 1024L * 1024L
    private const val FAILURE_COOLDOWN_MS = 30_000L
    private const val MAX_PARALLEL_DECODES = 3

    private val failedAt = ConcurrentHashMap<String, Long>()
    private val inflight = ConcurrentHashMap<String, CompletableFuture<ImageBitmap?>>()
    private val decodePermits = Semaphore(MAX_PARALLEL_DECODES, true)

    fun load(trackId: String, targetPx: Int): ImageBitmap? {
        val size = targetPx.coerceIn(16, 256)
        val key = "$trackId@$size"
        cached(key)?.let { return it }
        val now = System.currentTimeMillis()
        val failed = failedAt[trackId]
        if (failed != null && now - failed < FAILURE_COOLDOWN_MS) return null

        val promise = CompletableFuture<ImageBitmap?>()
        val existing = inflight.putIfAbsent(key, promise)
        if (existing != null) return await(existing)

        return try {
            val result = fetchAndDecode(trackId, size)
            if (result != null) {
                cachePut(key, result)
                failedAt.remove(trackId)
                promise.complete(result)
            } else {
                failedAt[trackId] = now
                promise.complete(null)
            }
            result
        } catch (failure: Throwable) {
            failedAt[trackId] = now
            promise.complete(null)
            null
        } finally {
            inflight.remove(key, promise)
        }
    }

    fun clear() {
        synchronized(cache) {
            cache.clear()
            cacheBytes = 0L
        }
        failedAt.clear()
    }

    private fun fetchAndDecode(trackId: String, size: Int): ImageBitmap? {
        var acquired = false
        try {
            decodePermits.acquire()
            acquired = true
            val bytes = WorkshopApi.library.getCoverById(trackId)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS)
            if (bytes == null || bytes.isEmpty()) return null
            return decodeThumbnail(bytes, size)
        } catch (failure: Throwable) {
            return null
        } finally {
            if (acquired) decodePermits.release()
        }
    }

    private fun decodeThumbnail(bytes: ByteArray, size: Int): ImageBitmap? {
        var source: Image? = null
        var surface: Surface? = null
        return try {
            val decoded = Image.makeFromEncoded(bytes)
            source = decoded
            if (decoded.width <= 0 || decoded.height <= 0) return null
            val raster = Surface.makeRasterN32Premul(size, size)
            surface = raster
            val scale = maxOf(
                size.toFloat() / decoded.width.toFloat(),
                size.toFloat() / decoded.height.toFloat()
            )
            val drawWidth = decoded.width * scale
            val drawHeight = decoded.height * scale
            raster.canvas.clear(0)
            raster.canvas.drawImageRect(
                decoded,
                Rect.makeWH(decoded.width.toFloat(), decoded.height.toFloat()),
                Rect.makeXYWH((size - drawWidth) / 2f, (size - drawHeight) / 2f, drawWidth, drawHeight),
                SamplingMode.LINEAR,
                null,
                true
            )
            raster.makeImageSnapshot().toComposeImageBitmap()
        } catch (failure: Throwable) {
            PluginLog.error("decoding cover thumbnail failed", failure)
            null
        } finally {
            surface?.close()
            source?.close()
        }
    }

    private fun cached(key: String): ImageBitmap? = synchronized(cache) {
        cache[key]?.bitmap
    }

    private fun cachePut(key: String, bitmap: ImageBitmap) {
        val bytes = bitmap.width.toLong() * bitmap.height.toLong() * 4L
        synchronized(cache) {
            cache.remove(key)?.let { cacheBytes -= it.bytes }
            cache[key] = Thumb(bitmap, bytes)
            cacheBytes += bytes
            val iterator = cache.entries.iterator()
            while (cacheBytes > MAX_CACHE_BYTES && iterator.hasNext()) {
                val entry = iterator.next()
                cacheBytes -= entry.value.bytes
                iterator.remove()
            }
        }
    }

    private fun await(future: CompletableFuture<ImageBitmap?>): ImageBitmap? {
        return try {
            future.get(8, TimeUnit.SECONDS)
        } catch (failure: Throwable) {
            null
        }
    }
}
