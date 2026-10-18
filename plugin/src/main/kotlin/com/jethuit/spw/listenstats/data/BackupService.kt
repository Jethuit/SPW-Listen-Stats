package com.jethuit.spw.listenstats.data

import com.jethuit.spw.listenstats.PluginLog
import com.jethuit.spw.listenstats.PluginState
import com.xuncorp.spw.workshop.api.WorkshopApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Backup format v1. The host's Listen table is the live source; a backup adds
 * historical days that the local database no longer keeps. Merge policy is
 * deliberately conservative: larger daily/hourly values and larger play
 * counts win, so importing never reduces visible statistics.
 */
object BackupService {
    private const val FORMAT = "spw-listen-stats-backup"
    private const val VERSION = 1

    fun export(data: StatsData) {
        val chooser = JFileChooser().apply {
            dialogTitle = "导出听歌统计备份"
            fileFilter = FileNameExtensionFilter("JSON 备份 (*.json)", "json")
            selectedFile = java.io.File("spw-listen-stats-${LocalDate.now()}.json")
        }
        if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile
        try {
            val records = buildJsonArray {
                data.dailyMillis.toSortedMap().forEach { (date, millis) ->
                    val hours = data.hoursOn(date)
                    add(
                        buildJsonObject {
                            put("date", date.toString())
                            put("ms", JsonPrimitive(millis))
                            put("hours", buildJsonArray { hours.forEach { add(JsonPrimitive(it)) } })
                        }
                    )
                }
            }
            val tracks = buildJsonArray {
                data.sortedTracks().forEach { rank ->
                    add(
                        buildJsonObject {
                            put("id", rank.track.id)
                            put("title", rank.track.title)
                            put("artist", rank.track.artist)
                            put("album", rank.track.album)
                            put("albumArtist", rank.track.albumArtist)
                            put("durationMs", JsonPrimitive(rank.track.durationMs))
                            put("count", JsonPrimitive(rank.count))
                        }
                    )
                }
            }
            val checksum = checksum(records, tracks)
            val root = buildJsonObject {
                put("format", FORMAT)
                put("version", JsonPrimitive(VERSION))
                put("exportedAt", java.time.Instant.now().toString())
                put("timeZone", ZoneId.systemDefault().id)
                put("records", records)
                put("tracks", tracks)
                put("checksum", checksum)
            }
            Files.writeString(file.toPath(), root.toString(), StandardCharsets.UTF_8)
            WorkshopApi.ui.toast("已导出听歌统计备份", WorkshopApi.Ui.ToastType.Success)
            PluginLog.info("backup exported to ${file.absolutePath}")
        } catch (failure: Throwable) {
            PluginLog.error("backup export failed", failure)
            WorkshopApi.ui.toast(
                "导出失败：${failure.message ?: "未知错误"}",
                WorkshopApi.Ui.ToastType.Error
            )
        }
    }

