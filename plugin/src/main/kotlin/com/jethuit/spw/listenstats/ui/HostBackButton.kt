@file:OptIn(com.moriafly.salt.ui.UnstableSaltUiApi::class)

package com.jethuit.spw.listenstats.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.moriafly.salt.ui.screen.BasicScreenDefaults

/** Same button and desktop title-row geometry as SPW's BasicScreen. */
@Composable
fun HostBackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.height(56.dp).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicScreenDefaults.BackButton(
            onBack = onBack,
            modifier = Modifier.semantics { contentDescription = "返回宿主" }
        )
    }
}

/** Reserve the native navigation row outside the clipped scrolling viewport. */
@Composable
fun StatsPageFrame(onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        HostBackButton(onBack, Modifier.fillMaxWidth())
        Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
            content()
        }
    }
}
