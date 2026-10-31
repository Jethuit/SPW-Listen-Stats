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
import com.jethuit.spw.listenstats.ui.HostUiBridge
import com.jethuit.spw.listenstats.ui.HostUiRuntime
import com.jethuit.spw.listenstats.ui.HostUiIntegration
import com.jethuit.spw.listenstats.ui.HostBytecodeIndex
import com.jethuit.spw.listenstats.ui.StatsHostTransitionHooks
import com.jethuit.spw.listenstats.ui.UiPerformanceProbe

class MainPlugin(
    pluginContext: PluginContext
) : SpwPlugin(pluginContext) {
    private var integration: HostUiIntegration? = null

    override fun start() {
        UiPerformanceProbe.mark("plugin_start")
        PluginState.pluginPath = pluginContext.pluginPath
        PluginState.pluginId = pluginContext.pluginId
        PluginState.spwVersion = pluginContext.spwVersion
        PluginState.pluginVersion = pluginContext.pluginVersion
        PluginLog.info("start plugin ${pluginContext.pluginVersion} for SPW ${pluginContext.spwVersion}")
        com.jethuit.spw.listenstats.data.StatsStore.initialize(pluginContext.pluginPath)
        com.jethuit.spw.listenstats.data.PlaybackTracker.initialize()
        closeHooks()
        com.jethuit.spw.listenstats.data.StatsSnapshots.start()
        UiPerformanceProbe.mark("plugin_preload_started")
        val host = WorkshopApi.instance.javaClass
        integration = HostUiIntegration(
            source = {
                checkUiPermission()
                HostBytecodeIndex.source(host).also { PluginLog.info("Host bytecode source: $it") }
            },
            loader = host.classLoader,
            install = ::installHostUi,
            failed = { failure ->
                PluginLog.error("Structural host UI discovery/installation failed", failure)
                WorkshopApi.ui.toast("听歌记录已启用，但统计页面接入失败，请在插件配置中写入诊断日志",
                    WorkshopApi.Ui.ToastType.Warning)
            },
            report = { status -> PluginState.discoveryStatus = status; PluginLog.info("Host UI discovery: $status") }
        ).also { it.start() }
    }

    private fun checkUiPermission() {
        check(WorkshopApi.manager.isPermissionGranted(com.xuncorp.spw.workshop.api.PluginPermission.CLASS_TRANSFORM)) {
            "请授予类变换权限后重新启用插件"
        }
    }

    private fun installHostUi(bridge: HostUiBridge): AutoCloseable {
        checkUiPermission()
        val handles = mutableListOf<AutoCloseable>()
        fun release() {
            PluginState.registeredHooks = emptySet()
            handles.asReversed().forEach { handle ->
                runCatching { handle.close() }.onFailure { PluginLog.error("Removing host UI hook failed", it) }
            }
            handles.clear()
        }
        try {
            handles += HostUiRuntime.activate(bridge)
            handles += StatsHostTransitionHooks.register { method, parameters, callback ->
                WorkshopApi.hookRegistrar.hookMethod(StatsHostTransitionHooks.TARGET_CLASS, method, parameters, callback)
            }
            handles += StatsNavigationHooks.register { method, parameters, callback ->
                WorkshopApi.hookRegistrar.hookMethod(bridge.targets.navigation.navigate.className, method, parameters, callback)
            }
            handles += StatsPageHook.register(bridge) { target, method, caller, parameters, content ->
                WorkshopApi.hookRegistrar.replaceComposable(target, method, caller, parameters, 50, content)
            }
            PluginState.registeredHooks = setOf("page", "navigation", "page-transition", "page-isolation")
            PluginLog.info("Structurally discovered UI registered: ${bridge.targets}")
            try {
                handles += StatsTitlebarEntry.register { target, method, caller, parameters, content ->
                    WorkshopApi.hookRegistrar.afterComposable(target, method, caller, parameters, 50, content)
                }
                PluginState.registeredHooks += "titlebar"
            } catch (failure: Throwable) {
                PluginLog.error("Optional titlebar entry unavailable", failure)
                WorkshopApi.ui.toast("顶部统计按钮加载失败，仍可从插件配置打开", WorkshopApi.Ui.ToastType.Warning)
            }
            WorkshopApi.ui.toast("听歌统计已启用", WorkshopApi.Ui.ToastType.Success)
        } catch (failure: Throwable) {
            release()
            throw failure
        }
        return AutoCloseable { release() }
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
        com.jethuit.spw.listenstats.data.StatsSnapshots.stop()
        integration?.close()
        integration = null
        PluginState.discoveryStatus = "stopped"
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
                        appendLine("databaseCandidates=${com.jethuit.spw.listenstats.data.StatsStore.diagnosticCandidates()}")
                        appendLine("sourceId=$source; registeredHooks=${PluginState.registeredHooks} (registration only)")
                        appendLine("hostUiDiscovery=${PluginState.discoveryStatus}; hostUiTargets=${HostUiRuntime.current?.targets ?: "unavailable"}")
                        appendLine("materialBridgeActive=${com.jethuit.spw.listenstats.ui.StatsHostState.materialBridgeAvailable}; pageIsolationHook=${HostUiRuntime.current?.targets?.layer ?: "unavailable"}")
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
