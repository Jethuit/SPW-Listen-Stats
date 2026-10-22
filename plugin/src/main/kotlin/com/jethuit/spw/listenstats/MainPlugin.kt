@file:OptIn(UnstableSpwWorkshopApi::class)
@file:Suppress("unused")

package com.jethuit.spw.listenstats

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.jethuit.spw.listenstats.ui.StatsNavigationHooks
import com.jethuit.spw.listenstats.ui.StatsTitlebarEntry
import com.jethuit.spw.listenstats.ui.StatsPageHook
import com.jethuit.spw.listenstats.ui.HostUiTargets

class MainPlugin(
    pluginContext: PluginContext
) : SpwPlugin(pluginContext) {
    private var hookHandle: AutoCloseable? = null
    private var navigationHandle: AutoCloseable? = null
    private var titlebarHandle: AutoCloseable? = null

    override fun start() {
        PluginState.pluginPath = pluginContext.pluginPath
        PluginState.pluginId = pluginContext.pluginId
        PluginState.spwVersion = pluginContext.spwVersion
        PluginState.pluginVersion = pluginContext.pluginVersion
        PluginLog.info("start plugin ${pluginContext.pluginVersion} for SPW ${pluginContext.spwVersion}")
        com.jethuit.spw.listenstats.data.StatsStore.initialize(pluginContext.pluginPath)
        com.jethuit.spw.listenstats.data.PlaybackTracker.initialize()
        closeHooks()
        try {
            navigationHandle = StatsNavigationHooks.register { method, parameters, callback ->
                WorkshopApi.hookRegistrar.hookMethod(
                    className = StatsNavigationHooks.NAVIGATOR_CLASS,
                    methodName = method,
                    parameterTypes = parameters,
                    callback = callback
                )
            }
            hookHandle = StatsPageHook.register(pluginContext.spwVersion) { target, method, caller, parameters, content ->
                WorkshopApi.hookRegistrar.replaceComposable(target, method, caller, parameters, 50, content)
            }
            PluginLog.info("Containing Salt Layer and Navigator hooks registered; pageCaller=${HostUiTargets.forVersion(pluginContext.spwVersion).pageCaller}")
        } catch (failure: Throwable) {
            closeHooks()
            PluginLog.error("Containing page Layer hook failed", failure)
            WorkshopApi.ui.toast(
                "听歌记录已启用，但统计页面加载失败，请在插件配置中写入诊断日志",
                WorkshopApi.Ui.ToastType.Warning
            )
        }
        if (hookHandle != null) {
            try {
                titlebarHandle = StatsTitlebarEntry.register { target, method, caller, parameters, content ->
                    WorkshopApi.hookRegistrar.afterComposable(
                        className = target,
                        methodName = method,
                        inClass = caller,
                        parameterTypes = parameters,
                        priority = 50,
                        content = content
                    )
                }
                PluginLog.info("Titlebar statistics entry registered before Quick")
            } catch (failure: Throwable) {
                PluginLog.error("Optional titlebar entry unavailable; keeping the statistics page hook", failure)
                WorkshopApi.ui.toast(
                    "顶部统计按钮加载失败，仍可从插件配置打开",
                    WorkshopApi.Ui.ToastType.Warning
                )
            }
        }
        PluginState.registeredHooks = buildSet {
            if (hookHandle != null) add("page")
            if (navigationHandle != null) add("navigation")
            if (hookHandle != null) add("page-isolation")
            if (titlebarHandle != null) add("titlebar")
        }
        if (hookHandle != null) {
            WorkshopApi.ui.toast("听歌统计已启用", WorkshopApi.Ui.ToastType.Success)
        }
    }

    override fun stop() {
        PluginLog.info("stop plugin")
        try {
            closeHooks()
        } finally {
            com.jethuit.spw.listenstats.data.PlaybackTracker.shutdown()
            javax.swing.SwingUtilities.invokeLater {
                com.jethuit.spw.listenstats.ui.StatsHostState.close()
            }
            com.jethuit.spw.listenstats.ui.StatsWindow.close()
            com.jethuit.spw.listenstats.data.CoverStore.clear()
        }
    }

    override fun delete() {
        PluginLog.info("delete plugin")
    }

    override fun update() {
        PluginLog.info("update plugin")
    }

    private fun closeHooks() {
        PluginState.registeredHooks = emptySet()
        val pending = listOfNotNull(titlebarHandle, hookHandle, navigationHandle)
        titlebarHandle = null
        hookHandle = null
        navigationHandle = null
        for (handle in pending) {
            runCatching { handle.close() }.onFailure {
                PluginLog.error("removing UI/navigation hooks failed", it)
            }
        }
    }

    companion object {
        @JvmStatic
        fun openStats() {
            PluginLog.info("openStats requested from preference")
            javax.swing.SwingUtilities.invokeLater {
                StatsNavigationHooks.openStats()
            }
        }

        @JvmStatic
        fun writeDiagnostics() {
            Thread({
                val result = runCatching {
                    val dir = com.jethuit.spw.listenstats.data.DataFiles.directory()
                    val permissions = com.xuncorp.spw.workshop.api.PluginPermission.values().associate { permission ->
                        permission.name to runCatching { WorkshopApi.manager.isPermissionGranted(permission) }
                            .fold({ it.toString() }, { "unavailable" })
                    }
                    val source = runCatching { java.nio.file.Files.readString(dir.resolve("source-id.txt")).trim() }
                        .getOrDefault("unavailable")
                    PluginLog.diagnostics(buildString {
                        appendLine("plugin=${PluginState.pluginVersion}; spw=${PluginState.spwVersion}")
                        appendLine("java=${System.getProperty("java.version")}; os=${System.getProperty("os.name")}")
                        appendLine("zone=${java.time.ZoneId.systemDefault()}; permissions=$permissions")
                        appendLine("dataDirectory=$dir; database=${com.jethuit.spw.listenstats.data.StatsStore.diagnosticPath()}")
                        appendLine("sourceId=$source; registeredHooks=${PluginState.registeredHooks} (registration only)")
                        appendLine("hostUiTargets=${runCatching { HostUiTargets.forVersion(PluginState.spwVersion) }.getOrNull() ?: "unsupported"}")
                        appendLine("materialBridgeActive=${com.jethuit.spw.listenstats.ui.StatsHostState.materialBridgeAvailable}; pageIsolationHook=${StatsPageHook.TARGET_CLASS}.${StatsPageHook.TARGET_METHOD}")
                        appendLine("navigationReady=${StatsNavigationHooks.navigatorReady}; statisticsVisible=${com.jethuit.spw.listenstats.ui.StatsHostState.visible}")
                        appendLine("${com.jethuit.spw.listenstats.data.PlaybackTracker.diagnostics()}")
                        append("storageError=${com.jethuit.spw.listenstats.data.LiveStore.storageError() ?: "none"}")
                    })
                }
                javax.swing.SwingUtilities.invokeLater {
                    val file = result.getOrNull()
                    WorkshopApi.ui.toast(if (file != null) "诊断日志已写入：${file.absolutePath}" else "诊断日志写入失败，请查看宿主日志",
                        if (file != null) WorkshopApi.Ui.ToastType.Success else WorkshopApi.Ui.ToastType.Warning)
                }
            }, "ListenStats-Diagnostics").apply { isDaemon = true }.start()
        }
    }
}
