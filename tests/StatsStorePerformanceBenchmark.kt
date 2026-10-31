@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

import com.jethuit.spw.listenstats.PluginState
import com.jethuit.spw.listenstats.data.HostDatabase
import com.jethuit.spw.listenstats.data.LiveStore
import com.jethuit.spw.listenstats.data.StatsStore
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

private data class Timing(val name: String, val millis: Double)

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

private fun measure(name: String, action: () -> Unit): Timing {
    val started = System.nanoTime()
    action()
    return Timing(name, (System.nanoTime() - started) / 1_000_000.0)
}

private fun median(values: List<Double>): Double = values.sorted()[values.size / 2]

private fun percentile95(values: List<Double>): Double {
    val sorted = values.sorted()
    return sorted[((sorted.size - 1) * 0.95).toInt()]
}

private fun createDatabase(database: Path, rows: Int) {
    DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
        exec(connection, "PRAGMA journal_mode=OFF")
        exec(connection, "PRAGMA synchronous=OFF")
        connection.autoCommit = false
        exec(connection, "CREATE TABLE Track (id TEXT PRIMARY KEY, title TEXT, artist TEXT, album TEXT, albumArtist TEXT, duration INTEGER)")
        exec(connection, "CREATE TABLE Listen (id TEXT PRIMARY KEY, trackId TEXT, startTime INTEGER, duration INTEGER)")
        exec(connection, "INSERT INTO Track VALUES ('track', '基准歌曲', '歌手', '专辑', '', 10000)")
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

private fun appendRow(database: Path, index: Int) {
    DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
        connection.prepareStatement("INSERT INTO Listen VALUES (?, 'track', ?, 6000)").use { statement ->
            statement.setString(1, "append-$index")
            statement.setLong(2, 1_800_000_000_000L + index * 10_000L)
            statement.executeUpdate()
        }
    }
}

private fun runCase(root: Path, rows: Int) {
    val caseRoot = root.resolve(rows.toString())
    Files.createDirectories(caseRoot)
    val pluginPath = caseRoot.resolve("workshop/plugins/listenstats")
    val database = caseRoot.resolve("spw.db")
    createDatabase(database, rows)
    PluginState.pluginPath = pluginPath.toString()
    PluginState.pluginId = "com.jethuit.spw.listenstats"
    LiveStore.load()
    StatsStore.initialize(pluginPath.toString())

    val cold = measure("cold") { StatsStore.load() }
    val cacheSamples = mutableListOf<Double>()
    repeat(7) { cacheSamples += measure("cache") { StatsStore.load() }.millis }
    val appendSamples = mutableListOf<Double>()
    repeat(3) { index ->
        appendRow(database, index)
        appendSamples += measure("append") { StatsStore.load() }.millis
    }
    val all = listOf(cold) + cacheSamples.map { Timing("cache", it) } +
        appendSamples.map { Timing("append", it) }
    check(StatsStore.load().listens.size == rows + 3)
    println("rows=$rows cold_ms=${"%.1f".format(cold.millis)} " +
        "cache_median_ms=${"%.1f".format(median(cacheSamples))} " +
        "cache_p95_ms=${"%.1f".format(percentile95(cacheSamples))} " +
        "append_median_ms=${"%.1f".format(median(appendSamples))} " +
        "append_p95_ms=${"%.1f".format(percentile95(appendSamples))}")
    check(all.all { it.millis >= 0.0 })
}

fun main(args: Array<String>) {
    Class.forName("org.sqlite.JDBC")
    allowLibraryRead()
    val root = Path.of(args.single()).resolve("stats-performance")
    Files.createDirectories(root)
    listOf(10_000, 100_000, 500_000).forEach { runCase(root, it) }
    HostDatabase.close()
    println("PASS statistics performance baseline")
}
