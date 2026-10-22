@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.animation.ExperimentalSharedTransitionApi::class,
    com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

import androidx.compose.foundation.layout.*
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.jethuit.spw.listenstats.ui.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import com.xuncorp.spw.workshop.api.hook.ComposableHook
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import javax.imageio.ImageIO

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
    checkNestedMaterialHook(output)
}

/** Exercise the actual native container callback with its original business arguments. */
private fun checkContainingLayer(output: Path) {
    lateinit var callback: ComposableHook
    val handle = StatsPageHook.register("1.19.1") { target, method, caller, parameters, content ->
        check(target != "com.xuncorp.voxzen.ui.navigation.AppNavigationKt")
        check(caller == HostUiTargets.forVersion("1.19.1").pageCaller)
        val types = parameters.map { if (it == "boolean") Boolean::class.javaPrimitiveType!! else Class.forName(it) } +
            listOf(Composer::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
        check(Class.forName(target).getDeclaredMethod(method, *types.toTypedArray()).returnType == Void.TYPE)
        callback = content
        AutoCloseable { }
    }
    val content: @Composable BoxScope.() -> Unit = {
        Box(Modifier.fillMaxSize().drawWith { drawRect(Color(0xFFBB2244)) })
    }
    val call = object : UiHookCall() {
        override val thisObject: Any? = null
        override val arguments = listOf(Modifier.fillMaxSize().padding(20.dp), false, content)
        override fun getArgument(name: String): Any? = error("Only positional arguments are used")
    }
    lateinit var scene: ImageComposeScene
    SwingUtilities.invokeAndWait {
        StatsHostState.close()
        scene = ImageComposeScene(400, 300, density = Density(1f)) {
            Box(Modifier.fillMaxSize().drawWith { drawRect(Color(0xFF276B56)) }) { callback.Content(call) }
        }
    }
    try {
        repeat(3) { SwingUtilities.invokeAndWait { scene.render().close() }; Thread.sleep(15) }
        SwingUtilities.invokeAndWait {
            scene.render().use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                    val bitmap = ImageIO.read(java.io.ByteArrayInputStream(data.bytes))
                    check(bitmap.getRGB(5, 5) and 0xffffff == 0x276B56)
                    check(bitmap.getRGB(200, 150) and 0xffffff == 0xBB2244)
                    Files.write(output.resolve("containing-layer.png"), data.bytes)
                }
            }
        }
        check(StatsHostState.materialBridgeAvailable)
        println("PASS actual containing-Layer callback: native reflection, business arguments, content lambda and composition")
    } finally { SwingUtilities.invokeAndWait { scene.close() }; handle.close() }
}

/** The target plugin opens before statistics exits, like Lyrics' real synchronous entry. */
private fun checkNestedMaterialHook(output: Path) {
    checkContainingLayer(output)
    var lyricsActive by mutableStateOf(false)
    var hostClicks = 0
    var lyricsClicks = 0
    var created = 0
    var disposed = 0
    lateinit var scene: ImageComposeScene
    lateinit var presenter: AutoCloseable
    SwingUtilities.invokeAndWait {
        StatsHostState.close()
        presenter = StatsHostState.attachPresenter()
        StatsHostState.setAnimationsEnabled(true)
        scene = ImageComposeScene(400, 300, density = Density(1f)) {
            Box(Modifier.fillMaxSize().drawWith { drawRect(Color(0xFF276B56)) }) {
                StatsPageLayers(hostContent = {
                    remember { created++; Any() }
                    DisposableEffect(Unit) { onDispose { disposed++ } }
                    Box(Modifier.fillMaxSize()) {
                        // Other plugin replaces AppNavigation at the original inner call.
                        Box(Modifier.fillMaxSize().onPointerEvent(PointerEventType.Release) { hostClicks++ }
                            .drawWith { if (!lyricsActive) drawRect(Color(0xFFBB2244)) })
                        if (lyricsActive) Box(Modifier.fillMaxSize()
                            .onPointerEvent(PointerEventType.Release) { lyricsClicks++ }
                            .drawWith { drawRect(Color(0xFF2244BB)) })
                    }
                }, statistics = {
                    if (StatsHostState.visible) {
                        // Transparent statistics/animation must reveal the window, never the target tool.
                        Box(Modifier.fillMaxSize().drawWith { drawRect(Color.Transparent) })
                    }
                })
            }
        }
    }
    fun render(name: String): Int {
        var pixel = 0
        repeat(3) {
            SwingUtilities.invokeAndWait {
                scene.render().use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                        pixel = ImageIO.read(java.io.ByteArrayInputStream(data.bytes)).getRGB(200, 150) and 0xffffff
                        Files.write(output.resolve("switch-$name.png"), data.bytes)
                    }
                }
            }
            Thread.sleep(15)
        }
        return pixel
    }
    fun click() = SwingUtilities.invokeAndWait {
        scene.sendPointerEvent(PointerEventType.Press, Offset(200f, 150f))
        scene.sendPointerEvent(PointerEventType.Release, Offset(200f, 150f))
    }
    try {
        check(render("host") == 0xBB2244)
        click(); check(hostClicks == 1)
        repeat(6) { index ->
            SwingUtilities.invokeAndWait { StatsHostState.open() }
            check(render("stats-$index") == 0x276B56)
            SwingUtilities.invokeAndWait { StatsHostState.requestClose(); lyricsActive = true }
            val ticket = StatsHostState.closeTicket
            check(StatsHostState.visible && StatsHostState.closing)
            check(render("exiting-$index") == 0x276B56) { "Target plugin leaked through outgoing statistics" }
            click(); check(lyricsClicks == 0 && hostClicks == 1)
            SwingUtilities.invokeAndWait { lyricsActive = false; StatsHostState.open(); StatsHostState.finishClose(ticket) }
            check(render("reopened-$index") == 0x276B56 && StatsHostState.visible)
        }
        SwingUtilities.invokeAndWait { StatsHostState.requestClose(); lyricsActive = true }
        check(render("last-exit") == 0x276B56)
        SwingUtilities.invokeAndWait { StatsHostState.finishClose(StatsHostState.closeTicket) }
        check(render("lyrics") == 0x2244BB)
        click(); check(lyricsClicks == 1)
        check(created == 1 && disposed == 0)
        println("PASS full-page isolation: six rapid reversals, hidden target pixels/clicks, transparent backdrop, target reveal and retained host state")
    } finally {
        SwingUtilities.invokeAndWait { presenter.close(); StatsHostState.close(); scene.close() }
    }
    check(disposed == 1 && !StatsHostState.materialBridgeAvailable)
}
