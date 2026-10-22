@file:OptIn(com.moriafly.salt.ui.UnstableSaltUiApi::class)

package com.jethuit.spw.listenstats.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.moriafly.salt.ui.screen.BasicScreenDefaults

/** Same button and desktop title-row geometry as SPW's BasicScreen. */
@Composable
fun HostBackButton(onBack: () -> Unit, modifier: Modifier = Modifier, title: String? = null) {
    Row(
        modifier = modifier.height(56.dp).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicScreenDefaults.BackButton(
            onBack = onBack,
            modifier = Modifier.semantics { contentDescription = "返回宿主" }
        )
        if (title != null) {
            Spacer(Modifier.width(8.dp))
            StatsText(title, maxLines = 1, style = TextStyle(
                fontSize = 24.sp, fontWeight = FontWeight.Medium,
                color = LocalStatsColors.current.text
            ))
        }
    }
}

/** Reserve the native navigation row outside the clipped scrolling viewport. */
@Composable
fun StatsPageFrame(
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            HostBackButton(onBack, Modifier.weight(1f), title = "听歌统计")
            Box(Modifier.padding(end = 12.dp)) { actions() }
        }
        Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
            content()
        }
    }
}
