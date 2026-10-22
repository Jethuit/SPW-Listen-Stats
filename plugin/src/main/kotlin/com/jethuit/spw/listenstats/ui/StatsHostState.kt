package com.jethuit.spw.listenstats.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Host-composed statistics page. No separate AWT window and no floating overlay. */
object StatsHostState {
    // True only while the optional host-content wrapper is actually in the composition.
    var materialBridgeAvailable by mutableStateOf(false)
        internal set

    var visible by mutableStateOf(false)
        private set

    fun open() {
        visible = true
    }

    fun close() {
        StatsNavigationHooks.cancelPendingOpen()
        visible = false
    }

    fun consumeBack(): Boolean {
        if (!visible) return false
        close()
        return true
    }
}
