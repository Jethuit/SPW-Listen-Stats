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
    // A new destination must stay isolated through the complete exit.
    var revealHostOnExit by mutableStateOf(true)
        private set
    private var presenter: Any? = null
    private var animationsEnabled = true

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

    fun open() {
        closeTicket++
        closing = false
        revealHostOnExit = true
        visible = true
    }

    fun requestClose(revealHost: Boolean = true) {
        StatsNavigationHooks.cancelPendingOpen()
        if (!canAnimateClose) {
            close()
            return
        }
        // Keep isolation latched after any new destination starts underneath.
        revealHostOnExit = revealHostOnExit && revealHost
        if (!closing) closeTicket++
        closing = true
    }

    fun finishClose(ticket: Long) {
        if (!closing || ticket != closeTicket) return
        // A newer open may be waiting for another tool's confirmation. Finishing
        // this visual exit must not invalidate that newer navigation request.
        resetVisibility()
    }

    /** Forced teardown removes the page; no navigation is queued for later replay. */
    fun close() {
        StatsNavigationHooks.cancelPendingOpen()
        resetVisibility()
    }

    private fun resetVisibility() {
        closeTicket++
        closing = false
        revealHostOnExit = true
        visible = false
    }

    fun consumeBack(): Boolean {
        if (!visible) return false
        requestClose()
        return true
    }
}
