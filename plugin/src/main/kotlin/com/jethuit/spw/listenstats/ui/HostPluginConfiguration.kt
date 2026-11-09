package com.jethuit.spw.listenstats.ui

/** Host route, not a duplicate plugin-owned configuration page. */
internal object HostPluginConfiguration {
    fun route(loader: ClassLoader, pluginId: String): Any {
        check(pluginId.isNotBlank()) { "Plugin ID is unavailable" }
        val type = Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRoute\$ModConfigContent", false, loader)
        val route = type.getConstructor(String::class.java).newInstance(pluginId)
        check(type.getMethod("getPluginId").invoke(route) == pluginId) { "Host configuration route does not match plugin" }
        return route
    }
}
