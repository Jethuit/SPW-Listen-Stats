@file:OptIn(androidx.compose.runtime.InternalComposeApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    com.moriafly.salt.ui.UnstableSaltUiApi::class,
    com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier as UiModifier
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import com.jethuit.spw.listenstats.Prefs
import com.jethuit.spw.listenstats.ui.*
import com.moriafly.salt.ui.SaltTheme
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import com.xuncorp.spw.workshop.api.config.ConfigManager
import com.xuncorp.spw.workshop.api.hook.ComposableHook
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

private class EntryConfig : ConfigHelper {
    val saved = mutableMapOf<String, Any>()
    var reloadOk = true
    private var cached = saved.toMap()
    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: String, defaultValue: T): T =
        ((cached[key] ?: defaultValue) as T)
    override fun set(key: String, value: Any) { saved[key] = value }
    override fun save() = true
    override fun reload(): Boolean { if (reloadOk) cached = saved.toMap(); return reloadOk }
    override fun getConfigPath() = Path.of("build/tests/entry-placement/mock-config.json")
}

private class SidebarStyleCall(value: ProvidedValue<*>, content: Any) : UiHookCall() {
    override val thisObject: Any? = null
    override val arguments: List<Any> = listOf(value, content)
    override fun getArgument(name: String): Any = error("No named arguments needed")
}

