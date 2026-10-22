package com.jethuit.spw.listenstats.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class StatsExitMotion { Return, Navigate }

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
    var exitMotion by mutableStateOf(StatsExitMotion.Return)
        private set
    var nativeNavigation by mutableStateOf(true)
        private set
    var navigationTicket by mutableStateOf(0L)
        private set
    private var presenter: Any? = null
    private var animationsEnabled = true

    val canAnimateClose: Boolean get() = visible && presenter != null && animationsEnabled
    val navigating: Boolean get() = visible && closing && exitMotion == StatsExitMotion.Navigate
    val revealHostOnExit: Boolean get() = exitMotion == StatsExitMotion.Return

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
        exitMotion = StatsExitMotion.Return
        nativeNavigation = true
        visible = true
    }

    fun requestClose() {
        // A later back press consumes the statistics layer without changing an
        // already accepted destination into a downward return animation.
        if (navigating) return
        beginClose(StatsExitMotion.Return)
    }

    fun requestNavigation(nativeTransition: Boolean = true) {
        nativeNavigation = nativeTransition
        if (canAnimateClose) navigationTicket++
        beginClose(StatsExitMotion.Navigate)
    }

    private fun beginClose(motion: StatsExitMotion) {
        StatsNavigationHooks.cancelPendingOpen()
        if (!canAnimateClose) {
            close()
            return
        }
        if (!closing || exitMotion != motion) closeTicket++
        exitMotion = motion
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
        exitMotion = StatsExitMotion.Return
        nativeNavigation = true
        visible = false
    }

    fun consumeBack(): Boolean {
        if (!visible) return false
        requestClose()
        return true
    }
}
