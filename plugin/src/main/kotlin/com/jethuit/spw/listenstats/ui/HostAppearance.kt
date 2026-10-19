package com.jethuit.spw.listenstats.ui

import com.xuncorp.voxzen.util.AppConfig

/**
 * Host appearance values are not part of the Workshop API. Keep every read
 * defensive so a renamed/removed host field falls back to the plugin palette.
 */
data class HostAppearance(
    val windowStyle: String? = null,
    val dark: Boolean? = null,
    val fontStrategy: String? = null,
    val customFontPath: String? = null
)

object HostAppearanceReader {
    fun read(): HostAppearance {
        return try {
            HostAppearance(
                windowStyle = AppConfig.spwWindowStyle.name,
                dark = when (AppConfig.lightDarkTheme.name) {
                    "Light" -> false
                    "Dark" -> true
                    else -> null
                },
                fontStrategy = runCatching { AppConfig.fontStrategy.name }.getOrNull(),
                customFontPath = runCatching { AppConfig.customFontFilePath }.getOrNull()
            )
        } catch (failure: Throwable) {
            HostAppearance()
        }
    }
}
