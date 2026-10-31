@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

import com.jethuit.spw.listenstats.PluginState
import com.jethuit.spw.listenstats.data.*
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.UUID

private val track = TrackMeta("track", "同名歌曲", "歌手", "专辑", "", 10_000)
private val day = LocalDate.of(2026, 10, 18)
private val epoch = Instant.parse("2026-10-18T23:59:57Z").toEpochMilli()
private var cases = 0
private fun test(name: String, action: () -> Unit) { action(); cases++; println("PASS $name") }
private fun isolate(root: Path, name: String) {
    PluginState.pluginPath = root.resolve("$name/workshop/plugins/listenstats").toString()
    LiveStore.load()
}
private fun event(id: String, offset: Long, duration: Long = 6_000) = ListeningEvent(
    id, track, listOf(ListeningSpan(epoch + offset, duration)))

fun main(args: Array<String>) {
    val root = Path.of(args.single())
    isolate(root, "core")
    test("deleted source files are hidden while legacy unknown paths remain visible") {
        Files.createDirectories(root)
        val file = Files.createTempFile(root, "track", ".m4a")
        val located = track.copy(path = file.toString())
        check(TrackVisibility.isVisible(located))
        Files.delete(file)
        check(!TrackVisibility.isVisible(located))
        check(TrackVisibility.isVisible(track))
        val roundtrip = BackupCodec.decode(BackupCodec.encode(BackupBundle(listOf(event("path", 0).copy(
            track = located)))))
        check(roundtrip.events.single().track.path == located.path)
    }
    test("pause + cross-day + backward seek: actual intervals, one count") {
        val sessions = linkedMapOf<String, LiveSession>()
        val engine = PlaybackAccumulator { sessions[it.id] = it.copyForStore() }
        engine.playing(true)
        engine.sample(track, 0, epoch, 0)
        engine.sample(track, 1_000, epoch + 1_000, 1_000_000_000)
        engine.sample(track, 2_000, epoch + 2_000, 2_000_000_000)
        engine.playing(false)
        engine.sample(track, 2_000, epoch + 62_000, 62_000_000_000)
        engine.playing(true)
        engine.sample(track, 2_000, epoch + 63_000, 63_000_000_000)
        engine.sample(track, 3_000, epoch + 64_000, 64_000_000_000)
        engine.seek(1_000)
        engine.sample(track, 1_000, epoch + 65_000, 65_000_000_000)
        engine.sample(track, 2_000, epoch + 66_000, 66_000_000_000)
        engine.sample(track, 3_000, epoch + 67_000, 67_000_000_000)
        engine.finish()
        check(sessions.size == 1)
        val data = ListeningEvents.aggregate(StatsData.empty(), sessions.values.map { it.event() })
        check(data.millisOn(day) == 2_000L)
        check(data.millisOn(day.plusDays(1)) == 3_000L)
        check(data.playCount("track") == 1)
    }
    test("restart at zero and automatic loop create distinct plays") {
        val sessions = linkedMapOf<String, LiveSession>()
        val engine = PlaybackAccumulator { sessions[it.id] = it.copyForStore() }
        var time = 0L
        fun sample(position: Long) { engine.sample(track, position, epoch + time * 1_000, time * 1_000_000_000); time++ }
        engine.playing(true)
        for (i in 0..6) sample(i * 1_000L)
        engine.seek(0)
        for (i in 0..10) sample(i * 1_000L)
        for (i in 0..6) sample(i * 1_000L)
        engine.finish()
        check(sessions.size == 3)
        check(ListeningEvents.aggregate(StatsData.empty(), sessions.values.map { it.event() }).playCount("track") == 3)
    }
    test("sleep, seek jumps, stale callbacks and unknown duration never fabricate counts") {
        val sessions = linkedMapOf<String, LiveSession>()
        val engine = PlaybackAccumulator { sessions[it.id] = it.copyForStore() }
        engine.playing(true)
        engine.sample(track, 0, epoch, 0)
        engine.sample(track, 1_000, epoch + 1, 1_000_000) // 1 ms, not 1 second
        engine.sample(track, 2_000, epoch + 60_001, 60_001_000_000) // sleep
        engine.seek(8_000)
        engine.sample(track, 8_000, epoch + 61_001, 61_001_000_000)
        engine.sample(track, 9_000, epoch + 62_001, 62_001_000_000)
        engine.sample(track.copy(id = "unknown", durationMs = 0), 0, epoch + 63_001, 63_001_000_000)
        engine.sample(track.copy(id = "unknown", durationMs = 0), 1_000, epoch + 64_001, 64_001_000_000)
        engine.finish()
        val data = ListeningEvents.aggregate(StatsData.empty(), sessions.values.map { it.event() })
        check(data.dailyMillis.values.sum() == 2_001L)
        check(data.playCounts.isEmpty())
    }
    test("DST repeated hour and midnight preserve duration") {
        val daily = linkedMapOf<LocalDate, Long>()
        val hourly = linkedMapOf<LocalDate, LongArray>()
        TimeSplitter.split(Instant.parse("2026-11-01T05:30:00Z").toEpochMilli(), 3_600_000,
            ZoneId.of("America/New_York"), daily, hourly)
        check(daily.values.sum() == 3_600_000L)
        check(hourly.values.single()[1] == 3_600_000L)
    }
    test("live spans persist and host aliases deduplicate exported live snapshots") {
        isolate(root, "persistence")
        val session = LiveSession("live-id", "track", epoch, 6_000, track.title, track.artist,
            track.album, "", track.durationMs, true, mutableListOf(ListeningSpan(epoch, 6_000)))
        LiveStore.upsert(session)
        LiveStore.save(); LiveStore.load()
        check(LiveStore.snapshot().single().spans == session.spans)
        val before = LiveStore.merge(StatsData.empty())
        val host = StatsData.empty().copy(listens = listOf(ListenEntry("row-1", "track", epoch, 7_000)),
            tracks = mapOf("track" to track))
        val after = LiveStore.merge(host)
        val result = BackupCodec.apply(after, BackupBundle(before.events))
        check(result.dailyMillis.values.sum() == 7_000L)
        check(result.playCount("track") == 1)
    }
    test("v2 roundtrip, same-day additions, duplicate import, and cumulative imports") {
        isolate(root, "backup")
        val a = BackupBundle(listOf(event("a", 0)))
        val b = BackupBundle(listOf(event("b", 60_000)))
        val aFile = root.resolve("a.json"); val bFile = root.resolve("b.json")
        Files.writeString(aFile, BackupCodec.encode(a)); Files.writeString(bFile, BackupCodec.encode(b))
        BackupService.importFile(aFile); BackupService.importFile(aFile); BackupService.importFile(bFile)
        val data = BackupService.merge(StatsData.empty())
        check(data.events.size == 2 && data.dailyMillis.values.sum() == 12_000L)
        check(data.playCount("track") == 2)
        val exported = root.resolve("roundtrip.json")
        BackupService.exportFile(exported, data)
        check(BackupCodec.read(exported).events.size == 2)
        val previous = Files.readString(DataFiles.directory().resolve("imported.json"))
        Files.writeString(bFile, Files.readString(bFile).replace("\"durationMs\":10000", "\"durationMs\":20000"))
        check(runCatching { BackupService.importFile(bFile) }.isFailure)
        check(Files.readString(DataFiles.directory().resolve("imported.json")) == previous)
    }
    test("alias union is transitive; longer snapshot wins") {
        val events = listOf(event("a", 0), event("b", 0), event("c", 0, 7_000).copy(aliases = setOf("a", "b")))
        val merged = ListeningEvents.union(events)
        check(merged.size == 1 && merged.single().durationMs == 7_000L)
    }
    test("different library IDs never collapse identical track IDs") {
        val a = event("a", 0)
        val b = event("b", 60_000).copy(libraryId = "00000000-0000-0000-0000-000000000001")
        val data = ListeningEvents.aggregate(StatsData.empty(), listOf(a, b))
        check(data.tracks.size == 2 && data.playCounts.values.sum() == 2)
    }
    test("legacy compatibility, required checksum, negative hours and duplicate dates") {
        val hours = LongArray(24).also { it[1] = 6_000 }
        val legacy = BackupBundle(emptyList(), LegacyTotals(mapOf(day to hours), mapOf("track" to track), mapOf("track" to 1)))
        val encoded = BackupCodec.encode(legacy)
        val decoded = BackupCodec.decode(encoded)
        check(decoded.legacy.hours[day]!![1] == 6_000L)
        val records = "[{\"date\":\"2026-10-18\",\"ms\":6000,\"hours\":[${hours.joinToString(",")}]}]"
        val tracks = "[]"
        fun old(recordsText: String) = "{\"format\":\"spw-listen-stats-backup\",\"version\":1,\"records\":$recordsText,\"tracks\":$tracks,\"checksum\":\"${BackupCodec.hash(recordsText + "\n" + tracks)}\"}"
        check(BackupCodec.decode(old(records)).legacy.hours.size == 1)
        check(runCatching { BackupCodec.decode(old(records).replace("checksum", "missing")) }.isFailure)
        val bad = records.replace("[0,6000,", "[-1000,7000,")
        check(runCatching { BackupCodec.decode(old(bad)) }.isFailure)
        val duplicate = "[" + records.drop(1).dropLast(1) + "," + records.drop(1).dropLast(1) + "]"
        check(runCatching { BackupCodec.decode(old(duplicate)) }.isFailure)
    }
    test("late host query cannot recreate session after shutdown or re-enable") {
        isolate(root, "lifecycle")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val playback = Proxy.newProxyInstance(WorkshopApi.Playback::class.java.classLoader,
            arrayOf(WorkshopApi.Playback::class.java)) { _, method, _ ->
            if (method.name == "getCurrentMediaItem") {
                entered.countDown()
                while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                finished.countDown()
            }
            null
        }
        WorkshopApi.instance = Proxy.newProxyInstance(WorkshopApi::class.java.classLoader,
            arrayOf(WorkshopApi::class.java)) { _, method, _ -> if (method.name == "getPlayback") playback else null } as WorkshopApi
        PlaybackTracker.initialize(); PlaybackTracker.onIsPlayingChanged(true); PlaybackTracker.onPositionUpdated(0)
        check(entered.await(3, TimeUnit.SECONDS))
        PlaybackTracker.shutdown(); PlaybackTracker.initialize()
        release.countDown(); check(finished.await(3, TimeUnit.SECONDS))
        PlaybackTracker.shutdown()
        check(LiveStore.snapshot().isEmpty())
        PlaybackTracker.onPositionUpdated(1_000) // no rejected-executor exception after stop
    }
    test("restart resumes an unfinished session instead of double-counting") {
        isolate(root, "resume")
        val sessions = linkedMapOf<String, LiveSession>()
        var wall = epoch
        var nano = 0L
        val first = PlaybackAccumulator({ sessions[it.id] = it.copyForStore() })
        first.playing(true)
        for (position in 0L..6_000L step 1_000L) {
            first.sample(track, position, wall, nano)
            wall += 1_000; nano += 1_000_000_000
        }
        check(sessions.size == 1) { "first run should persist one open session" }
        val second = PlaybackAccumulator({ sessions[it.id] = it.copyForStore() }) { id ->
            sessions.values.firstOrNull { it.trackId == id.id && !it.finalized }
        }
        second.playing(true)
        for (position in 7_000L..10_000L step 1_000L) {
            second.sample(track, position, wall, nano)
            wall += 1_000; nano += 1_000_000_000
        }
        second.finish()
        check(sessions.size == 1) { "resume must reuse the open session" }
        val data = ListeningEvents.aggregate(StatsData.empty(), sessions.values.map { it.event() })
        check(data.dailyMillis.values.sum() == 9_000L) { "resumed duration ${data.dailyMillis}" }
        check(data.playCount("track") == 1)
    }
    test("unfinished live sessions survive load but finalized ones do not resume") {
        isolate(root, "resume-load")
        val open = LiveSession("open", "track", epoch, 5_000, track.title, track.artist,
            track.album, "", track.durationMs, false, mutableListOf(ListeningSpan(epoch, 5_000)))
        LiveStore.upsert(open); LiveStore.save(); LiveStore.load()
        check(LiveStore.findOpen("track", epoch + 60_000) != null)
        check(LiveStore.findOpen("track", epoch + 6 * 60 * 60 * 1000L + 1) == null)
        LiveStore.upsert(open.copy(finalized = true)); LiveStore.save(); LiveStore.load()
        check(LiveStore.findOpen("track", epoch + 60_000) == null)
    }
    test("recovery only considers the first track and closes unrelated open sessions") {
        isolate(root, "resume-switch")
        val old = LiveSession("old-a", "track", epoch, 6_000, track.title, track.artist,
            track.album, "", track.durationMs, false, mutableListOf(ListeningSpan(epoch, 6_000)))
        LiveStore.upsert(old)
        val other = track.copy(id = "other")
        var calls = 0
        val engine = PlaybackAccumulator({ LiveStore.upsert(it) }) {
            calls++
            LiveStore.recoverOpen(it.id, epoch + 60_000)
        }
        engine.playing(true)
        var time = 60_000L
        for (song in listOf(other, track)) {
            for (position in 0L..6_000L step 1_000L) {
                engine.sample(song, position, epoch + time, time * 1_000_000)
                time += 1_000
            }
        }
        engine.finish()
        check(calls == 1)
        val result = ListeningEvents.aggregate(StatsData.empty(), LiveStore.snapshot().map { it.event() })
        check(result.playCount("track") == 2 && result.playCount("other") == 1)
        check(result.dailyMillis.values.sum() == 18_000L)
        LiveStore.save(); LiveStore.load()
        check(LiveStore.findOpen("track", epoch + time) == null)
    }
    test("explicit restart before first sample does not recover a crashed play") {
        var calls = 0
        val engine = PlaybackAccumulator({}) { calls++; null }
        engine.playing(true)
        engine.seek(0)
        engine.sample(track, 0, epoch, 0)
        check(calls == 0)
    }
    test("clock corrections preserve elapsed time and remain exportable") {
        isolate(root, "clock")
        val saved = linkedMapOf<String, LiveSession>()
        val engine = PlaybackAccumulator { saved[it.id] = it.copyForStore() }
        engine.playing(true)
        val wallOffsets = listOf(0L, 1_000L, 1_500L, -8_000L, -7_000L, 30_000L)
        wallOffsets.forEachIndexed { index, offset ->
            engine.sample(track, index * 1_000L, epoch + offset, index * 1_000_000_000L)
        }
        engine.finish()
        val session = saved.values.single()
        check(session.durationMs == 5_000L && session.approximate)
        check(session.spans.zipWithNext().all { (a, b) -> a.startMs + a.durationMs <= b.startMs })
        val roundtrip = BackupCodec.decode(BackupCodec.encode(BackupBundle(listOf(session.event()))))
        check(roundtrip.events.single().durationMs == 5_000L)
        check(ListeningEvents.aggregate(StatsData.empty(), roundtrip.events).playCount("track") == 1)
    }
    test("old overlapping live spans are repaired on load without losing listening time") {
        isolate(root, "clock-migration")
        val old = LiveSession("overlap", "track", epoch, 2_000, track.title, track.artist,
            track.album, "", track.durationMs, true,
            mutableListOf(ListeningSpan(epoch, 1_000), ListeningSpan(epoch + 500, 1_000)))
        LiveStore.upsert(old); LiveStore.save(); LiveStore.load()
        val repaired = LiveStore.snapshot().single()
        check(repaired.approximate && repaired.durationMs == 2_000L)
        check(repaired.spans[1].startMs == epoch + 1_000)
        val decoded = BackupCodec.decode(BackupCodec.encode(BackupBundle(listOf(repaired.event()))))
        check(decoded.events.single().durationMs == 2_000L)
        LiveStore.save(); LiveStore.load()
        check(LiveStore.snapshot().single().spans == repaired.spans)
    }
    test("ranking search retains full-list rank before pagination") {
        val tracks = (1..80).associate { i ->
            "$i" to track.copy(id = "$i", title = "song $i", artist = if (i >= 50) "match" else "other")
        }
        val data = StatsData.empty().copy(tracks = tracks, playCounts = (1..80).associate { "$it" to 81 - it })
        val filtered = data.rankedTracks(" MATCH ")
        check(filtered.first().position == 50 && filtered.last().position == 80)
        check(filtered.drop(25).first().position == 75)
        check(data.rankedTracks("song 80").single().position == 80)
        check(data.rankedTracks("").size == 80 && data.rankedTracks("missing").isEmpty())
    }
    test("corrupt source id regenerates instead of failing every event") {
        isolate(root, "source-id")
        val file = DataFiles.directory().resolve("source-id.txt")
        Files.createDirectories(file.parent)
        Files.writeString(file, "not-a-uuid")
        val id = DataFiles.sourceId()
        check(UUID.fromString(id).toString() == id)
        check(Files.readString(file).trim() == id)
    }
    println("$cases data regression cases passed")
}
