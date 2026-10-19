package com.jethuit.spw.listenstats.data

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.jethuit.spw.listenstats.PluginLog
import com.xuncorp.spw.workshop.api.WorkshopApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

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
    // Three lanes made the 25-row page wait up to ~390 ms while the API/decode
    // themselves were mostly under 50 ms. Four is a measured, bounded increase.
    private const val MAX_PARALLEL_DECODES = 4

    private val failedAt = ConcurrentHashMap<String, Long>()
    private val inflight = ConcurrentHashMap<String, CompletableFuture<ImageBitmap?>>()
    private val decodeSemaphore = Semaphore(MAX_PARALLEL_DECODES)

    suspend fun load(trackId: String, targetPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val size = targetPx.coerceIn(16, 256)
        val key = "$trackId@$size"
        cached(key)?.let {
            CoverDiagnostics.log("cover cache=hit track=$trackId px=$size")
            return@withContext it
        }
        val now = System.currentTimeMillis()
        val failed = failedAt[trackId]
        if (failed != null && now - failed < FAILURE_COOLDOWN_MS) {
            CoverDiagnostics.log("cover cache=cooldown track=$trackId px=$size")
            return@withContext null
        }

        val promise = CompletableFuture<ImageBitmap?>()
        val existing = inflight.putIfAbsent(key, promise)
        if (existing != null) {
            CoverDiagnostics.log("cover cache=joined track=$trackId px=$size")
            return@withContext await(existing)
        }

        var completed = false
        try {
            val requestStart = System.nanoTime()
            val result = fetchAndDecode(trackId, size, requestStart)
            if (result != null) {
                cachePut(key, result)
                failedAt.remove(trackId)
            } else {
                failedAt[trackId] = now
            }
            completed = true
            promise.complete(result)
            result
        } catch (failure: Throwable) {
            failedAt[trackId] = now
            promise.complete(null)
            null
        } finally {
            if (!completed) promise.complete(null)
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

    private suspend fun fetchAndDecode(trackId: String, size: Int, requestStart: Long): ImageBitmap? {
        val waitStart = System.nanoTime()
        var waitMs = 0L
        var apiMs = 0L
        var decodeMs = 0L
        var bytes: ByteArray? = null
        var result: ImageBitmap? = null
        var acquired = false
        try {
            decodeSemaphore.acquire()
            acquired = true
            coroutineContext.ensureActive()
            waitMs = (System.nanoTime() - waitStart) / 1_000_000
            val apiStart = System.nanoTime()
            bytes = runInterruptible {
                WorkshopApi.library.getCoverById(trackId)
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS)
            }
            apiMs = (System.nanoTime() - apiStart) / 1_000_000
            val coverBytes = bytes
            if (coverBytes != null && coverBytes.isNotEmpty()) {
                coroutineContext.ensureActive()
                val decodeStart = System.nanoTime()
                result = decodeThumbnail(coverBytes, size)
                decodeMs = (System.nanoTime() - decodeStart) / 1_000_000
            }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
        } finally {
            if (acquired) decodeSemaphore.release()
            val totalMs = (System.nanoTime() - requestStart) / 1_000_000
            CoverDiagnostics.log(
                "cover cache=miss track=$trackId px=$size wait_ms=$waitMs api_ms=$apiMs decode_ms=$decodeMs total_ms=$totalMs bytes=${bytes?.size ?: 0}"
            )
        }
        return result
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

    private suspend fun await(future: CompletableFuture<ImageBitmap?>): ImageBitmap? {
        return try {
            runInterruptible { future.get(8, TimeUnit.SECONDS) }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            null
        }
    }
}

object CoverDiagnostics {
    val enabled: Boolean = System.getProperty("spw.listenstats.coverDiagnostics") == "true"

    fun log(message: String) {
        if (enabled) PluginLog.info(message)
    }

    fun display(trackId: String, targetPx: Int, elapsedMs: Long, shown: Boolean) {
        log("cover cache=ui track=$trackId px=$targetPx ui_ms=$elapsedMs shown=$shown")
    }
}
