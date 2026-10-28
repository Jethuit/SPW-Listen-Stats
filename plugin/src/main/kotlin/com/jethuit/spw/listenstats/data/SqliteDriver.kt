package com.jethuit.spw.listenstats.data

import com.jethuit.spw.listenstats.PluginLog
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteJDBCLoader
import java.net.JarURLConnection
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** Owns JDBC initialization and resources that otherwise outlive the plugin class loader. */
internal object SqliteDriver {
    private var driverClass: Class<*>? = null

    @Synchronized
    fun openReadOnly(path: Path): Connection {
        if (driverClass == null) {
            driverClass = Class.forName("org.sqlite.JDBC")
            try {
                SQLiteJDBCLoader.getVersion()
                releaseVersionResource()
            } catch (failure: Throwable) {
                close()
                throw failure
            }
        }
        val config = SQLiteConfig()
        config.setReadOnly(true)
        config.setBusyTimeout(4_000)
        // Direct creation also works after stop/re-enable: JDBC's static initializer
        // registers its driver only once, while stop removes that global reference.
        return config.createConnection("jdbc:sqlite:${path.toAbsolutePath()}")
    }

    private fun releaseVersionResource() {
        val type = driverClass ?: return
        if (type.classLoader !== SqliteDriver::class.java.classLoader) return
        // SQLite JDBC 3.50.3.0's VersionHolder leaves its version stream open in the
        // JAR URL cache. Close only this plugin's archive, keeping JVM-wide caching
        // and any parent-provided JDBC driver untouched. Native extraction already
        // uses uncached, scoped streams in this version of the driver.
        val resource = type.getResource("/META-INF/maven/org.xerial/sqlite-jdbc/pom.properties")
            ?: type.getResource("/META-INF/maven/org.xerial/sqlite-jdbc/VERSION")
        val connection = resource?.openConnection() as? JarURLConnection ?: return
        connection.jarFile.close()
    }

    @Synchronized
    fun close() {
        val type = driverClass ?: return
        if (type.classLoader === SqliteDriver::class.java.classLoader) {
            DriverManager.drivers().filter { it.javaClass === type }.forEach { driver ->
                runCatching { DriverManager.deregisterDriver(driver) }
                    .onFailure { PluginLog.error("unregistering SQLite driver failed", it) }
            }
        }
        driverClass = null
    }
}
