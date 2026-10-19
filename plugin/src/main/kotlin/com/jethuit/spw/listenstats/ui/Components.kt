@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, com.moriafly.salt.ui.UnstableSaltUiApi::class)

package com.jethuit.spw.listenstats.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import com.moriafly.salt.ui.Card as SaltCard
import com.moriafly.salt.ui.LocalTextStyle
import com.moriafly.salt.ui.rememberScrollState
import com.moriafly.salt.ui.verticalScroll
import com.jethuit.spw.listenstats.ui.StatsText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

data class SegmentOption(
    val key: String,
    val label: String? = null,
    val icon: String? = null,
    val tooltip: String? = null
)

private val ControlHeight = 34.dp

@Composable
fun HoverColor(
    normal: Color,
    hover: Color,
    tooltip: String? = null,
    content: @Composable (Color) -> Unit
) {
    var hovered by remember { mutableStateOf(false) }
    Box(
        Modifier
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
    ) {
        content(if (hovered) hover else normal)
        if (hovered && tooltip != null) StatsTooltip(TooltipData(tooltip, "", "", 0f, 24f))
    }
}

@Composable
fun IconAction(
    path: String,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 34.dp,
    iconSize: Dp = 19.dp,
    tooltip: String? = label
) {
    val colors = LocalStatsColors.current
    HoverColor(Color.Transparent, colors.hover, tooltip) { background ->
        Surface(
            onClick = onClick,
            modifier = modifier.size(buttonSize).semantics { contentDescription = label },
            shape = AppShape(8.dp),
            color = background
        ) {
            Box(Modifier.size(buttonSize), contentAlignment = Alignment.Center) {
                AppIcon(path, tint, iconSize = iconSize)
            }
        }
    }
}

@Composable
fun TextAction(
    path: String,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    val colors = LocalStatsColors.current
    HoverColor(Color.Transparent, colors.hover) { background ->
        Surface(
            onClick = onClick,
            modifier = Modifier.height(34.dp),
            shape = AppShape(8.dp),
            color = background
        ) {
            Row(
                Modifier.fillMaxHeight().padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(path, colors.muted, iconSize = 19.dp)
                Text(label, style = AppType.action.copy(color = tint))
            }
        }
    }
}

