package com.jethuit.spw.listenstats.data

import kotlin.math.roundToLong

/** A display preference only; never changes recorded duration or play counts. */
object HeatmapScale {
    const val PREFERENCE_KEY = "dailyHighHours"
    const val DEFAULT_HOURS = "2.25"
    const val DEFAULT_HIGH_MILLIS = 135L * 60_000L
    private const val HOUR_MILLIS = 3_600_000L
    private const val MAX_HIGH_MILLIS = 24L * HOUR_MILLIS

    fun parseHours(value: String?): Long {
        val hours = value?.trim()?.toDoubleOrNull() ?: return DEFAULT_HIGH_MILLIS
        if (!hours.isFinite() || hours <= 0.0 || hours > 24.0) return DEFAULT_HIGH_MILLIS
        val millis = (hours * HOUR_MILLIS).roundToLong()
        // Seven milliseconds is the smallest threshold with four distinct tiers.
        return if (millis in 7L..MAX_HIGH_MILLIS) millis else DEFAULT_HIGH_MILLIS
    }

    fun level(millis: Long, highMillis: Long = DEFAULT_HIGH_MILLIS): Int {
        val high = highMillis.takeIf { it in 7L..MAX_HIGH_MILLIS } ?: DEFAULT_HIGH_MILLIS
        return when {
            millis <= 0L -> 0
            millis < (high + 5L) / 6L -> 1
            millis < (high + 1L) / 2L -> 2
            millis < high -> 3
            else -> 4
        }
    }
}
