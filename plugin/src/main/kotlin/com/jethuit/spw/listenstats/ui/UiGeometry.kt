package com.jethuit.spw.listenstats.ui

import java.math.BigDecimal
import java.math.RoundingMode

/** Coordinates are pixels in the popup window; pointer coordinates are relative to its anchor. */
object UiGeometry {
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
