@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.jethuit.spw.listenstats.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.scaleIn
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal val PageEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
const val StatsMotionDurationMillis = 300
const val StatsPageOffsetPixels = 150
const val StatsPageSpringStiffness = 500f
val LocalStatsAnimationsEnabled = staticCompositionLocalOf { true }
val LocalStatsPageExiting = staticCompositionLocalOf { false }
internal val LocalStatsHostFade = staticCompositionLocalOf<Animatable<Float, AnimationVector1D>?> { null }
internal val LocalStatsPageOffset = staticCompositionLocalOf<Animatable<IntOffset, AnimationVector2D>?> { null }

// Animatable resets velocity on cancellation; retain the last frame for a reversed spring.
private class PageSpringVelocity {
    var offset = IntOffset.Zero
    var opacity = 0f
    var hostOpacity = 0f
}

@Composable
fun statsMotionDurationMillis(): Int = if (LocalStatsAnimationsEnabled.current) StatsMotionDurationMillis else 0

/** SPW ScreenSearchBar swaps the whole action, with a short fade-out before fade/scale-in. */
@Composable
fun SearchExpansion(expanded: Boolean, content: @Composable (Boolean) -> Unit) {
    if (!LocalStatsAnimationsEnabled.current) {
        content(expanded)
        return
    }
    AnimatedContent(
        targetState = expanded,
        transitionSpec = {
            (fadeIn(tween(300, delayMillis = 90)) +
                scaleIn(tween(300, delayMillis = 90), initialScale = 0.92f)) togetherWith
                fadeOut(tween(90))
        },
        label = "ranking-search"
    ) { shown -> content(shown) }
}

/** Forward: fade in place under the incoming page. Return: slide/fade down. */
@Composable
fun StatsPageEnter(
    exiting: Boolean = false,
    exitMotion: StatsExitMotion = StatsExitMotion.Return,
    onExited: () -> Unit = {},
    onEntered: () -> Unit = {},
    content: @Composable () -> Unit
) {
    val enabled = LocalStatsAnimationsEnabled.current
    val offset = LocalStatsPageOffset.current ?: remember {
        Animatable(IntOffset(0, if (enabled) StatsPageOffsetPixels else 0), IntOffset.VectorConverter)
    }
    val opacity = remember { Animatable(1f) }
    val velocities = remember { PageSpringVelocity() }
    val hostFade = LocalStatsHostFade.current
    val finished by rememberUpdatedState(onExited)
    val entered by rememberUpdatedState(onEntered)
    LaunchedEffect(enabled, exiting, exitMotion, hostFade) {
        val navigating = exiting && exitMotion == StatsExitMotion.Navigate
        val targetOffset = if (navigating) offset.value else IntOffset(0, if (exiting) StatsPageOffsetPixels else 0)
        val targetOpacity = if (exiting) 0f else 1f
        val targetHostOpacity = if (exiting) 1f else 0f
        if (!enabled) {
            offset.snapTo(targetOffset)
            opacity.snapTo(targetOpacity)
            hostFade?.snapTo(targetHostOpacity)
            velocities.offset = IntOffset.Zero
            velocities.opacity = 0f
            velocities.hostOpacity = 0f
        } else coroutineScope {
            // Use the same typed pixel/alpha springs as SPW, without a fixed duration.
            // A new target cancels this scope and continues from the current values/velocity.
            launch {
                if (navigating) offset.snapTo(targetOffset)
                else offset.animateTo(targetOffset, spring(dampingRatio = 1f, stiffness = StatsPageSpringStiffness),
                    initialVelocity = velocities.offset) { velocities.offset = velocity }
                velocities.offset = IntOffset.Zero
            }
            launch {
                opacity.animateTo(targetOpacity, spring(dampingRatio = 1f, stiffness = StatsPageSpringStiffness),
                    initialVelocity = velocities.opacity) { velocities.opacity = velocity }
                velocities.opacity = 0f
            }
            launch {
                hostFade?.animateTo(targetHostOpacity, spring(dampingRatio = 1f, stiffness = StatsPageSpringStiffness),
                    initialVelocity = velocities.hostOpacity) { velocities.hostOpacity = velocity }
                velocities.hostOpacity = 0f
            }
        }
        // A canceled/reversed exit cannot acknowledge completion or replay navigation.
        if (exiting) finished() else entered()
    }
    Box(Modifier.fillMaxSize().clipToBounds().onPreviewKeyEvent { exiting }
        .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
            if (exiting) event.changes.forEach { it.consume() }
        }.graphicsLayer {
        alpha = opacity.value.coerceIn(0f, 1f)
        translationY = offset.value.y.toFloat()
    }) {
        CompositionLocalProvider(LocalStatsPageExiting provides exiting) { content() }
    }
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
    val pageExiting = LocalStatsPageExiting.current
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
        }) {
            CompositionLocalProvider(LocalStatsPageExiting provides (pageExiting || contentKey(shown) != contentKey(target))) {
                content(shown)
            }
        }
    }
}
