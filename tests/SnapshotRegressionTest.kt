@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

import com.jethuit.spw.listenstats.DisplayPreferences
import com.jethuit.spw.listenstats.data.*
import com.jethuit.spw.listenstats.ui.StatsHostState
import com.jethuit.spw.listenstats.ui.StatsNavigationHooks
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

fun checkSnapshotPreparation() {
    val day = LocalDate.of(2026, 10, 23)
    var reads = 0
    var result = StatsData.empty().copy(dailyMillis = mapOf(day to 60_000L))
    var throws = false
    val session = StatsSnapshotSession(read = {
        reads++
        if (throws) error("synthetic read failure")
        result
    }, preferences = { DisplayPreferences() })
    runBlocking {
        check(session.load().data.millisOn(day) == 60_000L)
        check(session.load(reuse = true).data.millisOn(day) == 60_000L && reads == 1)
        result = StatsData.empty(error = "synthetic database failure")
        check(session.load().data.let { it.millisOn(day) == 60_000L && it.error != null })
        throws = true
        check(session.load().data.let { it.millisOn(day) == 60_000L && it.error != null })
        throws = false
        result = StatsData.empty().copy(dailyMillis = mapOf(day to 120_000L))
        check(session.load().data.let { it.millisOn(day) == 120_000L && it.error == null })
    }
    println("PASS snapshots reuse real values, retain data on repeated failures, and recover")

    for (action in listOf("ready", "navigate", "back", "close", "stop")) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val delayed = StatsSnapshotSession(read = {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            result
        }, preferences = { DisplayPreferences() })
        TestNavigationRegistration().use { registration ->
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
