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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.jethuit.spw.listenstats.data.ChartBucket
import com.jethuit.spw.listenstats.data.ChartType
import com.jethuit.spw.listenstats.util.formatDuration
import com.moriafly.salt.ui.LocalTextStyle
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

@Composable
fun TrendChart(
    buckets: List<ChartBucket>,
    chartType: ChartType,
    showAverage: Boolean,
    hourly: Boolean = false,
    viewportRevision: Int = 0
) {
    val colors = LocalStatsColors.current
    val measurer = rememberTextMeasurer()
    val hostFont = LocalTextStyle.current.fontFamily
    val axisStyle = AppType.tiny.copy(fontFamily = hostFont)
    var tooltip by remember(buckets, chartType, viewportRevision) { mutableStateOf<TooltipData?>(null) }
    var hoverIndex by remember(buckets, chartType, viewportRevision) { mutableStateOf<Int?>(null) }
    val widestLabel = remember(buckets, hostFont) {
        buckets.maxOfOrNull { measurer.measure(it.label, axisStyle).size.width.toFloat() } ?: 0f
    }

    val values = buckets.map { it.millis }
    val maxMillis = values.maxOrNull() ?: 0L
    val empty = maxMillis <= 0L
    val axis = UiGeometry.trendAxis(maxMillis, hourly)
    val unitMillis = axis.unitMillis
    val yMax = axis.maximum
    val average = if (values.isEmpty()) 0.0 else values.sum().toDouble() / values.size / unitMillis

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(258.dp)
    ) {
        val density = LocalDensity.current
        val AXIS_LEFT = max(
            with(density) { 52.dp.toPx() },
            widestLabel / 2f + with(density) { 6.dp.toPx() }
        )
        val AXIS_RIGHT = max(
            with(density) { 10.dp.toPx() },
            widestLabel / 2f + with(density) { 6.dp.toPx() }
        )
        val AXIS_TOP = with(density) { 10.dp.toPx() }
        val AXIS_BOTTOM = with(density) { 30.dp.toPx() }
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { 258.dp.toPx() }
        val plotWidth = (widthPx - AXIS_LEFT - AXIS_RIGHT).coerceAtLeast(1f)
        val plotHeight = (heightPx - AXIS_TOP - AXIS_BOTTOM).coerceAtLeast(1f)
        val slot = if (buckets.isEmpty()) plotWidth else plotWidth / buckets.size
        val banded = hourly || chartType == ChartType.Bar
        val tickCount = 4

        fun xAt(index: Int): Float {
            return when {
                buckets.size <= 1 -> AXIS_LEFT + plotWidth / 2f
                banded -> AXIS_LEFT + (index + 0.5f) * slot
                else -> AXIS_LEFT + index * plotWidth / (buckets.size - 1)
            }
        }

        fun yAt(valueMillis: Long): Float {
            val ratio = if (yMax <= 0.0) 0.0 else (valueMillis / unitMillis) / yMax
            return AXIS_TOP + plotHeight - (ratio.coerceIn(0.0, 1.0) * plotHeight).toFloat()
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(258.dp)
                .onPointerEvent(PointerEventType.Move) { event ->
                    val position = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                    if (buckets.isEmpty() || empty || position.x !in AXIS_LEFT..(widthPx - AXIS_RIGHT) ||
                        position.y !in AXIS_TOP..(AXIS_TOP + plotHeight)) {
                        tooltip = null; hoverIndex = null
                        return@onPointerEvent
                    }
                    val index = if (buckets.size <= 1) 0 else if (banded) {
                        floor((position.x - AXIS_LEFT) / slot).toInt()
                    } else {
                        ((position.x - AXIS_LEFT) / plotWidth * (buckets.size - 1) + 0.5f).toInt()
                    }.coerceIn(0, buckets.size - 1)
                    hoverIndex = index
                    val bucket = buckets[index]
                    tooltip = TooltipData(
                        title = bucket.longLabel,
                        label = "听歌时长",
                        value = formatDuration(bucket.millis),
                        x = position.x,
                        y = position.y
                    )
                }
                .onPointerEvent(PointerEventType.Exit) {
                    hoverIndex = null
                    tooltip = null
                }
                .drawWith {
                    if (buckets.isEmpty()) return@drawWith
                    val tickValues = (0..tickCount).map { yMax * it / tickCount }
                    val averageY = if (showAverage && !empty) {
                        AXIS_TOP + plotHeight - ((average / yMax).coerceIn(0.0, 1.0) * plotHeight).toFloat()
                    } else {
                        null
                    }

                    // Grid and y-axis labels
                    tickValues.forEachIndexed { index, tick ->
                        val y = AXIS_TOP + plotHeight - (index.toFloat() / tickCount) * plotHeight
                        drawLine(
                            color = colors.line,
                            start = Offset(AXIS_LEFT, y),
                            end = Offset(widthPx - AXIS_RIGHT, y),
                            strokeWidth = 1f
                        )
                        val label = UiGeometry.tick(tick, yMax / tickCount)
                        val nearAverage = averageY != null && abs(y - averageY) < with(density) { 14.dp.toPx() }
                        if (!nearAverage) {
                            val layout = measurer.measure(
                                label,
                                axisStyle.copy(color = colors.muted)
                            )
                            drawText(
                                layout,
                                topLeft = Offset(
                                    AXIS_LEFT - with(density) { 12.dp.toPx() } - layout.size.width,
                                    y - layout.size.height / 2f
                                )
                            )
                        }
                    }

                    // X-axis labels
                    val maxLabels = max(2, (plotWidth / with(density) { 64.dp.toPx() }).toInt())
                    val step = if (hourly && buckets.size == 24) 4
                        else max(1, ceil(buckets.size.toDouble() / maxLabels).toInt())
                    val lastBucket = buckets.last()
                    val lastLayout = measurer.measure(
                        lastBucket.label,
                        axisStyle.copy(color = colors.muted)
                    )
                    val lastCenter = xAt(buckets.lastIndex)
                    val lastLeft = lastCenter - lastLayout.size.width / 2f
                    buckets.forEachIndexed { index, bucket ->
                        val isFirst = index == 0
                        val isLast = index == buckets.lastIndex
                        if (isLast) return@forEachIndexed
                        val shouldDraw = isFirst || index % step == 0
                        if (!shouldDraw) return@forEachIndexed
                        val label = bucket.label
                        val layout = measurer.measure(label, axisStyle.copy(color = colors.muted))
                        val center = xAt(index)
                        if (center - layout.size.width / 2f < 0f) return@forEachIndexed
                        if (center + layout.size.width / 2f > lastLeft - 6f) return@forEachIndexed
                        drawLine(
                            color = colors.line,
                            start = Offset(center, AXIS_TOP + plotHeight),
                            end = Offset(center, AXIS_TOP + plotHeight + 4f),
                            strokeWidth = 1f
                        )
                        drawText(
                            layout,
                            topLeft = Offset(
                                center - layout.size.width / 2f,
                                AXIS_TOP + plotHeight + 9f
                            )
                        )
                    }
                    drawLine(
                        color = colors.line,
                        start = Offset(lastCenter, AXIS_TOP + plotHeight),
                        end = Offset(lastCenter, AXIS_TOP + plotHeight + 4f),
                        strokeWidth = 1f
                    )
                    drawText(
                        lastLayout,
                        topLeft = Offset(lastLeft, AXIS_TOP + plotHeight + 9f)
                    )

                    // Bars or line
                    if (!empty) when (chartType) {
                        ChartType.Bar -> {
                            val barWidth = min(with(density) { 32.dp.toPx() }, slot * 0.65f).coerceAtLeast(2f)
                            buckets.forEachIndexed { index, bucket ->
                                if (bucket.millis <= 0) return@forEachIndexed
                                val top = yAt(bucket.millis)
                                val left = xAt(index) - barWidth / 2f
                                drawRoundRect(
                                    color = colors.accent.copy(alpha = if (hoverIndex == index) 1f else 0.72f),
                                    topLeft = Offset(left, top),
                                    size = Size(barWidth, AXIS_TOP + plotHeight - top),
                                    cornerRadius = CornerRadius(min(3f, barWidth / 2f))
                                )
                            }
                        }
                        ChartType.Line, ChartType.Curve -> {
                            val points = buckets.mapIndexed { index, bucket ->
                                Offset(xAt(index), yAt(bucket.millis))
                            }
                            val path = if (chartType == ChartType.Line) {
                                linePath(points)
                            } else {
                                monotonePath(points)
                            }
                            val area = Path().apply {
                                addPath(path)
                                lineTo(points.last().x, AXIS_TOP + plotHeight)
                                lineTo(points.first().x, AXIS_TOP + plotHeight)
                                close()
                            }
                            drawPath(
                                area,
                                color = colors.accentSoft
                            )
                            drawPath(path, color = colors.accent, style = Stroke(width = 2f))
                            if (buckets.size == 1) {
                                val point = points.single()
                                drawCircle(
                                    color = colors.accent,
                                    radius = 2f,
                                    center = point
                                )
                            }
                        }
                    }

                    // Average line and label
                    if (showAverage && averageY != null) {
                        drawLine(
                            color = colors.muted,
                            start = Offset(AXIS_LEFT - with(density) { 6.dp.toPx() }, averageY),
                            end = Offset(widthPx - AXIS_RIGHT, averageY),
                            strokeWidth = 1.2f,
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                floatArrayOf(6f, 5f)
                            )
                        )
                        val label = UiGeometry.tick(average, yMax / tickCount)
                        val layout = measurer.measure(
                            label,
                            axisStyle.copy(
                                color = colors.accent,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                            )
                        )
                        drawText(
                            layout,
                            topLeft = Offset(
                                AXIS_LEFT - with(density) { 12.dp.toPx() } - layout.size.width,
                                averageY - layout.size.height / 2f
                            )
                        )
                    }

                    // Crosshair and hovered point
                    val hovered = hoverIndex
                    if (hovered != null && hovered in buckets.indices) {
                        val x = xAt(hovered)
                        drawLine(
                            color = colors.muted.copy(alpha = 0.5f),
                            start = Offset(x, AXIS_TOP),
                            end = Offset(x, AXIS_TOP + plotHeight),
                            strokeWidth = 1f,
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                floatArrayOf(3f, 4f)
                            )
                        )
                        val point = Offset(x, yAt(buckets[hovered].millis))
                        drawCircle(
                            color = colors.accent,
                            radius = 4f,
                            center = point
                        )
                    }

                    if (empty) {
                        val layout = measurer.measure(
                            "这段时间还没有听歌记录",
                            AppType.bodySmall.copy(color = colors.muted, fontFamily = hostFont)
                        )
                        drawText(
                            layout,
                            topLeft = Offset(
                                AXIS_LEFT + (plotWidth - layout.size.width) / 2f,
                                AXIS_TOP + plotHeight / 2f - layout.size.height / 2f
                            )
                        )
                    }
                }
        )
        StatsTooltip(tooltip)
    }
}

