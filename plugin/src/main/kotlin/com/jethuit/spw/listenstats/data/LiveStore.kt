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
    var approximate: Boolean = false,
    var hostId: String? = null,
    var hostAliases: Set<String> = emptySet()
) {
    fun copyForStore(): LiveSession = copy(spans = spans.toMutableList(), hostAliases = hostAliases.toSet())
    fun copyForDisplay(): LiveSession = copyForStore()
    fun recordedSpans(): List<ListeningSpan> =
        if (spans.isEmpty() && durationMs > 0) listOf(ListeningSpan(startTimeMs, durationMs)) else spans.toList()
    fun event() = ListeningEvent("live:$id",
        TrackMeta(trackId, title, artist, album, albumArtist, trackDurationMs),
        recordedSpans(),
        aliases = hostAliases + setOfNotNull(hostId), approximate = approximate)
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
                // Repair intervals produced by older versions during wall-clock corrections.
                // Preserve elapsed duration and recorded order; only the wall placement is approximate.
                var repaired = false
                for (index in 1 until spans.size) {
                    val previous = spans[index - 1]
                    val end = previous.startMs + previous.durationMs
                    if (spans[index].startMs < end) {
                        spans[index] = spans[index].copy(startMs = end)
                        repaired = true
                    }
                }
                val finalized = if (version == 1) true
                    else item["finalized"]?.jsonPrimitive?.booleanOrNull ?: true
                loaded[str("id")] = LiveSession(str("id"), str("trackId"), num("startTime"), num("duration"),
                    str("title"), str("artist"), str("album"), str("albumArtist"), num("trackDuration"),
                    finalized, spans, repaired || version == 1 || item["approximate"]?.jsonPrimitive?.booleanOrNull == true,
                    item["hostId"]?.jsonPrimitive?.contentOrNull,
                    item["hostAliases"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet())
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
                put("sourceId", DataFiles.sourceId())
                put("sessions", buildJsonArray {
                    sessions.values.forEach { session -> add(buildJsonObject {
                        put("id", session.id); put("trackId", session.trackId)
                        put("startTime", session.startTimeMs); put("duration", session.durationMs)
                        put("title", session.title); put("artist", session.artist)
                        put("album", session.album); put("albumArtist", session.albumArtist)
                        put("trackDuration", session.trackDurationMs); put("finalized", session.finalized)
                        put("approximate", session.approximate)
                        session.hostId?.let { put("hostId", it) }
                        put("hostAliases", buildJsonArray { session.hostAliases.sorted().forEach { add(JsonPrimitive(it)) } })
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
        val oldAliases = sessions[session.id]?.hostAliases.orEmpty()
        sessions[session.id] = session.copyForStore().also {
            // The accumulator may still hold a pre-migration copy of this session.
            if (oldHostId != null) {
                it.hostId = oldHostId
                session.hostId?.takeIf { alias -> alias != oldHostId }?.let { alias -> it.hostAliases += alias }
            }
            it.hostAliases += oldAliases
        }
    }

    /**
     * A session left unfinished by a crash can be resumed when the same track is
     * observed again. Graceful stops finalize sessions and are never resumed.
     */
    @Synchronized fun findOpen(trackId: String, nowMs: Long = System.currentTimeMillis()): LiveSession? {
        val cutoff = nowMs - 6 * 60 * 60 * 1000L
        return sessions.values
            .filter { it.trackId == trackId && !it.finalized && it.startTimeMs >= cutoff }
            .maxByOrNull { it.startTimeMs }
            ?.copyForDisplay()
    }

    /** Called only for the first valid playback observation after initialization. */
    @Synchronized fun recoverOpen(trackId: String, nowMs: Long = System.currentTimeMillis()): LiveSession? {
        val recovered = findOpen(trackId, nowMs)
        finalizeOpenExcept(recovered?.id)
        return recovered
    }

    @Synchronized fun finalizeOpenExcept(keepId: String? = null) {
        var changed = false
        sessions.values.filter { !it.finalized && it.id != keepId }.forEach {
            it.finalized = true
            changed = true
        }
        if (changed) save()
    }

    @Synchronized fun snapshot() = sessions.values.map { it.copyForDisplay() }

    @Synchronized fun merge(host: StatsData): StatsData {
        val source = DataFiles.sourceId()
        val hostEvents = host.listens.map { listen ->
            ListeningEvent("host:$source:${listen.id}", host.tracks[listen.trackId] ?: TrackMeta(
                listen.trackId, "未知歌曲", "", "", "", 0),
                listOf(ListeningSpan(listen.startTimeMs, listen.durationMs)), approximate = true)
        }
        val prefix = "host:$source:"
        val assigned = sessions.values.filter { it.hostId?.startsWith(prefix) == true }
            .groupBy { it.hostId!! }.mapValues { it.value.toMutableList() }.toMutableMap()
        var changed = false
        sessions.values.filter { it.durationMs > 0 }.forEach { session ->
            if (session.hostId?.startsWith(prefix) != true) {
                val observed = session.recordedSpans()
                // Distinct, non-overlapping lifecycle fragments can share the same host play.
                // Keep the existing near-start heuristic and do not join adjacent replays.
                val candidate = host.listens.filter {
                    it.trackId == session.trackId && abs(it.startTimeMs - session.startTimeMs) <= 10_000 &&
                        assigned["$prefix${it.id}"].orEmpty().all { other ->
                            abs(other.startTimeMs - session.startTimeMs) <= 250 &&
                                other.recordedSpans().none { a -> observed.any { b ->
                                    a.startMs < b.startMs + b.durationMs && b.startMs < a.startMs + a.durationMs
                                } }
                        }
                }.minByOrNull { abs(it.startTimeMs - session.startTimeMs) }
                if (candidate != null) {
                    session.hostId?.let { session.hostAliases += it }
                    session.hostId = "$prefix${candidate.id}"
                    assigned.getOrPut(session.hostId!!) { mutableListOf() }.add(session)
                    changed = true
                }
            }
        }
        if (changed) save()
        val liveEvents = sessions.values.filter { it.durationMs > 0 }.map { it.event() }
            .groupBy { it.aliases.firstOrNull { alias -> alias.startsWith(prefix) } ?: it.id }
            .values.map { pieces ->
                val first = pieces.first()
                val spans = mutableListOf<ListeningSpan>()
                pieces.flatMap { it.spans }.sortedBy { it.startMs }.forEach { span ->
                    val last = spans.lastOrNull()
                    if (last != null && span.startMs <= last.startMs + last.durationMs) {
                        spans[spans.lastIndex] = last.copy(durationMs =
                            maxOf(last.startMs + last.durationMs, span.startMs + span.durationMs) - last.startMs)
                    } else spans.add(span)
                }
                first.copy(
                    track = pieces.fold(first.track) { known, piece -> ListeningEvents.mergeTrack(known, piece.track) },
                    spans = spans,
                    aliases = pieces.flatMap { it.aliases + it.id }.toSet() - first.id,
                    approximate = pieces.any { it.approximate }
                )
            }
        return ListeningEvents.aggregate(host, hostEvents + liveEvents)
    }
}
