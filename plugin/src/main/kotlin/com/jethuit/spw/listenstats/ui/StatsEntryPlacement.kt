package com.jethuit.spw.listenstats.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jethuit.spw.listenstats.Prefs
import java.awt.EventQueue
import kotlinx.coroutines.*

/** One enable session owns the preference poll and both entry visibility states. */
object StatsEntryPlacement {
    private class Session {
        @Volatile var alive = true
        var sidebarRequested by mutableStateOf(false)
        var sidebarReady by mutableStateOf(false)
        var titlebarReady by mutableStateOf(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
    private var current by mutableStateOf<Session?>(null)
    val showSidebar: Boolean get() = current?.let {
        it.alive && it.sidebarReady && (it.sidebarRequested || !it.titlebarReady)
    } == true
    val showTitlebar: Boolean get() = current?.let { it.alive && it.titlebarReady && !showSidebar } == true

    fun start(read: () -> Boolean? = Prefs::sidebarEntry, intervalMillis: Long = 750,
              publish: ((() -> Unit) -> Unit) = { EventQueue.invokeLater(it) }) {
        stop()
        val session = Session()
        current = session
        session.scope.launch {
            while (isActive) {
                val value = read()
                if (value != null) publish {
                    if (session.alive && current === session) session.sidebarRequested = value
                }
                delay(intervalMillis)
            }
        }
    }

    fun installed(sidebar: Boolean, titlebar: Boolean): AutoCloseable {
        val session = checkNotNull(current) { "Entry placement session not started" }
        session.sidebarReady = sidebar
        session.titlebarReady = titlebar
        return AutoCloseable {
            session.sidebarReady = false
            session.titlebarReady = false
        }
    }

    fun stop() {
        current?.let { it.alive = false; it.scope.cancel() }
        current = null
    }
}
