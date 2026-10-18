package com.jethuit.spw.listenstats.data

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.jethuit.spw.listenstats.PluginLog
import com.xuncorp.spw.workshop.api.WorkshopApi
import org.jetbrains.skia.Image
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object CoverStore {
    private val cache = ConcurrentHashMap<String, Optional<ImageBitmap>>()

    fun load(trackId: String): ImageBitmap? {
        val cached = cache[trackId]
        if (cached != null) return cached.orElse(null)
        val result = try {
            val bytes = WorkshopApi.library.getCoverById(trackId)
                .toCompletableFuture()
                .get(3, TimeUnit.SECONDS)
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
        cache[trackId] = Optional.ofNullable(result)
        return result
    }

    fun clear() {
        cache.clear()
    }
}
