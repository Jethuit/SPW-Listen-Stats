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

/** Model the competing outer replace slot, using the real registered inner callback and layout. */
private fun checkNestedMaterialHook(output: Path) {
    for (lyricsEnabled in listOf(false, true)) {
        var created = 0
        var disposed = 0
        var clicks = 0
        var mounted by mutableStateOf(true)
        lateinit var callback: ComposableHook
        var removed = false
        val handle = StatsMaterialHook.register { target, method, caller, parameters, content ->
            check(target != "com.xuncorp.voxzen.ui.navigation.AppNavigationKt") {
                "Material must not compete with Lyrics for the outer navigation replacement"
            }
            check(caller == "com.xuncorp.voxzen.ui.navigation.AppNavigationKt")
            val types = parameters.map { Class.forName(it) } + listOf(
                androidx.compose.runtime.Composer::class.java,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
            check(Class.forName(target).getDeclaredMethod(method, *types.toTypedArray()).returnType == Void.TYPE)
            callback = content
            AutoCloseable { removed = true }
        }
        val nativeContent: @Composable SharedTransitionScope.() -> Unit = {
            remember { created++; Any() }
            DisposableEffect(Unit) { onDispose { disposed++ } }
            Box(Modifier.fillMaxSize().onPointerEvent(PointerEventType.Release) { clicks++ }
                .drawWith { drawRect(Color(0xFFBB2244)) })
        }
        val call = object : UiHookCall() {
            override val thisObject: Any? = null
            override val arguments = listOf(Modifier.fillMaxSize(), nativeContent)
            override fun getArgument(name: String): Any? = error("Only positional layout arguments are used")
        }
        lateinit var scene: ImageComposeScene
        SwingUtilities.invokeAndWait {
            StatsHostState.close()
            scene = ImageComposeScene(400, 300, density = Density(1f)) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize().drawWith { drawRect(Color(0xFF276B56)) }) {
                        if (mounted) {
                            // Lyrics wins the outer replace slot at priority 80. Its inactive
                            // surface invokes the original AppNavigation, which reaches this hook.
                            if (lyricsEnabled) Box(Modifier.fillMaxSize()) { callback.Content(call) }
                            else callback.Content(call)
                        }
                        if (StatsHostState.visible) {
                            val page = materialStatsColors(LightStatsColors, "WindowsMica",
                                StatsHostState.materialBridgeAvailable)
                            Box(Modifier.fillMaxSize().drawWith { drawRect(page.pageBackground) })
                        }
                    }
                }
            }
        }
        fun render(name: String): Int {
            var pixel = 0
            repeat(5) {
                SwingUtilities.invokeAndWait {
                    scene.render().use { image ->
                        image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                            val bytes = data.bytes
                            pixel = ImageIO.read(java.io.ByteArrayInputStream(bytes)).getRGB(200, 150) and 0xffffff
                            Files.write(output.resolve("compat-$lyricsEnabled-$name.png"), bytes)
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
            check(render("before") == 0xBB2244 && StatsHostState.materialBridgeAvailable)
            click()
            SwingUtilities.invokeAndWait { StatsHostState.open() }
            check(render("statistics") == 0x276B56) { "Material fallback covered the window backdrop" }
            click()
            check(clicks == 1 && created == 1 && disposed == 0) { "Underlying page lost its state or received a hidden click" }
            SwingUtilities.invokeAndWait { StatsHostState.close() }
            check(render("restored") == 0xBB2244)
            click()
            check(clicks == 2 && created == 1 && disposed == 0)
            SwingUtilities.invokeAndWait { mounted = false }
            render("removed")
            check(!StatsHostState.materialBridgeAvailable && disposed == 1)
            println("PASS material hook with competing outer replacement=$lyricsEnabled: callback, transparent background, retained state, click isolation and disposal")
        } finally {
            SwingUtilities.invokeAndWait { StatsHostState.close(); scene.close() }
            handle.close()
            check(removed)
        }
    }
}
