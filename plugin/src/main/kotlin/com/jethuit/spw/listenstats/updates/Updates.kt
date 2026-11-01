package com.jethuit.spw.listenstats.updates

import com.jethuit.spw.listenstats.PluginLog
import com.jethuit.spw.listenstats.PluginState
import com.jethuit.spw.listenstats.Prefs
import com.jethuit.spw.listenstats.data.DataFiles
import com.xuncorp.spw.workshop.api.WorkshopApi
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object Updates {
    private val status = MutableStateFlow(UpdateNotice())
    val notice = status.asStateFlow()
    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var ready: Deferred<UpdateChecker>? = null
    @Volatile private var remindersEnabled = false

    fun start() {
        stop()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = owner
        val version = PluginState.pluginVersion
        val path = DataFiles.directory().resolve("update-check.json")
        val checker = owner.async {
            UpdateChecker(version, path,
                enabled = { (Prefs.microsoftStoreEdition() == true).also { remindersEnabled = it } },
                changed = { if (scope === owner) status.value = it },
                notify = { release -> SwingUtilities.invokeLater {
                    if (scope === owner && remindersEnabled) runCatching {
                        WorkshopApi.ui.toast("听歌统计有新正式版本 ${release.label}，可在“关于”中查看。",
                            WorkshopApi.Ui.ToastType.Success)
                    }.onFailure { PluginLog.info("showing update reminder failed") }
                } })
        }
        ready = checker
        owner.launch {
            val engine = checker.await()
            delay(30_000)
            while (isActive) {
                engine.check(manual = false)
                delay(60_000)
            }
        }
    }

    fun checkManually() {
        val owner = scope
        val checker = ready
        if (owner == null || checker == null) {
            status.value = UpdateNotice(message = "更新检查尚未就绪，请重新启用插件后重试。", error = true)
            return
        }
        owner.launch { checker.await().check(manual = true) }
    }

    fun preferenceChanged(enabled: Boolean) { remindersEnabled = enabled }

    fun stop() {
        val previous = scope
        scope = null
        ready = null
        remindersEnabled = false
        previous?.cancel()
        status.value = UpdateNotice()
    }
}
