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
        var cursor = Instant.ofEpochMilli(startTimeMs)
        val end = cursor.plusMillis(durationMs)
        while (cursor.isBefore(end)) {
            val local = cursor.atZone(zone)
            // Use the current offset until the next transition. Resolving a truncated
            // ZonedDateTime in an overlap can otherwise move backwards (Lord Howe).
            val nextHour = local.toLocalDateTime().truncatedTo(ChronoUnit.HOURS)
                .plusHours(1).toInstant(local.offset)
            val transition = zone.rules.nextTransition(cursor)?.instant
            val boundary = if (transition != null && transition.isBefore(nextHour)) transition else nextHour
            check(boundary.isAfter(cursor)) { "hour boundary must advance on the instant timeline" }
            val segmentEnd = minOf(end, boundary)
            val millis = Duration.between(cursor, segmentEnd).toMillis()
            if (millis > 0) {
                val date = local.toLocalDate()
                daily[date] = (daily[date] ?: 0L) + millis
                val hours = hourly.getOrPut(date) { LongArray(24) }
                hours[local.hour] += millis
            }
            cursor = segmentEnd
        }
    }
}
