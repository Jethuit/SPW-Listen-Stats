@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.jethuit.spw.listenstats.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import com.moriafly.salt.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jethuit.spw.listenstats.data.CoverStore
import com.jethuit.spw.listenstats.data.StatsData
import com.jethuit.spw.listenstats.data.TrackRank
import com.jethuit.spw.listenstats.util.formatNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PAGE_SIZE = 50

@Composable
fun RankingPage(
    data: StatsData,
    query: String,
    onQueryChange: (String) -> Unit,
    page: Int,
    onPageChange: (Int) -> Unit
) {
    val colors = LocalStatsColors.current
    val all = remember(data) { data.sortedTracks() }
    val searchState = remember { TextFieldState(query) }
    val searchText = searchState.text.toString()
    var searchFocused by remember { mutableStateOf(false) }
    LaunchedEffect(searchText) {
        if (searchText != query) onQueryChange(searchText)
    }
    val filtered = remember(all, query) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) all else all.filter {
            it.track.title.lowercase().contains(needle) || it.track.artist.lowercase().contains(needle)
        }
    }
    val totalPages = if (filtered.isEmpty()) 1 else (filtered.size + PAGE_SIZE - 1) / PAGE_SIZE
    val currentPage = page.coerceIn(1, totalPages)
    val from = (currentPage - 1) * PAGE_SIZE
    val to = minOf(from + PAGE_SIZE, filtered.size)
    val rows = filtered.subList(from, to)

    Column(Modifier.fillMaxWidth()) {
        SectionHeading(
            icon = IconPaths.QueueMusic,
            title = "歌曲播放次数",
            headingHeight = 34.dp
        ) {
            Surface(
                modifier = Modifier.width(190.dp).height(34.dp),
                shape = AppShape(7.dp),
                color = colors.background,
                border = BorderStroke(
                    if (searchFocused) 2.dp else 1.dp,
                    SolidColor(if (searchFocused) colors.accent else colors.line)
                )
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppIcon(IconPaths.Search, colors.muted, iconSize = 15.dp)
                    OutlinedTextField(
                        state = searchState,
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .onFocusChanged { searchFocused = it.isFocused },
                        lineLimits = TextFieldLineLimits.SingleLine,
                        shape = AppShape(0.dp),
                        contentPadding = PaddingValues(0.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colors.text,
                            unfocusedTextColor = colors.text,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            cursorColor = colors.accent,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedPlaceholderColor = colors.muted,
                            unfocusedPlaceholderColor = colors.muted
                        ),
                        textStyle = AppType.bodySmall,
                        placeholder = {
                            Text(
                                "搜索歌曲或歌手",
                                style = AppType.bodySmall.copy(color = colors.muted)
                            )
                        }
                    )
                }
            }
        }
        Box(Modifier.height(15.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                pageSummary(filtered.size, from, to),
                style = AppType.tiny.copy(color = colors.muted)
            )
        }
        Box(Modifier.height(8.dp))
        SongTableHeader()
        rows.forEachIndexed { index, rank ->
            SongRow(rank, from + index + 1, data.loadedAt)
        }
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (query.isBlank()) "还没有达到计次条件的歌曲" else "没有找到匹配的歌曲",
                    style = AppType.bodySmall.copy(color = colors.muted)
                )
            }
        }
        if (totalPages > 1) {
            Box(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Pagination(currentPage, totalPages, onPageChange)
            }
        }
        Box(Modifier.height(12.dp))
        Text(
            "每首歌独立累计 · 实际听满歌曲时长的 50% 计一次 · 不受概览页年份与日期筛选影响",
            style = AppType.tiny.copy(color = colors.muted)
        )
    }
}

private fun pageSummary(total: Int, from: Int, to: Int): String {
    if (total <= 0) return "暂无歌曲"
    return "共 ${formatNumber(total.toLong())} 首 · 第 ${from + 1}–${to} 首"
}

@Composable
private fun SongTableHeader() {
    val colors = LocalStatsColors.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widths = tableWidths(maxWidth)
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Text(
                "排名",
                modifier = Modifier.width(widths.rank),
                style = AppType.tableHead.copy(color = colors.muted),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Text(
                "歌曲",
                modifier = Modifier.width(widths.song),
                style = AppType.tableHead.copy(color = colors.muted)
            )
            Text(
                "专辑",
                modifier = Modifier.width(widths.album),
                style = AppType.tableHead.copy(color = colors.muted)
            )
            Text(
                "播放次数",
                modifier = Modifier.width(widths.count),
                style = AppType.tableHead.copy(color = colors.muted),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .drawWith { drawRect(color = colors.line) }
        )
    }
}

