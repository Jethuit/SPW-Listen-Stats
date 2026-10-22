@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.animation.ExperimentalSharedTransitionApi::class,
    com.moriafly.salt.ui.UnstableSaltUiApi::class,
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
import com.jethuit.spw.listenstats.data.StatsData
import com.moriafly.salt.ui.SaltTheme
import androidx.compose.ui.input.pointer.PointerEventPass
import java.time.LocalDate
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
    checkForwardCover(output)
    checkHeatmapExitInput()
}

/** Raw pointer handlers must respect both page exit state and an already consumed press. */
private fun checkHeatmapExitInput() {
    for (weekly in listOf(false, true)) {
        var exiting by mutableStateOf(false)
        var blocked by mutableStateOf(false)
        var selected = 0
        var completed = 0
        lateinit var scene: ImageComposeScene
        SwingUtilities.invokeAndWait {
            scene = ImageComposeScene(1080, 400, density = Density(1f)) {
                SaltTheme { MaterialTheme {
                    CompositionLocalProvider(LocalStatsColors provides LightStatsColors,
                        LocalStatsAnimationsEnabled provides true) {
                        StatsPageEnter(exiting = exiting, onExited = { completed++ }) {
                            Column(Modifier.fillMaxSize().onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
                                if (blocked) event.changes.forEach { it.consume() }
                            }) {
                                YearHeatmap(StatsData.empty(), 2026, weekly, LocalDate.of(2026, 10, 22), null,
                                    { selected++ }, { _, _ -> selected++ })
                            }
                        }
                    }
                } }
            }
        }
        var time = 0L
        fun frame() {
            time += 16_666_667L
            SwingUtilities.invokeAndWait { scene.render(time).close() }
            Thread.sleep(3)
        }
        fun click() {
            SwingUtilities.invokeAndWait {
                scene.sendPointerEvent(PointerEventType.Press, Offset(135f, 55f))
                scene.sendPointerEvent(PointerEventType.Release, Offset(135f, 55f))
            }
        }
        try {
            repeat(60) { frame() }
            click(); check(selected == 1) { "Heatmap was not clickable before exit (weekly=$weekly)" }
            SwingUtilities.invokeAndWait { blocked = true }
            repeat(3) { frame() }
            click(); check(selected == 1) { "Heatmap ignored a consumed press (weekly=$weekly)" }
            SwingUtilities.invokeAndWait { blocked = false; exiting = true }
            repeat(3) { frame() }
            click()
            check(selected == 1 && completed == 0) { "Exiting heatmap changed selection (weekly=$weekly)" }
            SwingUtilities.invokeAndWait { exiting = false }
            repeat(60) { frame() }
            click(); check(selected == 2) { "Canceled exit did not restore heatmap interaction (weekly=$weekly)" }
        } finally { SwingUtilities.invokeAndWait { scene.close() } }
    }
    println("PASS daily/weekly heatmaps ignore consumed and exiting presses, and recover after a reversed exit")
}

