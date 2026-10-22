package com.jethuit.spw.listenstats.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Static neutral shapes: no fabricated counts or empty-state message before the first snapshot. */
@Composable
fun StatsPlaceholder(page: StatsPage) {
    val colors = LocalStatsColors.current
    Box(Modifier.fillMaxWidth().height(500.dp)
        .semantics { contentDescription = "统计数据读取中" }.drawWith {
        val tint = colors.text.copy(alpha = 0.045f)
        fun block(x: Float, y: Float, width: Float, height: Float, radius: Float = 8f) {
            drawRoundRect(tint, Offset(x, y.dp.toPx()), Size(width, height.dp.toPx()),
                CornerRadius(radius.dp.toPx()))
        }
        if (page == StatsPage.Overview) {
            block(0f, 0f, size.width, 100f, 14f)
            block(0f, 140f, size.width * 0.18f, 16f)
            block(0f, 180f, size.width, 140f)
            block(0f, 360f, size.width * 0.18f, 16f)
            block(0f, 400f, size.width, 100f)
        } else {
            block(0f, 12f, size.width * 0.24f, 20f)
            repeat(6) { row ->
                val y = 76f + row * 66f
                val cover = 48.dp.toPx()
                block(0f, y, cover, 48f)
                block(60.dp.toPx(), y + 8f, (size.width - 80.dp.toPx()) * 0.45f, 12f)
                block(60.dp.toPx(), y + 30f, (size.width - 80.dp.toPx()) * 0.28f, 10f)
            }
        }
    })
}
