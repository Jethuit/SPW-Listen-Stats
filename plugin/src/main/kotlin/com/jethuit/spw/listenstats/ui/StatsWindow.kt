@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.jethuit.spw.listenstats.data.StatsCalculations
import com.jethuit.spw.listenstats.data.StatsData
import com.jethuit.spw.listenstats.data.StatsStore
import com.jethuit.spw.listenstats.util.formatDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Color as AwtColor
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.GridLayout
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.KeyEvent
import java.time.LocalDate
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField
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
}

enum class StatsPage { Overview, Ranking }

@Composable
fun ListenStatsApp(
    onClose: () -> Unit,
    onContentHeight: (StatsPage, Int) -> Unit = { _, _ -> }
) {
    var data by remember { mutableStateOf<StatsData?>(null) }
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
    var themeMode by remember {
        mutableStateOf(
            System.getProperty("spw.listenstats.theme")?.let { ThemeMode.fromStorage(it) }
                ?: ThemeMode.System
        )
    }
    var chartType by remember { mutableStateOf(ChartType.Line) }
    var userGranularity by remember { mutableStateOf(Granularity.Day) }
    var rangePreset by remember { mutableStateOf(RangePreset.Last30) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var customStart by remember { mutableStateOf<LocalDate?>(null) }
    var customEnd by remember { mutableStateOf<LocalDate?>(null) }
    var query by remember { mutableStateOf("") }
    var rankPage by remember { mutableStateOf(1) }
    var reloadTick by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        Prefs.initialize()
        if (System.getProperty("spw.listenstats.theme") == null) {
            themeMode = ThemeMode.fromStorage(Prefs.getString("theme", ThemeMode.System.storage))
        }
        chartType = ChartType.fromStorage(Prefs.getString("chartType", ChartType.Line.storage))
        userGranularity = Granularity.fromStorage(Prefs.getString("granularity", Granularity.Day.storage))
        rangePreset = RangePreset.fromStorage(Prefs.getString("range", RangePreset.Last30.storage))
        while (true) {
            val loaded = withContext(Dispatchers.IO) { StatsStore.load() }
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

    StatsTheme(themeMode) {
        val colors = LocalStatsColors.current
        val snapshot = data
        if (snapshot == null) {
            Surface(Modifier.fillMaxSize(), color = colors.background) {
                LoadingPage(onClose)
            }
            return@StatsTheme
        }
        val summary = remember(snapshot, today) { StatsCalculations.summary(snapshot, today) }
        val rankCount = remember(snapshot, query) { filteredRankCount(snapshot, query) }
        val totalPages = if (rankCount == 0) 1 else (rankCount + 49) / 50
        val safePage = rankPage.coerceIn(1, totalPages)
        val rowsOnPage = if (rankCount == 0) 0 else min(50, rankCount - (safePage - 1) * 50)
        LaunchedEffect(totalPages) {
            if (rankPage > totalPages) rankPage = totalPages
        }
        val contentHeight = if (page == StatsPage.Overview) {
            if (snapshot.error != null || snapshot.listens.isEmpty()) 1380.dp else 1300.dp
        } else {
            600.dp + 71.dp * rowsOnPage + if (snapshot.error != null) 80.dp else 0.dp
        }

        Surface(Modifier.fillMaxSize(), color = colors.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val pageWidth = when {
                maxWidth > 1192.dp -> 1080.dp
                maxWidth > 900.dp -> maxWidth - 112.dp
                maxWidth > 600.dp -> maxWidth - 64.dp
                else -> (maxWidth - 36.dp).coerceAtLeast(280.dp)
            }
            LaunchedEffect(page, contentHeight) {
                onContentHeight(page, with(density) { contentHeight.toPx().toInt() })
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        Column(
                            Modifier
                                .width(pageWidth)
                                .padding(top = 56.dp, bottom = 24.dp)
                        ) {
                            PageHeader(
                                themeMode = themeMode,
                                onTheme = {
                                    themeMode = themeMode.next()
                                    Prefs.putString("theme", themeMode.storage)
                                },
                                onImport = {
                                    if (BackupService.importBackup()) {
                                        reloadTick++
                                    }
                                },
                                onExport = { BackupService.export(snapshot) },
                                onClose = onClose
                            )
                            PageNav(page) { newPage ->
                                page = newPage
                            }
                            if (snapshot.error != null) {
                                ErrorBanner(snapshot.error)
                                Box(Modifier.height(18.dp))
                            } else if (snapshot.listens.isEmpty()) {
                                EmptyBanner()
                                Box(Modifier.height(18.dp))
                            }
                            when (page) {
                                StatsPage.Overview -> OverviewPage(
                                    data = snapshot,
                                    summary = summary,
                                    today = today,
                                    selectedDate = selectedDate,
                                    rangePreset = rangePreset,
                                    customStart = customStart,
                                    customEnd = customEnd,
                                    chartType = chartType,
                                    userGranularity = userGranularity,
                                    onDaySelected = { date ->
                                        selectedDate = date
                                        rangePreset = RangePreset.Selected
                                        Prefs.putString("range", rangePreset.storage)
                                    },
                                    onWeekSelected = { start, end ->
                                        selectedDate = start
                                        customStart = start
                                        customEnd = end
                                        rangePreset = RangePreset.Custom
                                        Prefs.putString("range", rangePreset.storage)
                                    },
                                    onRangePreset = { preset ->
                                        if (preset == RangePreset.Selected && selectedDate == null) {
                                            selectedDate = today
                                        }
                                        rangePreset = preset
                                        Prefs.putString("range", preset.storage)
                                    },
                                    onCustomRange = {
                                        chooseDateRange(customStart, customEnd)?.let { (start, end) ->
                                            customStart = start
                                            customEnd = end
                                            rangePreset = RangePreset.Custom
                                            Prefs.putString("range", rangePreset.storage)
                                        }
                                    },
                                    onChartType = {
                                        chartType = it
                                        Prefs.putString("chartType", it.storage)
                                    },
                                    onGranularity = {
                                        userGranularity = it
                                        Prefs.putString("granularity", it.storage)
                                    }
                                )
                                StatsPage.Ranking -> RankingPage(
                                    data = snapshot,
                                    query = query,
                                    onQueryChange = { query = it },
                                    page = safePage,
                                    onPageChange = { rankPage = it }
                                )
                            }
                            PageFooter(snapshot)
                        }
                    }
                }
            }
        }
    }

@Composable
private fun PageHeader(
    themeMode: ThemeMode,
    onTheme: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onClose: () -> Unit
) {
    val colors = LocalStatsColors.current
    val themeIcon = when (themeMode) {
        ThemeMode.System -> IconPaths.Routine
        ThemeMode.Light -> IconPaths.LightMode
        ThemeMode.Dark -> IconPaths.DarkMode
    }
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
            IconAction(themeIcon, "切换主题", colors.muted, onTheme)
            VerticalRule(colors.line, 17.dp)
            TextAction(IconPaths.Download, "导入", colors.text, onImport)
            TextAction(IconPaths.Upload, "导出", colors.text, onExport)
            Box(Modifier.width(4.dp))
            IconAction(IconPaths.Close, "关闭", colors.muted, onClose)
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
        Box(Modifier.fillMaxWidth().height(1.dp).drawWith { drawRect(color = colors.line) })
    }
    Box(Modifier.height(30.dp))
}

