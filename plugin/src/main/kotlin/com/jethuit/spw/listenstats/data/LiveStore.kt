package com.jethuit.spw.listenstats.data

import com.jethuit.spw.listenstats.PluginLog
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.math.abs

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
    var finalized: Boolean,
    val spans: MutableList<ListeningSpan> = mutableListOf(),
    val approximate: Boolean = false,
    var hostId: String? = null
) {
    fun copyForStore(): LiveSession = copy(spans = spans.toMutableList())
    fun copyForDisplay(): LiveSession = copyForStore()
    fun event() = ListeningEvent("live:$id",
        TrackMeta(trackId, title, artist, album, albumArtist, trackDurationMs),
        if (spans.isEmpty() && durationMs > 0) listOf(ListeningSpan(startTimeMs, durationMs)) else spans.toList(),
        aliases = hostId?.let { setOf(it) } ?: emptySet(), approximate = approximate)
}

object LiveStore {
    private val sessions = LinkedHashMap<String, LiveSession>()
    private var writable = true
    private var error: String? = null
    @Synchronized fun storageError(): String? = error

    @Synchronized fun load() {
        sessions.clear()
        writable = true
        error = null
        val file = DataFiles.directory().resolve("live-sessions.json")
        if (!Files.isRegularFile(file)) return
        try {
            val root = Json.parseToJsonElement(Files.readString(file)).jsonObject
            val version = root["version"]!!.jsonPrimitive.int
            require(version in 1..2) { "unsupported live session version" }
            val loaded = LinkedHashMap<String, LiveSession>()
            root["sessions"]!!.jsonArray.forEach { element ->
                val item = element.jsonObject
                fun str(key: String) = item[key]?.jsonPrimitive?.contentOrNull.orEmpty()
                fun num(key: String) = item[key]?.jsonPrimitive?.long ?: 0L
                val spans = item["spans"]?.jsonArray?.map {
                    val pair = it.jsonArray
                    ListeningSpan(pair[0].jsonPrimitive.long, pair[1].jsonPrimitive.long)
                }?.toMutableList() ?: mutableListOf()
                require(num("duration") >= 0 && spans.all { it.durationMs > 0 })
                if (version == 2) require(spans.sumOf { it.durationMs } == num("duration"))
                if (version == 1 && num("duration") > 0) spans.add(ListeningSpan(num("startTime"), num("duration")))
                val finalized = if (version == 1) true
                    else item["finalized"]?.jsonPrimitive?.booleanOrNull ?: true
                loaded[str("id")] = LiveSession(str("id"), str("trackId"), num("startTime"), num("duration"),
                    str("title"), str("artist"), str("album"), str("albumArtist"), num("trackDuration"),
                    finalized, spans, version == 1 || item["approximate"]?.jsonPrimitive?.booleanOrNull == true,
                    item["hostId"]?.jsonPrimitive?.contentOrNull)
            }
            sessions.putAll(loaded)
        } catch (failure: Exception) {
            // Never replace unreadable historical data with an empty new file.
            writable = false
            error = "实时记录文件无法读取，已保留原文件；新记录暂时仅在内存中"
            PluginLog.error("live sessions unreadable; preserving original file", failure)
        }
    }

    @Synchronized fun save() {
        if (!writable) return
        try {
            val root = buildJsonObject {
                put("version", 2)
                put("sessions", buildJsonArray {
                    sessions.values.forEach { session -> add(buildJsonObject {
                        put("id", session.id); put("trackId", session.trackId)
                        put("startTime", session.startTimeMs); put("duration", session.durationMs)
                        put("title", session.title); put("artist", session.artist)
                        put("album", session.album); put("albumArtist", session.albumArtist)
                        put("trackDuration", session.trackDurationMs); put("finalized", session.finalized)
                        put("approximate", session.approximate)
                        session.hostId?.let { put("hostId", it) }
                        put("spans", buildJsonArray { session.event().spans.forEach { span ->
                            add(buildJsonArray { add(JsonPrimitive(span.startMs)); add(JsonPrimitive(span.durationMs)) })
                        } })
                    }) }
                })
            }
            DataFiles.writeAtomic(DataFiles.directory().resolve("live-sessions.json"), root.toString())
            error = null
        } catch (failure: Exception) {
            error = "实时记录保存失败：${failure.message}"
            PluginLog.error("saving live sessions failed", failure)
        }
    }

    @Synchronized fun upsert(session: LiveSession) {
        val oldHostId = sessions[session.id]?.hostId
        sessions[session.id] = session.copyForStore().also { if (it.hostId == null) it.hostId = oldHostId }
    }

    /**
     * A session left unfinished by a crash can be resumed when the same track is
     * observed again. Graceful stops finalize sessions and are never resumed.
     */
    @Synchronized fun findOpen(trackId: String): LiveSession? {
        val cutoff = System.currentTimeMillis() - 6 * 60 * 60 * 1000L
        return sessions.values
            .filter { it.trackId == trackId && !it.finalized && it.startTimeMs >= cutoff }
            .maxByOrNull { it.startTimeMs }
            ?.copyForDisplay()
    }

    @Synchronized fun snapshot() = sessions.values.map { it.copyForDisplay() }

    @Synchronized fun merge(host: StatsData): StatsData {
        val source = DataFiles.sourceId()
        val hostEvents = host.listens.map { listen ->
            ListeningEvent("host:$source:${listen.id}", host.tracks[listen.trackId] ?: TrackMeta(
                listen.trackId, "未知歌曲", "", "", "", 0),
                listOf(ListeningSpan(listen.startTimeMs, listen.durationMs)), approximate = true)
        }
        val used = sessions.values.mapNotNull { it.hostId }.toMutableSet()
        var changed = false
        sessions.values.filter { it.durationMs > 0 }.forEach { session ->
            if (session.hostId == null) {
                // Match at most one live play to a host row. Near-start matching remains a host heuristic.
                val candidate = host.listens.filter {
                    it.trackId == session.trackId && abs(it.startTimeMs - session.startTimeMs) <= 10_000 &&
                        "host:$source:${it.id}" !in used
                }.minByOrNull { abs(it.startTimeMs - session.startTimeMs) }
                if (candidate != null) {
                    session.hostId = "host:$source:${candidate.id}"
                    used.add(session.hostId!!)
                    changed = true
                }
            }
        }
        if (changed) save()
        return ListeningEvents.aggregate(host, hostEvents + sessions.values.filter { it.durationMs > 0 }.map { it.event() })
    }
}