private fun linePath(points: List<Offset>): Path {
    val path = Path()
    points.forEachIndexed { index, point ->
        if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    return path
}

/** Fritsch-Carlson monotone cubic interpolation; never overshoots neighbours. */
private fun monotonePath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    if (points.size == 1) {
        path.moveTo(points[0].x, points[0].y)
        return path
    }
    val n = points.size
    val dx = DoubleArray(n - 1) { (points[it + 1].x - points[it].x).toDouble() }
    val slope = DoubleArray(n - 1) {
        (points[it + 1].y - points[it].y).toDouble() / dx[it]
    }
    val tangent = DoubleArray(n)
    tangent[0] = slope[0]
    tangent[n - 1] = slope[n - 2]
    for (index in 1 until n - 1) {
        if (slope[index - 1] * slope[index] <= 0.0) {
            tangent[index] = 0.0
        } else {
            tangent[index] = (slope[index - 1] + slope[index]) / 2.0
        }
    }
    for (index in 0 until n - 1) {
        if (slope[index] == 0.0) {
            tangent[index] = 0.0
            tangent[index + 1] = 0.0
            continue
        }
        val alpha = tangent[index] / slope[index]
        val beta = tangent[index + 1] / slope[index]
        val length = alpha * alpha + beta * beta
        if (length > 9.0) {
            val tau = 3.0 / sqrt(length)
            tangent[index] = tau * alpha * slope[index]
            tangent[index + 1] = tau * beta * slope[index]
        }
    }
    path.moveTo(points[0].x, points[0].y)
    for (index in 0 until n - 1) {
        val x0 = points[index].x.toDouble()
        val x1 = points[index + 1].x.toDouble()
        val y0 = points[index].y.toDouble()
        val y1 = points[index + 1].y.toDouble()
        val low = min(y0, y1)
        val high = max(y0, y1)
        val steps = 18
        for (step in 1..steps) {
            val t = step.toDouble() / steps
            val t2 = t * t
            val t3 = t2 * t
            val h00 = 2 * t3 - 3 * t2 + 1
            val h10 = t3 - 2 * t2 + t
            val h01 = -2 * t3 + 3 * t2
            val h11 = t3 - t2
            val y = h00 * y0 + h10 * dx[index] * tangent[index] +
                h01 * y1 + h11 * dx[index] * tangent[index + 1]
            val x = x0 + dx[index] * t
            path.lineTo(x.toFloat(), y.coerceIn(low, high).toFloat())
        }
    }
    return path
}
