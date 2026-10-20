@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.Density
import com.jethuit.spw.listenstats.ui.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities

/** The backdrop is synthetic. This verifies composition/placement, not Windows DWM rendering. */
fun main(args: Array<String>) {
    val output = Path.of(args.single())
    Files.createDirectories(output)
    var hidden by mutableStateOf(false)
    var created = 0
    var disposed = 0
    var clicked = 0
    lateinit var scene: ImageComposeScene
    val colors = materialStatsColors(LightStatsColors, "WindowsMica", true)
    SwingUtilities.invokeAndWait {
        scene = ImageComposeScene(400, 300, density = Density(1f)) {
            MaterialTheme {
                Box(Modifier.fillMaxSize().drawWith { drawRect(Color(0xFF276B56)) }) {
                    RetainedHostContent(hidden) {
                        remember { created++; Any() }
                        DisposableEffect(Unit) { onDispose { disposed++ } }
                        Box(Modifier.fillMaxSize()
                            .onPointerEvent(PointerEventType.Release) { clicked++ }
                            .drawWith { drawRect(Color(0xFFBB2244)) })
                    }
                    if (hidden) {
                        Box(Modifier.fillMaxSize().drawWith { drawRect(colors.pageBackground) })
                    }
                }
            }
        }
    }
    fun render(name: String) {
        repeat(3) {
            SwingUtilities.invokeAndWait { scene.render().close() }
            Thread.sleep(20)
        }
        SwingUtilities.invokeAndWait {
            scene.render().use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(output.resolve(name), it.bytes) }
            }
        }
    }
    fun click() {
        SwingUtilities.invokeAndWait {
            scene.sendPointerEvent(PointerEventType.Press, Offset(200f, 150f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(200f, 150f))
        }
    }
    try {
        render("before.png"); click(); render("before.png")
        check(clicked == 1) { "Visible host control must receive a click: $clicked" }
        SwingUtilities.invokeAndWait { hidden = true }
        render("hidden.png"); click(); render("hidden.png")
        check(clicked == 1) { "Hidden host received a click: $clicked" }
        SwingUtilities.invokeAndWait { hidden = false }
        render("restored.png"); click(); render("restored.png")
        check(clicked == 2 && created == 1 && disposed == 0) {
            "Host state changed: clicks=$clicked, creates=$created, disposes=$disposed"
        }
        println("PASS host stays composed, does not receive clicks while hidden, and restores its state")
    } finally { SwingUtilities.invokeAndWait { scene.close() } }
}
