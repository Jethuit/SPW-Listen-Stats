@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

import com.jethuit.spw.listenstats.PluginState
import com.jethuit.spw.listenstats.data.*
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
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
    println("$cases review fix regression groups passed")
}
