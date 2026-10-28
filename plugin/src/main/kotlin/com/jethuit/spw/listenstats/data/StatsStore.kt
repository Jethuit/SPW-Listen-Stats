package com.jethuit.spw.listenstats.data

import com.jethuit.spw.listenstats.PluginLog
import com.jethuit.spw.listenstats.PluginState
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.nio.file.Files
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
        val path = databasePath?.takeIf { Files.isRegularFile(it) }
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

    fun diagnosticCandidates(): String = databaseCandidates(PluginState.pluginPath).joinToString("; ") { path ->
        "$path (file=${Files.isRegularFile(path)}, readable=${Files.isReadable(path)})"
    }

    private fun resolveDatabasePath(pluginPath: String?): Path? =
        databaseCandidates(pluginPath).firstOrNull { Files.isRegularFile(it) }

    private fun databaseCandidates(pluginPath: String?): List<Path> {
        val os = System.getProperty("os.name", "")
        val windows = os.startsWith("Windows", ignoreCase = true)
        // SPW 1.19.2's database factory uses spw.db only on Windows; Linux uses sp.db.
        val filename = if (windows) "spw.db" else "sp.db"
        val candidates = linkedSetOf<Path>()
        if (!pluginPath.isNullOrBlank()) {
            val pluginDirectory = runCatching { Path.of(pluginPath) }.getOrNull()
            // Steam subscription caches add account, item and revision directories.
            // Check only ancestors of this plugin, rather than assuming three parents
            // or searching other installations and potentially mixing library IDs.
            generateSequence(pluginDirectory) { it.parent }
                .mapTo(candidates) { it.resolve(filename) }
        }
        val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        // These defaults match the host's own platform path resolver. In particular,
        // its Linux path is based on user.home, rather than XDG_DATA_HOME.
        val root = when {
            windows -> System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
                ?.resolve("Salt Player for Windows")
                ?: home?.resolve("AppData/Roaming/Salt Player for Windows")
            os.startsWith("Linux", ignoreCase = true) -> home?.resolve(".local/share/Salt Player")
            else -> null
        }
        root?.resolve(filename)?.let(candidates::add)
        return candidates.toList()
    }
}
