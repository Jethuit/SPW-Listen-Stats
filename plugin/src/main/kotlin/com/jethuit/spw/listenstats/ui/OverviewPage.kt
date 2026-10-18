package com.jethuit.spw.listenstats.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.jethuit.spw.listenstats.ui.StatsText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jethuit.spw.listenstats.data.ChartType
import com.jethuit.spw.listenstats.data.Granularity
import com.jethuit.spw.listenstats.data.RangePreset
import com.jethuit.spw.listenstats.data.StatsCalculations
import com.jethuit.spw.listenstats.data.StatsData
import com.jethuit.spw.listenstats.data.StatsSummary
import com.jethuit.spw.listenstats.util.durationParts
import com.jethuit.spw.listenstats.util.formatDay
import com.jethuit.spw.listenstats.util.formatDuration
import com.jethuit.spw.listenstats.util.formatHourRange
import com.jethuit.spw.listenstats.util.formatMonthDay
import java.time.LocalDate

@Composable
fun OverviewPage(
    data: StatsData,
    summary: StatsSummary,
    today: LocalDate,
    year: Int,
    weekly: Boolean,
    onYearSelected: (Int) -> Unit,
    onWeeklySelected: (Boolean) -> Unit,
    selectedDate: LocalDate?,
    rangePreset: RangePreset,
    customStart: LocalDate?,
    customEnd: LocalDate?,
    chartType: ChartType,
    userGranularity: Granularity,
    onDaySelected: (LocalDate) -> Unit,
    onWeekSelected: (LocalDate, LocalDate) -> Unit,
    onRangePreset: (RangePreset) -> Unit,
    onCustomRange: () -> Unit,
    onChartType: (ChartType) -> Unit,
    onGranularity: (Granularity) -> Unit
) {
    val colors = LocalStatsColors.current
    val years = remember(data, today) { StatsCalculations.years(data, today) }
    val yearTotal = remember(data, year) {
        data.dailyMillis.filterKeys { it.year == year }.values.sum()
    }
    val yearActiveDays = remember(data, year) {
        data.dailyMillis.count { (date, millis) -> date.year == year && millis > 0 }
    }
    val range = StatsCalculations.range(rangePreset, today, selectedDate, customStart, customEnd)
    val effectiveGranularity = if (range.days < 14) Granularity.Day else userGranularity
    val buckets = remember(data, range, effectiveGranularity) {
        StatsCalculations.chartBuckets(data, range, effectiveGranularity)
    }
    val rangeTotal = buckets.sumOf { it.millis }
    val maxBucket = buckets.maxOfOrNull { it.millis } ?: 0L
    val unitName = if (maxBucket >= 3_600_000L) "小时" else "分钟"

    Column(Modifier.fillMaxWidth()) {
        SummaryStrip(4) { itemWidth ->
            SummaryStat(itemWidth, "累计听歌时长", summary.totalMillis, colors)
            PlainStat(itemWidth, "${summary.activeDays}", "天", "听歌天数", colors)
            PlainStat(itemWidth, "${summary.longestStreak}", "天", "最长连续", colors)
            PlainStat(itemWidth, "${summary.currentStreak}", "天", "当前连续", colors)
        }

        Box(Modifier.height(43.dp))

        SectionHeading(
            icon = IconPaths.CalendarMonth,
            title = "听歌热力图",
            caption = "$year 年 · $yearActiveDays 个听歌日 · ${formatDuration(yearTotal)}"
        ) {
            SegmentedControl(
                options = listOf(
                    SegmentOption("day", "每天"),
                    SegmentOption("week", "每周")
                ),
                selected = if (weekly) "week" else "day",
                onSelected = { onWeeklySelected(it == "week") }
            )
            SelectBox(
                label = "${year}年",
                options = years.map { it.toString() to "${it}年" },
                onSelected = { onYearSelected(it.toIntOrNull() ?: year) },
                minWidth = 78.dp
            )
        }
        Box(Modifier.height(23.dp))
        YearHeatmap(
            data = data,
            year = year,
            weekly = weekly,
            today = today,
            selectedDate = selectedDate,
            onDaySelected = onDaySelected,
            onWeekSelected = onWeekSelected
        )

        Box(Modifier.height(43.dp))

        SectionHeading(
            icon = IconPaths.ShowChart,
            title = "时长分布",
            caption = "这段时间听了 ${formatDuration(rangeTotal)}",
            controlsGap = 9.dp
        ) {
            SegmentedControl(
                options = listOf(
                    SegmentOption("bar", icon = IconPaths.BarChart, tooltip = "柱状图"),
                    SegmentOption("line", icon = IconPaths.ShowChart, tooltip = "折线图"),
                    SegmentOption("curve", icon = IconPaths.MultilineChart, tooltip = "曲线图")
                ),
                selected = chartType.storage,
                onSelected = { key ->
                    ChartType.entries.firstOrNull { it.storage == key }?.let(onChartType)
                },
                iconOnly = true
            )
            SelectBox(
                label = rangeLabel(rangePreset, selectedDate, customStart, customEnd),
                options = rangeOptions(selectedDate),
                onSelected = { key ->
                    if (key == RangePreset.Custom.storage) {
                        onCustomRange()
                    } else {
                        RangePreset.entries.firstOrNull { it.storage == key }?.let(onRangePreset)
                    }
                }
            )
            if (range.days >= 14) {
                SelectBox(
                    label = if (effectiveGranularity == Granularity.Week) "按周" else "按日",
                    options = listOf(
                        Granularity.Day.storage to "按日",
                        Granularity.Week.storage to "按周"
                    ),
                    onSelected = { key ->
                        Granularity.entries.firstOrNull { it.storage == key }?.let(onGranularity)
                    }
                )
            }
        }
        Box(Modifier.height(23.dp))
        Text(
            if (unitName == "小时") "时长/h" else "时长/min",
            style = AppType.tiny.copy(color = colors.muted)
        )
        Box(Modifier.height(7.dp))
        TrendChart(
            buckets = buckets,
            chartType = chartType,
            showAverage = range.days > 1,
            hourly = range.days == 1
        )

        Box(Modifier.height(61.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(IconPaths.Lightbulb, colors.muted, iconSize = 19.dp)
            Text("听歌小结", style = AppType.sectionTitle.copy(color = colors.text))
        }
        Box(Modifier.height(16.dp))
        SummaryStrip(3) { itemWidth ->
            InsightStat(
                width = itemWidth,
                value = summary.peakMillis,
                fallback = "—",
                label = "听得最多 · " + (summary.peakDay?.let { formatDay(it) } ?: "—"),
                colors = colors
            )
            InsightStat(
                width = itemWidth,
                value = summary.dailyAverageMillis,
                fallback = "—",
                label = "每日平均收听",
                colors = colors
            )
            FavoriteHourStat(
                width = itemWidth,
                hour = summary.favoriteHour,
                label = "最常听歌时段",
                colors = colors
            )
        }
    }
}

@Composable
private fun SummaryStat(
    width: Dp,
    label: String,
    millis: Long,
    colors: StatsColors
) {
    Column(
        Modifier.width(width).padding(horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        DurationValue(millis, colors)
        Text(
            label,
            modifier = Modifier.padding(top = 5.dp),
            style = AppType.bodySmall.copy(color = colors.muted)
        )
    }
}

@Composable
private fun PlainStat(
    width: Dp,
    value: String,
    unit: String,
    label: String,
    colors: StatsColors
) {
    Column(
        Modifier.width(width).padding(horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = AppType.number.copy(color = colors.text))
            Text(
                unit,
                modifier = Modifier.padding(start = 3.dp, bottom = 4.dp),
                style = AppType.numberSmall.copy(color = colors.muted)
            )
        }
        Text(
            label,
            modifier = Modifier.padding(top = 5.dp),
            style = AppType.bodySmall.copy(color = colors.muted)
        )
    }
}

@Composable
private fun DurationValue(millis: Long, colors: StatsColors) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) {
        durationParts(millis).forEachIndexed { index, part ->
            if (index > 0) Box(Modifier.width(2.dp))
            Text(part.value, style = AppType.number.copy(color = colors.text))
            Text(
                part.unit,
                modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
                style = AppType.numberSmall.copy(color = colors.muted)
            )
        }
    }
}

