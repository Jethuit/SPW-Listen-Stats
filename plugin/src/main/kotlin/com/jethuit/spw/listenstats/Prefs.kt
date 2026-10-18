package com.jethuit.spw.listenstats

import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper

@OptIn(UnstableSpwWorkshopApi::class)
object Prefs {
    private var helper: ConfigHelper? = null

    fun initialize() {
        helper = try {
            WorkshopApi.manager.createConfigManager().getConfig("config.json").also { it.reload() }
        } catch (failure: Throwable) {
            PluginLog.error("config initialization failed", failure)
            null
        }
    }

    fun getString(key: String, fallback: String): String {
        return try {
            helper?.get(key, fallback) ?: fallback
        } catch (failure: Throwable) {
            fallback
        }
    }

    fun putString(key: String, value: String) {
        try {
            helper?.set(key, value)
            helper?.save()
        } catch (failure: Throwable) {
            PluginLog.error("saving preference $key failed", failure)
        }
    }
}
