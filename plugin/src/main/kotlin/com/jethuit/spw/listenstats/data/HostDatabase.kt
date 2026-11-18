package com.jethuit.spw.listenstats.data

import com.jethuit.spw.listenstats.PluginLog
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.sql.Connection
import java.time.LocalDate
import java.time.ZoneId

/** Reads the host history while keeping an in-memory append cursor for the enable session. */
object HostDatabase {
    private const val RECENT_ROW_WINDOW = 256L
    private const val TRACK_QUERY_CHUNK = 500

    private val cacheLock = Any()
    private var cache: Cache? = null

    private data class FileIdentity(
        val path: Path,
        val fileKey: Any?,
        val creationTime: FileTime
    )

    private data class Cache(
        val identity: FileIdentity,
        val connection: Connection,
        var rowIdSupported: Boolean,
        val rows: LinkedHashMap<Long, ListenEntry>,
        val rawRows: LinkedHashMap<Long, RawListen>,
        var maxRowId: Long,
        var tracks: LinkedHashMap<String, TrackMeta>,
        var dataVersion: Long,
        var builtZone: ZoneId? = null,
        var builtAggregate: Boolean? = null,
        var builtData: StatsData? = null
    )

    private data class RowBatch(
        val rows: List<Pair<Long, ListenEntry>>,
        val rawRows: LinkedHashMap<Long, RawListen>,
        val maxRowId: Long
    )

    private data class RawListen(
        val id: String?,
        val trackId: String?,
        val startTimeMs: Long,
        val durationMs: Long
    )

    data class CacheMetrics(
        val validRows: Int,
        val rawRows: Int,
        val trackRows: Int,
        val builtData: Boolean
    )

    /** Read-only diagnostics for synthetic memory analysis; does not expose cache contents. */
    fun cacheMetrics(): CacheMetrics? = synchronized(cacheLock) {
        cache?.let { CacheMetrics(it.rows.size, it.rawRows.size, it.tracks.size, it.builtData != null) }
    }

    /**
     * Reads one snapshot. The first read scans the history; later reads use SQLite's
     * connection-local data version and append cursor when the table is append-only.
     */
    fun load(databasePath: Path, zone: ZoneId = ZoneId.systemDefault(), aggregate: Boolean = true): StatsData =
        PerformanceProbe.measure("HostDatabase.load") { synchronized(cacheLock) {
            if (!Files.isRegularFile(databasePath)) {
                closeLocked()
                return@synchronized StatsData.empty(
                    error = "没有找到播放器听歌数据库：$databasePath",
                    databaseFound = false
                )
            }

            return@synchronized try {
                val identity = fileIdentity(databasePath)
                val state = ensureCache(identity)
                val currentVersion = dataVersion(state.connection)
                val changed = currentVersion != state.dataVersion
                if (changed) {
                    PerformanceProbe.measure("HostDatabase.refreshChanged") {
                        refreshChanged(state, currentVersion)
                    }
                }
                if (!changed && state.builtZone == zone && state.builtAggregate == aggregate) {
                    state.builtData ?: buildData(state, zone, aggregate)
                } else {
                    buildData(state, zone, aggregate)
                }
            } catch (failure: Throwable) {
                PluginLog.error("reading listening database failed", failure)
                StatsData.empty(
                    error = failure.message ?: failure.javaClass.simpleName,
                    databaseFound = true
                )
            }
        } }

    /** Closes the session-owned read connection when the plugin is disabled. */
    fun close() = synchronized(cacheLock) {
        closeLocked()
        SqliteDriver.close()
    }

    private fun ensureCache(identity: FileIdentity): Cache {
        cache?.takeIf { sameFile(it.identity, identity) }?.let { return it }
        closeLocked()
        val connection = open(identity.path)
        return try {
            val state = Cache(
                identity = identity,
                connection = connection,
                rowIdSupported = supportsRowId(connection),
                rows = LinkedHashMap(),
                rawRows = LinkedHashMap(),
                maxRowId = 0,
                tracks = LinkedHashMap(),
                dataVersion = 0
            )
            val versionBeforeRead = dataVersion(connection)
            fullReload(state)
            val versionAfterRead = dataVersion(connection)
            state.dataVersion = if (versionAfterRead == versionBeforeRead) versionAfterRead else versionBeforeRead
            cache = state
            state
        } catch (failure: Throwable) {
            runCatching { connection.close() }
            throw failure
        }
    }

    private fun sameFile(left: FileIdentity, right: FileIdentity): Boolean {
        if (left.path != right.path) return false
        if (left.fileKey != null && right.fileKey != null) return left.fileKey == right.fileKey
        // Windows normally supplies fileKey. On a filesystem without one, creation
        // time catches replacement while avoiding invalidation on normal appends.
        return left.creationTime == right.creationTime
    }

    private fun fileIdentity(path: Path): FileIdentity {
        val normalized = path.toAbsolutePath().normalize()
        val attributes = Files.readAttributes(normalized, BasicFileAttributes::class.java)
        return FileIdentity(
            path = normalized,
            fileKey = attributes.fileKey(),
            creationTime = attributes.creationTime()
        )
    }

