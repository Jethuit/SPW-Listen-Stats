@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.jethuit.spw.listenstats.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

internal val PageEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
const val StatsMotionDurationMillis = 300
val LocalStatsAnimationsEnabled = staticCompositionLocalOf { true }

@Composable
fun statsMotionDurationMillis(): Int = if (LocalStatsAnimationsEnabled.current) StatsMotionDurationMillis else 0

/** Keep the trailing action anchored while the search field opens towards the left. */
@Composable
fun SearchExpansion(visible: Boolean, content: @Composable () -> Unit) {
    val duration = statsMotionDurationMillis()
    val distance = with(LocalDensity.current) { 16.dp.roundToPx() }
    AnimatedVisibility(
        visible = visible,
        enter = expandHorizontally(tween(duration, easing = PageEasing), expandFrom = Alignment.End) +
            slideIn(tween(duration, easing = PageEasing)) { IntOffset(distance, 0) } +
            fadeIn(tween(duration, easing = PageEasing)),
        exit = shrinkHorizontally(tween(duration, easing = PageEasing), shrinkTowards = Alignment.End) +
            slideOut(tween(duration, easing = PageEasing)) { IntOffset(distance, 0) } +
            fadeOut(tween(duration, easing = PageEasing))
    ) { content() }
}

/** Composed only when the first data snapshot is ready; reloads do not replay it. */
@Composable
fun StatsPageEnter(content: @Composable () -> Unit) {
    val duration = statsMotionDurationMillis()
    val progress = remember { Animatable(if (duration == 0) 1f else 0f) }
    val distance = with(LocalDensity.current) { 28.dp.toPx() }
    LaunchedEffect(duration) {
        if (duration == 0) progress.snapTo(1f)
        else progress.animateTo(1f, tween(duration, easing = PageEasing))
    }
    Box(Modifier.fillMaxSize().graphicsLayer {
        alpha = progress.value
        translationY = distance * (1f - progress.value)
    }) { content() }
}

/** Header/navigation stay outside. All UI choices and loaded data live in the parent. */
@Composable
fun StatsPageBody(page: StatsPage, content: @Composable (StatsPage) -> Unit) {
    DirectionalContent(page, { it }, { from, to -> to.ordinal.compareTo(from.ordinal) },
        "statistics-page", content)
}

/** Stable keys avoid replaying a transition on data refresh; each outgoing value keeps its snapshot. */
@Composable
fun <T> DirectionalContent(
    target: T,
    contentKey: (T) -> Any,
    direction: (T, T) -> Int,
    label: String,
    content: @Composable (T) -> Unit
) {
    val duration = statsMotionDurationMillis()
    val distance = with(LocalDensity.current) { 36.dp.roundToPx() }
    AnimatedContent(
        targetState = target,
        contentKey = contentKey,
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopStart,
        transitionSpec = {
            val sign = if (direction(initialState, targetState) >= 0) 1 else -1
            ((slideIn(tween(duration, easing = PageEasing)) { IntOffset(sign * distance, 0) } +
                fadeIn(tween(duration, easing = PageEasing))) togetherWith
                (slideOut(tween(duration, easing = PageEasing)) { IntOffset(-sign * distance, 0) } +
                    fadeOut(tween(duration)))).using(
                        SizeTransform(clip = true) { _, _ -> tween(duration, easing = PageEasing) }
                    )
        },
        label = label
    ) { shown ->
        Box(Modifier.fillMaxWidth().onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
            if (contentKey(shown) != contentKey(target)) event.changes.forEach { it.consume() }
        }) { content(shown) }
    }
}
