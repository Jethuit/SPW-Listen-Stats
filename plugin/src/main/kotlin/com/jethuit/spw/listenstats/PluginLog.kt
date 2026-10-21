package com.jethuit.spw.listenstats

import java.io.File
import java.time.Instant

/** Small file logger because the packaged SPW launcher has no visible console. */
object PluginLog {
    private fun logFile(): File {
        val candidates = ArrayList<File>()
        runCatching { com.jethuit.spw.listenstats.data.DataFiles.directory().toFile() }
            .getOrNull()?.let { candidates.add(it) }
        candidates.add(File(System.getProperty("java.io.tmpdir"), "spw-listen-stats"))
        var directory: File? = null
        for (candidate in candidates) {
            if (candidate.isDirectory || candidate.mkdirs()) {
                directory = candidate
                break
            }
        }
        return File(directory ?: File(System.getProperty("java.io.tmpdir")), "status.log")
    }

    fun info(message: String) {
        write("INFO", message, null)
    }

    fun error(message: String, failure: Throwable? = null) {
        write("ERROR", message, failure)
    }

    fun diagnostics(message: String): File? = write("DIAGNOSTICS", message, null)

    @Synchronized private fun write(level: String, message: String, failure: Throwable?): File? {
        var written: File? = null
        try {
            val file = logFile()
            file.appendText("${Instant.now()} $level $message\n")
            if (failure != null) {
                file.appendText(failure.stackTraceToString() + "\n")
            }
            written = file
        } catch (ignored: Throwable) {
            // Logging must never break playback.
        }
        println("[ListenStats] $level $message")
        failure?.printStackTrace()
        return written
    }
}
