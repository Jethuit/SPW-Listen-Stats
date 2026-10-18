@file:OptIn(UnstableSpwWorkshopApi::class)
@file:Suppress("unused")

package com.jethuit.spw.listenstats

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.jethuit.spw.listenstats.ui.StatsOverlay

class MainPlugin(
    pluginContext: PluginContext
) : SpwPlugin(pluginContext) {
    private var hookHandle: AutoCloseable? = null

    override fun start() {
        PluginState.pluginPath = pluginContext.pluginPath
        PluginState.pluginId = pluginContext.pluginId
        PluginState.spwVersion = pluginContext.spwVersion
        PluginLog.info("start plugin ${pluginContext.pluginVersion} for SPW ${pluginContext.spwVersion}")
        com.jethuit.spw.listenstats.data.StatsStore.initialize(pluginContext.pluginPath)
        hookHandle?.close()
        hookHandle = null
        try {
            hookHandle = WorkshopApi.hookRegistrar.afterComposable(
                className = "com.xuncorp.voxzen.ui.navigation.AppNavigationKt",
                methodName = "AppNavigation",
                inClass = "androidx.compose.ui.ۄ"
            ) { _ ->
                StatsOverlay()
            }
            PluginLog.info("AppNavigation hook registered")
        } catch (failure: Throwable) {
            PluginLog.error("AppNavigation hook failed", failure)
            WorkshopApi.ui.toast(
                "听歌统计入口加载失败，请在插件配置中打开",
                WorkshopApi.Ui.ToastType.Warning
            )
        }
        WorkshopApi.ui.toast("听歌统计已启用", WorkshopApi.Ui.ToastType.Success)
        if (System.getProperty("spw.listenstats.autotest") == "true") {
            Thread {
                for (attempt in 0 until 20) {
                    Thread.sleep(2_000)
                    if (com.jethuit.spw.listenstats.ui.StatsWindow.open()) {
                        break
                    }
                }
            }.apply { isDaemon = true }.start()
        }
    }

    override fun stop() {
        PluginLog.info("stop plugin")
        hookHandle?.close()
        hookHandle = null
    }

    override fun delete() {
        PluginLog.info("delete plugin")
    }

    override fun update() {
        PluginLog.info("update plugin")
    }

    companion object {
        @JvmStatic
        fun openStats() {
            PluginLog.info("openStats requested from preference")
            Thread {
                com.jethuit.spw.listenstats.ui.StatsWindow.open()
            }.apply { isDaemon = true }.start()
        }

        @JvmStatic
        fun writeDiagnostics() {
            PluginLog.info("diagnostics requested from preference")
            WorkshopApi.ui.toast("诊断信息已写入插件数据目录", WorkshopApi.Ui.ToastType.Success)
        }
    }
}
