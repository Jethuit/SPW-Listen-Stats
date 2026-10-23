@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, com.moriafly.salt.ui.UnstableSaltUiApi::class)

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.jethuit.spw.listenstats.data.*
import com.jethuit.spw.listenstats.ui.*
import com.moriafly.salt.ui.SaltTheme
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import javax.swing.SwingUtilities

fun main(args: Array<String>) {
    val output = Path.of(args.single())
    Files.createDirectories(output)
    val date = LocalDate.of(2026, 10, 20)
    val buckets = (0..6).map { ChartBucket("10/${20 + it}", "2026年10月${20 + it}日", 900_000L, date.plusDays(it.toLong()), date.plusDays(it.toLong()), null) }
    val titles = listOf("沿途的光", "夜间航行", "潮汐", "城市微雨")
    val rows = titles.mapIndexed { i, title -> ListeningDetailRow(
        TrackMeta("11111111-1111-1111-1111-111111111111:$i", title, "演示歌手", "", "", 300_000),
        (4 - i) * 60_000L + (i + 1) * 7_000L, if (i == 3) 60_000L else 0L) }
    for ((name, palette, width) in listOf(Triple("light", LightStatsColors, 1000), Triple("dark", DarkStatsColors, 650))) {
        var open by mutableStateOf(false)
        var selected by mutableStateOf<Int?>(null)
        var chart by mutableStateOf(ChartType.Bar)
        var enabled by mutableStateOf(true)
        var visibleRows by mutableStateOf(6)
        var exiting by mutableStateOf(false)
        var details by mutableStateOf(ListeningDetails(buckets.first(), rows, 230_000))
        var marker = 0f
        lateinit var scene: ImageComposeScene
        SwingUtilities.invokeAndWait {
            scene = ImageComposeScene(width, 980, density = Density(1f)) {
                SaltTheme { MaterialTheme {
                    CompositionLocalProvider(LocalStatsColors provides palette, LocalStatsAnimationsEnabled provides enabled,
                        LocalStatsPageExiting provides exiting) {
                        Column(Modifier.fillMaxSize().drawWith { drawRect(palette.background) }.padding(28.dp)) {
                            StatsText("时长分布 · 歌曲明细演示", style = AppType.sectionTitle.copy(color = palette.text))
                            Spacer(Modifier.height(20.dp))
                            TrendChart(buckets, chart, false, selectedIndex = selected,
                                onBucketSelected = { selected = it; details = details.copy(bucket = buckets[it]); open = true },
                                onBucketDeselected = { open = false; selected = null })
                            ListeningDetailsExpansion(open, details, visibleRows) { open = false; selected = null }
                            Box(Modifier.padding(top = 30.dp).onGloballyPositioned { marker = it.positionInRoot().y }) {
                                StatsText("听歌小结", style = AppType.sectionTitle.copy(color = palette.text))
                            }
                        }
                    }
                } }
            }
        }
        var time = 0L
        fun frame(file: String? = null) {
            time += 16_666_667L
            SwingUtilities.invokeAndWait {
                scene.render(time).use { image ->
                    if (file != null) image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(output.resolve("$name-$file.png"), it.bytes) }
                }
            }
        }
        fun click(x: Float = width / 2f) { SwingUtilities.invokeAndWait {
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, 160f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, 160f))
        } }
        try {
            repeat(4) { frame() }
            val closed = marker
            click(); repeat(8) { frame() }; val middle = marker
            check(open && selected != null) { "Chart click did not select a bucket" }
            repeat(24) { frame() }; val expanded = marker
            check(middle > closed && middle < expanded) { "Expansion must animate height: $closed / $middle / $expanded" }
            frame("expanded")
            // Same-size snapshot refresh must keep the section expanded.
            SwingUtilities.invokeAndWait { details = details.copy(rows = rows.map { it.copy(millis = it.millis + 1) }) }
            repeat(3) { frame(); check(marker == expanded) { "Refresh replayed expansion" } }
            val firstSelection = selected
            click(width * .75f); repeat(3) { frame() }
            check(open && selected != firstSelection) { "Another bucket must switch details instead of closing" }
            click(width * .75f); check(!open && selected == null)
            repeat(28) { frame() }; check(marker == closed)
            for (type in listOf(ChartType.Line, ChartType.Curve)) {
                SwingUtilities.invokeAndWait { chart = type }
                repeat(2) { frame() }; click(); check(open)
                repeat(25) { frame() }
                click(); check(!open && selected == null) { "Repeated curve/line selection must close details" }
                repeat(25) { frame() }
            }
            SwingUtilities.invokeAndWait { enabled = false }
            repeat(3) { frame() }; click(); repeat(3) { frame() }; check(marker == expanded)
            val selectedBeforeResize = selected
            SwingUtilities.invokeAndWait { visibleRows = 3 }
            repeat(3) { frame() }
            check(marker == expanded - 64f && open && selected == selectedBeforeResize) {
                "Three visible rows must shorten the panel without closing or resetting selection"
            }
            SwingUtilities.invokeAndWait { visibleRows = 12 }
            repeat(3) { frame() }; check(marker == expanded) { "Few songs must not leave blank rows" }
            click(); check(!open && selected == null)
            repeat(3) { frame() }; check(marker == closed)
            SwingUtilities.invokeAndWait { exiting = true }
            repeat(3) { frame() }; click(); repeat(3) { frame() }; check(!open)
            println("PASS $name: click again to close on three chart types, switch bucket, animated close, stable refresh, disabled animation, exit guard")
        } finally { SwingUtilities.invokeAndWait { scene.close() } }
    }
}
