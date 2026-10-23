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
import com.jethuit.spw.listenstats.DisplayPreferences
import com.jethuit.spw.listenstats.ui.StatsText as Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import com.jethuit.spw.listenstats.PluginLog
import com.jethuit.spw.listenstats.Prefs
import com.jethuit.spw.listenstats.data.BackupService
import com.jethuit.spw.listenstats.data.ChartType
import com.jethuit.spw.listenstats.data.Granularity
import com.jethuit.spw.listenstats.data.RangePreset
import com.jethuit.spw.listenstats.data.RankingMode
import com.jethuit.spw.listenstats.data.Rankings
import com.jethuit.spw.listenstats.data.StatsCalculations
import com.jethuit.spw.listenstats.data.StatsData
import com.jethuit.spw.listenstats.data.StatsStore
import com.jethuit.spw.listenstats.util.formatDay
import com.moriafly.salt.ui.Card as SaltCard
import com.moriafly.salt.ui.BasicButton
import com.moriafly.salt.ui.ButtonColors
import com.moriafly.salt.ui.ControlSize
import com.moriafly.salt.ui.SaltDynamicColors
import com.moriafly.salt.ui.SaltTheme
import com.moriafly.salt.ui.rememberScrollState
import com.moriafly.salt.ui.saltConfigs
import com.moriafly.salt.ui.verticalScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import com.jethuit.spw.listenstats.data.StatsSnapshots
import com.jethuit.spw.listenstats.data.StatsSnapshotSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Color as AwtColor
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.KeyEvent
import java.time.LocalDate
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlin.math.min

/**
 * The host main window is a plain AWT frame. A borderless JDialog owned by it
 * gives us a real full-window page without depending on the host's obfuscated
 * navigation composables.
 */
object StatsWindow {
    private var dialog: JDialog? = null

    fun open(): Boolean {
        if (!SwingUtilities.isEventDispatchThread()) {
            var opened = false
            try {
                SwingUtilities.invokeAndWait { opened = open() }
            } catch (failure: Throwable) {
                PluginLog.error("opening stats window on EDT failed", failure)
            }
            return opened
        }
        dialog?.dispose()
        val owner = findHostFrame()
        if (owner == null) {
            PluginLog.error("host frame not found")
            return false
        }
        val frame = JDialog(owner, "听歌统计", false)
        frame.isUndecorated = true
        frame.background = AwtColor(0, 0, 0, 0)
        frame.setBounds(clampToScreen(owner.bounds))
        val panel = ComposePanel()
        val content = JPanel(BorderLayout())
        content.isOpaque = false
        content.add(panel, BorderLayout.CENTER)
        val scrollPane = javax.swing.JScrollPane(
            content,
            javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        )
        scrollPane.border = null
        scrollPane.isOpaque = false
        scrollPane.viewport.isOpaque = false
        scrollPane.verticalScrollBar.unitIncrement = 48
        var contentHeightPx = frame.height
        var lastPage: StatsPage? = null
        fun applyContentSize() {
            val viewportWidth = scrollPane.viewport.width.coerceAtLeast(1)
            val viewportHeight = scrollPane.viewport.height.coerceAtLeast(1)
            content.preferredSize = java.awt.Dimension(
                viewportWidth,
                contentHeightPx.coerceAtLeast(viewportHeight)
            )
            content.revalidate()
            scrollPane.revalidate()
        }
        panel.setContent {
            ListenStatsApp(
                onClose = { frame.dispose() },
                onScrollTop = { SwingUtilities.invokeLater { scrollPane.verticalScrollBar.value = 0 } },
                onContentHeight = { page, heightPx ->
                    contentHeightPx = heightPx
                    if (lastPage != page) {
                        lastPage = page
                        SwingUtilities.invokeLater { scrollPane.verticalScrollBar.value = 0 }
                    }
                    SwingUtilities.invokeLater { applyContentSize() }
                }
            )
        }
        scrollPane.viewport.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) {
                applyContentSize()
            }
        })
        frame.contentPane.layout = BorderLayout()
        frame.contentPane.add(scrollPane, BorderLayout.CENTER)
        frame.rootPane.registerKeyboardAction(
            { frame.dispose() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        )
        frame.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosed(e: java.awt.event.WindowEvent?) {
                if (dialog === frame) {
                    dialog = null
                }
            }
        })
        frame.isVisible = true
        frame.toFront()
        panel.requestFocusInWindow()
        dialog = frame
        PluginLog.info("stats window opened at ${frame.bounds}; panel=${panel.width}x${panel.height}; insets=${frame.insets}")
        return true
    }

    private fun findHostFrame(): Frame? {
        val active = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        val activeFrame = generateSequence(active) { it.owner }
            .filterIsInstance<Frame>()
            .firstOrNull { it.isShowing && it.width > 600 && it.height > 400 }
        if (activeFrame != null) {
            PluginLog.info("host frame from focus: ${activeFrame.title} ${activeFrame.bounds}")
            return activeFrame
        }
        val frames = Window.getWindows()
            .filterIsInstance<Frame>()
            .filter { it.isShowing && it.width > 600 && it.height > 400 }
        for (candidate in frames) {
            PluginLog.info("host frame candidate: ${candidate.title} ${candidate.bounds} active=${candidate.isActive}")
        }
        return frames.firstOrNull { it.title.contains("Salt Player") }
            ?: frames.maxByOrNull { it.width.toLong() * it.height }
    }

    private fun clampToScreen(bounds: Rectangle): Rectangle {
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val configuration = environment.screenDevices
            .map { it.defaultConfiguration.bounds }
            .firstOrNull { it.intersects(bounds) }
            ?: environment.defaultScreenDevice.defaultConfiguration.bounds
        val width = bounds.width.coerceAtMost(configuration.width)
        val height = bounds.height.coerceAtMost(configuration.height)
        val x = bounds.x.coerceIn(configuration.x, configuration.x + configuration.width - width)
        val y = bounds.y.coerceIn(configuration.y, configuration.y + configuration.height - height)
        return Rectangle(x, y, width, height)
    }

    fun bounds(): Rectangle? = dialog?.bounds

    fun close() {
        SwingUtilities.invokeLater { dialog?.dispose(); dialog = null }
    }
}

