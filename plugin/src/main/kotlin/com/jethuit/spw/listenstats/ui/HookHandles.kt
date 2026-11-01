package com.jethuit.spw.listenstats.ui

/** Own every acquired handle until its close has returned successfully. EDT only. */
class HookHandles : AutoCloseable {
    private val handles = mutableListOf<AutoCloseable>()
    val isEmpty: Boolean get() = handles.isEmpty()
    operator fun plusAssign(handle: AutoCloseable) { handles += handle }
    override fun close() {
        var failure: Throwable? = null
        for (index in handles.indices.reversed()) {
            try { handles[index].close(); handles.removeAt(index) }
            catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
    fun registrationFailed(cause: Throwable): HookRegistrationFailure {
        // A nested registration may itself still own handles which failed to close.
        try { close() } catch (cleanup: Throwable) { if (cause !== cleanup) cause.addSuppressed(cleanup) }
        if (cause is HookRegistrationFailure && !cause.rollbackComplete) this += cause.remaining
        return HookRegistrationFailure(cause, this)
    }
}

class HookRegistrationFailure(cause: Throwable, val remaining: HookHandles) :
    RuntimeException("Host hook registration failed; rollbackComplete=${remaining.isEmpty}", cause) {
    val rollbackComplete: Boolean = remaining.isEmpty
}

class HostHookInstallation(private val handles: HookHandles, val cacheable: Boolean,
                           val incompleteCause: Throwable? = null, private val onClosed: () -> Unit = {}) : AutoCloseable {
    override fun close() { handles.close(); onClosed() }
}

object HookFailurePolicy {
    fun noRetry(error: Throwable): Boolean {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        fun inspect(value: Throwable): Boolean {
            if (!seen.add(value)) return false
            return value is InterruptedException || value is java.util.concurrent.CancellationException ||
                value is SecurityException || value.javaClass.simpleName.contains("PermissionDenied") ||
                value.cause?.let(::inspect) == true || value.suppressed.any(::inspect)
        }
        return inspect(error)
    }
}