@Composable
fun SegmentedControl(
    options: List<SegmentOption>,
    selected: String,
    onSelected: (String) -> Unit,
    iconOnly: Boolean = false
) {
    val colors = LocalStatsColors.current
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
    Surface(
        modifier = Modifier.height(ControlHeight),
        color = colors.controlBackground,
        shape = AppShape(8.dp)
    ) {
        Row(
            Modifier.fillMaxHeight().padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            options.forEach { option ->
                val active = option.key == selected
                HoverColor(
                    if (active) colors.background else Color.Transparent,
                    if (active) colors.background else colors.hover,
                    tooltip = null
                ) { background ->
                    Surface(
                        onClick = { onSelected(option.key) },
                        modifier = Modifier.semantics { contentDescription = option.tooltip ?: option.label.orEmpty() },
                        shape = AppShape(5.dp),
                        color = background,
                        border = if (active) BorderStroke(1.dp, SolidColor(colors.line)) else null
                    ) {
                        Row(
                            Modifier
                                .height(ControlHeight - 6.dp)
                                .padding(horizontal = if (iconOnly) 7.dp else 8.dp)
                                .then(if (iconOnly) Modifier.width(18.dp) else Modifier),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            option.icon?.let {
                                AppIcon(
                                    it,
                                    if (active) colors.text else colors.muted,
                                    iconSize = 17.dp
                                )
                            }
                            if (!iconOnly && option.label != null) {
                                Text(
                                    option.label,
                                    style = AppType.tiny.copy(
                                        color = if (active) colors.text else colors.muted
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

}

@Composable
fun SelectBox(
    label: String,
    options: List<Pair<String, String>>,
    selected: String? = null,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    minWidth: Dp = 0.dp
) {
    val colors = LocalStatsColors.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val optionTextStyle = LocalTextStyle.current.merge(AppType.bodySmall)
    val longestTextPx = remember(options, optionTextStyle) {
        options.maxOfOrNull { measurer.measure(it.second, optionTextStyle).size.width } ?: 0
    }
    val optionWidth = with(density) {
        (longestTextPx + 20.dp.toPx()).toDp()
    }.coerceAtLeast(132.dp)
    val naturalMenuWidth = optionWidth + 12.dp
    var triggerWidthPx by remember { mutableStateOf(0) }
    val triggerWidthDp = with(density) { triggerWidthPx.toDp() }
    val menuWidth = if (triggerWidthPx > 0) {
        minOf(triggerWidthDp, naturalMenuWidth)
    } else {
        naturalMenuWidth
    }
    var expanded by remember { mutableStateOf(false) }
    var anchorBounds by remember { mutableStateOf<IntRect?>(null) }
    Box(
        modifier.onGloballyPositioned {
            val bounds = it.boundsInWindow()
            triggerWidthPx = it.size.width
            anchorBounds = IntRect(
                bounds.left.roundToInt(),
                bounds.top.roundToInt(),
                bounds.right.roundToInt(),
                bounds.bottom.roundToInt()
            )
        }
    ) {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.height(ControlHeight),
            shape = AppShape(8.dp),
            color = colors.controlBackground
        ) {
            Row(
                Modifier
                    .fillMaxHeight()
                    .then(if (minWidth > 0.dp) Modifier.width(minWidth) else Modifier)
                    .padding(start = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, style = AppType.bodySmall.copy(color = colors.text))
                Box(Modifier.width(8.dp))
                AppIcon(
                    IconPaths.KeyboardArrowDown,
                    colors.muted,
                    iconSize = 18.dp,
                    viewBoxSize = 24f,
                    positiveCoordinates = true
                )
            }
        }
        if (expanded) {
            val anchor = anchorBounds
            if (anchor != null) {
                Popup(
                    popupPositionProvider = remember(anchor, density) {
                        SelectMenuPositionProvider(anchor, with(density) { 4.dp.roundToPx() })
                    },
                    onDismissRequest = { expanded = false },
                    properties = PopupProperties(focusable = true, dismissOnClickOutside = true)
                ) {
                    Surface(
                        modifier = Modifier.width(menuWidth),
                        shape = AppShape(9.dp),
                        color = colors.background,
                        border = BorderStroke(1.dp, SolidColor(colors.line)),
                        shadowElevation = 4.dp
                    ) {
                        Box(Modifier.padding(6.dp)) {
                            Column(
                                Modifier
                                    .heightIn(max = 320.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                options.forEach { (value, text) ->
                                val isSelected = value == selected
                                HoverColor(
                                    if (isSelected) colors.controlBackground else Color.Transparent,
                                    colors.hover
                                ) { background ->
                                    Surface(
                                        onClick = {
                                            expanded = false
                                            onSelected(value)
                                        },
                                        modifier = Modifier.fillMaxWidth().height(34.dp),
                                        shape = AppShape(5.dp),
                                        color = background
                                    ) {
                                        Row(
                                            Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text,
                                                maxLines = 1,
                                                style = AppType.bodySmall.copy(
                                                    color = if (isSelected) colors.accent else colors.text
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

}

private class SelectMenuPositionProvider(
    private val anchor: IntRect,
    private val gapPx: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        var x = anchor.left
        var y = anchor.bottom + gapPx
        if (y + popupContentSize.height > windowSize.height) {
            y = anchor.top - popupContentSize.height - gapPx
        }
        x = x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        y = y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}

@Composable
fun SectionHeading(
    icon: String,
    title: String,
    caption: String? = null,
    controlsGap: Dp = 12.dp,
    headingHeight: Dp = 42.dp,
    titleStyle: TextStyle = AppType.sectionTitle,
    controls: @Composable () -> Unit
) {
    val colors = LocalStatsColors.current
    val heading: @Composable () -> Unit = {
        Column {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(icon, colors.muted, iconSize = 19.dp)
                Text(title, style = titleStyle.copy(color = colors.text))
            }
            if (caption != null) {
                Text(
                    caption,
                    modifier = Modifier.padding(top = 7.dp),
                    style = AppType.bodySmall.copy(color = colors.muted)
                )
            }
        }
    }
    val actions: @Composable () -> Unit = {
        Row(
            horizontalArrangement = Arrangement.spacedBy(controlsGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            controls()
        }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = headingHeight),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f, fill = false)) { heading() }
        actions()
    }
}

@Composable
fun SummaryStrip(
    columnCount: Int,
    modifier: Modifier = Modifier,
    content: @Composable (itemWidth: Dp) -> Unit
) {
    val colors = LocalStatsColors.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val itemWidth = (maxWidth - 8.dp) / columnCount
        SaltCard(
            modifier = Modifier.fillMaxWidth(),
            shape = AppShape(15.dp),
            color = colors.background,
            border = BorderStroke(1.dp, SolidColor(colors.line))
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 23.dp)
                    .drawWith {
                        val step = size.width / columnCount
                        for (index in 1 until columnCount) {
                            val x = step * index
                            drawLine(
                                color = colors.line,
                                start = androidx.compose.ui.geometry.Offset(x, 4f),
                                end = androidx.compose.ui.geometry.Offset(x, size.height - 4f),
                                strokeWidth = 1f
                            )
                        }
                    }
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    content(itemWidth)
                }
            }
        }
    }
}

@Composable
fun VerticalRule(color: Color, height: Dp) {
    Box(Modifier.width(1.dp).height(height).drawWith { drawRect(color = color) })
}

data class TooltipData(
    val title: String,
    val label: String,
    val value: String,
    val x: Float,
    val y: Float
)

internal class FixedPositionProvider(
    private val position: IntOffset,
    private val gap: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize
    ): IntOffset {
        return IntOffset(
            UiGeometry.popupAxis(anchorBounds.left, position.x, popupContentSize.width, windowSize.width, gap),
            UiGeometry.popupAxis(anchorBounds.top, position.y, popupContentSize.height, windowSize.height, gap)
        )
    }
}

@Composable
fun StatsTooltip(data: TooltipData?) {
    if (data == null) return
    val colors = LocalStatsColors.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val position = IntOffset(data.x.toInt(), data.y.toInt())
    Popup(popupPositionProvider = FixedPositionProvider(position, with(density) { 14.dp.roundToPx() })) {
        Surface(
            shape = AppShape(10.dp),
            color = colors.tooltipBackground,
            shadowElevation = 8.dp
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    data.title,
                    style = AppType.bodySmall.copy(color = colors.tooltipText)
                )
                if (data.label.isNotEmpty()) {
                Box(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        data.label,
                        style = AppType.bodySmall.copy(color = colors.tooltipText.copy(alpha = 0.7f))
                    )
                    Text(
                        data.value,
                        style = AppType.bodySmall.copy(color = colors.tooltipText)
                    )
                }
                }
            }
        }
    }
}
