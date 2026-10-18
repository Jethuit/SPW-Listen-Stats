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
    /** Repeated snapshots share an identity; only the most complete snapshot contributes. */
    fun union(events: List<ListeningEvent>): List<ListeningEvent> {
        val groups = LinkedHashMap<String, ListeningEvent>()
        val owners = HashMap<String, String>()
        for (event in events) {
            val keys = event.aliases + event.id
            val matches = keys.mapNotNull { owners[it] }.distinct()
            val candidates = matches.mapNotNull { groups.remove(it) } + event
            val chosen = candidates.maxWithOrNull(
                compareBy<ListeningEvent> { it.durationMs }.thenBy { !it.approximate }
            )!!
            val allKeys = candidates.flatMap { it.aliases + it.id }.toSet()
            val merged = chosen.copy(aliases = allKeys - chosen.id)
            groups[merged.id] = merged
            allKeys.forEach { owners[it] = merged.id }
        }
        return groups.values.toList()
    }

    fun aggregate(base: StatsData, input: List<ListeningEvent>, zone: ZoneId = ZoneId.systemDefault()): StatsData {
        val events = union(input)
        val daily = linkedMapOf<LocalDate, Long>()
        val hourly = linkedMapOf<LocalDate, LongArray>()
        val tracks = linkedMapOf<String, TrackMeta>()
        val counts = linkedMapOf<String, Int>()
        val localLibrary = DataFiles.sourceId()
        for (event in events) {
            val trackId = if (event.libraryId == localLibrary) event.track.id else "${event.libraryId}:${event.track.id}"
            tracks[trackId] = event.track.copy(id = trackId)
            event.spans.forEach { TimeSplitter.split(it.startMs, it.durationMs, zone, daily, hourly) }
            if (event.track.durationMs > 0 && event.durationMs >= event.track.durationMs / 2.0) {
                counts[trackId] = (counts[trackId] ?: 0) + 1
            }
        }
        return base.copy(events = events, dailyMillis = daily, hourlyMillis = hourly,
            tracks = tracks, playCounts = counts, loadedAt = System.currentTimeMillis())
    }
}
