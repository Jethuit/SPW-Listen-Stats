package com.jethuit.spw.listenstats.data

import com.jethuit.spw.listenstats.PluginLog
import com.jethuit.spw.listenstats.PluginState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.abs
import kotlin.math.max

data class LiveSession(
    val id: String,
    val trackId: String,
    val startTimeMs: Long,
    var durationMs: Long,
    var title: String,
    var artist: String,
    var album: String,
    var albumArtist: String,
    var trackDurationMs: Long,
    var finalized: Boolean
) {
    fun copyForStore(): LiveSession = LiveSession(
        id, trackId, startTimeMs, durationMs, title, artist, album, albumArtist, trackDurationMs, finalized
    )

    fun copyForDisplay(): LiveSession = copyForStore()
}

/**
 * Durable sessions written from playback events. Host Listen rows are still
 * authoritative when they exist; live sessions only fill the gap before the
 * host writes its row, and matching host rows suppress the live duplicate.
 */
object LiveStore {
    private const val FORMAT_VERSION = 1
    private const val MATCH_TOLERANCE_MS = 10_000L

    private val lock = ReentrantLock()
    private val sessions = LinkedHashMap<String, LiveSession>()

    fun load() {
        val file = dataFile()
        if (!Files.isRegularFile(file)) return
        try {
            val root = Json.parseToJsonElement(Files.readString(file, StandardCharsets.UTF_8)).jsonObject
            if (root["version"]?.jsonPrimitive?.long != FORMAT_VERSION.toLong()) {
                PluginLog.info("ignoring unknown live session version")
                return
            }
            val loaded = LinkedHashMap<String, LiveSession>()
            root["sessions"]?.jsonArray?.forEach { element ->
                val item = element.jsonObject
                val id = item["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val trackId = item["trackId"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                loaded[id] = LiveSession(
                    id = id,
                    trackId = trackId,
                    startTimeMs = item["startTime"]?.jsonPrimitive?.long ?: 0L,
                    durationMs = item["duration"]?.jsonPrimitive?.long ?: 0L,
                    title = item["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    artist = item["artist"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    album = item["album"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    albumArtist = item["albumArtist"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    trackDurationMs = item["trackDuration"]?.jsonPrimitive?.long ?: 0L,
                    finalized = item["finalized"]?.jsonPrimitive?.booleanOrNull ?: false
                )
            }
            withLock {
                sessions.clear()
                sessions.putAll(loaded)
            }
            PluginLog.info("loaded ${loaded.size} live sessions")
        } catch (failure: Throwable) {
            PluginLog.error("loading live sessions failed", failure)
        }
    }

    fun save() {
        val snapshot = withLock { sessions.values.map { it.copyForStore() } }
        if (snapshot.isEmpty()) return
        val root = buildJsonObject {
            put("version", JsonPrimitive(FORMAT_VERSION))
            put(
                "sessions",
                buildJsonArray {
                    snapshot.forEach { session ->
                        add(
                            buildJsonObject {
                                put("id", session.id)
                                put("trackId", session.trackId)
                                put("startTime", JsonPrimitive(session.startTimeMs))
                                put("duration", JsonPrimitive(session.durationMs))
                                put("title", session.title)
                                put("artist", session.artist)
                                put("album", session.album)
                                put("albumArtist", session.albumArtist)
                                put("trackDuration", JsonPrimitive(session.trackDurationMs))
                                put("finalized", JsonPrimitive(session.finalized))
                            }
                        )
                    }
                }
            )
        }
        try {
            val target = dataFile()
            Files.createDirectories(target.parent)
            val temporary = target.resolveSibling(target.fileName.toString() + ".tmp")
            Files.writeString(temporary, root.toString(), StandardCharsets.UTF_8)
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (ignored: Throwable) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (failure: Throwable) {
            PluginLog.error("saving live sessions failed", failure)
        }
    }

    fun upsert(session: LiveSession) {
        withLock { sessions[session.id] = session.copyForStore() }
    }

    fun remove(id: String) {
        withLock { sessions.remove(id) }
    }

    fun snapshot(): List<LiveSession> = withLock { sessions.values.map { it.copyForDisplay() } }

    fun findResumable(trackId: String, startTimeMs: Long, toleranceMs: Long = 15_000L): LiveSession? {
        if (trackId.isEmpty()) return null
        return withLock {
            sessions.values
                .filter {
                    it.trackId == trackId &&
                        abs(it.startTimeMs - startTimeMs) <= toleranceMs
                }
                .maxByOrNull { it.durationMs }
                ?.copyForDisplay()
        }
    }

    fun merge(host: StatsData): StatsData {
        val live = snapshot().filter { it.durationMs > 0 && it.trackId.isNotEmpty() }
        if (live.isEmpty()) return host
        val hostStarts = HashMap<String, MutableList<Long>>()
        host.listens.forEach { listen ->
            hostStarts.getOrPut(listen.trackId) { ArrayList() }.add(listen.startTimeMs)
        }
        val unmatched = live.filter { session ->
            val starts = hostStarts[session.trackId]
            starts == null || starts.none { abs(it - session.startTimeMs) <= MATCH_TOLERANCE_MS }
        }
        if (unmatched.isEmpty()) return host

        val zone = ZoneId.systemDefault()
        val daily = host.dailyMillis.toMutableMap()
        val hourly = host.hourlyMillis.mapValues { it.value.copyOf() }.toMutableMap()
        val tracks = host.tracks.toMutableMap()
        val counts = host.playCounts.toMutableMap()
        for (session in unmatched) {
            TimeSplitter.split(session.startTimeMs, session.durationMs, zone, daily, hourly)
            val existing = tracks[session.trackId]
            val effectiveDuration = when {
                existing != null && existing.durationMs > 0 -> existing.durationMs
                session.trackDurationMs > 0 -> session.trackDurationMs
                else -> 0L
            }
            if (existing == null) {
                tracks[session.trackId] = TrackMeta(
                    id = session.trackId,
                    title = session.title,
                    artist = session.artist,
                    album = session.album,
                    albumArtist = session.albumArtist,
                    durationMs = session.trackDurationMs
                )
            } else if (existing.durationMs <= 0 && session.trackDurationMs > 0) {
                tracks[session.trackId] = existing.copy(durationMs = session.trackDurationMs)
            }
            if (effectiveDuration > 0 && session.durationMs >= effectiveDuration / 2.0) {
                counts[session.trackId] = (counts[session.trackId] ?: 0) + 1
            }
        }
        return host.copy(
            dailyMillis = daily,
            hourlyMillis = hourly,
            tracks = tracks,
            playCounts = counts,
            loadedAt = System.currentTimeMillis()
        )
    }

    private fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    private fun dataFile(): Path {
        val pluginPath = PluginState.pluginPath
        val appData = pluginPath?.let { Path.of(it).parent?.parent?.parent }
        val root = appData ?: Path.of(
            System.getenv("APPDATA") ?: System.getProperty("java.io.tmpdir")
        ).let { if (it.fileName?.toString() == "Salt Player for Windows") it else it.resolve("Salt Player for Windows") }
        return root.resolve("workshop").resolve("data").resolve(PluginState.pluginId)
            .resolve("live-sessions.json")
    }
}
