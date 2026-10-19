@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, com.moriafly.salt.ui.UnstableSaltUiApi::class)

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.jethuit.spw.listenstats.ui.*
import com.moriafly.salt.ui.SaltTheme
import com.moriafly.salt.ui.rememberScrollState
import com.moriafly.salt.ui.ScrollState
import com.moriafly.salt.ui.verticalScroll
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

private fun save(scene: ImageComposeScene, output: Path) {
    scene.render().use { image ->
        image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(output, it.bytes) }
    }
}

fun main(args: Array<String>) {
    val output = Path.of(args.single())
    Files.createDirectories(output)
    SwingUtilities.invokeAndWait {
        var topY = -1f
        lateinit var scroll: ScrollState
        val frame = ImageComposeScene(1875, 900, density = Density(1.25f)) {
            SaltTheme {
                scroll = rememberScrollState(0)
                Box(Modifier.fillMaxSize().drawWith { drawRect(Color(0xFFF8F8F8)) }) {
                    StatsPageFrame(onBack = {}) {
                        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                            Box(Modifier.fillMaxWidth().height(80.dp).onGloballyPositioned {
                                topY = it.positionInRoot().y
                            }) { StatsText("听歌统计", style = AppType.pageTitle) }
                            Box(Modifier.fillMaxWidth().height(1400.dp).drawWith { drawRect(Color(0xFFBBD7FA)) })
                        }
                    }
                }
            }
        }
        try {
            for (width in listOf(579, 599, 600, 601, 899, 900, 901, 1191, 1192, 1193, 1449, 1450, 1451, 1500)) {
                frame.constraints = Constraints.fixed((width * 1.25f).roundToInt(), 900)
                frame.render().close()
                check(topY == 70f) { "Header jumped at width $width: content y=$topY" }
            }
            save(frame, output.resolve("frame-top.png"))
            runBlocking { scroll.scrollTo(400) }
            save(frame, output.resolve("frame-scrolled.png"))
            check(scroll.value == 400)
            println("PASS content starts below the 56dp navigation row at 14 widths; scrolling remains available")
        } finally { frame.close() }

        val heights = mutableListOf<Int>()
        for (width in listOf(140, 160, 220, 340)) {
            var height = 0
            val scene = ImageComposeScene((width * 1.25f).roundToInt(), 100, density = Density(1.25f)) {
                SaltTheme {
                    Box(Modifier.onSizeChanged { height = it.height }) {
                        FavoriteHourStat(width.dp, 14, "最常听歌时段", LightStatsColors)
                    }
                }
            }
            try {
                save(scene, output.resolve("favorite-hour-$width.png"))
                heights += height
            } finally { scene.close() }
        }
        check(heights.distinct().size == 1) { "Favorite hour wrapped on resize: $heights" }
        println("PASS favorite-hour text keeps a stable single-line value at four cell widths")

        for (width in listOf(280, 400, 543, 760, 1080)) {
            val scene = ImageComposeScene((width * 1.25f).roundToInt(), 200, density = Density(1.25f)) {
                SaltTheme {
                    Box(Modifier.fillMaxSize().drawWith { drawRect(Color(0xFFF8F8F8)) }) {
                        RankingFooter(133, 1, 6, {})
                    }
                }
            }
            try { save(scene, output.resolve("ranking-footer-$width.png")) }
            finally { scene.close() }
        }
        println("Rendered real ranking footers at five widths for visual review")
    }
}
