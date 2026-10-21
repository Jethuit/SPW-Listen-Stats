@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.jethuit.spw.listenstats.ui

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

private val PageEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** Composed only when the first data snapshot is ready; reloads do not replay it. */
@Composable
fun StatsPageEnter(content: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }
    val distance = with(LocalDensity.current) { 28.dp.toPx() }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(220, easing = PageEasing)) }
    Box(Modifier.fillMaxSize().graphicsLayer {
        alpha = progress.value
        translationY = distance * (1f - progress.value)
    }) { content() }
}

/** Header/navigation stay outside. All UI choices and loaded data live in the parent. */
@Composable
fun StatsPageBody(page: StatsPage, content: @Composable (StatsPage) -> Unit) {
    val distance = with(LocalDensity.current) { 36.dp.roundToPx() }
    AnimatedContent(
        targetState = page,
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopStart,
        transitionSpec = {
            val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
            ((slideIn(tween(200, easing = PageEasing)) { IntOffset(direction * distance, 0) } +
                fadeIn(tween(200, easing = PageEasing))) togetherWith
                (slideOut(tween(200, easing = PageEasing)) { IntOffset(-direction * distance, 0) } +
                    fadeOut(tween(160)))).using(
                        SizeTransform(clip = true) { _, _ -> tween(200, easing = PageEasing) }
                    )
        },
        label = "statistics-page"
    ) { shownPage ->
        Box(Modifier.fillMaxWidth().onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
            if (shownPage != page) event.changes.forEach { it.consume() }
        }) { content(shownPage) }
    }
}
