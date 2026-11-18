import com.jethuit.spw.listenstats.PluginState
import com.jethuit.spw.listenstats.data.*
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    val root = Path.of(args.single())
    Files.createDirectories(root)
    PluginState.pluginPath = root.resolve("workshop/plugins/listenstats").toString()
    val source = DataFiles.sourceId()
    val track = TrackMeta("one", "当前标题", "歌手", "专辑", "", 10_000)
    val other = track.copy(id = "two")
    val host = ListeningEvent("host:$source:one", track, listOf(ListeningSpan(1_000, 9_000)),
        approximate = true, libraryId = source)
    val precise = ListeningEvent("live:one", track.copy(title = ""), listOf(ListeningSpan(2_000, 3_000)),
        aliases = setOf(host.id), libraryId = source)
    val exact = ListeningEvent("independent", other, listOf(ListeningSpan(20_000, 6_000)),
        aliases = setOf("independent", "retired"), libraryId = source)
    val foreign = ListeningEvent("foreign", track.copy(title = "外库标题"), listOf(ListeningSpan(40_000, 7_000)),
        libraryId = "11111111-1111-1111-1111-111111111111")
    val zero = ListeningEvent("zero", track, listOf(ListeningSpan(0, 0)), approximate = true, libraryId = source)
    val disjoint = ListeningEvent("disjoint", track,
        listOf(ListeningSpan(60_000, 1_000), ListeningSpan(62_000, 1_000)), libraryId = source)
    val input = listOf(host, exact, foreign, precise, zero, disjoint)
    val union = ListeningEvents.union(input)
    val merged = union.single { it.id == host.id }
    check(merged.durationMs == 9_000L && merged.observedSpans() == precise.spans && merged.approximate)
    check(merged.track.title == track.title && "live:one" in merged.aliases)
    check(union.single { it.id == exact.id } == exact.copy(aliases = setOf("retired")))
    check(union.single { it.id == "zero" }.let { it.spans.isEmpty() && !it.approximate })
    check(union.single { it.id == "disjoint" }.spans == disjoint.spans)
    check(ListeningEvents.union(union + input) == union) { "Repeated input must stay idempotent" }
    val resolved = ListeningEvents.aggregate(StatsData.empty(), input)
    check(resolved.tracks[track.id]?.title == track.title)
    check(resolved.tracks["${foreign.libraryId}:${track.id}"]?.title == "外库标题")
    check(resolved.dailyMillis.values.sum() == 24_000L)
    // A later event can join two independent fast-path owners through aliases.
    val bridge = ListeningEvent("bridge", other, listOf(ListeningSpan(25_000, 2_000)),
        aliases = setOf("retired", "newer"), libraryId = source)
    val newer = exact.copy(id = "newer", aliases = emptySet(), spans = listOf(ListeningSpan(30_000, 1_000)))
    val bridged = ListeningEvents.union(listOf(exact, newer, bridge))
    check(bridged.size == 1 && bridged.single().durationMs == 8_000L)
    check(bridged.single().aliases + bridged.single().id == setOf("independent", "retired", "newer", "bridge"))
    println("PASS independent event fast path preserves alias bridges, precise evidence, zero spans, metadata and foreign identity")

    val media = root.resolve("track.m4a")
    Files.writeString(media, "synthetic media")
    val located = track.copy(path = media.toString())
    var fileChecks = 0
    PerformanceProbe.counterSink = { if (it == "TrackVisibility.fileCheck") fileChecks++ }
    try {
        val first = TrackVisibility.Snapshot()
        repeat(100) { check(first.isVisible(located.copy(path = " ${media} "))) }
        check(fileChecks == 1)
        Files.delete(media)
        check(first.isVisible(located)) // A batch remains internally consistent.
        val second = TrackVisibility.Snapshot()
        check(!second.isVisible(located) && !second.matches(first) && fileChecks == 2)
        Files.writeString(media, "restored synthetic media")
        val third = TrackVisibility.Snapshot()
        check(third.isVisible(located) && third.matches(first) && fileChecks == 3)
        check(third.isVisible(track) && third.isVisible(track.copy(path = "\u0000")))
        check(third.isVisible(track.copy(path = media.toUri().toString())))
        check(!third.isVisible(track.copy(path = root.toString())))
    } finally { PerformanceProbe.counterSink = null }
    println("PASS visibility is checked once per batch, detects deletion/restoration next time and preserves unknown/URI path rules")
}