@Composable
private fun InsightStat(
    width: Dp,
    value: Long,
    fallback: String,
    label: String,
    colors: StatsColors
) {
    Column(
        Modifier.width(width).padding(horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (value <= 0) {
            Text(fallback, style = AppType.number.copy(color = colors.muted))
        } else {
            DurationValue(value, colors)
        }
        Text(
            label,
            modifier = Modifier.padding(top = 5.dp),
            style = AppType.bodySmall.copy(color = colors.muted)
        )
    }
}

@Composable
private fun FavoriteHourStat(
    width: Dp,
    hour: Int?,
    label: String,
    colors: StatsColors
) {
    Column(
        Modifier.width(width).padding(horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            hour?.let { formatHourRange(it) } ?: "—",
            style = AppType.number.copy(color = colors.text)
        )
        Text(
            label,
            modifier = Modifier.padding(top = 5.dp),
            style = AppType.bodySmall.copy(color = colors.muted)
        )
    }
}

private fun rangeOptions(selectedDate: LocalDate?): List<Pair<String, String>> {
    val result = ArrayList<Pair<String, String>>()
    RangePreset.entries.forEach { preset ->
        if (preset == RangePreset.Selected && selectedDate == null) return@forEach
        result.add(preset.storage to preset.label)
    }
    return result
}

private fun rangeLabel(
    preset: RangePreset,
    selectedDate: LocalDate?,
    customStart: LocalDate?,
    customEnd: LocalDate?
): String {
    return when (preset) {
        RangePreset.Selected -> formatDay(selectedDate ?: java.time.LocalDate.now())
        RangePreset.Custom -> {
            if (customStart != null && customEnd != null) {
                "${formatDay(customStart)}–${formatDay(customEnd)}"
            } else {
                val end = java.time.LocalDate.now()
                "${formatDay(end.minusDays(29))}–${formatDay(end)}"
            }
        }
        else -> preset.label
    }
}
