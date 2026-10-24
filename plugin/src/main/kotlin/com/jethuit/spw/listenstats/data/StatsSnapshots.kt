package com.jethuit.spw.listenstats.data

import com.jethuit.spw.listenstats.DisplayPreferences
import com.jethuit.spw.listenstats.Prefs
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import javax.swing.SwingUtilities

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

    fun start(session: StatsSnapshotSession = StatsSnapshotSession(), initialize: () -> Unit = Prefs::initialize) {
        stop()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        current = session
        scope = owner
        preload = owner.async { initialize(); session.load(reuse = true) }
    }

    fun stop() {
        current = null
        scope?.cancel()
        scope = null
        preload = null
    }

    fun prepareOpen(ready: () -> Unit) {
        val session = current
        // Standalone synthetic navigation checks do not start the plugin lifecycle.
        if (session == null) { ready(); return }
        val preparation = preload ?: return
        scope?.launch {
            preparation.await()
            session.load(reuse = true)
            ensureActive()
            SwingUtilities.invokeLater { if (current === session) ready() }
        }
    }
}
