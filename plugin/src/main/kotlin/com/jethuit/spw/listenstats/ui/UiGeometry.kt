package com.jethuit.spw.listenstats.ui

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

data class TrendAxis(val useHours: Boolean, val maximum: Double) {
    val unitMillis: Double get() = if (useHours) 3_600_000.0 else 60_000.0
    val caption: String get() = if (useHours) "时长/h" else "时长/min"
}

/** Coordinates are pixels in the popup window; pointer coordinates are relative to its anchor. */
object UiGeometry {
    fun trendAxis(maxMillis: Long, hourly: Boolean): TrendAxis {
        // Each single-day bucket represents one hour, so keep its full 60-minute scale.
        if (hourly) return TrendAxis(useHours = false, maximum = 60.0)
        val useHours = maxMillis >= 3_600_000L
        val value = maxMillis / if (useHours) 3_600_000.0 else 60_000.0
        if (value <= 0.0) return TrendAxis(useHours, 4.0)
        val padded = value * 1.12
        val power = 10.0.pow(floor(log10(padded)))
        val fraction = padded / power
        val nice = when {
            fraction <= 1.0 -> 1.0
            fraction <= 2.0 -> 2.0
            fraction <= 5.0 -> 5.0
            else -> 10.0
        }
        return TrendAxis(useHours, nice * power)
    }

    fun popupAxis(anchor: Int, pointer: Int, popup: Int, window: Int, gap: Int): Int {
        val desired = anchor + pointer + gap
        val flipped = anchor + pointer - popup - gap
        val max = (window - popup - gap).coerceAtLeast(0)
        return (if (desired > max) flipped else desired).coerceIn(0, max)
    }

    fun tick(value: Double, step: Double): String {
        val digits = BigDecimal.valueOf(step.coerceAtLeast(0.000001)).stripTrailingZeros().scale()
        return BigDecimal.valueOf(value).setScale(digits.coerceIn(0, 6), RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString()
    }
}
