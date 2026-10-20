package com.jethuit.spw.listenstats.data

import java.text.Collator
import java.util.Locale

enum class RankingMode(val storage: String, val label: String, val itemUnit: String) {
    Song("song", "歌曲", "首"),
    Artist("artist", "歌手", "位"),
    Album("album", "专辑", "张");

    val title: String get() = "${label}播放次数"
    val searchPlaceholder: String get() = when (this) {
        Song -> "搜索歌曲或歌手"
        Artist -> "搜索歌手"
        Album -> "搜索专辑或艺术家"
    }

    companion object {
        fun fromStorage(value: String): RankingMode = entries.firstOrNull { it.storage == value } ?: Song
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
    val coverTrackId: String?
)

data class RankedEntry(val position: Int, val value: RankingEntry)

object Rankings {
    private fun normalized(value: String) = value.trim().lowercase(Locale.ROOT)
    private val slashBand = Regex("(?i)(?<![\\p{L}\\p{N}])AC/DC(?![\\p{L}\\p{N}])")

    /** SPW exposes a display string, not structured credits. Keep '&' and commas in band names. */
    fun artists(credits: String): List<String> {
        val protected = slashBand.replace(credits) { it.value.replace('/', '\u0000') }
        return protected.split('/', ';', '；').map { it.replace('\u0000', '/').trim() }
            .filter { it.isNotEmpty() }.distinctBy(::normalized).ifEmpty { listOf("未知歌手") }
    }

    fun ranked(data: StatsData, mode: RankingMode, query: String = ""): List<RankedEntry> {
        val tracks = data.sortedTracks()
        val entries = if (mode == RankingMode.Song) {
            tracks.map {
                RankingEntry(RankingKey(mode, it.track.id), it.track.title, it.track.artist,
                    it.track.album, it.count.toLong(), it.track.id)
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
                    // Without an album tag there is no evidence that unrelated tracks share an album.
                    val key = RankingKey(mode, normalized(album), normalized(artist),
                        if (album.isBlank()) track.track.id else null)
                    groups.getOrPut(key) { mutableListOf() }.add(track)
                    labels.putIfAbsent(key, album.ifBlank { "未知专辑" } to artist.ifBlank { "未知歌手" })
                }
            }
            val collator = Collator.getInstance(Locale.CHINA)
            groups.map { (key, members) ->
                val (title, artist) = labels.getValue(key)
                RankingEntry(key, title, if (mode == RankingMode.Artist) "" else artist,
                    "${members.size} 首", members.sumOf { it.count.toLong() },
                    if (mode == RankingMode.Album) members.first().track.id else null)
            }.sortedWith(compareByDescending<RankingEntry> { it.count }
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
