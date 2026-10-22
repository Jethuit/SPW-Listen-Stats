@file:OptIn(androidx.compose.runtime.InternalComposeApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package com.jethuit.spw.listenstats.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Constraints
import com.xuncorp.spw.workshop.api.hook.ComposableHook
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import java.lang.reflect.Method

/** Wrap the containing Salt Layer, outside other plugins' AppNavigation replacements. */
object StatsPageHook {
    const val TARGET_CLASS = "androidx.compose.ui.\u0559"
    const val TARGET_METHOD = "\u037f"

    fun register(version: String,
        addHook: (String, String, String, List<String>, ComposableHook) -> AutoCloseable
    ): AutoCloseable {
        HostPageLayer.prepare()
        val caller = HostUiTargets.forVersion(version).pageCaller
        return addHook(TARGET_CLASS, TARGET_METHOD, caller,
            listOf("androidx.compose.ui.Modifier", "boolean", "kotlin.jvm.functions.Function3"), object : ComposableHook() {
                @Composable
                override fun Content(call: UiHookCall) {
                    val modifier = call.arguments[0] as Modifier
                    val decoration = call.arguments[1] as Boolean
                    val original = requireNotNull(call.arguments[2])
                    val content: @Composable BoxScope.() -> Unit = {
                        val scope = this
                        StatsPageLayers(hostContent = {
                            HostPageLayer.renderContent(original, scope, currentComposer)
                        })
                    }
                    HostPageLayer.render(modifier, decoration, content, currentComposer)
                }
            })
    }
}

/** Preserve the host container's parameters; these supported call sites use no default mask. */
private object HostPageLayer {
    private val loader = StatsPageHook::class.java.classLoader
    private val method: Method by lazy {
        Class.forName(StatsPageHook.TARGET_CLASS, false, loader).getDeclaredMethod(
            StatsPageHook.TARGET_METHOD, Modifier::class.java, Boolean::class.javaPrimitiveType,
            Class.forName("kotlin.jvm.functions.Function3", false, loader),
            Composer::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        ).also { check(java.lang.reflect.Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE) }
    }

    // Compose lambdas implement multiple JVM function arities; a Kotlin arity cast rejects them.
    private val invokeContent: Method by lazy {
        Class.forName("kotlin.jvm.functions.Function3", false, loader)
            .getMethod("invoke", Any::class.java, Any::class.java, Any::class.java)
    }

    fun prepare() { method; invokeContent }

    fun render(modifier: Modifier, decoration: Boolean, content: Any, composer: Composer) {
        method.invoke(null, modifier, decoration, content, composer, 0, 0)
    }

    fun renderContent(content: Any, scope: BoxScope, composer: Composer) {
        invokeContent.invoke(content, scope, composer, 0)
    }
}

/** Keep the host composition alive; an isolated page is measured without placement or hit testing. */
private data class HostPlacement(val hidden: Boolean) : LayoutModifier {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val child = measurable.measure(constraints)
        return layout(child.width, child.height) {
            if (!hidden) child.placeRelative(0, 0)
        }
    }
}

@Composable
fun RetainedHostContent(
    hidden: Boolean,
    drawWhileHidden: Boolean = false,
    opacity: () -> Float = { 1f },
    content: @Composable () -> Unit
) {
    val focus = LocalFocusManager.current
    LaunchedEffect(hidden) { if (hidden) focus.clearFocus(force = true) }
    Box(Modifier.fillMaxSize().then(HostPlacement(hidden && !drawWhileHidden))
        .onPreviewKeyEvent { hidden }
        .pointerInput(hidden) {
            if (hidden) awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
        .graphicsLayer { alpha = opacity().coerceIn(0f, 1f) }
    ) { content() }
}

/** Covers the complete host page, including tools that replace AppNavigation. */
@Composable
fun HostMaterialLayer(content: @Composable () -> Unit) {
    DisposableEffect(Unit) {
        StatsHostState.materialBridgeAvailable = true
        onDispose { StatsHostState.materialBridgeAvailable = false }
    }
    val visible = StatsHostState.visible
    val fade = LocalStatsHostFade.current
    val isolated = StatsHostState.closing && !StatsHostState.revealHostOnExit
    RetainedHostContent(
        hidden = visible,
        drawWhileHidden = !isolated && fade != null && fade.value > 0f,
        opacity = { if (visible) fade?.value ?: 0f else 1f },
        content = content
    )
}

@Composable
fun StatsPageLayers(hostContent: @Composable () -> Unit, statistics: @Composable () -> Unit = { StatsOverlay() }) {
    // One fade per presented page, shared with its spring rather than a second timer.
    val fade = if (StatsHostState.visible) remember { Animatable(1f) } else null
    CompositionLocalProvider(LocalStatsHostFade provides fade) {
        HostMaterialLayer(hostContent)
        statistics()
    }
}
