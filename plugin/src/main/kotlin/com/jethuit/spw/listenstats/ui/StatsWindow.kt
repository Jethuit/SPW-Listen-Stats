@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, com.moriafly.salt.ui.UnstableSaltUiApi::class)

package com.jethuit.spw.listenstats.ui

import androidx.compose.foundation.layout.Arrangement
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
import com.jethuit.spw.listenstats.ui.StatsText as Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
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
import com.moriafly.salt.ui.SaltTheme
import com.moriafly.salt.ui.rememberScrollState
import com.moriafly.salt.ui.saltConfigs
import com.moriafly.salt.ui.verticalScroll
import kotlinx.coroutines.Dispatchers
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
    var data by remember { mutableStateOf<StatsData?>(null) }
    var dailyHighMillis by remember {
        mutableStateOf(com.jethuit.spw.listenstats.data.HeatmapScale.DEFAULT_HIGH_MILLIS)
    }
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
    var chartType by remember { mutableStateOf(ChartType.Line) }
    var userGranularity by remember { mutableStateOf(Granularity.Day) }
    var rangePreset by remember { mutableStateOf(RangePreset.Last30) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var heatmapYear by remember { mutableStateOf(today.year) }
    var heatmapRolling by remember { mutableStateOf(false) }
    var heatmapWeekly by remember { mutableStateOf(false) }
    var customStart by remember { mutableStateOf<LocalDate?>(null) }
    var customEnd by remember { mutableStateOf<LocalDate?>(null) }
    var query by remember { mutableStateOf("") }
    var rankPage by remember { mutableStateOf(1) }
    var rankingMode by remember { mutableStateOf(RankingMode.Song) }
    var bottomScrollRequest by remember { mutableStateOf(0) }
    var reloadTick by remember { mutableStateOf(0) }
    var dateDialogOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        Prefs.initialize()
        chartType = ChartType.fromStorage(Prefs.getString("chartType", ChartType.Line.storage))
        userGranularity = Granularity.fromStorage(Prefs.getString("granularity", Granularity.Day.storage))
        rankingMode = RankingMode.fromStorage(Prefs.getString("rankingMode", RankingMode.Song.storage))
        rangePreset = RangePreset.fromStorage(Prefs.getString("range", RangePreset.Last30.storage))
        selectedDate = Prefs.getString("selectedDate", "")
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        customStart = Prefs.getString("customStart", "")
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        customEnd = Prefs.getString("customEnd", "")
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (rangePreset == RangePreset.Selected && selectedDate == null) selectedDate = today
        if (rangePreset == RangePreset.Custom && (customStart == null || customEnd == null)) {
            customStart = today.minusDays(29)
            customEnd = today
        }
        while (true) {
            val (loaded, highMillis) = withContext(Dispatchers.IO) {
                StatsStore.load() to Prefs.dailyHighMillis()
            }
            dailyHighMillis = highMillis
            data = loaded
            today = LocalDate.now()
            delay(5_000)
        }
    }
    LaunchedEffect(reloadTick) {
        if (reloadTick > 0) {
            data = withContext(Dispatchers.IO) { StatsStore.load() }
        }
    }
    LaunchedEffect(query) { rankPage = 1 }
    LaunchedEffect(bottomScrollRequest) {
        if (bottomScrollRequest > 0) {
            // Wait for the new page to be laid out, then keep the reader at the
            // bottom instead of leaving them at an arbitrary scroll offset.
            delay(60)
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    StatsTheme(inheritMaterial = embedded && StatsHostState.materialBridgeAvailable) {
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
        if (snapshot == null) {
            Surface(Modifier.fillMaxSize(), color = colors.pageBackground) {
                StatsPageFrame(onClose) { LoadingPage(onClose) }
            }
            return@SaltTheme
        }
        val summary = remember(snapshot, today) { StatsCalculations.summary(snapshot, today) }
        val rankingEntries = remember(snapshot, query, rankingMode) { Rankings.ranked(snapshot, rankingMode, query) }
        val rankCount = rankingEntries.size
        val totalPages = rankingTotalPages(rankCount)
        val safePage = rankingSafePage(rankCount, rankPage)
        LaunchedEffect(totalPages) {
            if (rankPage > totalPages) rankPage = totalPages
        }
        Surface(Modifier.fillMaxSize(), color = colors.pageBackground) {
        StatsPageFrame(onClose) {
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
                            PageHeader(
                                onImport = {
                                    if (BackupService.importBackup()) {
                                        rankPage = 1
                                        scrollTop()
                                        reloadTick++
                                    }
                                },
                                onExport = { BackupService.export(snapshot) }
                            )
                            PageNav(page) { newPage ->
                                page = newPage
                                scrollTop()
                            }
                            if (snapshot.error != null) {
                                ErrorBanner(snapshot.error)
                                Box(Modifier.height(18.dp))
                            } else if (snapshot.dailyMillis.values.none { it > 0 }) {
                                EmptyBanner()
                                Box(Modifier.height(18.dp))
                            }
                            when (page) {
                                StatsPage.Overview -> OverviewPage(
                                    data = snapshot,
                                    summary = summary,
                                    today = today,
                                    year = heatmapYear,
                                    weekly = heatmapWeekly,
                                    heatmapRolling = heatmapRolling,
                                    dailyHighMillis = dailyHighMillis,
                                    onYearSelected = { heatmapYear = it },
                                    onHeatmapRollingSelected = { heatmapRolling = it },
                                    onWeeklySelected = { heatmapWeekly = it },
                                    selectedDate = selectedDate,
                                    rangePreset = rangePreset,
                                    customStart = customStart,
                                    customEnd = customEnd,
                                    chartType = chartType,
                                    userGranularity = userGranularity,
                                    onDaySelected = { date ->
                                        if (selectedDate == date && rangePreset == RangePreset.Selected) {
                                            selectedDate = null
                                            rangePreset = RangePreset.Last30
                                            Prefs.putString("range", rangePreset.storage)
                                            Prefs.putString("selectedDate", "")
                                        } else {
                                            selectedDate = date
                                            rangePreset = RangePreset.Selected
                                            Prefs.putString("range", rangePreset.storage)
                                            Prefs.putString("selectedDate", date.toString())
                                        }
                                    },
                                    onWeekSelected = { start, end ->
                                        if (rangePreset == RangePreset.Custom &&
                                            customStart == start && customEnd == end
                                        ) {
                                            selectedDate = null
                                            customStart = null
                                            customEnd = null
                                            rangePreset = RangePreset.Last30
                                            Prefs.putString("range", rangePreset.storage)
                                            Prefs.putString("selectedDate", "")
                                            Prefs.putString("customStart", "")
                                            Prefs.putString("customEnd", "")
                                        } else {
                                            selectedDate = start
                                            customStart = start
                                            customEnd = end
                                            rangePreset = RangePreset.Custom
                                            Prefs.putString("range", rangePreset.storage)
                                            Prefs.putString("selectedDate", start.toString())
                                            Prefs.putString("customStart", start.toString())
                                            Prefs.putString("customEnd", end.toString())
                                        }
                                    },
                                    onRangePreset = { preset ->
                                        if (preset == RangePreset.Selected && selectedDate == null) {
                                            selectedDate = today
                                        }
                                        rangePreset = preset
                                        Prefs.putString("range", preset.storage)
                                        selectedDate?.let { Prefs.putString("selectedDate", it.toString()) }
                                    },
                                    onCustomRange = {
                                        dateDialogOpen = true
                                    },
                                    onChartType = {
                                        chartType = it
                                        Prefs.putString("chartType", it.storage)
                                    },
                                    onGranularity = {
                                        userGranularity = it
                                        Prefs.putString("granularity", it.storage)
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
                                            Prefs.putString("rankingMode", it.storage)
                                        }
                                    },
                                    query = query,
                                    onQueryChange = { query = it },
                                    page = safePage,
                                    onPageChange = {
                                        val wasAtBottom = scrollState.value >= scrollState.maxValue - 2
                                        rankPage = it
                                        if (wasAtBottom) bottomScrollRequest++
                                    }
                                )
                            }
                            if (page == StatsPage.Overview) PageFooter(snapshot)
                        }
                    }
                }
                }
                if (dateDialogOpen) {
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
                        onCancel = { dateDialogOpen = false },
                        onConfirm = { start, end ->
                            customStart = start
                            customEnd = end
                            rangePreset = RangePreset.Custom
                            Prefs.putString("range", rangePreset.storage)
                            Prefs.putString("customStart", start.toString())
                            Prefs.putString("customEnd", end.toString())
                            dateDialogOpen = false
                        }
                    )
                }
            }
        }
    }

}

