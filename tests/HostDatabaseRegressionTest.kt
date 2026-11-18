import com.jethuit.spw.listenstats.data.HostDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneId

private fun exec(connection: java.sql.Connection, sql: String) {
    connection.createStatement().use { it.executeUpdate(sql) }
}

fun main(args: Array<String>) {
    Class.forName("org.sqlite.JDBC")
    val root = Path.of(args.single())
    Files.createDirectories(root)
    val database = root.resolve("host.db")
    val epoch = Instant.parse("2026-10-20T00:00:00Z").toEpochMilli()
    DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
        connection.autoCommit = false
        exec(connection, "CREATE TABLE Track (id TEXT PRIMARY KEY, title TEXT, artist TEXT, album TEXT, albumArtist TEXT, duration INTEGER)")
        exec(connection, "CREATE TABLE Listen (id TEXT PRIMARY KEY, trackId TEXT, startTime INTEGER, duration INTEGER)")
        exec(connection, "INSERT INTO Track VALUES ('one', '第一首', '歌手', '专辑', '', 10000)")
        exec(connection, "INSERT INTO Track VALUES ('two', '第二首', '歌手', '专辑', '', 20000)")
        exec(connection, "INSERT INTO Listen VALUES ('a', 'one', ${epoch + 2000}, 6000)")
        exec(connection, "INSERT INTO Listen VALUES ('b', 'two', ${epoch + 2000}, 12000)")
        connection.commit()
    }

    HostDatabase.close()
    val first = HostDatabase.load(database, ZoneId.of("UTC"), aggregate = true)
    check(first.listens.map { it.id } == listOf("a", "b"))
    check(first.tracks["one"]?.title == "第一首")
    check(first.playCount("one") == 1 && first.playCount("two") == 1)
    check(first.dailyMillis.values.sum() == 18_000L)

    val unchanged = HostDatabase.load(database, ZoneId.of("UTC"), aggregate = false)
    check(unchanged.listens == first.listens) { "unchanged refresh changed the cached rows" }
    check(HostDatabase.load(database, ZoneId.of("UTC"), aggregate = false) === unchanged) {
        "unchanged refresh rebuilt the derived snapshot"
    }

    DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
        connection.autoCommit = false
        // Same timestamp as an existing row exercises the rowid cursor rather than a
        // (timestamp, id) boundary. The earlier start time exercises final ordering.
        exec(connection, "INSERT INTO Listen VALUES ('c', 'one', ${epoch + 1000}, 7000)")
        exec(connection, "INSERT INTO Listen VALUES ('d', 'two', ${epoch + 2000}, 15000)")
        exec(connection, "INSERT INTO Listen VALUES (NULL, 'one', ${epoch + 2500}, 5000)")
        connection.commit()
    }
    val appended = HostDatabase.load(database, ZoneId.of("UTC"), aggregate = false)
    check(appended.listens.map { it.id } == listOf("c", "a", "b", "d", "one-${epoch + 2500}"))
    check(appended.listens.size == 5)

    DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
        connection.autoCommit = false
        exec(connection, "UPDATE Listen SET duration = 8000 WHERE id = 'a'")
        exec(connection, "INSERT INTO Listen VALUES ('e', 'one', ${epoch + 3000}, 9000)")
        connection.commit()
    }
    val updatedAndAppended = HostDatabase.load(database, ZoneId.of("UTC"), aggregate = false)
    check(updatedAndAppended.listens.single { it.id == "a" }.durationMs == 8000L)
    check(updatedAndAppended.listens.single { it.id == "e" }.durationMs == 9000L)

    DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
        connection.autoCommit = false
        exec(connection, "UPDATE Track SET title = '第一首（更新）' WHERE id = 'one'")
        connection.commit()
    }
    val metadataUpdated = HostDatabase.load(database, ZoneId.of("UTC"), aggregate = false)
    check(metadataUpdated.tracks["one"]?.title == "第一首（更新）")

    DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
        connection.autoCommit = false
        exec(connection, "DELETE FROM Listen WHERE id = 'a'")
        connection.commit()
    }
    val deleted = HostDatabase.load(database, ZoneId.of("UTC"), aggregate = false)
    check(deleted.listens.map { it.id } == listOf("c", "b", "d", "one-${epoch + 2500}", "e"))

    DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
        exec(connection, "WITH RECURSIVE n(i) AS (VALUES(1) UNION ALL SELECT i+1 FROM n WHERE i<100000) " +
            "INSERT INTO Listen SELECT 'pad-'||i,'one',1000+i,100 FROM n")
        HostDatabase.load(database)
        exec(connection, "UPDATE Listen SET trackId='one' WHERE id='b'")
        val start = System.nanoTime()
        val changed = HostDatabase.load(database, aggregate = false)
        check(changed.listens.single { it.id == "b" }.trackId == "one")
        println("100000 older history rows, modified database refresh_ms=${(System.nanoTime() - start) / 1_000_000}")
        exec(connection, "INSERT INTO Listen VALUES ('append-after-verification','two',200000,100)")
        val appendStart = System.nanoTime()
        val appendedAfterRepair = HostDatabase.load(database, aggregate = false)
        check(appendedAfterRepair.listens.size == changed.listens.size + 1)
        println("100000 history rows, complete prefix verification and append_ms=${(System.nanoTime() - appendStart) / 1_000_000}")

    }

    HostDatabase.close()
    println("PASS host database uses append refresh, preserves same-time rows, and falls back for edits/deletes")
}
