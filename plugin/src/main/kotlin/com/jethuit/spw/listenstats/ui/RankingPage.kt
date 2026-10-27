@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, com.moriafly.salt.ui.UnstableSaltUiApi::class)

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.moriafly.salt.ui.BasicButton
import com.moriafly.salt.ui.ButtonColors
import com.moriafly.salt.ui.ControlSize
import com.moriafly.salt.ui.button.PillButton
import com.moriafly.salt.ui.SaltTheme
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jethuit.spw.listenstats.data.CoverDiagnostics
import com.jethuit.spw.listenstats.data.CoverStore
import com.jethuit.spw.listenstats.data.RankedEntry
import com.jethuit.spw.listenstats.data.RankingEntry
import com.jethuit.spw.listenstats.data.RankingMetric
import com.jethuit.spw.listenstats.data.RankingMode
import com.jethuit.spw.listenstats.util.formatDurationSeconds
import com.jethuit.spw.listenstats.util.formatNumber
import kotlinx.coroutines.delay

const val RANKING_PAGE_SIZE = 25
private val RankingCoverSize = 48.dp
private val RankingCoverRadius = 4.dp
// 65dp content plus a 1dp gap preserves the host's approximately 66dp row pitch.
private val RankingRowHeight = 65.dp
private val RankingRowText = AppType.table.copy(fontSize = 14.sp, lineHeight = 20.sp)
private val RankingSecondaryText = AppType.tiny.copy(fontSize = 12.sp, lineHeight = 16.sp)

fun rankingTotalPages(count: Int, pageSize: Int = RANKING_PAGE_SIZE): Int =
    if (count <= 0) 1 else (count - 1) / pageSize.coerceAtLeast(1) + 1

fun rankingSafePage(count: Int, page: Int, pageSize: Int = RANKING_PAGE_SIZE): Int =
    page.coerceIn(1, rankingTotalPages(count, pageSize))

private data class RankingPanel(
    val mode: RankingMode,
    val metric: RankingMetric,
    val page: Int,
    val pageSize: Int,
    val rows: List<RankedEntry>,
    val query: String
)

@Composable
fun RankingPage(
    entries: List<RankedEntry>,
    mode: RankingMode,
    onModeChange: (RankingMode) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    page: Int,
    onPageChange: (Int) -> Unit,
    pageSize: Int = RANKING_PAGE_SIZE,
    metric: RankingMetric = RankingMetric.Count,
    onMetricChange: (RankingMetric) -> Unit = {},
    loading: Boolean = false
) {
    val colors = LocalStatsColors.current
    var searchState by remember { mutableStateOf(TextFieldState(query)) }
    var searchExpanded by remember { mutableStateOf(query.isNotBlank()) }
    val focusManager = LocalFocusManager.current
    val closeSearch = {
        searchState = TextFieldState("")
        onQueryChange("")
        searchExpanded = false
        focusManager.clearFocus()
    }
    val searchText = searchState.text.toString()
    LaunchedEffect(searchText) {
        if (searchText != query) onQueryChange(searchText)
    }
    val filtered = entries
    val size = pageSize.coerceAtLeast(1)
    val totalPages = rankingTotalPages(filtered.size, size)
    val currentPage = rankingSafePage(filtered.size, page, size)
    val from = (currentPage - 1) * size
    val to = from + minOf(size, filtered.size - from)
    val rows = filtered.subList(from, to)

    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            val searchWidth = (maxWidth - 92.dp).coerceIn(100.dp, 160.dp)
            val controls: @Composable () -> Unit = {
                CompactMenuButton(
                    description = "排行类型",
                    options = listOf(RankingMode.Song.storage to "标题", RankingMode.Artist.storage to "艺术家",
                        RankingMode.Album.storage to "专辑"),
                    selected = mode.storage,
                    onSelected = { onModeChange(RankingMode.fromStorage(it)) },
                    checkable = true
                ) { HostToolbarIcon("ic_sort_a_z", IconPaths.QueueMusic, colors.text) }
                CompactMenuButton(
                    description = "排行指标",
                    options = listOf(RankingMetric.Count.storage to "播放次数",
                        RankingMetric.Duration.storage to "收听时长"),
                    selected = metric.storage,
                    onSelected = { onMetricChange(RankingMetric.fromStorage(it)) },
                    checkable = true
                ) { AppIcon(IconPaths.BarChart, colors.text, iconSize = 20.dp) }
                SearchExpansion(searchExpanded) { expanded ->
                    if (expanded) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RankingSearch(searchState, mode, searchWidth, searchExpanded, closeSearch)
                            Box(Modifier.width(12.dp))
                            RankingIconButton("关闭搜索", closeSearch, outlined = true) {
                                AppIcon(IconPaths.Close, colors.text, iconSize = 16.dp)
                            }
                        }
                    } else {
                        RankingIconButton("搜索", { searchExpanded = true }) {
                            HostToolbarIcon("ic_search", IconPaths.Search, colors.text)
                        }
                    }
                }
            }
            if (maxWidth < 600.dp) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppIcon(IconPaths.QueueMusic, colors.muted, iconSize = 19.dp)
                        Text(mode.title(metric), style = AppType.sectionTitle.copy(color = colors.text,
                            fontSize = 18.sp, lineHeight = 24.sp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically) { controls() }
                }
            } else {
                SectionHeading(
                    icon = IconPaths.QueueMusic,
                    title = mode.title(metric),
                    headingHeight = 34.dp,
                    titleStyle = AppType.sectionTitle.copy(fontSize = 18.sp, lineHeight = 24.sp)
                ) { controls() }
            }
        }
        DirectionalContent(
            target = RankingPanel(mode, metric, currentPage, size, rows, query),
            contentKey = { "${it.metric.storage}:${it.mode.storage}:${it.page}:${it.pageSize}" },
            direction = { from, to ->
                if (from.metric != to.metric) to.metric.ordinal.compareTo(from.metric.ordinal)
                else if (from.mode == to.mode) to.page.compareTo(from.page)
                else to.mode.ordinal.compareTo(from.mode.ordinal)
            },
            label = "ranking-panel"
        ) { shown ->
            Column(Modifier.fillMaxWidth()) {
                SongTableHeader(shown.mode, shown.metric)
                shown.rows.forEach { rank ->
                    key(rank.value.key) {
                        SongRow(rank.value, rank.position, shown.metric)
                    }
                }
                if (shown.rows.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (loading) {
                                "正在整理排行…"
                            } else if (shown.query.isBlank()) {
                                if (shown.metric == RankingMetric.Duration) "还没有收听时长记录的${shown.mode.label}"
                                else "还没有达到计次条件的${shown.mode.label}"
                            } else "没有找到匹配的${shown.mode.label}",
                            style = AppType.bodySmall.copy(color = colors.muted)
                        )
                    }
                }
            }
        }
        Box(Modifier.height(1.dp))
        Box(Modifier.height(16.dp))
        RankingFooter(filtered.size, currentPage, totalPages, onPageChange, mode, metric)
    }
}

