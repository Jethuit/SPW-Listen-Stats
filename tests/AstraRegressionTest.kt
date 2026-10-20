import com.jethuit.spw.listenstats.PluginState
import com.jethuit.spw.listenstats.data.*
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val T = 1_792_300_000_000L
private val song = TrackMeta("song", "Known", "Artist", "Album", "Artist", 240_000)
private var cases = 0
private fun test(name: String, action: () -> Unit) { action(); cases++; println("PASS $name") }
private fun isolate(root: Path, name: String) {
    PluginState.pluginPath = root.resolve("$name/workshop/plugins/listenstats").toString()
    LiveStore.load()
}
private fun piece(id: String, estimate: Long, start: Long, duration: Long, track: TrackMeta = song) =
    LiveSession(id, track.id, estimate, duration, track.title, track.artist, track.album,
        track.albumArtist, track.durationMs, true, mutableListOf(ListeningSpan(start, duration)))
private fun total(data: StatsData) = data.dailyMillis.values.sum()
private fun checkData(data: StatsData, millis: Long, count: Int) {
    check(total(data) == millis && data.playCounts.values.sum() == count) {
        "expected $millis/$count; actual ${total(data)}/${data.playCounts.values.sum()} ${data.events}"
    }
}

fun main(args: Array<String>) {
    val root = Path.of(args.single())
    test("Lord Howe half-hour rollback terminates and preserves a one-second record") {
        val pool = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
        try {
            val result = pool.submit<Long> {
                val daily = linkedMapOf<LocalDate, Long>()
                val hourly = linkedMapOf<LocalDate, LongArray>()
                TimeSplitter.split(Instant.parse("2026-04-04T15:15:00Z").toEpochMilli(), 1_000,
                    ZoneId.of("Australia/Lord_Howe"), daily, hourly)
                check(hourly.getValue(LocalDate.of(2026, 4, 5))[1] == 1_000L)
                daily.values.sum()
            }
            check(result.get(2, TimeUnit.SECONDS) == 1_000L)
        } finally { pool.shutdownNow() }
    }
    test("half-hour forward jump and skipped local date assign each real millisecond once") {
        val daily = linkedMapOf<LocalDate, Long>()
        val hourly = linkedMapOf<LocalDate, LongArray>()
        TimeSplitter.split(Instant.parse("2026-10-03T15:15:00Z").toEpochMilli(), 3_600_000,
            ZoneId.of("Australia/Lord_Howe"), daily, hourly)
        val hours = hourly.getValue(LocalDate.of(2026, 10, 4))
        check(hours[1] == 900_000L && hours[2] == 1_800_000L && hours[3] == 900_000L)
        check(daily.values.sum() == 3_600_000L)
        daily.clear(); hourly.clear()
        TimeSplitter.split(Instant.parse("2011-12-30T09:30:00Z").toEpochMilli(), 3_600_000,
            ZoneId.of("Pacific/Apia"), daily, hourly)
        check(daily == mapOf(LocalDate.of(2011, 12, 29) to 1_800_000L,
            LocalDate.of(2011, 12, 31) to 1_800_000L))
    }
    test("checkpoint rollback plus backup import unions disjoint branches to 14 seconds and one play") {
        isolate(root, "crash-branch")
        val track = song.copy(durationMs = 20_000)
        val checkpoint = piece("same-play", T, T, 2_000, track).copy(finalized = false)
        LiveStore.upsert(checkpoint); LiveStore.save()
        LiveStore.upsert(checkpoint.copy(durationMs = 8_000, spans = mutableListOf(ListeningSpan(T, 8_000))))
        val backup = root.resolve("before-crash.json")
        BackupService.exportFile(backup, LiveStore.merge(StatsData.empty()))
        LiveStore.load()
        val engine = PlaybackAccumulator({ LiveStore.upsert(it) }) { LiveStore.recoverOpen(it.id, T + 20_000) }
        engine.playing(true)
        for (i in 0L..6L) engine.sample(track, 8_000 + i * 1_000,
            T + 20_000 + i * 1_000, i * 1_000_000_000)
        engine.finish()
        val resumed = LiveStore.merge(StatsData.empty())
        BackupService.importFile(backup)
        val merged = BackupService.merge(resumed)
        val reversed = ListeningEvents.aggregate(StatsData.empty(), BackupCodec.read(backup).events + resumed.events)
        checkData(merged, 14_000, 1); checkData(reversed, 14_000, 1)
        check(merged.events.single().spans == reversed.events.single().spans)
        checkData(BackupCodec.apply(merged, BackupCodec.read(backup)), 14_000, 1)
    }
    test("exact evidence survives a longer approximate snapshot and backup roundtrip") {
        isolate(root, "precise-evidence")
        val hostId = "host:${DataFiles.sourceId()}:row"
        val a = piece("same", T, T, 8_000).event().copy(aliases = setOf(hostId))
        val b = a.copy(spans = listOf(ListeningSpan(T, 2_000), ListeningSpan(T + 20_000, 6_000)))
        val c = a.copy(spans = listOf(ListeningSpan(T + 30_000, 2_000)))
        val host = a.copy(id = hostId, aliases = emptySet(), spans = listOf(ListeningSpan(T, 15_000)), approximate = true)
        for (input in listOf(listOf(a, b, host), listOf(host, b, a), listOf(b, host, a))) {
            val bundle = BackupCodec.decode(BackupCodec.encode(BackupBundle(ListeningEvents.union(input))))
            check(bundle.events.single().durationMs == 15_000L)
            val result = ListeningEvents.union(bundle.events + c).single()
            check(result.durationMs == 16_000L && !result.approximate)
            check(result.spans == listOf(ListeningSpan(T, 8_000), ListeningSpan(T + 20_000, 6_000),
                ListeningSpan(T + 30_000, 2_000)))
            check(ListeningEvents.union(listOf(result, result)).single().durationMs == 16_000L)
        }
    }
    test("unobserved previous same-song host play never steals the new play") {
        isolate(root, "adjacent-host")
        val track = song.copy(durationMs = 4_000)
        LiveStore.upsert(piece("new", T + 5_000, T + 5_000, 3_000, track))
        val old = StatsData.empty().copy(tracks = mapOf(track.id to track),
            listens = listOf(ListenEntry("old", track.id, T, 2_200)))
        checkData(LiveStore.merge(old), 5_200, 2)
        check(LiveStore.snapshot().single().hostId == null)
        val both = old.copy(listens = old.listens + ListenEntry("new", track.id, T + 5_000, 3_000))
        checkData(LiveStore.merge(both), 5_200, 2)
        LiveStore.save(); LiveStore.load()
        checkData(LiveStore.merge(both), 5_200, 2)
    }
    test("old wrong binding and aliases are removed durably even after a stale accumulator upsert") {
        isolate(root, "repair-host")
        val track = song.copy(durationMs = 4_000)
        val badId = "host:${DataFiles.sourceId()}:old"
        val stale = piece("new", T + 5_000, T + 5_000, 3_000, track)
            .copy(hostId = badId, hostAliases = setOf(badId))
        LiveStore.upsert(stale)
        val old = StatsData.empty().copy(tracks = mapOf(track.id to track),
            listens = listOf(ListenEntry("old", track.id, T, 2_200)))
        checkData(LiveStore.merge(old), 5_200, 2)
        LiveStore.save(); LiveStore.load(); LiveStore.upsert(stale)
        val both = old.copy(listens = old.listens + ListenEntry("new", track.id, T + 5_000, 3_000))
        checkData(LiveStore.merge(both), 5_200, 2)
        LiveStore.upsert(stale); LiveStore.save(); LiveStore.load()
        checkData(LiveStore.merge(both), 5_200, 2)
        check(badId !in LiveStore.snapshot().single().event().aliases)
    }
    test("paused legacy lifecycle fragments share one host play despite shifted origin estimate") {
        isolate(root, "paused-legacy")
        LiveStore.upsert(piece("first", T, T, 60_000))
        LiveStore.upsert(piece("second", T + 60_000, T + 120_000, 160_000))
        val oldExport = BackupBundle(LiveStore.merge(StatsData.empty()).events)
        val host = StatsData.empty().copy(tracks = mapOf(song.id to song),
            listens = listOf(ListenEntry("one-play", song.id, T, 220_000)))
        checkData(BackupCodec.apply(LiveStore.merge(host), oldExport), 220_000, 1)
        LiveStore.save(); LiveStore.load()
        checkData(LiveStore.merge(host), 220_000, 1)
    }
    test("real accumulator positions survive pause and normal plugin restart; next replay stays separate") {
        isolate(root, "paused-positions")
        val first = PlaybackAccumulator { LiveStore.upsert(it) }
        first.playing(true)
        for (i in 0L..60L) first.sample(song, i * 1_000, T + i * 1_000, i * 1_000_000_000)
        first.playing(false); first.finish()
        LiveStore.save(); LiveStore.load()
        val second = PlaybackAccumulator { LiveStore.upsert(it) }
        second.playing(true)
        for (i in 0L..160L) second.sample(song, 60_000 + i * 1_000,
            T + 120_000 + i * 1_000, i * 1_000_000_000)
        second.finish()
        check(LiveStore.snapshot().last().firstPositionMs == 60_000L)
        val host = StatsData.empty().copy(tracks = mapOf(song.id to song),
            listens = listOf(ListenEntry("one-play", song.id, T, 220_000)))
        checkData(LiveStore.merge(host), 220_000, 1)
        LiveStore.upsert(piece("replay", T + 281_000, T + 281_000, 120_000))
        checkData(LiveStore.merge(host), 340_000, 2)
    }
    test("distinct observed plays within one lifecycle cannot merge just because origins are near") {
        isolate(root, "same-lifecycle")
        LiveStore.upsert(piece("one", T, T, 100).copy(trackDurationMs = 200,
            firstPositionMs = 0, lastPositionMs = 100, lifecycleId = "one-run"))
        LiveStore.upsert(piece("two", T + 150, T + 150, 100).copy(trackDurationMs = 200,
            firstPositionMs = 0, lastPositionMs = 100, lifecycleId = "one-run"))
        val host = StatsData.empty().copy(tracks = mapOf(song.id to song.copy(durationMs = 200)),
            listens = listOf(ListenEntry("first", song.id, T, 100)))
        checkData(LiveStore.merge(host), 200, 2)
    }
    println("$cases Astra regression groups passed")
}
