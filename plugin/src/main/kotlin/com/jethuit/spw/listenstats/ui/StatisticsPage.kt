@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, com.moriafly.salt.ui.UnstableSaltUiApi::class)

package com.jethuit.spw.listenstats.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import com.jethuit.spw.listenstats.ui.StatsText as Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import com.jethuit.spw.listenstats.Prefs
import com.jethuit.spw.listenstats.data.ChartType
import com.jethuit.spw.listenstats.data.Granularity
import com.jethuit.spw.listenstats.data.RangePreset
import com.jethuit.spw.listenstats.data.RankingMode
import com.jethuit.spw.listenstats.data.RankingMetric
import com.jethuit.spw.listenstats.data.StatsCalculations
import com.moriafly.salt.ui.Card as SaltCard
import com.moriafly.salt.ui.BasicButton
import com.moriafly.salt.ui.ButtonColors
import com.moriafly.salt.ui.ControlSize
import com.moriafly.salt.ui.SaltDynamicColors
import com.moriafly.salt.ui.SaltTheme
import com.moriafly.salt.ui.rememberScrollState
import com.moriafly.salt.ui.saltConfigs
import com.moriafly.salt.ui.verticalScroll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

private fun savedDate(key: String): LocalDate? = Prefs.getString(key, "")
    .takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

enum class StatsPage { Overview, Ranking }

