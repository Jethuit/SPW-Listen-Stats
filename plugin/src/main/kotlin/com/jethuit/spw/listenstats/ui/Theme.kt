package com.jethuit.spw.listenstats.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skiko.SystemTheme
import org.jetbrains.skiko.currentSystemTheme

data class StatsColors(
    val isDark: Boolean,
    val background: Color,
    val text: Color,
    val muted: Color,
    val line: Color,
    val subtle: Color,
    val hover: Color,
    val accent: Color,
    val accentSoft: Color,
    val cells: List<Color>,
    val tooltipBackground: Color,
    val tooltipText: Color,
    val danger: Color,
    val pageBackground: Color = background,
    val controlBackground: Color = subtle,
    val cardBackground: Color = background,
    val selectedControlBackground: Color = background,
    val rankingRowHover: Color = subtle
)

// Sampled from the official SPW classic settings cards, for both theme modes.
private val ClassicLightCardBackground = Color(0xFFFCFCFC)
private val ClassicDarkCardBackground = Color(0xFF2A2A2A)

val LightStatsColors = StatsColors(
    isDark = false,
    background = Color(0xFFFFFFFF),
    text = Color(0xFF25272B),
    muted = Color(0xFF777B82),
    line = Color(0xFFE9EAED),
    subtle = Color(0xFFF5F6F8),
    hover = Color(0xFFF1F3F6),
    accent = Color(0xFF2777D8),
    accentSoft = Color(0x132777D8),
    cells = listOf(
        Color(0xFFF0F1F3),
        Color(0xFFD7E9FD),
        Color(0xFF9CC9FB),
        Color(0xFF559EEC),
        Color(0xFF1769BD)
    ),
    tooltipBackground = ClassicLightCardBackground,
    tooltipText = Color(0xFF25272B),
    danger = Color(0xFFCF4545),
    pageBackground = Color(0xFFF8F8F8),
    controlBackground = Color(0xFFEDEEF1),
    cardBackground = ClassicLightCardBackground,
    rankingRowHover = Color(0xFFE8E8E8)
)

val DarkStatsColors = StatsColors(
    isDark = true,
    background = Color(0xFF202124),
    text = Color(0xFFEDEDEE),
    muted = Color(0xFFA0A3AB),
    line = Color(0xFF37393E),
    subtle = Color(0xFF2B2D32),
    hover = Color(0xFF34363C),
    accent = Color(0xFF79B5FF),
    accentSoft = Color(0x1779B5FF),
    cells = listOf(
        Color(0xFF2D3036),
        Color(0xFF3F78AE),
        Color(0xFF5799D6),
        Color(0xFF7AB7EC),
        Color(0xFFA5D4FF)
    ),
    tooltipBackground = ClassicDarkCardBackground,
    tooltipText = Color(0xFFEDEDEE),
    danger = Color(0xFFFF8A8A),
    pageBackground = Color(0xFF232323),
    cardBackground = ClassicDarkCardBackground,
    rankingRowHover = Color(0xFF363637)
)

val LocalStatsColors = staticCompositionLocalOf { LightStatsColors }
internal val LocalStatsMaterialBridge = staticCompositionLocalOf { false }
internal val LocalHostAppearance = staticCompositionLocalOf<HostAppearance?> { null }

/** Kept alive by the containing page layer, including while statistics is closed. */
@Composable
internal fun rememberHostAppearance(initial: HostAppearance = HostAppearance(),
    read: () -> HostAppearance = HostAppearanceReader::read): HostAppearance {
    var appearance by remember(read) { mutableStateOf(initial) }
    LaunchedEffect(read) {
        while (true) {
            appearance = withContext(Dispatchers.IO) { read() }
            delay(1_000)
        }
    }
    return appearance
}

