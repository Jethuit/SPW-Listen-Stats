@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package com.jethuit.spw.listenstats.ui

import com.jethuit.spw.listenstats.PluginLog
import com.xuncorp.spw.workshop.api.hook.MethodHook
import com.xuncorp.spw.workshop.api.hook.MethodHookParam

/** Bridges the embedded page to SPW 1.19.0's Navigator without editing its back stack. */
object StatsNavigationHooks {
    const val NAVIGATOR_CLASS = "com.xuncorp.voxzen.ui.navigation.Navigator"
    private const val ROUTE_CLASS = "com.xuncorp.voxzen.ui.navigation.ScreenRoute"

    fun register(
        addHook: (String, List<String>, MethodHook) -> AutoCloseable
    ): AutoCloseable {
        val handles = mutableListOf<AutoCloseable>()
        try {
            // Close before the host's same-route early return as well as actual transitions.
            for (method in listOf("navigate", "replace", "forward")) {
                val parameters = if (method == "forward") emptyList() else listOf(ROUTE_CLASS)
                handles += addHook(method, parameters, object : MethodHook() {
                    override fun before(call: MethodHookParam) {
                        StatsHostState.close()
                    }
                })
            }
            handles += addHook("back", emptyList(), object : MethodHook() {
                override fun before(call: MethodHookParam) {
                    if (StatsHostState.consumeBack()) {
                        // The statistics page is the top layer. One back action closes only it.
                        // This also works when the underlying host is already at its root.
                        call.setResult(null)
                    }
                }
            })
        } catch (failure: Throwable) {
            closeAll(handles)
            throw failure
        }
        return AutoCloseable { closeAll(handles) }
    }

    private fun closeAll(handles: MutableList<AutoCloseable>) {
        val pending = handles.toList().asReversed()
        handles.clear()
        for (handle in pending) {
            runCatching { handle.close() }.onFailure {
                PluginLog.error("removing navigation hook failed", it)
            }
        }
    }
}