@Composable
private fun PageHeader(
    onImport: () -> Unit,
    onExport: () -> Unit
) {
    val colors = LocalStatsColors.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("听歌统计", style = AppType.pageTitle.copy(color = colors.text))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextAction(IconPaths.Download, "导入", colors.text, onImport)
            TextAction(IconPaths.Upload, "导出", colors.text, onExport)
        }
    }
    Box(Modifier.height(30.dp))
}

@Composable
private fun PageNav(page: StatsPage, onPage: (StatsPage) -> Unit) {
    val colors = LocalStatsColors.current
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            NavTab(IconPaths.Dashboard, "听歌概览", page == StatsPage.Overview) {
                onPage(StatsPage.Overview)
            }
            NavTab(IconPaths.Leaderboard, "听歌排行", page == StatsPage.Ranking) {
                onPage(StatsPage.Ranking)
            }
        }
        Box(Modifier.height(1.dp))
    }
    Box(Modifier.height(if (page == StatsPage.Ranking) 0.dp else 30.dp))
}

@Composable
private fun NavTab(icon: String, label: String, active: Boolean, onClick: () -> Unit) {
    val colors = LocalStatsColors.current
    var hovered by remember { mutableStateOf(false) }
    Box(
        Modifier
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .onPointerEvent(PointerEventType.Press) { onClick() }
    ) {
        Column(
            Modifier.drawWith {
                if (active) {
                    drawRect(
                        color = colors.accent,
                        topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - 2f),
                        size = androidx.compose.ui.geometry.Size(size.width, 2f)
                    )
                }
            }
        ) {
            Row(
                Modifier.padding(bottom = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(icon, if (active || hovered) colors.text else colors.muted, iconSize = 17.dp)
                Text(
                    label,
                    style = AppType.tab.copy(
                        color = if (active || hovered) colors.text else colors.muted,
                        fontWeight = if (active) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal
                    )
                )
            }
        }
    }
}

@Composable
private fun PageFooter(data: StatsData) {
    val colors = LocalStatsColors.current
    Column {
        Box(Modifier.height(36.dp))
        Box(Modifier.height(1.dp))
        Box(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(5.dp)
                        .drawWith { drawCircle(color = colors.muted, radius = size.minDimension / 2f) }
                )
                Box(Modifier.width(7.dp))
                Text(
                    if (data.databaseFound) "真实听歌记录 · 已连接 SPW 播放器" else "播放器数据不可用",
                    style = AppType.tiny.copy(color = colors.muted)
                )
            }
            Text(
                "仅记录实际收听 · 播放次数按歌曲独立累计",
                style = AppType.tiny.copy(color = colors.muted)
            )
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

@Composable
private fun LoadingPage(onClose: () -> Unit) {
    val colors = LocalStatsColors.current
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("听歌统计", style = AppType.pageTitle.copy(color = colors.text))
        Box(Modifier.height(12.dp))
        Text("正在读取播放器听歌记录…", style = AppType.body.copy(color = colors.muted))
        Box(Modifier.height(24.dp))
        IconAction(IconPaths.Close, "关闭", colors.muted, onClose)
    }
}