    fun importBackup(): Boolean {
        val chooser = JFileChooser().apply {
            dialogTitle = "导入听歌统计备份"
            fileFilter = FileNameExtensionFilter("JSON 备份 (*.json)", "json")
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return false
        val file = chooser.selectedFile
        return try {
            val text = Files.readString(file.toPath(), StandardCharsets.UTF_8)
            val root = Json.parseToJsonElement(text).jsonObject
            require(root["format"]?.jsonPrimitive?.contentOrNull == FORMAT) {
                "不是听歌统计备份文件"
            }
            require(root["version"]?.jsonPrimitive?.int == VERSION) {
                "备份版本不受支持"
            }
            val records = root["records"]?.jsonArray ?: JsonArray(emptyList())
            val tracks = root["tracks"]?.jsonArray ?: JsonArray(emptyList())
            val expected = root["checksum"]?.jsonPrimitive?.contentOrNull
            require(expected == null || expected == checksum(records, tracks)) {
                "备份校验失败，文件可能已损坏"
            }
            val validated = validate(records, tracks)
            Files.createDirectories(importedFile().parent)
            Files.writeString(importedFile(), text, StandardCharsets.UTF_8)
            WorkshopApi.ui.toast(
                "已导入 ${validated.first} 天记录、${validated.second} 首歌曲",
                WorkshopApi.Ui.ToastType.Success
            )
            PluginLog.info("backup imported from ${file.absolutePath}")
            true
        } catch (failure: Throwable) {
            PluginLog.error("backup import failed", failure)
            WorkshopApi.ui.toast(
                "导入失败：${failure.message ?: "文件格式不正确"}",
                WorkshopApi.Ui.ToastType.Error
            )
            JOptionPane.showMessageDialog(
                null,
                failure.message ?: "文件格式不正确",
                "导入失败",
                JOptionPane.ERROR_MESSAGE
            )
            false
        }
    }

    fun merge(host: StatsData): StatsData {
        val file = importedFile()
        if (!Files.isRegularFile(file)) return host
        return try {
            val root = Json.parseToJsonElement(Files.readString(file, StandardCharsets.UTF_8)).jsonObject
            if (root["format"]?.jsonPrimitive?.contentOrNull != FORMAT) return host
            val daily = host.dailyMillis.toMutableMap()
            val hourly = host.hourlyMillis.toMutableMap()
            val tracks = host.tracks.toMutableMap()
            val counts = host.playCounts.toMutableMap()
            root["records"]?.jsonArray?.forEach { element ->
                val record = element.jsonObject
                val date = LocalDate.parse(record["date"]!!.jsonPrimitive.content)
                val millis = record["ms"]?.jsonPrimitive?.long ?: 0L
                if (millis > (daily[date] ?: 0L)) {
                    daily[date] = millis
                    val values = LongArray(24)
                    record["hours"]?.jsonArray?.forEachIndexed { index, value ->
                        if (index in 0 until 24) values[index] = value.jsonPrimitive.long
                    }
                    hourly[date] = values
                }
            }
            root["tracks"]?.jsonArray?.forEach { element ->
                val track = element.jsonObject
                val id = track["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                tracks.getOrPut(id) {
                    TrackMeta(
                        id = id,
                        title = track["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        artist = track["artist"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        album = track["album"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        albumArtist = track["albumArtist"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        durationMs = track["durationMs"]?.jsonPrimitive?.long ?: 0L
                    )
                }
                val count = track["count"]?.jsonPrimitive?.int ?: 0
                if (count > (counts[id] ?: 0)) counts[id] = count
            }
            host.copy(
                dailyMillis = daily,
                hourlyMillis = hourly,
                tracks = tracks,
                playCounts = counts,
                loadedAt = System.currentTimeMillis()
            )
        } catch (failure: Throwable) {
            PluginLog.error("merging imported backup failed", failure)
            host
        }
    }

    private fun validate(records: JsonArray, tracks: JsonArray): Pair<Int, Int> {
        var days = 0
        records.forEach { element ->
            val record = element.jsonObject
            val date = LocalDate.parse(record["date"]!!.jsonPrimitive.content)
            val millis = record["ms"]!!.jsonPrimitive.long
            require(millis in 0..86_400_000L) { "$date 的听歌时长不合法" }
            val hours = record["hours"]?.jsonArray
            if (hours != null) {
                require(hours.size == 24) { "$date 的小时数据不完整" }
                require(hours.sumOf { it.jsonPrimitive.long } == millis) {
                    "$date 的小时数据与每日汇总不一致"
                }
            }
            days++
        }
        var trackCount = 0
        tracks.forEach { element ->
            val track = element.jsonObject
            require(!track["id"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) { "歌曲条目不完整" }
            val count = track["count"]?.jsonPrimitive?.int ?: 0
            require(count >= 0) { "歌曲播放次数不合法" }
            trackCount++
        }
        return days to trackCount
    }

    private fun checksum(records: JsonArray, tracks: JsonArray): String {
        val payload = records.toString() + "\n" + tracks.toString()
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(payload.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }
    }

    private fun importedFile(): Path {
        val pluginPath = PluginState.pluginPath
        if (pluginPath != null) {
            val dir = Path.of(pluginPath).parent?.parent?.parent
            if (dir != null) {
                return dir.resolve("workshop").resolve("data").resolve(PluginState.pluginId)
                    .resolve("imported.json")
            }
        }
        val appData = System.getenv("APPDATA")
        return Path.of(appData ?: System.getProperty("java.io.tmpdir"))
            .resolve("Salt Player for Windows/workshop/data/${PluginState.pluginId}/imported.json")
    }
}
