package com.jethuit.spw.listenstats.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Constraints
import com.xuncorp.voxzen.ui.navigation.AppNavigation

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

/** The replacement applies only in the host caller; this plugin's original call is not hooked. */
@Composable
fun HostMaterialLayer() {
    DisposableEffect(Unit) {
        StatsHostState.materialBridgeAvailable = true
        onDispose { StatsHostState.materialBridgeAvailable = false }
    }
    RetainedHostContent(StatsHostState.visible) { AppNavigation() }
}