    private fun refreshChanged(state: Cache, versionBeforeRead: Long) {
        if (!state.rowIdSupported) {
            fullReload(state)
            val versionAfterRead = dataVersion(state.connection)
            state.dataVersion = if (versionAfterRead == versionBeforeRead) versionAfterRead else versionBeforeRead
            return
        }

        val tailFloor = (state.maxRowId - RECENT_ROW_WINDOW).coerceAtLeast(0L)
        if (!olderRowsMatch(state, tailFloor)) {
            fullReload(state)
        } else {
            // Replace the mutable tail and append new rows in one query.
            val tail = readRows(state.connection, rowIdSupported = true, afterRowId = tailFloor)
            state.rawRows.keys.removeIf { it > tailFloor }
            state.rows.keys.removeIf { it > tailFloor }
            state.rawRows.putAll(tail.rawRows)
            tail.rows.forEach { (rowId, entry) -> state.rows[rowId] = entry }
            state.maxRowId = state.rawRows.keys.maxOrNull() ?: 0
            state.tracks = readTracks(state.connection, state.rows.values.asSequence().map { it.trackId }.toSet())
            invalidateBuiltData(state)
        }
        val versionAfterRead = dataVersion(state.connection)
        state.dataVersion = if (versionAfterRead == versionBeforeRead) versionAfterRead else versionBeforeRead
    }

    private fun fullReload(state: Cache) {
        val batch = try {
            readRows(state.connection, rowIdSupported = state.rowIdSupported, afterRowId = null)
        } catch (failure: Throwable) {
            // A schema replacement can remove rowid. Fall back to a row-order snapshot;
            // subsequent changes will use full reloads until the table is replaced again.
            if (!state.rowIdSupported) throw failure
            state.rowIdSupported = false
            readRows(state.connection, rowIdSupported = false, afterRowId = null)
        }
        state.rows.clear()
        state.rawRows.clear()
        state.rawRows.putAll(batch.rawRows)
        batch.rows.forEach { (rowId, entry) -> state.rows[rowId] = entry }
        state.maxRowId = batch.maxRowId
        state.tracks = readTracks(state.connection, state.rows.values.asSequence().map { it.trackId }.toSet())
        invalidateBuiltData(state)
    }

    private fun buildData(state: Cache, zone: ZoneId, aggregate: Boolean): StatsData =
        PerformanceProbe.measure("HostDatabase.buildData") {
            buildDataInternal(state, zone, aggregate)
        }

    private fun buildDataInternal(state: Cache, zone: ZoneId, aggregate: Boolean): StatsData {
        val listens = state.rows.values
            .sortedBy { it.startTimeMs }
        val trackIds = listens.asSequence().map { it.trackId }.toSet()
        val tracks = LinkedHashMap<String, TrackMeta>()
        trackIds.forEach { id -> state.tracks[id]?.let { tracks[id] = it } }
        val daily = HashMap<LocalDate, Long>()
        val hourly = HashMap<LocalDate, LongArray>()
        val playCounts = HashMap<String, Int>()
        if (aggregate) {
            listens.forEach { entry ->
                tracks[entry.trackId]?.let { meta ->
                    if (meta.durationMs > 0 && entry.durationMs >= meta.durationMs / 2.0) {
                        playCounts[entry.trackId] = (playCounts[entry.trackId] ?: 0) + 1
                    }
                }
                splitIntoHours(entry, zone, daily, hourly)
            }
        }
        return StatsData(
            listens = listens,
            tracks = tracks,
            dailyMillis = daily,
            hourlyMillis = hourly,
            playCounts = playCounts,
            covers = emptyMap(),
            loadedAt = System.currentTimeMillis(),
            databaseFound = true,
            error = null
        ).also {
            state.builtZone = zone
            state.builtAggregate = aggregate
            state.builtData = it
        }
    }

    private fun invalidateBuiltData(state: Cache) {
        state.builtZone = null
        state.builtAggregate = null
        state.builtData = null
    }

