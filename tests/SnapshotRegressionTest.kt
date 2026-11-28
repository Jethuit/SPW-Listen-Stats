@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

import com.jethuit.spw.listenstats.DisplayPreferences
import com.jethuit.spw.listenstats.PageOptions
import com.jethuit.spw.listenstats.data.*
import com.jethuit.spw.listenstats.ui.StatsHostState
import com.jethuit.spw.listenstats.ui.StatsNavigationHooks
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities

fun checkSnapshotPreparation() {
    val day = LocalDate.of(2026, 10, 23)
    var reads = 0
    var result = StatsData.empty().copy(dailyMillis = mapOf(day to 60_000L))
    var throws = false
    var displayPreferences = DisplayPreferences()
    var allowed = true
    val session = StatsSnapshotSession(read = {
        reads++
        if (throws) error("synthetic read failure")
        result
    }, preferences = { displayPreferences }, hasAccess = { allowed })
    runBlocking {
        val first = session.load()
        check(first.data.millisOn(day) == 60_000L)
        check(session.load(reuse = true).let {
            it.data === first.data && it.overview === first.overview && it.checkedAtNanos == first.checkedAtNanos
        } && reads == 1)
        val options = PageOptions(chartType = ChartType.Curve, granularity = Granularity.Week,
            range = RangePreset.Custom, customStart = day.minusDays(20), customEnd = day,
            rankingMode = RankingMode.Album, rankingMetric = RankingMetric.Duration)
        displayPreferences = DisplayPreferences(pageAnimations = false, refreshIntervalSeconds = 60,
            detailVisibleRows = 3, showDetailHint = false, heatmapAnimations = false, pageOptions = options)
        val customized = session.load(reuse = true)
        val overview = checkNotNull(customized.overview)
        val range = DateRange(day.minusDays(20), day)
        check(customized.preferences == displayPreferences && customized.options == options)
        check(overview.summary.totalMillis == 60_000L && overview.summary.activeDays == 1)
        check(overview.range == range && overview.granularity == Granularity.Week)
        check(overview.buckets == StatsCalculations.chartBuckets(first.data, range, Granularity.Week))
        check(overview.heatmap === first.overview?.heatmap && customized.checkedAtNanos == first.checkedAtNanos)
        val switched = session.load(selection = OverviewSelection(day.year, false, true,
            RangePreset.Selected, day, null, null, Granularity.Week))
        check(switched.overview?.let {
            it.granularity == Granularity.Day && it.buckets.size == 24 &&
                it.heatmap.weeks.isNotEmpty() && it.heatmap.totalMillis == 60_000L
        } == true)
        check(reads == 2) { "Opening with new preferences must reuse cached listening data" }
        result = StatsData.empty(error = "synthetic database failure")
        check(session.load().data.let { it.millisOn(day) == 60_000L && it.error != null })
        throws = true
        check(session.load().data.let { it.millisOn(day) == 60_000L && it.error != null })
        allowed = false
        check(session.load(reuse = true).let { it.data.libraryAccessDenied && it.data.dailyMillis.isEmpty() && it.overview == null })
        allowed = true
        check(session.load().data.dailyMillis.isEmpty()) { "a read failure resurrected revoked history" }
        throws = false
        result = StatsData.empty().copy(dailyMillis = mapOf(day to 120_000L))
        check(session.load().data.let { it.millisOn(day) == 120_000L && it.error == null })
    }
    println("PASS snapshots reuse real values, retain data on repeated failures, and recover")

    StatsSnapshots.start(session, initialize = {})
    try {
        for (enabled in listOf(true, false)) {
            displayPreferences = displayPreferences.copy(pageAnimations = enabled, heatmapAnimations = enabled)
            val ready = CountDownLatch(1)
            SwingUtilities.invokeAndWait { StatsSnapshots.prepareOpen { ready.countDown() } }
            check(ready.await(5, TimeUnit.SECONDS))
            check(session.latest?.preferences == displayPreferences) { "Warm entry used stale settings" }
        }
    } finally { StatsSnapshots.stop() }
    println("PASS warm entry refreshes settings before presenting cached data")

    val integrationReady = CompletableDeferred<Unit>()
    val preferencesReady = CountDownLatch(1)
    val prioritizedReads = AtomicInteger()
    val prioritized = StatsSnapshotSession(read = {
        check(integrationReady.isCompleted) { "History read competed with UI integration" }
        prioritizedReads.incrementAndGet()
        result
    }, preferences = { displayPreferences })
    StatsSnapshots.start(prioritized, initialize = { preferencesReady.countDown() }, preloadAfter = integrationReady)
    try {
        check(preferencesReady.await(5, TimeUnit.SECONDS)) { "Preferences must initialize before UI integration finishes" }
        val opened = CountDownLatch(1)
        SwingUtilities.invokeAndWait {
            StatsSnapshots.prepareOpen { opened.countDown() }
            check(prioritizedReads.get() == 0 && prioritized.latest == null && opened.count == 1L)
        }
        integrationReady.complete(Unit)
        check(opened.await(5, TimeUnit.SECONDS))
        check(prioritizedReads.get() == 1 && prioritized.latest?.data?.millisOn(day) == 120_000L)
    } finally { StatsSnapshots.stop() }
    println("PASS startup loads preferences early; history and pending opens wait for UI completion")

    val oldGate = CompletableDeferred<Unit>()
    val newGate = CompletableDeferred<Unit>()
    val oldInitialized = CountDownLatch(1)
    val newInitialized = CountDownLatch(1)
    val staleReads = AtomicInteger()
    val staleOpens = AtomicInteger()
    val oldSession = StatsSnapshotSession(read = { staleReads.incrementAndGet(); result },
        preferences = { displayPreferences })
    StatsSnapshots.start(oldSession, initialize = { oldInitialized.countDown() }, preloadAfter = oldGate)
    check(oldInitialized.await(5, TimeUnit.SECONDS))
    SwingUtilities.invokeAndWait { StatsSnapshots.prepareOpen { staleOpens.incrementAndGet() } }
    StatsSnapshots.stop()
    val newSession = StatsSnapshotSession(read = {
        check(newGate.isCompleted) { "An old UI completion released the new session" }
        result
    }, preferences = { displayPreferences })
    StatsSnapshots.start(newSession, initialize = { newInitialized.countDown() }, preloadAfter = newGate)
    try {
        check(newInitialized.await(5, TimeUnit.SECONDS))
        oldGate.complete(Unit)
        val opened = CountDownLatch(1)
        SwingUtilities.invokeAndWait {
            check(StatsSnapshots.current === newSession && newSession.latest == null)
            StatsSnapshots.prepareOpen { opened.countDown() }
        }
        newGate.complete(Unit)
        check(opened.await(5, TimeUnit.SECONDS))
        SwingUtilities.invokeAndWait { check(staleOpens.get() == 0) }
        check(staleReads.get() == 0 && oldSession.latest == null && newSession.latest != null)
    } finally { StatsSnapshots.stop() }
    println("PASS stop cancels startup wait; old completion cannot release or reopen a replacement session")

    for (action in listOf("ready", "navigate", "back", "close", "stop")) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val delayed = StatsSnapshotSession(read = {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            result
        }, preferences = { DisplayPreferences() })
        val navigated = CountDownLatch(1)
        TestNavigationRegistration(afterNavigate = navigated::countDown).use { registration ->
            try {
                StatsHostState.close()
                StatsSnapshots.start(delayed, initialize = {})
                check(started.await(5, TimeUnit.SECONDS))
                SwingUtilities.invokeAndWait {
                    StatsNavigationHooks.openStats()
                    check(!StatsHostState.visible && registration.lastRequest == null)
                    when (action) {
                        "navigate" -> registration.dispatch("navigate", arrayOf(registration.host.currentRoute))
                        "back" -> registration.dispatch("back")
                        "close" -> StatsHostState.close()
                        "stop" -> StatsSnapshots.stop()
                    }
                    if (action != "stop") StatsSnapshots.prepareOpen { delivered.countDown() }
                }
                release.countDown()
                if (action != "stop") {
                    check(delivered.await(5, TimeUnit.SECONDS))
                    // Separate asynchronous preparation requests can finish in either order.
                    // Await the navigation result itself instead of a second request's callback.
                    if (action == "ready") check(navigated.await(5, TimeUnit.SECONDS))
                    // Flush callbacks queued on the same UI thread before checking navigation state.
                    SwingUtilities.invokeAndWait { }
                    SwingUtilities.invokeAndWait {
                        check(StatsHostState.visible == (action == "ready")) { "Late open after $action" }
                    }
                } else {
                    runBlocking { delayed.load(reuse = true) }
                    SwingUtilities.invokeAndWait { check(!StatsHostState.visible && StatsSnapshots.current == null) }
                }
            } finally { release.countDown(); StatsSnapshots.stop(); StatsHostState.close() }
        }
    }
    println("PASS cold entry waits for data; navigation/back/close/stop invalidate pending opens")
}
