package com.jethuit.spw.listenstats.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Host-composed statistics page. No separate AWT window and no floating overlay. */
object StatsHostState {
    // True only while the containing host-content wrapper is actually composed.
    var materialBridgeAvailable by mutableStateOf(false)
        internal set

    var visible by mutableStateOf(false)
        private set
    var closing by mutableStateOf(false)
        private set
    var closeTicket by mutableStateOf(0L)
        private set
    private var presenter: Any? = null
    private var animationsEnabled = true
    private var afterClose: (() -> Unit)? = null

    val canAnimateClose: Boolean get() = visible && presenter != null && animationsEnabled

    /** Only a composed page can acknowledge completion; headless/unavailable pages close immediately. */
    fun attachPresenter(): AutoCloseable {
        val token = Any()
        presenter = token
        return AutoCloseable {
            if (presenter === token) {
                presenter = null
                // A completed exit can leave a newer open waiting for another tool.
                // Unexpected unmount while visible still cancels everything.
                if (visible) close()
            }
        }
    }

    fun setAnimationsEnabled(enabled: Boolean) { animationsEnabled = enabled }

    fun cancelDeferredNavigation() { afterClose = null }

    fun open() {
        closeTicket++
        afterClose = null
        closing = false
        visible = true
    }

    fun requestClose(onClosed: (() -> Unit)? = null) {
        StatsNavigationHooks.cancelPendingOpen()
        if (!canAnimateClose) {
            close()
            onClosed?.invoke()
            return
        }
        afterClose = onClosed // A newer destination replaces the previous one without restarting the fade.
        if (!closing) closeTicket++
        closing = true
    }

    fun finishClose(ticket: Long) {
        if (!closing || ticket != closeTicket) return
        val action = afterClose
        // A newer open may be waiting for another tool's confirmation. Finishing
        // this visual exit must not invalidate that newer navigation request.
        resetVisibility()
        action?.invoke()
    }

    /** Forced teardown cancels any delayed navigation instead of replaying it after disable/unmount. */
    fun close() {
        StatsNavigationHooks.cancelPendingOpen()
        resetVisibility()
    }

    private fun resetVisibility() {
        closeTicket++
        afterClose = null
        closing = false
        visible = false
    }

    fun consumeBack(): Boolean {
        if (!visible) return false
        requestClose()
        return true
    }
}
