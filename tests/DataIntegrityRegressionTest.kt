import com.jethuit.spw.listenstats.PluginState
import com.jethuit.spw.listenstats.data.*
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.WorkshopApi
import kotlinx.serialization.json.*
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val START = 1_792_300_000_000L
private val song = TrackMeta("song", "Known title", "Known artist", "Known album", "Album artist", 240_000)
private var failed = 0
private var cases = 0
private fun test(name: String, action: () -> Unit) {
    cases++
    try { action(); println("PASS $name") }
    catch (failure: Throwable) { failed++; println("FAIL $name: $failure") }
}
private fun isolate(root: Path, name: String) {
    PluginState.pluginPath = root.resolve("$name/workshop/plugins/listenstats").toString()
    LiveStore.load()
}
private fun piece(id: String, start: Long, duration: Long, estimate: Long = START) =
    LiveSession(id, song.id, estimate, duration, song.title, song.artist, song.album,
        song.albumArtist, song.durationMs, true, mutableListOf(ListeningSpan(start, duration)))
private fun host(duration: Long, metadata: Boolean = true) = StatsData.empty().copy(
    listens = listOf(ListenEntry("row", song.id, START, duration)),
    tracks = if (metadata) mapOf(song.id to song) else emptyMap())
private fun total(data: StatsData) = data.dailyMillis.values.sum()
private fun flushTracker() {
    val field = PlaybackTracker::class.java.getDeclaredField("executor").apply { isAccessible = true }
    (field.get(PlaybackTracker) as ExecutorService).submit {}.get(4, TimeUnit.SECONDS)
}

