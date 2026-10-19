@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.jethuit.spw.listenstats.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jethuit.spw.listenstats.data.HeatmapGrid
import com.jethuit.spw.listenstats.data.StatsCalculations
import com.jethuit.spw.listenstats.data.StatsData
import com.jethuit.spw.listenstats.data.WeeklyCell
import com.jethuit.spw.listenstats.util.formatDay
import com.jethuit.spw.listenstats.util.formatDuration
import com.moriafly.salt.ui.LocalTextStyle
import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

@Composable
fun YearHeatmap(
    data: StatsData,
    year: Int,
    weekly: Boolean,
    today: LocalDate,
    selectedDate: LocalDate?,
    onDaySelected: (LocalDate) -> Unit,
    onWeekSelected: (LocalDate, LocalDate) -> Unit,
    viewportRevision: Int = 0
) {
    val colors = LocalStatsColors.current
    val measurer = rememberTextMeasurer()
    val labelStyle = AppType.tiny.copy(fontFamily = LocalTextStyle.current.fontFamily)
    val grid = remember(year) { StatsCalculations.heatmapGrid(year) }
    val weeks = remember(data, grid, today, weekly) {
        if (weekly) StatsCalculations.weeklyCells(data, grid, today) else emptyList()
    }
    var tooltip by remember(year, weekly, viewportRevision, data) { mutableStateOf<TooltipData?>(null) }
    var hoverWeek by remember(year, weekly, viewportRevision) { mutableStateOf<Int?>(null) }
    var hoverRow by remember(year, weekly, viewportRevision) { mutableStateOf<Int?>(null) }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val WEEKDAY_LABEL_WIDTH = with(density) { 17.dp.toPx() }
        val LABEL_COLUMN_GAP = with(density) { 10.dp.toPx() }
        val MONTH_LABEL_HEIGHT = with(density) { 19.dp.toPx() }
        val MONTH_LABEL_GAP = with(density) { 8.dp.toPx() }
        val ROW_GAP = with(density) { 8.dp.toPx() }
        val CELL_GAP = with(density) { 4.dp.toPx() }
        val radiusPx = with(density) { 6.dp.toPx() }
        val availableWidth = with(density) { maxWidth.toPx() } - WEEKDAY_LABEL_WIDTH - LABEL_COLUMN_GAP
        val cell = ((availableWidth - CELL_GAP * (grid.weeks - 1)) / grid.weeks)
            .coerceAtLeast(1f)
        val gridWidth = cell * grid.weeks + CELL_GAP * (grid.weeks - 1)
        val gridHeight = cell * 7 + CELL_GAP * 6
        val totalHeight = MONTH_LABEL_HEIGHT + MONTH_LABEL_GAP + gridHeight + ROW_GAP
        val heightDp = with(density) { totalHeight.toDp() }
        val startX = WEEKDAY_LABEL_WIDTH + LABEL_COLUMN_GAP
        val gridTop = MONTH_LABEL_HEIGHT + MONTH_LABEL_GAP

        fun weekIndex(x: Float): Int? {
            val local = x - startX
            if (local < 0f) return null
            val week = floor(local / (cell + CELL_GAP)).toInt()
            return week.takeIf { it in 0 until grid.weeks && local % (cell + CELL_GAP) <= cell }
        }

        fun rowIndex(y: Float): Int? {
            val local = y - gridTop
            if (local < 0f) return null
            val row = floor(local / (cell + CELL_GAP)).toInt()
            return row.takeIf { it in 0..6 && local % (cell + CELL_GAP) <= cell }
        }

        fun cellX(week: Int): Float = startX + week * (cell + CELL_GAP)
        fun cellY(row: Int): Float = gridTop + row * (cell + CELL_GAP)

        fun weeklyCellAt(week: Int): WeeklyCell? = weeks.getOrNull(week)

        fun showTooltipForDay(date: LocalDate, hoverX: Float, hoverY: Float) {
            tooltip = TooltipData(
                title = formatDay(date),
                label = "听歌时长",
                value = formatDuration(data.millisOn(date)),
                x = hoverX,
                y = hoverY
            )
        }

        fun showTooltipForWeek(cell: WeeklyCell?, hoverX: Float, hoverY: Float) {
            if (cell == null) return
            tooltip = TooltipData(
                title = "${formatDay(cell.start)}起的一周",
                label = "听歌时长",
                value = formatDuration(cell.millis),
                x = hoverX,
                y = hoverY
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(heightDp)
                .onPointerEvent(PointerEventType.Move) { event ->
                    val position = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                    val week = weekIndex(position.x)
                    val row = rowIndex(position.y)
                    hoverWeek = week
                    hoverRow = row
                    if (weekly) {
                        val weekCell = week?.let { weeklyCellAt(it) }
                        hoverRow = null
                        if (weekCell == null || row == null || grid.gridStart.plusDays(week!! * 7L + row).year != year) {
                            tooltip = null
                        } else {
                            showTooltipForWeek(weekCell, position.x, position.y)
                        }
                    } else {
                        if (week == null || row == null) {
                            tooltip = null
                        } else {
                            val date = grid.gridStart.plusDays((week * 7 + row).toLong())
                            if (date.year != year) {
                                tooltip = null
                            } else {
                                showTooltipForDay(date, position.x, position.y)
                            }
                        }
                    }
                }
                .onPointerEvent(PointerEventType.Exit) {
                    tooltip = null
                    hoverWeek = null
                    hoverRow = null
                }
                .onPointerEvent(PointerEventType.Press) { event ->
                    val position = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                    val week = weekIndex(position.x) ?: return@onPointerEvent
                    val row = rowIndex(position.y) ?: return@onPointerEvent
                    if (grid.gridStart.plusDays(week * 7L + row).year != year) return@onPointerEvent
                    if (weekly) {
                        val weekCell = weeklyCellAt(week) ?: return@onPointerEvent
                        if (!weekCell.start.isAfter(today)) onWeekSelected(weekCell.start, minOf(weekCell.end, today))
                    } else {
                        val date = grid.gridStart.plusDays((week * 7 + row).toLong())
                        if (date.year == year && !date.isAfter(today)) {
                            onDaySelected(date)
                        }
                    }
                }
                .drawWith {
                    // Month labels
                    var lastLabelRight = -100f
                    for (month in 1..12) {
                        val first = LocalDate.of(year, month, 1)
                        val week = ((first.toEpochDay() - grid.gridStart.toEpochDay()) / 7).toInt()
                        val x = cellX(week)
                        val layout = measurer.measure("$month" + "月", labelStyle.copy(color = colors.muted))
                        if (x > lastLabelRight + 4f) {
                            drawText(
                                layout,
                                topLeft = Offset(x, (MONTH_LABEL_HEIGHT - layout.size.height) / 2f)
                            )
                            lastLabelRight = x + layout.size.width
                        }
                    }

                    // Weekday labels
                    val weekdays = listOf("一", "三", "五", "日")
                    weekdays.forEachIndexed { index, label ->
                        val row = index * 2
                        val layout = measurer.measure(
                            label,
                            labelStyle.copy(fontSize = 10.sp, color = colors.muted)
                        )
                        drawText(
                            layout,
                            topLeft = Offset(0f, cellY(row) + (cell - layout.size.height) / 2f)
                        )
                    }

                    // Grid cells
                    if (!weekly) {
                        for (week in 0 until grid.weeks) {
                            for (row in 0..6) {
                                val date = grid.gridStart.plusDays((week * 7 + row).toLong())
                                if (date.year != year) continue
                                val x = cellX(week)
                                val y = cellY(row)
                                val millis = data.millisOn(date)
                                val level = StatsCalculations.dayLevel(millis, today, date)
                                val alpha = if (date.isAfter(today)) 0.35f else 1f
                                val radius = CornerRadius(min(radiusPx, cell / 2f))
                                drawRoundRect(
                                    color = colors.cells[level],
                                    topLeft = Offset(x, y),
                                    size = Size(cell, cell),
                                    cornerRadius = radius,
                                    alpha = alpha
                                )
                                val isSelected = date == selectedDate
                                val isHovered = hoverWeek == week && hoverRow == row
                                if (isHovered) {
                                    drawRoundRect(
                                        color = colors.accent,
                                        topLeft = Offset(x - 3f, y - 3f),
                                        size = Size(cell + 6f, cell + 6f),
                                        cornerRadius = CornerRadius(min(9f, cell / 2f + 3f)),
                                        style = Stroke(width = 2.5f)
                                    )
                                }
                                if (isSelected) {
                                    drawRoundRect(
                                        color = colors.accent,
                                        topLeft = Offset(x - 2f, y - 2f),
                                        size = Size(cell + 4f, cell + 4f),
                                        cornerRadius = CornerRadius(min(8f, cell / 2f + 2f)),
                                        style = Stroke(width = 1f)
                                    )
                                }
                            }
                        }
                    } else {
                        for (week in 0 until grid.weeks) {
                            val weekCell = weeks[week]
                            val x = cellX(week)
                            val selected = selectedDate != null &&
                                selectedDate in weekCell.start..weekCell.end
                            val validRows = (0..6).filter { grid.gridStart.plusDays(week * 7L + it).year == year }
                            for (row in 0..6) {
                                if (row !in validRows) continue
                                val y = cellY(row)
                                val blockIndexFromBottom = validRows.last() - row
                                val filled = weekCell.filledBlocks
                                val active = blockIndexFromBottom < filled
                                val color = if (active) colors.cells[weekCell.level] else colors.cells[0]
                                val alpha = if (weekCell.start.isAfter(today)) 0.35f else 1f
                                drawRoundRect(
                                    color = color,
                                    topLeft = Offset(x, y),
                                    size = Size(cell, cell),
                                    cornerRadius = CornerRadius(min(radiusPx, cell / 2f)),
                                    alpha = alpha
                                )
                            }
                            if (selected) {
                                drawRoundRect(
                                    color = colors.accent,
                                    topLeft = Offset(x - 2f, gridTop - 2f),
                                    size = Size(cell + 4f, gridHeight + 4f),
                                    cornerRadius = CornerRadius(min(8f, cell / 2f + 2f)),
                                    style = Stroke(width = 1f)
                                )
                            }
                            if (hoverWeek == week) {
                                drawRoundRect(
                                    color = colors.accent.copy(alpha = 0.5f),
                                    topLeft = Offset(x - 2f, gridTop - 2f),
                                    size = Size(cell + 4f, gridHeight + 4f),
                                    cornerRadius = CornerRadius(min(8f, cell / 2f + 2f)),
                                    style = Stroke(width = 1.5f)
                                )
                            }
                        }
                    }
                }
        )
        StatsTooltip(tooltip)
    }
}
