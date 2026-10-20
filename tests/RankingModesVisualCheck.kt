@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, com.moriafly.salt.ui.UnstableSaltUiApi::class)

import androidx.compose.foundation.layout.*
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
import javax.swing.SwingUtilities

/** Synthetic tags/counts only. Null cover IDs keep this fixture independent of the player API. */
fun main(args: Array<String>) {
    val output = Path.of(args.single())
    Files.createDirectories(output)
    val tracks = listOf(
        TrackMeta("demo1", "歌曲一", "歌手甲/歌手乙", "专辑一", "专辑艺术家", 240_000),
        TrackMeta("demo2", "歌曲二", "歌手甲", "专辑一", "专辑艺术家", 240_000),
        TrackMeta("demo3", "歌曲三", "歌手乙", "专辑二", "歌手乙", 240_000)
    )
    val data = StatsData.empty().copy(tracks = tracks.associateBy { it.id },
        playCounts = mapOf("demo1" to 5, "demo2" to 3, "demo3" to 2))
    SwingUtilities.invokeAndWait {
        for ((theme, colors) in listOf("light" to LightStatsColors, "dark" to DarkStatsColors)) {
            for (width in listOf(280, 760)) for (mode in RankingMode.entries) {
                val rows = Rankings.ranked(data, mode).map { it.copy(value = it.value.copy(coverTrackId = null)) }
                val scene = ImageComposeScene(width * 5 / 4, 570, density = Density(1.25f)) {
                    SaltTheme {
                        MaterialTheme(colorScheme = if (colors.isDark) darkColorScheme() else lightColorScheme()) {
                            CompositionLocalProvider(LocalStatsColors provides colors) {
                                Box(Modifier.fillMaxSize().drawWith { drawRect(colors.pageBackground) }) {
                                    RankingPage(rows, mode, {}, "", {}, 1, {})
                                }
                            }
                        }
                    }
                }
                try {
                    scene.render().use { image ->
                        image.encodeToData(EncodedImageFormat.PNG)!!.use {
                            Files.write(output.resolve("$theme-${mode.storage}-$width.png"), it.bytes)
                        }
                    }
                } finally { scene.close() }
            }
        }
    }
    println("Rendered 12 real ranking layouts: three modes, two widths and two themes; synthetic data only")
}
