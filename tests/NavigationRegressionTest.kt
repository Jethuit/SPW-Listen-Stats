@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

import com.jethuit.spw.listenstats.ui.StatsHostState
import com.jethuit.spw.listenstats.ui.StatsNavigationHooks
import com.xuncorp.spw.workshop.api.hook.MethodHook
import com.xuncorp.spw.workshop.api.hook.MethodHookParam

private class LyricsGuard(private val registration: TestNavigationRegistration) : MethodHook(80) {
    var active = true
    var dirty = false
    var pending: (() -> Unit)? = null
    private var replaying = false
    override fun before(call: MethodHookParam) {
        if (!active || replaying) return
        val args = call.args.copyOf()
        call.setResult(null)
        val leave = {
            active = false
            replaying = true
            try { registration.dispatch("navigate", args); Unit } finally { replaying = false }
        }
        if (dirty) pending = leave else leave()
    }
    fun confirm() { val action = pending; pending = null; action?.invoke() }
    fun cancel() { pending = null }

    /** Match Lyrics: enter navigation returns before its session is created. */
    fun open() {
        if (active) return
        replaying = true
        try { registration.dispatch("navigate", arrayOf(registration.host.currentRoute)) }
        finally { replaying = false }
        active = true
    }
}

fun main() {
    val registration = TestNavigationRegistration()
    check(registration.hooks.size == 5)
    val host = registration.host
    for (method in listOf("navigate", "replace", "forward")) {
        StatsHostState.open()
        registration.dispatch(method) { check(!StatsHostState.visible) }
    }
    println("PASS ordinary sidebar, same-route, replacement and forward close statistics before host navigation")

    StatsHostState.open()
    val before = host.pages.toList()
    val calls = host.originalCalls
    registration.dispatch("back") { error("First back must not reach host history") }
    check(!StatsHostState.visible && host.pages == before && host.originalCalls == calls)
    registration.dispatch("back") { host.pages.removeLast() }
    check(host.pages == before.dropLast(1) && host.originalCalls == calls + 1)
    host.pages.clear(); host.pages += "songs"
    StatsHostState.open()
    registration.dispatch("back") { error("Root back must not execute with statistics open") }
    println("PASS first back closes only statistics, including above host root; next back reaches host")

    StatsNavigationHooks.openStats()
    check(StatsHostState.visible && host.pages == listOf("songs"))
    val marker = checkNotNull(registration.lastRequest)
    StatsHostState.close()
    val track = host.currentRoute
    host.currentRoute = Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRoute\$ModConfigContent")
        .getConstructor(String::class.java).newInstance("synthetic-statistics-plugin")
    StatsNavigationHooks.openStats()
    check(StatsHostState.visible && registration.lastRequest !== host.currentRoute &&
        registration.lastRequest == host.currentRoute)
    StatsHostState.close()
    host.currentRoute = track
    println("PASS native singleton and parameterized plugin-config routes retain their values with distinct request identities")
    val guard = LyricsGuard(registration)
    registration.extra += TestNavigationRegistration.Entry("navigate", guard)
    StatsNavigationHooks.openStats()
    check(!guard.active && StatsHostState.visible && host.pages == listOf("songs"))
    StatsHostState.close()
    println("PASS opening Lyrics then statistics dismisses Lyrics before statistics becomes visible")

    guard.active = true; guard.dirty = true
    StatsNavigationHooks.openStats()
    check(guard.active && !StatsHostState.visible && guard.pending != null)
    guard.cancel()
    check(guard.active && !StatsHostState.visible)
    guard.dirty = false
    registration.dispatch("navigate", arrayOf("songs"))
    check(!StatsHostState.visible)
    guard.active = true; guard.dirty = true
    StatsNavigationHooks.openStats()
    check(!StatsHostState.visible)
    guard.confirm()
    check(!guard.active && StatsHostState.visible && host.pages == listOf("songs"))
    StatsHostState.close()
    println("PASS dirty lyrics draft: wait for confirmation, cancel stays in Lyrics, confirm opens statistics")

    guard.active = true; guard.dirty = true
    StatsNavigationHooks.openStats()
    val stale = checkNotNull(guard.pending)
    StatsNavigationHooks.openStats()
    stale()
    check(!StatsHostState.visible) { "Earlier deferred request won over a newer request" }
    guard.confirm()
    check(StatsHostState.visible)
    StatsHostState.close()
    guard.active = true
    StatsNavigationHooks.openStats()
    val canceledByNavigation = checkNotNull(guard.pending)
    registration.dispatch("replace", arrayOf("albums"))
    canceledByNavigation()
    check(!StatsHostState.visible)
    println("PASS repeated opens, stale callbacks and intervening host navigation cannot reopen the wrong page")

    guard.active = true
    StatsNavigationHooks.openStats()
    val afterStop = checkNotNull(guard.pending)
    registration.close(); registration.close()
    afterStop()
    check(!StatsHostState.visible && host.pages == listOf("songs"))
    check(registration.hooks.isEmpty())
    check(registration.closed == listOf("back@100", "navigate@50", "forward@100", "replace@100", "navigate@100"))
    TestNavigationRegistration().use {
        StatsNavigationHooks.openStats()
        check(StatsHostState.visible)
        StatsHostState.close()
    }
    println("PASS disable/re-enable clears deferred activation and unregisters both navigation stages")

    checkNativeNoOp(marker)
    checkExitNavigation()
    checkRapidPluginSwitches()

    val rolledBack = mutableListOf<String>()
    val failure = runCatching {
        StatsNavigationHooks.register { method, _, callback ->
            if (method == "back") error("simulated missing host target")
            AutoCloseable { rolledBack += "$method@${callback.priority}" }
        }
    }.exceptionOrNull()
    check(failure != null && rolledBack == listOf("navigate@50", "forward@100", "replace@100", "navigate@100"))
    println("PASS failed registration rolls back every installed navigation hook")
}

