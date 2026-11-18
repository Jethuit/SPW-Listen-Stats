@file:OptIn(com.moriafly.salt.ui.UnstableSaltUiApi::class)

package com.jethuit.spw.listenstats.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moriafly.salt.ui.screen.BasicScreen

/** Salt UI places the scrolling source behind the title bar and supplies its content inset. */
@Composable
fun StatsPageFrame(
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    BasicScreen(
        onBack = onBack,
        modifier = Modifier.fillMaxSize(),
        title = "听歌统计",
        toolButtons = actions,
        contentPadding = PaddingValues(0.dp)
    ) { insets -> content(insets) }
}
