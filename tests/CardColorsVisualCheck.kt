@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, com.moriafly.salt.ui.UnstableSaltUiApi::class)

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.jethuit.spw.listenstats.ui.*
import com.moriafly.salt.ui.SaltTheme
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities

/** Visual fixture using the actual summary card and popup; no player data is loaded. */
fun main(args: Array<String>) {
    val output = Path.of(args.single())
    Files.createDirectories(output)
    SwingUtilities.invokeAndWait {
        for ((name, colors) in listOf("light" to LightStatsColors, "dark" to DarkStatsColors)) {
            val scene = ImageComposeScene(650, 400, density = Density(1.25f)) {
                SaltTheme {
                    MaterialTheme(colorScheme = if (colors.isDark) darkColorScheme() else lightColorScheme()) {
                        CompositionLocalProvider(LocalStatsColors provides colors) {
                            Box(Modifier.fillMaxSize().drawWith { drawRect(colors.pageBackground) }) {
                                Box(Modifier.fillMaxWidth().padding(20.dp)) {
                                    SummaryStrip(4) { itemWidth ->
                                        repeat(4) {
                                            Box(Modifier.width(itemWidth).height(40.dp), contentAlignment = Alignment.Center) {
                                                StatsText("3 天", style = AppType.body.copy(color = colors.text))
                                            }
                                        }
                                    }
                                }
                                Box(Modifier.offset(20.dp, 140.dp)) {
                                    StatsTooltip(TooltipData("2026年6月19日", "听歌时长", "0 分钟", 0f, 0f))
                                }
                            }
                        }
                    }
                }
            }
            try {
                scene.render().use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)!!.use {
                        Files.write(output.resolve("cards-$name.png"), it.bytes)
                    }
                }
            } finally { scene.close() }
        }
    }
    println("Rendered actual summary cards and heatmap tooltips in both themes at 125%")
}