@Composable
private fun RankingSearch(
    state: TextFieldState, mode: RankingMode, width: Dp, active: Boolean, onClose: () -> Unit
) {
    val colors = LocalStatsColors.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(active) { if (active) focusRequester.requestFocus() }
    Surface(
        modifier = Modifier.width(width).height(36.dp),
        shape = AppShape(100.dp),
        border = BorderStroke(Dp.Hairline, SolidColor(SaltTheme.colors.stroke)),
        color = SaltTheme.colors.subBackground
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                state = state,
                enabled = active,
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp).focusRequester(focusRequester)
                    .semantics { contentDescription = mode.searchPlaceholder }
                    .onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyUp && it.key == Key.Escape) { onClose(); true } else false
                    },
                lineLimits = TextFieldLineLimits.SingleLine,
                shape = AppShape(0.dp),
                contentPadding = PaddingValues(0.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.text,
                    unfocusedTextColor = colors.text,
                    disabledTextColor = colors.text,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    cursorColor = colors.accent,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent,
                    focusedPlaceholderColor = colors.muted,
                    unfocusedPlaceholderColor = colors.muted,
                    disabledPlaceholderColor = colors.muted
                ),
                textStyle = com.moriafly.salt.ui.LocalTextStyle.current.merge(AppType.bodySmall),
                placeholder = {
                    Text(
                        "搜索",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = AppType.bodySmall.copy(color = colors.muted)
                    )
                }
            )
        }
    }
}

@Composable
private fun RankingIconButton(
    label: String, onClick: () -> Unit, outlined: Boolean = false, icon: @Composable () -> Unit
) {
    val colors = LocalStatsColors.current
    if (outlined) {
        // The host's ScreenSearchBar.CloseButton delegates to this native control:
        // circular sub-material fill, one physical pixel stroke and native feedback.
        PillButton(
            onClick = onClick,
            modifier = Modifier.semantics { contentDescription = label },
            icon = icon
        )
        return
    }
    BasicButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp).semantics { contentDescription = label },
        size = ControlSize.Regular,
        shape = AppShape(8.dp),
        colors = ButtonColors(Color.Transparent, colors.text, Color.Transparent, colors.muted),
        contentPadding = PaddingValues(0.dp)
    ) { icon() }
}

