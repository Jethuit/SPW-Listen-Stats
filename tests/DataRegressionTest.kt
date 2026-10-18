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
    println("$cases data regression cases passed")
}