    private fun supportsRowId(connection: Connection): Boolean = try {
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT rowid FROM Listen LIMIT 0").use { }
        }
        true
    } catch (_: Throwable) {
        false
    }

    private fun readRows(
        connection: Connection,
        rowIdSupported: Boolean,
        afterRowId: Long?
    ): RowBatch = PerformanceProbe.measure("HostDatabase.readRows") {
        readRowsInternal(connection, rowIdSupported, afterRowId)
    }

    private fun readRowsInternal(
        connection: Connection,
        rowIdSupported: Boolean,
        afterRowId: Long?
    ): RowBatch {
        val select = if (rowIdSupported) {
            "SELECT rowid AS __rowid, id, trackId, startTime, duration FROM Listen" +
                (if (afterRowId != null) " WHERE rowid > ?" else "") +
                " ORDER BY rowid"
        } else {
            "SELECT id, trackId, startTime, duration FROM Listen" +
                (if (afterRowId != null) " WHERE 1 = 0" else "") +
                " ORDER BY startTime"
        }
        val rows = ArrayList<Pair<Long, ListenEntry>>()
        val rawRows = LinkedHashMap<Long, RawListen>()
        var maxRowId = 0L
        connection.prepareStatement(select).use { statement ->
            afterRowId?.let { statement.setLong(1, it) }
            statement.executeQuery().use { result ->
                var syntheticRowId = 0L
                while (result.next()) {
                    val rowId = if (rowIdSupported) result.getLong("__rowid") else ++syntheticRowId
                    maxRowId = maxOf(maxRowId, rowId)
                    val start = result.getLong("startTime")
                    val rawDuration = result.getLong("duration")
                    val id = result.getString("id")
                    val trackId = result.getString("trackId")
                    val raw = RawListen(id, trackId, start, rawDuration)
                    rawRows[rowId] = raw
                    val validTrackId = trackId ?: continue
                    val duration = rawDuration
                    if (duration <= 0) continue
                    rows += rowId to ListenEntry(
                        id = id ?: "$validTrackId-$start",
                        trackId = validTrackId,
                        startTimeMs = start,
                        durationMs = duration
                    )
                }
            }
        }
        return RowBatch(rows, rawRows, maxRowId)
    }

    private fun readTracks(connection: Connection, ids: Set<String>): LinkedHashMap<String, TrackMeta> =
        PerformanceProbe.measure("HostDatabase.readTracks") { readTracksInternal(connection, ids) }

    private fun readTracksInternal(connection: Connection, ids: Set<String>): LinkedHashMap<String, TrackMeta> {
        val result = LinkedHashMap<String, TrackMeta>()
        if (ids.isEmpty()) return result
        val pathColumn = trackPathColumn(connection)
        val pathSelect = pathColumn?.let { ", $it" }.orEmpty()
        ids.chunked(TRACK_QUERY_CHUNK).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            connection.prepareStatement(
                "SELECT id, title, artist, album, albumArtist, duration$pathSelect FROM Track WHERE id IN ($placeholders)"
            ).use { statement ->
                chunk.forEachIndexed { index, id -> statement.setString(index + 1, id) }
                statement.executeQuery().use { rows ->
                    while (rows.next()) {
                        val id = rows.getString("id") ?: continue
                        val title = rows.getString("title") ?: continue
                        result[id] = TrackMeta(
                            id = id,
                            title = title,
                            artist = rows.getString("artist").orEmpty(),
                            album = rows.getString("album").orEmpty(),
                            albumArtist = rows.getString("albumArtist").orEmpty(),
                            durationMs = rows.getLong("duration"),
                            path = pathColumn?.let { rows.getString(it) }.orEmpty()
                        )
                    }
                }
            }
        }
        return result
    }

    /** Older host schemas do not expose the source path, so an empty path means unknown. */
    private fun trackPathColumn(connection: Connection): String? {
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(Track)").use { rows ->
                while (rows.next()) {
                    val name = rows.getString("name") ?: continue
                    if (name.equals("path", ignoreCase = true) ||
                        name.equals("filePath", ignoreCase = true) ||
                        name.equals("file", ignoreCase = true) ||
                        name.equals("uri", ignoreCase = true) ||
                        name.equals("url", ignoreCase = true) ||
                        name.equals("location", ignoreCase = true)) return name
                }
            }
        }
        return null
    }

    /** data_version covers the entire database. Verify every cached prefix row before reusing it. */
    private fun olderRowsMatch(state: Cache, through: Long): Boolean {
        val sql = "SELECT rowid, id, trackId, startTime, duration FROM Listen WHERE rowid <= ?"
        state.connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, through)
            statement.executeQuery().use { rows ->
                var seen = 0
                while (rows.next()) {
                    val cached = state.rawRows[rows.getLong(1)] ?: return false
                    if (cached.id != rows.getString(2) || cached.trackId != rows.getString(3) ||
                        cached.startTimeMs != rows.getLong(4) || cached.durationMs != rows.getLong(5)) return false
                    seen++
                }
                return seen == state.rawRows.keys.count { it <= through }
            }
        }
    }

    private fun dataVersion(connection: Connection): Long {
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA data_version").use { rows ->
                check(rows.next()) { "SQLite data_version returned no row" }
                return rows.getLong(1)
            }
        }
    }

    private fun open(path: Path): Connection = SqliteDriver.openReadOnly(path)

    private fun closeLocked() {
        cache?.let { runCatching { it.connection.close() } }
        cache = null
    }

    private fun splitIntoHours(
        entry: ListenEntry,
        zone: ZoneId,
        daily: MutableMap<LocalDate, Long>,
        hourly: MutableMap<LocalDate, LongArray>
    ) {
        TimeSplitter.split(entry.startTimeMs, entry.durationMs, zone, daily, hourly)
    }
}