@Composable
fun RankingFooter(count: Int, currentPage: Int, totalPages: Int, onPageChange: (Int) -> Unit,
                  mode: RankingMode = RankingMode.Song,
                  metric: RankingMetric = RankingMetric.Count) {
    val colors = LocalStatsColors.current
    val caption = if (metric == RankingMetric.Duration) {
        "共 ${formatNumber(count.toLong())} ${mode.itemUnit} · 按实际收听时长累计"
    } else {
        "共 ${formatNumber(count.toLong())} ${mode.itemUnit} · 听满单曲 50% 计一次"
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 840.dp) {
            val compact = maxWidth < 440.dp
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (compact) {
                    Text(caption, style = AppType.tiny.copy(color = colors.muted), maxLines = 1)
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            caption, modifier = Modifier.weight(1f),
                            style = AppType.tiny.copy(color = colors.muted), maxLines = 1
                        )
                        Box(Modifier.width(12.dp))
                        PageJumpControl(currentPage, totalPages, onPageChange)
                    }
                }
                if (totalPages > 1) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Pagination(currentPage, totalPages, onPageChange, compact)
                    }
                }
                if (compact) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        PageJumpControl(currentPage, totalPages, onPageChange)
                    }
                }
            }
        } else {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                Text(
                    caption,
                    style = AppType.tiny.copy(color = colors.muted),
                    maxLines = 1
                )
            }
            if (totalPages > 1) {
                Pagination(currentPage, totalPages, onPageChange)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                PageJumpControl(currentPage, totalPages, onPageChange)
            }
        }
        }
    }
}

@Composable
private fun SongTableHeader(mode: RankingMode, metric: RankingMetric) {
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
                if (mode == RankingMode.Song) "标题" else mode.label,
                modifier = Modifier.width(widths.song),
                style = AppType.tableHead.copy(color = colors.muted)
            )
            if (widths.album > 0.dp) Text(
                if (mode == RankingMode.Song) "专辑" else "歌曲数",
                modifier = Modifier.width(widths.album),
                style = AppType.tableHead.copy(color = colors.muted)
            )
            Text(
                metric.header,
                modifier = Modifier.width(widths.count),
                style = AppType.tableHead.copy(color = colors.muted),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        Box(Modifier.height(1.dp))
    }
}

@Composable
private fun SongRow(rank: RankingEntry, position: Int, metric: RankingMetric) {
    val colors = LocalStatsColors.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widths = tableWidths(maxWidth)
        HoverColor(colors.pageBackground, colors.rankingRowHover) { background ->
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(RankingRowHeight)
                        .drawWith { drawRect(color = background) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "$position",
                        modifier = Modifier.width(widths.rank),
                        style = RankingRowText.copy(color = colors.muted),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Row(
                        Modifier.width(widths.song).padding(end = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (rank.coverTrackId != null) Cover(rank.coverTrackId) else ArtistMonogram(rank.title)
                        Column(Modifier.weight(1f)) {
                            Text(
                                rank.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = RankingRowText.copy(
                                    color = colors.text,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Normal
                                )
                            )
                            val subtitle = rank.subtitle.ifEmpty { if (widths.album == 0.dp) rank.detail else "" }
                            if (subtitle.isNotEmpty()) Text(
                                subtitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = RankingSecondaryText.copy(color = colors.muted)
                            )
                        }
                    }
                    if (widths.album > 0.dp) Text(
                        rank.detail,
                        modifier = Modifier.width(widths.album).padding(end = 16.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = RankingRowText.copy(color = colors.muted)
                    )
                    Row(
                        Modifier.width(widths.count),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        if (metric == RankingMetric.Duration) {
                            Text(
                                formatDurationSeconds(rank.durationMs),
                                style = RankingRowText.copy(color = colors.text)
                            )
                        } else {
                            Text(
                                formatNumber(rank.count),
                                style = RankingRowText.copy(color = colors.text)
                            )
                            Text(
                                "次",
                                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                                style = AppType.tiny.copy(color = colors.muted)
                            )
                        }
                    }
                }
                Box(Modifier.height(1.dp))
            }
        }
    }
}

@Composable
private fun ArtistMonogram(name: String) {
    val colors = LocalStatsColors.current
    Surface(modifier = Modifier.size(RankingCoverSize), shape = AppShape(RankingCoverRadius), color = colors.subtle,
        border = BorderStroke(1.dp, SolidColor(colors.line))) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(name.take(1), style = AppType.sectionTitle.copy(color = colors.muted))
        }
    }
}

