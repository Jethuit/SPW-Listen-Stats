package com.jethuit.spw.listenstats

/** Minimal bridge so UI code can locate plugin data without passing the context around. */
object PluginState {
    @Volatile
    var pluginPath: String? = null

    @Volatile
    var pluginId: String = "com.jethuit.spw.listenstats"

    @Volatile
    var spwVersion: String = "unknown"
}
