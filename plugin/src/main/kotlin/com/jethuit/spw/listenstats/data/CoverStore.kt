package com.jethuit.spw.listenstats.data

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.jethuit.spw.listenstats.PluginLog
import com.xuncorp.spw.workshop.api.WorkshopApi
import org.jetbrains.skia.Image
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object CoverStore {
    private val cache = ConcurrentHashMap<String, ImageBitmap>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private const val FAILURE_COOLDOWN_MS = 30_000L

    fun load(trackId: String): ImageBitmap? {
        cache[trackId]?.let { return it }
        val now = System.currentTimeMillis()
        val failed = failedAt[trackId]
        if (failed != null && now - failed < FAILURE_COOLDOWN_MS) return null
        val result = try {
            val bytes = WorkshopApi.library.getCoverById(trackId)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS)
            if (bytes == null || bytes.isEmpty()) {
                null
            } else {
                try {
                    Image.makeFromEncoded(bytes).toComposeImageBitmap()
                } catch (failure: Throwable) {
                    PluginLog.error("decoding cover failed for $trackId", failure)
                    null
                }
            }
        } catch (failure: Throwable) {
            null
        }
        if (result != null) {
            cache[trackId] = result
            failedAt.remove(trackId)
        } else {
            failedAt[trackId] = now
        }
        return result
    }

    fun clear() {
        cache.clear()
        failedAt.clear()
    }
}
