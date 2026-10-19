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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jethuit.spw.listenstats.ui.StatsText as Text
import com.jethuit.spw.listenstats.util.formatDay
import java.time.LocalDate
import java.time.YearMonth

private val WeekdayLabels = listOf("一", "二", "三", "四", "五", "六", "日")

@Composable
fun DateRangeDialog(
    initialStart: LocalDate,
    initialEnd: LocalDate,
    onCancel: () -> Unit,
    onConfirm: (LocalDate, LocalDate) -> Unit
) {
    val colors = LocalStatsColors.current
    var firstMonth by remember { mutableStateOf(YearMonth.from(initialStart)) }
    var start by remember { mutableStateOf<LocalDate?>(initialStart) }
    var end by remember { mutableStateOf<LocalDate?>(initialEnd) }
    var selectingNewRange by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val rootFocus = remember { FocusRequester() }

    fun pick(date: LocalDate) {
        error = null
        if (!selectingNewRange || end != null) {
            start = date
            end = null
            selectingNewRange = true
        } else {
            val currentStart = start
            if (currentStart != null && date.isBefore(currentStart)) {
                start = date
                end = currentStart
            } else {
                end = date
            }
        }
    }

    fun submit() {
        val selectedStart = start
        val selectedEnd = end
        if (selectedStart == null || selectedEnd == null) {
            error = "请先选择开始日期和结束日期"
            return
        }
        onConfirm(selectedStart, selectedEnd)
    }

    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
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
            Surface(
                onClick = {},
                modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 640.dp),
                shape = AppShape(14.dp),
                color = colors.background,
                border = BorderStroke(1.dp, SolidColor(colors.line))
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        "自定义日期范围",
                        style = AppType.sectionTitle.copy(color = colors.text)
                    )
                    Text(
                        "点击开始日期，再点击结束日期；再次点击可重新选择",
                        modifier = Modifier.padding(top = 5.dp),
                        style = AppType.tiny.copy(color = colors.muted)
                    )

                    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        val twoMonths = maxWidth >= 470.dp
                        val secondMonth = firstMonth.plusMonths(1)
                        Column {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CalendarNavButton("«", "上一年") { firstMonth = firstMonth.minusYears(1) }
                                CalendarNavButton("‹", "上个月") { firstMonth = firstMonth.minusMonths(1) }
                                Text(
                                    if (twoMonths) {
                                        "${firstMonth.year}年${firstMonth.monthValue}月 — ${secondMonth.year}年${secondMonth.monthValue}月"
                                    } else {
                                        "${firstMonth.year}年${firstMonth.monthValue}月"
                                    },
                                    modifier = Modifier.padding(horizontal = 10.dp),
                                    style = AppType.bodySmall.copy(color = colors.text)
                                )
                                CalendarNavButton("›", "下个月") { firstMonth = firstMonth.plusMonths(1) }
                                CalendarNavButton("»", "下一年") { firstMonth = firstMonth.plusYears(1) }
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(18.dp)
                            ) {
                                MonthGrid(
                                    month = firstMonth,
                                    start = start,
                                    end = end,
                                    colors = colors,
                                    modifier = Modifier.weight(1f),
                                    onPick = ::pick
                                )
                                if (twoMonths) {
                                    MonthGrid(
                                        month = secondMonth,
                                        start = start,
                                        end = end,
                                        colors = colors,
                                        modifier = Modifier.weight(1f),
                                        onPick = ::pick
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        error ?: when {
                            start == null -> "请选择开始日期"
                            end == null -> "请选择结束日期"
                            else -> "${formatDay(start!!)} 至 ${formatDay(end!!)}"
                        },
                        modifier = Modifier.padding(top = 12.dp),
                        style = AppType.tiny.copy(
                            color = if (error != null || start == null || end == null) colors.danger else colors.muted
                        )
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DialogButton("取消", colors.controlBackground, colors.text, onCancel)
                        DialogButton(
                            "确定",
                            colors.accent,
                            if (colors.isDark) colors.background else Color.White,
                            ::submit
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    start: LocalDate?,
    end: LocalDate?,
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
    Column(modifier) {
        Text(
            "${month.year}年${month.monthValue}月",
            modifier = Modifier.align(Alignment.CenterHorizontally),
            style = AppType.bodySmall.copy(color = colors.text)
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            WeekdayLabels.forEach { label ->
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    style = AppType.tiny.copy(color = colors.muted, fontSize = 10.sp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        for (week in 0 until 6) {
            Row(Modifier.fillMaxWidth()) {
                for (day in 0 until 7) {
                    val date = cells[week * 7 + day]
                    Box(
                        Modifier.weight(1f).height(30.dp).padding(1.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (date != null) {
                            CalendarDay(
                                date = date,
                                isStart = date == start,
                                isEnd = date == end,
                                inRange = start != null && end != null && !date.isBefore(start) && !date.isAfter(end),
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

@Composable
private fun CalendarDay(
    date: LocalDate,
    isStart: Boolean,
    isEnd: Boolean,
    inRange: Boolean,
    colors: StatsColors,
    onClick: () -> Unit
) {
    val endpoint = isStart || isEnd
    val normal = when {
        endpoint -> colors.accent
        inRange -> colors.accentSoft
        else -> Color.Transparent
    }
    val hover = if (endpoint) colors.accent else colors.hover
    HoverColor(normal, hover) { background ->
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxSize(),
            shape = AppShape(7.dp),
            color = background
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    date.dayOfMonth.toString(),
                    style = AppType.tiny.copy(
                        color = if (endpoint) {
                            if (colors.isDark) colors.background else Color.White
                        } else {
                            colors.text
                        }
                    )
                )
            }
        }
    }
}

@Composable
private fun CalendarNavButton(label: String, description: String, onClick: () -> Unit) {
    val colors = LocalStatsColors.current
    Surface(
        onClick = onClick,
        modifier = Modifier
            .height(26.dp)
            .widthIn(min = 26.dp)
            .semantics { contentDescription = description },
        shape = AppShape(6.dp),
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
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = AppShape(8.dp),
        color = background
    ) {
        Box(
            Modifier.height(34.dp).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(label, style = AppType.action.copy(color = foreground))
        }
    }
}
