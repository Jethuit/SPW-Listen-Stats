@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

import com.jethuit.spw.listenstats.PluginState
import com.jethuit.spw.listenstats.data.BackupBundle
import com.jethuit.spw.listenstats.data.BackupCodec
import com.jethuit.spw.listenstats.data.LiveSession
import com.jethuit.spw.listenstats.data.LiveStore
import com.jethuit.spw.listenstats.data.ListeningEvent
import com.jethuit.spw.listenstats.data.ListeningSpan
import com.jethuit.spw.listenstats.data.StatsStore
import com.jethuit.spw.listenstats.data.TrackMeta
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant

private fun exec(connection: java.sql.Connection, sql: String) {
    connection.createStatement().use { it.executeUpdate(sql) }
}

private fun allowLibraryRead() {
    val manager = Proxy.newProxyInstance(
        WorkshopApi.Manager::class.java.classLoader,
        arrayOf(WorkshopApi.Manager::class.java)
    ) { _, method, _ -> if (method.name == "isPermissionGranted") true else null }
    WorkshopApi.instance = Proxy.newProxyInstance(
        WorkshopApi::class.java.classLoader,
        arrayOf(WorkshopApi::class.java)
    ) { _, method, _ -> if (method.name == "getManager") manager else null } as WorkshopApi
}

fun main(args: Array<String>) {
    Class.forName("org.sqlite.JDBC")
    val root = Path.of(args.single()).resolve("snapshot-cache")
    Files.createDirectories(root)
    val pluginPath = root.resolve("workshop/plugins/listenstats")
    val database = root.resolve("spw.db")
    val start = Instant.parse("2026-10-31T00:00:00Z").toEpochMilli()
    val hostTrack = TrackMeta("host-track", "宿主歌曲", "歌手", "专辑", "", 10_000)
    DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
        exec(connection, "CREATE TABLE Track (id TEXT PRIMARY KEY, title TEXT, artist TEXT, album TEXT, albumArtist TEXT, duration INTEGER)")
        exec(connection, "CREATE TABLE Listen (id TEXT PRIMARY KEY, trackId TEXT, startTime INTEGER, duration INTEGER)")
        exec(connection, "INSERT INTO Track VALUES ('host-track', '宿主歌曲', '歌手', '专辑', '', 10000)")
        exec(connection, "INSERT INTO Listen VALUES ('host-row', 'host-track', ${start}, 6000)")
    }

    PluginState.pluginPath = pluginPath.toString()
    PluginState.pluginId = "com.jethuit.spw.listenstats"
    allowLibraryRead()
    LiveStore.load()
    StatsStore.initialize(pluginPath.toString())

    val first = StatsStore.load()
    val unchanged = StatsStore.load()
    check(unchanged === first) { "unchanged inputs should reuse the merged snapshot" }
    check(unchanged.loadedAt == first.loadedAt) { "reused snapshot should keep loadedAt" }

    val media = Files.createTempFile(root, "live-track", ".m4a")
    val liveTrack = TrackMeta("live-track", "实时歌曲", "歌手", "专辑", "", 10_000,
        media.toString())
    LiveStore.upsert(LiveSession("live-session", liveTrack.id, start + 20_000, 6_000,
        liveTrack.title, liveTrack.artist, liveTrack.album, liveTrack.albumArtist,
        liveTrack.durationMs, finalized = true,
        spans = mutableListOf(ListeningSpan(start + 20_000, 6_000)), path = media.toString()))
    val afterLive = StatsStore.load()
    check(afterLive !== first && afterLive.events.any { it.track.id == liveTrack.id }) {
        "live input changes should invalidate the merged snapshot"
    }
    check(StatsStore.load() === afterLive) { "unchanged live input should reuse the new snapshot" }
    Files.delete(media)
    val afterHidden = StatsStore.load()
    check(afterHidden !== afterLive && afterHidden.events.none { it.track.id == liveTrack.id }) {
        "track visibility changes should invalidate the merged snapshot"
    }

    val importedTrack = TrackMeta("imported-track", "导入歌曲", "歌手", "专辑", "", 10_000)
    val importedFile = com.jethuit.spw.listenstats.data.DataFiles.directory().resolve("imported.json")
    Files.createDirectories(importedFile.parent)
    Files.writeString(importedFile, BackupCodec.encode(BackupBundle(listOf(
        ListeningEvent("imported-event", importedTrack,
            listOf(ListeningSpan(start + 40_000, 6_000)))
    ))))
    val afterImport = StatsStore.load()
    check(afterImport !== afterLive && afterImport.events.any { it.track.id == importedTrack.id }) {
        "imported backup changes should invalidate the merged snapshot"
    }

    DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
        exec(connection, "INSERT INTO Listen VALUES ('host-row-2', 'host-track', ${start + 60_000}, 7000)")
    }
    val afterHost = StatsStore.load()
    check(afterHost !== afterImport && afterHost.listens.size == 2) {
        "host database changes should invalidate the merged snapshot"
    }

    println("PASS merged snapshot reuses unchanged inputs and invalidates on live, backup, and host changes")
}