/** DWM belongs to the host window. Transparent page paint is safe only behind an active wrapper. */
fun materialStatsColors(base: StatsColors, windowStyle: String?,
                        inheritMaterial: Boolean): StatsColors {
    if (!inheritMaterial ||
        windowStyle !in setOf("WindowsAcrylic", "WindowsMica", "WindowsMicaAlt")) return base
    // The supplied light Acrylic captures have a #DDDDDD inactive backdrop and
    // #E2E2E2 card interiors. A light tint preserves the native focused backdrop
    // as well; never replace DWM's focus-dependent background with a fixed RGB.
    if (windowStyle == "WindowsAcrylic" && !base.isDark) {
        return base.copy(
            pageBackground = Color.Transparent,
            text = Color(0xFF1E1715),
            muted = Color.Black.copy(alpha = 0.56f),
            cardBackground = Color.White.copy(alpha = 0.16f),
            // Fitted against native focused borders and the #C7C7C7 inactive border.
            line = Color(0xFF808080).copy(alpha = 0.28f),
            controlBackground = Color.Black.copy(alpha = 0.045f),
            selectedControlBackground = Color.White.copy(alpha = 0.60f),
            subtle = Color.Black.copy(alpha = 0.04f),
            hover = Color.Black.copy(alpha = 0.075f),
            // Native Acrylic row: #DDDDDD backdrop -> #C2C2C2 on hover.
            rankingRowHover = Color.Black.copy(alpha = 0.12f),
            cells = listOf(Color.Black.copy(alpha = 0.055f)) + base.cells.drop(1)
        )
    }
    if (base.isDark) {
        // Fitted to the user's native dark captures, retaining the real backdrop:
        // Acrylic #505050 -> card #5F5F5F; Mica / Mica Alt #272727 -> #444444.
        val surfaceTint = Color(0xFF7C7C7C).copy(alpha = 0.34f)
        return base.copy(
            pageBackground = Color.Transparent,
            text = Color(0xFFEBEEF1),
            muted = Color(0xFFCCD0D5).copy(alpha = 0.91f),
            cardBackground = surfaceTint,
            controlBackground = surfaceTint,
            selectedControlBackground = Color.White.copy(alpha = 0.10f),
            // Borders over the card composite to #737375 / #616163.
            line = Color(0xFF9B9BA1).copy(alpha = 1f / 3f),
            subtle = Color.White.copy(alpha = 0.08f),
            hover = Color.White.copy(alpha = 0.08f),
            // Unlike the former dark fill, native hovered rows become lighter:
            // Acrylic #676769, Mica #515153 on the supplied backgrounds.
            rankingRowHover = Color(0xFF828286).copy(alpha = 0.46f),
            cells = listOf(Color.White.copy(alpha = 0.055f)) + base.cells.drop(1)
        )
    }
    return base.copy(
        pageBackground = Color.Transparent,
        cardBackground = base.cardBackground.copy(alpha = 0.72f),
        controlBackground = base.controlBackground.copy(alpha = 0.72f),
        subtle = base.subtle.copy(alpha = 0.55f),
        hover = base.hover.copy(alpha = 0.65f),
        // Native light Mica: #F5F5F5 -> #E1E1E1; Mica Alt: #EDEDED -> #DADADA.
        rankingRowHover = Color.Black.copy(alpha = 0.08f)
    )
}

/** SPW's explicit light/dark selection takes precedence over the OS fallback. */
fun hostStatsColors(hostDark: Boolean?, systemDark: Boolean): StatsColors =
    if (hostDark ?: systemDark) DarkStatsColors else LightStatsColors

@Composable
fun StatsTheme(inheritMaterial: Boolean = false, content: @Composable () -> Unit) {
    val inheritedAppearance = LocalHostAppearance.current
    val hostAppearance = if (inheritedAppearance != null) inheritedAppearance else rememberHostAppearance()
    var systemDark by remember { mutableStateOf(false) }
    var hostFont by remember { mutableStateOf<androidx.compose.ui.text.font.FontFamily>(androidx.compose.ui.text.font.FontFamily.Default) }
    LaunchedEffect(hostAppearance.fontStrategy, hostAppearance.customFontPath) {
        hostFont = withContext(Dispatchers.IO) {
            HostFont.load(hostAppearance.fontStrategy, hostAppearance.customFontPath)
        }
    }
    LaunchedEffect(hostAppearance.dark) {
        if (hostAppearance.dark == null) {
            while (true) {
                systemDark = withContext(Dispatchers.IO) { readSystemDarkTheme() }
                delay(1_000)
            }
        }
    }
    val reactiveDark = readHostDarkTheme()
    val baseColors = hostStatsColors(reactiveDark ?: hostAppearance.dark, systemDark)
    val dark = baseColors.isDark
    val colors = materialStatsColors(baseColors, hostAppearance.windowStyle, inheritMaterial)
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.background,
            background = colors.background,
            onBackground = colors.text,
            surface = colors.background,
            onSurface = colors.text,
            surfaceVariant = colors.subtle,
            onSurfaceVariant = colors.muted,
            outline = colors.line,
            error = colors.danger
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = Color.White,
            background = colors.background,
            onBackground = colors.text,
            surface = colors.background,
            onSurface = colors.text,
            surfaceVariant = colors.subtle,
            onSurfaceVariant = colors.muted,
            outline = colors.line,
            error = colors.danger
        )
    }
    CompositionLocalProvider(LocalStatsColors provides colors, LocalHostFontFamily provides hostFont) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

@OptIn(androidx.compose.runtime.InternalComposeApi::class)
@Composable
private fun readHostDarkTheme(): Boolean? {
    val composer = androidx.compose.runtime.currentComposer
    return runCatching { HostUiRuntime.current?.dark(composer) }.getOrNull()
}

private fun readSystemDarkTheme(): Boolean {
    return runCatching { currentSystemTheme == SystemTheme.DARK }.getOrDefault(false)
}
