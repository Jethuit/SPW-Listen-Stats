package com.jethuit.spw.listenstats.data

/** Disabled by default; benchmark code installs a sink to collect stage timings. */
object PerformanceProbe {
    @Volatile
    var sink: ((stage: String, elapsedNanos: Long) -> Unit)? = null

    @Volatile
    var counterSink: ((name: String) -> Unit)? = null

    fun mark(name: String) {
        counterSink?.invoke(name)
    }

    fun <T> measure(stage: String, action: () -> T): T {
        val observer = sink ?: return action()
        val started = System.nanoTime()
        return try {
            action()
        } finally {
            observer(stage, System.nanoTime() - started)
        }
    }
}
