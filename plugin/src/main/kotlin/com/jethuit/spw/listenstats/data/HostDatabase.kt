package com.jethuit.spw.listenstats.data

import com.jethuit.spw.listenstats.PluginLog
import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneId

/** Reads the host history while keeping an in-memory append cursor for the enable session. */
object HostDatabase {
    private const val DRIVER = "org.sqlite.JDBC"

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
        var rawRowCount: Long,
        var maxRowId: Long,
        var maxStartTimeMs: Long?,
        var rawDurationSum: Long,
        var rawStartTimeSum: Long,
        var rawTextLengthSum: Long,
        var tracks: LinkedHashMap<String, TrackMeta>,
        var dataVersion: Long,
        var builtZone: ZoneId? = null,
        var builtAggregate: Boolean? = null,
        var builtData: StatsData? = null
    )

    private data class RowBatch(
        val rows: List<Pair<Long, ListenEntry>>,
        val rawRowCount: Long,
        val maxRowId: Long,
        val maxStartTimeMs: Long?,
        val durationSum: Long,
        val startTimeSum: Long,
        val textLengthSum: Long
    )

    /**
     * Reads one snapshot. The first read scans the history; later reads use SQLite's
     * connection-local data version and append cursor when the table is append-only.
     */
    fun load(databasePath: Path, zone: ZoneId = ZoneId.systemDefault(), aggregate: Boolean = true): StatsData =
        synchronized(cacheLock) {
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
                    refreshChanged(state, currentVersion)
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
        }

    /** Closes the session-owned read connection when the plugin is disabled. */
    fun close() = synchronized(cacheLock) { closeLocked() }

    private fun ensureCache(identity: FileIdentity): Cache {
        cache?.takeIf { sameFile(it.identity, identity) }?.let { return it }
        closeLocked()
        Class.forName(DRIVER)
        val connection = open(identity.path)
        return try {
            val state = Cache(
                identity = identity,
                connection = connection,
                rowIdSupported = supportsRowId(connection),
                rows = LinkedHashMap(),
                rawRowCount = 0,
                maxRowId = 0,
                maxStartTimeMs = null,
                rawDurationSum = 0,
                rawStartTimeSum = 0,
                rawTextLengthSum = 0,
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

        val summary = listenSummary(state.connection)
        val timeOrderIntact = state.maxStartTimeMs == null ||
            summary.maxStartTimeMs == null || summary.maxStartTimeMs >= state.maxStartTimeMs!!
        val canAppend = timeOrderIntact &&
            summary.rawRowCount >= state.rawRowCount && summary.maxRowId >= state.maxRowId

        if (canAppend && summary.maxRowId > state.maxRowId) {
            val batch = readRows(state.connection, rowIdSupported = true, afterRowId = state.maxRowId)
            val expected = summary.rawRowCount - state.rawRowCount
            val oldRowsUnchanged = summary.durationSum == state.rawDurationSum + batch.durationSum &&
                summary.startTimeSum == state.rawStartTimeSum + batch.startTimeSum &&
                summary.textLengthSum == state.rawTextLengthSum + batch.textLengthSum
            if (batch.rawRowCount != expected || batch.maxRowId != summary.maxRowId || !oldRowsUnchanged) {
                fullReload(state)
            } else {
                batch.rows.forEach { (rowId, entry) -> state.rows[rowId] = entry }
                state.rawRowCount = summary.rawRowCount
                state.maxRowId = summary.maxRowId
                state.maxStartTimeMs = summary.maxStartTimeMs
                state.rawDurationSum = summary.durationSum
                state.rawStartTimeSum = summary.startTimeSum
                state.rawTextLengthSum = summary.textLengthSum
                state.tracks = readTracks(state.connection)
                invalidateBuiltData(state)
            }
        } else {
            // A changed database with no provable append may contain edits, deletes,
            // a rebuild, or a clock rollback. Re-read it once to restore a safe cursor.
            fullReload(state)
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
        batch.rows.forEach { (rowId, entry) -> state.rows[rowId] = entry }
        state.rawRowCount = batch.rawRowCount
        state.maxRowId = batch.maxRowId
        state.maxStartTimeMs = batch.maxStartTimeMs
        state.rawDurationSum = batch.durationSum
        state.rawStartTimeSum = batch.startTimeSum
        state.rawTextLengthSum = batch.textLengthSum
        state.tracks = readTracks(state.connection)
        invalidateBuiltData(state)
    }

    private fun buildData(state: Cache, zone: ZoneId, aggregate: Boolean): StatsData {
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

    private fun readRows(connection: Connection, rowIdSupported: Boolean, afterRowId: Long?): RowBatch {
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
        var rawCount = 0L
        var maxRowId = 0L
        var maxStart: Long? = null
        var durationSum = 0L
        var startTimeSum = 0L
        var textLengthSum = 0L
        connection.prepareStatement(select).use { statement ->
            if (afterRowId != null) statement.setLong(1, afterRowId)
            statement.executeQuery().use { result ->
                var syntheticRowId = 0L
                while (result.next()) {
                    rawCount++
                    val rowId = if (rowIdSupported) result.getLong("__rowid") else ++syntheticRowId
                    maxRowId = maxOf(maxRowId, rowId)
                    val start = result.getLong("startTime")
                    maxStart = maxStart?.let { maxOf(it, start) } ?: start
                    val rawDuration = result.getLong("duration")
                    durationSum += rawDuration
                    startTimeSum += start
                    textLengthSum += result.getString("id")?.length?.toLong() ?: 0L
                    textLengthSum += result.getString("trackId")?.length?.toLong() ?: 0L
                    val trackId = result.getString("trackId") ?: continue
                    val duration = rawDuration
                    if (duration <= 0) continue
                    rows += rowId to ListenEntry(
                        id = result.getString("id") ?: "$trackId-$start",
                        trackId = trackId,
                        startTimeMs = start,
                        durationMs = duration
                    )
                }
            }
        }
        return RowBatch(rows, rawCount, maxRowId, maxStart, durationSum, startTimeSum, textLengthSum)
    }

    private fun readTracks(connection: Connection): LinkedHashMap<String, TrackMeta> {
        val result = LinkedHashMap<String, TrackMeta>()
        connection.prepareStatement(
            "SELECT id, title, artist, album, albumArtist, duration FROM Track"
        ).use { statement ->
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
                        durationMs = rows.getLong("duration")
                    )
                }
            }
        }
        return result
    }

    private data class ListenSummary(
        val rawRowCount: Long,
        val maxRowId: Long,
        val maxStartTimeMs: Long?,
        val durationSum: Long,
        val startTimeSum: Long,
        val textLengthSum: Long
    )

    private fun listenSummary(connection: Connection): ListenSummary {
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT COUNT(*) AS rowCount, COALESCE(MAX(rowid), 0) AS maxRowId, MAX(startTime) AS maxStart, " +
                    "COALESCE(SUM(duration), 0) AS durationSum, COALESCE(SUM(startTime), 0) AS startTimeSum, " +
                    "COALESCE(SUM(LENGTH(id) + LENGTH(trackId)), 0) AS textLengthSum FROM Listen"
            ).use { rows ->
                check(rows.next()) { "Listen summary returned no row" }
                return ListenSummary(
                    rawRowCount = rows.getLong("rowCount"),
                    maxRowId = rows.getLong("maxRowId"),
                    maxStartTimeMs = rows.getLong("maxStart").let { if (rows.wasNull()) null else it },
                    durationSum = rows.getLong("durationSum"),
                    startTimeSum = rows.getLong("startTimeSum"),
                    textLengthSum = rows.getLong("textLengthSum")
                )
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

    private fun open(path: Path): Connection {
        val absolute = path.toAbsolutePath().toString()
        val config = SQLiteConfig()
        config.setReadOnly(true)
        config.setBusyTimeout(4_000)
        return DriverManager.getConnection("jdbc:sqlite:$absolute", config.toProperties())
    }

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
