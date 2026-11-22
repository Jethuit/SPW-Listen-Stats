@file:OptIn(UnstableSpwWorkshopApi::class)
@file:Suppress("unused")

package com.jethuit.spw.listenstats

import com.jethuit.spw.listenstats.data.BackupDialogs
import com.jethuit.spw.listenstats.data.StatisticsStart
import com.jethuit.spw.listenstats.data.StatsSnapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import com.jethuit.spw.listenstats.ui.HookFailurePolicy
import com.jethuit.spw.listenstats.ui.HookHandles
import com.jethuit.spw.listenstats.ui.HostHookInstallation
import com.jethuit.spw.listenstats.ui.UiPerformanceProbe
import com.jethuit.spw.listenstats.ui.QueueNotificationHooks
import com.jethuit.spw.listenstats.ui.StatsEntryPlacement
import com.jethuit.spw.listenstats.ui.StatsSidebarEntry

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
        val uiStartup = kotlinx.coroutines.CompletableDeferred<Unit>()
        com.jethuit.spw.listenstats.data.StatsSnapshots.start(preloadAfter = uiStartup)
        StatsEntryPlacement.start()
        UiPerformanceProbe.mark("plugin_preload_started")
        val host = WorkshopApi.instance.javaClass
        integration = HostUiIntegration(
            source = {
                checkUiPermission()
                HostBytecodeIndex.source(host).also { PluginLog.info("Host bytecode source: $it") }
            },
            loader = host.classLoader,
            install = ::installHostUi,
            checkInstallAllowed = ::checkUiPermission,
            ready = {
                uiStartup.complete(Unit)
                val hooks = PluginState.registeredHooks
                val entryHint = when {
                    "titlebar" in hooks && "sidebar" in hooks -> null
                    "titlebar" in hooks -> "侧栏入口不可用，可从顶部统计图标或插件配置打开"
                    "sidebar" in hooks -> "顶部入口不可用，可从侧栏「统计」或插件配置打开"
                    else -> "顶部和侧栏入口不可用，请从插件配置打开"
                }
                WorkshopApi.ui.toast(entryHint?.let { "听歌统计已启用。$it" } ?: "听歌统计已启用",
                    if (entryHint == null) WorkshopApi.Ui.ToastType.Success else WorkshopApi.Ui.ToastType.Warning)
            },
            failed = { failure ->
                // Public playback recording and statistics preparation remain independent
                // of internal UI integration, including permission failures.
                uiStartup.complete(Unit)
                PluginLog.error("Structural host UI discovery/installation failed", failure)
                WorkshopApi.ui.toast("听歌记录已启用，但统计页面接入失败，请在插件配置中写入诊断日志",
                    WorkshopApi.Ui.ToastType.Warning)
            },
            report = { status -> PluginState.discoveryStatus = status; PluginLog.info("Host UI discovery: $status") }
        ).also { it.start() }
        com.jethuit.spw.listenstats.updates.Updates.start()
    }

    private fun checkUiPermission() {
        check(WorkshopApi.manager.isPermissionGranted(com.xuncorp.spw.workshop.api.PluginPermission.CLASS_TRANSFORM)) {
            "请授予类变换权限后重新启用插件"
        }
    }

    private fun installHostUi(bridge: HostUiBridge): AutoCloseable {
        val handles = HookHandles()
        val timings = linkedMapOf<String, Long>()
        fun <T> measure(group: String, action: () -> T): T {
            val started = System.nanoTime()
            try { return action() }
            finally { timings[group] = (System.nanoTime() - started) / 1_000_000 }
        }
        var complete = bridge.cacheable
        try {
            handles += HostUiRuntime.activate(bridge)
            handles += measure("transitions") {
                StatsHostTransitionHooks.register { method, parameters, callback ->
                    WorkshopApi.hookRegistrar.hookMethod(StatsHostTransitionHooks.TARGET_CLASS, method, parameters, callback)
                }
            }
            handles += measure("navigation") {
                StatsNavigationHooks.register { method, parameters, callback ->
                    WorkshopApi.hookRegistrar.hookMethod(bridge.targets.navigation.navigate.className, method, parameters, callback)
                }
            }
            handles += measure("page") {
                StatsPageHook.register(bridge) { target, method, caller, parameters, content ->
                    WorkshopApi.hookRegistrar.replaceComposable(target, method, caller, parameters, 50, content)
                }
            }
            PluginState.registeredHooks = setOf("page", "navigation", "page-transition", "page-isolation")
            // Full descriptors remain available in explicit diagnostics; keep startup logging compact.
            PluginLog.info("Core host UI hooks registered")
            try {
                handles += measure("titlebar") {
                    StatsTitlebarEntry.register { target, method, caller, parameters, content ->
                        WorkshopApi.hookRegistrar.afterComposable(target, method, caller, parameters, 50, content)
                    }
                }
                PluginState.registeredHooks += "titlebar"
            } catch (failure: Throwable) {
                if (HookFailurePolicy.noRetry(failure)) throw failure
                complete = false
                PluginLog.error("Optional titlebar entry unavailable", failure)
            }
            try {
                handles += measure("sidebar") {
                    StatsSidebarEntry.register(addHook = { target, method, caller, parameters, content ->
                        WorkshopApi.hookRegistrar.afterComposable(target, method, caller, parameters, 25, content)
                    }, replaceHook = { target, method, caller, parameters, content ->
                        WorkshopApi.hookRegistrar.replaceComposable(target, method, caller, parameters, 50, content)
                    })
                }
                PluginState.registeredHooks += "sidebar"
            } catch (failure: Throwable) {
                if (HookFailurePolicy.noRetry(failure)) throw failure
                complete = false
                PluginLog.error("Optional sidebar entry unavailable", failure)
                if (failure is com.jethuit.spw.listenstats.ui.HookRegistrationFailure && !failure.rollbackComplete) throw failure
            }
            handles += StatsEntryPlacement.installed("sidebar" in PluginState.registeredHooks,
                "titlebar" in PluginState.registeredHooks)
            try {
                handles += measure("queue-notification") {
                    QueueNotificationHooks.register(checkNotNull(bridge.queueNotifications)) { target, method, parameters, callback ->
                        WorkshopApi.hookRegistrar.hookMethod(target, method, parameters, callback)
                    }
                }
                PluginState.registeredHooks += "queue-notification"
            } catch (failure: Throwable) {
                if (HookFailurePolicy.noRetry(failure)) throw failure
                complete = false
                PluginLog.error("Optional silent queue insertion unavailable", failure)
                if (failure is com.jethuit.spw.listenstats.ui.HookRegistrationFailure && !failure.rollbackComplete) throw failure
            }
        } catch (failure: Throwable) {
            PluginState.registeredHooks = emptySet()
            throw measure("rollback") { handles.registrationFailed(failure) }
        } finally {
            PluginLog.info("Host UI install stages_ms=$timings")
        }
        val registered = PluginState.registeredHooks
        return HostHookInstallation(handles, complete, onClosed = {
            if (PluginState.registeredHooks === registered) PluginState.registeredHooks = emptySet()
        })
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
        StatsEntryPlacement.stop()
        com.jethuit.spw.listenstats.updates.Updates.stop()
        integration?.close()
        com.jethuit.spw.listenstats.data.StatsSnapshots.stop()
        // The installation clears registeredHooks after its queued EDT release actually succeeds.
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
        fun resetStatistics() {
            val session = StatsSnapshots.current ?: return
            CoroutineScope(session.pageActions + Dispatchers.Main).launch {
                try {
                    if (!BackupDialogs.confirm("重置统计数据", "从现在开始重新统计？SPW 的听歌历史和已有备份会保留。")) return@launch
                    val start = System.currentTimeMillis()
                    withContext(Dispatchers.IO) { session.reset(start) }
                    WorkshopApi.ui.toast("已从现在开始重新统计", WorkshopApi.Ui.ToastType.Success)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    PluginLog.error("reset statistics failed", failure)
                    WorkshopApi.ui.toast("重置失败，请查看诊断日志", WorkshopApi.Ui.ToastType.Warning)
                }
            }
        }

        @JvmStatic
        fun restoreStatistics() {
            val session = StatsSnapshots.current ?: return
            CoroutineScope(session.pageActions + Dispatchers.Main).launch {
                try {
                    val start = withContext(Dispatchers.IO) { StatisticsStart.read() }
                    if (start == 0L) {
                        WorkshopApi.ui.toast("当前没有设置统计起点", WorkshopApi.Ui.ToastType.Success)
                        return@launch
                    }
                    if (!BackupDialogs.confirm("恢复全部统计", "取消统计起点，重新计入重置前后仍保留的收听记录？")) return@launch
                    withContext(Dispatchers.IO) { session.restore(start) }
                    WorkshopApi.ui.toast("已取消统计起点，重新打开听歌统计即可查看", WorkshopApi.Ui.ToastType.Success)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    PluginLog.error("restore statistics failed", failure)
                    val message = if (failure is IllegalStateException) failure.message else null
                    WorkshopApi.ui.toast(message ?: "恢复失败，请查看诊断日志", WorkshopApi.Ui.ToastType.Warning)
                }
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
                        appendLine(HostUiRuntime.current?.songPlayback?.diagnostics()
                            ?: "singleSongTarget=inactive; insertNextTarget=inactive")
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
