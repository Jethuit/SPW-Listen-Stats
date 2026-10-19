@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    com.moriafly.salt.ui.UnstableSaltUiApi::class
)

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.jethuit.spw.listenstats.ui.HostBackButton
import com.jethuit.spw.listenstats.ui.drawWith
import com.moriafly.salt.ui.SaltTheme
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities

/** Renders only the back control; no player, profile, database, or window is opened. */
fun main(args: Array<String>) {
    val output = Path.of(args.single())
    Files.createDirectories(output.parent)
    SwingUtilities.invokeAndWait {
        val scene = ImageComposeScene(width = 160, height = 100, density = Density(1.25f)) {
            SaltTheme {
                Box(Modifier.fillMaxSize().drawWith { drawRect(Color(0xFFF8F8F8)) }) {
                    HostBackButton(onBack = {})
                }
            }
        }
        try {
            scene.render().use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { png ->
                    Files.write(output, png.bytes)
                }
            }
        } finally {
            scene.close()
        }
    }
    println("Rendered the native SaltUI back button at 125% scale: $output")
}
