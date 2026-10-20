package com.jethuit.spw.listenstats.data

import java.time.LocalDate
import java.time.ZoneId

/** Wall-clock intervals actually observed while playing; pauses never fill the gaps. */
data class ListeningSpan(val startMs: Long, val durationMs: Long)

data class ListeningEvent(
    val id: String,
    val track: TrackMeta,
    val spans: List<ListeningSpan>,
    val aliases: Set<String> = emptySet(),
    val approximate: Boolean = false,
    val libraryId: String = DataFiles.sourceId()
) {
    val durationMs: Long get() = spans.sumOf { it.durationMs }
}

object ListeningEvents {
    /** A larger listening snapshot must not erase already known track metadata. */
    fun mergeTrack(preferred: TrackMeta, fallback: TrackMeta): TrackMeta {
        require(preferred.id == fallback.id)
        return preferred.copy(
            title = preferred.title.takeIf { it.isNotBlank() && it != "未知歌曲" }
                ?: fallback.title.takeIf { it.isNotBlank() } ?: preferred.title,
            artist = preferred.artist.ifBlank { fallback.artist },
            album = preferred.album.ifBlank { fallback.album },
            albumArtist = preferred.albumArtist.ifBlank { fallback.albumArtist },
            durationMs = preferred.durationMs.takeIf { it > 0 } ?: fallback.durationMs
        )
    }

    /** Repeated snapshots share an identity; only the most complete snapshot contributes. */
    fun union(events: List<ListeningEvent>): List<ListeningEvent> {
        val groups = LinkedHashMap<String, ListeningEvent>()
        val owners = HashMap<String, String>()
        val localLibrary = DataFiles.sourceId()
        for (event in events) {
            val keys = event.aliases + event.id
            val matches = keys.mapNotNull { owners[it] }.distinct()
            val candidates = matches.mapNotNull { groups.remove(it) } + event
            val chosen = candidates.maxWithOrNull(
                compareBy<ListeningEvent> { it.durationMs }.thenBy { !it.approximate }
            )!!
            val allKeys = candidates.flatMap { it.aliases + it.id }.toSet()
            // A verified local host alias can reconnect a backup made before identity repair.
            val identity = candidates.firstOrNull {
                it.libraryId == localLibrary && (it.id.startsWith("host:$localLibrary:") ||
                    it.aliases.any { alias -> alias.startsWith("host:$localLibrary:") })
            } ?: chosen
            val metadata = candidates.filter { it.track.id == identity.track.id }
                .fold(identity.track) { known, candidate -> mergeTrack(known, candidate.track) }
            val merged = chosen.copy(track = metadata, libraryId = identity.libraryId, aliases = allKeys - chosen.id)
            groups[merged.id] = merged
            allKeys.forEach { owners[it] = merged.id }
        }
        return groups.values.toList()
    }

    fun aggregate(base: StatsData, input: List<ListeningEvent>, zone: ZoneId = ZoneId.systemDefault()): StatsData {
        val snapshots = union(input)
        val daily = linkedMapOf<LocalDate, Long>()
        val hourly = linkedMapOf<LocalDate, LongArray>()
        val tracks = linkedMapOf<String, TrackMeta>()
        val counts = linkedMapOf<String, Int>()
        val localLibrary = DataFiles.sourceId()
        fun localTrackId(event: ListeningEvent) =
            if (event.libraryId == localLibrary) event.track.id else "${event.libraryId}:${event.track.id}"
        for (event in snapshots) {
            val trackId = localTrackId(event)
            val meta = event.track.copy(id = trackId)
            tracks[trackId] = tracks[trackId]?.let { mergeTrack(meta, it) } ?: meta
        }
        val events = snapshots.map { event ->
            event.copy(track = tracks.getValue(localTrackId(event)).copy(id = event.track.id))
        }
        for (event in events) {
            val trackId = if (event.libraryId == localLibrary) event.track.id else "${event.libraryId}:${event.track.id}"
            event.spans.forEach { TimeSplitter.split(it.startMs, it.durationMs, zone, daily, hourly) }
            if (event.track.durationMs > 0 && event.durationMs >= event.track.durationMs / 2.0) {
                counts[trackId] = (counts[trackId] ?: 0) + 1
            }
        }
        return base.copy(events = events, dailyMillis = daily, hourlyMillis = hourly,
            tracks = tracks, playCounts = counts, loadedAt = System.currentTimeMillis())
    }
}
