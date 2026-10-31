import com.jethuit.spw.listenstats.PluginState
import com.jethuit.spw.listenstats.data.DataFiles
import com.jethuit.spw.listenstats.data.LiveSession
import com.jethuit.spw.listenstats.data.LiveStore
import com.jethuit.spw.listenstats.data.ListeningSpan
import com.jethuit.spw.listenstats.data.PerformanceProbe
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import com.sun.management.ThreadMXBean

private data class Gc(val count: Long, val timeMs: Long)

private fun usedHeap(): Long {
    val runtime = Runtime.getRuntime()
    return runtime.totalMemory() - runtime.freeMemory()
}

private fun gc(): Gc {
    val beans = ManagementFactory.getGarbageCollectorMXBeans()
    return Gc(beans.sumOf { it.collectionCount.takeIf { value -> value >= 0 } ?: 0L },
        beans.sumOf { it.collectionTime.takeIf { value -> value >= 0 } ?: 0L })
}

private fun allocated(): Long {
    val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean ?: return -1L
    if (!bean.isThreadAllocatedMemorySupported) return -1L
    if (!bean.isThreadAllocatedMemoryEnabled) runCatching { bean.isThreadAllocatedMemoryEnabled = true }
    return runCatching { bean.getThreadAllocatedBytes(Thread.currentThread().id) }.getOrDefault(-1L)
}

private class PeakSampler {
    @Volatile private var running = true
    @Volatile var peak = usedHeap()
    private val worker = thread(start = true, isDaemon = true, name = "listen-stats-save-heap") {
        while (running) {
            peak = maxOf(peak, usedHeap())
            try { Thread.sleep(1) } catch (_: InterruptedException) { break }
        }
    }
    fun close(): Long {
        running = false
        worker.join(1000)
        peak = maxOf(peak, usedHeap())
        return peak
    }
}

private fun populate(count: Int) {
    repeat(count) { index ->
        val start = 1_700_000_000_000L + index * 10_000L
        LiveStore.upsert(LiveSession(
            id = "session-$index",
            trackId = "track-${index % 1000}",
            startTimeMs = start,
            durationMs = 6_000,
            title = "歌曲${index % 1000}",
            artist = "歌手${index % 31}",
            album = "专辑${index % 47}",
            albumArtist = "歌手${index % 31}",
            trackDurationMs = 10_000,
            finalized = true,
            spans = mutableListOf(ListeningSpan(start, 6_000))
        ))
    }
}

fun main(args: Array<String>) {
    val root = Path.of(args.first()).resolve("live-save")
    Files.createDirectories(root)
    val output = StringBuilder("sessions\twall_ms\tjson_bytes\theap_peak_bytes\tthread_allocated_bytes\tgc_count\tgc_time_ms\n")
    try {
        listOf(10_000, 100_000, 500_000).forEach { count ->
            val caseRoot = root.resolve(count.toString())
            Files.createDirectories(caseRoot)
            PluginState.pluginPath = caseRoot.resolve("workshop/plugins/listenstats").toString()
            PluginState.pluginId = "com.jethuit.spw.listenstats"
            PerformanceProbe.sink = null
            PerformanceProbe.counterSink = null
            LiveStore.load()
            populate(count)
            val beforeGc = gc()
            val beforeAllocated = allocated()
            val sampler = PeakSampler()
            val started = System.nanoTime()
            LiveStore.save()
            val wallMs = (System.nanoTime() - started) / 1_000_000.0
            val peak = sampler.close()
            val afterGc = gc()
            val afterAllocated = allocated()
            val file = DataFiles.directory().resolve("live-sessions.json")
            val bytes = Files.size(file)
            val allocation = if (beforeAllocated >= 0 && afterAllocated >= 0) afterAllocated - beforeAllocated else -1L
            output.appendLine(listOf(count, wallMs, bytes, peak, allocation,
                afterGc.count - beforeGc.count, afterGc.timeMs - beforeGc.timeMs).joinToString("\t"))
            println("sessions=$count wall_ms=${"%.3f".format(wallMs)} json_mb=${"%.1f".format(bytes / 1048576.0)} " +
                "heap_peak_mb=${"%.1f".format(peak / 1048576.0)} alloc_mb=${"%.1f".format(allocation / 1048576.0)} " +
                "gc=${afterGc.count - beforeGc.count}/${afterGc.timeMs - beforeGc.timeMs}ms")
        }
    } finally {
        PerformanceProbe.sink = null
        PerformanceProbe.counterSink = null
    }
    Files.writeString(root.resolve("live-save.tsv"), output.toString())
    println("report=${root.resolve("live-save.tsv")}")
    println("PASS live store save analysis")
}
