@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

import com.jethuit.spw.listenstats.ui.StatsHostState
import com.jethuit.spw.listenstats.ui.StatsNavigationHooks
import com.xuncorp.spw.workshop.api.hook.MethodHook
import com.xuncorp.spw.workshop.api.hook.MethodHookParam
import java.lang.reflect.Method

private class HostHistory {
    val pages = mutableListOf("songs", "settings", "plugin-config")
    var originalCalls = 0
    fun original() { originalCalls++ }
}

private class Call(val host: HostHistory, val original: () -> Unit) : MethodHookParam() {
    override val method: Method = HostHistory::class.java.getDeclaredMethod("original")
    override val thisObject: Any = host
    override val args: Array<Any?> = emptyArray()
    private var returned: Any? = null
    private var thrown: Throwable? = null
    override val result: Any? get() = returned
    override val throwable: Throwable? get() = thrown
    override var isOriginalSkipped = false
        private set
    override fun setResult(result: Any?) { returned = result; isOriginalSkipped = true }
    override fun setThrowable(throwable: Throwable) { thrown = throwable; isOriginalSkipped = true }
    override fun invokeOriginal(): Any? { host.original(); original(); return null }
}

private class Registration : AutoCloseable {
    val hooks = linkedMapOf<String, MethodHook>()
    val closed = mutableListOf<String>()
    val handle = StatsNavigationHooks.register { method, parameters, callback ->
        check(parameters == if (method in listOf("navigate", "replace"))
            listOf("com.xuncorp.voxzen.ui.navigation.ScreenRoute") else emptyList())
        val loader = StatsNavigationHooks::class.java.classLoader
        val navigator = Class.forName(StatsNavigationHooks.NAVIGATOR_CLASS, false, loader)
        val types = parameters.map { Class.forName(it, false, loader) }.toTypedArray()
        check(navigator.getDeclaredMethod(method, *types).returnType == Void.TYPE)
        hooks[method] = callback
        AutoCloseable { closed += method; hooks.remove(method) }
    }
    fun dispatch(method: String, host: HostHistory, original: () -> Unit) {
        val call = Call(host, original)
        val hook = hooks[method]
        hook?.before(call)
        if (!call.isOriginalSkipped) call.invokeOriginal()
        hook?.after(call)
    }
    override fun close() = handle.close()
}

fun main() {
    val registration = Registration()
    check(registration.hooks.keys == setOf("navigate", "replace", "forward", "back"))
    for (method in listOf("navigate", "replace", "forward")) {
        val host = HostHistory()
        StatsHostState.open()
        registration.dispatch(method, host) {
            check(!StatsHostState.visible) // No statistics layer over the new destination.
            host.pages.clear()
            host.pages += "genres"
        }
        check(host.pages == listOf("genres") && host.originalCalls == 1)
    }
    println("PASS sidebar navigation, replacement and forward keep host actions")

    val host = HostHistory()
    StatsHostState.open()
    registration.dispatch("navigate", host) { /* Host's same-route no-op. */ }
    check(!StatsHostState.visible && host.originalCalls == 1)
    println("PASS selecting the already active destination still closes statistics")

    StatsHostState.open()
    val before = host.pages.toList()
    registration.dispatch("back", host) { host.pages.removeLast() }
    check(!StatsHostState.visible && host.pages == before && host.originalCalls == 1)
    registration.dispatch("back", host) { host.pages.removeLast() }
    check(host.pages == before.dropLast(1) && host.originalCalls == 2)
    println("PASS first back closes statistics, second back reaches host history")

    host.pages.clear()
    host.pages += "songs"
    StatsHostState.open()
    registration.dispatch("back", host) { error("Root back must not execute with statistics open") }
    check(!StatsHostState.visible && host.pages == listOf("songs"))
    println("PASS back closes statistics even above the host root")

    val closedCalls = host.originalCalls
    registration.dispatch("navigate", host) { host.pages += "albums" }
    registration.dispatch("back", host) { host.pages.removeLast() }
    check(host.originalCalls == closedCalls + 2 && host.pages == listOf("songs"))
    println("PASS host navigation is unchanged when statistics is closed")

    registration.close()
    registration.close()
    check(registration.hooks.isEmpty())
    check(registration.closed == listOf("back", "forward", "replace", "navigate"))
    Registration().use { fresh ->
        StatsHostState.open()
        fresh.dispatch("back", host) { error("Re-enabled hook must consume back") }
        check(!StatsHostState.visible)
    }
    println("PASS disable cleans all hooks once and re-enable registers fresh hooks")

    val rolledBack = mutableListOf<String>()
    val failure = runCatching {
        StatsNavigationHooks.register { method, _, _ ->
            if (method == "back") error("simulated missing host target")
            AutoCloseable { rolledBack += method }
        }
    }.exceptionOrNull()
    check(failure != null && rolledBack == listOf("forward", "replace", "navigate"))
    println("PASS failed registration rolls back every installed navigation hook")
    println("7 navigation regression groups passed; physical mouse input still needs host verification")
}
