@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.jethuit.spw.listenstats.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jethuit.spw.listenstats.ui.StatsText as Text
import com.jethuit.spw.listenstats.util.formatDay
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.min

private val WeekdayLabels = listOf("一", "二", "三", "四", "五", "六", "日")

@Composable
fun DateRangeDialog(
    initialStart: LocalDate,
    initialEnd: LocalDate,
    onCancel: () -> Unit,
    onConfirm: (LocalDate, LocalDate) -> Unit
) {
    val colors = LocalStatsColors.current
    var startDate by remember { mutableStateOf(initialStart) }
    var endDate by remember { mutableStateOf(initialEnd) }
    var startMonth by remember { mutableStateOf(YearMonth.from(initialStart)) }
    var endMonth by remember { mutableStateOf(YearMonth.from(initialEnd)) }
    val rootFocus = remember { FocusRequester() }
    val years = (2025..LocalDate.now().year).toList()
    val months = (1..12).map { it.toString() to "${it}月" }
    val yearOptions = years.map { it.toString() to "${it}年" }

    val rangeValid = !startDate.isAfter(endDate)
    val days = if (rangeValid) {
        ChronoUnit.DAYS.between(startDate, endDate) + 1
    } else {
        null
    }
    val summary = if (!rangeValid) {
        "起始日期不能晚于结束日期"
    } else {
        "${formatDay(startDate)} 至 ${formatDay(endDate)} · 已选 $days 天"
    }

    fun submit() {
        if (rangeValid) onConfirm(startDate, endDate)
    }

    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val dialogWidth = minOf(600.dp, maxWidth - 32.dp).coerceAtLeast(280.dp)
        Surface(
            onClick = onCancel,
            modifier = Modifier.fillMaxSize().focusRequester(rootFocus),
            color = Color.Black.copy(alpha = 0.28f)
        ) {}
        Box(
            Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Escape -> {
                            onCancel()
                            true
                        }
                        Key.Enter -> {
                            submit()
                            true
                        }
                        else -> false
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.width(dialogWidth)
            ) {
                Surface(
                    modifier = Modifier.matchParentSize(),
                    shape = AppShape(14.dp),
                    color = colors.background,
                    border = BorderStroke(1.dp, SolidColor(colors.line))
                ) {}
                Box(
                    Modifier
                        .matchParentSize()
                        .onPointerEvent(PointerEventType.Press) { event ->
                            event.changes.forEach { it.consume() }
                        }
                )
                Column(Modifier.padding(20.dp)) {
                    Text(
                        "自定义日期范围",
                        style = AppType.sectionTitle.copy(color = colors.text)
                    )
                    Text(
                        "分别选择起始和结束日期；切换年月只浏览，不改变已选日期",
                        modifier = Modifier.padding(top = 5.dp),
                        style = AppType.tiny.copy(color = colors.muted)
                    )

                    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        val fontScale = LocalDensity.current.fontScale
                        val twoCards = maxWidth >= 560.dp && fontScale <= 1.15f
                        if (twoCards) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)
                            ) {
                                DateCard(
                                    title = "起始日期",
                                    selected = startDate,
                                    month = startMonth,
                                    onMonthChange = { startMonth = it },
                                    onDateSelected = { startDate = it },
                                    years = yearOptions,
                                    months = months,
                                    colors = colors,
                                    modifier = Modifier.width(270.dp)
                                )
                                DateCard(
                                    title = "结束日期",
                                    selected = endDate,
                                    month = endMonth,
                                    onMonthChange = { endMonth = it },
                                    onDateSelected = { endDate = it },
                                    years = yearOptions,
                                    months = months,
                                    colors = colors,
                                    modifier = Modifier.width(270.dp)
                                )
                            }
                        } else {
                            Column(
                                Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                DateCard(
                                    title = "起始日期",
                                    selected = startDate,
                                    month = startMonth,
                                    onMonthChange = { startMonth = it },
                                    onDateSelected = { startDate = it },
                                    years = yearOptions,
                                    months = months,
                                    colors = colors,
                                    modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth()
                                )
                                DateCard(
                                    title = "结束日期",
                                    selected = endDate,
                                    month = endMonth,
                                    onMonthChange = { endMonth = it },
                                    onDateSelected = { endDate = it },
                                    years = yearOptions,
                                    months = months,
                                    colors = colors,
                                    modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth()
                                )
                            }
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            summary,
                            modifier = Modifier.weight(1f),
                            style = AppType.tiny.copy(
                                color = if (rangeValid) colors.muted else colors.danger
                            )
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            DialogButton("取消", colors.controlBackground, colors.text, true, onCancel)
                            DialogButton(
                                "确定",
                                if (rangeValid) colors.accent else colors.controlBackground,
                                if (rangeValid) {
                                    if (colors.isDark) colors.background else Color.White
                                } else {
                                    colors.muted
                                },
                                rangeValid,
                                ::submit
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DateCard(
    title: String,
    selected: LocalDate,
    month: YearMonth,
    onMonthChange: (YearMonth) -> Unit,
    onDateSelected: (LocalDate) -> Unit,
    years: List<Pair<String, String>>,
    months: List<Pair<String, String>>,
    colors: StatsColors,
    modifier: Modifier
) {
    Surface(
        modifier = modifier,
        shape = AppShape(12.dp),
        color = colors.subtle,
        border = BorderStroke(1.dp, SolidColor(colors.line))
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = AppType.bodySmall.copy(color = colors.text))
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CalendarNavButton("‹", "上个月") { onMonthChange(month.minusMonths(1)) }
                SelectBox(
                    label = "${month.year}年",
                    options = years,
                    selected = month.year.toString(),
                    onSelected = { year ->
                        year.toIntOrNull()?.let { onMonthChange(YearMonth.of(it, month.monthValue)) }
                    },
                    modifier = Modifier.padding(horizontal = 3.dp),
                    minWidth = 78.dp
                )
                SelectBox(
                    label = "${month.monthValue}月",
                    options = months,
                    selected = month.monthValue.toString(),
                    onSelected = { value ->
                        value.toIntOrNull()?.let { onMonthChange(YearMonth.of(month.year, it)) }
                    },
                    modifier = Modifier.padding(horizontal = 3.dp),
                    minWidth = 58.dp
                )
                CalendarNavButton("›", "下个月") { onMonthChange(month.plusMonths(1)) }
            }
            MonthGrid(
                month = month,
                selected = selected,
                colors = colors,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                onPick = onDateSelected
            )
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    selected: LocalDate,
    colors: StatsColors,
    modifier: Modifier,
    onPick: (LocalDate) -> Unit
) {
    val first = month.atDay(1)
    val offset = (first.dayOfWeek.value + 6) % 7
    val days = month.lengthOfMonth()
    val cells = List(42) { index ->
        if (index < offset || index >= offset + days) null
        else first.plusDays((index - offset).toLong())
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.TopCenter) {
        val cell = minOf(maxWidth / 7f, 34.dp)
        Column(Modifier.width(cell * 7f)) {
            Row(Modifier.fillMaxWidth()) {
                WeekdayLabels.forEach { label ->
                    Box(Modifier.width(cell), contentAlignment = Alignment.Center) {
                        Text(
                            label,
                            style = AppType.tiny.copy(color = colors.muted, fontSize = 10.sp)
                        )
                    }
                }
            }
            for (week in 0 until 6) {
                Row(Modifier.fillMaxWidth()) {
                    for (day in 0 until 7) {
                        val date = cells[week * 7 + day]
                        Box(Modifier.size(cell).padding(1.dp), contentAlignment = Alignment.Center) {
                            if (date != null) {
                                CalendarDay(
                                    date = date,
                                    selected = date == selected,
                                    colors = colors,
                                    onClick = { onPick(date) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDay(
    date: LocalDate,
    selected: Boolean,
    colors: StatsColors,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxSize(),
        shape = AppShape(7.dp),
        color = if (selected) colors.accent else Color.Transparent
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                date.dayOfMonth.toString(),
                style = AppType.tiny.copy(
                    color = if (selected) {
                        if (colors.isDark) colors.background else Color.White
                    } else {
                        colors.text
                    }
                )
            )
        }
    }
}

@Composable
private fun CalendarNavButton(label: String, description: String, onClick: () -> Unit) {
    val colors = LocalStatsColors.current
    Surface(
        onClick = onClick,
        modifier = Modifier
            .height(34.dp)
            .widthIn(min = 34.dp)
            .semantics { contentDescription = description },
        shape = AppShape(8.dp),
        color = colors.controlBackground
    ) {
        Box(Modifier.padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
            Text(label, style = AppType.bodySmall.copy(color = colors.text))
        }
    }
}

@Composable
private fun DialogButton(
    label: String,
    background: Color,
    foreground: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = { if (enabled) onClick() },
        shape = AppShape(8.dp),
        color = if (enabled) background else background.copy(alpha = 0.55f)
    ) {
        Box(
            Modifier.height(34.dp).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(label, style = AppType.action.copy(color = foreground))
        }
    }
}
