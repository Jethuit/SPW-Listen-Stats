@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, com.moriafly.salt.ui.UnstableSaltUiApi::class)

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
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

/** Actual adaptive heatmap colors and per-cell weekly selection, using synthetic durations only. */
fun main(args: Array<String>) {
    val output = Path.of(args.single())
    Files.createDirectories(output)
    val today = LocalDate.of(2026, 10, 20)
    var data by mutableStateOf(StatsData.empty().copy(dailyMillis = listOf(0.5, 2.0, 4.0, 6.0)
        .mapIndexed { index, hours -> LocalDate.of(2026, 1, 5 + index) to (hours * 3_600_000L).toLong() }.toMap()))
    var weekly by mutableStateOf(false)
    var selected by mutableStateOf<LocalDate?>(null)
    lateinit var scene: ImageComposeScene
    SwingUtilities.invokeAndWait {
        scene = ImageComposeScene(1000, 300, density = Density(1.25f)) {
            SaltTheme {
                MaterialTheme {
                    CompositionLocalProvider(LocalStatsColors provides LightStatsColors,
                        LocalStatsAnimationsEnabled provides false) {
                        Box(Modifier.fillMaxSize().drawWith { drawRect(LightStatsColors.pageBackground) }) {
                            Box(Modifier.fillMaxWidth().padding(20.dp)) {
                                YearHeatmap(data, 2026, weekly, today, selected, { selected = it }, { start, _ -> selected = start })
                            }
                        }
                    }
                }
            }
        }
    }
    try {
        for (name in listOf("daily", "rebalanced", "week-selected", "week-clear", "edge-week")) {
            SwingUtilities.invokeAndWait {
                when (name) {
                    "rebalanced" -> data = data.copy(dailyMillis = data.dailyMillis + (1L..12L)
                        .associate { today.minusDays(it) to 40_000_000L })
                    "week-selected" -> { weekly = true; selected = LocalDate.of(2026, 1, 5) }
                    "week-clear" -> selected = null
                    "edge-week" -> selected = LocalDate.of(2026, 1, 1)
                }
            }
            repeat(3) {
                SwingUtilities.invokeAndWait { scene.render().close() }
                Thread.sleep(20)
            }
            SwingUtilities.invokeAndWait {
                scene.render().use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)!!.use {
                        Files.write(output.resolve("heatmap-$name.png"), it.bytes)
                    }
                }
            }
        }
    } finally { SwingUtilities.invokeAndWait { scene.close() } }
    println("Rendered adaptive daily recoloring, a selected week with individual cell outlines, cleared selection and a clipped edge week")
}