@Composable
private fun SongRow(rank: TrackRank, position: Int, coverRevision: Long) {
    val colors = LocalStatsColors.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widths = tableWidths(maxWidth)
        HoverColor(colors.background, colors.subtle) { background ->
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(70.dp)
                        .drawWith { drawRect(color = background) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "$position",
                        modifier = Modifier.width(widths.rank),
                        style = AppType.table.copy(color = colors.muted),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Row(
                        Modifier.width(widths.song).padding(end = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Cover(rank.track.id, coverRevision)
                        Column {
                            Text(
                                rank.track.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = AppType.table.copy(
                                    color = colors.text,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                )
                            )
                            Text(
                                rank.track.artist,
                                modifier = Modifier.padding(top = 5.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = AppType.tiny.copy(color = colors.muted)
                            )
                        }
                    }
                    Text(
                        rank.track.album,
                        modifier = Modifier.width(widths.album).padding(end = 16.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = AppType.table.copy(color = colors.muted)
                    )
                    Row(
                        Modifier.width(widths.count),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text(
                            formatNumber(rank.count.toLong()),
                            style = AppType.table.copy(color = colors.text)
                        )
                        Text(
                            "次",
                            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                            style = AppType.tiny.copy(color = colors.muted)
                        )
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .drawWith { drawRect(color = colors.line) }
                )
            }
        }
    }
}

@Composable
private fun Cover(trackId: String, retryKey: Long) {
    val colors = LocalStatsColors.current
    var image by remember(trackId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(trackId, retryKey) {
        if (image == null) image = withContext(Dispatchers.IO) { CoverStore.load(trackId) }
    }
    Surface(
        modifier = Modifier.size(44.dp),
        shape = AppShape(6.dp),
        color = colors.subtle,
        border = BorderStroke(1.dp, SolidColor(colors.line))
    ) {
        val bitmap = image
        if (bitmap != null) {
            Box(
                Modifier.fillMaxSize().drawWith {
                    val sx = size.width / bitmap.width.toFloat()
                    val sy = size.height / bitmap.height.toFloat()
                    withTransform({ scale(sx, sy, pivot = Offset.Zero) }) {
                        drawImage(bitmap)
                    }
                }
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AppIcon(IconPaths.QueueMusic, colors.muted, iconSize = 22.dp)
            }
        }
    }
}

private data class TableWidths(
    val rank: Dp,
    val song: Dp,
    val album: Dp,
    val count: Dp
)

private fun tableWidths(maxWidth: Dp): TableWidths {
    val rank = 48.dp
    val count = 96.dp
    val remaining = (maxWidth - rank - count).coerceAtLeast(0.dp)
    val song = remaining * 0.56f
    val album = remaining - song
    return TableWidths(rank, song, album, count)
}

@Composable
private fun Pagination(
    current: Int,
    total: Int,
    onPage: (Int) -> Unit
) {
    val colors = LocalStatsColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        PageButton("上一页", false, current > 1) { onPage(current - 1) }
        pageItems(current, total).forEach { item ->
            if (item == null) {
                Text(
                    "…",
                    modifier = Modifier.padding(horizontal = 3.dp),
                    style = AppType.tiny.copy(color = colors.muted)
                )
            } else {
                PageButton("$item", item == current, true) { onPage(item) }
            }
        }
        PageButton("下一页", false, current < total) { onPage(current + 1) }
    }
}

@Composable
private fun PageButton(
    text: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalStatsColors.current
    val color = when {
        !enabled -> colors.muted.copy(alpha = 0.45f)
        active -> colors.accent
        else -> colors.text
    }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(
            onClick = { if (enabled) onClick() },
            shape = AppShape(6.dp),
            color = if (active) colors.accentSoft else colors.background,
            border = BorderStroke(1.dp, SolidColor(if (active) colors.accent else colors.line))
        ) {
            Box(
                Modifier.height(30.dp).padding(horizontal = 9.dp).widthIn(min = 30.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text,
                    style = AppType.bodySmall.copy(color = color)
                )
            }
        }
    }
}

private fun pageItems(current: Int, total: Int): List<Int?> {
    if (total <= 7) return (1..total).toList()
    val pages = sortedSetOf(1, total, current - 1, current, current + 1)
    val valid = pages.filter { it in 1..total }
    val result = ArrayList<Int?>()
    var previous = 0
    for (page in valid) {
        if (previous > 0 && page - previous > 1) result.add(null)
        result.add(page)
        previous = page
    }
    return result
}
