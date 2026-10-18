package com.jethuit.spw.listenstats.data

import com.jethuit.spw.listenstats.PluginLog
import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneId

object HostDatabase {
    private const val DRIVER = "org.sqlite.JDBC"

    fun load(databasePath: Path, zone: ZoneId = ZoneId.systemDefault()): StatsData {
        if (!Files.isRegularFile(databasePath)) {
            return StatsData.empty(
                error = "没有找到播放器听歌数据库：$databasePath",
                databaseFound = false
            )
        }
        return try {
            Class.forName(DRIVER)
            open(databasePath).use { connection ->
                val listens = ArrayList<ListenEntry>()
                val tracks = LinkedHashMap<String, TrackMeta>()
                val daily = HashMap<LocalDate, Long>()
                val hourly = HashMap<LocalDate, LongArray>()
                val playCounts = HashMap<String, Int>()

                connection.prepareStatement(
                    """
                    SELECT l.id, l.trackId, l.startTime, l.duration,
                           t.title, t.artist, t.album, t.albumArtist, t.duration AS trackDuration
                    FROM Listen l
                    LEFT JOIN Track t ON t.id = l.trackId
                    ORDER BY l.startTime
                    """.trimIndent()
                ).use { statement ->
                    statement.executeQuery().use { rows ->
                        while (rows.next()) {
                            val trackId = rows.getString("trackId") ?: continue
                            val duration = rows.getLong("duration")
                            if (duration <= 0) continue
                            val entry = ListenEntry(
                                id = rows.getString("id") ?: "$trackId-${rows.getLong("startTime")}",
                                trackId = trackId,
                                startTimeMs = rows.getLong("startTime"),
                                durationMs = duration
                            )
                            listens.add(entry)
                            val title = rows.getString("title")
                            if (title != null) {
                                val meta = TrackMeta(
                                    id = trackId,
                                    title = title,
                                    artist = rows.getString("artist").orEmpty(),
                                    album = rows.getString("album").orEmpty(),
                                    albumArtist = rows.getString("albumArtist").orEmpty(),
                                    durationMs = rows.getLong("trackDuration")
                                )
                                tracks[trackId] = meta
                                if (meta.durationMs > 0 && duration >= meta.durationMs / 2.0) {
                                    playCounts[trackId] = (playCounts[trackId] ?: 0) + 1
                                }
                            }
                            splitIntoHours(entry, zone, daily, hourly)                        }
                    }
                }

                StatsData(
                    listens = listens,
                    tracks = tracks,
                    dailyMillis = daily,
                    hourlyMillis = hourly,
                    playCounts = playCounts,
                    covers = emptyMap(),
                    loadedAt = System.currentTimeMillis(),
                    databaseFound = true,
                    error = null
                )
            }
        } catch (failure: Throwable) {
            PluginLog.error("reading listening database failed", failure)
            StatsData.empty(
                error = failure.message ?: failure.javaClass.simpleName,
                databaseFound = true
            )
        }
    }

    private fun open(path: Path): java.sql.Connection {
        val absolute = path.toAbsolutePath().toString()
        val config = SQLiteConfig()
        config.setReadOnly(true)
        config.setBusyTimeout(4_000)
        return try {
            DriverManager.getConnection("jdbc:sqlite:$absolute", config.toProperties())
        } catch (failure: Throwable) {
            PluginLog.error("read-only sqlite connection failed, retrying with query_only", failure)
            val fallback = SQLiteConfig()
            fallback.setBusyTimeout(4_000)
            DriverManager.getConnection("jdbc:sqlite:$absolute", fallback.toProperties()).also {
                it.createStatement().use { statement -> statement.execute("PRAGMA query_only = ON") }
            }
        }
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
