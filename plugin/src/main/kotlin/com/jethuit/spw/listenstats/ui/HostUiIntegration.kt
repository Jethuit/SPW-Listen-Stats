package com.jethuit.spw.listenstats.ui

import com.jethuit.spw.listenstats.PluginState
import com.jethuit.spw.listenstats.PluginLog
import com.jethuit.spw.listenstats.data.DataFiles
import java.awt.EventQueue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Validate/discover/prepare on worker; register/release on EDT without blocking the stop worker. */
class HostUiIntegration(
    private val source: () -> Path,
    private val loader: ClassLoader,
    private val install: (HostUiBridge) -> AutoCloseable,
    private val failed: (Throwable) -> Unit,
    private val report: (String) -> Unit,
    private val discover: ((Path, ClassLoader) -> HostUiBridge)? = null,
    private val cache: HostTargetCache? = HostTargetCache(DataFiles.directory().resolve("host-targets-v1.json"), PluginState.pluginVersion),
    private val checkInstallAllowed: () -> Unit = {},
    private val ready: (HostUiBridge) -> Unit = {},
    private val releaseFailed: (Throwable) -> Unit = {
        PluginLog.error("Deferred host UI cleanup failed; restart SPW before enabling statistics again", it)
    }
) : AutoCloseable {
    private val stopped = AtomicBoolean(false)
    private val session = synchronized(sessionLock) { ++generation }
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ListenStats-HostDiscovery").apply { isDaemon = true }
    }
    private var hooks: AutoCloseable? = null // EDT only; retained on release failure
    @Volatile private var actionBridge: HostUiBridge? = null
    private val releaseLock = Any() // Never held while running cleanup or waiting for the EDT.
    private var releaseQueued = false
    private var releasing = false
    private fun current(): Boolean = !stopped.get() && session == generation
    private fun emit(status: String) { if (current()) report(status) }
    private fun elapsed(start: Long) = (System.nanoTime() - start) / 1_000_000

    fun start() {
        emit("scanning")
        UiPerformanceProbe.mark("host_discovery_start")
        val started = System.nanoTime()
        worker.execute {
            try {
                val path = source()
                val validation = System.nanoTime()
                val identity = cache?.identity(path)
                var bridge: HostUiBridge? = null
                var targets: HostUiTargets? = null
                if (identity != null) try {
                    targets = cache?.read(identity)
                } catch (error: Throwable) {
                    if (cancelled(error)) throw error
                    emit("cache invalid: ${error.message}")
                }
                emit("cache_validate_ms=${elapsed(validation)}; ${if (targets != null) "matched" else "miss"}")
                if (targets != null) try {
                    bridge = prepare(targets).also { check(it.cacheable) { "Cached reflection preparation incomplete" } }
                } catch (error: Throwable) {
                    if (cancelled(error)) throw error
                    emit("cache reflection failed: ${error.message}; falling back to full scan")
                }
                val cached = bridge != null
                if (bridge == null) bridge = scan(path)
                enqueueInstall(path, identity, bridge, cached, started)
            } catch (error: Throwable) { finishFailure(error) }
        }
    }

    private fun prepare(targets: HostUiTargets): HostUiBridge {
        val start = System.nanoTime()
        return HostUiBridge(targets, loader).also { emit("prepare_ms=${elapsed(start)}; warnings=${it.warnings}") }
    }

    private fun scan(path: Path): HostUiBridge {
        if (!current()) throw CancellationException("Expired host session")
        val start = System.nanoTime()
        discover?.let { injected -> return injected(path, loader).also { emit("scan_ms=${elapsed(start)}; injected discovery") } }
        val targets = HostUiDiscovery.discover(HostBytecodeIndex.read(path))
        emit("scan_ms=${elapsed(start)}")
        return prepare(targets)
    }

    private fun enqueueInstall(path: Path, identity: HostTargetCache.Identity?, bridge: HostUiBridge,
                               cached: Boolean, started: Long) {
        EventQueue.invokeLater {
            synchronized(sessionLock) {
                if (!current()) { worker.shutdown(); return@invokeLater }
                try {
                    // A replacement may already be queued before a stop worker posts its cleanup.
                    pendingReleases.toList().forEach { it.releaseDeferred() }
                    if (!current()) { worker.shutdown(); return@invokeLater }
                    check(blockedHandles.isEmpty()) { "Previous host hooks remain unreleased; registration stopped" }
                    checkInstallAllowed()
                    val installStarted = System.nanoTime()
                    actionBridge = bridge
                    val installed = install(bridge)
                    hooks = installed
                    // install may synchronously stop this session or start a newer one.
                    if (!current()) {
                        try { installed.close(); hooks = null }
                        catch (cleanup: Throwable) { blockedHandles += installed; throw cleanup }
                        worker.shutdown()
                        return@invokeLater
                    }
                    if (cached && installed is HostHookInstallation && !installed.cacheable) {
                        val rollback = HookHandles()
                        rollback += installed
                        val failure = rollback.registrationFailed(installed.incompleteCause ?: IllegalStateException("Cached installation incomplete"))
                        hooks = null // failure now owns any remaining handles
                        throw failure
                    }
                    emit("install_ms=${elapsed(installStarted)}")
                    emit("ready ready_ms=${elapsed(started)}; source=${if (cached) "cache" else "scan"}; warnings=${bridge.warnings}")
                    UiPerformanceProbe.mark("host_discovery_ready")
                    runCatching { ready(bridge) }.onFailure { emit("ready notification failed: ${it.message}") }
                    if (!current()) {
                        hooks?.let { owned ->
                            try { owned.close(); hooks = null }
                            catch (cleanup: Throwable) { blockedHandles += owned; throw cleanup }
                        }
                        worker.shutdown()
                        return@invokeLater
                    }
                    val complete = bridge.cacheable && (hooks as? HostHookInstallation)?.cacheable != false
                    if (identity != null && complete && !cached) {
                        worker.execute { save(path, identity, bridge.targets) }
                    } else worker.shutdown()
                } catch (error: Throwable) {
                    if (error is HookRegistrationFailure && !error.rollbackComplete) {
                        hooks = error.remaining
                        blockedHandles += error.remaining
                    }
                    if (cached && error is HookRegistrationFailure && error.rollbackComplete &&
                        !cancelled(error) && current()) {
                        emit("cache registration failed; rollback complete; retrying full scan once")
                        worker.execute {
                            try {
                                // Permission can be withdrawn after the original preparation.
                                checkInstallAllowed()
                                enqueueInstall(path, cache?.identity(path), scan(path), false, started)
                            } catch (retryError: Throwable) { finishFailure(retryError) }
                        }
                    } else finishFailure(error)
                }
            }
        }
    }

    private fun save(path: Path, identity: HostTargetCache.Identity, targets: HostUiTargets) {
        val store = cache ?: return
        var temporary: Path? = null
        try {
            if (!current() || store.identity(path) != identity) return
            val prepared = store.prepare(identity, targets)
            temporary = prepared
            synchronized(sessionLock) { if (current()) { store.publish(prepared); emit("cache saved") } }
        } catch (error: Throwable) {
            if (current()) emit("cache write skipped: ${error.message}")
        } finally { try { temporary?.let { Files.deleteIfExists(it) } } finally { worker.shutdown() } }
    }

    private fun finishFailure(error: Throwable) {
        worker.shutdown()
        EventQueue.invokeLater {
            if (current()) {
                if (interrupted(error)) emit("cancelled")
                else { emit("failed: ${error.message}"); failed(error) }
            }
        }
    }

    override fun close() {
        stopped.set(true)
        actionBridge?.stopActions()
        worker.shutdownNow()
        if (EventQueue.isDispatchThread()) {
            if (!claimRelease(queuedOnly = false)) return
            try { releaseOwned() } finally { synchronized(releaseLock) { releasing = false } }
        } else {
            val enqueue = synchronized(releaseLock) {
                if (releaseQueued || releasing) false else {
                    releaseQueued = true
                    pendingReleases += this
                    true
                }
            }
            if (enqueue) EventQueue.invokeLater { releaseDeferred() }
        }
    }

    private fun claimRelease(queuedOnly: Boolean): Boolean = synchronized(releaseLock) {
        if (releasing || (queuedOnly && !releaseQueued)) false else {
            releaseQueued = false
            releasing = true
            pendingReleases -= this
            true
        }
    }

    private fun releaseDeferred() {
        check(EventQueue.isDispatchThread())
        if (!claimRelease(queuedOnly = true)) return
        try { releaseOwned() }
        catch (error: Throwable) {
            // The stop call has returned; report here without creating an uncaught EDT exception.
            try { releaseFailed(error) }
            catch (reportError: Throwable) { runCatching { PluginLog.error("Reporting UI cleanup failure failed", reportError) } }
        } finally { synchronized(releaseLock) { releasing = false } }
    }

    private fun releaseOwned() {
        synchronized(sessionLock) {
            val owned = hooks
            if (owned != null) {
                try {
                    owned.close()
                    hooks = null
                    actionBridge = null
                    blockedHandles.remove(owned)
                    failedReleases.remove(this)
                } catch (error: Throwable) {
                    blockedHandles += owned
                    failedReleases += this
                    throw error
                }
            }
        }
    }

    companion object {
        private val sessionLock = Any()
        @Volatile private var generation = 0L
        private val blockedHandles = mutableSetOf<AutoCloseable>() // retain failed releases across sessions
        private val failedReleases = mutableSetOf<HostUiIntegration>() // retain the owner for explicit cleanup retry
        private val pendingReleases = ConcurrentHashMap.newKeySet<HostUiIntegration>()
        private fun interrupted(error: Throwable): Boolean = error is InterruptedException ||
            error is CancellationException || error.cause?.let(::interrupted) == true
        private fun cancelled(error: Throwable): Boolean = HookFailurePolicy.noRetry(error)
    }
}
