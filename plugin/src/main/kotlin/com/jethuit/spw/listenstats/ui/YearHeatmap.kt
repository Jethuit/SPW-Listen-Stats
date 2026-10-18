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
import com.jethuit.spw.listenstats.data.HeatmapGrid
import com.jethuit.spw.listenstats.data.StatsCalculations
import com.jethuit.spw.listenstats.data.StatsData
import com.jethuit.spw.listenstats.data.WeeklyCell
import com.jethuit.spw.listenstats.util.formatDay
import com.jethuit.spw.listenstats.util.formatDuration
import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private const val WEEKDAY_LABEL_WIDTH = 17f
private const val LABEL_COLUMN_GAP = 10f
private const val MONTH_LABEL_HEIGHT = 19f
private const val ROW_GAP = 8f
private const val CELL_GAP = 4f
private const val MONTH_LABEL_GAP = 8f

@Composable
fun YearHeatmap(
    data: StatsData,
    year: Int,
    weekly: Boolean,
    today: LocalDate,
    selectedDate: LocalDate?,
    onDaySelected: (LocalDate) -> Unit,
    onWeekSelected: (LocalDate, LocalDate) -> Unit
) {
    val colors = LocalStatsColors.current
    val measurer = rememberTextMeasurer()
    val grid = remember(year) { StatsCalculations.heatmapGrid(year) }
    val weeks = remember(data, grid, today) {
        if (weekly) StatsCalculations.weeklyCells(data, grid) else emptyList()
    }
    var tooltip by remember { mutableStateOf<TooltipData?>(null) }
    var hoverWeek by remember { mutableStateOf<Int?>(null) }
    var hoverRow by remember { mutableStateOf<Int?>(null) }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val availableWidth = with(density) { maxWidth.toPx() } - WEEKDAY_LABEL_WIDTH - LABEL_COLUMN_GAP
        val cell = ((availableWidth - CELL_GAP * (grid.weeks - 1)) / grid.weeks)
            .coerceIn(3f, 18f)
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
            return week.takeIf { it in 0 until grid.weeks }
        }

        fun rowIndex(y: Float): Int? {
            val local = y - gridTop
            if (local < 0f) return null
            val row = floor(local / (cell + CELL_GAP)).toInt()
            return row.takeIf { it in 0..6 }
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
                        if (weekCell == null) {
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
                    if (weekly) {
                        val weekCell = weeklyCellAt(week) ?: return@onPointerEvent
                        onWeekSelected(weekCell.start, weekCell.end)
                    } else {
                        val row = rowIndex(position.y) ?: return@onPointerEvent
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
                        val layout = measurer.measure("$month" + "月", AppType.tiny.copy(color = colors.muted))
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
                        val layout = measurer.measure(label, AppType.tiny.copy(color = colors.muted))
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
                                val radius = CornerRadius(min(6f, cell / 2f))
                                drawRoundRect(
                                    color = colors.cells[level],
                                    topLeft = Offset(x, y),
                                    size = Size(cell, cell),
                                    cornerRadius = radius,
                                    alpha = alpha
                                )
                                val isSelected = date == selectedDate
                                val isHovered = hoverWeek == week && hoverRow == row
                                if (isSelected || isHovered) {
                                    drawRoundRect(
                                        color = colors.accent,
                                        topLeft = Offset(x - 2f, y - 2f),
                                        size = Size(cell + 4f, cell + 4f),
                                        cornerRadius = CornerRadius(min(8f, cell / 2f + 2f)),
                                        style = Stroke(width = if (isSelected) 1.2f else 1f)
                                    )
                                }
                            }
                        }
                    } else {
                        for (week in 0 until grid.weeks) {
                            val weekCell = weeks[week]
                            val x = cellX(week)
                            val selected = weekCell.millis > 0 &&
                                selectedDate != null &&
                                selectedDate in weekCell.start..weekCell.end
                            for (row in 0..6) {
                                val y = cellY(row)
                                val blockIndexFromBottom = 6 - row
                                val filled = weekCell.filledBlocks
                                val active = blockIndexFromBottom < filled
                                val color = if (active) colors.cells[weekCell.level] else colors.cells[0]
                                val alpha = if (weekCell.start.isAfter(today)) 0.35f else 1f
                                drawRoundRect(
                                    color = color,
                                    topLeft = Offset(x, y),
                                    size = Size(cell, cell),
                                    cornerRadius = CornerRadius(min(6f, cell / 2f)),
                                    alpha = alpha
                                )
                            }
                            if (selected) {
                                drawRoundRect(
                                    color = colors.accent,
                                    topLeft = Offset(x - 2f, gridTop - 2f),
                                    size = Size(cell + 4f, gridHeight + 4f),
                                    cornerRadius = CornerRadius(min(8f, cell / 2f + 2f)),
                                    style = Stroke(width = 1.2f)
                                )
                            }
                            if (hoverWeek == week) {
                                drawRoundRect(
                                    color = colors.accent.copy(alpha = 0.5f),
                                    topLeft = Offset(x - 1f, gridTop - 1f),
                                    size = Size(cell + 2f, gridHeight + 2f),
                                    cornerRadius = CornerRadius(min(8f, cell / 2f + 2f)),
                                    style = Stroke(width = 1f)
                                )
                            }
                        }
                    }
                }
        )
        StatsTooltip(tooltip)
    }
}
