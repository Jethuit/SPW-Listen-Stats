package com.jethuit.spw.listenstats.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import com.jethuit.spw.listenstats.PluginLog
import com.xuncorp.voxzen.util.AppConfig
import java.nio.file.Files
import java.nio.file.Path

val LocalHostFontFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Default }

/** SPW 1.19.0's font selection and resource; no font is copied into the plugin package. */
object HostFont {
    private const val MISANS = "composeResources/voxzen.composeapp.generated.resources/font/misans.ttf"

    fun readBytes(strategy: String?, customPath: String?): ByteArray? = when (strategy) {
        "MiSansRegular" -> AppConfig::class.java.classLoader.getResourceAsStream(MISANS)?.use { it.readBytes() }
            ?: error("SPW MiSans resource is unavailable")
        "Custom" -> {
            require(!customPath.isNullOrBlank()) { "SPW custom font path is empty" }
            Files.readAllBytes(Path.of(customPath))
        }
        else -> null
    }

    fun load(strategy: String?, customPath: String?): FontFamily {
        return try {
            val bytes = readBytes(strategy, customPath) ?: return FontFamily.Default
            // Detect invalid custom files before Compose tries to render with them.
            if (strategy == "Custom") {
                java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, Path.of(customPath!!).toFile())
            }
            FontFamily(Font(identity = "spw-listenstats:$strategy:${customPath.orEmpty()}", data = bytes))
        } catch (failure: Exception) {
            PluginLog.error("loading SPW font failed; using system font", failure)
            FontFamily.Default
        }
    }
}
