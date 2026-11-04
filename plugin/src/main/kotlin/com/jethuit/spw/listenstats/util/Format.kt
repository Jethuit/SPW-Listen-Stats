package com.jethuit.spw.listenstats.util

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Round the final display total once; stored durations and chart values stay in milliseconds. */
private fun roundedDisplayMinutes(millis: Long): Long =
    if (millis <= 0) 0L else millis / 60_000L + if (millis % 60_000L >= 30_000L) 1L else 0L

fun formatDuration(millis: Long, latinUnits: Boolean = false): String {
    if (millis <= 0) return if (latinUnits) "0m" else "0 分钟"
    val totalMinutes = roundedDisplayMinutes(millis)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    if (latinUnits) return when {
        hours > 0 && minutes > 0 -> "${hours}h${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
    return when {
        hours > 0 && minutes > 0 -> "${hours}小时${minutes}分钟"
        hours > 0 -> "${hours}小时"
        else -> "${minutes}分钟"
    }
}

/** Details show completed seconds; subsecond records remain distinguishable from zero. */
fun formatDurationSeconds(millis: Long, latinUnits: Boolean = false, omitZeroMinutes: Boolean = false): String {
    if (millis <= 0L) return if (latinUnits) "0s" else "0秒"
    if (millis < 1_000L) return if (latinUnits) "<1s" else "不足1秒"
    val totalSeconds = millis / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds / 60L % 60L
    val seconds = totalSeconds % 60L
    if (hours > 0 && minutes == 0L && omitZeroMinutes) {
        return if (latinUnits) "${hours}h${seconds}s" else "${hours}小时${seconds}秒"
    }
    if (latinUnits) return when {
        hours > 0L -> "${hours}h${minutes}m${seconds}s"
        minutes > 0L -> "${minutes}m${seconds}s"
        else -> "${seconds}s"
    }
    return when {
        hours > 0L -> "${hours}小时${minutes}分${seconds}秒"
        minutes > 0L -> "${minutes}分${seconds}秒"
        else -> "${seconds}秒"
    }
}

/** Table-only fields: empty hour cell below one hour, zero-padded minutes/seconds, no rounding. */
data class DurationTableParts(val hours: String, val minutes: String, val seconds: String)

fun durationTableParts(millis: Long, latinUnits: Boolean, omitZeroMinutes: Boolean = false): DurationTableParts {
    if (millis in 1L..999L) return DurationTableParts("", "", if (latinUnits) "<1s" else "不足1秒")
    val seconds = millis.coerceAtLeast(0L) / 1_000L
    val hours = seconds / 3_600L
    val minutes = seconds / 60L % 60L
    return DurationTableParts(
        if (hours > 0) "$hours${if (latinUnits) "h" else "小时"}" else "",
        if (omitZeroMinutes && minutes == 0L) "" else minutes.toString().padStart(2, '0') + if (latinUnits) "m" else "分",
        (seconds % 60L).toString().padStart(2, '0') + if (latinUnits) "s" else "秒"
    )
}

fun formatDurationCompact(millis: Long, latinUnits: Boolean = false): Pair<String, String> {
    val hourUnit = if (latinUnits) "h" else "小时"
    val minuteUnit = if (latinUnits) "m" else "分钟"
    if (millis <= 0) return "0" to minuteUnit
    val totalMinutes = roundedDisplayMinutes(millis)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}.${(minutes * 10 / 60)}" to hourUnit
        hours > 0 -> "$hours" to hourUnit
        else -> "$minutes" to minuteUnit
    }
}

data class DurationPart(val value: String, val unit: String)

fun durationParts(millis: Long, latinUnits: Boolean = false): List<DurationPart> {
    if (millis <= 0) return listOf(DurationPart("0", if (latinUnits) "m" else "分钟"))
    val totalMinutes = roundedDisplayMinutes(millis)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    if (hours > 0) {
        val result = ArrayList<DurationPart>(2)
        result.add(DurationPart(formatNumber(hours), if (latinUnits) "h" else "小时"))
        if (minutes > 0) result.add(DurationPart(minutes.toString(), if (latinUnits) "m" else "分"))
        return result
    }
    return listOf(DurationPart(totalMinutes.toString(), if (latinUnits) "m" else "分钟"))
}

fun formatDay(date: LocalDate): String =
    DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA).format(date)

fun formatMonthDay(date: LocalDate): String =
    DateTimeFormatter.ofPattern("M月d日", Locale.CHINA).format(date)

fun formatDateShort(date: LocalDate): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINA).format(date)

fun formatHourRange(hour: Int): String =
    String.format(Locale.CHINA, "%02d:00 — %02d:00", hour, (hour + 1) % 24)

fun formatNumber(value: Long): String = String.format(Locale.CHINA, "%,d", value)
