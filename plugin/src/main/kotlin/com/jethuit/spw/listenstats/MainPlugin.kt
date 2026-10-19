@file:OptIn(UnstableSpwWorkshopApi::class)
@file:Suppress("unused")

package com.jethuit.spw.listenstats

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.jethuit.spw.listenstats.ui.StatsOverlay
import com.jethuit.spw.listenstats.ui.StatsNavigationHooks
import com.jethuit.spw.listenstats.ui.StatsTitlebarEntry

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
            hookHandle = WorkshopApi.hookRegistrar.afterComposable(
                className = "com.xuncorp.voxzen.ui.navigation.AppNavigationKt",
                methodName = "AppNavigation",
                inClass = "androidx.compose.ui.ۄ"
            ) { _ ->
                StatsOverlay()
            }
            PluginLog.info("AppNavigation and Navigator hooks registered")
        } catch (failure: Throwable) {
            closeHooks()
            PluginLog.error("AppNavigation hook failed", failure)
            WorkshopApi.ui.toast(
                "听歌统计入口加载失败，请在插件配置中打开",
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
        WorkshopApi.ui.toast("听歌统计已启用", WorkshopApi.Ui.ToastType.Success)
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
                com.jethuit.spw.listenstats.ui.StatsHostState.open()
            }
        }

        @JvmStatic
        fun writeDiagnostics() {
            PluginLog.info("diagnostics requested from preference")
            WorkshopApi.ui.toast("诊断信息已写入插件数据目录", WorkshopApi.Ui.ToastType.Success)
        }
    }
}
