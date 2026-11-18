package com.jethuit.spw.listenstats.data

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/** Applies the selected deletion policy without destroying durable history. */
object TrackVisibility {
    private fun pathOf(raw: String): Path? = runCatching {
        if (raw.startsWith("file:", ignoreCase = true)) Path.of(URI(raw)) else Path.of(raw)
    }.getOrNull()

    /** An absent path is legacy/unknown metadata, so it remains visible. */
    fun isVisible(track: TrackMeta): Boolean {
        val raw = track.path.trim()
        return isVisiblePath(raw)
    }

    private fun isVisiblePath(raw: String): Boolean {
        if (raw.isEmpty()) return true
        val path = pathOf(raw) ?: return true
        PerformanceProbe.mark("TrackVisibility.fileCheck")
        return Files.isRegularFile(path)
    }

    /** One read observes each path once; the next read rechecks deletions and restorations. */
    class Snapshot {
        private val paths = HashMap<String, Boolean>()
        private fun visible(raw: String): Boolean = paths.getOrPut(raw) { isVisiblePath(raw) }
        fun isVisible(track: TrackMeta): Boolean = visible(track.path.trim())
        fun matches(previous: Snapshot): Boolean =
            previous.paths.all { (path, wasVisible) -> visible(path) == wasVisible }
    }
}