private fun checkRapidPluginSwitches() {
    TestNavigationRegistration().use { registration ->
        val guard = LyricsGuard(registration).apply { active = false }
        registration.extra += TestNavigationRegistration.Entry("navigate", guard)
        val presenter = StatsHostState.attachPresenter()
        StatsHostState.setAnimationsEnabled(true)
        try {
            repeat(8) {
                StatsHostState.open()
                val calls = registration.host.originalCalls
                guard.open()
                val ticket = StatsHostState.closeTicket
                check(guard.active && StatsHostState.visible && StatsHostState.closing)
                check(!StatsHostState.revealHostOnExit) { "Prepared lyrics page must remain isolated" }
                check(registration.host.originalCalls == calls + 1)
                StatsNavigationHooks.openStats()
                check(!guard.active && StatsHostState.visible && !StatsHostState.closing)
                check(StatsHostState.revealHostOnExit) { "Accepted reopen did not restore ordinary host fading" }
                StatsHostState.finishClose(ticket)
                check(StatsHostState.visible && !guard.active) { "Stale exit overrode rapid reopen" }
            }

            guard.open()
            val ticket = StatsHostState.closeTicket
            StatsHostState.consumeBack()
            check(!StatsHostState.revealHostOnExit) { "Back exposed a synchronously prepared tool during exit" }
            val calls = registration.host.originalCalls
            StatsHostState.finishClose(ticket)
            check(!StatsHostState.visible && guard.active && registration.host.originalCalls == calls) {
                "Exit replayed the same-route request and dismissed the target plugin"
            }
            StatsNavigationHooks.openStats()
            check(StatsHostState.visible && !guard.active)
            println("PASS rapid plugin switching: outgoing animation retained, reopen leaves target, same-route entry is never replayed")

            guard.open()
            guard.dirty = true
            StatsNavigationHooks.openStats()
            check(guard.pending != null && guard.active && StatsHostState.closing)
            StatsHostState.finishClose(StatsHostState.closeTicket)
            check(!StatsHostState.visible && guard.active)
            presenter.close() // Completed visual exit removes the composed statistics page.
            guard.confirm()
            check(StatsHostState.visible && !guard.active) { "Visual exit invalidated a newer confirmation" }

            val replacementPresenter = StatsHostState.attachPresenter()

            guard.open()
            StatsNavigationHooks.openStats()
            guard.cancel()
            StatsHostState.finishClose(StatsHostState.closeTicket)
            check(!StatsHostState.visible && guard.active)
            guard.dirty = false
            StatsNavigationHooks.openStats()
            check(StatsHostState.visible && !guard.active)
            println("PASS rapid reopen waits for dirty target confirmation; cancel stays on target, confirm reopens statistics")

            registration.dispatch("navigate", arrayOf("albums"))
            val canceledDestination = StatsHostState.closeTicket
            guard.open()
            StatsHostState.finishClose(canceledDestination)
            check(!StatsHostState.visible && guard.active) { "Earlier sidebar target overrode later plugin entry" }

            StatsNavigationHooks.openStats()
            guard.open()
            val stopped = StatsHostState.closeTicket
            registration.close()
            StatsHostState.finishClose(stopped)
            check(!StatsHostState.visible && guard.active)
            replacementPresenter.close()
            println("PASS latest plugin entry replaces earlier navigation; teardown cancels unfinished exit")
        } finally {
            presenter.close()
            StatsHostState.setAnimationsEnabled(true)
        }
    }
}

