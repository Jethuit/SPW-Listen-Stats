package com.jethuit.spw.listenstats.data

import com.jethuit.spw.listenstats.PluginLog
import kotlinx.serialization.json.*
import androidx.compose.runtime.mutableStateListOf
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
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
    var rejectedHostIds: Set<String> = emptySet(),
    val observedOriginMs: Long? = null,
    var path: String = ""
) {
    fun copyForStore(): LiveSession = copy(spans = spans.toMutableList(), hostAliases = hostAliases.toSet(), rejectedHostIds = rejectedHostIds.toSet())
    fun copyForDisplay(): LiveSession = copyForStore()
    fun recordedSpans(): List<ListeningSpan> =
        if (spans.isEmpty() && durationMs > 0) listOf(ListeningSpan(startTimeMs, durationMs)) else spans.toList()
    fun trackMeta() = TrackMeta(trackId, title, artist, album, albumArtist, trackDurationMs, path)
    fun event(libraryId: String = DataFiles.sourceId()) = ListeningEvent("live:$id",
        trackMeta(),
        recordedSpans(),
        aliases = hostAliases + setOfNotNull(hostId), approximate = approximate, libraryId = libraryId)
}

object LiveStore {
    // Entries are replaced, never mutated after publication. toList() retains an ordered,
    // structurally shared snapshot in O(1), including for queued saves.
    private val sessions = mutableStateListOf<LiveSession>()
    private val indices = HashMap<String, Int>()
    @Synchronized private fun records(): List<LiveSession> = sessions.toList()
    private var writable = true
    @Volatile private var error: String? = null
    private val writer = ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(),
        java.util.concurrent.ThreadFactory { task -> Thread(task, "ListenStats-Storage").apply { isDaemon = true } })
    private var pendingSave: Future<*>? = null
    private var replaceableSave = false
    /** Changes whenever the live input or its durable storage status changes. */
    private val mutationRevision = AtomicLong()
    @Synchronized fun storageError(): String? = error
    @Synchronized fun revision(): Long = mutationRevision.get()

    @Synchronized fun load() {
        pendingSave?.get()
        pendingSave = null
        mutationRevision.incrementAndGet()
        indices.clear()
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
                    item["rejectedHostIds"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet(),
                    item["observedOrigin"]?.jsonPrimitive?.longOrNull,
                    item["path"]?.jsonPrimitive?.contentOrNull.orEmpty())
            }
            sessions.addAll(loaded.values)
            sessions.forEachIndexed { index, record -> indices[record.id] = index }
        } catch (failure: Exception) {
            // Never replace unreadable historical data with an empty new file.
            writable = false
            error = "实时记录文件无法读取，已保留原文件；新记录暂时仅在内存中"
            PluginLog.error("live sessions unreadable; preserving original file", failure)
        }
    }

    fun save() {
        enqueueSave(replaceable = false)?.get()
    }

    fun requestSave() {
        enqueueSave(replaceable = true)
    }

    @Synchronized private fun enqueueSave(replaceable: Boolean): Future<*>? {
        if (!writable) return null
        val directory = DataFiles.directory()
        val records = records()
        // A newer complete snapshot supersedes a queued write. An active write finishes first.
        val previous = pendingSave
        if (replaceableSave && previous is Runnable && writer.remove(previous)) previous.cancel(false)
        replaceableSave = replaceable
        return writer.submit { writeSnapshot(directory, records) }.also { pendingSave = it }
    }

    private fun writeSnapshot(directory: Path, records: List<LiveSession>) {
        val previousError = error
        try {
            val source = DataFiles.sourceId(directory)
            val root = buildJsonObject {
                put("version", 2)
                put("sourceId", source)
                put("sessions", buildJsonArray {
                    records.forEach { session -> add(buildJsonObject {
                        put("id", session.id); put("trackId", session.trackId)
                        put("startTime", session.startTimeMs); put("duration", session.durationMs)
                        put("title", session.title); put("artist", session.artist)
                        put("album", session.album); put("albumArtist", session.albumArtist)
                        put("trackDuration", session.trackDurationMs); put("finalized", session.finalized)
                        put("approximate", session.approximate)
                        session.firstPositionMs?.let { put("firstPosition", it) }
                        session.lastPositionMs?.let { put("lastPosition", it) }
                        session.lifecycleId?.let { put("lifecycleId", it) }
                        session.observedOriginMs?.let { put("observedOrigin", it) }
                        if (session.path.isNotBlank()) put("path", session.path)
                        put("rejectedHostIds", buildJsonArray { session.rejectedHostIds.sorted().forEach { add(JsonPrimitive(it)) } })
                        session.hostId?.let { put("hostId", it) }
                        put("hostAliases", buildJsonArray { session.hostAliases.sorted().forEach { add(JsonPrimitive(it)) } })
                        put("spans", buildJsonArray { session.recordedSpans().forEach { span ->
                            add(buildJsonArray { add(JsonPrimitive(span.startMs)); add(JsonPrimitive(span.durationMs)) })
                        } })
                    }) }
                })
            }
            DataFiles.writeAtomic(directory.resolve("live-sessions.json"), root.toString())
            error = null
            if (error != previousError) mutationRevision.incrementAndGet()
        } catch (failure: Exception) {
            error = "实时记录保存失败：${failure.message}"
            if (error != previousError) mutationRevision.incrementAndGet()
            PluginLog.error("saving live sessions failed", failure)
        }
    }

    @Synchronized fun upsert(session: LiveSession) {
        val index = indices[session.id]
        val previous = index?.let { sessions[it] }
        val oldHostId = previous?.hostId
        val oldAliases = previous?.hostAliases.orEmpty()
        val rejected = previous?.rejectedHostIds.orEmpty() + session.rejectedHostIds
        val updated = session.copyForStore().also {
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
        if (previous == updated) return
        if (index == null) {
            indices[updated.id] = sessions.size
            sessions.add(updated)
        } else sessions[index] = updated
        mutationRevision.incrementAndGet()
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
        return sessions
            .filter { it.trackId == trackId && !it.finalized && it.startTimeMs >= cutoff }
            .maxByOrNull { it.startTimeMs }
            ?.copyForDisplay()
    }

    /** Called only for the first valid playback observation after initialization. */
    @Synchronized fun recoverOpen(trackId: String, nowMs: Long = System.currentTimeMillis(), deferSave: Boolean = false): LiveSession? {
        val recovered = findOpen(trackId, nowMs)
        finalizeOpenExcept(recovered?.id, deferSave)
        return recovered
    }

    @Synchronized fun finalizeOpenExcept(keepId: String? = null, deferSave: Boolean = false) {
        var changed = false
        sessions.toList().filter { !it.finalized && it.id != keepId }.forEach {
            sessions[indices.getValue(it.id)] = it.copy(finalized = true)
            changed = true
        }
        if (changed) {
            mutationRevision.incrementAndGet()
            if (deferSave) requestSave() else save()
        }
    }

    fun snapshot() = records().map { it.copyForDisplay() }
    @Synchronized fun size() = sessions.size

    /** Apply local identity repairs before stale backup aliases can join unrelated plays. */
    fun correctImportedEvents(events: List<ListeningEvent>) = correctImportedEvents(events, records())

    private fun correctImportedEvents(events: List<ListeningEvent>, records: List<LiveSession>): List<ListeningEvent> {
        val repairs = records.filter { it.rejectedHostIds.isNotEmpty() }.associateBy { "live:${it.id}" }
        if (repairs.isEmpty()) return events
        return events.map { event ->
            val keys = event.aliases + event.id
            val rejected = keys.mapNotNull { repairs[it] }.flatMap { it.rejectedHostIds }.toSet()
            if (rejected.isEmpty()) event else {
                val accepted = keys.filterNot { rejectedHostAlias(it, rejected) }.toSet()
                val id = event.id.takeIf { it in accepted } ?: accepted.first { it in repairs }
                event.copy(id = id, aliases = accepted - id)
            }
        }
    }

    fun merge(
        host: StatsData,
        importedEvents: List<ListeningEvent> = emptyList(),
        trackVisible: (TrackMeta) -> Boolean = TrackVisibility.Snapshot()::isVisible
    ): StatsData = PerformanceProbe.measure("LiveStore.merge") {
        mergeInternal(host, importedEvents, trackVisible)
    }

    private fun mergeInternal(
        host: StatsData,
        importedEvents: List<ListeningEvent>,
        trackVisible: (TrackMeta) -> Boolean
    ): StatsData {
        val source = DataFiles.sourceId()
        val visibleIds = host.tracks.filterValues(trackVisible).keys
        val missingTrackIds = mutableSetOf<String>()
        val visibleHost = host.copy(
            listens = host.listens.filter { it.trackId !in host.tracks || it.trackId in visibleIds },
            tracks = host.tracks.filterKeys { it in visibleIds }
        )
        val prefix = "host:$source:"
        val original = records()
        val ordered = original.filter { it.durationMs > 0 && trackVisible(it.trackMeta()) }
            .sortedBy { firstObserved(it) }
        val hostEvents = PerformanceProbe.measure("LiveStore.buildHostEvents") {
            visibleHost.listens.map { listen ->
                val track = visibleHost.tracks[listen.trackId] ?: run {
                    missingTrackIds.add(listen.trackId)
                    TrackMeta(listen.trackId, "未知歌曲", "", "", "", 0)
                }
                ListeningEvent("host:$source:${listen.id}", track,
                    listOf(ListeningSpan(listen.startTimeMs, listen.durationMs)),
                    approximate = true, libraryId = source)
            }
        }
        val hostRows = PerformanceProbe.measure("LiveStore.indexHostRows") {
            if (ordered.isEmpty()) emptyMap() else visibleHost.listens.associateBy { it.id }
        }
        val byTrack = PerformanceProbe.measure("LiveStore.groupHostByTrack") {
            if (ordered.isEmpty()) emptyMap() else
                visibleHost.listens.groupBy { it.trackId }.mapValues { (_, rows) -> rows.sortedBy { it.startTimeMs } }
        }
        val nextHostStart = PerformanceProbe.measure("LiveStore.buildNextHostStart") {
            byTrack.values.flatMap { rows ->
                rows.zipWithNext().map { (a, b) -> a.id to b.startTimeMs }
            }.toMap()
        }
        val assigned = mutableMapOf<String, MutableList<LiveSession>>()
        val repairs = mutableMapOf<String, LiveSession>()
        val changedInputs = mutableListOf<LiveSession>()
        PerformanceProbe.measure("LiveStore.matchCandidates") { ordered.forEachIndexed { index, session ->
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
                val origin = session.observedOriginMs ?: session.startTimeMs
                val root = members.isEmpty() && abs(listen.startTimeMs - origin) <= 10_000 &&
                    (first < listen.startTimeMs + listen.durationMs ||
                        abs(listen.startTimeMs - origin) <= 250)
                val laterPlay = nextHostStart[listen.id]?.let { next ->
                    next > listen.startTimeMs && next <= first && next <= origin + 250
                } == true
                disjoint && !laterPlay && (root || sameOrigin || continuation)
            }.minByOrNull { abs(it.startTimeMs - session.startTimeMs) }
            val oldId = session.hostId
            val newId = candidate?.let { "$prefix${it.id}" }
                ?: oldId?.takeUnless { old -> old.startsWith(prefix) && old.removePrefix(prefix) in hostRows }
            val resolved = if (oldId != newId) session.copy().also { updated ->
                if (oldId != null && hostRowId(oldId) != newId?.let { hostRowId(it) }) {
                    updated.rejectedHostIds += updated.hostAliases.filter { hostRowId(it) == hostRowId(oldId) } + oldId
                    updated.hostAliases = updated.hostAliases.filterNot {
                        rejectedHostAlias(it, updated.rejectedHostIds)
                    }.toSet()
                } else if (oldId != null) updated.hostAliases += oldId // source identity migration
                updated.hostId = newId
                // A row with newly confirmed evidence is no longer rejected.
                if (newId != null) updated.rejectedHostIds = updated.rejectedHostIds.filterNot {
                    hostRowId(it) == hostRowId(newId)
                }.toSet()
                repairs[updated.id] = updated
                changedInputs.add(session)
            } else session
            resolved.hostId?.takeIf { it.startsWith(prefix) }?.let {
                assigned.getOrPut(it) { mutableListOf() }.add(session)
            }
        } }
        // Publish only repairs whose input still has the same identity. Playback or a new
        // enable session may have replaced a record while the host matching ran.
        if (repairs.isNotEmpty()) {
            val write = synchronized(this) {
                var applied = false
                for (record in changedInputs) {
                    val updated = repairs.getValue(record.id)
                    val index = indices[record.id] ?: continue
                    if (sessions[index] === record) {
                        sessions[index] = updated
                        applied = true
                    }
                }
                if (applied) {
                    mutationRevision.incrementAndGet()
                    enqueueSave(replaceable = false)
                } else null
            }
            // A completed identity repair is durable before returning it to backup callers.
            write?.get()
        }
        val liveEvents = PerformanceProbe.measure("LiveStore.buildLiveEvents") {
            ordered.map { repairs[it.id] ?: it }.groupBy { it.hostId?.takeIf { id -> id.startsWith(prefix) } ?: "live:${it.id}" }
                .values.map { pieces ->
                    val events = pieces.map { it.event(source) }
                    val first = events.first()
                    first.copy(
                        track = events.fold(first.track) { known, piece -> ListeningEvents.mergeTrack(known, piece.track) },
                        spans = ListeningEvents.unionSpans(events.flatMap { it.spans }),
                        aliases = events.flatMap { it.aliases + it.id }.toSet() - first.id,
                        approximate = events.any { it.approximate }
                    )
                }
        }
        return ListeningEvents.aggregate(visibleHost.copy(missingHostTrackIds = missingTrackIds), hostEvents + liveEvents +
            correctImportedEvents(importedEvents, if (repairs.isEmpty()) original else original.map { repairs[it.id] ?: it }).filter { trackVisible(it.track) })
    }
}
