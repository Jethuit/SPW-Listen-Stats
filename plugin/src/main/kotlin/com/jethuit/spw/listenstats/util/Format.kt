package com.jethuit.spw.listenstats.util

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Round the final display total once; stored durations and chart values stay in milliseconds. */
private fun roundedDisplayMinutes(millis: Long): Long =
    if (millis <= 0) 0L else millis / 60_000L + if (millis % 60_000L >= 30_000L) 1L else 0L

fun formatDuration(millis: Long): String {
    if (millis <= 0) return "0 分钟"
    val totalMinutes = roundedDisplayMinutes(millis)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}小时${minutes}分钟"
        hours > 0 -> "${hours}小时"
        else -> "${minutes}分钟"
    }
}

fun formatDurationCompact(millis: Long): Pair<String, String> {
    if (millis <= 0) return "0" to "分钟"
    val totalMinutes = roundedDisplayMinutes(millis)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}.${(minutes * 10 / 60)}" to "小时"
        hours > 0 -> "$hours" to "小时"
        else -> "$minutes" to "分钟"
    }
}

data class DurationPart(val value: String, val unit: String)

fun durationParts(millis: Long): List<DurationPart> {
    if (millis <= 0) return listOf(DurationPart("0", "分钟"))
    val totalMinutes = roundedDisplayMinutes(millis)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    if (hours > 0) {
        val result = ArrayList<DurationPart>(2)
        result.add(DurationPart(formatNumber(hours), "小时"))
        if (minutes > 0) result.add(DurationPart(minutes.toString(), "分"))
        return result
    }
    return listOf(DurationPart(totalMinutes.toString(), "分钟"))
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