/** Delay route replay until the composed page has acknowledged the end of its exit. */
private fun checkExitNavigation() {
    val registration = TestNavigationRegistration()
    val host = registration.host
    val presenter = StatsHostState.attachPresenter()
    StatsHostState.setAnimationsEnabled(true)
    try {
        StatsHostState.open()
        registration.dispatch("navigate", arrayOf("albums")) { error("Navigation ran before exit") }
        val ticket = StatsHostState.closeTicket
        check(StatsHostState.visible && StatsHostState.closing && host.originalCalls == 0)
        check(StatsHostState.revealHostOnExit) { "Ordinary return must allow the retained host to fade in" }
        registration.dispatch("replace", arrayOf("artists")) { error("Replacement ran before exit") }
        check(StatsHostState.closeTicket == ticket)
        StatsHostState.finishClose(ticket)
        check(!StatsHostState.visible && host.originalCalls == 1 && host.lastReplay == ("replace" to listOf("artists")))
        StatsHostState.finishClose(ticket)
        check(host.originalCalls == 1)

        StatsHostState.open()
        registration.dispatch("navigate", arrayOf("canceled"))
        val canceled = StatsHostState.closeTicket
        registration.dispatch("back") { error("Back escaped to host history during exit") }
        StatsHostState.finishClose(canceled)
        check(!StatsHostState.visible && host.originalCalls == 1)

        StatsHostState.open()
        registration.dispatch("forward")
        val interrupted = StatsHostState.closeTicket
        StatsNavigationHooks.openStats()
        StatsHostState.finishClose(interrupted)
        check(StatsHostState.visible && !StatsHostState.closing && host.originalCalls == 1)
        StatsHostState.requestClose()
        val next = StatsHostState.closeTicket
        StatsHostState.finishClose(interrupted)
        check(StatsHostState.visible && StatsHostState.closing)
        StatsHostState.finishClose(next)

        val guard = LyricsGuard(registration).apply { dirty = true }
        registration.extra += TestNavigationRegistration.Entry("navigate", guard)
        StatsHostState.open()
        registration.dispatch("navigate", arrayOf("lyrics"))
        check(guard.pending == null)
        StatsHostState.finishClose(StatsHostState.closeTicket)
        check(!StatsHostState.visible && guard.pending != null && host.originalCalls == 1)
        guard.confirm()
        check(host.originalCalls == 2)

        StatsHostState.setAnimationsEnabled(false)
        StatsHostState.open()
        registration.dispatch("forward") { check(!StatsHostState.visible) }
        check(host.originalCalls == 3 && !StatsHostState.closing)
        StatsHostState.setAnimationsEnabled(true)
        StatsHostState.open()
        registration.dispatch("navigate", arrayOf("after-disable"))
        val disabled = StatsHostState.closeTicket
        registration.close()
        StatsHostState.finishClose(disabled)
        check(!StatsHostState.visible && host.originalCalls == 3)
        println("PASS exit: latest destination only, one replay, repeated back, reopen reversal and stale completion guards")
        println("PASS exit: replay re-enters Lyrics draft confirmation; disabled animation and plugin teardown are immediate")
    } finally {
        presenter.close()
        registration.close()
        StatsHostState.setAnimationsEnabled(true)
    }
}

/** Exercise SPW's real navigate implementation with an expired request and synthetic history. */
private fun checkNativeNoOp(marker: Any) {
    val native = TestNativeNavigator()
    native.navigate.invoke(native.navigator, marker)
    check(native.history.size == 1 && native.history[0] === native.initial)
    check(marker !== native.initial && marker == native.initial)
    println("PASS actual SPW navigate: deferred request is an equal current route and keeps same-route history unchanged")
}