/** New content sits above the stationary outgoing page, rather than waiting for a pop. */
private fun checkForwardCover(output: Path) {
    var target by mutableStateOf(false)
    lateinit var scene: ImageComposeScene
    lateinit var presenter: AutoCloseable
    SwingUtilities.invokeAndWait {
        StatsHostState.close()
        presenter = StatsHostState.attachPresenter()
        scene = ImageComposeScene(400, 300, density = Density(1f)) {
            Box(Modifier.fillMaxSize()) {
                StatsPageLayers(hostContent = {
                    Box(Modifier.fillMaxSize().drawWith { drawRect(if (target) Color.Blue else Color.Green) })
                }, statistics = {
                    if (StatsHostState.visible) StatsPageEnter(StatsHostState.closing,
                        exitMotion = StatsHostState.exitMotion,
                        onExited = { StatsHostState.finishClose(StatsHostState.closeTicket) }) {
                        Box(Modifier.fillMaxSize().drawWith {
                            drawRect(Color.Red)
                            drawRect(Color.White, topLeft = Offset(0f, 30f), size = androidx.compose.ui.geometry.Size(400f, 20f))
                        })
                    }
                })
            }
        }
    }
    var time = 0L
    fun frame(): java.awt.image.BufferedImage {
        time += 16_666_667L
        var bytes = byteArrayOf()
        SwingUtilities.invokeAndWait {
            scene.render(time).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { bytes = it.bytes }
            }
        }
        Thread.sleep(3)
        return ImageIO.read(java.io.ByteArrayInputStream(bytes))
    }
    try {
        frame()
        SwingUtilities.invokeAndWait { StatsHostState.open() }
        repeat(60) { frame() }
        SwingUtilities.invokeAndWait { target = true; StatsHostState.requestNavigation(nativeTransition = false) }
        frame()
        val covered = frame()
        ImageIO.write(covered, "png", output.resolve("forward-cover.png").toFile())
        val stripe = covered.getRGB(200, 35)
        check((stripe and 255) > 80 && ((stripe shr 8) and 255) > 80) {
            "Forward switch moved the outgoing stripe downward like a return"
        }
        check(covered.getRGB(200, 220) and 0xffffff == 0x0000ff) {
            "Incoming page did not immediately cover outgoing content"
        }
        repeat(60) { frame() }
        check(!StatsHostState.visible && frame().getRGB(200, 35) and 0xffffff == 0x0000ff)
        println("PASS forward cover: outgoing marker remains in place, incoming content is above it, no sequential return")
    } finally { SwingUtilities.invokeAndWait { presenter.close(); StatsHostState.close(); scene.close() } }
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
                        Box(Modifier.fillMaxSize().onPointerEvent(PointerEventType.Release) { event ->
                            if (event.changes.none { it.isConsumed }) hostClicks++
                        }
                            .drawWith { if (!lyricsActive) drawRect(Color(0xFFBB2244)) })
                        if (lyricsActive) Box(Modifier.fillMaxSize()
                            .onPointerEvent(PointerEventType.Release) { event ->
                                if (event.changes.none { it.isConsumed }) lyricsClicks++
                            }
                            .drawWith { drawRect(Color(0xFF2244BB)) })
                    }
                }, statistics = {
                    if (StatsHostState.visible) {
                        // Drive the shared native spring fade; complete exits explicitly below.
                        StatsPageEnter(StatsHostState.closing, exitMotion = StatsHostState.exitMotion) {
                            Box(Modifier.fillMaxSize().drawWith { drawRect(Color.Transparent) })
                        }
                    }
                })
            }
        }
    }
    var time = 0L
    fun nextFrame(): org.jetbrains.skia.Image {
        time += 16_666_667L
        return scene.render(time)
    }
    fun render(name: String): Int {
        var pixel = 0
        repeat(3) {
            SwingUtilities.invokeAndWait {
                nextFrame().use { image ->
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
    fun settleMotion() {
        repeat(45) {
            SwingUtilities.invokeAndWait { nextFrame().close() }
            Thread.sleep(15)
        }
    }
    try {
        check(render("host") == 0xBB2244)
        click(); check(hostClicks == 1)
        SwingUtilities.invokeAndWait { StatsHostState.open() }
        check(render("enter-host-fade") != 0x276B56) { "Original host disappeared before its entrance fade" }
        click(); check(hostClicks == 1) { "Fading host accepted input" }
        settleMotion()
        check(render("entered") == 0x276B56)
        SwingUtilities.invokeAndWait { StatsHostState.requestClose() }
        check(render("return-host-fade") != 0x276B56) { "Ordinary return did not fade in the retained host" }
        click(); check(hostClicks == 1) { "Restoring host accepted input before statistics exit completed" }
        SwingUtilities.invokeAndWait { StatsHostState.finishClose(StatsHostState.closeTicket) }
        check(render("returned-host") == 0xBB2244)
        repeat(6) { index ->
            SwingUtilities.invokeAndWait { StatsHostState.open() }
            settleMotion()
            check(render("stats-$index") == 0x276B56)
            SwingUtilities.invokeAndWait { StatsHostState.requestNavigation(nativeTransition = false); lyricsActive = true }
            val ticket = StatsHostState.closeTicket
            check(StatsHostState.visible && StatsHostState.closing)
            check(render("exiting-$index") == 0x2244BB) { "Incoming tool must cover outgoing statistics" }
            click(); check(lyricsClicks == 0 && hostClicks == 1)
            SwingUtilities.invokeAndWait { lyricsActive = false; StatsHostState.open(); StatsHostState.finishClose(ticket) }
            settleMotion()
            check(render("reopened-$index") == 0x276B56 && StatsHostState.visible)
        }
        SwingUtilities.invokeAndWait { StatsHostState.requestNavigation(nativeTransition = false); lyricsActive = true }
        check(render("last-exit") == 0x2244BB)
        SwingUtilities.invokeAndWait { StatsHostState.finishClose(StatsHostState.closeTicket) }
        check(render("lyrics") == 0x2244BB)
        click(); check(lyricsClicks == 1)
        check(created == 1 && disposed == 0)
        println("PASS forward page covers statistics, blocks input during motion, retains host state and tolerates six reversals")
    } finally {
        SwingUtilities.invokeAndWait { presenter.close(); StatsHostState.close(); scene.close() }
    }
    check(disposed == 1 && !StatsHostState.materialBridgeAvailable)
}
