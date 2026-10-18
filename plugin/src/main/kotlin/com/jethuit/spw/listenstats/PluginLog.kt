package com.jethuit.spw.listenstats

import java.io.File
import java.time.Instant

/** Small file logger because the packaged SPW launcher has no visible console. */
object PluginLog {
    private val file: File by lazy {
        val candidates = ArrayList<File>()
        val appData = System.getenv("APPDATA")
        if (!appData.isNullOrBlank()) {
            candidates.add(File(appData, "Salt Player for Windows/workshop/data/com.jethuit.spw.listenstats"))
        }
        candidates.add(File(System.getProperty("java.io.tmpdir"), "spw-listen-stats"))
        var directory: File? = null
        for (candidate in candidates) {
            if (candidate.isDirectory || candidate.mkdirs()) {
                directory = candidate
                break
            }
        }
        File(directory ?: File(System.getProperty("java.io.tmpdir")), "status.log")
    }

    fun info(message: String) {
        write("INFO", message, null)
    }

    fun error(message: String, failure: Throwable? = null) {
        write("ERROR", message, failure)
    }

    private fun write(level: String, message: String, failure: Throwable?) {
        try {
            file.appendText("${Instant.now()} $level $message\n")
            if (failure != null) {
                file.appendText(failure.stackTraceToString() + "\n")
            }
        } catch (ignored: Throwable) {
            // Logging must never break playback.
        }
        println("[ListenStats] $level $message")
        failure?.printStackTrace()
    }
}
