package com.jethuit.spw.listenstats.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Appended after the host navigation root. When opened, it renders inside the
 * host composition instead of creating a JDialog or a Popup window.
 */
@Composable
fun StatsOverlay() {
    CaptureHostNavigator()
    if (!StatsHostState.visible) return
    Box(Modifier.fillMaxSize()) {
        ListenStatsApp(
            embedded = true,
            onClose = { StatsHostState.requestClose() }
        )
    }
}
