@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, com.moriafly.salt.ui.UnstableSaltUiApi::class)

package com.jethuit.spw.listenstats.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.jethuit.spw.listenstats.PluginLog
import com.jethuit.spw.listenstats.PluginState
import com.jethuit.spw.listenstats.Prefs
import com.jethuit.spw.listenstats.updates.Updates
import com.moriafly.salt.ui.BasicButton
import com.moriafly.salt.ui.ButtonColors
import com.moriafly.salt.ui.ControlSize
import com.moriafly.salt.ui.Switcher
import com.moriafly.salt.ui.rememberScrollState
import com.moriafly.salt.ui.verticalScroll
import java.awt.Desktop
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AboutButton(onClick: () -> Unit) {
    val colors = LocalStatsColors.current
    val update by Updates.notice.collectAsState()
    Box(Modifier.size(40.dp)) {
        BasicButton(
            onClick = onClick,
            modifier = Modifier.fillMaxSize().semantics {
                contentDescription = if (update.hasUpdate) "关于听歌统计，有可用更新" else "关于听歌统计"
            },
            size = ControlSize.Regular,
            shape = AppShape(8.dp),
            colors = ButtonColors(Color.Transparent, colors.text, Color.Transparent, colors.muted),
            contentPadding = PaddingValues(0.dp)
        ) { AppIcon(IconPaths.Info, colors.text, iconSize = 20.dp) }
        if (update.hasUpdate) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).size(6.dp)
                .drawWith { drawCircle(colors.accent) })
        }
    }
}

@Composable
fun AboutDialog(onClose: () -> Unit) {
    val colors = LocalStatsColors.current
    val scope = rememberCoroutineScope()
    val closeFocus = remember { FocusRequester() }
    var storeEdition by remember { mutableStateOf<Boolean?>(null) }
    var saving by remember { mutableStateOf(false) }
    var openingLink by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val update by Updates.notice.collectAsState()

    LaunchedEffect(Unit) {
        runCatching { closeFocus.requestFocus() }
        storeEdition = withContext(Dispatchers.IO) { Prefs.microsoftStoreEdition() }
        if (storeEdition == null) error = "设置读取失败，请关闭后重试。"
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val dialogWidth = minOf(480.dp, (maxWidth - 32.dp).coerceAtLeast(0.dp))
        val dialogHeight = (maxHeight - 32.dp).coerceAtLeast(0.dp)
        // A clickable Surface adds a hover state layer over the entire dimmed area.
        Surface(modifier = Modifier.fillMaxSize()
            .focusProperties { canFocus = false }
            .pointerInput(onClose) { detectTapGestures { onClose() } },
            color = Color.Black.copy(alpha = 0.28f)) {}
        Box(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                onClose(); true
            } else false
        }, contentAlignment = Alignment.Center) {
            Box(Modifier.width(dialogWidth).heightIn(max = dialogHeight)
                .focusProperties { canFocus = false; onExit = { cancelFocusChange() } }.focusTarget()) {
                Surface(modifier = Modifier.matchParentSize(), color = colors.background,
                    shape = AppShape(14.dp), border = BorderStroke(1.dp, SolidColor(colors.line))) {}
                Box(Modifier.matchParentSize().onPointerEvent(PointerEventType.Press) { event ->
                    event.changes.forEach { it.consume() }
                })
                Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        StatsText("关于", Modifier.weight(1f),
                            style = AppType.sectionTitle.copy(color = colors.text))
                        StatsText("v${PluginState.pluginVersion.removePrefix("v")}",
                            style = AppType.body.copy(color = colors.muted))
                    }
                    Surface(modifier = Modifier.fillMaxWidth(), color = colors.controlBackground,
                        shape = AppShape(8.dp), border = BorderStroke(1.dp, SolidColor(colors.line))) {
                        BasicButton(
                            onClick = {
                                val previous = storeEdition
                                if (previous != null && !saving) {
                                    saving = true
                                    error = null
                                    scope.launch {
                                        val saved = withContext(Dispatchers.IO) {
                                            Prefs.setMicrosoftStoreEdition(!previous)
                                        }
                                        if (saved) {
                                            storeEdition = !previous
                                            Updates.preferenceChanged(!previous)
                                        }
                                        else error = "设置保存失败，请重试。"
                                        saving = false
                                    }
                                }
                            },
                            enabled = storeEdition != null && !saving,
                            modifier = Modifier.fillMaxWidth().semantics {
                                role = Role.Switch
                                toggleableState = if (storeEdition == true) ToggleableState.On else ToggleableState.Off
                            },
                            shape = AppShape(8.dp), size = ControlSize.Regular,
                            colors = ButtonColors(colors.controlBackground, colors.text,
                                colors.controlBackground, colors.muted),
                            contentPadding = PaddingValues(12.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    StatsText("为微软商店版启用更新提醒", style = AppType.body.copy(color = colors.text))
                                    StatsText("每天检查一次，检查失败时不打扰",
                                        style = AppType.tiny.copy(color = colors.muted))
                                }
                                Switcher(storeEdition == true)
                            }
                        }
                    }
                    val time = remember(update.checkedAt) {
                        if (update.checkedAt > 0) DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                            .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(update.checkedAt))
                        else "尚无记录"
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        StatsText("上次成功检查: $time", Modifier.weight(1f),
                            style = AppType.bodySmall.copy(color = colors.muted))
                        StatsText(when {
                            update.checking -> "正在检查…"
                            update.error -> "检查失败"
                            update.hasUpdate -> "有可用更新"
                            update.checkedAt > 0 -> "已是最新版本"
                            else -> "尚未检查"
                        }, style = AppType.bodySmall.copy(color = when {
                            update.checking -> colors.muted
                            update.error -> colors.danger
                            update.hasUpdate -> colors.accent
                            else -> colors.muted
                        }))
                    }
                    if (update.error && !update.checking) {
                        StatsText(update.message, style = AppType.tiny.copy(color = colors.danger))
                    }
                    error?.let { StatsText(it, style = AppType.tiny.copy(color = colors.danger)) }
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BasicButton(onClick = Updates::checkManually, enabled = !update.checking,
                            size = ControlSize.Regular,
                            colors = ButtonColors(colors.controlBackground, colors.text,
                                colors.controlBackground, colors.muted)) {
                            StatsText("检查更新", style = AppType.action.copy(color = colors.text))
                        }
                        BasicButton(onClick = {
                            if (!openingLink) scope.launch {
                                openingLink = true
                                val failure = withContext(Dispatchers.IO) {
                                    runCatching { Desktop.getDesktop().browse(URI(update.releaseUrl)) }.exceptionOrNull()
                                }
                                if (failure != null) {
                                    PluginLog.error("opening project page failed", failure)
                                    error = "无法打开浏览器：${update.releaseUrl}"
                                }
                                openingLink = false
                            }
                        }, enabled = !openingLink, size = ControlSize.Regular,
                            colors = ButtonColors(colors.controlBackground, colors.text,
                                colors.controlBackground, colors.muted)) {
                            StatsText("查看发布页", style = AppType.action.copy(color = colors.text))
                        }
                        Spacer(Modifier.weight(1f))
                        BasicButton(onClick = onClose, modifier = Modifier.focusRequester(closeFocus),
                            size = ControlSize.Regular,
                            colors = ButtonColors(colors.accent, Color.White, colors.accent, colors.muted)) {
                            StatsText("关闭", style = AppType.action.copy(color = Color.White))
                        }
                    }
                }
            }
        }
    }
}
