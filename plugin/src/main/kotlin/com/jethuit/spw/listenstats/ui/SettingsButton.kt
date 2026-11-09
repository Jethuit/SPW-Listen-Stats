@file:OptIn(com.moriafly.salt.ui.UnstableSaltUiApi::class)

package com.jethuit.spw.listenstats.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.moriafly.salt.ui.BasicButton
import com.moriafly.salt.ui.ButtonColors
import com.moriafly.salt.ui.ControlSize

@Composable
fun SettingsButton(onClick: () -> Unit = StatsNavigationHooks::openConfiguration) {
    val colors = LocalStatsColors.current
    HoverColor(Color.Transparent, colors.hover) { background ->
        BasicButton(onClick = onClick,
            modifier = Modifier.size(40.dp).semantics { contentDescription = "打开听歌统计配置" },
            size = ControlSize.Regular, shape = AppShape(8.dp),
            colors = ButtonColors(background, colors.text, background, colors.muted),
            contentPadding = PaddingValues(0.dp)) {
            AppIcon(IconPaths.Settings, colors.text, iconSize = 24.dp)
        }
    }
}
