package com.jethuit.spw.listenstats.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

data class TrackMeta(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val durationMs: Long
)

data class ListenEntry(
    val id: String,
    val trackId: String,
    val startTimeMs: Long,
    val durationMs: Long
)

data class StatsData(
    val listens: List<ListenEntry>,
    val tracks: Map<String, TrackMeta>,
    val dailyMillis: Map<LocalDate, Long>,
    val hourlyMillis: Map<LocalDate, LongArray>,
    val playCounts: Map<String, Int>,
    val covers: Map<String, ByteArray>,
    val loadedAt: Long,
    val databaseFound: Boolean,
    val error: String?,
    val events: List<ListeningEvent> = emptyList()
) {
    fun millisOn(date: LocalDate): Long = dailyMillis[date] ?: 0L

    fun hoursOn(date: LocalDate): LongArray = hourlyMillis[date] ?: LongArray(24)

    fun playCount(trackId: String): Int = playCounts[trackId] ?: 0

    fun sortedTracks(): List<TrackRank> {
        val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
        val entries = playCounts.entries
            .filter { it.value > 0 }
            .mapNotNull { (id, count) ->
                tracks[id]?.let { TrackRank(it, count) }
            }
        return entries.sortedWith(
            compareByDescending<TrackRank> { it.count }
                .thenComparator { left, right -> collator.compare(left.track.title, right.track.title) }
        )
    }

    fun rankedTracks(query: String = ""): List<RankedTrack> {
        val needle = query.trim().lowercase()
        return sortedTracks().mapIndexed { index, track -> RankedTrack(index + 1, track) }
            .filter { needle.isEmpty() || it.value.track.title.lowercase().contains(needle) ||
                it.value.track.artist.lowercase().contains(needle) }
    }

    companion object {
        fun empty(error: String? = null, databaseFound: Boolean = false): StatsData = StatsData(
            listens = emptyList(),
            tracks = emptyMap(),
            dailyMillis = emptyMap(),
            hourlyMillis = emptyMap(),
            playCounts = emptyMap(),
            covers = emptyMap(),
            loadedAt = System.currentTimeMillis(),
            databaseFound = databaseFound,
            error = error
        )
    }
}

data class TrackRank(
    val track: TrackMeta,
    val count: Int
)

data class RankedTrack(val position: Int, val value: TrackRank)

data class DateRange(
    val start: LocalDate,
    val endInclusive: LocalDate
) {
    val days: Int
        get() = java.time.temporal.ChronoUnit.DAYS.between(start, endInclusive).toInt() + 1

    fun dates(): List<LocalDate> {
        val result = ArrayList<LocalDate>(days)
        var cursor = start
        while (!cursor.isAfter(endInclusive)) {
            result.add(cursor)
            cursor = cursor.plusDays(1)
        }
        return result
    }
}

fun epochMillisToLocalDateTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
    java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()
