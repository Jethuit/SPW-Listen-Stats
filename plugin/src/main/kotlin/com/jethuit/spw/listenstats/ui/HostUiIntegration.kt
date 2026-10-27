package com.jethuit.spw.listenstats.ui

import java.awt.EventQueue
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Scan on each enable, then install on the UI thread. Stop never waits for the scan. */
class HostUiIntegration(
    private val source: () -> Path,
    private val loader: ClassLoader,
    private val install: (HostUiBridge) -> AutoCloseable,
    private val failed: (Throwable) -> Unit,
    private val report: (String) -> Unit,
    private val discover: (Path, ClassLoader) -> HostUiBridge = { path, hostLoader ->
        HostUiBridge(HostUiDiscovery.discover(HostBytecodeIndex.read(path)), hostLoader)
    }
) : AutoCloseable {
    private val stopped = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ListenStats-HostDiscovery").apply { isDaemon = true }
    }
    private var hooks: AutoCloseable? = null // EDT only

    fun start() {
        report("scanning")
        worker.execute {
            val started = System.nanoTime()
            val result = runCatching {
                val path = source()
                discover(path, loader)
            }
            EventQueue.invokeLater {
                if (stopped.get()) return@invokeLater
                result.onSuccess { bridge ->
                    runCatching { install(bridge) }.onSuccess {
                        hooks = it
                        report("ready (${(System.nanoTime() - started) / 1_000_000}ms); warnings=${bridge.warnings}")
                    }.onFailure { error -> report("failed: ${error.message}"); failed(error) }
                }.onFailure { error -> report("failed: ${error.message}"); failed(error) }
            }
            worker.shutdown()
        }
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        worker.shutdownNow()
        val release = { hooks?.close(); hooks = null; Unit }
        // Do not wait for a discovery scan, but do finish releasing installed
        // hooks before the host tries to move this plugin directory during an update.
        if (EventQueue.isDispatchThread()) release() else {
            runCatching { EventQueue.invokeAndWait(release) }
                .onFailure { EventQueue.invokeLater(release) }
        }
    }
}
