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
import com.jethuit.spw.listenstats.ui.StatsText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jethuit.spw.listenstats.data.CoverDiagnostics
import com.jethuit.spw.listenstats.data.CoverStore
import com.jethuit.spw.listenstats.data.StatsData
import com.jethuit.spw.listenstats.data.TrackRank
import com.jethuit.spw.listenstats.util.formatNumber
import kotlinx.coroutines.delay

const val RANKING_PAGE_SIZE = 25

fun rankingTotalPages(count: Int): Int =
    if (count <= 0) 1 else (count + RANKING_PAGE_SIZE - 1) / RANKING_PAGE_SIZE

fun rankingSafePage(count: Int, page: Int): Int =
    page.coerceIn(1, rankingTotalPages(count))

@Composable
fun RankingPage(
    data: StatsData,
    query: String,
    onQueryChange: (String) -> Unit,
    page: Int,
    onPageChange: (Int) -> Unit
) {
    val colors = LocalStatsColors.current
    val searchState = remember { TextFieldState(query) }
    val searchText = searchState.text.toString()
    var searchFocused by remember { mutableStateOf(false) }
    LaunchedEffect(searchText) {
        if (searchText != query) onQueryChange(searchText)
    }
    val filtered = remember(data, query) {
        data.rankedTracks(query)
    }
    val totalPages = rankingTotalPages(filtered.size)
    val currentPage = rankingSafePage(filtered.size, page)
    val from = (currentPage - 1) * RANKING_PAGE_SIZE
    val to = minOf(from + RANKING_PAGE_SIZE, filtered.size)
    val rows = filtered.subList(from, to)

    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            SectionHeading(
                icon = IconPaths.QueueMusic,
                title = "歌曲播放次数",
                headingHeight = 34.dp,
                titleStyle = AppType.sectionTitle.copy(fontSize = 18.sp, lineHeight = 24.sp)
            ) {
                Surface(
                    modifier = Modifier.width(190.dp).height(34.dp),
                    shape = AppShape(8.dp),
                    color = colors.controlBackground
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
                            textStyle = com.moriafly.salt.ui.LocalTextStyle.current.merge(AppType.bodySmall),
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
        }
        SongTableHeader()
        rows.forEach { rank ->
            key(rank.value.track.id) {
                SongRow(rank.value, rank.position)
            }
        }
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (query.isBlank()) "还没有达到计次条件的歌曲" else "没有找到匹配的歌曲",
                    style = AppType.bodySmall.copy(color = colors.muted)
                )
            }
        }
        Box(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "共 ${formatNumber(filtered.size.toLong())} 首 · 听满单曲 50% 计一次",
                style = AppType.tiny.copy(color = colors.muted)
            )
            if (totalPages > 1) {
                Box(Modifier.weight(1f))
                Text(
                    "第 $currentPage / $totalPages 页",
                    modifier = Modifier.padding(end = 10.dp),
                    style = AppType.tiny.copy(color = colors.muted)
                )
                Pagination(currentPage, totalPages, onPageChange)
            }
        }
    }
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
private fun SongRow(rank: TrackRank, position: Int) {
    val colors = LocalStatsColors.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widths = tableWidths(maxWidth)
        HoverColor(colors.pageBackground, colors.subtle) { background ->
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
                        Cover(rank.track.id)
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
private fun Cover(trackId: String) {
    val colors = LocalStatsColors.current
    val targetPx = with(LocalDensity.current) { 44.dp.roundToPx() }
    var image by remember(trackId, targetPx) { mutableStateOf<ImageBitmap?>(null) }
    var retry by remember(trackId, targetPx) { mutableIntStateOf(0) }
    LaunchedEffect(trackId, targetPx, retry) {
        val started = System.nanoTime()
        image = CoverStore.load(trackId, targetPx)
        CoverDiagnostics.display(
            trackId,
            targetPx,
            (System.nanoTime() - started) / 1_000_000,
            image != null
        )
        if (image == null && retry == 0) {
            delay(30_000)
            retry++
        }
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
    Row(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PageArrowButton("‹", "上一页", current > 1) { onPage(current - 1) }
        Surface(
            shape = AppShape(8.dp),
            color = colors.controlBackground
        ) {
            Row(
                Modifier.height(34.dp).padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
            }
        }
        PageArrowButton("›", "下一页", current < total) { onPage(current + 1) }
    }
}

@Composable
private fun PageArrowButton(
    text: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalStatsColors.current
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(
            onClick = { if (enabled) onClick() },
            modifier = Modifier.semantics { contentDescription = description },
            shape = AppShape(8.dp),
            color = colors.controlBackground
        ) {
            Box(
                Modifier.size(34.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text,
                    style = AppType.bodySmall.copy(
                        color = if (enabled) colors.text else colors.muted.copy(alpha = 0.45f)
                    )
                )
            }
        }
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
            shape = AppShape(5.dp),
            color = if (active) colors.background else Color.Transparent
        ) {
            Box(
                Modifier.height(28.dp).padding(horizontal = 7.dp).widthIn(min = 28.dp),
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
    val window = when {
        current <= 3 -> 1..4
        current >= total - 2 -> (total - 3)..total
        else -> (current - 1)..(current + 1)
    }
    val pages = sortedSetOf(1, total)
    pages.addAll(window)
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
