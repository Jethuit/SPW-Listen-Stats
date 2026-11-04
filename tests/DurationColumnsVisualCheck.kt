@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, com.moriafly.salt.ui.UnstableSaltUiApi::class)

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
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

/** Actual ranking components with synthetic metadata; never query the library or invoke playback. */
fun main(args: Array<String>) {
    val output = Path.of(args.single())
    Files.createDirectories(output)
    val durations = listOf(3_945_000L, 2_881_000L, 2_581_000L, 2_547_000L, 2_469_000L,
        43_205_000L, 450_009_000L, 999L, 0L)
    val entries = durations.mapIndexed { index, millis ->
        RankedEntry(index + 1, RankingEntry(RankingKey(RankingMode.Song, "synthetic-$index"),
            "示例歌曲 ${index + 1}", "演示艺术家", "演示专辑", 0L, null, millis))
    }
    val shortRows = listOf(1_700_000L, 97_000L, 1_000L, 1_000L, 999L, 0L)
    val longRows = List(9) { 54_001_000L } // Aggregate needs three hour digits; individual rows need two.
    for ((theme, colors) in listOf("light" to LightStatsColors, "dark" to DarkStatsColors)) {
        for (detailsMode in listOf(false, true)) for (latinUnits in listOf(false, true)) for (width in listOf(1000, 360)) {
            val detailRows = (if (width == 360) longRows else shortRows).mapIndexed { index, millis ->
                ListeningDetailRow(TrackMeta("11111111-1111-1111-1111-111111111111:$index",
                    "示例歌曲 ${index + 1}", "演示艺术家", "演示专辑", "", 300_000L), millis,
                    if (index == 0) 1_000L else 0L)
            }
            val date = LocalDate.of(2026, 11, 2)
            val bucket = ChartBucket("示例时段", "2026年11月2日", detailRows.sumOf { it.millis }, date,
                if (width == 360) date.plusDays(6) else date, null)
            lateinit var scene: ImageComposeScene
            SwingUtilities.invokeAndWait {
                scene = ImageComposeScene((width * 1.25).toInt(), 1050, density = Density(1.25f)) {
                    SaltTheme { MaterialTheme(colorScheme = if (colors.isDark) darkColorScheme() else lightColorScheme()) {
                        CompositionLocalProvider(LocalStatsColors provides colors) {
                            Column(Modifier.fillMaxSize().drawWith { drawRect(colors.pageBackground) }.padding(24.dp)) {
                                if (detailsMode) ListeningDetailsPanel(bucket, ListeningDetails(bucket, detailRows, 0L),
                                    latinDurationUnits = latinUnits, onClose = {})
                                else RankingPage(entries, RankingMode.Song, {}, "", {}, 1, {},
                                    metric = RankingMetric.Duration, latinDurationUnits = latinUnits)
                            }
                        }
                    } }
                }
            }
            try {
                repeat(3) { SwingUtilities.invokeAndWait { scene.render().close() }; Thread.sleep(20) }
                SwingUtilities.invokeAndWait {
                    scene.render().use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use {
                        Files.write(output.resolve("${if (detailsMode) "details-" else ""}${if (latinUnits) "latin" else "chinese"}-$theme-$width.png"), it.bytes)
                    } }
                }
            } finally { SwingUtilities.invokeAndWait { scene.close() } }
        }
    }
    println("Rendered Chinese and Latin rankings/details in two themes and widths, including long aggregate hours")
}
