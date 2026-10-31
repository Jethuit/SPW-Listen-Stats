@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

import com.jethuit.spw.listenstats.PluginState
import com.jethuit.spw.listenstats.data.BackupBundle
import com.jethuit.spw.listenstats.data.BackupCodec
import com.jethuit.spw.listenstats.data.HostDatabase
import com.jethuit.spw.listenstats.data.ListeningEvent
import com.jethuit.spw.listenstats.data.ListeningSpan
import com.jethuit.spw.listenstats.data.LiveSession
import com.jethuit.spw.listenstats.data.LiveStore
import com.jethuit.spw.listenstats.data.PerformanceProbe
import com.jethuit.spw.listenstats.data.RankingMetric
import com.jethuit.spw.listenstats.data.RankingMode
import com.jethuit.spw.listenstats.data.Rankings
import com.jethuit.spw.listenstats.data.StatsData
import com.jethuit.spw.listenstats.data.StatsStore
import com.jethuit.spw.listenstats.data.TrackMeta
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.sun.management.ThreadMXBean
import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.Locale
import kotlin.concurrent.thread

private val stageNames = listOf(
    "StatsStore.load", "HostDatabase.load", "HostDatabase.refreshChanged",
    "HostDatabase.readRows", "HostDatabase.readTracks", "HostDatabase.buildData",
    "LiveStore.merge", "LiveStore.matchCandidates", "LiveStore.buildHostEvents",
    "LiveStore.indexHostRows", "LiveStore.groupHostByTrack", "LiveStore.buildNextHostStart",
    "LiveStore.buildLiveEvents", "ListeningEvents.union", "ListeningEvents.unionCore",
    "ListeningEvents.aggregate", "ListeningEvents.aggregateMetadata",
    "ListeningEvents.aggregateResolve", "ListeningEvents.aggregateTimeSplit",
    "Rankings.index"
)

private data class GcSnapshot(val count: Long, val timeMs: Long)

private data class OperationResult(
    val wallMs: Double,
    val heapBefore: Long,
    val heapAfter: Long,
    val heapPeak: Long,
    val allocatedBytes: Long,
    val gcCount: Long,
    val gcTimeMs: Long,
    val sourceIdCalls: Long,
    val stages: Map<String, Long>
)

private class TraceCollector {
    private val values = linkedMapOf<String, Long>()
    private val counts = linkedMapOf<String, Long>()
    fun record(stage: String, elapsedNanos: Long) {
        values[stage] = (values[stage] ?: 0L) + elapsedNanos
    }
    fun mark(name: String) { counts[name] = (counts[name] ?: 0L) + 1L }
    fun count(name: String): Long = counts[name] ?: 0L
    fun snapshot(): Map<String, Long> = values.toMap()
}

private fun usedHeap(): Long {
    val runtime = Runtime.getRuntime()
    return runtime.totalMemory() - runtime.freeMemory()
}

private fun gcSnapshot(): GcSnapshot {
    val beans = ManagementFactory.getGarbageCollectorMXBeans()
    return GcSnapshot(
        beans.sumOf { it.collectionCount.takeIf { count -> count >= 0 } ?: 0L },
        beans.sumOf { it.collectionTime.takeIf { time -> time >= 0 } ?: 0L }
    )
}

private fun allocatedBytes(): Long {
    val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean ?: return -1L
    if (!bean.isThreadAllocatedMemorySupported) return -1L
    if (!bean.isThreadAllocatedMemoryEnabled) runCatching { bean.isThreadAllocatedMemoryEnabled = true }
    return runCatching { bean.getThreadAllocatedBytes(Thread.currentThread().id) }.getOrDefault(-1L)
}

