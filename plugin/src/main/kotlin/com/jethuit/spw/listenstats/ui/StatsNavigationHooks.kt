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

/** Bridges the embedded page to SPW 1.19.0's Navigator without editing its back stack. */
object StatsNavigationHooks {
    const val NAVIGATOR_CLASS = "com.xuncorp.voxzen.ui.navigation.Navigator"
    private const val ROUTE_CLASS = "com.xuncorp.voxzen.ui.navigation.ScreenRoute"
    private class Binding(val navigator: Any, val currentRoute: () -> Any?, val navigate: (Any) -> Unit)
    private class RouteKey(route: Any, queue: ReferenceQueue<Any>? = null) : WeakReference<Any>(route, queue) {
        private val identity = System.identityHashCode(route)
        override fun hashCode(): Int = identity
        override fun equals(other: Any?): Boolean = this === other ||
            (other is RouteKey && get() != null && get() === other.get())
    }
    private class OpenRequest(val host: Binding, val route: RouteKey)
    private val expired = ReferenceQueue<Any>()
    private val requests = HashMap<RouteKey, OpenRequest>()
    private var binding: Binding? = null
    private var pending: OpenRequest? = null
    private var registered = false
    val navigatorReady: Boolean get() = registered && binding != null

    fun bindNavigator(navigator: Any, currentRoute: () -> Any?, navigate: (Any) -> Unit): AutoCloseable {
        cancelPendingOpen()
        val current = Binding(navigator, currentRoute, navigate)
        binding = current
        return AutoCloseable {
            if (binding === current) { binding = null; cancelPendingOpen() }
        }
    }

    fun cancelPendingOpen() { pending = null }

    /** Ask other tools to leave first; they may synchronously accept or defer for draft confirmation. */
    fun openStats() {
        if (StatsHostState.visible && !StatsHostState.closing) return
        val host = binding
        if (!registered || host == null) {
            WorkshopApi.ui.toast("统计页面导航尚未准备好，请稍后重试", WorkshopApi.Ui.ToastType.Warning)
            return
        }
        cancelPendingOpen()
        // A destination may already be preparing under the isolation layer.
        // Reverse the exit only after its leave guard accepts.
        var request: OpenRequest? = null
        runCatching {
            val current = checkNotNull(host.currentRoute()) { "Host navigation history is empty" }
            val marker = copyHostRoute(current)
            cleanRequests()
            val key = RouteKey(marker, expired)
            request = OpenRequest(host, key).also { requests[key] = it; pending = it }
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
        addHook: (String, List<String>, MethodHook) -> AutoCloseable
    ): AutoCloseable {
        val handles = mutableListOf<AutoCloseable>()
        registered = false
        cancelPendingOpen()
        try {
            // Close before the host's same-route early return as well as actual transitions.
            for (method in listOf("navigate", "replace", "forward")) {
                val parameters = if (method == "forward") emptyList() else listOf(ROUTE_CLASS)
                val sameRouteCalls = Collections.newSetFromMap(IdentityHashMap<MethodHookParam, Boolean>())
                handles += addHook(method, parameters, object : MethodHook(100) {
                    override fun before(call: MethodHookParam) {
                        // Observe ordinary navigation before another tool can defer it.
                        if (method != "navigate" || requestOf(call.args.firstOrNull()) == null) {
                            val host = binding
                            val sameRoute = method == "navigate" && host != null &&
                                call.thisObject === host.navigator && call.args.firstOrNull() == host.currentRoute()
                            if (sameRoute) {
                                // A tool opens synchronously after this no-op returns, outside
                                // NavDisplay. Animate the containing layer over statistics.
                                sameRouteCalls.add(call)
                            } else if (!StatsHostState.canAnimateClose) StatsHostState.close()
                        }
                    }
                    override fun after(call: MethodHookParam) {
                        // Only accepted navigation starts the exit. A lower-priority tool
                        // can defer/cancel the call, or replay it after draft confirmation.
                        // The native incoming page covers statistics as it fades in place.
                        val sameRoute = sameRouteCalls.remove(call)
                        if (!call.isOriginalSkipped && call.throwable == null && StatsHostState.visible) {
                            StatsHostState.requestNavigation(nativeTransition = !sameRoute)
                        }
                    }
                })
            }
            // Lyrics uses priority 80. This request must reach its leave guard before
            // we consume it; it must never become a real destination in host history.
            handles += addHook("navigate", listOf(ROUTE_CLASS), object : MethodHook(50) {
                override fun before(call: MethodHookParam) {
                    val request = requestOf(call.args.firstOrNull()) ?: return
                    call.setResult(null)
                    if (registered && pending === request && binding === request.host &&
                        call.thisObject === request.host.navigator && request.host.currentRoute() == request.route.get()) {
                        pending = null
                        StatsHostState.open()
                    }
                }
            })
            handles += addHook("back", emptyList(), object : MethodHook(100) {
                override fun before(call: MethodHookParam) {
                    cancelPendingOpen()
                    if (StatsHostState.consumeBack()) {
                        // The statistics page is the top layer. One back action closes only it.
                        // This also works when the underlying host is already at its root.
                        call.setResult(null)
                    }
                }
            })
            registered = true
        } catch (failure: Throwable) {
            closeAll(handles)
            throw failure
        }
        return AutoCloseable { registered = false; StatsHostState.close(); closeAll(handles) }
    }

    private fun closeAll(handles: MutableList<AutoCloseable>) {
        val pending = handles.toList().asReversed()
        handles.clear()
        for (handle in pending) {
            runCatching { handle.close() }.onFailure {
                PluginLog.error("removing navigation hook failed", it)
            }
        }
    }
}
