@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package com.jethuit.spw.listenstats.ui

import com.jethuit.spw.listenstats.PluginLog
import com.xuncorp.spw.workshop.api.hook.MethodHook
import com.xuncorp.spw.workshop.api.hook.MethodHookParam
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap

/** Bridges the embedded page to the discovered navigator without editing its back stack. */
object StatsNavigationHooks {
    val NAVIGATOR_CLASS: String get() = HostUiRuntime.require().targets.navigation.navigate.className
    private class Binding(val navigator: Any, val currentRoute: () -> Any?, val navigate: (Any) -> Unit,
                          val hostHandlesBack: () -> Boolean, val forwardHistory: () -> List<Any?>?)
    private class ForwardReturn(val host: Binding, val route: Any, val history: List<Any?>) {
        fun matches(): Boolean {
            val current = host.forwardHistory() ?: return false
            return host.currentRoute() === route && sameHistory(current, history)
        }
    }
    private fun sameHistory(left: List<Any?>?, right: List<Any?>?): Boolean =
        if (left == null || right == null) left === right
        else left.size == right.size && left.indices.all { left[it] === right[it] }
    private class ForwardObservation(val host: Binding, val route: Any?, val overlay: Boolean, val history: List<Any?>?)
    private class RouteKey(route: Any, queue: ReferenceQueue<Any>? = null) : WeakReference<Any>(route, queue) {
        private val identity = System.identityHashCode(route)
        override fun hashCode(): Int = identity
        override fun equals(other: Any?): Boolean = this === other ||
            (other is RouteKey && get() != null && get() === other.get())
    }
    private class OpenRequest(val host: Binding, val route: RouteKey, val restore: ForwardReturn?)
    private val expired = ReferenceQueue<Any>()
    private val requests = HashMap<RouteKey, OpenRequest>()
    private var binding: Binding? = null
    private var pending: OpenRequest? = null
    private var forwardReturn: ForwardReturn? = null
    private var preparationTicket = 0L
    private var registered = false
    val navigatorReady: Boolean get() = registered && binding != null

    fun bindNavigator(navigator: Any, currentRoute: () -> Any?, hostHandlesBack: () -> Boolean = { false },
                      forwardHistory: () -> List<Any?>? = { null },
                      navigate: (Any) -> Unit): AutoCloseable {
        cancelPendingOpen()
        clearForwardReturn()
        val current = Binding(navigator, currentRoute, navigate, hostHandlesBack, forwardHistory)
        binding = current
        return AutoCloseable {
            if (binding === current) { binding = null; cancelPendingOpen(); clearForwardReturn() }
        }
    }

    fun cancelPendingOpen() { pending = null; preparationTicket++ }

    fun clearForwardReturn() { forwardReturn = null }

    /** One in-memory overlay step above the host's history, captured only by an explicit return. */
    fun rememberForwardReturn() {
        forwardReturn = null
        val host = binding?.takeIf { registered } ?: return
        val route = host.currentRoute() ?: return
        val history = host.forwardHistory() ?: run {
            PluginLog.info("Statistics forward return unavailable: host forward history cannot be read safely")
            return
        }
        forwardReturn = ForwardReturn(host, route, history)
        PluginLog.info("Statistics forward return recorded")
    }

    /** Ask other tools to leave first; they may synchronously accept or defer for draft confirmation. */
    fun openStats() {
        if (HostUiRuntime.current?.acceptingActions == false) return
        if (StatsHostState.visible && !StatsHostState.closing) return
        val host = binding
        if (!registered || host == null) {
            WorkshopApi.ui.toast("统计页面导航尚未准备好，请稍后重试", WorkshopApi.Ui.ToastType.Warning)
            return
        }
        prepareOpen(host)
    }

