package com.jethuit.spw.listenstats.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * The host jar is obfuscated and does not expose all the RoundedCornerShape
 * constructor overloads through Kotlin metadata, so shapes are built directly.
 */
class AppShape(private val radius: Dp) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val pixels = with(density) { radius.toPx() }
            .coerceAtMost(minOf(size.width, size.height) / 2f)
        val corner = CornerRadius(pixels)
        return Outline.Rounded(
            RoundRect(
                left = 0f,
                top = 0f,
                right = size.width,
                bottom = size.height,
                topLeftCornerRadius = corner,
                topRightCornerRadius = corner,
                bottomRightCornerRadius = corner,
                bottomLeftCornerRadius = corner
            )
        )
    }
}

val PillShape: Shape = AppShape(999.dp)
