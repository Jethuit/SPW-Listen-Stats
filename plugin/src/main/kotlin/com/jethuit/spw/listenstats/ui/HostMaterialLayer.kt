@file:OptIn(androidx.compose.runtime.InternalComposeApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package com.jethuit.spw.listenstats.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import com.xuncorp.spw.workshop.api.hook.ComposableHook
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import java.lang.reflect.Method

/** Wrap the containing Salt Layer, outside other plugins' AppNavigation replacements. */
object StatsPageHook {
    val TARGET_CLASS: String get() = HostUiRuntime.require().targets.layer.target.className
    val TARGET_METHOD: String get() = HostUiRuntime.require().targets.layer.target.name

    fun register(bridge: HostUiBridge = HostUiRuntime.require(),
        addHook: (String, String, String, List<String>, ComposableHook) -> AutoCloseable
    ): AutoCloseable {
        val layer = HostPageLayer(bridge.layer)
        val site = bridge.targets.layer
        return addHook(site.target.className, site.target.name, site.callerClass,
            listOf("androidx.compose.ui.Modifier", "boolean", "kotlin.jvm.functions.Function3"), object : ComposableHook() {
                @Composable
                override fun Content(call: UiHookCall) {
                    val modifier = call.arguments[0] as Modifier
                    val decoration = call.arguments[1] as Boolean
                    val original = requireNotNull(call.arguments[2])
                    val content: @Composable BoxScope.() -> Unit = {
                        val scope = this
                        StatsPageLayers(hostContent = {
                            layer.renderContent(original, scope, currentComposer)
                        })
                    }
                    layer.render(modifier, decoration, content, currentComposer)
                }
            })
    }
}

/** Preserve the host container's parameters; these supported call sites use no default mask. */
private class HostPageLayer(private val method: Method) {
    private val loader = StatsPageHook::class.java.classLoader

    // Compose lambdas implement multiple JVM function arities; a Kotlin arity cast rejects them.
    private val invokeContent: Method by lazy {
        Class.forName("kotlin.jvm.functions.Function3", false, loader)
            .getMethod("invoke", Any::class.java, Any::class.java, Any::class.java)
    }

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
    aboveStatistics: Boolean = false,
    translationY: () -> Float = { 0f },
    content: @Composable () -> Unit
) {
    val focus = LocalFocusManager.current
    LaunchedEffect(hidden) { if (hidden) focus.clearFocus(force = true) }
    Box(Modifier.fillMaxSize().zIndex(if (aboveStatistics) 1f else 0f)
        .then(HostPlacement(hidden && !drawWhileHidden))
        .onPreviewKeyEvent { hidden }
        .pointerInput(hidden) {
            if (hidden) awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
        .graphicsLayer { alpha = opacity().coerceIn(0f, 1f); this.translationY = translationY() }
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
    val navigating = StatsHostState.navigating
    // Same-route tool pages do not participate in NavDisplay's entrance.
    val toolOffset = remember(StatsHostState.navigationTicket) {
        Animatable(IntOffset(0, if (navigating && !StatsHostState.nativeNavigation) StatsPageOffsetPixels else 0),
            IntOffset.VectorConverter)
    }
    LaunchedEffect(toolOffset) {
        toolOffset.animateTo(IntOffset.Zero, spring(dampingRatio = 1f, stiffness = StatsPageSpringStiffness))
    }
    RetainedHostContent(
        hidden = visible,
        drawWhileHidden = navigating || (fade != null && fade.value > 0f),
        opacity = { if (!visible || navigating) 1f else fade?.value ?: 0f },
        aboveStatistics = navigating,
        translationY = { toolOffset.value.y.toFloat() },
        // Native material navigation briefly overlaps both pages: fade the whole old
        // page in place instead of cutting it off at the incoming page's moving edge.
        content = content
    )
}

@Composable
fun StatsPageLayers(hostContent: @Composable () -> Unit, statistics: @Composable () -> Unit = { StatsOverlay() }) {
    // One fade per presented page, shared with its spring rather than a second timer.
    val animations = com.jethuit.spw.listenstats.data.StatsSnapshots.current?.latest?.preferences?.pageAnimations ?: true
    val fade = if (StatsHostState.visible) remember { Animatable(if (animations) 1f else 0f) } else null
    val offset = if (StatsHostState.visible) remember {
        Animatable(IntOffset(0, if (animations) StatsPageOffsetPixels else 0), IntOffset.VectorConverter)
    } else null
    val bridge = HostUiRuntime.current
    val appearance = rememberHostAppearance(bridge?.initialAppearance ?: HostAppearance(),
        bridge?.readAppearance ?: HostAppearanceReader::read)
    CompositionLocalProvider(LocalStatsHostFade provides fade, LocalStatsPageOffset provides offset,
        LocalStatsMaterialBridge provides true, LocalHostAppearance provides appearance) {
        HostMaterialLayer(hostContent)
        statistics()
    }
}
