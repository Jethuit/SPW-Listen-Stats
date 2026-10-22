@file:OptIn(
    androidx.compose.runtime.InternalComposeApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class
)

package com.jethuit.spw.listenstats.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xuncorp.spw.workshop.api.hook.ComposableHook
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import com.jethuit.spw.listenstats.PluginState
import java.lang.reflect.Method

/** Version-bound call site, immediately before the Quick button. */
object StatsTitlebarEntry {
    const val TARGET_METHOD = "\u037f"
    const val ICON_RESOURCE = "/com/jethuit/spw/listenstats/icons/stats-entry.svg"

    fun register(addHook: (String, String, String, List<String>, ComposableHook) -> AutoCloseable): AutoCloseable {
        // Resolve before installing; incompatibility must leave the main statistics hook running.
        HostCaptionButton.prepare()
        val targets = HostUiTargets.forVersion(PluginState.spwVersion)
        return addHook(targets.debugCaption, TARGET_METHOD, targets.titlebarCaller, emptyList(), object : ComposableHook() {
            @Composable
            override fun Content(call: UiHookCall) {
                StatsTitlebarButton()
            }
        })
    }
}

/** Uses SPW's own caption control, including its width, height, indication and tooltip. */
@Composable
fun StatsTitlebarButton(onClick: () -> Unit = StatsNavigationHooks::openStats) {
    val density = LocalDensity.current
    val painter = remember(density) {
        HostCaptionButton.iconBytes.inputStream().use { loadSvgPainter(it, density) }
    }
    val icon: @Composable BoxScope.() -> Unit = {
        Box(Modifier.align(Alignment.Center).size(20.dp).drawWith {
            with(painter) { draw(size) }
        })
    }
    // Only this bridge knows the obfuscated JVM signature. Composer is the current host
    // composition, so no separate window or copied caption Row is introduced.
    HostCaptionButton.method.invoke(
        null, onClick, "听歌统计",
        Modifier.semantics { contentDescription = "听歌统计" },
        true, null, 0f, icon, currentComposer, 0, 32 // Default host caption width.
    )
}

private object HostCaptionButton {
    val iconBytes: ByteArray by lazy {
        requireNotNull(StatsTitlebarEntry::class.java.getResourceAsStream(StatsTitlebarEntry.ICON_RESOURCE)) {
            "Missing titlebar statistics icon"
        }.use { it.readBytes() }
    }
    val method: Method by lazy {
        val loader = StatsTitlebarEntry::class.java.classLoader
        Class.forName("com.xuncorp.voxzen.ui.component.\u0529", false, loader).getDeclaredMethod(
            "\u037f",
            kotlin.jvm.functions.Function0::class.java,
            String::class.java,
            Modifier::class.java,
            Boolean::class.javaPrimitiveType,
            String::class.java,
            Float::class.javaPrimitiveType,
            kotlin.jvm.functions.Function3::class.java,
            Composer::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        ).also { check(java.lang.reflect.Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE) }
    }

    fun prepare() {
        check(iconBytes.isNotEmpty())
        method // Fail early on unsupported host signatures.
    }
}
