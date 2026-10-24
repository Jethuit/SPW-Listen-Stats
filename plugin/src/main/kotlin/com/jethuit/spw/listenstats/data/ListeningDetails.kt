package com.jethuit.spw.listenstats.data

import java.time.LocalDate
import java.time.ZoneId
import java.text.Collator
import java.util.Locale

data class ListeningDetailRow(val track: TrackMeta, val millis: Long, val approximateMillis: Long)

data class ListeningDetails(
    val bucket: ChartBucket,
    val rows: List<ListeningDetailRow>,
    val unknownMillis: Long
)

/** Read the already merged snapshot. Never infer dated tracks from legacy aggregate counts. */
object ListeningDetailsCalculator {
    fun calculate(
        data: StatsData,
        bucket: ChartBucket,
        zone: ZoneId = ZoneId.systemDefault(),
        localLibrary: String = DataFiles.sourceId()
    ): ListeningDetails {
        val startMs = bucket.start.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = bucket.end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        fun portion(spans: List<ListeningSpan>): Long = spans.sumOf { span ->
            val start = maxOf(span.startMs, startMs)
            val end = minOf(span.startMs + span.durationMs, endMs)
            if (end <= start) 0L
            else if (bucket.hour == null) end - start
            else {
                // Use the same local-hour splitting as the chart, including repeated DST hours.
                val daily = linkedMapOf<LocalDate, Long>()
                val hourly = linkedMapOf<LocalDate, LongArray>()
                TimeSplitter.split(start, end - start, zone, daily, hourly)
                hourly[bucket.start]?.get(bucket.hour) ?: 0L
            }
        }
        val totals = linkedMapOf<String, ListeningDetailRow>()
        for (event in data.events) {
            val millis = portion(event.spans)
            if (millis <= 0L) continue
            val id = if (event.libraryId == localLibrary) event.track.id else "${event.libraryId}:${event.track.id}"
            val track = data.tracks[id] ?: event.track.copy(id = id)
            val approximate = (millis - portion(event.observedSpans())).coerceAtLeast(0L)
            val old = totals[id]
            totals[id] = ListeningDetailRow(track, millis + (old?.millis ?: 0L),
                approximate + (old?.approximateMillis ?: 0L))
        }
        val collator = Collator.getInstance(Locale.CHINA)
        val rows = totals.values.sortedWith(compareByDescending<ListeningDetailRow> { it.millis }
            .thenComparator { a, b -> collator.compare(a.track.title, b.track.title) }.thenBy { it.track.id })
        return ListeningDetails(bucket, rows, (bucket.millis - rows.sumOf { it.millis }).coerceAtLeast(0L))
    }
}
