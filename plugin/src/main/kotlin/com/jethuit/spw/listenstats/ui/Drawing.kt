package com.jethuit.spw.listenstats.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.DrawModifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Draws arbitrary content inside a layout node without the obfuscated Canvas composable. */
class DrawBlock(
    private val overlay: Boolean,
    private val block: DrawScope.() -> Unit
) : DrawModifier {
    override fun ContentDrawScope.draw() {
        if (overlay) drawContent()
        block(this)
        if (!overlay) drawContent()
    }
}

/** Draws below the node's children. */
fun Modifier.drawWith(block: DrawScope.() -> Unit): Modifier = then(DrawBlock(false, block))

/** Draws above the node's children. */
fun Modifier.drawOver(block: DrawScope.() -> Unit): Modifier = then(DrawBlock(true, block))

object AppType {
    val pageTitle = TextStyle(
        fontSize = 27.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.6).sp,
        lineHeight = 36.sp
    )
    val sectionTitle = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.1.sp,
        lineHeight = 22.sp
    )
    val body = TextStyle(fontSize = 13.sp, lineHeight = 20.sp)
    val bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp)
    val tiny = TextStyle(fontSize = 11.sp, lineHeight = 16.sp)
    val number = TextStyle(
        fontSize = 26.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.65).sp,
        lineHeight = 36.sp
    )
    val numberSmall = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp
    )
    val tab = TextStyle(fontSize = 15.sp, lineHeight = 20.sp)
    val tableHead = TextStyle(fontSize = 11.sp, lineHeight = 16.sp)
    val table = TextStyle(fontSize = 13.sp, lineHeight = 19.sp)
}

@Composable
fun AppIcon(
    pathData: String,
    tint: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 18.dp
) {
    val path = remember(pathData) {
        try {
            PathParser().parsePathString(pathData).toPath()
        } catch (failure: Throwable) {
            Path()
        }
    }
    Box(modifier.size(iconSize).drawWith {
        val scale = minOf(size.width, size.height) / 960f
        if (scale <= 0f) return@drawWith
        withTransform({
            translate(0f, size.height)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            drawPath(path, tint)
        }
    })
}

fun DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    color: Color,
    topLeft: Offset
): TextLayoutResult {
    val styled = style.copy(color = color)
    val layout = measurer.measure(text, styled)
    drawText(layout, topLeft = topLeft)
    return layout
}

fun DrawScope.drawCenteredLabel(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    color: Color,
    centerX: Float,
    top: Float
): TextLayoutResult {
    val styled = style.copy(color = color)
    val layout = measurer.measure(text, styled)
    drawText(layout, topLeft = Offset(centerX - layout.size.width / 2f, top))
    return layout
}

fun DrawScope.drawRightLabel(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    color: Color,
    right: Float,
    top: Float
): TextLayoutResult {
    val styled = style.copy(color = color)
    val layout = measurer.measure(text, styled)
    drawText(layout, topLeft = Offset(right - layout.size.width, top))
    return layout
}
