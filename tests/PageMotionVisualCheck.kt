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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalFocusManager
import com.jethuit.spw.listenstats.ui.*
import com.moriafly.salt.ui.SaltTheme
import org.jetbrains.skia.EncodedImageFormat
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

fun main(args: Array<String>) {
    val out = Path.of(args.single())
    Files.createDirectories(out)
    for ((name, colors) in listOf("light" to LightStatsColors, "dark" to DarkStatsColors)) {
        var page by mutableStateOf(StatsPage.Overview)
        var show by mutableStateOf(true)
        var clicks = 0
        var clearFocus: () -> Unit = {}
        lateinit var scene: ImageComposeScene
        SwingUtilities.invokeAndWait {
            scene = ImageComposeScene(400, 320, density = Density(1f)) {
                SaltTheme {
                    MaterialTheme(colorScheme = if (colors.isDark) darkColorScheme() else lightColorScheme()) {
                        CompositionLocalProvider(LocalStatsColors provides colors) {
                            val focus = LocalFocusManager.current
                            SideEffect { clearFocus = { focus.clearFocus(force = true) } }
                            Box(Modifier.fillMaxSize().drawWith { drawRect(colors.pageBackground) }) {
                                if (show) StatsPageEnter {
                                    Column(Modifier.fillMaxSize().padding(20.dp)) {
                                        StatsText("听歌统计", style = AppType.sectionTitle.copy(color = colors.text))
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
        fun settle(): BufferedImage { repeat(20) { frame() }; return frame() }
        fun sameRegion(a: BufferedImage, b: BufferedImage, left: Int, top: Int, right: Int, bottom: Int): Boolean =
            (left until right).all { x -> (top until bottom).all { y -> a.getRGB(x, y) == b.getRGB(x, y) } }
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
            println("PASS $name: navigation click, entry, directional body switch, fixed title and rapid reversals")
        } finally { SwingUtilities.invokeAndWait { scene.close() } }
    }
}
