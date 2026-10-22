package com.jethuit.spw.listenstats.data

import java.time.LocalDate
import kotlin.math.floor

/** Relative display scale. Zero days never participate in the positive-duration quartiles. */
data class HeatmapScale private constructor(val thresholds: List<Double>) {
    fun level(millis: Long): Int = when {
        millis <= 0L -> 0
        thresholds.isEmpty() -> 1 // Old history without any recent sample still has a visible color.
        else -> 1 + thresholds.count { millis.toDouble() > it }
    }

    companion object {
        fun fromSamples(samples: Collection<Long>): HeatmapScale {
            val sorted = samples.filter { it > 0L }.sorted()
            if (sorted.isEmpty()) return HeatmapScale(emptyList())
            val thresholds = (1..3).map { quartile ->
                val index = (sorted.size - 1) * quartile / 4.0
                val lower = floor(index).toInt()
                val upper = minOf(lower + 1, sorted.lastIndex)
                sorted[lower].toDouble() + (sorted[upper] - sorted[lower]).toDouble() * (index - lower)
            }
            return HeatmapScale(thresholds)
        }

        private fun recent(daily: Map<LocalDate, Long>, today: LocalDate): Map<LocalDate, Long> {
            val start = today.minusYears(1).plusDays(1)
            return daily.filter { (date, millis) -> date in start..today && millis > 0L }
        }

        fun daily(daily: Map<LocalDate, Long>, today: LocalDate): HeatmapScale =
            fromSamples(recent(daily, today).values)

        fun weekly(daily: Map<LocalDate, Long>, today: LocalDate): HeatmapScale {
            val totals = mutableMapOf<LocalDate, Long>()
            for ((date, millis) in recent(daily, today)) {
                val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
                totals[monday] = (totals[monday] ?: 0L) + millis
            }
            return fromSamples(totals.values)
        }
    }
}
