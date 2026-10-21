package com.jethuit.spw.listenstats.data

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** v1 totals have no event identity. They remain a separately labelled lower-bound overlay. */
data class LegacyTotals(
    val hours: Map<LocalDate, LongArray> = emptyMap(),
    val tracks: Map<String, TrackMeta> = emptyMap(),
    val counts: Map<String, Int> = emptyMap()
)
data class BackupBundle(val events: List<ListeningEvent>, val legacy: LegacyTotals = LegacyTotals())

object BackupCodec {
    private const val FORMAT = "spw-listen-stats-backup"
    const val MAX_BYTES = 32L * 1024 * 1024
    private const val MAX_TIME = 253402300799000L

    fun read(path: Path): BackupBundle {
        require(Files.size(path) <= MAX_BYTES) { "备份超过 32 MB 限制" }
        Files.newInputStream(path).use {
            val bytes = it.readNBytes((MAX_BYTES + 1).toInt())
            require(bytes.size <= MAX_BYTES) { "备份超过 32 MB 限制" }
            return decode(bytes.toString(Charsets.UTF_8))
        }
    }

    fun decode(text: String): BackupBundle {
        require(text.toByteArray().size <= MAX_BYTES) { "备份过大" }
        val root = Json.parseToJsonElement(text).jsonObject
        require(root["format"]?.jsonPrimitive?.content == FORMAT) { "不是听歌统计备份" }
        val version = root["version"]!!.jsonPrimitive.int
        val expected = root["checksum"]?.jsonPrimitive?.content
        require(expected != null) { "备份缺少校验和" }
        if (version == 1) {
            val records = root["records"]!!.jsonArray
            val tracks = root["tracks"]!!.jsonArray
            require(expected == hash("$records\n$tracks")) { "备份校验失败" }
            return BackupBundle(emptyList(), parseLegacy(records, tracks))
        }
        require(version == 2) { "备份版本不受支持" }
        val payload = root["payload"]!!.jsonObject
        require(expected == hash(payload.toString())) { "备份校验失败" }
        val events = payload["events"]!!.jsonArray
        require(events.size <= 100_000) { "会话数量过多" }
        val ids = hashSetOf<String>()
        val parsed = events.map { element ->
            val obj = element.jsonObject
            val id = obj.string("id")
            require(id.isNotBlank() && ids.add(id)) { "会话 ID 重复或为空" }
            val library = obj.string("library")
            java.util.UUID.fromString(library)
            val track = parseTrack(obj["track"]!!.jsonObject)
            fun parseSpans(array: JsonArray): List<ListeningSpan> {
                require(array.size <= 100_000)
                val spans = array.map {
                    val pair = it.jsonArray
                    require(pair.size == 2)
                    val start = pair[0].jsonPrimitive.long
                    val duration = pair[1].jsonPrimitive.long
                    require(start in 0..MAX_TIME && duration in 1..604_800_000L && start <= MAX_TIME - duration) {
                        "收听片段时间不合法"
                    }
                    ListeningSpan(start, duration)
                }
                require(spans.zipWithNext().all { (a, b) -> a.startMs + a.durationMs <= b.startMs }) {
                    "同一会话的收听片段重叠或顺序错误"
                }
                return spans
            }
            val spans = parseSpans(obj["spans"]!!.jsonArray)
            val precise = obj["preciseSpans"]?.jsonArray?.let { parseSpans(it) }
            val aliases = obj["aliases"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
            require(aliases.size <= 100 && aliases.all { it.isNotBlank() && it.length <= 500 })
            ListeningEvent(id, track, spans, aliases, obj["approximate"]!!.jsonPrimitive.boolean, library, precise)
        }
        val legacy = payload["legacy"]!!.jsonObject
        return BackupBundle(parsed, parseLegacy(legacy["records"]!!.jsonArray, legacy["tracks"]!!.jsonArray))
    }

    fun encode(bundle: BackupBundle): String {
        val payload = buildJsonObject {
            put("events", buildJsonArray { ListeningEvents.union(bundle.events).forEach { event ->
                add(buildJsonObject {
                    put("id", event.id); put("library", event.libraryId)
                    put("track", trackJson(event.track)); put("approximate", event.approximate)
                    put("aliases", buildJsonArray { event.aliases.sorted().forEach { add(JsonPrimitive(it)) } })
                    put("spans", buildJsonArray { event.spans.forEach {
                        add(buildJsonArray { add(JsonPrimitive(it.startMs)); add(JsonPrimitive(it.durationMs)) })
                    } })
                    event.preciseSpans?.let { spans ->
                        put("preciseSpans", buildJsonArray { spans.forEach {
                            add(buildJsonArray { add(JsonPrimitive(it.startMs)); add(JsonPrimitive(it.durationMs)) })
                        } })
                    }
                })
            } })
            put("legacy", buildJsonObject {
                put("records", buildJsonArray { bundle.legacy.hours.toSortedMap().forEach { (date, hours) ->
                    add(buildJsonObject {
                        put("date", date.toString()); put("ms", hours.sum())
                        put("hours", buildJsonArray { hours.forEach { add(JsonPrimitive(it)) } })
                    })
                } })
                put("tracks", buildJsonArray { bundle.legacy.tracks.forEach { (id, track) ->
                    add(JsonObject(trackJson(track) + ("count" to JsonPrimitive(bundle.legacy.counts[id] ?: 0))))
                } })
            })
        }
        return buildJsonObject {
            put("format", FORMAT); put("version", 2)
            put("exportedAt", Instant.now().toString()); put("timeZone", ZoneId.systemDefault().id)
            put("payload", payload); put("checksum", hash(payload.toString()))
        }.toString()
    }

    fun union(left: BackupBundle, right: BackupBundle) = BackupBundle(
        ListeningEvents.union(left.events + right.events), mergeLegacy(left.legacy, right.legacy))

    fun apply(data: StatsData, bundle: BackupBundle): StatsData {
        val merged = if (bundle.events.isEmpty()) data
            else ListeningEvents.aggregate(data, data.events + bundle.events)
        return applyLegacy(merged, bundle.legacy)
    }

    /** Apply the v1 lower bound only after the exact/host event union is aggregated. */
    fun applyLegacy(merged: StatsData, legacy: LegacyTotals): StatsData {
        if (legacy.hours.isEmpty() && legacy.tracks.isEmpty() && legacy.counts.isEmpty()) return merged
        val base = LegacyTotals(merged.hourlyMillis, merged.tracks, merged.playCounts)
        val totals = mergeLegacy(base, legacy)
        return merged.copy(hourlyMillis = totals.hours, dailyMillis = totals.hours.mapValues { it.value.sum() },
            tracks = totals.tracks, playCounts = totals.counts)
    }

    private fun mergeLegacy(a: LegacyTotals, b: LegacyTotals): LegacyTotals {
        val hours = a.hours.mapValues { it.value.copyOf() }.toMutableMap()
        b.hours.forEach { (date, incoming) ->
            val target = hours.getOrPut(date) { LongArray(24) }
            for (i in 0..23) target[i] = maxOf(target[i], incoming[i])
        }
        val tracks = b.tracks.toMutableMap()
        a.tracks.forEach { (id, track) ->
            tracks[id] = tracks[id]?.let { ListeningEvents.mergeTrack(track, it) } ?: track
        }
        val counts = a.counts.toMutableMap()
        b.counts.forEach { (id, count) -> counts[id] = maxOf(counts[id] ?: 0, count) }
        return LegacyTotals(hours, tracks, counts)
    }

    private fun parseLegacy(records: JsonArray, tracks: JsonArray): LegacyTotals {
        val hours = linkedMapOf<LocalDate, LongArray>()
        records.forEach { element ->
            val obj = element.jsonObject
            val date = LocalDate.parse(obj.string("date"))
            require(date.year in 1970..9999 && date !in hours) { "日期重复或无效" }
            val values = obj["hours"]!!.jsonArray
            require(values.size == 24) { "小时数据必须为 24 项" }
            val array = values.map { it.jsonPrimitive.long }.toLongArray()
            require(array.all { it in 0..86_400_000L }) { "小时数据不能为负或溢出" }
            require(array.sum() == obj["ms"]!!.jsonPrimitive.long) { "每日与小时数据不一致" }
            hours[date] = array
        }
        val metas = linkedMapOf<String, TrackMeta>()
        val counts = linkedMapOf<String, Int>()
        tracks.forEach { element ->
            val obj = element.jsonObject
            val track = parseTrack(obj)
            require(track.id !in metas) { "歌曲 ID 重复" }
            val count = obj["count"]!!.jsonPrimitive.int
            require(count >= 0) { "播放次数不能为负" }
            metas[track.id] = track; counts[track.id] = count
        }
        return LegacyTotals(hours, metas, counts)
    }

    private fun trackJson(track: TrackMeta) = buildJsonObject {
        put("id", track.id); put("title", track.title); put("artist", track.artist)
        put("album", track.album); put("albumArtist", track.albumArtist); put("durationMs", track.durationMs)
    }
    private fun parseTrack(obj: JsonObject): TrackMeta {
        val id = obj.string("id")
        val duration = obj["durationMs"]!!.jsonPrimitive.long
        require(id.isNotBlank() && duration in 0..604_800_000L) { "歌曲信息不合法" }
        return TrackMeta(id, obj.string("title"), obj.string("artist"), obj.string("album"),
            obj["albumArtist"]?.jsonPrimitive?.contentOrNull.orEmpty(), duration)
    }
    private fun JsonObject.string(key: String) = this[key]!!.jsonPrimitive.content.also {
        require(it.length <= 16_384) { "文本字段过长" }
    }
    fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
