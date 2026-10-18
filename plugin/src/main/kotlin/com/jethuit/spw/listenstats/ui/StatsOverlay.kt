package com.jethuit.spw.listenstats.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.jethuit.spw.listenstats.PluginLog

private object WindowCornerPosition : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset = IntOffset(
        x = windowSize.width - popupContentSize.width - 24,
        y = windowSize.height - popupContentSize.height - 24
    )
}

private val overlayProperties = PopupProperties(
    focusable = false,
    dismissOnBackPress = false,
    dismissOnClickOutside = false,
    clippingEnabled = false,
    usePlatformDefaultWidth = false
)

/** Small always-available entry, appended after the host navigation root. */
@Composable
fun StatsOverlay() {
    LaunchedEffect(Unit) { PluginLog.info("StatsOverlay composed") }
    Popup(
        popupPositionProvider = WindowCornerPosition,
        properties = overlayProperties
    ) {
        Surface(
            onClick = {
                PluginLog.info("stats entry clicked")
                StatsWindow.open()
            },
            shape = PillShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            tonalElevation = 6.dp
        ) {
            Text(
                "听歌统计",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}
