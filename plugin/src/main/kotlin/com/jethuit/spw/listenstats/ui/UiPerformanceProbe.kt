package com.jethuit.spw.listenstats.ui

import com.jethuit.spw.listenstats.PluginLog

/** Default-off user-perceived UI timing markers for real-host diagnosis. */
object UiPerformanceProbe {
    private val enabled = System.getProperty("spw.listenstats.uiPerf") == "true"

    fun mark(stage: String, detail: String = "") {
        if (!enabled) return
        val suffix = detail.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        PluginLog.info("ui_perf stage=$stage monoNanos=${System.nanoTime()}$suffix")
    }
}
