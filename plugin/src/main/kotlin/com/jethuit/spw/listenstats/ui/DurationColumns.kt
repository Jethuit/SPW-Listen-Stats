package com.jethuit.spw.listenstats.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jethuit.spw.listenstats.util.formatDurationSeconds
import com.jethuit.spw.listenstats.util.durationTableParts
import com.moriafly.salt.ui.LocalTextStyle

/** Shared by the table header and all visible rows; never measured from an individual row. */
internal data class DurationColumnWidths(val hours: Dp, val minutes: Dp, val seconds: Dp, val latinUnits: Boolean) {
    val total: Dp get() = hours + minutes + seconds
}

@Composable
internal fun rememberDurationColumnWidths(maxMillis: Long, style: TextStyle, latinUnits: Boolean): DurationColumnWidths {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val resolved = LocalTextStyle.current.merge(style).copy(fontFeatureSettings = "tnum")
    val hourDigits = (maxMillis.coerceAtLeast(0) / 3_600_000L).toString().length.coerceAtLeast(2)
    return remember(hourDigits, resolved, measurer, density.density, density.fontScale, latinUnits) {
        // Also reserve for proportional digits when a custom font does not support tabular figures.
        fun width(digits: Int, unit: String): Dp {
            val pixels = (0..9).maxOf { digit -> measurer.measure(digit.toString().repeat(digits) + unit, resolved).size.width }
            return with(density) { pixels.toDp() }
        }
        DurationColumnWidths(width(hourDigits, if (latinUnits) "h" else "小时"),
            width(2, if (latinUnits) "m" else "分"), width(2, if (latinUnits) "s" else "秒"), latinUnits)
    }
}

@Composable
internal fun DurationColumns(millis: Long, columns: DurationColumnWidths, style: TextStyle,
                             omitZeroMinutes: Boolean = false) {
    val parts = durationTableParts(millis, columns.latinUnits, omitZeroMinutes)
    val numberStyle = style.copy(fontFeatureSettings = "tnum")
    val semantics = Modifier.clearAndSetSemantics {
        contentDescription = formatDurationSeconds(millis, columns.latinUnits, omitZeroMinutes)
    }
    if (millis in 1L..999L) {
        StatsText(parts.seconds, semantics, numberStyle, maxLines = 1, textAlign = TextAlign.End)
        return
    }
    // Parents align this group to the trailing edge. Empty fields consume no internal gap;
    // remaining minute/second fields retain the same shared widths and right-hand anchors.
    val width = (if (parts.hours.isEmpty()) 0.dp else columns.hours) +
        (if (parts.minutes.isEmpty()) 0.dp else columns.minutes) + columns.seconds
    Row(semantics.width(width), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
        if (parts.hours.isNotEmpty()) {
            StatsText(parts.hours, Modifier.width(columns.hours), numberStyle, maxLines = 1, textAlign = TextAlign.End)
        }
        if (parts.minutes.isNotEmpty()) {
            StatsText(parts.minutes, Modifier.width(columns.minutes), numberStyle, maxLines = 1, textAlign = TextAlign.End)
        }
        StatsText(parts.seconds, Modifier.width(columns.seconds), numberStyle, maxLines = 1, textAlign = TextAlign.End)
    }
}
