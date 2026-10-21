package com.jethuit.spw.listenstats.data

import com.jethuit.spw.listenstats.PluginLog
import com.jethuit.spw.listenstats.PluginState
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.nio.file.Path

object StatsStore {
    @Volatile
    private var databasePath: Path? = null

    fun initialize(pluginPath: String?) {
        databasePath = resolveDatabasePath(pluginPath)
        PluginLog.info("listening database path: $databasePath")
    }

    fun load(): StatsData {
        val allowed = runCatching {
            WorkshopApi.manager.isPermissionGranted(PluginPermission.LIBRARY_READ)
        }.getOrDefault(false)
        if (!allowed) return StatsData.empty(error = "请先授权曲库读取权限")
        val path = databasePath?.takeIf { java.nio.file.Files.isRegularFile(it) }
            ?: resolveDatabasePath(PluginState.pluginPath)
        if (path == null) {
            return StatsData.empty(error = "无法定位播放器数据目录")
        }
        databasePath = path
        val host = HostDatabase.load(path, aggregate = false)
        return try {
            val merged = BackupService.mergeWithLive(host)
            LiveStore.storageError()?.let { merged.copy(error = it) } ?: merged
        } catch (failure: Exception) {
            PluginLog.error("loading statistics failed", failure)
            host.copy(error = "统计数据读取失败：${failure.message}")
        }
    }

    fun diagnosticPath(): String = databasePath?.toString() ?: "unresolved"

    private fun resolveDatabasePath(pluginPath: String?): Path? {
        if (!pluginPath.isNullOrBlank()) {
            val pluginDirectory = runCatching { Path.of(pluginPath) }.getOrNull()
            val appData = pluginDirectory?.parent?.parent?.parent
            if (appData != null) {
                val candidate = appData.resolve("spw.db")
                if (java.nio.file.Files.isRegularFile(candidate)) {
                    return candidate
                }
            }
        }
        val appData = System.getenv("APPDATA")?.let { Path.of(it, "Salt Player for Windows", "spw.db") }
        if (appData != null && java.nio.file.Files.isRegularFile(appData)) {
            return appData
        }
        return appData
    }
}