@Composable
fun ListenStatsApp() {
    val scrollState = rememberScrollState(0)
    val scope = rememberCoroutineScope()
    val scrollTop: () -> Unit = { scope.launch { scrollState.scrollTo(0) } }
    var entering by remember { mutableStateOf(true) }
    var reloadTick by remember { mutableStateOf(0) }
    val exiting = StatsHostState.closing
    val prepared = rememberPageStatistics(reloadTick, canApply = !entering && !exiting)
    val data = prepared.data
    val displayPreferences = prepared.preferences
    val today = prepared.today
    var page by remember {
        mutableStateOf(
            if (System.getProperty("spw.listenstats.startpage") == "ranking") {
                StatsPage.Ranking
            } else {
                StatsPage.Overview
            }
        )
    }
    var chartType by remember { mutableStateOf(ChartType.fromStorage(Prefs.getString("chartType", ChartType.Bar.storage))) }
    var userGranularity by remember { mutableStateOf(Granularity.fromStorage(Prefs.getString("granularity", Granularity.Day.storage))) }
    var rangePreset by remember { mutableStateOf(RangePreset.fromStorage(Prefs.getString("range", RangePreset.Today.storage))) }
    var selectedDate by remember { mutableStateOf(savedDate("selectedDate") ?: if (rangePreset == RangePreset.Selected) LocalDate.now() else null) }
    var heatmapYear by remember { mutableStateOf(today.year) }
    var heatmapRolling by remember { mutableStateOf(false) }
    var heatmapWeekly by remember { mutableStateOf(false) }
    var customStart by remember { mutableStateOf(savedDate("customStart") ?: if (rangePreset == RangePreset.Custom) today.minusDays(29) else null) }
    var customEnd by remember { mutableStateOf(savedDate("customEnd") ?: if (rangePreset == RangePreset.Custom) today else null) }
    var query by remember { mutableStateOf("") }
    var rankPage by remember { mutableStateOf(1) }
    var rankingMode by remember { mutableStateOf(RankingMode.fromStorage(Prefs.getString("rankingMode", RankingMode.Song.storage))) }
    var rankingMetric by remember { mutableStateOf(RankingMetric.fromStorage(Prefs.getString("rankingMetric", RankingMetric.Count.storage))) }
    var bottomScrollRequest by remember { mutableStateOf(0) }
    var dateDialogOpen by remember { mutableStateOf(false) }
    var aboutDialogOpen by remember { mutableStateOf(false) }
    val closeTicket = StatsHostState.closeTicket
    val requestExit: () -> Unit = {
        if (aboutDialogOpen) aboutDialogOpen = false
        else StatsHostState.requestClose()
    }
    val focus = LocalFocusManager.current
    DisposableEffect(Unit) {
        val presenter = StatsHostState.attachPresenter()
        onDispose { presenter.close() }
    }
    LaunchedEffect(Unit) { UiPerformanceProbe.mark("window_composed") }
    LaunchedEffect(exiting) {
        if (exiting) {
            entering = true
            dateDialogOpen = false
            aboutDialogOpen = false
            focus.clearFocus(force = true)
        }
    }

    SideEffect { StatsHostState.setAnimationsEnabled(displayPreferences.pageAnimations) }
    LaunchedEffect(query, displayPreferences.rankingPageSize) { rankPage = 1 }
    LaunchedEffect(bottomScrollRequest) {
        if (bottomScrollRequest > 0) {
            // Wait for the new page to be laid out, then keep the reader at the
            // bottom instead of leaving them at an arbitrary scroll offset.
            if (displayPreferences.pageAnimations) delay(StatsMotionDurationMillis.toLong())
            androidx.compose.runtime.withFrameNanos { }
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    CompositionLocalProvider(LocalStatsAnimationsEnabled provides displayPreferences.pageAnimations) {
    StatsTheme(inheritMaterial = LocalStatsMaterialBridge.current) {
        val colors = LocalStatsColors.current
        val font = LocalHostFontFamily.current
        val inheritedText = SaltTheme.textStyles
        SaltTheme(configs = saltConfigs(colors.isDark), textStyles = inheritedText.copy(
            main = inheritedText.main.copy(fontFamily = font),
            sub = inheritedText.sub.copy(fontFamily = font),
            paragraph = inheritedText.paragraph.copy(fontFamily = font),
            largeTitle = inheritedText.largeTitle.copy(fontFamily = font)
        )) {
        val snapshot = data
        val summary = remember(snapshot?.loadedAt, today) { snapshot?.let { StatsCalculations.summary(it, today) } }
        val ranking = rememberPageRanking(snapshot, rankingMode, rankingMetric, query,
            mayPrepare = !exiting && (page == StatsPage.Ranking || !entering))
        val displayedRankingEntries = ranking.entries
        val rankCount = displayedRankingEntries.size
        val totalPages = rankingTotalPages(rankCount, displayPreferences.rankingPageSize)
        val safePage = rankingSafePage(rankCount, rankPage, displayPreferences.rankingPageSize)
        LaunchedEffect(totalPages) {
            if (rankPage > totalPages) rankPage = totalPages
        }
        StatsPageEnter(exiting, exitMotion = StatsHostState.exitMotion,
            onEntered = { entering = false; UiPerformanceProbe.mark("window_entered") }, onExited = {
            StatsHostState.finishClose(closeTicket)
        }) {
        Surface(Modifier.fillMaxSize(), color = colors.pageBackground) {
        StatsPageFrame(requestExit, actions = {
            Row(verticalAlignment = Alignment.CenterVertically) {
            if (snapshot != null && !snapshot.libraryAccessDenied) {
            StatisticsBackupMenu(snapshot) {
                rankPage = 1
                scrollTop()
                reloadTick++
            }
            }
            SettingsButton(onClick = {
                dateDialogOpen = false
                aboutDialogOpen = false
                StatsNavigationHooks.openConfiguration()
            })
            AboutButton(onClick = { dateDialogOpen = false; aboutDialogOpen = true })
            }
        }) { titlePadding ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val pageWidth = when {
                maxWidth > 1192.dp -> 1080.dp
                maxWidth > 900.dp -> maxWidth - 112.dp
                maxWidth > 600.dp -> maxWidth - 64.dp
                else -> (maxWidth - 36.dp).coerceAtLeast(280.dp)
            }
            Box(
                Modifier.fillMaxSize().verticalScroll(scrollState),
                contentAlignment = Alignment.TopCenter
            ) {
                        Column(
                            Modifier
                                .width(pageWidth)
                                .wrapContentHeight(unbounded = true)
                                .padding(top = titlePadding.calculateTopPadding(), bottom = 96.dp)
                        ) {
                            Box(Modifier.height(12.dp))
                            PageNav(page) { newPage ->
                                    if (page != newPage) {
                                        UiPerformanceProbe.mark("page_switch", "from=$page to=$newPage")
                                    page = newPage
                                    scrollTop()
                                }
                            }
                            StatsPageBody(page) { shownPage ->
                            Column(Modifier.fillMaxWidth()) {
                            if (snapshot?.libraryAccessDenied == true) {
                                ErrorBanner(checkNotNull(snapshot.error))
                            } else if (snapshot == null || summary == null) {
                                StatsPlaceholder(shownPage)
                            } else {
                            snapshot.storageWarning?.let { warning ->
                                ErrorBanner(warning)
                                Box(Modifier.height(18.dp))
                            }
                            if (snapshot.error != null) {
                                ErrorBanner(snapshot.error)
                                Box(Modifier.height(18.dp))
                            } else if (snapshot.dailyMillis.values.none { it > 0 }) {
                                EmptyBanner()
                                Box(Modifier.height(18.dp))
                            }
                            when (shownPage) {
                                StatsPage.Overview -> OverviewPage(
                                    data = snapshot,
                                    summary = summary,
                                    today = today,
                                    year = heatmapYear,
                                    weekly = heatmapWeekly,
                                    heatmapRolling = heatmapRolling,
                                    onYearSelected = { heatmapYear = it },
                                    onHeatmapRollingSelected = { heatmapRolling = it },
                                    onWeeklySelected = { weekly ->
                                        if (heatmapWeekly != weekly) {
                                            rangePreset = StatsCalculations.rangeAfterHeatmapModeChange(rangePreset, selectedDate)
                                            selectedDate = null
                                            heatmapWeekly = weekly
                                            Prefs.putStringAsync("selectedDate", "")
                                            Prefs.putStringAsync("range", rangePreset.storage)
                                        }
                                    },
                                    selectedDate = selectedDate,
                                    rangePreset = rangePreset,
                                    customStart = customStart,
                                    customEnd = customEnd,
                                    chartType = chartType,
                                    userGranularity = userGranularity,
                                    onDaySelected = { date ->
                                        if (selectedDate == date && rangePreset == RangePreset.Selected) {
                                            selectedDate = null
                                            rangePreset = RangePreset.Today
                                            Prefs.putStringAsync("range", rangePreset.storage)
                                            Prefs.putStringAsync("selectedDate", "")
                                        } else {
                                            selectedDate = date
                                            rangePreset = RangePreset.Selected
                                            Prefs.putStringAsync("range", rangePreset.storage)
                                            Prefs.putStringAsync("selectedDate", date.toString())
                                        }
                                    },
                                    onWeekSelected = { start, end ->
                                        if (rangePreset == RangePreset.Custom && selectedDate == start &&
                                            customStart == start && customEnd == end
                                        ) {
                                            selectedDate = null
                                            customStart = null
                                            customEnd = null
                                            rangePreset = RangePreset.Today
                                            Prefs.putStringAsync("range", rangePreset.storage)
                                            Prefs.putStringAsync("selectedDate", "")
                                            Prefs.putStringAsync("customStart", "")
                                            Prefs.putStringAsync("customEnd", "")
                                        } else {
                                            selectedDate = start
                                            customStart = start
                                            customEnd = end
                                            rangePreset = RangePreset.Custom
                                            Prefs.putStringAsync("range", rangePreset.storage)
                                            Prefs.putStringAsync("selectedDate", start.toString())
                                            Prefs.putStringAsync("customStart", start.toString())
                                            Prefs.putStringAsync("customEnd", end.toString())
                                        }
                                    },
                                    onRangePreset = { preset ->
                                        if (preset == RangePreset.Selected && selectedDate == null) {
                                            selectedDate = today
                                        }
                                        rangePreset = preset
                                        if (preset != RangePreset.Selected) {
                                            selectedDate = null
                                            Prefs.putStringAsync("selectedDate", "")
                                        }
                                        Prefs.putStringAsync("range", preset.storage)
                                        selectedDate?.let { Prefs.putStringAsync("selectedDate", it.toString()) }
                                    },
                                    onCustomRange = {
                                        dateDialogOpen = true
                                    },
                                    onChartType = {
                                        chartType = it
                                        Prefs.putStringAsync("chartType", it.storage)
                                    },
                                    onGranularity = {
                                        userGranularity = it
                                        Prefs.putStringAsync("granularity", it.storage)
                                    },
                                    scrollState = scrollState,
                                    displayPreferences = displayPreferences
                                )
                                StatsPage.Ranking -> RankingPage(
                                    entries = displayedRankingEntries,
                                    mode = rankingMode,
                                    onModeChange = {
                                        if (rankingMode != it) {
                                            rankingMode = it
                                            rankPage = 1
                                            Prefs.putStringAsync("rankingMode", it.storage)
                                        }
                                    },
                                    metric = rankingMetric,
                                    onMetricChange = {
                                        if (rankingMetric != it) {
                                            rankingMetric = it
                                            rankPage = 1
                                            Prefs.putStringAsync("rankingMetric", it.storage)
                                        }
                                    },
                                    query = query,
                                    onQueryChange = { query = it },
                                    page = safePage,
                                    pageSize = displayPreferences.rankingPageSize,
                                    loading = !ranking.ready && ranking.entries.isEmpty(),
                                    latinDurationUnits = displayPreferences.detailLatinDurationUnits,
                                    onPageChange = {
                                        val wasAtBottom = scrollState.maxValue > 0 &&
                                            scrollState.value >= scrollState.maxValue - 2
                                        rankPage = it
                                        if (wasAtBottom) bottomScrollRequest++
                                    }
                                )
                            }
                            }
                            }
                            }
                        }
                    }
        }
                }
                }
                if (aboutDialogOpen && !exiting) {
                    AboutDialog(onClose = { aboutDialogOpen = false })
                }
                if (dateDialogOpen && snapshot != null && !exiting) {
                    val dateRange = StatsCalculations.range(
                        rangePreset,
                        today,
                        selectedDate,
                        customStart,
                        customEnd
                    )
                    DateRangeDialog(
                        initialStart = dateRange.start,
                        initialEnd = dateRange.endInclusive,
                        earliestYear = snapshot.dailyMillis.keys.minOfOrNull { it.year } ?: today.year,
                        onCancel = { dateDialogOpen = false },
                        onConfirm = { start, end ->
                            customStart = start
                            customEnd = end
                            rangePreset = RangePreset.Custom
                            selectedDate = null
                            Prefs.putStringAsync("selectedDate", "")
                            Prefs.putStringAsync("range", rangePreset.storage)
                            Prefs.putStringAsync("customStart", start.toString())
                            Prefs.putStringAsync("customEnd", end.toString())
                            dateDialogOpen = false
                        }
                    )
                }
            }
        }
    }

}
}

@Composable
fun PageNav(page: StatsPage, onPage: (StatsPage) -> Unit) {
    val colors = LocalStatsColors.current
    var overviewWidth by remember { mutableStateOf(0) }
    var rankingWidth by remember { mutableStateOf(0) }
    var overviewContentWidth by remember { mutableStateOf(0) }
    var rankingContentWidth by remember { mutableStateOf(0) }
    Box {
        Row {
            NavTab(IconPaths.Dashboard, "听歌概览", page == StatsPage.Overview,
                flushStart = true,
                modifier = Modifier.onSizeChanged { overviewWidth = it.width },
                onContentWidthChanged = { overviewContentWidth = it }) {
                onPage(StatsPage.Overview)
            }
            NavTab(IconPaths.Leaderboard, "听歌排行", page == StatsPage.Ranking,
                modifier = Modifier.onSizeChanged { rankingWidth = it.width },
                onContentWidthChanged = { rankingContentWidth = it }) {
                onPage(StatsPage.Ranking)
            }
        }
        if (overviewWidth > 0 && rankingWidth > 0 && overviewContentWidth > 0 && rankingContentWidth > 0) {
            val duration = statsMotionDurationMillis()
            val left by animateFloatAsState(
                if (page == StatsPage.Overview) 0f
                    else overviewWidth + (rankingWidth - rankingContentWidth) / 2f,
                tween(duration, easing = PageEasing), label = "navigation-indicator-left")
            val width by animateFloatAsState(
                (if (page == StatsPage.Overview) overviewContentWidth else rankingContentWidth).toFloat(),
                tween(duration, easing = PageEasing), label = "navigation-indicator-width")
            Box(Modifier.matchParentSize().drawWith {
                val thickness = 2.dp.toPx()
                drawRect(colors.accent, topLeft = androidx.compose.ui.geometry.Offset(left, size.height - thickness),
                    size = androidx.compose.ui.geometry.Size(width, thickness))
            })
        }
    }
    Box(Modifier.height(24.dp))
}

@Composable
private fun NavTab(
    icon: String,
    label: String,
    active: Boolean,
    flushStart: Boolean = false,
    modifier: Modifier = Modifier,
    onContentWidthChanged: (Int) -> Unit,
    onClick: () -> Unit
) {
    val colors = LocalStatsColors.current
    var hovered by remember { mutableStateOf(false) }
    SaltTheme(configs = SaltTheme.configs.copy(indication = PlainNavIndication),
        dynamicColors = SaltDynamicColors(SaltTheme.colors, SaltTheme.colors)) {
    BasicButton(
        onClick = onClick,
        modifier = modifier.height(36.dp)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .semantics { selected = active; role = androidx.compose.ui.semantics.Role.Tab },
        shape = AppShape(0.dp),
        size = ControlSize.Small,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        colors = ButtonColors(Color.Transparent, colors.text, Color.Transparent, colors.muted)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(
                Modifier.padding(start = if (flushStart) 0.dp else 16.dp, end = 16.dp)
                    .onSizeChanged { onContentWidthChanged(it.width) },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(icon, if (active || hovered) colors.text else colors.muted, iconSize = 17.dp)
                Text(
                    label,
                    style = AppType.tab.copy(
                        color = if (active || hovered) colors.text else colors.muted,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal
                    )
                )
            }
        }
    }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    val colors = LocalStatsColors.current
    SaltCard(
        shape = AppShape(10.dp),
        color = colors.subtle,
        border = androidx.compose.foundation.BorderStroke(1.dp, SolidColor(colors.line))
    ) {
        Text(
            "统计暂时不可用：$message",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = AppType.bodySmall.copy(color = colors.danger)
        )
    }
}

@Composable
private fun EmptyBanner() {
    val colors = LocalStatsColors.current
    SaltCard(
        shape = AppShape(10.dp),
        color = colors.subtle
    ) {
        Text(
            "还没有可统计的听歌记录。播放歌曲后，统计会自动出现。",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = AppType.bodySmall.copy(color = colors.muted)
        )
    }
}
