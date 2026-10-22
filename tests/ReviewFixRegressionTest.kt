@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

import com.jethuit.spw.listenstats.PluginState
import com.jethuit.spw.listenstats.data.*
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

private const val T = 1_792_300_000_000L
private val song = TrackMeta("song", "Known", "Artist", "Album", "Artist", 12_000)
private var cases = 0
private fun test(name: String, body: () -> Unit) { body(); cases++; println("PASS $name") }
private fun isolate(root: Path, name: String) {
    PluginState.pluginPath = root.resolve("$name/workshop/plugins/listenstats").toString()
    LiveStore.load()
}
private fun flushTracker() {
    val f = PlaybackTracker::class.java.getDeclaredField("executor").apply { isAccessible = true }
    (f.get(PlaybackTracker) as ExecutorService).submit {}.get(4, TimeUnit.SECONDS)
}

fun main(args: Array<String>) {
    val root = Path.of(args.single())
    test("buffering loses timing baseline but preserves automatic rollover evidence") {
        val sessions = linkedMapOf<String, LiveSession>()
        val engine = PlaybackAccumulator { sessions[it.id] = it.copyForStore() }
        engine.playing(true)
        for (i in 0L..11L) engine.sample(song, i * 1_000, T + i * 1_000, i * 1_000_000_000)
        engine.suspendSampling()
        for (i in 0L..7L) engine.sample(song, i * 1_000, T + 12_000 + i * 1_000, (12 + i) * 1_000_000_000)
        engine.finish()
        check(sessions.size == 2 && sessions.values.sumOf { it.durationMs } == 18_000L)
        isolate(root, "loop")
        check(ListeningEvents.aggregate(StatsData.empty(), sessions.values.map { it.event() }).playCount(song.id) == 2)
    }
    test("explicit backward seek near the end stays in one play") {
        val sessions = linkedMapOf<String, LiveSession>()
        val engine = PlaybackAccumulator { sessions[it.id] = it.copyForStore() }
        engine.playing(true)
        for (i in 0L..11L) engine.sample(song, i * 1_000, T + i * 1_000, i * 1_000_000_000)
        engine.seek(1_000)
        engine.sample(song, 1_000, T + 12_000, 12_000_000_000)
        engine.sample(song, 2_000, T + 13_000, 13_000_000_000)
        engine.finish()
        check(sessions.size == 1 && sessions.values.single().durationMs == 12_000L)
    }
    test("two-second callbacks at double speed count wall time without counting a seek") {
        val sessions = linkedMapOf<String, LiveSession>()
        val engine = PlaybackAccumulator { sessions[it.id] = it.copyForStore() }
        engine.playing(true)
        engine.sample(song, 0, T, 0)
        engine.sample(song, 4_000, T + 2_000, 2_000_000_000)
        engine.seek(10_000)
        engine.sample(song, 10_000, T + 3_000, 3_000_000_000)
        engine.sample(song, 11_000, T + 4_000, 4_000_000_000)
        engine.finish()
        check(sessions.values.single().durationMs == 3_000L)
    }
    test("observed origin survives a long initial pause, persistence and host deduplication") {
        isolate(root, "origin")
        val engine = PlaybackAccumulator { LiveStore.upsert(it) }
        engine.observeTrack(song, T)
        engine.playing(true)
        for (i in 0L..8L) engine.sample(song, i * 1_000, T + 20_000 + i * 1_000, i * 1_000_000_000)
        engine.finish(); LiveStore.save(); LiveStore.load()
        check(LiveStore.snapshot().single().observedOriginMs == T)
        val host = StatsData.empty().copy(tracks = mapOf(song.id to song),
            listens = listOf(ListenEntry("one", song.id, T, 8_000)))
        val data = LiveStore.merge(host)
        check(data.events.size == 1 && data.dailyMillis.values.sum() == 8_000L && data.playCount(song.id) == 1)
    }
    test("legacy metadata fills placeholders without overwriting newer valid local tags") {
        isolate(root, "legacy")
        val placeholder = song.copy(title = "未知歌曲", artist = "", album = "", albumArtist = "", durationMs = 0)
        val event = ListeningEvent("one", placeholder, listOf(ListeningSpan(T, 8_000)))
        val local = ListeningEvents.aggregate(StatsData.empty(), listOf(event))
        val legacy = LegacyTotals(tracks = mapOf(song.id to song), counts = mapOf(song.id to 1))
        val merged = BackupCodec.apply(local, BackupBundle(emptyList(), legacy))
        check(merged.tracks[song.id] == song && merged.playCount(song.id) == 1)
        val edited = song.copy(title = "New title")
        check(BackupCodec.apply(local.copy(tracks = mapOf(song.id to edited)), BackupBundle(emptyList(), legacy))
            .tracks[song.id] == edited)
    }
    test("diagnostic output follows the plugin directory, including Unicode paths") {
        isolate(root, "诊断目录")
        val file = com.jethuit.spw.listenstats.PluginLog.diagnostics("test snapshot; no track titles")!!
        check(file.toPath().parent == DataFiles.directory())
        check(Files.readString(file.toPath()).contains("DIAGNOSTICS test snapshot"))
    }
    test("single-pass merge preserves event union and detects changed imported files") {
        isolate(root, "single-pass")
        val imported = ListeningEvent("imported", song, listOf(ListeningSpan(T, 7_000)))
        val file = DataFiles.directory().resolve("imported.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, BackupCodec.encode(BackupBundle(listOf(imported))))
        val a = BackupService.mergeWithLive(StatsData.empty())
        check(a.dailyMillis.values.sum() == 7_000L && a.playCount(song.id) == 1)
        check(BackupService.mergeWithLive(StatsData.empty()).events == a.events)
        Files.writeString(file, BackupCodec.encode(BackupBundle(listOf(imported.copy(id = "changed-event",
            spans = listOf(ListeningSpan(T, 8_000)))))))
        check(BackupService.mergeWithLive(StatsData.empty()).dailyMillis.values.sum() == 8_000L)
        Files.writeString(file, "broken")
        check(BackupService.mergeWithLive(StatsData.empty()).error != null)
    }
    test("resuming the same track only needs one timing baseline and keeps transition isolation") {
        isolate(root, "resume")
        val media = PlaybackExtensionPoint.MediaItem("Known", "", "", "", "", song.id, 0, "", 0, 12_000, false, true)
        val playback = Proxy.newProxyInstance(WorkshopApi.Playback::class.java.classLoader,
            arrayOf(WorkshopApi.Playback::class.java)) { _, method, _ ->
            if (method.name == "getCurrentMediaItem") media else null
        }
        WorkshopApi.instance = Proxy.newProxyInstance(WorkshopApi::class.java.classLoader,
            arrayOf(WorkshopApi::class.java)) { _, method, _ -> if (method.name == "getPlayback") playback else null } as WorkshopApi
        PlaybackTracker.initialize()
        try {
            PlaybackTracker.onIsPlayingChanged(true); flushTracker()
            PlaybackTracker.onPositionUpdated(0); flushTracker()
            PlaybackTracker.onPositionUpdated(1_000); flushTracker()
            Thread.sleep(20); PlaybackTracker.onPositionUpdated(1_020); flushTracker()
            val before = LiveStore.snapshot().sumOf { it.durationMs }
            PlaybackTracker.onIsPlayingChanged(false); flushTracker()
            PlaybackTracker.onIsPlayingChanged(true); flushTracker()
            PlaybackTracker.onPositionUpdated(1_020); flushTracker()
            Thread.sleep(20); PlaybackTracker.onPositionUpdated(1_040); flushTracker()
            check(LiveStore.snapshot().sumOf { it.durationMs } > before)
        } finally { PlaybackTracker.shutdown() }
    }
    test("a longer host snapshot preserves precise cross-hour and cross-day locations through backup roundtrips") {
        isolate(root, "precise-distribution")
        val start = Instant.parse("2026-10-21T23:59:00Z").toEpochMilli()
        val exact = listOf(ListeningSpan(start + 1_000, 59_000), ListeningSpan(start + 3_660_000, 59_000))
        LiveStore.upsert(LiveSession("pause-play", song.id, start, 118_000, song.title, song.artist,
            song.album, song.albumArtist, 240_000, true, exact.toMutableList()))
        val host = StatsData.empty().copy(tracks = mapOf(song.id to song.copy(durationMs = 240_000)),
            listens = listOf(ListenEntry("paused", song.id, start, 120_000)))
        val merged = LiveStore.merge(host)
        val day = LocalDate.of(2026, 10, 22)
        check(merged.hoursOn(day)[1] == 59_000L)
        check(merged.millisOn(day.minusDays(1)) >= 59_000L && merged.hoursOn(day)[0] <= 2_000L)
        check(merged.dailyMillis.values.sum() == 120_000L && merged.events.single().observedSpans() == exact)
        var result = merged
        repeat(3) {
            val backup = BackupCodec.decode(BackupCodec.encode(BackupBundle(result.events)))
            result = LiveStore.merge(host, backup.events)
            check(result.events == merged.events && result.hoursOn(day)[1] == 59_000L)
        }
        val longer = LiveStore.merge(host.copy(listens = listOf(ListenEntry("paused", song.id, start, 180_000))), result.events)
        check(longer.dailyMillis.values.sum() == 180_000L && longer.hoursOn(day)[1] == 59_000L)
        val complete = ListeningEvents.aggregate(StatsData.empty(), listOf(
            ListeningEvent("precise", song, exact),
            ListeningEvent("host", song, listOf(ListeningSpan(start, 110_000)), aliases = setOf("precise"), approximate = true)
        ), ZoneId.of("UTC"))
        check(complete.events.single().spans == exact && !complete.events.single().approximate)
    }
    test("old imports cannot reintroduce a rejected host link, even with a host primary ID or after export") {
        isolate(root, "rejected-import")
        val short = song.copy(durationMs = 4_000)
        val badId = "host:${DataFiles.sourceId()}:old"
        val stale = LiveSession("new-play", short.id, T + 5_000, 3_000, short.title, short.artist,
            short.album, short.albumArtist, short.durationMs, true,
            mutableListOf(ListeningSpan(T + 5_000, 3_000)), hostId = badId, hostAliases = setOf(badId))
        val old = root.resolve("rejected-old-backup.json")
        Files.writeString(old, BackupCodec.encode(BackupBundle(listOf(stale.event()))))
        LiveStore.upsert(stale)
        val host = StatsData.empty().copy(tracks = mapOf(short.id to short), listens = listOf(
            ListenEntry("old", short.id, T, 2_200), ListenEntry("new", short.id, T + 5_000, 3_000)))
        val before = BackupService.mergeWithLive(host)
        check(before.dailyMillis.values.sum() == 5_200L && before.playCount(short.id) == 2)
        check(badId in LiveStore.snapshot().single().rejectedHostIds)
        BackupService.importFile(old)
        val after = BackupService.mergeWithLive(host)
        check(after.dailyMillis.values.sum() == 5_200L && after.playCount(short.id) == 2)
        val hostPrimary = stale.event().copy(id = badId, aliases = setOf("live:${stale.id}"))
        val applied = BackupCodec.apply(after, BackupBundle(listOf(hostPrimary)))
        check(applied.dailyMillis.values.sum() == 5_200L && applied.playCount(short.id) == 2)
        val exported = root.resolve("rejected-clean-export.json")
        BackupService.exportFile(exported, applied)
        val bundle = BackupCodec.read(exported)
        check(bundle.events.none { badId in it.aliases && "live:${stale.id}" in (it.aliases + it.id) })
        LiveStore.save(); LiveStore.load()
        check(LiveStore.merge(host, bundle.events).dailyMillis.values.sum() == 5_200L)
    }
    test("current track metadata outranks stale independent and duplicate backup sessions") {
        isolate(root, "metadata-priority")
        val fresh = song.copy(title = "Current", artist = "Correct", durationMs = 4_000)
        val stale = fresh.copy(title = "Old", artist = "Wrong", durationMs = 10_000)
        val local = ListeningEvent("current", fresh, listOf(ListeningSpan(T, 3_000)))
        val imported = ListeningEvent("old", stale, listOf(ListeningSpan(T - 10_000, 3_000)))
        val before = ListeningEvents.aggregate(StatsData.empty(), listOf(local))
        val after = BackupCodec.apply(before, BackupBundle(listOf(imported)))
        check(before.playCount(song.id) == 1 && after.playCount(song.id) == 2 && after.tracks[song.id] == fresh)
        val duplicate = imported.copy(id = "current", spans = listOf(ListeningSpan(T, 3_500)))
        val same = BackupCodec.apply(before, BackupBundle(listOf(duplicate)))
        check(same.playCount(song.id) == 1 && same.tracks[song.id] == fresh)
        val library = fresh.copy(title = "Current library title")
        val authoritative = ListeningEvents.aggregate(StatsData.empty().copy(tracks = mapOf(song.id to library)),
            listOf(imported, local))
        check(authoritative.tracks[song.id] == library && authoritative.playCount(song.id) == 2)
        val foreign = imported.copy(libraryId = java.util.UUID.randomUUID().toString())
        val scoped = BackupCodec.apply(before, BackupBundle(listOf(foreign)))
        check(scoped.tracks.size == 2 && scoped.playCounts.values.sum() == 1)
    }
    test("unknown initial playback is inferred only from plausible progress; explicit pause and seek remain authoritative") {
        val recorded = linkedMapOf<String, LiveSession>()
        val engine = PlaybackAccumulator { recorded[it.id] = it.copyForStore() }
        engine.sample(song, 1_000, T, 0)
        engine.sample(song, 1_000, T + 1_000, 1_000_000_000)
        check(recorded.isEmpty())
        engine.sample(song, 2_000, T + 2_000, 2_000_000_000)
        check(recorded.values.sumOf { it.durationMs } == 1_000L)
        engine.playing(false)
        engine.sample(song, 3_000, T + 3_000, 3_000_000_000)
        engine.sample(song, 4_000, T + 4_000, 4_000_000_000)
        check(recorded.values.sumOf { it.durationMs } == 1_000L)
        engine.playing(true); engine.seek(10_000)
        engine.sample(song, 10_000, T + 5_000, 5_000_000_000)
        check(recorded.values.sumOf { it.durationMs } == 1_000L)
        engine.sample(song, 11_000, T + 6_000, 6_000_000_000)
        check(recorded.values.sumOf { it.durationMs } == 2_000L)
    }
    test("enabling during playback records without a new playing-state callback") {
        isolate(root, "enable-playing")
        val media = PlaybackExtensionPoint.MediaItem("Known", "", "", "", "", song.id, 0, "", 0, 240_000, false, true)
        val playback = Proxy.newProxyInstance(WorkshopApi.Playback::class.java.classLoader,
            arrayOf(WorkshopApi.Playback::class.java)) { _, method, _ ->
            if (method.name == "getCurrentMediaItem") media else null
        }
        WorkshopApi.instance = Proxy.newProxyInstance(WorkshopApi::class.java.classLoader,
            arrayOf(WorkshopApi::class.java)) { _, method, _ -> if (method.name == "getPlayback") playback else null } as WorkshopApi
        PlaybackTracker.initialize()
        try {
            for (i in 0L..4L) {
                PlaybackTracker.onPositionUpdated(60_000 + i * 20); flushTracker(); Thread.sleep(20)
            }
            val recorded = LiveStore.snapshot().sumOf { it.durationMs }
            check(recorded > 0 && recorded < 1_000L) { "Mid-play enable did not record observed time: $recorded" }
            PlaybackTracker.onIsPlayingChanged(false); flushTracker()
            for (i in 5L..7L) {
                PlaybackTracker.onPositionUpdated(60_000 + i * 20); flushTracker(); Thread.sleep(20)
            }
            check(LiveStore.snapshot().sumOf { it.durationMs } == recorded)
        } finally { PlaybackTracker.shutdown() }
    }
    println("$cases review fix regression groups passed")
}
