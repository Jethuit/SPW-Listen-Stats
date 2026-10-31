package com.jethuit.spw.listenstats.data

import com.jethuit.spw.listenstats.PluginState
import com.jethuit.spw.listenstats.PluginLog
import kotlinx.serialization.json.*
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

object DataFiles {
    private var backedUpIdentity: Pair<Path, String>? = null
    fun directory(): Path {
        val root = PluginState.pluginPath?.let { Path.of(it).parent?.parent?.parent }
            ?: Path.of(System.getenv("APPDATA") ?: System.getProperty("java.io.tmpdir"))
                .resolve("Salt Player for Windows")
        return root.resolve("workshop/data").resolve(PluginState.pluginId)
    }

    /** Installation identity scopes host database IDs; never equate tracks just by their title. */
    @Synchronized fun sourceId(): String {
        PerformanceProbe.mark("DataFiles.sourceId")
        val directory = directory()
        val path = directory.resolve("source-id.txt")
        val backup = directory.resolve("source-id.backup.txt")
        fun readId(file: Path) = runCatching { validId(Files.readString(file).trim()) }.getOrNull()
        val primary = readId(path)
        val id = primary ?: readId(backup) ?: recoverLocalSource(directory) ?: UUID.randomUUID().toString()
        val identity = directory.toAbsolutePath() to id
        if (backedUpIdentity != identity) {
            // A failed redundant copy must not make an otherwise valid identity unreadable.
            runCatching { writeAtomic(backup, id) }.onFailure {
                PluginLog.error("saving source identity backup failed", it)
            }
            backedUpIdentity = identity
        }
        if (primary == null) writeAtomic(path, id)
        return id
    }

    private fun validId(text: String): String? = runCatching {
        UUID.fromString(text).toString().takeIf { it.equals(text, ignoreCase = true) }
    }.getOrNull()

    private fun recoverLocalSource(directory: Path): String? = runCatching {
        // Read only this installation's live store, never infer identity from foreign imports.
        val root = Json.parseToJsonElement(Files.readString(directory.resolve("live-sessions.json"))).jsonObject
        root["sourceId"]?.jsonPrimitive?.contentOrNull?.let { validId(it) } ?: root["sessions"]?.jsonArray
            ?.mapNotNull { item ->
                item.jsonObject["hostId"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.startsWith("host:") }?.removePrefix("host:")
                    ?.substringBefore(':')?.let { validId(it) }
            }?.distinct()?.singleOrNull()
    }.getOrNull()

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