@Composable
internal fun Cover(trackId: String) {
    val colors = LocalStatsColors.current
    val targetPx = with(LocalDensity.current) { RankingCoverSize.roundToPx() }
    var image by remember(trackId, targetPx) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(trackId, targetPx) {
        if (!CoverStore.canLoad(trackId)) return@LaunchedEffect
        while (image == null) {
            val started = System.nanoTime()
            image = CoverStore.load(trackId, targetPx)
            CoverDiagnostics.display(
                trackId,
                targetPx,
                (System.nanoTime() - started) / 1_000_000,
                image != null
            )
            if (image == null) delay(CoverStore.retryDelayMillis(trackId))
        }
    }
    Surface(
        modifier = Modifier.size(RankingCoverSize),
        shape = AppShape(RankingCoverRadius),
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

internal data class TableWidths(
    val rank: Dp,
    val song: Dp,
    val album: Dp,
    val count: Dp
)

internal fun tableWidths(maxWidth: Dp): TableWidths {
    if (maxWidth < 440.dp) {
        val rank = 36.dp
        val count = 72.dp
        return TableWidths(rank, (maxWidth - rank - count).coerceAtLeast(0.dp), 0.dp, count)
    }
    val rank = 48.dp
    val count = 96.dp
    val remaining = (maxWidth - rank - count).coerceAtLeast(0.dp)
    val song = remaining * 0.56f
    val album = remaining - song
    return TableWidths(rank, song, album, count)
}

@Composable
private fun PageJumpControl(
    current: Int,
    total: Int,
    onPage: (Int) -> Unit
) {
    val colors = LocalStatsColors.current
    val state = remember(current) { TextFieldState(current.toString()) }

    fun submit() {
        val target = state.text.toString().trim().toIntOrNull() ?: return
        onPage(target.coerceIn(1, total))
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "共 $total 页",
            style = AppType.tiny.copy(color = colors.muted)
        )
        if (total > 1) {
            Text("·", style = AppType.tiny.copy(color = colors.muted))
            Text("跳转至", style = AppType.tiny.copy(color = colors.muted))
            OutlinedTextField(
                state = state,
                modifier = Modifier
                    .width(42.dp)
                    .height(34.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                            submit()
                            true
                        } else {
                            false
                        }
                    },
                lineLimits = TextFieldLineLimits.SingleLine,
                shape = AppShape(8.dp),
                contentPadding = PaddingValues(0.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.text,
                    unfocusedTextColor = colors.text,
                    focusedContainerColor = colors.controlBackground,
                    unfocusedContainerColor = colors.controlBackground,
                    cursorColor = colors.accent,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent
                ),
                textStyle = com.moriafly.salt.ui.LocalTextStyle.current
                    .merge(AppType.tiny)
                    .copy(textAlign = TextAlign.Center)
            )
            Text("页", style = AppType.tiny.copy(color = colors.muted))
        }
    }
}

@Composable
private fun Pagination(
    current: Int,
    total: Int,
    onPage: (Int) -> Unit,
    compact: Boolean = false
) {
    val colors = LocalStatsColors.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PageArrowButton(IconPaths.KeyboardArrowLeft, "上一页", current > 1) { onPage(current - 1) }
        Surface(
            shape = AppShape(8.dp),
            color = colors.controlBackground
        ) {
            Row(
                Modifier.height(34.dp).padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                pageItems(current, total, compact).forEach { item ->
                    if (item == null) {
                        Text(
                            "…",
                            modifier = Modifier.padding(horizontal = 3.dp),
                            style = AppType.tiny.copy(color = colors.muted)
                        )
                    } else {
                        PageButton("$item", item == current, true, compact) { onPage(item) }
                    }
                }
            }
        }
        PageArrowButton(IconPaths.KeyboardArrowRight, "下一页", current < total) { onPage(current + 1) }
    }
}

@Composable
private fun PageArrowButton(
    pathData: String,
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
            color = Color.Transparent
        ) {
            Box(
                Modifier.size(34.dp),
                contentAlignment = Alignment.Center
            ) {
                AppIcon(
                    pathData,
                    if (enabled) colors.muted else colors.muted.copy(alpha = 0.45f),
                    iconSize = 24.dp,
                    viewBoxSize = 24f,
                    positiveCoordinates = true
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
    compact: Boolean = false,
    onClick: () -> Unit
) {
    val colors = LocalStatsColors.current
    val color = when {
        !enabled -> colors.muted.copy(alpha = 0.45f)
        else -> colors.text
    }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(
            onClick = { if (enabled) onClick() },
            shape = AppShape(8.dp),
            color = if (active) colors.selectedControlBackground else Color.Transparent
        ) {
            Box(
                Modifier.height(28.dp).padding(horizontal = if (compact) 4.dp else 7.dp)
                    .widthIn(min = if (compact) 24.dp else 28.dp),
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

private fun pageItems(current: Int, total: Int, compact: Boolean = false): List<Int?> {
    if (total <= if (compact) 5 else 7) return (1..total).toList()
    val window = when {
        compact && current == 1 -> 1..2
        compact && current == total -> (total - 1)..total
        compact -> current..current
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
