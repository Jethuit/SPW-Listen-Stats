@file:OptIn(androidx.compose.runtime.InternalComposeApi::class)

package com.jethuit.spw.listenstats.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.currentComposer

/** Capture the host's navigation context even while the statistics page is closed. */
@Composable
fun CaptureHostNavigator() {
    val access = HostUiRuntime.current ?: return
    val composer = currentComposer
    val navigator = runCatching {
        access.navigatorType.cast(composer.consume(access.local()))
    }.getOrNull()
    DisposableEffect(navigator) {
        val handle = navigator?.let { host ->
            StatsNavigationHooks.bindNavigator(host,
                currentRoute = { access.currentRoute(host) },
                navigate = { route -> access.navigate.invoke(host, route) },
                hostHandlesBack = { access.hasOverlay(host) })
        }
        onDispose { handle?.close() }
    }
}

/** Match Navigator.back's higher layers before consuming the embedded statistics page. */
object HostBackPriority {
    fun hasOverlay(navigator: Any): Boolean = HostUiRuntime.require().hasOverlay(navigator)
}