@Composable
private fun NavTab(icon: String, label: String, active: Boolean, onClick: () -> Unit) {
    val colors = LocalStatsColors.current
    Surface(onClick = onClick, color = Color.Transparent) {
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
                AppIcon(icon, if (active) colors.text else colors.muted, iconSize = 17.dp)
                Text(
                    label,
                    style = AppType.tab.copy(
                        color = if (active) colors.text else colors.muted,
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
        Box(Modifier.fillMaxWidth().height(1.dp).drawWith { drawRect(color = colors.line) })
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
    Surface(
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
    Surface(
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

private fun filteredRankCount(data: StatsData, query: String): Int {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return data.playCounts.count { it.value > 0 }
    return data.sortedTracks().count {
        it.track.title.lowercase().contains(needle) || it.track.artist.lowercase().contains(needle)
    }
}

private fun chooseDateRange(start: LocalDate?, end: LocalDate?): Pair<LocalDate, LocalDate>? {
    val startField = JTextField(start?.toString() ?: "")
    val endField = JTextField(end?.toString() ?: "")
    val panel = JPanel(GridLayout(0, 1, 0, 6))
    panel.add(JLabel("开始日期（YYYY-MM-DD）"))
    panel.add(startField)
    panel.add(JLabel("结束日期（YYYY-MM-DD）"))
    panel.add(endField)
    while (true) {
        val result = JOptionPane.showConfirmDialog(
            null,
            panel,
            "自定义日期范围",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE
        )
        if (result != JOptionPane.OK_OPTION) return null
        try {
            val parsedStart = LocalDate.parse(startField.text.trim())
            val parsedEnd = LocalDate.parse(endField.text.trim())
            if (parsedEnd.isBefore(parsedStart)) {
                throw IllegalArgumentException("结束日期不能早于开始日期")
            }
            return parsedStart to parsedEnd
        } catch (failure: Throwable) {
            JOptionPane.showMessageDialog(
                null,
                failure.message ?: "请输入有效日期",
                "日期格式不正确",
                JOptionPane.WARNING_MESSAGE
            )
        }
    }
}
