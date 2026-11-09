@file:OptIn(androidx.compose.runtime.InternalComposeApi::class,
    com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package com.jethuit.spw.listenstats.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.xuncorp.spw.workshop.api.hook.ComposableHook
import com.xuncorp.spw.workshop.api.hook.UiHookCall

/** Append in the native MusicLibrary expansion slot, as used by Salt-Extend-Online. */
object StatsSidebarEntry {
    fun register(addHook: (String, String, String, List<String>, ComposableHook) -> AutoCloseable,
                 replaceHook: (String, String, String, List<String>, ComposableHook) -> AutoCloseable): AutoCloseable {
        val bridge = HostUiRuntime.require()
        checkNotNull(bridge.sidebar) { "Native sidebar control unavailable" }
        val selection = checkNotNull(bridge.sidebarSelection) { "Native sidebar selection unavailable" }
        val site = checkNotNull(bridge.targets.sidebar).entry
        val style = bridge.targets.sidebar.style
        val handles = HookHandles()
        try {
        handles += replaceHook(style.target.className, style.target.name, style.callerClass,
            style.target.parameters.dropLast(2), object : ComposableHook() {
                @Composable
                @Suppress("UNCHECKED_CAST")
                override fun Content(call: UiHookCall) {
                    selection.Provider(call.arguments[0] as ProvidedValue<Any?>, checkNotNull(call.arguments[1]))
                }
            })
        handles += addHook(site.target.className, site.target.name, site.callerClass,
            site.target.parameters.dropLast(2), object : ComposableHook() {
                @Composable
                override fun Content(call: UiHookCall) {
                    if (StatsEntryPlacement.showSidebar && isMusicLibrary(call.getArgument(0))) StatsSidebarButton()
                }
            })
        } catch (failure: Throwable) { throw handles.registrationFailed(failure) }
        return handles
    }

    fun isMusicLibrary(item: Any?): Boolean {
        if (item == null) return false
        val getter = HostUiRuntime.current?.sidebar?.second ?: return false
        return getter.invoke(item)?.javaClass?.name == "com.xuncorp.voxzen.ui.navigation.ScreenRoute\$MusicLibrary"
    }
}

@Composable
fun StatsSidebarButton(onClick: () -> Unit = StatsNavigationHooks::openStats) {
    val selection = HostUiRuntime.current?.sidebarSelection ?: return
    val original = LocalUnmaskedSidebarStyle.current
    val style = original ?: selection.local.current
    CompositionLocalProvider(selection.local provides style) { NativeStatsSidebarButton(onClick) }
}

@Composable
private fun NativeStatsSidebarButton(onClick: () -> Unit) {
    val row = HostUiRuntime.current?.sidebar?.first ?: return
    val active = StatsHostState.visible && !StatsHostState.closing
    val modifier = Modifier.semantics { contentDescription = "统计"; selected = active }
        .clickable(onClick = onClick)
    val icon: @Composable BoxScope.() -> Unit = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { StatsEntryIcon() }
    }
    // Color and size are provided by the native SidebarItemStyle composition local.
    row.invoke(null, "统计", active, null, false, modifier, null, null,
        icon, currentComposer, 0, 108)
}
