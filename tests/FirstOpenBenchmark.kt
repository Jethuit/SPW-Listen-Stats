@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

import com.jethuit.spw.listenstats.PluginState
import com.jethuit.spw.listenstats.data.*
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.lang.management.ManagementFactory
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Locale

/** Cold snapshot and unchanged refreshes; synthetic data, no live host or personal history. */
fun main(args: Array<String>) {
    val root = Path.of(args[0]).toAbsolutePath()
    val rows = args.getOrNull(1)?.toInt() ?: 50_000
    val imported = args.getOrNull(2) == "imported"
    Files.createDirectories(root)
    PluginState.pluginPath = root.resolve("workshop/plugins/listenstats").toString()
    val manager = Proxy.newProxyInstance(WorkshopApi.Manager::class.java.classLoader,
        arrayOf(WorkshopApi.Manager::class.java)) { _, method, _ ->
        check(method.name == "isPermissionGranted"); true
    }
    WorkshopApi.instance = Proxy.newProxyInstance(WorkshopApi::class.java.classLoader,
        arrayOf(WorkshopApi::class.java)) { _, method, _ ->
        check(method.name == "getManager"); manager
    } as WorkshopApi
    Class.forName("org.sqlite.JDBC")
    val media = root.resolve("synthetic-media.m4a")
    Files.writeString(media, "synthetic media")
    val tracks = (0 until 1_000).map { i ->
        TrackMeta("track-$i", "歌曲$i", "歌手${i % 37}", "专辑${i % 83}", "", 10_000, media.toString())
    }
    val start = 1_700_000_000_000L
    DriverManager.getConnection("jdbc:sqlite:${root.resolve("spw.db")}").use { c ->
        c.autoCommit = false
        c.createStatement().use { s ->
            s.executeUpdate("CREATE TABLE Track (id TEXT PRIMARY KEY, title TEXT, artist TEXT, album TEXT, albumArtist TEXT, duration INTEGER, path TEXT)")
            s.executeUpdate("CREATE TABLE Listen (id TEXT PRIMARY KEY, trackId TEXT, startTime INTEGER, duration INTEGER)")
        }
        c.prepareStatement("INSERT INTO Track VALUES (?, ?, ?, ?, ?, ?, ?)").use { s ->
            tracks.forEach { t ->
                s.setString(1, t.id); s.setString(2, t.title); s.setString(3, t.artist)
                s.setString(4, t.album); s.setString(5, t.albumArtist); s.setLong(6, t.durationMs)
                s.setString(7, t.path); s.addBatch()
            }
            s.executeBatch()
        }
        c.prepareStatement("INSERT INTO Listen VALUES (?, ?, ?, ?)").use { s ->
            repeat(rows) { i ->
                s.setString(1, "row-$i"); s.setString(2, tracks[i % tracks.size].id)
                s.setLong(3, start + i * 10_000L); s.setLong(4, 6_000); s.addBatch()
                if (i % 5_000 == 4_999) s.executeBatch()
            }
            s.executeBatch()
        }
        c.commit()
    }
    LiveStore.load()
    if (imported) {
        val source = DataFiles.sourceId()
        val events = (0 until rows).map { i -> ListeningEvent("import-$i", tracks[i % tracks.size],
            listOf(ListeningSpan(start + (rows + i) * 10_000L, 6_000)), libraryId = source) }
        DataFiles.writeAtomic(DataFiles.directory().resolve("imported.json"), BackupCodec.encode(BackupBundle(events)))
    }
    HostDatabase.close()
    StatsStore.initialize(PluginState.pluginPath)
    val stages = linkedMapOf<String, Long>()
    val counters = linkedMapOf<String, Long>()
    PerformanceProbe.sink = { stage, nanos -> stages[stage] = (stages[stage] ?: 0) + nanos }
    PerformanceProbe.counterSink = { name -> counters[name] = (counters[name] ?: 0) + 1 }
    val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    bean.isThreadAllocatedMemoryEnabled = true
    val allocated = bean.getThreadAllocatedBytes(Thread.currentThread().id)
    val started = System.nanoTime()
    try {
        val data = StatsStore.load()
        val elapsed = (System.nanoTime() - started) / 1_000_000.0
        check(data.error == null) { data.error.orEmpty() }
        check(data.events.size == rows * if (imported) 2 else 1)
        check(data.dailyMillis.values.sum() == rows * 6_000L * if (imported) 2 else 1)
        counters["TrackVisibility.fileCheck"]?.let { check(it == 1L) { "Repeated path checks: $it" } }
        val mb = (bean.getThreadAllocatedBytes(Thread.currentThread().id) - allocated) / 1048576.0
        println("COLD rows=$rows imported=$imported wall_ms=${"%.1f".format(Locale.ROOT, elapsed)} allocated_mb=${"%.1f".format(Locale.ROOT, mb)}")
        stages.forEach { (stage, nanos) -> println("STAGE $stage ms=${"%.1f".format(Locale.ROOT, nanos / 1_000_000.0)}") }
        println("COUNTERS $counters")
        PerformanceProbe.sink = null
        PerformanceProbe.counterSink = null
        repeat(5) { check(StatsStore.load() === data) }
        val samples = DoubleArray(25)
        val refreshAllocated = bean.getThreadAllocatedBytes(Thread.currentThread().id)
        for (index in samples.indices) {
            val before = System.nanoTime()
            check(StatsStore.load() === data)
            samples[index] = (System.nanoTime() - before) / 1_000_000.0
        }
        val bytesPerRefresh = (bean.getThreadAllocatedBytes(Thread.currentThread().id) - refreshAllocated) / samples.size
        samples.sort()
        println("REFRESH median_ms=${"%.3f".format(Locale.ROOT, samples[samples.size / 2])} bytes_per_refresh=$bytesPerRefresh")
    } finally {
        PerformanceProbe.sink = null
        PerformanceProbe.counterSink = null
        HostDatabase.close()
    }
}
