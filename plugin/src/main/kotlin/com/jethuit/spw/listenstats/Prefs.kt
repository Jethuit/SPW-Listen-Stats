package com.jethuit.spw.listenstats

import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import com.jethuit.spw.listenstats.data.HeatmapScale

data class DisplayPreferences(
    val dailyHighMillis: Long = HeatmapScale.DEFAULT_HIGH_MILLIS,
    val rankingPageSize: Int = 25,
    val pageAnimations: Boolean = true
)

@OptIn(UnstableSpwWorkshopApi::class)
object Prefs {
    private var helper: ConfigHelper? = null
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "ListenStats-Preferences").apply { isDaemon = true }
    }

    /** Preserve callback order without doing config IO or waiting for its lock on the UI thread. */
    fun putStringAsync(key: String, value: String) {
        writer.execute { putString(key, value) }
    }

    @Synchronized
    fun initialize() {
        helper = try {
            WorkshopApi.manager.createConfigManager().getConfig("config.json").also { it.reload() }
        } catch (failure: Throwable) {
            PluginLog.error("config initialization failed", failure)
            null
        }
    }

    @Synchronized
    fun getString(key: String, fallback: String): String {
        return try {
            helper?.get(key, fallback) ?: fallback
        } catch (failure: Throwable) {
            fallback
        }
    }

    /** Host preference controls may save through another cached ConfigHelper. */
    @Synchronized
    fun displayPreferences(): DisplayPreferences {
        try {
            helper?.reload()
        } catch (failure: Throwable) {
            PluginLog.error("reloading display preferences failed", failure)
        }
        val pageSize = getString("rankingPageSize", "25").trim().toIntOrNull()
            ?.takeIf { it > 0 } ?: 25
        val animations = try { helper?.get("pageAnimations", true) ?: true }
            catch (_: Throwable) { true }
        return DisplayPreferences(
            HeatmapScale.parseHours(getString(HeatmapScale.PREFERENCE_KEY, HeatmapScale.DEFAULT_HOURS)),
            pageSize, animations
        )
    }

    fun dailyHighMillis(): Long = displayPreferences().dailyHighMillis

    @Synchronized
    fun putString(key: String, value: String) {
        try {
            val config = helper ?: return
            // Merge our UI selection into the latest settings rather than writing
            // an older snapshot over the user's new heatmap threshold.
            if (!config.reload()) {
                PluginLog.error("reloading preferences before saving $key failed")
                return
            }
            config.set(key, value)
            config.save()
        } catch (failure: Throwable) {
            PluginLog.error("saving preference $key failed", failure)
        }
    }
}