fun main() = runBlocking {
    ImageIO.setUseCache(false)
    val config = EntryConfig()
    val manager = Proxy.newProxyInstance(ConfigManager::class.java.classLoader, arrayOf(ConfigManager::class.java)) {
        _, method, _ -> check(method.name == "getConfig"); config
    } as ConfigManager
    val apiManager = Proxy.newProxyInstance(WorkshopApi.Manager::class.java.classLoader,
        arrayOf(WorkshopApi.Manager::class.java)) { _, method, _ -> check(method.name == "createConfigManager"); manager }
    WorkshopApi.instance = Proxy.newProxyInstance(WorkshopApi::class.java.classLoader, arrayOf(WorkshopApi::class.java)) {
        _, method, _ -> check(method.name == "getManager"); apiManager
    } as WorkshopApi
    Prefs.initialize()
    check(Prefs.sidebarEntry() == false)
    config.saved["sidebarEntry"] = true
    check(Prefs.sidebarEntry() == true)
    Prefs.initialize()
    check(Prefs.sidebarEntry() == true)
    config.reloadOk = false
    check(Prefs.sidebarEntry() == null)
    config.reloadOk = true
    config.saved["sidebarEntry"] = false
    println("PASS default is top; saved sidebar preference reloads and survives initialization; failed read stays unknown")

    val source = AtomicReference<Boolean?>(false)
    val pending = ConcurrentLinkedQueue<() -> Unit>()
    suspend fun publish() {
        withTimeout(3000) { while (pending.isEmpty()) delay(5) }
        while (true) (pending.poll() ?: break).invoke()
    }
    StatsEntryPlacement.start(source::get, 10) { pending.add(it) }
    try {
        var installed = StatsEntryPlacement.installed(true, true)
        publish()
        check(StatsEntryPlacement.showTitlebar && !StatsEntryPlacement.showSidebar)
        source.set(true)
        withTimeout(3000) { while (!StatsEntryPlacement.showSidebar) { publish(); delay(10) } }
        check(!StatsEntryPlacement.showTitlebar)
        source.set(null); delay(30)
        while (true) (pending.poll() ?: break).invoke()
        check(StatsEntryPlacement.showSidebar && !StatsEntryPlacement.showTitlebar)
        source.set(false)
        withTimeout(3000) { while (!StatsEntryPlacement.showTitlebar) { publish(); delay(10) } }
        check(!StatsEntryPlacement.showSidebar)
        println("PASS live switching shows exactly one entry; failed configuration reads keep the last known position")

        installed.close()
        source.set(true)
        installed = StatsEntryPlacement.installed(false, true)
        publish(); delay(15); publish()
        check(StatsEntryPlacement.showTitlebar && !StatsEntryPlacement.showSidebar)
        installed.close()
        installed = StatsEntryPlacement.installed(true, false)
        check(StatsEntryPlacement.showSidebar && !StatsEntryPlacement.showTitlebar)
        installed.close()
        check(!StatsEntryPlacement.showSidebar && !StatsEntryPlacement.showTitlebar)
        println("PASS missing optional hooks retain the other entry; releasing hooks hides both")

        source.set(true); delay(30)
        val old = mutableListOf<() -> Unit>()
        while (true) old += pending.poll() ?: break
        StatsEntryPlacement.stop()
        source.set(false)
        StatsEntryPlacement.start(source::get, 10) { pending.add(it) }
        StatsEntryPlacement.installed(true, true)
        old.forEach { it() }
        publish()
        check(StatsEntryPlacement.showTitlebar && !StatsEntryPlacement.showSidebar)
        StatsEntryPlacement.stop()
        while (true) (pending.poll() ?: break).invoke()
        check(!StatsEntryPlacement.showSidebar && !StatsEntryPlacement.showTitlebar)
        println("PASS disable/re-enable rejects stale preference callbacks and releases the polling session")
    } finally { StatsEntryPlacement.stop() }

    val index = HostBytecodeIndex.read(Path.of("build/tools/spw-host.jar"))
    val bridge = HostUiBridge(HostUiDiscovery.discover(index), StatsSidebarEntry::class.java.classLoader)
    check(bridge.sidebar != null) { "Sidebar preparation failed: ${bridge.warnings}" }
    HostUiRuntime.activate(bridge).use {
        val targets = checkNotNull(bridge.targets.sidebar)
        val routeType = bridge.sidebar!!.second.declaringClass
        fun item(route: String): Any {
            val type = Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRoute\$$route")
            return routeType.constructors.single { it.parameterCount == 1 }.newInstance(type.getField("INSTANCE").get(null))
        }
        check(StatsSidebarEntry.isMusicLibrary(item("MusicLibrary")))
        check(!StatsSidebarEntry.isMusicLibrary(item("Settings")) && !StatsSidebarEntry.isMusicLibrary(null))
        var hook: ComposableHook? = null
        var selectionHook: ComposableHook? = null
        var closed = 0
        StatsSidebarEntry.register(addHook = { owner, name, caller, params, content ->
            check(owner == targets.entry.target.className && name == targets.entry.target.name)
            check(caller == targets.entry.callerClass && params == listOf(routeType.name))
            hook = content
            AutoCloseable { closed++ }
        }, replaceHook = { owner, name, caller, params, content ->
            check(owner == targets.style.target.className && name == targets.style.target.name)
            check(caller == targets.style.callerClass && params == targets.style.target.parameters.dropLast(2))
            selectionHook = content
            AutoCloseable { closed++ }
        }).use { check(hook != null && selectionHook != null) }
        check(closed == 2)
        println("PASS only MusicLibrary gets the appended entry; exact native hook signature and disposal are preserved")
        var rolledBack = 0
        val failure = runCatching { StatsSidebarEntry.register(
            addHook = { _, _, _, _, _ -> error("simulated entry failure") },
            replaceHook = { _, _, _, _, _ -> AutoCloseable { rolledBack++ } }) }.exceptionOrNull()
        check(failure is HookRegistrationFailure && failure.rollbackComplete && rolledBack == 1)
        println("PASS failed sidebar registration rolls back its style provider")

        val row = bridge.sidebar!!.first
        val owner = row.declaringClass
        val local = owner.declaredMethods.single {
            Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                ProvidableCompositionLocal::class.java.isAssignableFrom(it.returnType)
        }.invoke(null) as ProvidableCompositionLocal<Any>
        val style = owner.declaredMethods.single { Modifier.isStatic(it.modifiers) &&
            it.parameterTypes.toList() == listOf(Composer::class.java) && it.returnType != Void.TYPE }
        var clicked = 0
        lateinit var scene: ImageComposeScene
        SwingUtilities.invokeAndWait {
            scene = ImageComposeScene(260, 80) {
                SaltTheme { MaterialTheme {
                    CompositionLocalProvider(local provides style.invoke(null, currentComposer)) {
                        StatsSidebarButton { clicked++ }
                    }
                } }
            }
        }
        try {
            repeat(3) { SwingUtilities.invokeAndWait { scene.render().close() }; delay(15) }
            SwingUtilities.invokeAndWait {
                scene.sendPointerEvent(PointerEventType.Press, Offset(95f, 22f))
                scene.sendPointerEvent(PointerEventType.Release, Offset(95f, 22f))
            }
            delay(40)
            check(clicked == 1)
            println("PASS actual native sidebar layout renders and dispatches one click with isolated style/config")
        } finally { SwingUtilities.invokeAndWait { scene.close() } }

        val selectedRoute = mutableStateOf(-1)
        val bounds = mutableMapOf<Int, androidx.compose.ui.geometry.Rect>()
        val labels = listOf("Folder", "听歌统计", "Playlist", "Other extension")
        val mounts = IntArray(labels.size)
        val unmounts = IntArray(labels.size)
        val listStates = mutableSetOf<Any>()
        lateinit var selectionScene: ImageComposeScene
        StatsEntryPlacement.start({ true }, 10)
        StatsEntryPlacement.installed(true, true)
        withTimeout(3000) { while (!StatsEntryPlacement.showSidebar) delay(10) }
        StatsHostState.close()
        SwingUtilities.invokeAndWait {
            selectionScene = ImageComposeScene(260, 240) {
                SaltTheme { MaterialTheme {
                    val provided = local provides style.invoke(null, currentComposer)
                    val content: @Composable () -> Unit = {
                        val listState = remember { Any() }
                        SideEffect { listStates.add(listState) }
                        Column {
                            labels.forEachIndexed { i, label ->
                                DisposableEffect(i) {
                                    mounts[i]++
                                    onDispose { unmounts[i]++ }
                                }
                                if (i == 1) {
                                    Box(UiModifier.onGloballyPositioned { bounds[i] = it.boundsInRoot() }) {
                                        StatsSidebarButton { }
                                    }
                                } else {
                                    val icon: @Composable BoxScope.() -> Unit = {}
                                    row.invoke(null, label, selectedRoute.value == i,
                                        UiModifier.onGloballyPositioned { bounds[i] = it.boundsInRoot() },
                                        false, UiModifier, null, null, icon, currentComposer, 0, 104)
                                }
                            }
                        }
                    }
                    selectionHook!!.Content(SidebarStyleCall(provided, content))
                } }
            }
        }
        suspend fun snapshot(): BufferedImage {
            repeat(20) { SwingUtilities.invokeAndWait { selectionScene.render().close() }; delay(20) }
            lateinit var bytes: ByteArray
            SwingUtilities.invokeAndWait { selectionScene.render().use { image ->
                bytes = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes
            } }
            return ImageIO.read(ByteArrayInputStream(bytes))
        }
        fun difference(a: BufferedImage, b: BufferedImage, index: Int): Int {
            val rect = checkNotNull(bounds[index])
            return (rect.top.toInt() until rect.bottom.toInt()).sumOf { y ->
                (rect.left.toInt() until rect.right.toInt()).count { x -> a.getRGB(x, y) != b.getRGB(x, y) }
            }
        }
        try {
            val empty = snapshot()
            for (index in listOf(0, 2, 3)) {
                SwingUtilities.invokeAndWait { selectedRoute.value = index; StatsHostState.close() }
                val before = snapshot()
                check(difference(empty, before, index) > 20) { "Native row $index did not show its selection" }
                SwingUtilities.invokeAndWait { StatsHostState.open() }
                val opened = snapshot()
                check(difference(empty, opened, index) < 10) { "Native row $index kept its selection under statistics" }
                check(difference(empty, opened, 1) > 20) { "Statistics lost its original selected style" }
                SwingUtilities.invokeAndWait { StatsHostState.close() }
                val restored = snapshot()
                check(difference(empty, restored, index) > 20 && difference(empty, restored, 1) < 10)
            }
            println("PASS actual native route/playlist/extension highlights clear under statistics; statistics alone stays selected")
            println("PASS leaving statistics restores the previous native selection and original shared style")
            check(mounts.all { it == 1 } && unmounts.all { it == 0 } && listStates.size == 1) {
                "Sidebar remounted during bidirectional navigation: mounts=${mounts.toList()}, " +
                    "unmounts=${unmounts.toList()}, listStates=${listStates.size}"
            }
            println("PASS both navigation directions retain every sidebar row and remembered list state without disposal/reload")
        } finally {
            StatsEntryPlacement.stop(); StatsHostState.close()
            SwingUtilities.invokeAndWait { selectionScene.close() }
        }
    }
    println("10 entry-placement regression groups passed; no real library, playback, or installed configuration used")
}
