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
    var hostAliases: Set<String> = emptySet(),
    val firstPositionMs: Long? = null,
    var lastPositionMs: Long? = null,
    val lifecycleId: String? = null,
    var rejectedHostIds: Set<String> = emptySet()
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
                    item["hostAliases"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet(),
                    item["firstPosition"]?.jsonPrimitive?.longOrNull,
                    item["lastPosition"]?.jsonPrimitive?.longOrNull,
                    item["lifecycleId"]?.jsonPrimitive?.contentOrNull,
                    item["rejectedHostIds"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet())
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
                        session.firstPositionMs?.let { put("firstPosition", it) }
                        session.lastPositionMs?.let { put("lastPosition", it) }
                        session.lifecycleId?.let { put("lifecycleId", it) }
                        put("rejectedHostIds", buildJsonArray { session.rejectedHostIds.sorted().forEach { add(JsonPrimitive(it)) } })
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
        val rejected = sessions[session.id]?.rejectedHostIds.orEmpty() + session.rejectedHostIds
        sessions[session.id] = session.copyForStore().also {
            // The accumulator may still hold a pre-migration copy of this session.
            if (oldHostId != null) {
                it.hostId = oldHostId
                session.hostId?.takeIf { alias -> alias != oldHostId }?.let { alias -> it.hostAliases += alias }
            }
            it.hostAliases += oldAliases
            it.rejectedHostIds = rejected
            it.hostAliases = it.hostAliases.filterNot { alias -> rejectedHostAlias(alias, rejected) }.toSet()
            if (it.hostId?.let { alias -> rejectedHostAlias(alias, rejected) } == true) it.hostId = null
        }
    }

    private fun hostRowId(alias: String): String? =
        alias.takeIf { it.startsWith("host:") }?.split(':', limit = 3)?.getOrNull(2)

    private fun rejectedHostAlias(alias: String, rejected: Set<String>): Boolean =
        alias in rejected || hostRowId(alias)?.let { row -> rejected.any { hostRowId(it) == row } } == true

    private fun firstObserved(session: LiveSession) = session.recordedSpans().minOf { it.startMs }
    private fun lastObserved(session: LiveSession) = session.recordedSpans().maxOf { it.startMs + it.durationMs }
    private fun firstPosition(session: LiveSession) =
        session.firstPositionMs ?: (firstObserved(session) - session.startTimeMs).coerceAtLeast(0)

    /** A pause moves wall-clock origin estimates, but does not rewind the playback position. */
    private fun continues(previous: LiveSession, next: LiveSession): Boolean {
        if (!previous.finalized || previous.trackId != next.trackId ||
            (previous.lifecycleId != null && previous.lifecycleId == next.lifecycleId)) return false
        val gap = firstObserved(next) - lastObserved(previous)
        val from = previous.lastPositionMs ?: (firstPosition(previous) + previous.durationMs)
        val to = firstPosition(next)
        return gap >= 0 && to > 250 && to >= from - 250 && to - from <= gap + 250
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
        val hostRows = host.listens.associateBy { it.id }
        val byTrack = host.listens.groupBy { it.trackId }.mapValues { (_, rows) -> rows.sortedBy { it.startTimeMs } }
        val nextHostStart = byTrack.values.flatMap { rows ->
            rows.zipWithNext().map { (a, b) -> a.id to b.startTimeMs }
        }.toMap()
        val assigned = mutableMapOf<String, MutableList<LiveSession>>()
        val ordered = sessions.values.filter { it.durationMs > 0 }.sortedBy { firstObserved(it) }
        var changed = false
        ordered.forEachIndexed { index, session ->
            val observed = session.recordedSpans()
            val first = firstObserved(session)
            val previous = ordered.getOrNull(index - 1)
            val candidate = byTrack[session.trackId].orEmpty().filter { listen ->
                if (listen.trackId != session.trackId || listen.startTimeMs > first + 250) return@filter false
                val members = assigned["$prefix${listen.id}"].orEmpty()
                val disjoint = members.all { other ->
                    other.recordedSpans().none { a -> observed.any { b ->
                        a.startMs < b.startMs + b.durationMs && b.startMs < a.startMs + a.durationMs
                    } }
                }
                val continuation = previous != null && previous in members && continues(previous, session)
                val sameOrigin = members.isNotEmpty() && members.all { other ->
                    (session.lifecycleId == null || session.lifecycleId != other.lifecycleId) &&
                        abs(other.startTimeMs - session.startTimeMs) <= 250
                }
                // A recent completed host row cannot own a new observation occurring after
                // its recorded interval. Empty assignments are not proof of a shared play.
                val root = members.isEmpty() && abs(listen.startTimeMs - session.startTimeMs) <= 10_000 &&
                    (first < listen.startTimeMs + listen.durationMs ||
                        abs(listen.startTimeMs - session.startTimeMs) <= 250)
                val laterPlay = nextHostStart[listen.id]?.let { next ->
                    next > listen.startTimeMs && next <= first && next <= session.startTimeMs + 250
                } == true
                disjoint && !laterPlay && (root || sameOrigin || continuation)
            }.minByOrNull { abs(it.startTimeMs - session.startTimeMs) }
            val oldId = session.hostId
            val newId = candidate?.let { "$prefix${it.id}" }
                ?: oldId?.takeUnless { old -> old.startsWith(prefix) && old.removePrefix(prefix) in hostRows }
            if (oldId != newId) {
                if (oldId != null && hostRowId(oldId) != newId?.let { hostRowId(it) }) {
                    session.rejectedHostIds += session.hostAliases.filter { hostRowId(it) == hostRowId(oldId) } + oldId
                    session.hostAliases = session.hostAliases.filterNot {
                        rejectedHostAlias(it, session.rejectedHostIds)
                    }.toSet()
                } else if (oldId != null) session.hostAliases += oldId // source identity migration
                session.hostId = newId
                // A row with newly confirmed evidence is no longer rejected.
                if (newId != null) session.rejectedHostIds = session.rejectedHostIds.filterNot {
                    hostRowId(it) == hostRowId(newId)
                }.toSet()
                changed = true
            }
            session.hostId?.takeIf { it.startsWith(prefix) }?.let {
                assigned.getOrPut(it) { mutableListOf() }.add(session)
            }
        }
        if (changed) save()
        val liveEvents = ordered.groupBy { it.hostId?.takeIf { id -> id.startsWith(prefix) } ?: "live:${it.id}" }
            .values.map { pieces ->
                val events = pieces.map { it.event() }
                val first = events.first()
                first.copy(
                    track = events.fold(first.track) { known, piece -> ListeningEvents.mergeTrack(known, piece.track) },
                    spans = ListeningEvents.unionSpans(events.flatMap { it.spans }),
                    aliases = events.flatMap { it.aliases + it.id }.toSet() - first.id,
                    approximate = events.any { it.approximate }
                )
            }
        return ListeningEvents.aggregate(host, hostEvents + liveEvents)
    }
}
