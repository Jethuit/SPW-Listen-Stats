package com.jethuit.spw.listenstats.data

import com.jethuit.spw.listenstats.PluginState
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

object DataFiles {
    fun directory(): Path {
        val root = PluginState.pluginPath?.let { Path.of(it).parent?.parent?.parent }
            ?: Path.of(System.getenv("APPDATA") ?: System.getProperty("java.io.tmpdir"))
                .resolve("Salt Player for Windows")
        return root.resolve("workshop/data").resolve(PluginState.pluginId)
    }

    /** Installation identity scopes host database IDs; never equate tracks just by their title. */
    @Synchronized fun sourceId(): String {
        val path = directory().resolve("source-id.txt")
        if (Files.exists(path)) {
            val parsed = runCatching { UUID.fromString(Files.readString(path).trim()) }.getOrNull()
            if (parsed != null) return parsed.toString()
        }
        val id = UUID.randomUUID().toString()
        writeAtomic(path, id)
        return id
    }

    fun writeAtomic(target: Path, text: String) {
        Files.createDirectories(target.toAbsolutePath().parent)
        val temporary = Files.createTempFile(target.toAbsolutePath().parent, ".listen-stats-", ".tmp")
        try {
            Files.writeString(temporary, text)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temporary) }
    }
}
