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
        if (raw.isEmpty()) return true
        val path = pathOf(raw) ?: return true
        return Files.isRegularFile(path)
    }

    /** Detects external deletion/replacement without changing durable statistics. */
    fun fingerprint(tracks: Iterable<TrackMeta>): Long {
        var result = 1L
        tracks.asSequence()
            .map { it.path.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .forEach { raw ->
                val visible = pathOf(raw)?.let(Files::isRegularFile) ?: true
                result = result * 31 + raw.hashCode()
                result = result * 31 + if (visible) 1 else 0
            }
        return result
    }
}
