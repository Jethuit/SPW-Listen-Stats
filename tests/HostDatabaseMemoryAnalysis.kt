import com.jethuit.spw.listenstats.PluginState
import com.jethuit.spw.listenstats.data.HostDatabase
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.lang.management.ManagementFactory
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.concurrent.thread

private fun exec(connection: Connection, sql: String) {
    connection.createStatement().use { it.executeUpdate(sql) }
}

private fun usedHeap(): Long {
    val runtime = Runtime.getRuntime()
    return runtime.totalMemory() - runtime.freeMemory()
}

private fun createDatabase(path: Path, rows: Int) {
    DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
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

fun main(args: Array<String>) {
    Class.forName("org.sqlite.JDBC")
    allowLibraryRead()
    val root = Path.of(args.first()).resolve("host-memory")
    Files.createDirectories(root)
    val output = StringBuilder("rows\tvalid_rows\traw_rows\ttrack_rows\theap_after_bytes\theap_delta_bytes\n")
    try {
        listOf(10_000, 100_000, 500_000).forEach { rows ->
            val caseRoot = root.resolve(rows.toString())
            Files.createDirectories(caseRoot)
            val database = caseRoot.resolve("spw.db")
            createDatabase(database, rows)
            PluginState.pluginPath = caseRoot.resolve("workshop/plugins/listenstats").toString()
            PluginState.pluginId = "com.jethuit.spw.listenstats"
            System.gc()
            Thread.sleep(50)
            val before = usedHeap()
            HostDatabase.load(database, aggregate = false)
            val after = usedHeap()
            val metrics = checkNotNull(HostDatabase.cacheMetrics())
            output.appendLine(listOf(rows, metrics.validRows, metrics.rawRows, metrics.trackRows, after, after - before).joinToString("\t"))
            println("rows=$rows valid_rows=${metrics.validRows} raw_rows=${metrics.rawRows} tracks=${metrics.trackRows} " +
                "heap_after_mb=${"%.1f".format(after / 1048576.0)} heap_delta_mb=${"%.1f".format((after - before) / 1048576.0)}")
        }
    } finally {
        HostDatabase.close()
    }
    Files.writeString(root.resolve("host-memory.tsv"), output.toString())
    println("report=${root.resolve("host-memory.tsv")}")
    println("PASS host database memory analysis")
}