    fun openConfiguration() {
        if (HostUiRuntime.current?.acceptingActions == false) return
        val host = binding
        if (!registered || host == null) {
            WorkshopApi.ui.toast("配置页面导航尚未准备好，请稍后重试", WorkshopApi.Ui.ToastType.Warning)
            return
        }
        cancelPendingOpen()
        runCatching {
            host.navigate(HostPluginConfiguration.route(host.navigator.javaClass.classLoader,
                com.jethuit.spw.listenstats.PluginState.pluginId))
        }.onFailure {
            PluginLog.error("Opening statistics configuration failed", it)
            WorkshopApi.ui.toast("无法打开插件配置，请查看诊断日志", WorkshopApi.Ui.ToastType.Warning)
        }
    }

    private fun prepareOpen(host: Binding, restore: ForwardReturn? = null) {
        if (HostUiRuntime.current?.acceptingActions == false) return
        cancelPendingOpen()
        val ticket = preparationTicket
        com.jethuit.spw.listenstats.data.StatsSnapshots.prepareOpen {
            if (ticket == preparationTicket && registered && binding === host &&
                (restore == null || (forwardReturn === restore && restore.matches()))) requestPreparedOpen(host, restore)
        }
    }

    private fun requestPreparedOpen(host: Binding, restore: ForwardReturn? = null) {
        // A destination may already be preparing under the isolation layer.
        // Reverse the exit only after its leave guard accepts.
        var request: OpenRequest? = null
        runCatching {
            val current = checkNotNull(host.currentRoute()) { "Host navigation history is empty" }
            val marker = copyHostRoute(current)
            cleanRequests()
            val key = RouteKey(marker, expired)
            request = OpenRequest(host, key, restore).also { requests[key] = it; pending = it }
            host.navigate(marker)
        }.onFailure {
            if (pending === request) pending = null
            PluginLog.error("requesting statistics page navigation failed", it)
            WorkshopApi.ui.toast("统计页面切换失败，请查看诊断日志", WorkshopApi.Ui.ToastType.Warning)
        }
    }

    private fun cleanRequests() {
        while (true) requests.remove(expired.poll() as? RouteKey ?: break)
    }

    private fun requestOf(value: Any?): OpenRequest? {
        cleanRequests()
        return value?.let { requests[RouteKey(it)] }
    }

    /** A distinct identity survives another plugin's deferred replay; equality keeps native same-route semantics. */
    private fun copyHostRoute(route: Any): Any {
        val type = route.javaClass
        val copy = type.declaredMethods.singleOrNull { it.name == "copy" }
        val result = if (copy != null) {
            val values = copy.parameterTypes.indices.map { type.getDeclaredMethod("component${it + 1}").invoke(route) }
            copy.invoke(route, *values.toTypedArray())
        } else {
            type.getDeclaredConstructor().also { check(it.trySetAccessible()) }.newInstance()
        }
        check(result !== route && result == route && route == result) { "Host route cannot be copied safely" }
        return result
    }

