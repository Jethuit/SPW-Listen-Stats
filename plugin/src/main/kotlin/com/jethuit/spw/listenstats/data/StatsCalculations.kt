package com.jethuit.spw.listenstats.data

import com.jethuit.spw.listenstats.util.formatDay
import com.jethuit.spw.listenstats.util.formatMonthDay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

data class StatsSummary(
    val totalMillis: Long,
    val activeDays: Int,
    val longestStreak: Int,
    val currentStreak: Int,
    val peakDay: LocalDate?,
    val peakMillis: Long,
    val dailyAverageMillis: Long,
    val favoriteHour: Int?,
    val favoriteHourMillis: Long
)

data class HeatmapGrid(
    val year: Int,
    val gridStart: LocalDate,
    val weeks: Int,
    val firstDay: LocalDate,
    val lastDay: LocalDate,
    val rolling: Boolean = false
)

data class WeeklyCell(
    val weekIndex: Int,
    val start: LocalDate,
    val end: LocalDate,
    val millis: Long,
    val coveredDays: Int,
    val level: Int,
    val filledBlocks: Int
)

data class ChartBucket(
    val label: String,
    val longLabel: String,
    val millis: Long,
    val start: LocalDate,
    val end: LocalDate,
    val hour: Int?
)

enum class RangePreset(val storage: String, val label: String, val visibleWhenNoSelection: Boolean = true) {
    Last7("7", "近 7 天"),
    Last30("30", "近 30 天"),
    Month("month", "本月"),
    Today("today", "今天"),
    Selected("selected", "所选日期", visibleWhenNoSelection = false),
    Custom("custom", "自定义");

    companion object {
        fun fromStorage(value: String?): RangePreset =
            entries.firstOrNull { it.storage == value } ?: Today
    }
}

enum class ChartType(val storage: String) {
    Bar("bar"),
    Line("line"),
    Curve("curve");

    companion object {
        fun fromStorage(value: String?): ChartType =
            entries.firstOrNull { it.storage == value } ?: Bar
    }
}

enum class Granularity(val storage: String) {
    Day("day"),
    Week("week");

    companion object {
        fun fromStorage(value: String?): Granularity =
            entries.firstOrNull { it.storage == value } ?: Day
    }
}

object StatsCalculations {
    fun summary(data: StatsData, today: LocalDate): StatsSummary {
        val activeDates = data.dailyMillis.filterValues { it > 0 }.keys.sorted()
        val total = data.dailyMillis.values.sum()
        val longest = longestStreak(activeDates)
        val current = currentStreak(activeDates, today)
        val peakDate = activeDates.maxByOrNull { data.millisOn(it) }
        val peakMillis = peakDate?.let { data.millisOn(it) } ?: 0L
        val average = if (activeDates.isEmpty()) 0L else total / activeDates.size
        val hourTotals = LongArray(24)
        data.hourlyMillis.values.forEach { hours ->
            for (hour in 0 until 24) {
                hourTotals[hour] += hours[hour]
            }
        }
        var favoriteHour: Int? = null
        var favoriteMillis = 0L
        for (hour in 0 until 24) {
            if (hourTotals[hour] > favoriteMillis) {
                favoriteMillis = hourTotals[hour]
                favoriteHour = hour
            }
        }
        return StatsSummary(
            totalMillis = total,
            activeDays = activeDates.size,
            longestStreak = longest,
            currentStreak = current,
            peakDay = peakDate,
            peakMillis = peakMillis,
            dailyAverageMillis = average,
            favoriteHour = favoriteHour,
            favoriteHourMillis = favoriteMillis
        )
    }

    private fun longestStreak(dates: List<LocalDate>): Int {
        if (dates.isEmpty()) return 0
        var best = 1
        var current = 1
        for (index in 1 until dates.size) {
            current = if (dates[index] == dates[index - 1].plusDays(1)) current + 1 else 1
            if (current > best) best = current
        }
        return best
    }

    private fun currentStreak(dates: List<LocalDate>, today: LocalDate): Int {
        if (dates.isEmpty()) return 0
        val set = dates.toHashSet()
        var cursor = today
        if (!set.contains(cursor)) {
            cursor = today.minusDays(1)
            if (!set.contains(cursor)) return 0
        }
        var count = 0
        while (set.contains(cursor)) {
            count++
            cursor = cursor.minusDays(1)
        }
        return count
    }

    fun years(data: StatsData, today: LocalDate): List<Int> {
        val years = sortedSetOf<Int>()
        data.dailyMillis.keys.forEach { years.add(it.year) }
        years.add(today.year)
        return years.toList().sortedDescending()
    }

    fun heatmapGrid(year: Int, rolling: Boolean = false, today: LocalDate = LocalDate.now()): HeatmapGrid {
        val first: LocalDate
        val last: LocalDate
        if (rolling) {
            first = today.minusDays(364)
            last = today
        } else {
            first = LocalDate.of(year, 1, 1)
            last = LocalDate.of(year, 12, 31)
        }
        val start = first.minusDays((first.dayOfWeek.value - 1).toLong())
        val end = last.plusDays((7 - last.dayOfWeek.value).toLong())
        val weeks = (ChronoUnit.DAYS.between(start, end) + 1).toInt() / 7
        return HeatmapGrid(year, start, weeks, first, last, rolling)
    }

