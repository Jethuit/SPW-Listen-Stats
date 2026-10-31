@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

import com.jethuit.spw.listenstats.PluginState
import com.jethuit.spw.listenstats.data.HostDatabase
import com.jethuit.spw.listenstats.data.LiveStore
import com.jethuit.spw.listenstats.data.PerformanceProbe
import com.jethuit.spw.listenstats.data.StatsStore
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.concurrent.thread

private data class Gc(val count: Long, val timeMs: Long)

private data class Result(
    val rows: Int,
    val scenario: String,
    val wallMs: Double,
    val heapPeak: Long,
    val allocated: Long,
    val gcCount: Long,
    val gcTimeMs: Long
)

private fun usedHeap(): Long {
    val runtime = Runtime.getRuntime()
    return runtime.totalMemory() - runtime.freeMemory()
}

private fun gc(): Gc {
    val beans = ManagementFactory.getGarbageCollectorMXBeans()
    return Gc(
        beans.sumOf { it.collectionCount.takeIf { value -> value >= 0 } ?: 0L },
        beans.sumOf { it.collectionTime.takeIf { value -> value >= 0 } ?: 0L }
    )
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
    private val worker = thread(start = true, isDaemon = true, name = "listen-stats-clean-heap") {
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

private fun exec(connection: Connection, sql: String) {
    connection.createStatement().use { it.executeUpdate(sql) }
}

private fun allowLibraryRead() {
    val manager = Proxy.newProxyInstance(
        WorkshopApi.Manager::class.java.classLoader,
        arrayOf(WorkshopApi.Manager::class.java)
    ) { _, method, _ -> if (method.name == "isPermissionGranted") true else null }
    WorkshopApi.instance = Proxy.newProxyInstance(
        WorkshopApi::class.java.classLoader,
        arrayOf(WorkshopApi::class.java)
    ) { _, method, _ -> if (method.name == "getManager") manager else null } as WorkshopApi
}

private fun createDatabase(database: Path, rows: Int) {
    DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
        exec(connection, "PRAGMA journal_mode=OFF")
        exec(connection, "PRAGMA synchronous=OFF")
        connection.autoCommit = false
        exec(connection, "CREATE TABLE Track (id TEXT PRIMARY KEY, title TEXT, artist TEXT, album TEXT, albumArtist TEXT, duration INTEGER, path TEXT)")
        exec(connection, "CREATE TABLE Listen (id TEXT PRIMARY KEY, trackId TEXT, startTime INTEGER, duration INTEGER)")
        exec(connection, "INSERT INTO Track VALUES ('track', '基准歌曲', '歌手', '专辑', '', 10000, '')")
        connection.prepareStatement("INSERT INTO Listen VALUES (?, 'track', ?, 6000)").use { statement ->
            repeat(rows) { index ->
                statement.setString(1, "row-$index")
                statement.setLong(2, 1_700_000_000_000L + index * 10_000L)
                statement.addBatch()
                if (index % 5_000 == 4_999) statement.executeBatch()
            }
            statement.executeBatch()
        }
        connection.commit()
    }
}

private fun appendRows(database: Path, prefix: String, start: Int, count: Int) {
    DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
        connection.prepareStatement("INSERT INTO Listen VALUES (?, 'track', ?, 6000)").use { statement ->
            repeat(count) { offset ->
                val index = start + offset
                statement.setString(1, "$prefix-$index")
                statement.setLong(2, 1_800_000_000_000L + index * 10_000L)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }
}

private fun updateHistory(database: Path, row: Int) {
    DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
        connection.prepareStatement("UPDATE Listen SET duration = 7000 WHERE id = ?").use { statement ->
            statement.setString(1, "row-$row")
            statement.executeUpdate()
        }
    }
}

private fun deleteHistory(database: Path, row: Int) {
    DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
        connection.prepareStatement("DELETE FROM Listen WHERE id = ?").use { statement ->
            statement.setString(1, "row-$row")
            statement.executeUpdate()
        }
    }
}

