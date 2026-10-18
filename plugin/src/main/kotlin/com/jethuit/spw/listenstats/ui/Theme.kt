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

enum class ThemeMode(val storage: String) {
    System("system"),
    Light("light"),
    Dark("dark");

    fun next(): ThemeMode = when (this) {
        System -> Light
        Light -> Dark
        Dark -> System
    }

    companion object {
        fun fromStorage(value: String?): ThemeMode =
            entries.firstOrNull { it.storage == value } ?: System
    }
}

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
    val danger: Color
)

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
    tooltipBackground = Color(0xFF25272B),
    tooltipText = Color(0xFFFFFFFF),
    danger = Color(0xFFCF4545)
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
    tooltipBackground = Color(0xFFEEEEEF),
    tooltipText = Color(0xFF202124),
    danger = Color(0xFFFF8A8A)
)

val LocalStatsColors = staticCompositionLocalOf { LightStatsColors }

@Composable
fun StatsTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    var systemDark by remember { mutableStateOf(false) }
    LaunchedEffect(mode) {
        if (mode == ThemeMode.System) {
            while (true) {
                systemDark = withContext(Dispatchers.IO) { readSystemDarkTheme() }
                delay(3_000)
            }
        }
    }
    val dark = when (mode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val colors = if (dark) DarkStatsColors else LightStatsColors
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
    CompositionLocalProvider(LocalStatsColors provides colors) {
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