fun main(args: Array<String>) {
    val root = Path.of(args.single())
    test("lifecycle fragments deduplicate to one 240-second host play, including old exports") {
        isolate(root, "fragments")
        LiveStore.upsert(piece("first", START, 60_000))
        LiveStore.upsert(piece("second", START + 80_000, 160_000, START + 100))
        val oldExport = BackupBundle(LiveStore.merge(StatsData.empty()).events)
        val result = BackupCodec.apply(LiveStore.merge(host(240_000)), oldExport)
        check(total(result) == 240_000L && result.playCount(song.id) == 1) {
            "duration=${total(result)}, count=${result.playCount(song.id)}"
        }
        LiveStore.load()
        check(total(LiveStore.merge(host(240_000))) == 240_000L)
    }
    test("disjoint fragments combine before comparison with a shorter host snapshot") {
        isolate(root, "short-host")
        LiveStore.upsert(piece("one", START, 60_000))
        LiveStore.upsert(piece("two", START + 80_000, 160_000))
        val result = LiveStore.merge(host(210_000))
        check(total(result) == 220_000L && result.playCount(song.id) == 1)
        check(BackupCodec.decode(BackupCodec.encode(BackupBundle(result.events))).events.single().durationMs == 220_000L)
    }
    test("an adjacent restart is not folded into the preceding host play") {
        isolate(root, "replay")
        val short = song.copy(durationMs = 2_000)
        LiveStore.upsert(piece("one", START, 1_000).copy(trackDurationMs = 2_000))
        LiveStore.upsert(piece("two", START + 1_000, 1_000, START + 1_000).copy(trackDurationMs = 2_000))
        val firstHost = StatsData.empty().copy(
            listens = listOf(ListenEntry("one", song.id, START, 1_000)), tracks = mapOf(song.id to short))
        val pending = LiveStore.merge(firstHost)
        check(total(pending) == 2_000L && pending.playCount(song.id) == 2)
        val both = LiveStore.merge(firstHost.copy(listens = firstHost.listens +
            ListenEntry("two", song.id, START + 1_000, 1_000)))
        check(total(both) == 2_000L && both.playCount(song.id) == 2)
    }
    test("longer unknown host metadata cannot erase title, duration or count") {
        isolate(root, "metadata")
        LiveStore.upsert(piece("known", START, 150_000))
        val known = LiveStore.merge(StatsData.empty()).events.single()
        val merged = LiveStore.merge(host(151_000, metadata = false))
        check(total(merged) == 151_000L && merged.playCount(song.id) == 1)
        check(merged.tracks.getValue(song.id) == song)
        val unknown = ListeningEvent("host:${DataFiles.sourceId()}:row",
            song.copy(title = "未知歌曲", artist = "", album = "", albumArtist = "", durationMs = 0),
            listOf(ListeningSpan(START, 151_000)))
        val linked = known.copy(aliases = setOf(unknown.id))
        for (events in listOf(listOf(linked, unknown), listOf(unknown, linked))) {
            val data = ListeningEvents.aggregate(StatsData.empty(), events)
            check(data.playCount(song.id) == 1 && data.tracks.getValue(song.id) == song)
        }
    }
    test("known metadata applies to another local play but never to a foreign library") {
        isolate(root, "metadata-scope")
        val a = piece("known", START, 150_000).event()
        val b = a.copy(id = "unknown", track = song.copy(title = "未知歌曲", durationMs = 0),
            spans = listOf(ListeningSpan(START + 300_000, 151_000)))
        check(ListeningEvents.aggregate(StatsData.empty(), listOf(a, b)).playCount(song.id) == 2)
        val foreign = b.copy(libraryId = UUID.randomUUID().toString())
        val data = ListeningEvents.aggregate(StatsData.empty(), listOf(a, foreign))
        check(data.playCounts.values.sum() == 1 && data.tracks.size == 2)
    }
    test("legacy local aliases recover a corrupt source identity and keep exports deduplicated") {
        isolate(root, "source-legacy")
        val old = DataFiles.sourceId()
        LiveStore.upsert(piece("known", START, 150_000))
        val before = LiveStore.merge(host(150_000))
        val file = DataFiles.directory().resolve("live-sessions.json")
        val json = Json.parseToJsonElement(Files.readString(file)).jsonObject
        Files.writeString(file, JsonObject(json.filterKeys { it != "sourceId" }).toString())
        Files.deleteIfExists(DataFiles.directory().resolve("source-id.backup.txt"))
        Files.writeString(DataFiles.directory().resolve("source-id.txt"), "broken")
        LiveStore.load()
        check(DataFiles.sourceId() == old)
        val after = BackupCodec.apply(LiveStore.merge(host(150_000)), BackupBundle(before.events))
        check(total(after) == 150_000L && after.playCount(song.id) == 1)
    }
    test("redundant identity survives corruption without live rows; imports never define local identity") {
        isolate(root, "source-backup")
        val old = DataFiles.sourceId()
        Files.writeString(DataFiles.directory().resolve("source-id.txt"), "broken")
        check(DataFiles.sourceId() == old)
        val foreign = UUID.randomUUID().toString()
        val foreignExport = BackupCodec.encode(BackupBundle(listOf(ListeningEvent("foreign", song,
            listOf(ListeningSpan(START, 150_000)), libraryId = foreign))))
        isolate(root, "source-foreign")
        val dir = DataFiles.directory()
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("source-id.txt"), "broken")
        Files.writeString(dir.resolve("imported.json"), foreignExport)
        check(DataFiles.sourceId() != foreign)
    }
    test("already-regenerated IDs migrate aliases and retain an older, longer export") {
        isolate(root, "source-migration")
        val oldSource = DataFiles.sourceId()
        LiveStore.upsert(piece("known", START, 150_000))
        LiveStore.merge(host(150_000))
        val stale = LiveStore.snapshot().single()
        val exported = stale.event().copy(spans = listOf(ListeningSpan(START, 160_000)))
        val replacement = UUID.randomUUID().toString()
        Files.writeString(DataFiles.directory().resolve("source-id.txt"), replacement)
        val result = BackupCodec.apply(LiveStore.merge(host(151_000)), BackupBundle(listOf(exported)))
        check(total(result) == 160_000L && result.playCount(song.id) == 1)
        LiveStore.upsert(stale) // The running accumulator may retain the pre-repair alias.
        LiveStore.save(); LiveStore.load()
        val fixed = LiveStore.snapshot().single()
        check(fixed.hostId == "host:$replacement:row")
        check("host:$oldSource:row" in fixed.event().aliases)
    }
    test("blocked lookup and an old queued position cannot assign A's 239 seconds to B") {
        isolate(root, "queued-position")
        fun media(id: String) = PlaybackExtensionPoint.MediaItem("Song $id", "", "", "", "",
            id, 0, "", 0, 600_000, false, true)
        val current = AtomicReference(media("a"))
        val block = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val playback = Proxy.newProxyInstance(WorkshopApi.Playback::class.java.classLoader,
            arrayOf(WorkshopApi.Playback::class.java)) { _, method, _ ->
            if (method.name == "getCurrentMediaItem") {
                val captured = current.get()
                if (block.getAndSet(false)) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
                captured
            } else null
        }
        WorkshopApi.instance = Proxy.newProxyInstance(WorkshopApi::class.java.classLoader,
            arrayOf(WorkshopApi::class.java)) { _, method, _ -> if (method.name == "getPlayback") playback else null } as WorkshopApi
        PlaybackTracker.initialize()
        try {
            PlaybackTracker.onIsPlayingChanged(true); flushTracker()
            PlaybackTracker.onPositionUpdated(0); flushTracker()
            block.set(true)
            PlaybackTracker.onPositionUpdated(238_000)
            check(entered.await(2, TimeUnit.SECONDS))
            PlaybackTracker.onPositionUpdated(239_000)
            val switchWall = System.currentTimeMillis()
            current.set(media("b"))
            release.countDown(); flushTracker()
            PlaybackTracker.onPositionUpdated(0); flushTracker()
            Thread.sleep(20)
            PlaybackTracker.onPositionUpdated(1_000); flushTracker()
            val b = LiveStore.snapshot().single { it.trackId == "b" }
            check(b.startTimeMs >= switchWall - 5_000) { "B start shifted backwards by ${switchWall - b.startTimeMs}ms" }
            val hostB = StatsData.empty().copy(listens = listOf(ListenEntry("b-row", "b", b.startTimeMs, 400_000)),
                tracks = mapOf("b" to song.copy(id = "b", durationMs = 600_000)))
            check(LiveStore.merge(hostB).events.count { it.track.id == "b" } == 1)
        } finally { release.countDown(); PlaybackTracker.shutdown() }
    }
    test("one observed B between two qualified A plays ends the old session before rejecting B's position") {
        isolate(root, "single-observation-switch")
        fun media(id: String) = PlaybackExtensionPoint.MediaItem("Song $id", "", "", "", "",
            id, 0, "", 0, 4_000, false, true)
        val current = AtomicReference(media("a"))
        val playback = Proxy.newProxyInstance(WorkshopApi.Playback::class.java.classLoader,
            arrayOf(WorkshopApi.Playback::class.java)) { _, method, _ ->
            if (method.name == "getCurrentMediaItem") current.get() else null
        }
        WorkshopApi.instance = Proxy.newProxyInstance(WorkshopApi::class.java.classLoader,
            arrayOf(WorkshopApi::class.java)) { _, method, _ -> if (method.name == "getPlayback") playback else null } as WorkshopApi
        PlaybackTracker.initialize()
        fun position(value: Long) { PlaybackTracker.onPositionUpdated(value); flushTracker() }
        fun qualifiedPlay() {
            position(0)
            Thread.sleep(1_200); position(1_200)
            Thread.sleep(1_200); position(2_400)
        }
        try {
            PlaybackTracker.onIsPlayingChanged(true); flushTracker()
            position(0) // Initial metadata observation, no position accepted yet.
            qualifiedPlay()
            val first = LiveStore.snapshot().single()
            current.set(media("b")); position(0) // B is observed exactly once, never sampled.
            val firstClosed = LiveStore.snapshot().single().finalized
            current.set(media("a")); position(0)
            val secondStart = System.currentTimeMillis()
            qualifiedPlay()
            val live = LiveStore.merge(StatsData.empty())
            val expected = total(live)
            val bothHost = StatsData.empty().copy(
                tracks = mapOf("a" to song.copy(id = "a", durationMs = 4_000)),
                listens = listOf(ListenEntry("a-first", "a", first.startTimeMs, first.durationMs),
                    ListenEntry("a-second", "a", secondStart, expected - first.durationMs)))
            val synced = LiveStore.merge(bothHost)
            check(firstClosed && live.playCount("a") == 2 && synced.playCount("a") == 2 &&
                total(synced) == expected && live.events.none { it.track.id == "b" }) {
                "closed=$firstClosed; live=${live.playCount("a")}/${expected}ms; " +
                    "synced=${synced.playCount("a")}/${total(synced)}ms"
            }
        } finally { PlaybackTracker.shutdown() }
    }
    test("current local identity survives a live-primary alias, longer old imports, roundtrip and another play") {
        isolate(root, "live-primary-migration")
        val oldSource = DataFiles.sourceId()
        LiveStore.upsert(piece("known", START, 150_000))
        val old = LiveStore.merge(host(140_000)).events.single()
            .copy(spans = listOf(ListeningSpan(START, 160_000)))
        val oldBackup = BackupCodec.decode(BackupCodec.encode(BackupBundle(listOf(old))))
        val replacement = UUID.randomUUID().toString()
        Files.writeString(DataFiles.directory().resolve("source-id.txt"), replacement)
        val migrated = LiveStore.merge(host(140_000))
        val primary = migrated.events.single()
        check(primary.id.startsWith("live:") && primary.libraryId == replacement)
        check("host:$replacement:row" in primary.aliases && migrated.playCount(song.id) == 1)
        val imported = BackupCodec.apply(migrated, oldBackup)
        val roundtrip = BackupCodec.decode(BackupCodec.encode(BackupBundle(imported.events)))
        val reimported = BackupCodec.apply(imported, roundtrip)
        val next = ListeningEvent("live:next", song,
            listOf(ListeningSpan(START + 300_000, 150_000)), libraryId = replacement)
        val again = ListeningEvents.aggregate(StatsData.empty(), reimported.events + next)
        // A foreign event alone is not promoted just because it mentions a local-looking alias.
        val unverified = old.copy(aliases = old.aliases + "host:$replacement:row")
        check(ListeningEvents.union(listOf(unverified)).single().libraryId == oldSource)
        check(imported.events.single().libraryId == replacement && imported.playCount(song.id) == 1 &&
            total(imported) == 160_000L && reimported.events.single().libraryId == replacement &&
            again.playCount(song.id) == 2 && again.tracks.size == 1) {
            "library=${imported.events.single().libraryId}; imported count=${imported.playCount(song.id)}; " +
                "next count=${again.playCount(song.id)}, rows=${again.tracks.size}"
        }
    }
    check(failed == 0) { "$failed/$cases integrity regressions failed" }
    println("$cases integrity regression groups passed")
}
