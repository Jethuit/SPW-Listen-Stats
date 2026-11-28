package com.jethuit.spw.listenstats.data

import androidx.compose.ui.text.font.FontFamily
import com.jethuit.spw.listenstats.DisplayPreferences
import com.jethuit.spw.listenstats.PageOptions
import com.jethuit.spw.listenstats.Prefs
import com.jethuit.spw.listenstats.PluginLog
import com.jethuit.spw.listenstats.ui.HostAppearanceReader
import com.jethuit.spw.listenstats.ui.HostFont
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import com.jethuit.spw.listenstats.ui.UiPerformanceProbe
import kotlin.coroutines.coroutineContext

class PreparedStatistics internal constructor(
    val data: StatsData,
    val preferences: DisplayPreferences,
    val today: LocalDate,
    val options: PageOptions,
    val overview: PreparedOverview?,
    val checkedAtNanos: Long,
    val font: FontFamily?
) {
    internal fun refreshDelayMillis(): Long {
        val interval = preferences.refreshIntervalSeconds * 1_000L
        val age = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - checkedAtNanos)
        return (interval - age).coerceIn(0L, interval)
    }
}

/** One enable session owns its cache. Failed reads never replace a successful snapshot. */
class StatsSnapshotSession(
    private val read: () -> StatsData,
    private val preferences: () -> DisplayPreferences = Prefs::displayPreferences,
    private val hasAccess: () -> Boolean = { true },
    private val prepareHostFont: () -> FontFamily? = { null }
) {
    constructor() : this(StatsStore::load, Prefs::displayPreferences, StatsStore::hasLibraryAccess, {
        val appearance = HostAppearanceReader.read()
        HostFont.load(appearance.fontStrategy, appearance.customFontPath)
    })

    internal val pageActions = SupervisorJob()
    private val mutex = Mutex()
    private var lastSuccess: StatsData? = null
    @Volatile var latest: PreparedStatistics? = null
        private set

    suspend fun reset(start: Long) = changeStatisticsStart { BackupService.resetStatistics(start) }

    suspend fun restore(expectedStart: Long) = changeStatisticsStart { BackupService.restoreStatistics(expectedStart) }

    private suspend fun changeStatisticsStart(change: () -> Unit) {
        mutex.lock()
        try {
            coroutineContext.ensureActive()
            change()
            lastSuccess = null
            latest = null
        } finally { mutex.unlock() }
    }

    suspend fun load(reuse: Boolean = false, selection: OverviewSelection? = null,
                     prepareFont: Boolean = false): PreparedStatistics {
        mutex.lock()
        try {
            val previous = latest
            val hasLibraryAccess = hasAccess()
            val reused = hasLibraryAccess && reuse && previous != null && !previous.data.libraryAccessDenied
            val result = when {
                !hasLibraryAccess -> StatsStore.accessDenied()
                reused -> checkNotNull(previous).data
                else -> try { read() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { StatsData.empty(error = "统计数据读取失败：${failure.message}") }
            }
            val checkedAt = if (reused) checkNotNull(previous).checkedAtNanos else System.nanoTime()
            val data = when {
                result.libraryAccessDenied || result.error == null || reused -> result
                else -> lastSuccess?.copy(error = result.error, storageWarning = result.storageWarning) ?: result
            }
            // Drop previous access immediately, including when this work is canceled before publication.
            if (data.libraryAccessDenied) {
                lastSuccess = null
                latest = null
            }
            val display = preferences()
            val today = LocalDate.now()
            val options = display.pageOptions.withDateDefaults(today)
            val overview = if (data.libraryAccessDenied) null else withContext(Dispatchers.Default) {
                PreparedOverview.prepare(data, today, selection ?: OverviewSelection(today, options), previous?.overview)
            }
            val font = if (prepareFont) prepareHostFont() else previous?.font
            if (prepareFont) UiPerformanceProbe.mark("open_font_prepared")
            coroutineContext.ensureActive()
            if (data.error == null) lastSuccess = data
            return PreparedStatistics(data, display, today, options, overview, checkedAt, font).also { latest = it }
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
            val prepared = session.load(reuse = true, prepareFont = true)
            ensureActive()
            PluginLog.info("Statistics preload: preferences_ms=${(initialized - initializeStarted) / 1_000_000}; " +
                "ui_wait_ms=${(readStarted - initialized) / 1_000_000}; " +
                "read_ms=${(System.nanoTime() - readStarted) / 1_000_000}; error=${prepared.data.error != null}")
            UiPerformanceProbe.mark("preload_ready")
            prepared
        }
    }

    fun stop() {
        current?.pageActions?.cancel()
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
        val owner = scope ?: return
        owner.launch {
            preparation.await()
            session.load(reuse = true, prepareFont = true)
            ensureActive()
            val prepared = System.nanoTime()
            UiPerformanceProbe.mark("open_prepare_complete", "prepare_ms=${(prepared - requested) / 1_000_000}")
            SwingUtilities.invokeLater {
                if (current === session) {
                    val edtQueueMs = (System.nanoTime() - prepared) / 1_000_000
                    UiPerformanceProbe.mark("open_ready", "edt_queue_ms=$edtQueueMs")
                    owner.launch {
                        PluginLog.info("Statistics open: prepare_ms=${(prepared - requested) / 1_000_000}; " +
                            "edt_queue_ms=$edtQueueMs")
                    }
                    ready()
                }
            }
        }
    }
}
