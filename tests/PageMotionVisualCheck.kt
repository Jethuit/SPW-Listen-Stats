@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, com.moriafly.salt.ui.UnstableSaltUiApi::class)

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalFocusManager
import com.jethuit.spw.listenstats.ui.*
import com.jethuit.spw.listenstats.data.*
import com.moriafly.salt.ui.SaltTheme
import com.moriafly.salt.ui.saltConfigs
import org.jetbrains.skia.EncodedImageFormat
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

fun main(args: Array<String>) {
    val out = Path.of(args.single())
    Files.createDirectories(out)
    for ((name, colors) in listOf("light" to LightStatsColors, "dark" to DarkStatsColors)) {
        var page by mutableStateOf(StatsPage.Overview)
        var show by mutableStateOf(true)
        var clicks = 0
        var imports = 0
        var animations by mutableStateOf(true)
        var clearFocus: () -> Unit = {}
        lateinit var scene: ImageComposeScene
        SwingUtilities.invokeAndWait {
            scene = ImageComposeScene(400, 320, density = Density(1f)) {
                SaltTheme(configs = saltConfigs(colors.isDark)) {
                    MaterialTheme(colorScheme = if (colors.isDark) darkColorScheme() else lightColorScheme()) {
                        CompositionLocalProvider(LocalStatsColors provides colors, LocalStatsAnimationsEnabled provides animations) {
                            val focus = LocalFocusManager.current
                            SideEffect { clearFocus = { focus.clearFocus(force = true) } }
                            Box(Modifier.fillMaxSize().drawWith { drawRect(colors.pageBackground) }) {
                                if (show) StatsPageEnter {
                                    Column(Modifier.fillMaxSize().padding(20.dp)) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                            StatsText("听歌统计", modifier = Modifier.weight(1f), style = AppType.sectionTitle.copy(color = colors.text))
                                            BackupMenu({ imports++ }, {})
                                        }
                                        Spacer(Modifier.height(12.dp))
                                        PageNav(page) { if (page != it) { clicks++; page = it } }
                                        StatsPageBody(page) { displayed ->
                                            Box(Modifier.fillMaxWidth().height(if (displayed == StatsPage.Overview) 100.dp else 140.dp)
                                                .drawWith { drawRect(if (displayed == StatsPage.Overview) Color(0xFF286AC6) else Color(0xFFB64352)) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        var time = 0L
        fun frame(file: String? = null): BufferedImage {
            time += 16_666_667L
            var bytes = byteArrayOf()
            SwingUtilities.invokeAndWait {
                scene.render(time).use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)!!.use { bytes = it.bytes }
                }
            }
            Thread.sleep(3) // Let composition effects resume between controlled frame ticks.
            if (file != null) Files.write(out.resolve("$name-$file.png"), bytes)
            return ImageIO.read(ByteArrayInputStream(bytes))
        }
        fun settle(): BufferedImage { repeat(30) { frame() }; return frame() }
        fun sameRegion(a: BufferedImage, b: BufferedImage, left: Int, top: Int, right: Int, bottom: Int): Boolean =
            (left until right).all { x -> (top until bottom).all { y -> a.getRGB(x, y) == b.getRGB(x, y) } }
        fun underline(image: BufferedImage): Pair<IntRange, Int> {
            for (y in 50 until 125) {
                val xs = (20 until 380).filter { x -> image.getRGB(x, y) == colors.accent.toArgb() }
                if (xs.size > 70) return (xs.first()..xs.last()) to y
            }
            error("Full-width bottom indicator is missing")
        }
        try {
            val entryStart = frame("entry-start")
            repeat(3) { frame() }
            val entryMid = frame("entry-mid")
            settle()
            val overview = frame("overview")
            check(!sameRegion(entryStart, overview, 20, 20, 350, 250))
            check(!sameRegion(entryMid, overview, 20, 20, 350, 250))
            SwingUtilities.invokeAndWait {
                scene.sendPointerEvent(PointerEventType.Press, Offset(210f, 75f))
                scene.sendPointerEvent(PointerEventType.Release, Offset(210f, 75f))
                scene.sendPointerEvent(PointerEventType.Exit, Offset(-1f, -1f))
                clearFocus()
            }
            frame(); frame()
            check(page == StatsPage.Ranking && clicks == 1) { "Native-style navigation did not activate once" }
            val forward = frame("forward-mid")
            settle()
            // Ripple enforces a real-time minimum press duration, independently
            // of the controlled animation clock. Do not publish a pressed state.
            Thread.sleep(300)
            repeat(16) { frame() }
            val ranking = frame("ranking")
            val (overviewBar, barY) = underline(overview)
            val (rankingBar, _) = underline(ranking)
            val (movingBar, _) = underline(forward)
            check(overviewBar.count() == rankingBar.count() && overviewBar.first < movingBar.first &&
                movingBar.first < rankingBar.first) { "Underline did not slide between the full button widths" }
            check(rankingBar.first == overviewBar.last + 1) { "Navigation buttons still have a gap" }
            check(overview.getRGB(overviewBar.first, barY - 34) == colors.pageBackground.toArgb()) {
                "Selected navigation still paints a gray background"
            }
            check(!sameRegion(forward, ranking, 20, 130, 380, 240)) { "Body did not animate" }
            check(sameRegion(overview, ranking, 20, 20, 350, 40)) { "Header moved during tab switch" }
            SwingUtilities.invokeAndWait { page = StatsPage.Overview }
            frame(); frame()
            frame("reverse-mid")
            // Interrupt a reversal, then request the latest target again. No transition queue.
            SwingUtilities.invokeAndWait { page = StatsPage.Ranking }
            frame(); frame()
            SwingUtilities.invokeAndWait { page = StatsPage.Overview }
            settle()
            Thread.sleep(300)
            repeat(16) { frame() }
            val restored = frame("restored")
            check(sameRegion(overview, restored, 20, 130, 380, 220)) { "Rapid reversals did not reach the latest page" }
            check(restored.getRGB(260, 85) == overview.getRGB(260, 85)) {
                "Inactive navigation retained a press overlay in the preview"
            }
            SwingUtilities.invokeAndWait { show = false }; settle()
            SwingUtilities.invokeAndWait { show = true }
            val reopen = frame("reopen-start")
            check(!sameRegion(reopen, overview, 20, 20, 350, 220)) { "Reopening must replay entry" }
            settle()
            SwingUtilities.invokeAndWait {
                scene.sendPointerEvent(PointerEventType.Press, Offset(360f, 40f))
                scene.sendPointerEvent(PointerEventType.Release, Offset(360f, 40f))
            }
            settle()
            frame("backup-menu")
            SwingUtilities.invokeAndWait {
                scene.sendPointerEvent(PointerEventType.Press, Offset(350f, 82f))
                scene.sendPointerEvent(PointerEventType.Release, Offset(350f, 82f))
            }
            settle()
            check(imports == 1) { "Compact menu did not dispatch import once" }
            SwingUtilities.invokeAndWait { animations = false; show = false }
            frame(); frame()
            SwingUtilities.invokeAndWait { show = true }
            frame(); frame()
            val immediateEntry = frame()
            check(sameRegion(immediateEntry, settle(), 20, 144, 380, 220)) { "Disabled entry still animates" }
            SwingUtilities.invokeAndWait { page = StatsPage.Ranking }
            frame(); frame()
            val immediatePage = frame()
            check(sameRegion(immediatePage, settle(), 20, 144, 380, 240)) { "Disabled page switch still animates" }
            println("PASS $name: animation switch bypasses entry and page transitions")
            println("PASS $name: navigation click, entry, directional body switch, fixed title and rapid reversals")
        } finally { SwingUtilities.invokeAndWait { scene.close() } }
    }
    check(StatsMotionDurationMillis == 300)
    if (System.getProperty("spw.test.skipRanking") != "true") {
        rankingMotionCheck(out, LightStatsColors, "light")
        rankingMotionCheck(out, DarkStatsColors, "dark")
    }
    placeholderVisuals(out)
    if (System.getProperty("spw.test.skipHeatmap") != "true") heatmapMotionCheck(out)
}

/** Review the real placeholder and fixed header in both themes, using no statistics data. */
private fun placeholderVisuals(out: Path) {
    for ((name, colors) in listOf("light" to LightStatsColors, "dark" to DarkStatsColors)) {
        lateinit var scene: ImageComposeScene
        var page by mutableStateOf(StatsPage.Overview)
        SwingUtilities.invokeAndWait {
            scene = ImageComposeScene(800, 680, Density(1f)) {
                SaltTheme(configs = saltConfigs(colors.isDark)) {
                    MaterialTheme {
                        CompositionLocalProvider(LocalStatsColors provides colors,
                            LocalStatsAnimationsEnabled provides false) {
                            Box(Modifier.fillMaxSize().drawWith { drawRect(colors.pageBackground) }) {
                                StatsPageFrame({}) {
                                    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp)) {
                                        Spacer(Modifier.height(12.dp))
                                        PageNav(page) { page = it }
                                        StatsPlaceholder(page)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        try {
            for (target in StatsPage.entries) {
                SwingUtilities.invokeAndWait { page = target }
                repeat(3) { index ->
                    SwingUtilities.invokeAndWait {
                        scene.render((target.ordinal * 3L + index + 1) * 16_666_667L).use { image ->
                            if (index == 2) image.encodeToData(EncodedImageFormat.PNG)!!.use {
                                Files.write(out.resolve("placeholder-$name-${target.name.lowercase()}.png"), it.bytes)
                            }
                        }
                    }
                    Thread.sleep(3)
                }
            }
        } finally { SwingUtilities.invokeAndWait { scene.close() } }
    }
}

/** Actual calendar drawing: ordered column reveal, retargets, refresh and disabled animations. */
private fun heatmapMotionCheck(out: Path) {
    val today = java.time.LocalDate.of(2026, 10, 22)
    val firstDay = java.time.LocalDate.of(2026, 1, 1)
    var data by mutableStateOf(StatsData.empty().copy(dailyMillis = (0L..294L)
        .associate { firstDay.plusDays(it) to ((it % 5 + 1) * 1_800_000L) }))
    var weekly by mutableStateOf(false)
    var year by mutableStateOf(2026)
    var animations by mutableStateOf(true)
    lateinit var scene: ImageComposeScene
    SwingUtilities.invokeAndWait {
        scene = ImageComposeScene(1000, 250, Density(1f)) {
            SaltTheme {
                MaterialTheme {
                    CompositionLocalProvider(LocalStatsColors provides LightStatsColors,
                        LocalStatsAnimationsEnabled provides animations) {
                        Box(Modifier.fillMaxSize().drawWith { drawRect(LightStatsColors.pageBackground) }.padding(20.dp)) {
                            YearHeatmap(data, year, weekly, today, null, {}, { _, _ -> })
                        }
                    }
                }
            }
        }
    }
    var time = 0L
    fun frame(name: String? = null): BufferedImage {
        time += 16_666_667L
        var bytes = byteArrayOf()
        SwingUtilities.invokeAndWait {
            scene.render(time).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { bytes = it.bytes } }
        }
        Thread.sleep(3)
        if (time <= 750_000_015L) {
            Files.write(out.resolve("heatmap-reveal-%03d.png".format(time / 16_666_667L)), bytes)
        }
        if (name != null) Files.write(out.resolve("heatmap-$name.png"), bytes)
        return ImageIO.read(ByteArrayInputStream(bytes))
    }
    fun settle(): BufferedImage { repeat(45) { frame() }; return frame() }
    fun same(a: BufferedImage, b: BufferedImage) =
        (47 until 980).all { x -> (47 until 180).all { y -> a.getRGB(x, y) == b.getRGB(x, y) } }
    fun ink(image: BufferedImage, from: Int, to: Int) =
        (from until to).sumOf { x -> (47 until 180).count { y -> image.getRGB(x, y) != LightStatsColors.pageBackground.toArgb() } }
    try {
        val blank = frame("start")
        repeat(10) { frame() }
        val early = frame("early")
        check(ink(early, 47, 300) > 1000 && ink(early, 750, 980) == 0) { "Heatmap did not reveal from left to right" }
        repeat(24) { frame() }
        val late = frame("late")
        val daily = settle()
        frame("daily")
        check(!same(late, daily)) { "Heatmap reveal completed before the requested 650ms" }
        check((47 until 980).all { x -> (20 until 45).all { y -> blank.getRGB(x, y) == daily.getRGB(x, y) } }) {
            "Month labels moved or faded with the cells"
        }
        SwingUtilities.invokeAndWait { weekly = true }
        repeat(10) { frame() }
        val weekEarly = frame("week-early")
        check(ink(weekEarly, 750, 980) == 0)
        val week = settle()
        check(!same(daily, week))
        SwingUtilities.invokeAndWait { year = 2025 }
        frame(); frame()
        SwingUtilities.invokeAndWait { year = 2026; weekly = false }
        check(same(daily, settle())) { "Interrupted heatmap reveal did not reach the latest mode/year" }
        SwingUtilities.invokeAndWait { data = data.copy(dailyMillis = data.dailyMillis + (firstDay to 43_200_000L)) }
        frame(); frame()
        val refreshed = frame()
        check(!same(daily, refreshed) && same(refreshed, settle())) { "Ordinary refresh replayed the calendar reveal" }
        SwingUtilities.invokeAndWait { animations = false; weekly = true }
        frame(); frame()
        val immediate = frame()
        check(same(immediate, settle())) { "Disabled heatmap still animated" }
        check(HeatmapRevealDurationMillis == 650)
        println("PASS heatmap: left-to-right 650ms reveal, stable labels, mode/year reversals, in-place refresh and animation switch")
    } finally { SwingUtilities.invokeAndWait { scene.close() } }
}

/** Render the actual ranking list, including a 25-row page and a short final page. */
private fun rankingMotionCheck(out: Path, colors: StatsColors, palette: String) {
    // Match the user's 125% desktop scaling: hairline borders must stay one pixel.
    val scale = 1.25f
    fun px(value: Int) = (value * scale).roundToInt()
    var page by mutableStateOf(1)
    var mode by mutableStateOf(RankingMode.Song)
    var revision by mutableStateOf(0)
    var query by mutableStateOf("")
    var animations by mutableStateOf(true)
    lateinit var scene: ImageComposeScene
    SwingUtilities.invokeAndWait {
        scene = ImageComposeScene(px(900), px(400), density = Density(scale)) {
            SaltTheme(configs = saltConfigs(colors.isDark)) {
                MaterialTheme(colorScheme = if (colors.isDark) darkColorScheme() else lightColorScheme()) {
                    CompositionLocalProvider(LocalStatsColors provides colors, LocalStatsAnimationsEnabled provides animations) {
                        val entries = (1..if (mode == RankingMode.Song) 26 else 3).map { index ->
                            RankedEntry(index, RankingEntry(RankingKey(mode, "$index"),
                                "${mode.label} $index / $revision", "合成测试", "测试专辑", index.toLong(), null))
                        }
                        Box(Modifier.fillMaxSize().drawWith { drawRect(colors.pageBackground) }.padding(20.dp)) {
                            RankingPage(entries.filter { query.isEmpty() || it.value.title.contains(query) },
                                mode, { mode = it; page = 1 }, query, { query = it; page = 1 }, page, { page = it })
                        }
                    }
                }
            }
        }
    }
    var time = 0L
    var captureSearch = false
    var searchFrame = 0
    fun frame(file: String? = null): BufferedImage {
        time += 16_666_667L
        var bytes = byteArrayOf()
        SwingUtilities.invokeAndWait {
            scene.render(time).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { bytes = it.bytes }
            }
        }
        Thread.sleep(3)
        if (file != null) Files.write(out.resolve("ranking-$palette-$file.png"), bytes)
        if (captureSearch && palette == "light") {
            Files.write(out.resolve("search-motion-%03d.png".format(++searchFrame)), bytes)
        }
        return ImageIO.read(ByteArrayInputStream(bytes))
    }
    fun settle(): BufferedImage { repeat(30) { frame() }; return frame() }
    fun same(a: BufferedImage, b: BufferedImage) =
        (px(40) until px(860)).all { x -> (px(100) until px(290)).all { y -> a.getRGB(x, y) == b.getRGB(x, y) } }
    try {
        val first = settle()
        SwingUtilities.invokeAndWait { page = 2 }
        repeat(7) { frame() }
        val pageMid = frame("page-mid")
        repeat(7) { frame() }
        val pageLate = frame()
        val last = settle()
        check(!same(first, pageMid) && !same(pageMid, last)) { "Actual ranking pagination did not animate" }
        check(!same(pageLate, last)) { "Pagination completed before 300ms" }
        frame("short-page")
        SwingUtilities.invokeAndWait { mode = RankingMode.Artist; page = 1 }
        repeat(7) { frame() }
        val modeMid = frame("mode-mid")
        val artist = settle()
        check(!same(modeMid, artist)) { "Actual ranking mode switch did not animate" }
        SwingUtilities.invokeAndWait { mode = RankingMode.Album }
        frame(); frame()
        SwingUtilities.invokeAndWait { mode = RankingMode.Song; page = 1 }
        check(same(first, settle())) { "Rapid ranking reversals did not restore the requested list" }
        SwingUtilities.invokeAndWait { revision++ }
        frame(); frame()
        val refresh = frame()
        check(!same(first, refresh) && same(refresh, settle())) { "Data refresh should update in place without replaying" }
        fun click(x: Float, y: Float) {
            SwingUtilities.invokeAndWait {
                scene.sendPointerEvent(PointerEventType.Press, Offset(x * scale, y * scale))
                scene.sendPointerEvent(PointerEventType.Release, Offset(x * scale, y * scale))
            }
        }
        val collapsed = frame("toolbar-collapsed")
        captureSearch = true
        click(862f, 50f)
        repeat(11) { frame() }
        val searchMid = frame("search-mid")
        val expanded = settle()
        frame("search-expanded")
        fun sameSearchEdge(a: BufferedImage, b: BufferedImage) =
            (px(670) until px(832)).all { x -> (px(32) until px(35)).all { y -> a.getRGB(x, y) == b.getRGB(x, y) } }
        check(!sameSearchEdge(collapsed, searchMid) && !sameSearchEdge(searchMid, expanded)) {
            "Native search fade/scale must have an intermediate frame"
        }
        // The right edge of the close button stays at the old search action position.
        check(expanded.getRGB(px(879), px(50)) != colors.pageBackground.toArgb()) { "Close button right edge moved" }
        SwingUtilities.invokeAndWait {
            val native = java.awt.event.KeyEvent(
                java.awt.Canvas(), java.awt.event.KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0,
                java.awt.event.KeyEvent.VK_UNDEFINED, 'q'
            )
            val compose = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
                .getMethod("toComposeEvent", java.awt.event.KeyEvent::class.java).invoke(null, native)
            scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(compose))
        }
        frame(); frame()
        check(query == "q") { "Expanded search did not accept typed input" }
        click(862f, 50f)
        repeat(5) { frame() }
        val closing = frame("search-closing")
        val closed = settle()
        captureSearch = false
        check(!sameSearchEdge(closing, closed)) { "Closing search did not animate" }
        check(query.isEmpty()) { "Closing search did not clear the filter" }
        click(862f, 50f); frame(); frame()
        click(862f, 50f); frame(); frame()
        click(862f, 50f); settle()
        click(862f, 50f); settle()
        check(query.isEmpty()) { "Rapid search reversals restored a stale filter" }
        SwingUtilities.invokeAndWait { animations = false }
        click(862f, 50f); frame(); frame()
        val immediateSearch = frame()
        check(sameSearchEdge(immediateSearch, settle())) { "Disabled search still animates" }
        click(862f, 50f); settle()
        SwingUtilities.invokeAndWait { animations = true }
        click(814f, 50f)
        settle()
        frame("type-menu")
        click(800f, 132f)
        settle()
        check(mode == RankingMode.Artist) { "Native-style type menu did not select the artist ranking" }
        println("PASS $palette actual toolbar: animated search, fixed right edge, reversals, animation switch, focus/type, close/clear and type menu")
        println("PASS actual ranking: modes, pagination, short page, 300ms duration, reversals and in-place data refresh")
    } finally { SwingUtilities.invokeAndWait { scene.close() } }
}
