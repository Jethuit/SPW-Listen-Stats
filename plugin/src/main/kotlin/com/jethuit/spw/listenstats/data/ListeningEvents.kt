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
    val libraryId: String = DataFiles.sourceId(),
    // Observed evidence is retained separately from the estimated remainder in spans.
    val preciseSpans: List<ListeningSpan>? = null
) {
    val durationMs: Long get() = spans.sumOf { it.durationMs }
    fun observedSpans(): List<ListeningSpan> = preciseSpans ?: if (approximate) emptyList() else spans
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

    fun unionSpans(input: List<ListeningSpan>): List<ListeningSpan> {
        val result = mutableListOf<ListeningSpan>()
        input.filter { it.durationMs > 0 }.sortedBy { it.startMs }.forEach { span ->
            val last = result.lastOrNull()
            if (last != null && span.startMs <= last.startMs + last.durationMs) {
                result[result.lastIndex] = last.copy(durationMs =
                    maxOf(last.startMs + last.durationMs, span.startMs + span.durationMs) - last.startMs)
            } else result.add(span)
        }
        return result
    }

    /** Keep observed locations; distribute only the missing total into uncovered host intervals. */
    private fun withApproximateRemainder(snapshot: ListeningEvent, precise: List<ListeningSpan>): List<ListeningSpan> {
        if (precise.isEmpty()) return snapshot.spans
        val gaps = mutableListOf<ListeningSpan>()
        var index = 0
        for (span in unionSpans(snapshot.spans)) {
            var start = span.startMs
            val end = start + span.durationMs
            while (index < precise.size && precise[index].startMs + precise[index].durationMs <= start) index++
            var cursor = index
            while (cursor < precise.size && precise[cursor].startMs < end) {
                val observed = precise[cursor++]
                if (observed.startMs > start) gaps += ListeningSpan(start, observed.startMs - start)
                start = maxOf(start, observed.startMs + observed.durationMs)
            }
            if (start < end) gaps += ListeningSpan(start, end - start)
        }
        var remaining = snapshot.durationMs - precise.sumOf { it.durationMs }
        var capacity = gaps.sumOf { it.durationMs }
        val estimates = gaps.mapNotNull { gap ->
            // Use a shrinking budget so rounding cannot change the total or exceed a gap.
            val duration = if (capacity == gap.durationMs) remaining else
                (remaining.toDouble() * (gap.durationMs.toDouble() / capacity)).toLong()
                    .coerceIn(0L, minOf(remaining, gap.durationMs))
            capacity -= gap.durationMs
            remaining -= duration
            gap.copy(durationMs = duration).takeIf { duration > 0 }
        }
        check(remaining == 0L) { "Approximate listening remainder exceeds its snapshot" }
        return unionSpans(precise + estimates)
    }

    /** Exact branches of one confirmed play form a union; approximate snapshots remain alternatives. */
    fun union(events: List<ListeningEvent>): List<ListeningEvent> {
        val groups = LinkedHashMap<String, ListeningEvent>()
        val owners = HashMap<String, String>()
        val localLibrary = DataFiles.sourceId()
        for (event in events) {
            val keys = event.aliases + event.id
            val matches = keys.mapNotNull { owners[it] }.distinct()
            val candidates = matches.mapNotNull { groups.remove(it) } + event
            val chosen = candidates.maxWithOrNull(
                compareBy<ListeningEvent> { it.durationMs }.thenBy { !it.approximate }.thenBy { it.id }
            )!!
            val allKeys = candidates.flatMap { it.aliases + it.id }.toSet()
            // A verified local host alias can reconnect a backup made before identity repair.
            val identity = candidates.firstOrNull {
                it.libraryId == localLibrary && (it.id.startsWith("host:$localLibrary:") ||
                    it.aliases.any { alias -> alias.startsWith("host:$localLibrary:") })
            } ?: chosen
            val metadata = candidates.filter { it.track.id == identity.track.id }.map { it.track }
                .reduce(::mergeTrack)
            val precise = unionSpans(candidates.flatMap { it.observedSpans() })
            val usePrecise = precise.sumOf { it.durationMs } >= chosen.durationMs
            val merged = chosen.copy(track = metadata, libraryId = identity.libraryId,
                aliases = allKeys - chosen.id,
                spans = if (usePrecise) precise else withApproximateRemainder(chosen, precise),
                approximate = if (usePrecise) false else chosen.approximate,
                preciseSpans = if (usePrecise || precise.isEmpty()) null else precise)
            groups[merged.id] = merged
            allKeys.forEach { owners[it] = merged.id }
        }
        return groups.values.toList()
    }

    fun aggregate(base: StatsData, input: List<ListeningEvent>, zone: ZoneId = ZoneId.systemDefault()): StatsData {
        val daily = linkedMapOf<LocalDate, Long>()
        val hourly = linkedMapOf<LocalDate, LongArray>()
        val tracks = linkedMapOf<String, TrackMeta>()
        val metadata = linkedMapOf<String, TrackMeta>()
        val counts = linkedMapOf<String, Int>()
        val localLibrary = DataFiles.sourceId()
        fun localTrackId(event: ListeningEvent) =
            if (event.libraryId == localLibrary) event.track.id else "${event.libraryId}:${event.track.id}"
        // Current library / already resolved local metadata wins. Incoming backups only fill gaps.
        // Resolve before the session union so choosing a longer old snapshot cannot choose its tags.
        for (event in input) {
            val trackId = localTrackId(event)
            val meta = event.track.copy(id = trackId)
            metadata[trackId] = (metadata[trackId] ?: base.tracks[trackId])?.let { mergeTrack(it, meta) } ?: meta
        }
        val events = union(input).map { event ->
            val trackId = localTrackId(event)
            val meta = event.track.copy(id = trackId)
            val resolved = metadata[trackId]?.let { mergeTrack(it, meta) } ?: meta
            tracks[trackId] = resolved
            event.copy(track = resolved.copy(id = event.track.id))
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
