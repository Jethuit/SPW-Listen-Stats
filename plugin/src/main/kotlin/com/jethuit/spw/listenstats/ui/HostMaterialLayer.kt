@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class,
    androidx.compose.runtime.InternalComposeApi::class,
    com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package com.jethuit.spw.listenstats.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Constraints
import com.xuncorp.spw.workshop.api.hook.ComposableHook
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import java.lang.reflect.Method

/** Wrap the navigation's inner layout, leaving its outer replacement slot to other tools. */
object StatsMaterialHook {
    const val TARGET_CLASS = "androidx.compose.animation.SharedTransitionScopeKt"
    const val TARGET_METHOD = "SharedTransitionLayout"
    const val CALLER_CLASS = "com.xuncorp.voxzen.ui.navigation.AppNavigationKt"

    fun register(addHook: (String, String, String, List<String>, ComposableHook) -> AutoCloseable): AutoCloseable {
        HostSharedTransitionLayout.prepare()
        return addHook(TARGET_CLASS, TARGET_METHOD, CALLER_CLASS,
            listOf("androidx.compose.ui.Modifier", "kotlin.jvm.functions.Function3"), object : ComposableHook() {
                @Composable
                override fun Content(call: UiHookCall) {
                    val modifier = call.arguments[0] as Modifier
                    val content = requireNotNull(call.arguments[1])
                    HostMaterialLayer { HostSharedTransitionLayout.render(modifier, content, currentComposer) }
                }
            })
    }
}

/** Compose lambdas implement multiple JVM function arities, so never use Kotlin's arity cast. */
private object HostSharedTransitionLayout {
    private val method: Method by lazy {
        val loader = StatsMaterialHook::class.java.classLoader
        Class.forName(StatsMaterialHook.TARGET_CLASS, false, loader).getDeclaredMethod(
            StatsMaterialHook.TARGET_METHOD, Modifier::class.java,
            Class.forName("kotlin.jvm.functions.Function3", false, loader),
            Composer::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        ).also { check(java.lang.reflect.Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE) }
    }

    fun prepare() { method }

    fun render(modifier: Modifier, content: Any, composer: Composer) {
        method.invoke(null, modifier, content, composer, 0, 0)
    }
}

/** Keep the host composition alive, but omit placement, drawing and hit testing while hidden. */
private data class HostPlacement(val hidden: Boolean) : LayoutModifier {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val child = measurable.measure(constraints)
        return layout(child.width, child.height) {
            if (!hidden) child.placeRelative(0, 0)
        }
    }
}

@Composable
fun RetainedHostContent(hidden: Boolean, content: @Composable () -> Unit) {
    val focus = LocalFocusManager.current
    LaunchedEffect(hidden) { if (hidden) focus.clearFocus(force = true) }
    Box(Modifier.fillMaxSize().then(HostPlacement(hidden))) { content() }
}

/** This plugin's original layout call is outside the filtered host caller and is not hooked. */
@Composable
fun HostMaterialLayer(content: @Composable () -> Unit) {
    DisposableEffect(Unit) {
        StatsHostState.materialBridgeAvailable = true
        onDispose { StatsHostState.materialBridgeAvailable = false }
    }
    RetainedHostContent(StatsHostState.visible, content)
}