    fun register(
        targets: HostNavigationTargets = HostUiRuntime.require().targets.navigation,
        addHook: (String, List<String>, MethodHook) -> AutoCloseable
    ): AutoCloseable {
        val handles = HookHandles()
        registered = false
        cancelPendingOpen()
        clearForwardReturn()
        try {
            // Close before the host's same-route early return as well as actual transitions.
            for (target in listOf(targets.navigate, targets.replace, targets.forward)) {
                val method = target.name
                val parameters = target.parameters
                val sameRouteCalls = Collections.newSetFromMap(IdentityHashMap<MethodHookParam, Boolean>())
                val forwardCalls = IdentityHashMap<MethodHookParam, ForwardObservation>()
                handles += addHook(method, parameters, object : MethodHook(100) {
                    override fun before(call: MethodHookParam) {
                        if (binding != null && call.thisObject !== binding?.navigator) return
                        if (target == targets.forward) {
                            val host = binding
                            if (host != null && call.thisObject === host.navigator) {
                                val restore = forwardReturn
                                if (restore != null && restore.host === host && restore.matches() &&
                                    (!StatsHostState.visible || (StatsHostState.closing && StatsHostState.revealHostOnExit)) &&
                                    !host.hostHandlesBack()) {
                                    call.setResult(null)
                                    PluginLog.info("Statistics forward restoration requested")
                                    // Re-enter through the existing prepared-open and full leave-guard chain.
                                    prepareOpen(host, restore)
                                    return
                                }
                                forwardCalls[call] = ForwardObservation(host, host.currentRoute(), host.hostHandlesBack(), host.forwardHistory())
                            }
                        }
                        // Observe ordinary navigation before another tool can defer it.
                        if (target != targets.navigate || requestOf(call.args.firstOrNull()) == null) {
                            cancelPendingOpen()
                            val host = binding
                            val sameRoute = target == targets.navigate && host != null &&
                                call.thisObject === host.navigator && call.args.firstOrNull() == host.currentRoute()
                            if (sameRoute) {
                                // A tool opens synchronously after this no-op returns, outside
                                // NavDisplay. Animate the containing layer over statistics.
                                sameRouteCalls.add(call)
                            }
                        }
                    }
                    override fun after(call: MethodHookParam) {
                        // Only accepted navigation starts the exit. A lower-priority tool
                        // can defer/cancel the call, or replay it after draft confirmation.
                        // The native incoming page covers statistics as it fades in place.
                        val sameRoute = sameRouteCalls.remove(call)
                        val previous = forwardCalls.remove(call)
                        if (!call.isOriginalSkipped && call.throwable == null &&
                            (binding == null || call.thisObject === binding?.navigator)) {
                            if (target != targets.forward) clearForwardReturn()
                            // Native forward may legitimately do nothing. It must not close the overlay.
                            val unchangedForward = target == targets.forward && (previous == null ||
                                binding !== previous.host || (previous.host.currentRoute() === previous.route &&
                                previous.host.hostHandlesBack() == previous.overlay &&
                                sameHistory(previous.history, previous.host.forwardHistory())))
                            if (StatsHostState.visible && !unchangedForward) {
                                StatsHostState.requestNavigation(nativeTransition = !sameRoute)
                            }
                        }
                    }
                })
            }
            // Lyrics uses priority 80. This request must reach its leave guard before
            // we consume it; it must never become a real destination in host history.
            handles += addHook(targets.navigate.name, targets.navigate.parameters, object : MethodHook(50) {
                override fun before(call: MethodHookParam) {
                    val request = requestOf(call.args.firstOrNull()) ?: return
                    call.setResult(null)
                    if (registered && HostUiRuntime.current?.acceptingActions != false &&
                        pending === request && binding === request.host &&
                        (request.restore == null || (forwardReturn === request.restore && request.restore.matches())) &&
                        call.thisObject === request.host.navigator && request.host.currentRoute() == request.route.get()) {
                        pending = null
                        StatsHostState.open()
                    }
                }
            })
            handles += addHook(targets.back.name, targets.back.parameters, object : MethodHook(100) {
                override fun before(call: MethodHookParam) {
                    cancelPendingOpen()
                    val host = binding
                    if (host != null && (call.thisObject !== host.navigator || host.hostHandlesBack())) return
                    if (StatsHostState.consumeBack()) {
                        // Native dialogs and the expanded player have priority over statistics.
                        call.setResult(null)
                    }
                }
            })
            registered = true
        } catch (failure: Throwable) {
            throw handles.registrationFailed(failure)
        }
        return AutoCloseable {
            registered = false
            clearForwardReturn()
            val stateFailure = runCatching { StatsHostState.close() }.exceptionOrNull()
            try { handles.close() } catch (cleanup: Throwable) {
                if (stateFailure == null) throw cleanup
                if (stateFailure !== cleanup) stateFailure.addSuppressed(cleanup)
            }
            stateFailure?.let { throw it }
        }
    }

}
