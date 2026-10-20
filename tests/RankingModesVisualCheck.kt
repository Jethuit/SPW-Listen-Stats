@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, com.moriafly.salt.ui.UnstableSaltUiApi::class)

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.jethuit.spw.listenstats.data.*
import com.jethuit.spw.listenstats.ui.*
import com.moriafly.salt.ui.SaltTheme
import com.xuncorp.spw.workshop.api.WorkshopApi
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/** Synthetic tags/counts and solid-color embedded covers; the real player API is never used. */
fun main(args: Array<String>) {
    val output = Path.of(args.first())
    val acrylic = args.getOrNull(1) == "acrylic"
    Files.createDirectories(output)
    val tracks = listOf(
        TrackMeta("demo1", "歌曲一", "歌手甲/歌手乙", "专辑一", "专辑艺术家", 240_000),
        TrackMeta("demo2", "歌曲二", "歌手甲", "专辑一", "专辑艺术家", 240_000),
        TrackMeta("demo3", "歌曲三", "歌手乙", "专辑二", "歌手乙", 240_000)
    )
    val data = StatsData.empty().copy(tracks = tracks.associateBy { it.id },
        playCounts = mapOf("demo1" to 5, "demo2" to 3, "demo3" to 2))
    val cover = BufferedImage(96, 96, BufferedImage.TYPE_INT_RGB)
    cover.createGraphics().also { g ->
        g.color = java.awt.Color(30, 90, 180); g.fillRect(0, 0, 96, 96); g.dispose()
    }
    val bytes = ByteArrayOutputStream().also { ImageIO.write(cover, "png", it) }.toByteArray()
    val library = Proxy.newProxyInstance(WorkshopApi.Library::class.java.classLoader,
        arrayOf(WorkshopApi.Library::class.java)) { _, method, _ ->
        check(method.name == "getCoverById")
        CompletableFuture.completedFuture(bytes)
    }
    WorkshopApi.instance = Proxy.newProxyInstance(WorkshopApi::class.java.classLoader,
        arrayOf(WorkshopApi::class.java)) { _, method, _ ->
        check(method.name == "getLibrary")
        library
    } as WorkshopApi
    CoverStore.clear()
    runBlocking { for (track in tracks) check(CoverStore.load(track.id, 60) != null) }
    try {
        val palettes = if (acrylic) listOf("acrylic" to materialStatsColors(LightStatsColors,
            ThemeMode.System, "WindowsAcrylic", true)) else listOf("light" to LightStatsColors, "dark" to DarkStatsColors)
        for ((theme, colors) in palettes) {
            for (width in if (acrylic) listOf(760) else listOf(280, 760)) for (mode in RankingMode.entries) {
                val rows = Rankings.ranked(data, mode)
                lateinit var scene: ImageComposeScene
                SwingUtilities.invokeAndWait {
                    scene = ImageComposeScene(width * 5 / 4, 570, density = Density(1.25f)) {
                        SaltTheme {
                            MaterialTheme(colorScheme = if (colors.isDark) darkColorScheme() else lightColorScheme()) {
                                CompositionLocalProvider(LocalStatsColors provides colors) {
                                    Box(Modifier.fillMaxSize().drawWith {
                                        drawRect(if (acrylic) Color(0xFFDDDDDD) else colors.pageBackground)
                                    }) {
                                        RankingPage(rows, mode, {}, "", {}, 1, {})
                                    }
                                }
                            }
                        }
                    }
                }
                try {
                    repeat(3) {
                        SwingUtilities.invokeAndWait { scene.render().close() }
                        Thread.sleep(20)
                    }
                    SwingUtilities.invokeAndWait {
                        scene.render().use { image ->
                            image.encodeToData(EncodedImageFormat.PNG)!!.use {
                                Files.write(output.resolve("$theme-${mode.storage}-$width.png"), it.bytes)
                            }
                        }
                    }
                    if (acrylic) {
                        SwingUtilities.invokeAndWait {
                            scene.sendPointerEvent(PointerEventType.Move, Offset(10f, 160f))
                        }
                        repeat(3) {
                            SwingUtilities.invokeAndWait { scene.render().close() }
                            Thread.sleep(20)
                        }
                        SwingUtilities.invokeAndWait {
                            scene.render().use { image ->
                                image.encodeToData(EncodedImageFormat.PNG)!!.use {
                                    Files.write(output.resolve("$theme-${mode.storage}-$width-hover.png"), it.bytes)
                                }
                            }
                        }
                    }
                } finally { SwingUtilities.invokeAndWait { scene.close() } }
            }
        }
    } finally { CoverStore.clear() }
    println(if (acrylic) "Rendered three actual Acrylic ranking modes before and after pointer hover"
        else "Rendered 12 real ranking layouts with synthetic 60px covers; three modes, two widths and two themes")
}
