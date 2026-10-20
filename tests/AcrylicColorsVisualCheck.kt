@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, com.moriafly.salt.ui.UnstableSaltUiApi::class)

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.jethuit.spw.listenstats.data.StatsData
import com.jethuit.spw.listenstats.ui.*
import com.moriafly.salt.ui.SaltTheme
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import javax.swing.SwingUtilities

/** Synthetic flat backdrops sampled from the user's native Acrylic captures;
 * these renders test actual components' alpha compositing, not Windows DWM. */
fun main(args: Array<String>) {
    val output = Path.of(args.single())
    Files.createDirectories(output)
    val colors = materialStatsColors(LightStatsColors, "WindowsAcrylic", true)
    SwingUtilities.invokeAndWait {
        for ((name, backdrop) in listOf(
            "focused" to Color(0xFFC3DBEE),
            "focused-blue" to Color(0xFFA7D6FE),
            "unfocused" to Color(0xFFDDDDDD)
        )) {
            val scene = ImageComposeScene(800, 520, density = Density(1.25f)) {
                SaltTheme {
                    MaterialTheme(colorScheme = lightColorScheme()) {
                        CompositionLocalProvider(LocalStatsColors provides colors) {
                            Box(Modifier.fillMaxSize().drawWith { drawRect(backdrop) }) {
                                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                                    SummaryStrip(4) { width ->
                                        repeat(4) {
                                            Box(Modifier.width(width).height(40.dp), contentAlignment = Alignment.Center) {
                                                StatsText("3 天", style = AppType.body.copy(color = colors.text))
                                            }
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        SegmentedControl(listOf(SegmentOption("song", "歌曲"), SegmentOption("artist", "歌手"),
                                            SegmentOption("album", "专辑")), "song", {})
                                        SelectBox("2026年", listOf("2026" to "2026年"), "2026", {})
                                    }
                                    Box(Modifier.fillMaxWidth().height(1.dp).drawWith { drawRect(colors.line) })
                                    YearHeatmap(StatsData.empty(), 2026, false, LocalDate.of(2026, 10, 20), null, {}, { _, _ -> })
                                }
                            }
                        }
                    }
                }
            }
            try {
                scene.render().use { rendered ->
                    rendered.encodeToData(EncodedImageFormat.PNG)!!.use {
                        Files.write(output.resolve("acrylic-$name.png"), it.bytes)
                    }
                }
            } finally { scene.close() }
        }
    }
    println("Rendered Acrylic summary, controls, divider and heatmap on three sampled synthetic backdrops")
}
