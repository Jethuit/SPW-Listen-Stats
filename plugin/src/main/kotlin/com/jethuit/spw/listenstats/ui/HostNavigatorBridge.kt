@file:OptIn(androidx.compose.runtime.InternalComposeApi::class)

package com.jethuit.spw.listenstats.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.currentComposer

/** Capture the host's navigation context even while the statistics page is closed. */
@Composable
fun CaptureHostNavigator() {
    val composer = currentComposer
    val navigator = runCatching {
        HostNavigatorAccess.type.cast(composer.consume(HostNavigatorAccess.local))
    }.getOrNull()
    DisposableEffect(navigator) {
        val handle = navigator?.let { host ->
            StatsNavigationHooks.bindNavigator(host,
                currentRoute = { (HostNavigatorAccess.history.invoke(host) as List<*>).lastOrNull() },
                navigate = { route -> HostNavigatorAccess.navigate.invoke(host, route) },
                hostHandlesBack = { HostBackPriority.hasOverlay(host) })
        }
        onDispose { handle?.close() }
    }
}

/** Match Navigator.back's higher layers before consuming the embedded statistics page. */
object HostBackPriority {
    private val loader = StatsNavigationHooks::class.java.classLoader
    private val navigatorType by lazy { Class.forName(StatsNavigationHooks.NAVIGATOR_CLASS, false, loader) }
    private val history by lazy { navigatorType.getDeclaredMethod("getNavBackStack") }
    private val playerState by lazy { navigatorType.getDeclaredMethod("getPlayerSheetState") }
    // Verified in Navigator.back in both supported hosts (SPW 1.19.0 / 1.19.1).
    private val expanded by lazy { playerState.returnType.getDeclaredMethod("\u0528") }
    private val dialog by lazy {
        Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRouteKt", false, loader)
            .getDeclaredMethod("isDialogRoute", Class.forName("androidx.navigation3.runtime.NavKey", false, loader))
    }

    fun hasOverlay(navigator: Any): Boolean {
        val route = (history.invoke(navigator) as List<*>).lastOrNull()
        return (route != null && dialog.invoke(null, route) == true) ||
            expanded.invoke(playerState.invoke(navigator)) == true
    }
}

private object HostNavigatorAccess {
    private val loader = StatsNavigationHooks::class.java.classLoader
    val type by lazy { Class.forName(StatsNavigationHooks.NAVIGATOR_CLASS, false, loader) }
    val navigate by lazy {
        type.getDeclaredMethod("navigate",
            Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRoute", false, loader))
    }
    val history by lazy { type.getDeclaredMethod("getNavBackStack") }
    @Suppress("UNCHECKED_CAST")
    val local by lazy {
        Class.forName("com.xuncorp.voxzen.ui.navigation.AppNavigationKt", false, loader)
            .getDeclaredMethod("getLocalNavigator").invoke(null) as CompositionLocal<Any?>
    }
}
