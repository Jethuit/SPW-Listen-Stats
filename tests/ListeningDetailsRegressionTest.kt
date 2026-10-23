import com.jethuit.spw.listenstats.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

fun main() {
    val utc = ZoneId.of("UTC")
    val date = LocalDate.of(2026, 10, 20)
    val epoch = date.atStartOfDay(utc).toInstant().toEpochMilli()
    val track = TrackMeta("one", "同名歌曲", "歌手", "专辑", "", 600_000L)
    fun span(offset: Long, duration: Long) = ListeningSpan(epoch + offset, duration)
    fun data(events: List<ListeningEvent>, bucket: ChartBucket) = StatsData.empty().copy(
        events = events, tracks = mapOf(track.id to track), dailyMillis = mapOf(date to bucket.millis))
    fun bucket(hour: Int?, total: Long, end: LocalDate = date) = ChartBucket("", "", total, date, end, hour)
    val first = ListeningEvent("a", track, listOf(span(-60_000, 120_000), span(3_660_000, 120_000)), libraryId = "local")
    val hour = bucket(0, 60_000)
    val detail = ListeningDetailsCalculator.calculate(data(listOf(first), hour), hour, utc, "local")
    check(detail.rows.single().millis == 60_000L && detail.unknownMillis == 0L)
    val paused = bucket(1, 120_000)
    check(ListeningDetailsCalculator.calculate(data(listOf(first), paused), paused, utc, "local").rows.single().millis == 120_000L)
    println("PASS cross-midnight clipping, hour selection and pause gaps")

    val hybrid = ListeningEvent("hybrid", track, listOf(span(0, 120_000), span(3_600_000, 60_000)),
        approximate = true, libraryId = "local", preciseSpans = listOf(span(0, 120_000)))
    check(ListeningDetailsCalculator.calculate(data(listOf(hybrid), hour), hour, utc, "local").rows.single().approximateMillis == 0L)
    check(ListeningDetailsCalculator.calculate(data(listOf(hybrid), paused), paused, utc, "local").rows.single().approximateMillis == 60_000L)
    println("PASS approximate labels depend only on selected-period evidence")

    val duplicateTitle = first.copy(id = "other", track = track.copy(id = "two"))
    val foreign = first.copy(id = "foreign", libraryId = "other-library")
    val day = bucket(null, 900_000)
    val rows = ListeningDetailsCalculator.calculate(data(listOf(first, duplicateTitle, foreign), day), day, utc, "local")
    check(rows.rows.size == 3 && rows.rows.map { it.track.id }.toSet() == setOf("one", "two", "other-library:one"))
    check(rows.rows.sumOf { it.millis } == 540_000L && rows.unknownMillis == 360_000L)
    check(ListeningDetailsCalculator.calculate(data(emptyList(), day), day, utc, "local").unknownMillis == 900_000L)
    val merged = ListeningDetailsCalculator.calculate(data(listOf(first, first.copy(id = "second-play")), day), day, utc, "local")
    check(merged.rows.single().millis == 360_000L)
    println("PASS track identities, repeated plays and legacy unassigned duration")

    val week = bucket(null, 240_000, date.plusDays(2))
    val boundary = first.copy(spans = listOf(span(-60_000, 120_000), span(3 * 86_400_000L - 60_000, 120_000)))
    val clipped = ListeningDetailsCalculator.calculate(data(listOf(boundary), week), week, utc, "local")
    check(clipped.rows.single().millis == 120_000L)
    println("PASS partial-week bounds exclude adjacent periods")

    val dstZone = ZoneId.of("America/New_York")
    val dstDate = LocalDate.of(2026, 11, 1)
    val repeatedHour = first.copy(spans = listOf(
        ListeningSpan(Instant.parse("2026-11-01T05:30:00Z").toEpochMilli(), 60 * 60_000L)))
    val dstBucket = ChartBucket("", "", 3_600_000, dstDate, dstDate, 1)
    check(ListeningDetailsCalculator.calculate(data(listOf(repeatedHour), dstBucket), dstBucket, dstZone, "local")
        .rows.single().millis == 3_600_000L)
    println("PASS repeated DST hour matches chart aggregation")
}
