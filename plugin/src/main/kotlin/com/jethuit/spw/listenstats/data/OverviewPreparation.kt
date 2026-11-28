package com.jethuit.spw.listenstats.data

import com.jethuit.spw.listenstats.PageOptions
import java.time.LocalDate

data class OverviewSelection(
    val year: Int,
    val rolling: Boolean,
    val weekly: Boolean,
    val rangePreset: RangePreset,
    val selectedDate: LocalDate?,
    val customStart: LocalDate?,
    val customEnd: LocalDate?,
    val granularity: Granularity
) {
    internal constructor(today: LocalDate, options: PageOptions) : this(
        today.year, false, false, options.range, options.selectedDate, options.customStart,
        options.customEnd, options.granularity
    )
}

class HeatmapDisplay internal constructor(
    val grid: HeatmapGrid,
    val dailyScale: HeatmapScale,
    val weeks: List<WeeklyCell>,
    val totalMillis: Long,
    val activeDays: Int
)

class PreparedOverview private constructor(
    private val dataIdentity: Any,
    private val today: LocalDate,
    private val selection: OverviewSelection,
    val summary: StatsSummary,
    val years: List<Int>,
    val heatmap: HeatmapDisplay,
    val range: DateRange,
    val granularity: Granularity,
    val buckets: List<ChartBucket>,
    val rangeTotal: Long,
    val maxBucket: Long
) {
    internal companion object {
        fun prepare(data: StatsData, today: LocalDate, selection: OverviewSelection,
                    previous: PreparedOverview? = null): PreparedOverview {
            val cached = previous?.takeIf { it.dataIdentity === data.snapshotIdentity && it.today == today }
            if (cached != null && cached.selection == selection) return cached
            val grid = StatsCalculations.heatmapGrid(selection.year, selection.rolling, today)
            val heatmap = if (cached != null && cached.heatmap.grid == grid && cached.selection.weekly == selection.weekly) {
                cached.heatmap
            } else {
                prepareHeatmap(data, grid, today, selection.weekly, cached?.heatmap?.dailyScale)
            }
            val range = StatsCalculations.range(selection.rangePreset, today, selection.selectedDate,
                selection.customStart, selection.customEnd)
            val granularity = if (range.days < 14) Granularity.Day else selection.granularity
            val previousTrend = cached?.takeIf { it.range == range && it.granularity == granularity }
            val buckets = previousTrend?.buckets ?: StatsCalculations.chartBuckets(data, range, granularity)
            return PreparedOverview(data.snapshotIdentity, today, selection,
                cached?.summary ?: StatsCalculations.summary(data, today),
                cached?.years ?: StatsCalculations.years(data, today), heatmap, range, granularity, buckets,
                previousTrend?.rangeTotal ?: buckets.sumOf { it.millis },
                previousTrend?.maxBucket ?: (buckets.maxOfOrNull { it.millis } ?: 0L))
        }
    }
}

internal fun prepareHeatmap(data: StatsData, grid: HeatmapGrid, today: LocalDate, weekly: Boolean,
                            dailyScale: HeatmapScale? = null): HeatmapDisplay {
    var total = 0L
    var activeDays = 0
    var day = grid.firstDay
    while (!day.isAfter(grid.lastDay)) {
        val millis = data.millisOn(day)
        total += millis
        if (millis > 0) activeDays++
        day = day.plusDays(1)
    }
    return HeatmapDisplay(grid, dailyScale ?: HeatmapScale.daily(data.dailyMillis, today),
        if (weekly) StatsCalculations.weeklyCells(data, grid, today) else emptyList(), total, activeDays)
}
