@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

import com.jethuit.spw.listenstats.PluginState
import com.jethuit.spw.listenstats.data.ListeningSpan
import com.jethuit.spw.listenstats.data.LiveSession
import com.jethuit.spw.listenstats.data.LiveStore
import com.jethuit.spw.listenstats.data.PerformanceProbe
import com.jethuit.spw.listenstats.data.StatsData
import com.jethuit.spw.listenstats.data.TrackMeta
import com.jethuit.spw.listenstats.data.ListenEntry
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.lang.management.ManagementFactory
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

private val stages = listOf(
    "LiveStore.merge", "LiveStore.matchCandidates", "LiveStore.buildHostEvents",
    "LiveStore.indexHostRows", "LiveStore.groupHostByTrack", "LiveStore.buildNextHostStart",
    "ListeningEvents.union", "ListeningEvents.unionCore", "ListeningEvents.aggregate",
    "ListeningEvents.aggregateMetadata", "ListeningEvents.aggregateResolve",
    "ListeningEvents.aggregateTimeSplit"
)

private class Trace {
    val nanos = linkedMapOf<String, Long>()
    var sourceIdCalls = 0L
    fun stage(name: String, elapsed: Long) { nanos[name] = (nanos[name] ?: 0L) + elapsed }
}

private fun heapUsed(): Long {
    val runtime = Runtime.getRuntime()
    return runtime.totalMemory() - runtime.freeMemory()
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

private fun syntheticHost(rows: Int, trackCount: Int): StatsData {
    val tracks = LinkedHashMap<String, TrackMeta>()
    repeat(trackCount) { index ->
        val id = "track-$index"
        tracks[id] = TrackMeta(id, "歌曲$index", "歌手${index % 17}", "专辑${index % 31}", "", 10_000)
    }
    val listens = ArrayList<ListenEntry>(rows)
    repeat(rows) { index ->
        listens += ListenEntry(
            id = "host-$index",
            trackId = "track-${index % trackCount}",
            startTimeMs = 1_700_000_000_000L + index * 10_000L,
            durationMs = 6_000
        )
    }
    return StatsData(
        listens = listens,
        tracks = tracks,
        dailyMillis = emptyMap(),
        hourlyMillis = emptyMap(),
        playCounts = emptyMap(),
        covers = emptyMap(),
        loadedAt = 0L,
        databaseFound = true,
        error = null
    )
}

private fun addLiveSessions(rows: Int, count: Int) {
    repeat(count) { index ->
        val track = TrackMeta("track-0", "歌曲0", "歌手0", "专辑0", "", 10_000)
        val start = 1_700_000_000_000L + (index % rows) * 10_000L
        LiveStore.upsert(LiveSession(
            id = "live-$index",
            trackId = track.id,
            startTimeMs = start,
            durationMs = 3_000,
            title = track.title,
            artist = track.artist,
            album = track.album,
            albumArtist = track.albumArtist,
            trackDurationMs = track.durationMs,
            finalized = true,
            spans = mutableListOf(ListeningSpan(start, 3_000)),
            lifecycleId = "lifecycle-$index"
        ))
    }
}

private fun runCase(root: Path, name: String, rows: Int, trackCount: Int, liveCount: Int) {
    val caseRoot = root.resolve(name)
    Files.createDirectories(caseRoot)
    PluginState.pluginPath = caseRoot.resolve("workshop/plugins/listenstats").toString()
    PluginState.pluginId = "com.jethuit.spw.listenstats"
    LiveStore.load()
    val host = syntheticHost(rows, trackCount)
    if (liveCount > 0) addLiveSessions(rows, liveCount)
    val trace = Trace()
    PerformanceProbe.sink = { stage, elapsed -> trace.stage(stage, elapsed) }
    PerformanceProbe.counterSink = { nameValue -> if (nameValue == "DataFiles.sourceId") trace.sourceIdCalls++ }
    val beforeHeap = heapUsed()
    val started = System.nanoTime()
    try {
        LiveStore.merge(host)
    } finally {
        PerformanceProbe.sink = null
        PerformanceProbe.counterSink = null
    }
    val wallMs = (System.nanoTime() - started) / 1_000_000.0
    val values = stages.associateWith { (trace.nanos[it] ?: 0L) / 1_000_000.0 }
    println("shape=$name rows=$rows tracks=$trackCount live=$liveCount " +
        "wall_ms=${"%.3f".format(Locale.ROOT, wallMs)} " +
        "match_ms=${"%.3f".format(Locale.ROOT, values["LiveStore.matchCandidates"])} " +
        "union_ms=${"%.3f".format(Locale.ROOT, values["ListeningEvents.union"])} " +
        "aggregate_ms=${"%.3f".format(Locale.ROOT, values["ListeningEvents.aggregate"])} " +
        "heap_delta_mb=${"%.1f".format(Locale.ROOT, (heapUsed() - beforeHeap) / 1048576.0)} " +
        "source_id_calls=${trace.sourceIdCalls}")
    val report = caseRoot.resolve("result.tsv")
    val header = listOf("shape", "rows", "tracks", "live", "wall_ms", "heap_delta_bytes", "source_id_calls") + stages
    val line = listOf(name, rows, trackCount, liveCount, wallMs, heapUsed() - beforeHeap, trace.sourceIdCalls) +
        stages.map { values[it] ?: 0.0 }
    Files.writeString(report, header.joinToString("\t") + "\n" + line.joinToString("\t") + "\n")
}

fun main(args: Array<String>) {
    Class.forName("org.sqlite.JDBC")
    allowLibraryRead()
    val root = Path.of(args.first()).resolve("merge-shapes")
    Files.createDirectories(root)
    try {
        listOf(
            Triple("dense-10k", 10_000, 1),
            Triple("dense-100k", 100_000, 1),
            Triple("wide-100k", 100_000, 1_000)
        ).forEach { (name, rows, tracks) -> runCase(root, name, rows, tracks, 0) }
        runCase(root, "live-100k-100", 100_000, 1, 100)
        runCase(root, "live-10k-1000", 10_000, 1, 1_000)
    } finally {
        PerformanceProbe.sink = null
        PerformanceProbe.counterSink = null
    }
    println("PASS merge shape analysis")
}
