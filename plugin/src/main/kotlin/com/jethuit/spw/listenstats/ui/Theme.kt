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
    val rankingRowHover: Color = subtle,
    val separatorLine: Color = line
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
    cardBackground = ClassicLightCardBackground
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
        Color(0xFF27425E),
        Color(0xFF356A9D),
        Color(0xFF599BDB),
        Color(0xFF8AC5FF)
    ),
    tooltipBackground = ClassicDarkCardBackground,
    tooltipText = Color(0xFFEDEDEE),
    danger = Color(0xFFFF8A8A),
    pageBackground = Color(0xFF232323),
    cardBackground = ClassicDarkCardBackground
)

val LocalStatsColors = staticCompositionLocalOf { LightStatsColors }

/** DWM belongs to the host window. Transparent page paint is safe only behind an active wrapper. */
fun materialStatsColors(base: StatsColors, windowStyle: String?,
                        inheritMaterial: Boolean): StatsColors {
    if (!inheritMaterial ||
        windowStyle !in setOf("WindowsAcrylic", "WindowsMica", "WindowsMicaAlt")) return base
    // The supplied light Acrylic captures have a #DDDDDD inactive backdrop and
    // #E2E2E2 card interiors. A light tint preserves the native focused backdrop
    // as well; never replace DWM's focus-dependent background with a fixed RGB.
    // Dark Acrylic has no reference capture yet and retains the dev.2 palette.
    if (windowStyle == "WindowsAcrylic" && !base.isDark) {
        return base.copy(
            pageBackground = Color.Transparent,
            text = Color(0xFF1E1715),
            muted = Color.Black.copy(alpha = 0.56f),
            cardBackground = Color.White.copy(alpha = 0.16f),
            // Fitted against native focused borders and the #C7C7C7 inactive border.
            line = Color(0xFF808080).copy(alpha = 0.28f),
            separatorLine = Color(0xFF808080).copy(alpha = 0.28f),
            controlBackground = Color.Black.copy(alpha = 0.045f),
            selectedControlBackground = Color.White.copy(alpha = 0.60f),
            subtle = Color.Black.copy(alpha = 0.04f),
            hover = Color.Black.copy(alpha = 0.075f),
            // Native Acrylic row: #DDDDDD backdrop -> #C2C2C2 on hover.
            rankingRowHover = Color.Black.copy(alpha = 0.12f),
            cells = listOf(Color.Black.copy(alpha = 0.055f)) + base.cells.drop(1)
        )
    }
    return base.copy(
        pageBackground = Color.Transparent,
        cardBackground = base.cardBackground.copy(alpha = 0.72f),
        controlBackground = base.controlBackground.copy(alpha = 0.72f),
        subtle = base.subtle.copy(alpha = 0.55f),
        hover = base.hover.copy(alpha = 0.65f),
        rankingRowHover = base.subtle.copy(alpha = 0.55f),
        // User selected the native drop-target outline gray, rather than the
        // lighter card border. Keep layout separators independent of borders.
        separatorLine = if (windowStyle in setOf("WindowsMica", "WindowsMicaAlt") && !base.isDark)
            Color(0xFFB1B1B1) else base.line
    )
}

/** SPW's explicit light/dark selection takes precedence over the OS fallback. */
fun hostStatsColors(hostDark: Boolean?, systemDark: Boolean): StatsColors =
    if (hostDark ?: systemDark) DarkStatsColors else LightStatsColors

@Composable
fun StatsTheme(inheritMaterial: Boolean = false, content: @Composable () -> Unit) {
    var hostAppearance by remember { mutableStateOf(HostAppearance()) }
    var systemDark by remember { mutableStateOf(false) }
    var hostFont by remember { mutableStateOf<androidx.compose.ui.text.font.FontFamily>(androidx.compose.ui.text.font.FontFamily.Default) }
    LaunchedEffect(hostAppearance.fontStrategy, hostAppearance.customFontPath) {
        hostFont = withContext(Dispatchers.IO) {
            HostFont.load(hostAppearance.fontStrategy, hostAppearance.customFontPath)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            val appearance = withContext(Dispatchers.IO) { HostAppearanceReader.read() }
            hostAppearance = appearance
            if (appearance.dark == null) {
                systemDark = withContext(Dispatchers.IO) { readSystemDarkTheme() }
            }
            delay(1_000)
        }
    }
    val baseColors = hostStatsColors(hostAppearance.dark, systemDark)
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

private fun readSystemDarkTheme(): Boolean {
    return try {
        val process = Runtime.getRuntime().exec(
            arrayOf(
                "reg", "query",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "/v", "AppsUseLightTheme"
            )
        )
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        output.contains("0x0")
    } catch (failure: Throwable) {
        false
    }
}
