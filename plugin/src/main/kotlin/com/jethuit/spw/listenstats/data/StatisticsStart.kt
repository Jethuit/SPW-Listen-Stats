package com.jethuit.spw.listenstats.data

import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.time.Instant
import java.time.ZoneId

/** Local reporting boundary. Source records and portable backups on disk remain intact. */
internal object StatisticsStart {
    private fun file() = DataFiles.directory().resolve("statistics-start.txt")

    fun read(): Long = try {
        Files.readString(file()).trim().toLong().also { require(it > 0) }
    } catch (_: NoSuchFileException) { 0L }

    fun save(time: Long) {
        require(time > 0)
        DataFiles.writeAtomic(file(), time.toString())
    }
}

internal fun ListeningEvent.since(start: Long): ListeningEvent? {
    if (start == 0L || spans.all { it.startMs >= start }) return this
    fun clip(span: ListeningSpan): ListeningSpan? {
        if (span.startMs >= start) return span
        val remaining = span.durationMs - (start - span.startMs)
        return if (remaining > 0) ListeningSpan(start, remaining) else null
    }
    val remaining = spans.mapNotNull(::clip)
    if (remaining.isEmpty()) return null
    return copy(spans = remaining, preciseSpans = preciseSpans?.mapNotNull(::clip))
}

internal fun LegacyTotals.since(start: Long): LegacyTotals {
    if (start == 0L) return this
    // Old play counts have no timestamps. Only whole, unambiguously later hours qualify.
    val boundary = Instant.ofEpochMilli(start)
    val zone = ZoneId.systemDefault()
    val day = boundary.atZone(zone).toLocalDate()
    val later = hours.filterKeys { it > day }.toMutableMap()
    hours[day]?.let { values ->
        val kept = values.copyOf()
        for (hour in kept.indices) {
            if (day.atTime(hour, 0).atZone(zone).toInstant() < boundary) kept[hour] = 0
        }
        if (kept.any { it > 0 }) later[day] = kept
    }
    return LegacyTotals(hours = later)
}
