@file:OptIn(com.moriafly.salt.ui.UnstableSaltUiApi::class)

package com.jethuit.spw.listenstats.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jethuit.spw.listenstats.data.ChartBucket
import com.jethuit.spw.listenstats.data.ListeningDetails
import com.jethuit.spw.listenstats.util.formatDay
import com.jethuit.spw.listenstats.util.formatDurationSeconds
import com.moriafly.salt.ui.BasicButton
import com.moriafly.salt.ui.ButtonColors
import com.moriafly.salt.ui.Card as SaltCard
import com.moriafly.salt.ui.ControlSize
import com.jethuit.spw.listenstats.ui.StatsText as Text

@Composable
fun ListeningDetailsExpansion(visible: Boolean, details: ListeningDetails?, onClose: () -> Unit) {
    val duration = statsMotionDurationMillis()
    AnimatedVisibility(visible && details != null,
        enter = expandVertically(tween(duration, easing = PageEasing), expandFrom = Alignment.Top) + fadeIn(tween(duration)),
        exit = shrinkVertically(tween(duration, easing = PageEasing), shrinkTowards = Alignment.Top) + fadeOut(tween(duration))) {
        details?.let { shown ->
            Box(Modifier.animateContentSize(tween(duration, easing = PageEasing)).padding(top = 20.dp)) {
                ListeningDetailsPanel(shown.bucket, shown, onClose)
            }
        }
    }
}

@Composable
fun ListeningDetailsPanel(bucket: ChartBucket, details: ListeningDetails?, onClose: () -> Unit) {
    val colors = LocalStatsColors.current
    val current = details?.takeIf {
        it.bucket.start == bucket.start && it.bucket.end == bucket.end && it.bucket.hour == bucket.hour
    }
    val shown = current?.bucket ?: bucket
    val label = if (shown.hour != null || shown.start == shown.end) shown.longLabel
        else "${formatDay(shown.start)} – ${formatDay(shown.end)}"
    SaltCard(modifier = Modifier.fillMaxWidth(), shape = AppShape(12.dp), color = colors.cardBackground,
        border = BorderStroke(1.dp, SolidColor(colors.line))) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = AppType.sectionTitle.copy(color = colors.text))
                    Text("歌曲收听明细", modifier = Modifier.padding(top = 6.dp),
                        style = AppType.bodySmall.copy(color = colors.muted))
                }
                BasicButton(onClick = onClose,
                    modifier = Modifier.size(32.dp).semantics { contentDescription = "收起歌曲明细" },
                    size = ControlSize.Regular, shape = AppShape(8.dp),
                    colors = ButtonColors(Color.Transparent, colors.text, Color.Transparent, colors.muted),
                    contentPadding = PaddingValues(0.dp)) {
                    AppIcon(IconPaths.Close, colors.muted, iconSize = 18.dp)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Text("${formatDurationSeconds(shown.millis)} 收听时长", style = AppType.table.copy(color = colors.text))
                if (current != null) Text("${current.rows.size} 首歌曲",
                    style = AppType.table.copy(color = colors.text))
            }
            when {
                current == null -> Text("正在读取明细…", style = AppType.bodySmall.copy(color = colors.muted))
                current.rows.isEmpty() && current.unknownMillis == 0L -> Text("该时段没有听歌记录",
                    style = AppType.bodySmall.copy(color = colors.muted))
                current.rows.isNotEmpty() -> {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp, end = 12.dp)) {
                        Text("歌曲", Modifier.weight(1f).padding(start = 32.dp), style = AppType.tiny.copy(color = colors.muted))
                        Text("本时段收听", style = AppType.tiny.copy(color = colors.muted))
                    }
                    key(shown.start, shown.end, shown.hour) {
                        val scroll = rememberLazyListState()
                        Box(Modifier.fillMaxWidth().height((minOf(current.rows.size, 6) * 64).dp)) {
                            LazyColumn(Modifier.fillMaxSize().padding(end = 12.dp), state = scroll) {
                                itemsIndexed(current.rows, key = { _, row -> row.track.id }) { index, row ->
                                    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text("${index + 1}", Modifier.width(20.dp), style = AppType.tiny.copy(color = colors.muted))
                                        Cover(row.track.id)
                                        Column(Modifier.weight(1f)) {
                                            Text(row.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                style = AppType.table.copy(color = colors.text))
                                            Text(row.track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                style = AppType.tiny.copy(color = colors.muted))
                                        }
                                        Text(formatDurationSeconds(row.millis), style = AppType.bodySmall.copy(color = colors.text))
                                    }
                                }
                            }
                            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                        }
                    }
                }
            }
            if (current != null && current.unknownMillis > 0L) {
                Text("无法关联歌曲 · ${formatDurationSeconds(current.unknownMillis)}",
                    Modifier.padding(top = 16.dp), style = AppType.bodySmall.copy(color = colors.text))
                Text("这部分历史仅保留汇总时长，无法恢复对应歌曲。", Modifier.padding(top = 6.dp),
                    style = AppType.tiny.copy(color = colors.muted))
            }
            if (current?.rows?.any { it.approximateMillis > 0L } == true) {
                Text("包含近似历史记录，时段归属可能有偏差。", Modifier.padding(top = 16.dp),
                    style = AppType.tiny.copy(color = colors.muted))
            }
        }
    }
}
