package com.jethuit.spw.listenstats.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.DrawModifier
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.toComposeImageBitmap
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
import org.jetbrains.skia.Image

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

/** Navigation tabs keep native click/focus semantics without painting an interaction state layer. */
object PlainNavIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PlainNode()
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = 18523
    private class PlainNode : Modifier.Node(), DrawModifierNode {
        override fun ContentDrawScope.draw() { drawContent() }
    }
}

object AppType {
    val pageTitle = TextStyle(
        fontSize = 27.sp,
        fontWeight = FontWeight(620),
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
    val action = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp)
    val tiny = TextStyle(fontSize = 11.sp, lineHeight = 16.sp)
    val number = TextStyle(
        fontSize = 26.sp,
        fontWeight = FontWeight(550),
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
    iconSize: Dp = 18.dp,
    rotationDegrees: Float = 0f,
    viewBoxSize: Float = 960f,
    positiveCoordinates: Boolean = false
) {
    val path = remember(pathData) {
        try {
            PathParser().parsePathString(pathData).toPath()
        } catch (failure: Throwable) {
            Path()
        }
    }
    Box(modifier.size(iconSize).drawWith {
        val scale = minOf(size.width, size.height) / viewBoxSize
        if (scale <= 0f) return@drawWith
        withTransform({
            if (rotationDegrees != 0f) {
                rotate(rotationDegrees, pivot = Offset(size.width / 2f, size.height / 2f))
            }
            translate(0f, if (positiveCoordinates) 0f else size.height)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            drawPath(path, tint)
        }
    })
}

/** Load SPW's toolbar artwork at runtime; host resources are never copied into the plugin. */
@Composable
fun HostToolbarIcon(resource: String, fallback: String, tint: Color) {
    val bitmap = remember(resource) {
        runCatching {
            IconPaths::class.java.classLoader.getResourceAsStream(
                "composeResources/voxzen.composeapp.generated.resources/drawable/$resource.png"
            )?.use { Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
        }.getOrNull()
    }
    if (bitmap == null) {
        AppIcon(fallback, tint, iconSize = 20.dp)
    } else {
        Box(Modifier.size(20.dp).drawWith {
            withTransform({ scale(size.width / bitmap.width, size.height / bitmap.height, pivot = Offset.Zero) }) {
                drawImage(bitmap, colorFilter = ColorFilter.tint(tint))
            }
        })
    }
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
