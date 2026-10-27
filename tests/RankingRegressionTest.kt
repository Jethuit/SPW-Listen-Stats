import com.jethuit.spw.listenstats.data.*
import com.jethuit.spw.listenstats.ui.rankingSafePage
import com.jethuit.spw.listenstats.ui.rankingTotalPages

fun main() {
    var passed = 0
    fun test(name: String, action: () -> Unit) { action(); passed++; println("PASS $name") }
    fun track(id: String, artist: String, album: String = "One", albumArtist: String = "Band") =
        TrackMeta(id, "Song $id", artist, album, albumArtist, 240_000)
    fun data(vararg entries: Pair<TrackMeta, Int>): StatsData = StatsData.empty().copy(
        tracks = entries.associate { it.first.id to it.first },
        playCounts = entries.associate { it.first.id to it.second })
    test("song ranking keeps its order, original positions, search and count") {
        val d = data(track("1", "A") to 4, track("2", "B") to 7, track("3", "A") to 2)
        val original = d.rankedTracks("A")
        val actual = Rankings.ranked(d, RankingMode.Song, " A ")
        check(actual.map { it.position } == original.map { it.position })
        check(actual.map { it.value.count } == original.map { it.value.count.toLong() })
        check(Rankings.ranked(d, RankingMode.Song, "Song 2").single().position == 1)
    }
    test("duration ranking uses actual listening time and includes sub-50-percent plays") {
        val first = track("1", "A", "One")
        val second = track("2", "B", "Two")
        val third = track("3", "A", "One")
        val d = StatsData.empty().copy(
            tracks = listOf(first, second, third).associateBy { it.id },
            playCounts = mapOf("1" to 1, "3" to 2),
            listens = listOf(
                ListenEntry("first", "1", 1L, 70_000L),
                ListenEntry("second", "2", 2L, 90_000L),
                ListenEntry("third", "3", 3L, 30_000L)
            )
        )
        val songs = Rankings.ranked(d, RankingMode.Song, metric = RankingMetric.Duration)
        check(songs.map { it.value.key.name } == listOf("2", "1", "3"))
        check(songs.map { it.value.durationMs } == listOf(90_000L, 70_000L, 30_000L))
        val artists = Rankings.ranked(d, RankingMode.Artist, metric = RankingMetric.Duration)
        check(artists.map { it.value.title } == listOf("A", "B"))
        check(artists.first().value.durationMs == 100_000L)
        val albums = Rankings.ranked(d, RankingMode.Album, metric = RankingMetric.Duration)
        check(albums.map { it.value.title } == listOf("One", "Two"))
        check(RankingMetric.fromStorage("duration") == RankingMetric.Duration)
    }
    test("collaborations count once per credited artist and sum different songs") {
        val d = data(track("1", "A/B/A") to 5, track("2", " a ") to 3,
            track("3", "B; C；C") to 2, track("4", "AC/DC") to 4,
            track("5", "Simon & Garfunkel") to 1)
        val result = Rankings.ranked(d, RankingMode.Artist)
        fun count(name: String) = result.single { it.value.title == name }.value.count
        check(count("A") == 8L && count("B") == 7L && count("C") == 2L)
        check(count("AC/DC") == 4L && count("Simon & Garfunkel") == 1L)
        check(result.first().value.detail == "2 首")
        check(Rankings.artists("A/AC/DC/B") == listOf("A", "AC/DC", "B"))
        check(Rankings.artists("A/B") == listOf("A", "B"))
    }
    test("album artist groups a compilation while same-title different artists stay separate") {
        val d = data(track("1", "A", "Hits", "Various") to 3,
            track("2", "B", " Hits ", "Various") to 4,
            track("3", "C", "Hits", "C") to 6,
            track("4", "D", "Hits", "") to 2)
        val result = Rankings.ranked(d, RankingMode.Album)
        check(result.size == 3 && result.map { it.value.count } == listOf(7L, 6L, 2L))
        check(result.first().value.subtitle == "Various" && result.first().value.detail == "2 首")
        check(result.first().value.coverTrackId == "2")
        check(Rankings.ranked(d, RankingMode.Album, "C").single().position == 2)
    }
    test("artist cover follows the highest-total album, not the highest individual song") {
        val d = data(track("hit", "A", "Single", "A") to 10,
            track("album1", "A/B", "Album", "A") to 7,
            track("album2", "A", "Album", "A") to 6,
            track("other", "A", "Album", "Other") to 2,
            track("untagged", "A", "", "A") to 20)
        val result = Rankings.ranked(d, RankingMode.Artist)
        val a = result.single { it.value.title == "A" }.value
        check(a.count == 45L && a.coverTrackId == "album1")
        check(result.single { it.value.title == "B" }.value.coverTrackId == "album1")
        // Other artist's same-title album must not join the 13-play album above.
        val separate = data(track("one", "A", "Same", "One") to 8,
            track("two", "A", "Same", "Two") to 5,
            track("win", "A", "Winner", "A") to 10)
        check(Rankings.ranked(separate, RankingMode.Artist).single().value.coverTrackId == "win")
        val unknown = data(track("1", "A", "", "") to 3, track("2", "A", "", "") to 5)
        check(Rankings.ranked(unknown, RankingMode.Artist).single().value.coverTrackId == "2")
    }
    test("missing metadata stays visible without guessing that unknown albums are the same") {
        val d = data(track("1", "", "", "") to 2, track("2", "", "", "") to 3,
            track("3", "A", "One", "Band") to 0)
        val albums = Rankings.ranked(d, RankingMode.Album)
        check(albums.size == 2 && albums.all { it.value.title == "未知专辑" })
        check(albums.map { it.value.key }.distinct().size == 2)
        val artists = Rankings.ranked(d, RankingMode.Artist)
        check(artists.single().value.title == "未知歌手" && artists.single().value.count == 5L)
        for (mode in RankingMode.entries) check(Rankings.ranked(StatsData.empty(), mode).isEmpty())
    }
    test("group counts do not overflow Int and artist totals are not forced to song totals") {
        val d = data(track("1", "A/B") to Int.MAX_VALUE, track("2", "A/B") to Int.MAX_VALUE)
        for (mode in listOf(RankingMode.Artist, RankingMode.Album)) {
            check(Rankings.ranked(d, mode).all { it.value.count == 4_294_967_294L })
        }
    }
    test("search uses full-list positions; mode-sized pagination clamps stale pages") {
        val d = data(*(1..80).map { track("$it", "Artist $it", "Album $it", "Artist $it") to 81 - it }.toTypedArray())
        for (mode in RankingMode.entries) {
            val result = Rankings.ranked(d, mode)
            check(result.size == 80 && rankingTotalPages(result.size) == 4)
            val match = Rankings.ranked(d, mode, "80").single()
            check(match.position == 80)
            check(rankingSafePage(1, 4) == 1 && rankingSafePage(0, 4) == 1)
        }
        check(RankingMode.fromStorage("invalid") == RankingMode.Song)
    }
    println("$passed ranking regression groups passed")
}
