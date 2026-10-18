package com.jethuit.spw.listenstats.data

import com.jethuit.spw.listenstats.PluginLog
import com.jethuit.spw.listenstats.PluginState
import java.nio.file.Path

object StatsStore {
    @Volatile
    private var databasePath: Path? = null

    fun initialize(pluginPath: String?) {
        databasePath = resolveDatabasePath(pluginPath)
        PluginLog.info("listening database path: $databasePath")
    }

    fun load(): StatsData {
        val path = databasePath ?: resolveDatabasePath(PluginState.pluginPath)
        if (path == null) {
            return StatsData.empty(error = "无法定位播放器数据目录")
        }
        databasePath = path
        return BackupService.merge(HostDatabase.load(path))
    }

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