private fun savedDate(key: String): LocalDate? = Prefs.getString(key, "")
    .takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

enum class StatsPage { Overview, Ranking }

@Composable
fun ListenStatsApp(
    embedded: Boolean = false,
    onClose: () -> Unit,
    onScrollTop: () -> Unit = {},
    onContentHeight: (StatsPage, Int) -> Unit = { _, _ -> }
) {
    val scrollState = rememberScrollState(0)
    val scope = rememberCoroutineScope()
    val scrollTop: () -> Unit = {
        if (embedded) {
            scope.launch { scrollState.scrollTo(0) }
        } else {
            onScrollTop()
        }
    }
    val snapshots = remember { StatsSnapshots.current ?: StatsSnapshotSession() }
    val initial = remember { snapshots.latest }
    var entering by remember { mutableStateOf(true) }
    var data by remember { mutableStateOf(initial?.data) }
    var displayPreferences by remember { mutableStateOf(initial?.preferences ?: DisplayPreferences()) }
    var today by remember { mutableStateOf(LocalDate.now()) }
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
    var selectedDate by remember { mutableStateOf(savedDate("selectedDate") ?: if (rangePreset == RangePreset.Selected) today else null) }
    var heatmapYear by remember { mutableStateOf(today.year) }
    var heatmapRolling by remember { mutableStateOf(false) }
    var heatmapWeekly by remember { mutableStateOf(false) }
    var customStart by remember { mutableStateOf(savedDate("customStart") ?: if (rangePreset == RangePreset.Custom) today.minusDays(29) else null) }
    var customEnd by remember { mutableStateOf(savedDate("customEnd") ?: if (rangePreset == RangePreset.Custom) today else null) }
    var query by remember { mutableStateOf("") }
    var rankPage by remember { mutableStateOf(1) }
    var rankingMode by remember { mutableStateOf(RankingMode.fromStorage(Prefs.getString("rankingMode", RankingMode.Song.storage))) }
    var bottomScrollRequest by remember { mutableStateOf(0) }
    var reloadTick by remember { mutableStateOf(0) }
    var dateDialogOpen by remember { mutableStateOf(false) }
    var backupBusy by remember { mutableStateOf(false) }
    var localExiting by remember { mutableStateOf(false) }
    val exiting = if (embedded) StatsHostState.closing else localExiting
    val latestExiting by rememberUpdatedState(exiting)
    val closeTicket = StatsHostState.closeTicket
    val requestExit: () -> Unit = { if (embedded) onClose() else localExiting = true }
    val focus = LocalFocusManager.current
    DisposableEffect(embedded) {
        val presenter = if (embedded) StatsHostState.attachPresenter() else null
        onDispose { presenter?.close() }
    }
    SideEffect { if (embedded) StatsHostState.setAnimationsEnabled(displayPreferences.pageAnimations) }
    LaunchedEffect(exiting) {
        if (exiting) { entering = true; dateDialogOpen = false; focus.clearFocus(force = true) }
    }

    LaunchedEffect(reloadTick) {
        // The embedded entry is prepared before navigation. Standalone previews may load here.
        if (initial == null) withContext(Dispatchers.IO) { Prefs.initialize() }
        while (true) {
            val loaded = withContext(Dispatchers.IO) { snapshots.load() }
            if (data == null) {
                data = loaded.data
                displayPreferences = loaded.preferences
            } else {
                // Wait for the actual spring completion, including interrupted/reversed entrances.
                snapshotFlow { !entering && !latestExiting }.first { it }
                data = loaded.data
                displayPreferences = loaded.preferences
            }
            today = LocalDate.now()
            delay(5_000)
        }
    }
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
    StatsTheme(inheritMaterial = embedded && LocalStatsMaterialBridge.current) {
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
        val summary = remember(snapshot, today) { snapshot?.let { StatsCalculations.summary(it, today) } }
        val rankingEntries = remember(snapshot, query, rankingMode) {
            snapshot?.let { Rankings.ranked(it, rankingMode, query) } ?: emptyList()
        }
        val rankCount = rankingEntries.size
        val totalPages = rankingTotalPages(rankCount, displayPreferences.rankingPageSize)
        val safePage = rankingSafePage(rankCount, rankPage, displayPreferences.rankingPageSize)
        LaunchedEffect(totalPages) {
            if (rankPage > totalPages) rankPage = totalPages
        }
        StatsPageEnter(exiting, exitMotion = if (embedded) StatsHostState.exitMotion else StatsExitMotion.Return,
            onEntered = { entering = false }, onExited = {
            if (embedded) StatsHostState.finishClose(closeTicket) else onClose()
        }) {
        Surface(Modifier.fillMaxSize(), color = colors.pageBackground) {
        StatsPageFrame(requestExit, actions = {
            if (snapshot != null) {
            BackupMenu(
                onImport = {
                    if (!backupBusy) scope.launch {
                        backupBusy = true
                        try {
                            if (BackupService.importBackup()) {
                                rankPage = 1
                                scrollTop()
                                reloadTick++
                            }
                        } finally { backupBusy = false }
                    }
                },
                onExport = {
                    if (!backupBusy) scope.launch {
                        backupBusy = true
                        try { BackupService.export(snapshot) }
                        finally { backupBusy = false }
                    }
                }
            )
            }
        }) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val pageWidth = when {
                maxWidth > 1192.dp -> 1080.dp
                maxWidth > 900.dp -> maxWidth - 112.dp
                maxWidth > 600.dp -> maxWidth - 64.dp
                else -> (maxWidth - 36.dp).coerceAtLeast(280.dp)
            }
            Box(
                Modifier
                    .then(
                        if (embedded) Modifier.fillMaxSize().verticalScroll(scrollState)
                        else Modifier.fillMaxWidth()
                    ),
                contentAlignment = Alignment.TopCenter
            ) {
                        Column(
                            Modifier
                                .width(pageWidth)
                                .wrapContentHeight(unbounded = true)
                                .onSizeChanged { size ->
                                    // Swing dimensions are logical pixels; Compose size is device pixels.
                                    onContentHeight(page, (size.height / density.density).toInt() + 56)
                                }
                                .padding(bottom = if (embedded) 96.dp else 24.dp)
                        ) {
                            Box(Modifier.height(12.dp))
                            PageNav(page) { newPage ->
                                if (page != newPage) {
                                    page = newPage
                                    scrollTop()
                                }
                            }
                            StatsPageBody(page) { shownPage ->
                            Column(Modifier.fillMaxWidth()) {
                            if (snapshot == null || summary == null) {
                                StatsPlaceholder(shownPage)
                            } else {
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
                                    scrollState = if (embedded) scrollState else null
                                )
                                StatsPage.Ranking -> RankingPage(
                                    entries = rankingEntries,
                                    mode = rankingMode,
                                    onModeChange = {
                                        if (rankingMode != it) {
                                            rankingMode = it
                                            rankPage = 1
                                            Prefs.putStringAsync("rankingMode", it.storage)
                                        }
                                    },
                                    query = query,
                                    onQueryChange = { query = it },
                                    page = safePage,
                                    pageSize = displayPreferences.rankingPageSize,
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
