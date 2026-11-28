package com.jethuit.spw.listenstats.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onGloballyPositioned
import com.jethuit.spw.listenstats.PluginLog
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Default-off user-perceived UI timing markers for real-host diagnosis. */
object UiPerformanceProbe {
    internal val enabled = System.getProperty("spw.listenstats.uiPerf") == "true"
    private val pending by lazy { ArrayBlockingQueue<String>(512) }
    private val flushScheduled = AtomicBoolean()
    private val writer by lazy {
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "ListenStats-UiPerf").apply { isDaemon = true }
        }
    }

    fun mark(stage: String, detail: String = "") {
        if (!enabled) return
        val suffix = detail.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        pending.offer("ui_perf stage=$stage monoNanos=${System.nanoTime()}$suffix")
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) return
        writer.schedule({
            val lines = mutableListOf<String>()
            pending.drainTo(lines)
            flushScheduled.set(false)
            if (pending.isNotEmpty()) scheduleFlush()
            if (lines.isNotEmpty()) PluginLog.info(lines.joinToString("\n"))
        }, 1, TimeUnit.SECONDS)
    }
}

/** Compose callbacks can measure layout and drawing, before the host presents the frame. */
internal class EntrancePerformanceProbe {
    private var laidOut = false
    private var drawn = false
    private var previousFrame: Long? = null
    private var maxGap = 0L
    private var frames = 0
    private var finished = false

    val modifier: Modifier = Modifier.onGloballyPositioned {
        if (!laidOut) {
            laidOut = true
            UiPerformanceProbe.mark("window_first_layout")
        }
    }.drawWithContent {
        drawContent()
        if (!drawn) {
            drawn = true
            UiPerformanceProbe.mark("window_first_draw")
        }
    }

    fun frame(time: Long) {
        if (finished) return
        previousFrame?.let { maxGap = maxOf(maxGap, time - it) }
        previousFrame = time
        frames++
    }

    fun entered() {
        if (finished) return
        finished = true
        UiPerformanceProbe.mark("window_entrance_frames", "samples=$frames max_gap_ns=$maxGap")
    }
}
