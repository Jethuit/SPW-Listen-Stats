package com.jethuit.spw.listenstats.data

import com.jethuit.spw.listenstats.DisplayPreferences
import com.jethuit.spw.listenstats.Prefs
import com.jethuit.spw.listenstats.PluginLog
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import javax.swing.SwingUtilities
import com.jethuit.spw.listenstats.ui.UiPerformanceProbe

data class PreparedStatistics(val data: StatsData, val preferences: DisplayPreferences)

/** One enable session owns its cache. Failed reads never replace a successful snapshot. */
class StatsSnapshotSession(
    private val read: () -> StatsData = StatsStore::load,
    private val preferences: () -> DisplayPreferences = Prefs::displayPreferences
) {
    private val mutex = Mutex()
    private var lastSuccess: StatsData? = null
    @Volatile var latest: PreparedStatistics? = null
        private set

    suspend fun load(reuse: Boolean = false): PreparedStatistics {
        mutex.lock()
        try {
        // Reuse listening data, but refresh settings before the page's first frame.
        if (reuse) latest?.let { cached ->
            return cached.copy(preferences = preferences()).also { latest = it }
        }
        val result = try { read() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { StatsData.empty(error = "统计数据读取失败：${failure.message}") }
        if (result.error == null) lastSuccess = result
        val data = if (result.error != null) lastSuccess?.copy(error = result.error) ?: result else result
        return PreparedStatistics(data, preferences()).also { latest = it }
        } finally { mutex.unlock() }
    }
}

/** Preload without holding the UI thread; replacing a session invalidates late callbacks. */
object StatsSnapshots {
    @Volatile var current: StatsSnapshotSession? = null
        private set
    private var scope: CoroutineScope? = null
    private var preload: Deferred<PreparedStatistics>? = null

    fun start(session: StatsSnapshotSession = StatsSnapshotSession(), initialize: () -> Unit = Prefs::initialize,
              preloadAfter: Deferred<Unit>? = null) {
        stop()
        UiPerformanceProbe.mark("preload_start")
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        current = session
        scope = owner
        preload = owner.async {
            val initializeStarted = System.nanoTime()
            initialize()
            val initialized = System.nanoTime()
            // Preferences stay available for the entry's first frame. History IO and
            // aggregation wait until UI integration has finished competing for startup resources.
            if (preloadAfter != null) UiPerformanceProbe.mark("preload_wait_ui")
            preloadAfter?.await()
            ensureActive()
            val readStarted = System.nanoTime()
            UiPerformanceProbe.mark("preload_read_start")
            val prepared = session.load(reuse = true)
            ensureActive()
            PluginLog.info("Statistics preload: preferences_ms=${(initialized - initializeStarted) / 1_000_000}; " +
                "ui_wait_ms=${(readStarted - initialized) / 1_000_000}; " +
                "read_ms=${(System.nanoTime() - readStarted) / 1_000_000}; error=${prepared.data.error != null}")
            UiPerformanceProbe.mark("preload_ready")
            prepared
        }
    }

    fun stop() {
        current = null
        scope?.cancel()
        scope = null
        preload = null
        HostDatabase.close()
    }

    fun prepareOpen(ready: () -> Unit) {
        val requested = System.nanoTime()
        UiPerformanceProbe.mark("open_prepare_requested")
        val session = current
        // Standalone synthetic navigation checks do not start the plugin lifecycle.
        if (session == null) { ready(); return }
        val preparation = preload ?: return
        scope?.launch {
            preparation.await()
            session.load(reuse = true)
            ensureActive()
            val prepared = System.nanoTime()
            SwingUtilities.invokeLater {
                if (current === session) {
                    PluginLog.info("Statistics open: prepare_ms=${(prepared - requested) / 1_000_000}; " +
                        "edt_queue_ms=${(System.nanoTime() - prepared) / 1_000_000}")
                    UiPerformanceProbe.mark("open_ready")
                    ready()
                }
            }
        }
    }
}
