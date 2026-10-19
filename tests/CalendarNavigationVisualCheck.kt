@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, com.moriafly.salt.ui.UnstableSaltUiApi::class)

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.jethuit.spw.listenstats.ui.CalendarMonthNavigation
import com.jethuit.spw.listenstats.ui.drawWith
import com.moriafly.salt.ui.SaltTheme
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import java.time.YearMonth
import javax.swing.SwingUtilities

fun main(args: Array<String>) {
    val output = Path.of(args.single())
    Files.createDirectories(output)
    val years = (2025..2026).map { it.toString() to "${it}年" }
    val months = (1..12).map { it.toString() to "${it}月" }
    SwingUtilities.invokeAndWait {
        for ((year, month) in listOf(2025 to 1, 2026 to 9, 2026 to 10, 2026 to 12)) {
            // The 270dp date card has 12dp padding on each side: 246dp available.
            val scene = ImageComposeScene(308, 60, density = Density(1.25f)) {
                SaltTheme {
                    Box(
                        Modifier.fillMaxSize().drawWith { drawRect(Color(0xFFF5F6F8)) },
                        contentAlignment = Alignment.TopCenter
                    ) {
                        CalendarMonthNavigation(YearMonth.of(year, month), {}, years, months)
                    }
                }
            }
            try {
                scene.render().use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)!!.use {
                        Files.write(output.resolve("month-$year-$month.png"), it.bytes)
                    }
                }
            } finally { scene.close() }
        }
    }
    println("Rendered month navigation inside the actual 270dp card budget at 125%")
}
