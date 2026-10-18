package com.jethuit.spw.listenstats.data

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Splits one listening interval into local date/hour buckets. */
object TimeSplitter {
    fun split(
        startTimeMs: Long,
        durationMs: Long,
        zone: ZoneId,
        daily: MutableMap<LocalDate, Long>,
        hourly: MutableMap<LocalDate, LongArray>
    ) {
        if (durationMs <= 0) return
        var cursor = Instant.ofEpochMilli(startTimeMs).atZone(zone)
        val end = cursor.plusNanos(durationMs * 1_000_000L)
        while (cursor.isBefore(end)) {
            val nextHour = cursor.truncatedTo(ChronoUnit.HOURS).plusHours(1)
            val segmentEnd = if (end.isBefore(nextHour)) end else nextHour
            val millis = Duration.between(cursor, segmentEnd).toMillis()
            if (millis > 0) {
                val date = cursor.toLocalDate()
                daily[date] = (daily[date] ?: 0L) + millis
                val hours = hourly.getOrPut(date) { LongArray(24) }
                hours[cursor.hour] += millis
            }
            cursor = segmentEnd
        }
    }
}
