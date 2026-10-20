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

/** Recompose the same real heatmap with a changed threshold; synthetic durations only. */
fun main(args: Array<String>) {
    val output = Path.of(args.single())
    Files.createDirectories(output)
    val today = LocalDate.of(2026, 10, 20)
    val data = StatsData.empty().copy(dailyMillis = listOf(0.5, 2.0, 4.0, 6.0)
        .mapIndexed { index, hours -> LocalDate.of(2026, 1, 5 + index) to (hours * 3_600_000L).toLong() }.toMap())
    var high by mutableStateOf(HeatmapScale.DEFAULT_HIGH_MILLIS)
    lateinit var scene: ImageComposeScene
    SwingUtilities.invokeAndWait {
        scene = ImageComposeScene(1000, 300, density = Density(1.25f)) {
            SaltTheme {
                MaterialTheme {
                    CompositionLocalProvider(LocalStatsColors provides LightStatsColors) {
                        Box(Modifier.fillMaxSize().drawWith { drawRect(LightStatsColors.pageBackground) }) {
                            Box(Modifier.fillMaxWidth().padding(20.dp)) {
                                YearHeatmap(data, 2026, false, today, null, {}, { _, _ -> }, dailyHighMillis = high)
                            }
                        }
                    }
                }
            }
        }
    }
    try {
        for ((name, value) in listOf("default" to HeatmapScale.DEFAULT_HIGH_MILLIS,
                                     "six-hours" to HeatmapScale.parseHours("6"))) {
            SwingUtilities.invokeAndWait { high = value }
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
    println("Rendered one actual heatmap before and after changing the daily high-duration setting")
}