    fun dayLevel(millis: Long, today: LocalDate, date: LocalDate,
                 scale: HeatmapScale): Int {
        if (date.isAfter(today)) return 0
        return scale.level(millis)
    }

    fun weeklyCells(data: StatsData, grid: HeatmapGrid, today: LocalDate = LocalDate.now()): List<WeeklyCell> {
        val scale = HeatmapScale.weekly(data.dailyMillis, today)
        val totals = LongArray(grid.weeks)
        for (week in 0 until grid.weeks) {
            val start = grid.gridStart.plusDays(week * 7L)
            var total = 0L
            for (offset in 0L until 7L) {
                val date = start.plusDays(offset)
                if (date.isBefore(grid.firstDay) || date.isAfter(grid.lastDay) || date.isAfter(today)) continue
                total += data.millisOn(date)
            }
            totals[week] = total
        }
        val max = totals.maxOrNull() ?: 0L
        return (0 until grid.weeks).map { week ->
            val start = grid.gridStart.plusDays(week * 7L)
            val rawEnd = start.plusDays(6)
            val coveredStart = if (start.isBefore(grid.firstDay)) grid.firstDay else start
            val coveredEnd = if (rawEnd.isAfter(grid.lastDay)) grid.lastDay else rawEnd
            val total = totals[week]
            val ratio = if (max <= 0) 0.0 else total.toDouble() / max
            val level = scale.level(total)
            val filled = if (total <= 0) 0 else kotlin.math.ceil(ratio * 7).toInt().coerceIn(1, 7)
            WeeklyCell(
                weekIndex = week,
                start = coveredStart,
                end = coveredEnd,
                millis = total,
                coveredDays = (ChronoUnit.DAYS.between(coveredStart, coveredEnd) + 1).toInt(),
                level = level,
                filledBlocks = filled
            )
        }
    }

    /** Only a heatmap-linked range is reset when switching day/week presentation. */
    fun rangeAfterHeatmapModeChange(preset: RangePreset, selectedDate: LocalDate?): RangePreset =
        if (selectedDate != null && (preset == RangePreset.Selected || preset == RangePreset.Custom)) {
            RangePreset.Today
        } else preset

    fun range(
        preset: RangePreset,
        today: LocalDate,
        selectedDate: LocalDate?,
        customStart: LocalDate?,
        customEnd: LocalDate?
    ): DateRange {
        return when (preset) {
            RangePreset.Last7 -> DateRange(today.minusDays(6), today)
            RangePreset.Last30 -> DateRange(today.minusDays(29), today)
            RangePreset.Month -> DateRange(YearMonth.from(today).atDay(1), today)
            RangePreset.Today -> DateRange(today, today)
            RangePreset.Selected -> {
                val date = selectedDate ?: today
                DateRange(date, date)
            }
            RangePreset.Custom -> {
                val start = customStart ?: today.minusDays(29)
                val end = customEnd ?: today
                if (end.isBefore(start)) DateRange(end, start) else DateRange(start, end)
            }
        }
    }

    fun chartBuckets(
        data: StatsData,
        range: DateRange,
        granularity: Granularity
    ): List<ChartBucket> {
        return when {
            range.days == 1 -> {
                val date = range.start
                val hours = data.hoursOn(date)
                (0 until 24).map { hour ->
                    ChartBucket(
                        label = String.format(java.util.Locale.CHINA, "%02d:00", hour),
                        longLabel = "${formatDay(date)} ${String.format(java.util.Locale.CHINA, "%02d:00–%02d:00", hour, hour + 1)}",
                        millis = hours[hour],
                        start = date,
                        end = date,
                        hour = hour
                    )
                }
            }
            granularity == Granularity.Day -> range.dates().map { date ->
                ChartBucket(
                    label = formatMonthDay(date),
                    longLabel = formatDay(date),
                    millis = data.millisOn(date),
                    start = date,
                    end = date,
                    hour = null
                )
            }
            else -> {
                val result = ArrayList<ChartBucket>()
                var cursor = range.start
                while (!cursor.isAfter(range.endInclusive)) {
                    val end = minOf(cursor.plusDays(6), range.endInclusive)
                    var total = 0L
                    var day = cursor
                    while (!day.isAfter(end)) {
                        total += data.millisOn(day)
                        day = day.plusDays(1)
                    }
                    result.add(
                        ChartBucket(
                            label = formatMonthDay(cursor),
                            longLabel = "${formatDay(cursor)}起的一周",
                            millis = total,
                            start = cursor,
                            end = end,
                            hour = null
                        )
                    )
                    cursor = end.plusDays(1)
                }
                result
            }
        }
    }

    fun rangeFraction(day: LocalDate, range: DateRange): Float {
        val total = range.days.toFloat().coerceAtLeast(1f)
        val offset = ChronoUnit.DAYS.between(range.start, day).toFloat()
        return ((offset + 0.5f) / total).coerceIn(0f, 1f)
    }

    fun weekdayIndex(date: LocalDate): Int = date.dayOfWeek.value - 1

    fun isMonday(date: LocalDate): Boolean = date.dayOfWeek == DayOfWeek.MONDAY
}
