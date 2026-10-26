package com.jethuit.spw.listenstats.data

import java.text.Collator
import java.util.Locale

enum class RankingMode(val storage: String, val label: String, val itemUnit: String) {
    Song("song", "歌曲", "首"),
    Artist("artist", "艺术家", "位"),
    Album("album", "专辑", "张");

    val title: String get() = title(RankingMetric.Count)
    fun title(metric: RankingMetric): String = when (metric) {
        RankingMetric.Count -> "${label}播放次数"
        RankingMetric.Duration -> "${label}收听时长"
    }
    val searchPlaceholder: String get() = when (this) {
        Song -> "搜索歌曲或歌手"
        Artist -> "搜索歌手"
        Album -> "搜索专辑或艺术家"
    }

    companion object {
        fun fromStorage(value: String): RankingMode = entries.firstOrNull { it.storage == value } ?: Song
    }
}

enum class RankingMetric(val storage: String, val label: String, val header: String) {
    Count("count", "播放次数", "播放次数"),
    Duration("duration", "收听时长", "收听时长");

    companion object {
        fun fromStorage(value: String): RankingMetric =
            entries.firstOrNull { it.storage == value } ?: Count
    }
}

data class RankingKey(val mode: RankingMode, val name: String, val artist: String = "",
                      val unknownTrackId: String? = null)

data class RankingEntry(
    val key: RankingKey,
    val title: String,
    val subtitle: String,
    val detail: String,
    val count: Long,
    val coverTrackId: String?,
    val durationMs: Long = 0L
) {
    fun metricValue(metric: RankingMetric): Long = when (metric) {
        RankingMetric.Count -> count
        RankingMetric.Duration -> durationMs
    }
}

data class RankedEntry(val position: Int, val value: RankingEntry)

object Rankings {
    private fun normalized(value: String) = value.trim().lowercase(Locale.ROOT)
    private val slashBand = Regex("(?i)(?<![\\p{L}\\p{N}])AC/DC(?![\\p{L}\\p{N}])")

    private fun albumKey(track: TrackMeta): RankingKey {
        val album = track.album.trim()
        val artist = track.albumArtist.trim().ifBlank { track.artist.trim() }
        // Untagged albums cannot safely be grouped across unrelated tracks.
        return RankingKey(RankingMode.Album, normalized(album), normalized(artist),
            if (album.isBlank()) track.id else null)
    }

    private fun artistCover(members: List<TrackRank>): String {
        val tagged = members.filter { it.track.album.isNotBlank() }.ifEmpty { members }
        // Members already follow the song ranking. For a winning album, use its top song's cover.
        return tagged.groupBy { albumKey(it.track) }.values
            .maxBy { album -> album.sumOf { it.count.toLong() } }.let(::coverTrack)
    }

    private fun coverTrack(members: List<TrackRank>): String =
        (members.firstOrNull { CoverStore.canLoad(it.track.id) } ?: members.first()).track.id

    /** SPW exposes a display string, not structured credits. Keep '&' and commas in band names. */
    fun artists(credits: String): List<String> {
        val protected = slashBand.replace(credits) { it.value.replace('/', '\u0000') }
        return protected.split('/', ';', '；').map { it.replace('\u0000', '/').trim() }
            .filter { it.isNotEmpty() }.distinctBy(::normalized).ifEmpty { listOf("未知歌手") }
    }

    fun ranked(data: StatsData, mode: RankingMode, query: String = "",
               metric: RankingMetric = RankingMetric.Count): List<RankedEntry> {
        val tracks = data.sortedTracks(metric)
        val entries = if (mode == RankingMode.Song) {
            tracks.map {
                RankingEntry(RankingKey(mode, it.track.id), it.track.title, it.track.artist,
                    it.track.album, it.count.toLong(), it.track.id, it.durationMs)
            }
        } else {
            val groups = linkedMapOf<RankingKey, MutableList<TrackRank>>()
            val labels = mutableMapOf<RankingKey, Pair<String, String>>()
            for (track in tracks) {
                if (mode == RankingMode.Artist) {
                    for (artist in artists(track.track.artist)) {
                        val key = RankingKey(mode, normalized(artist))
                        groups.getOrPut(key) { mutableListOf() }.add(track)
                        labels.putIfAbsent(key, artist to "")
                    }
                } else {
                    val album = track.track.album.trim()
                    val artist = track.track.albumArtist.trim().ifBlank { track.track.artist.trim() }
                    val key = albumKey(track.track)
                    groups.getOrPut(key) { mutableListOf() }.add(track)
                    labels.putIfAbsent(key, album.ifBlank { "未知专辑" } to artist.ifBlank { "未知歌手" })
                }
            }
            val collator = Collator.getInstance(Locale.CHINA)
            groups.map { (key, members) ->
                val (title, artist) = labels.getValue(key)
                RankingEntry(key, title, if (mode == RankingMode.Artist) "" else artist,
                    "${members.size} 首", members.sumOf { it.count.toLong() },
                    if (mode == RankingMode.Artist) artistCover(members) else coverTrack(members),
                    members.sumOf { it.durationMs })
            }.sortedWith(compareByDescending<RankingEntry> { it.metricValue(metric) }
                .thenComparator { a, b -> collator.compare(a.title, b.title) }
                .thenComparator { a, b -> collator.compare(a.subtitle, b.subtitle) })
        }
        val needle = normalized(query)
        return entries.mapIndexed { index, entry -> RankedEntry(index + 1, entry) }.filter {
            needle.isEmpty() || normalized(it.value.title).contains(needle) ||
                normalized(it.value.subtitle).contains(needle)
        }
    }
}