private fun configure(root: Path) {
    PluginState.pluginPath = root.resolve("workshop/plugins/listenstats").toString()
    PluginState.pluginId = "com.jethuit.spw.listenstats"
    LiveStore.load()
    StatsStore.initialize(PluginState.pluginPath)
}

private fun measure(rows: Int, scenario: String, action: () -> Unit): Result {
    // Clean mode: no stage sink and no sourceId counter sink.
    PerformanceProbe.sink = null
    PerformanceProbe.counterSink = null
    val beforeGc = gc()
    val beforeAllocated = allocated()
    val sampler = PeakSampler()
    val started = System.nanoTime()
    action()
    val wallMs = (System.nanoTime() - started) / 1_000_000.0
    val peak = sampler.close()
    val afterGc = gc()
    val afterAllocated = allocated()
    return Result(
        rows = rows,
        scenario = scenario,
        wallMs = wallMs,
        heapPeak = peak,
        allocated = if (beforeAllocated >= 0 && afterAllocated >= 0) afterAllocated - beforeAllocated else -1L,
        gcCount = afterGc.count - beforeGc.count,
        gcTimeMs = afterGc.timeMs - beforeGc.timeMs
    )
}

private fun runDataset(root: Path, rows: Int, results: MutableList<Result>) {
    val caseRoot = root.resolve("dataset-$rows")
    Files.createDirectories(caseRoot)
    val database = caseRoot.resolve("spw.db")
    createDatabase(database, rows)
    configure(caseRoot)
    StatsStore.load() // Cold warmup excluded from reported rows.

    results += measure(rows, "unchanged") { check(StatsStore.load().listens.isNotEmpty()) }
    appendRows(database, "single", rows, 1)
    results += measure(rows, "single_append") { check(StatsStore.load().listens.size == rows + 1) }
    appendRows(database, "batch", rows + 1, 1_000)
    results += measure(rows, "batch_append_1000") { check(StatsStore.load().listens.size == rows + 1_001) }
    updateHistory(database, rows / 2)
    results += measure(rows, "history_update") { check(StatsStore.load().listens.size == rows + 1_001) }
    deleteHistory(database, rows / 2)
    results += measure(rows, "history_delete") { check(StatsStore.load().listens.size == rows + 1_000) }
}

fun main(args: Array<String>) {
    Class.forName("org.sqlite.JDBC")
    allowLibraryRead()
    val root = Path.of(args.first()).resolve("stats-performance-clean")
    Files.createDirectories(root)
    val results = mutableListOf<Result>()
    try {
        listOf(10_000, 100_000, 500_000).forEach { runDataset(root, it, results) }
    } finally {
        PerformanceProbe.sink = null
        PerformanceProbe.counterSink = null
        HostDatabase.close()
    }
    val report = buildString {
        appendLine("rows\tscenario\twall_ms\theap_peak_bytes\tthread_allocated_bytes\tgc_count\tgc_time_ms")
        results.forEach { result ->
            appendLine(listOf(result.rows, result.scenario,
                "%.3f".format(java.util.Locale.ROOT, result.wallMs), result.heapPeak,
                result.allocated, result.gcCount, result.gcTimeMs).joinToString("\t"))
            println("rows=${result.rows} scenario=${result.scenario} wall_ms=${"%.3f".format(java.util.Locale.ROOT, result.wallMs)} " +
                "heap_peak_mb=${"%.1f".format(java.util.Locale.ROOT, result.heapPeak / 1048576.0)} " +
                "alloc_mb=${"%.1f".format(java.util.Locale.ROOT, result.allocated / 1048576.0)} " +
                "gc=${result.gcCount}/${result.gcTimeMs}ms")
        }
    }
    Files.writeString(root.resolve("stats-performance-clean.tsv"), report)
    println("report=${root.resolve("stats-performance-clean.tsv")}")
    println("PASS clean performance benchmark")
}