private class HeapSampler {
    @Volatile private var running = true
    @Volatile var peak: Long = usedHeap()
        private set
    private val worker = thread(start = true, isDaemon = true, name = "listen-stats-heap-sampler") {
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

private var activeTrace: TraceCollector? = null

private fun <T> measureOperation(action: () -> T): Pair<OperationResult, T> {
    val collector = TraceCollector()
    activeTrace = collector
    val gcBefore = gcSnapshot()
    val allocatedBefore = allocatedBytes()
    val heapBefore = usedHeap()
    val sampler = HeapSampler()
    val started = System.nanoTime()
    return try {
        val value = action()
        val wallMs = (System.nanoTime() - started) / 1_000_000.0
        val heapPeak = sampler.close()
        val gcAfter = gcSnapshot()
        val allocatedAfter = allocatedBytes()
        OperationResult(
            wallMs = wallMs,
            heapBefore = heapBefore,
            heapAfter = usedHeap(),
            heapPeak = heapPeak,
            allocatedBytes = if (allocatedBefore >= 0 && allocatedAfter >= 0) allocatedAfter - allocatedBefore else -1L,
            gcCount = gcAfter.count - gcBefore.count,
            gcTimeMs = gcAfter.timeMs - gcBefore.timeMs,
            sourceIdCalls = collector.count("DataFiles.sourceId"),
            stages = collector.snapshot()
        ) to value
    } finally {
        activeTrace = null
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

private fun createDatabase(database: Path, rows: Int, media: Path? = null) {
    media?.let { Files.writeString(it, "synthetic media") }
    DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
        exec(connection, "PRAGMA journal_mode=OFF")
        exec(connection, "PRAGMA synchronous=OFF")
        connection.autoCommit = false
        exec(connection, "CREATE TABLE Track (id TEXT PRIMARY KEY, title TEXT, artist TEXT, album TEXT, albumArtist TEXT, duration INTEGER, path TEXT)")
        exec(connection, "CREATE TABLE Listen (id TEXT PRIMARY KEY, trackId TEXT, startTime INTEGER, duration INTEGER)")
        val path = media?.toString()?.replace("'", "''") ?: ""
        exec(connection, "INSERT INTO Track VALUES ('track', '基准歌曲', '歌手', '专辑', '', 10000, '$path')")
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

private fun appendRows(database: Path, prefix: String, startIndex: Int, count: Int) {
    DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
        connection.prepareStatement("INSERT INTO Listen VALUES (?, 'track', ?, 6000)").use { statement ->
            repeat(count) { offset ->
                val index = startIndex + offset
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

private fun updateMetadata(database: Path) {
    DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
        exec(connection, "UPDATE Track SET title = '基准歌曲（更新）' WHERE id = 'track'")
    }
}

private fun stageMs(result: OperationResult, stage: String): Double =
    (result.stages[stage] ?: 0L) / 1_000_000.0

private fun writeMetricsDefinition(root: Path) {
    Files.writeString(root.resolve("performance-analysis-metrics.txt"), """
wall_ms: end-to-end wall-clock time of the measured operation.
heap_*_bytes: JVM used heap (totalMemory - freeMemory); heap_peak_bytes is sampled every ~1ms and is not total allocation.
thread_allocated_bytes: cumulative bytes allocated by the benchmark thread, when the JVM exposes ThreadMXBean allocation counters; this is not live memory usage.
gc_count/gc_time_ms: deltas from GarbageCollectorMXBeans during the operation.
source_id_calls: number of DataFiles.sourceId() invocations during the operation; this is a call count, not file-read count.
stage_*_ms: nested stage timers from PerformanceProbe; do not sum them because stages can be nested.
Synthetic data only; no personal listening history is read.
""".trimIndent())
}

private fun configure(root: Path, database: Path) {
    val pluginPath = root.resolve("workshop/plugins/listenstats")
    PluginState.pluginPath = pluginPath.toString()
    PluginState.pluginId = "com.jethuit.spw.listenstats"
    LiveStore.load()
    StatsStore.initialize(pluginPath.toString())
    HostDatabase.load(database, aggregate = false)
}

private fun runLoadAndRanking(): Pair<OperationResult, OperationResult> {
    val (loadResult, data) = measureOperation { StatsStore.load() }
    val (rankingResult, _) = measureOperation { Rankings.index(data, RankingMode.Song, RankingMetric.Count) }
    return loadResult to rankingResult
}

private fun invalidationChecks(root: Path) {
    val caseRoot = root.resolve("p0-checks")
    Files.createDirectories(caseRoot)
    val database = caseRoot.resolve("spw.db")
    val media = caseRoot.resolve("media.m4a")
    createDatabase(database, 10_000, media)
    configure(caseRoot, database)
    check(StatsStore.load().events.isNotEmpty())

    Files.delete(media)
    val visibility = StatsStore.load()
    check(visibility.events.isEmpty()) { "visibility change did not invalidate the P0 cache" }
    Files.writeString(media, "synthetic media restored")

    val liveTrack = TrackMeta("live-track", "实时歌曲", "歌手", "专辑", "", 10_000)
    LiveStore.upsert(LiveSession("live", liveTrack.id, 1_800_000_000_000L, 6_000,
        liveTrack.title, liveTrack.artist, liveTrack.album, liveTrack.albumArtist,
        liveTrack.durationMs, finalized = true,
        spans = mutableListOf(ListeningSpan(1_800_000_000_000L, 6_000))))
    check(StatsStore.load().events.any { it.track.id == liveTrack.id }) { "live change did not invalidate the P0 cache" }

    val backupTrack = TrackMeta("backup-track", "备份歌曲", "歌手", "专辑", "", 10_000)
    val imported = caseRoot.resolve("workshop/data/com.jethuit.spw.listenstats/imported.json")
    Files.createDirectories(imported.parent)
    Files.writeString(imported, BackupCodec.encode(BackupBundle(listOf(
        ListeningEvent("backup", backupTrack, listOf(ListeningSpan(1_800_000_100_000L, 6_000)))
    ))))
    check(StatsStore.load().events.any { it.track.id == backupTrack.id }) { "backup change did not invalidate the P0 cache" }

    updateMetadata(database)
    check(StatsStore.load().tracks["track"]?.title == "基准歌曲（更新）") {
        "host metadata change did not invalidate the P0 cache"
    }

    val crossDay = LiveSession("cross-day", liveTrack.id, 1_799_999_900_000L, 200_000,
        liveTrack.title, liveTrack.artist, liveTrack.album, liveTrack.albumArtist,
        liveTrack.durationMs, finalized = true,
        spans = mutableListOf(ListeningSpan(1_799_999_900_000L, 200_000)))
    LiveStore.upsert(crossDay)
    val crossDayData = StatsStore.load()
    check(crossDayData.dailyMillis.values.sum() > 0L) { "cross-day live data was not refreshed" }
    println("p0_checks=pass visibility, live, backup, metadata, and cross-day invalidation")
}

private fun runDataset(root: Path, rows: Int, report: MutableList<Map<String, String>>, profileOnly: Boolean) {
    val caseRoot = root.resolve("dataset-$rows")
    Files.createDirectories(caseRoot)
    val database = caseRoot.resolve("spw.db")
    createDatabase(database, rows)
    configure(caseRoot, database)
    StatsStore.load() // cold warmup is intentionally excluded from scenario rows.

    fun record(scenario: String, result: OperationResult, ranking: OperationResult) {
        val values = linkedMapOf<String, String>()
        values["rows"] = rows.toString()
        values["scenario"] = scenario
        values["wall_ms"] = "%.3f".format(Locale.ROOT, result.wallMs)
        values["heap_before_bytes"] = result.heapBefore.toString()
        values["heap_after_bytes"] = result.heapAfter.toString()
        values["heap_peak_bytes"] = result.heapPeak.toString()
        values["thread_allocated_bytes"] = result.allocatedBytes.toString()
        values["gc_count"] = result.gcCount.toString()
        values["gc_time_ms"] = result.gcTimeMs.toString()
        values["source_id_calls"] = result.sourceIdCalls.toString()
        values["ranking_wall_ms"] = "%.3f".format(Locale.ROOT, ranking.wallMs)
        values["ranking_allocated_bytes"] = ranking.allocatedBytes.toString()
        for (stage in stageNames) values["stage_${stage.replace('.', '_')}_ms"] =
            "%.3f".format(Locale.ROOT, stageMs(result, stage))
        for (stage in stageNames) values["ranking_${stage.replace('.', '_')}_ms"] =
            "%.3f".format(Locale.ROOT, stageMs(ranking, stage))
        report += values
        println("rows=$rows scenario=$scenario wall_ms=${values["wall_ms"]} " +
            "host_load_ms=${values["stage_HostDatabase_load_ms"]} " +
            "host_build_ms=${values["stage_HostDatabase_buildData_ms"]} " +
            "merge_ms=${values["stage_LiveStore_merge_ms"]} " +
            "union_ms=${values["stage_ListeningEvents_union_ms"]} " +
            "aggregate_ms=${values["stage_ListeningEvents_aggregate_ms"]} " +
            "source_id_calls=${values["source_id_calls"]} " +
            "ranking_ms=${values["ranking_wall_ms"]} " +
            "alloc_bytes=${values["thread_allocated_bytes"]} gc=${values["gc_count"]}")
    }

    if (!profileOnly) runLoadAndRanking().also { record("unchanged", it.first, it.second) }
    appendRows(database, "append-single", rows, 1)
    runLoadAndRanking().also { record("single_append", it.first, it.second) }
    if (profileOnly) return
    appendRows(database, "append-batch", rows + 1, 1_000)
    runLoadAndRanking().also { record("batch_append_1000", it.first, it.second) }
    updateHistory(database, rows / 2)
    runLoadAndRanking().also { record("history_update", it.first, it.second) }
    deleteHistory(database, rows / 2)
    runLoadAndRanking().also { record("history_delete", it.first, it.second) }
}

fun main(args: Array<String>) {
    Class.forName("org.sqlite.JDBC")
    allowLibraryRead()
    val root = Path.of(args.first()).resolve("stats-performance-analysis")
    Files.createDirectories(root)
    writeMetricsDefinition(root)
    PerformanceProbe.sink = { stage, elapsed -> activeTrace?.record(stage, elapsed) }
    PerformanceProbe.counterSink = { name -> activeTrace?.mark(name) }
    val report = mutableListOf<Map<String, String>>()
    val profileOnly = args.getOrNull(1) == "profile"
    try {
        val sizes = if (profileOnly) listOf(10_000, 100_000, 500_000) else listOf(10_000, 100_000, 500_000)
        sizes.forEach { runDataset(root, it, report, profileOnly) }
        invalidationChecks(root)
    } finally {
        PerformanceProbe.sink = null
        PerformanceProbe.counterSink = null
        HostDatabase.close()
    }
    val columns = report.flatMap { it.keys }.distinct()
    val output = buildString {
        appendLine(columns.joinToString("\t"))
        report.forEach { row -> appendLine(columns.joinToString("\t") { row[it].orEmpty() }) }
    }
    Files.writeString(root.resolve("performance-analysis.tsv"), output)
    println("report=${root.resolve("performance-analysis.tsv")}")
    println("metrics=${root.resolve("performance-analysis-metrics.txt")}")
    println("PASS statistics performance analysis")
}
