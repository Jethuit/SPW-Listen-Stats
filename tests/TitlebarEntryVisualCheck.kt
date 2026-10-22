@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    com.moriafly.salt.ui.UnstableSaltUiApi::class,
    com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class
)

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.jethuit.spw.listenstats.ui.*
import com.moriafly.salt.ui.SaltTheme
import com.xuncorp.spw.workshop.api.hook.ComposableHook
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

private val call = object : UiHookCall() {
    override val thisObject: Any? = null
    override val arguments = emptyList<Any?>()
    override fun getArgument(name: String): Any? = null
}

private fun descendants(node: SemanticsNode): List<SemanticsNode> =
    listOf(node) + node.children.flatMap(::descendants)

private fun buttons(scene: ImageComposeScene) = scene.semanticsOwners.flatMap {
    descendants(it.rootSemanticsNode)
}.filter { node ->
    node.config.contains(SemanticsProperties.ContentDescription) &&
        "听歌统计" in node.config[SemanticsProperties.ContentDescription] &&
        node.config.contains(SemanticsActions.OnClick)
}

fun main(args: Array<String>) {
    val output = Path.of(args.single())
    Files.createDirectories(output)
    SwingUtilities.invokeAndWait {
        for ((width, scale, dark) in listOf(
            Triple(360, 1f, false), Triple(780, 1.25f, false), Triple(360, 1.5f, true)
        )) {
            val navigation = TestNavigationRegistration()
            var active by mutableStateOf<ComposableHook?>(null)
            fun register() = StatsTitlebarEntry.register { target, method, caller, parameters, content ->
                check(target == "androidx.compose.ui.\u04f4" && method == "\u037f")
                check(caller == "androidx.compose.ui.\u0205" && parameters.isEmpty())
                check(active == null)
                active = content
                AutoCloseable { active = null }
            }
            var handle = register()
            val scene = ImageComposeScene((width * scale).roundToInt(), (48 * scale).roundToInt(), Density(scale)) {
                SaltTheme {
                    Row(
                        Modifier.fillMaxSize().drawWith {
                            drawRect(if (dark) Color(0xFF202124) else Color(0xFFF8F8F8))
                        }.padding(end = 12.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        active?.Content(call)
                        // Layout markers only; this fixture does not copy the user's profile.
                        Box(Modifier.size(32.dp).semantics { contentDescription = "quick-marker" })
                        Box(Modifier.width(64.dp).height(32.dp))
                    }
                }
            }
            try {
                scene.render().use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)!!.use {
                        Files.write(output.resolve("titlebar-$width-$scale.png"), it.bytes)
                    }
                }
                val entry = buttons(scene).single()
                val bounds = entry.boundsInRoot
                check(bounds.width > 20 * scale && bounds.height >= 20 * scale)
                val quick = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }.single {
                    it.config.contains(SemanticsProperties.ContentDescription) &&
                        "quick-marker" in it.config[SemanticsProperties.ContentDescription]
                }.boundsInRoot
                check(bounds.right <= quick.left && bounds.left >= 0)
                StatsHostState.close()
                scene.sendPointerEvent(PointerEventType.Press, bounds.center, button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, bounds.center, button = PointerButton.Primary)
                scene.render().close()
                check(StatsHostState.visible) { "Pointer click did not open statistics" }
                check(StatsHostState.consumeBack() && !StatsHostState.visible)
                handle.close()
                scene.render().close()
                check(buttons(scene).isEmpty()) { "Entry remains after unhook" }
                handle = register()
                scene.render().close()
                check(buttons(scene).size == 1) { "Duplicate entry after re-enable" }
                println("PASS native button at ${scale}x / ${width}dp: order, pointer click, back, remove and re-enable; bounds=$bounds")
            } finally {
                handle.close()
                scene.close()
                StatsHostState.close()
                navigation.close()
            }
        }
        TestNavigationRegistration(bind = false).use {
            val native = TestNativeNavigator()
            @Suppress("UNCHECKED_CAST")
            val local = Class.forName("com.xuncorp.voxzen.ui.navigation.AppNavigationKt")
                .getDeclaredMethod("getLocalNavigator").invoke(null) as ProvidableCompositionLocal<Any?>
            check(!StatsNavigationHooks.navigatorReady)
            val capture = ImageComposeScene(100, 100, Density(1f)) {
                CompositionLocalProvider(local provides native.navigator) { CaptureHostNavigator() }
            }
            repeat(3) { capture.render().close() }
            check(StatsNavigationHooks.navigatorReady) { "Real host navigation context was not captured" }
            capture.close()
            check(!StatsNavigationHooks.navigatorReady) { "Disposed host retained its navigation context" }
            println("PASS actual host navigation CompositionLocal capture and disposal")
        }
    }
}
