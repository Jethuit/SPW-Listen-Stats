package com.jethuit.spw.listenstats

import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper

data class DisplayPreferences(
    val rankingPageSize: Int = 25,
    val pageAnimations: Boolean = true,
    val refreshIntervalSeconds: Int = 5,
    val detailVisibleRows: Int = 6,
    val showDetailHint: Boolean = true,
    val heatmapAnimations: Boolean = true,
    val overviewLatinDurationUnits: Boolean = false,
    val detailLatinDurationUnits: Boolean = false
)

@OptIn(UnstableSpwWorkshopApi::class)
object Prefs {
    private var saveFailureReported = false
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
        saveFailureReported = false
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

    /** Update reminders; retain the dev.2 configuration key so an existing choice is preserved. */
    @Synchronized
    fun microsoftStoreEdition(): Boolean? = try {
        val config = helper
        if (config == null || !config.reload()) null else config.get("microsoftStoreEdition", false)
    } catch (failure: Exception) {
        PluginLog.error("reading Microsoft Store preference failed", failure)
        null
    }

    @Synchronized
    fun setMicrosoftStoreEdition(enabled: Boolean): Boolean {
        val config = helper ?: return false
        return try {
            if (!config.reload()) return false
            config.set("microsoftStoreEdition", enabled)
            config.save().also { saved ->
                if (!saved) PluginLog.error("saving Microsoft Store preference failed")
            }
        } catch (failure: Exception) {
            PluginLog.error("saving Microsoft Store preference failed", failure)
            false
        }
    }

    /** Host preference controls may save through another cached ConfigHelper. */
    @Synchronized
    fun sidebarEntry(): Boolean? = try {
        val config = helper
        if (config == null || !config.reload()) null else config.get("sidebarEntry", false)
    } catch (_: Exception) { null }

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
        fun readBoolean(key: String, default: Boolean = true): Boolean = try { helper?.get(key, default) ?: default }
            catch (_: Exception) { default }
        fun readBoundedInt(key: String, default: Int, range: IntRange): Int =
            getString(key, default.toString()).trim().toIntOrNull()?.takeIf { it in range } ?: default
        return DisplayPreferences(
            rankingPageSize = pageSize,
            pageAnimations = readBoolean("pageAnimations"),
            refreshIntervalSeconds = readBoundedInt("refreshIntervalSeconds", 5, 2..60),
            detailVisibleRows = readBoundedInt("detailVisibleRows", 6, 3..12),
            showDetailHint = readBoolean("showDetailHint"),
            heatmapAnimations = readBoolean("heatmapAnimations"),
            overviewLatinDurationUnits = readBoolean("overviewLatinDurationUnits", false),
            detailLatinDurationUnits = readBoolean("detailLatinDurationUnits", false)
        )
    }

    @Synchronized
    fun putString(key: String, value: String) {
        val saved = try {
            val config = checkNotNull(helper) { "配置尚未就绪" }
            check(config.reload()) { "配置重新读取失败" }
            config.set(key, value)
            check(config.save()) { "宿主未保存配置" }
            true
        } catch (failure: Exception) {
            PluginLog.error("saving preference $key failed", failure)
            false
        }
        if (!saved && !saveFailureReported) {
            javax.swing.SwingUtilities.invokeLater {
                runCatching { WorkshopApi.ui.toast("偏好设置保存失败，本次选择可能无法在下次打开时恢复",
                    WorkshopApi.Ui.ToastType.Warning) }
            }
        }
        saveFailureReported = !saved
    }
}
