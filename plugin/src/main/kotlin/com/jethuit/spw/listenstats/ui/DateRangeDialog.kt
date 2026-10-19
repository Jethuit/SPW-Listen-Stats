package com.jethuit.spw.listenstats.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.unit.dp
import com.jethuit.spw.listenstats.ui.StatsText as Text
import com.moriafly.salt.ui.LocalTextStyle
import java.time.LocalDate

@Composable
fun DateRangeDialog(
    initialStart: LocalDate,
    initialEnd: LocalDate,
    onCancel: () -> Unit,
    onConfirm: (LocalDate, LocalDate) -> Unit
) {
    val colors = LocalStatsColors.current
    val startState = remember { TextFieldState(initialStart.toString()) }
    val endState = remember { TextFieldState(initialEnd.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    val startFocus = remember { FocusRequester() }

    fun submit() {
        val start = runCatching { LocalDate.parse(startState.text.toString().trim()) }.getOrNull()
        val end = runCatching { LocalDate.parse(endState.text.toString().trim()) }.getOrNull()
        when {
            start == null || end == null -> error = "请输入有效日期，格式为 YYYY-MM-DD"
            start.isAfter(end) -> error = "开始日期不能晚于结束日期"
            else -> {
                error = null
                onConfirm(start, end)
            }
        }
    }

    LaunchedEffect(startState.text, endState.text) { error = null }
    LaunchedEffect(Unit) { runCatching { startFocus.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        Surface(
            onClick = onCancel,
            modifier = Modifier.fillMaxSize(),
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
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .widthIn(max = 400.dp),
                shape = AppShape(14.dp),
                color = colors.background,
                border = BorderStroke(1.dp, SolidColor(colors.line))
            ) {
                Column(Modifier.padding(22.dp)) {
                    Text(
                        "自定义日期范围",
                        style = AppType.sectionTitle.copy(color = colors.text)
                    )
                    Text(
                        "开始和结束日期，格式 YYYY-MM-DD",
                        modifier = Modifier.padding(top = 5.dp, bottom = 16.dp),
                        style = AppType.tiny.copy(color = colors.muted)
                    )
                    DateField(
                        label = "开始日期",
                        state = startState,
                        focus = startFocus,
                        colors = colors
                    )
                    Box(Modifier.height(10.dp))
                    DateField(
                        label = "结束日期",
                        state = endState,
                        focus = null,
                        colors = colors
                    )
                    if (error != null) {
                        Text(
                            error!!,
                            modifier = Modifier.padding(top = 10.dp),
                            style = AppType.tiny.copy(color = colors.danger)
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 18.dp),
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
private fun DateField(
    label: String,
    state: TextFieldState,
    focus: FocusRequester?,
    colors: StatsColors
) {
    Text(label, style = AppType.tiny.copy(color = colors.muted))
    Box(Modifier.height(7.dp))
    Surface(
        modifier = Modifier.fillMaxWidth().height(38.dp),
        shape = AppShape(8.dp),
        color = colors.controlBackground
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            OutlinedTextField(
                state = state,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (focus != null) Modifier.focusRequester(focus) else Modifier),
                lineLimits = TextFieldLineLimits.SingleLine,
                shape = AppShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.text,
                    unfocusedTextColor = colors.text,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    cursorColor = colors.accent,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent
                ),
                textStyle = LocalTextStyle.current.merge(AppType.bodySmall.copy(color = colors.text))
            )
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
