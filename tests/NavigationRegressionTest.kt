@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

import com.jethuit.spw.listenstats.ui.StatsHostState
import com.jethuit.spw.listenstats.ui.StatsNavigationHooks
import com.jethuit.spw.listenstats.ui.StatsExitMotion
import com.jethuit.spw.listenstats.ui.StatsHostTransitionHooks
import com.jethuit.spw.listenstats.ui.HostBackPriority
import androidx.compose.animation.ContentTransform
import java.lang.reflect.Proxy
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

private fun checkNavigationGuards() {
    TestNavigationRegistration().use { registration ->
        val presenter = StatsHostState.attachPresenter()
        try {
            StatsHostState.setAnimationsEnabled(false)
            StatsHostState.open()
            val rejection = TestNavigationRegistration.Entry("navigate", object : MethodHook(80) {
                override fun before(call: MethodHookParam) { call.setResult(null) }
            })
            registration.extra += rejection
            registration.dispatch("navigate", arrayOf("rejected")) { error("Rejected navigation reached host") }
            check(StatsHostState.visible && !StatsHostState.closing)
            registration.extra.remove(rejection)
            registration.dispatch("navigate", arrayOf("accepted"))
            check(!StatsHostState.visible)
            StatsHostState.open()
            registration.host.overlayOpen = true
            registration.dispatch("back") { registration.host.overlayOpen = false }
            check(StatsHostState.visible && !StatsHostState.closing)
            registration.dispatch("back") { error("Statistics back popped native history") }
            check(!StatsHostState.visible)
            StatsHostState.setAnimationsEnabled(true)
            var deferred: Array<Any?>? = null
            var hold = true
            registration.extra += TestNavigationRegistration.Entry("navigate", object : MethodHook(80) {
                override fun before(call: MethodHookParam) {
                    if (hold && call.args.firstOrNull() === registration.lastRequest) {
                        deferred = call.args.copyOf(); call.setResult(null)
                    }
                }
            })
            StatsNavigationHooks.openStats()
            check(!StatsHostState.visible && deferred != null)
            registration.dispatch("navigate", arrayOf(registration.host.currentRoute))
            hold = false
            registration.dispatch("navigate", deferred!!)
            check(!StatsHostState.visible) { "Same-route navigation did not invalidate the older deferred open" }
        } finally { presenter.close(); StatsHostState.setAnimationsEnabled(true) }
    }
    val native = TestNativeNavigator()
    val dialogs = Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRouteKt")
        .getDeclaredMethod("getDialogScreenRoutes").invoke(null) as Set<*>
    native.history += dialogs.first()!!
    check(HostBackPriority.hasOverlay(native.navigator))
    native.navigator.javaClass.getDeclaredMethod("back").invoke(native.navigator)
    check(native.history.size == 1 && native.history.single() === native.initial)
    // Check the exact state getter consumed by the installed host, not an arbitrary boolean accessor.
    val playerCalls = mutableListOf<String>()
    val navigatorClass = StatsNavigationHooks.NAVIGATOR_CLASS.replace('.', '/')
    ClassLoader.getSystemResourceAsStream("$navigatorClass.class").use { stream ->
        net.bytebuddy.jar.asm.ClassReader(checkNotNull(stream)).accept(object : net.bytebuddy.jar.asm.ClassVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9) {
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): net.bytebuddy.jar.asm.MethodVisitor? {
                if (name != "back") return null
                return object : net.bytebuddy.jar.asm.MethodVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9) {
                    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                        if (owner.endsWith("/PlayerSheetState") && descriptor == "()Z") playerCalls += name
                    }
                }
            }
        }, net.bytebuddy.jar.asm.ClassReader.SKIP_DEBUG or net.bytebuddy.jar.asm.ClassReader.SKIP_FRAMES)
    }
    check(playerCalls == listOf("\u0528"))
    println("PASS disabled animation respects rejected navigation; native overlays get back priority; stale same-route opens stay canceled")
}

fun main() {
    com.jethuit.spw.listenstats.ui.HostUiRuntime.activate(com.jethuit.spw.listenstats.ui.HostUiBridge(
        com.jethuit.spw.listenstats.ui.HostUiDiscovery.discover(com.jethuit.spw.listenstats.ui.HostBytecodeIndex.read(
            java.nio.file.Path.of("build/tools/spw-host.jar"))), StatsNavigationHooks::class.java.classLoader))
    checkSnapshotPreparation()
    val registration = TestNavigationRegistration()
    check(registration.hooks.size == 5)
    val host = registration.host
    val initialRoute = host.currentRoute
    for (method in listOf("navigate", "replace", "forward")) {
        StatsHostState.open()
        registration.dispatch(method) { check(StatsHostState.visible); if (method == "forward") host.advanceRoute() }
        check(!StatsHostState.visible)
    }
    println("PASS ordinary navigation closes statistics only after the host accepts the request")
    host.currentRoute = initialRoute

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
    checkNativeForwardMotion()
    checkNavigationGuards()
    checkStatisticsForwardRestore()
    checkConfigurationEntry()
    val stoppedBridge = com.jethuit.spw.listenstats.ui.HostUiBridge(
        com.jethuit.spw.listenstats.ui.HostUiRuntime.require().targets,
        StatsNavigationHooks::class.java.classLoader)
    // A fresh lifecycle can be retired while its EDT cleanup is still queued.
    val previousRuntime = com.jethuit.spw.listenstats.ui.HostUiRuntime.current
    val runtimeField = com.jethuit.spw.listenstats.ui.HostUiRuntime::class.java.getDeclaredField("current")
        .also { check(it.trySetAccessible()) }
    runtimeField.set(null, stoppedBridge)
    try {
        TestNavigationRegistration().use { registration ->
            val guard = LyricsGuard(registration).apply { dirty = true }
            registration.extra += TestNavigationRegistration.Entry("navigate", guard)
            StatsNavigationHooks.openStats()
            check(guard.pending != null && !StatsHostState.visible)
            stoppedBridge.stopActions()
            guard.confirm()
            check(!StatsHostState.visible)
            val lastRequest = registration.lastRequest
            StatsNavigationHooks.openStats()
            StatsNavigationHooks.openConfiguration()
            check(!StatsHostState.visible && registration.lastRequest === lastRequest)
        }
    } finally { runtimeField.set(null, previousRuntime) }
    println("PASS retired UI session rejects deferred replay and cannot open statistics/configuration while cleanup is queued")

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

private fun checkConfigurationEntry() {
    TestNavigationRegistration(bind = false).use { registration ->
        var destination: Any? = null
        val binding = StatsNavigationHooks.bindNavigator(registration.host,
            currentRoute = { registration.host.currentRoute }) { route ->
            registration.dispatch("navigate", arrayOf(route)) {
                destination = route
                registration.host.currentRoute = route
            }
        }
        try {
            StatsHostState.setAnimationsEnabled(false)
            StatsHostState.open()
            val guard = TestNavigationRegistration.Entry("navigate", object : MethodHook(80) {
                override fun before(call: MethodHookParam) { call.setResult(null) }
            })
            registration.extra += guard
            StatsNavigationHooks.openConfiguration()
            check(destination == null && StatsHostState.visible)
            registration.extra.remove(guard)
            StatsNavigationHooks.openConfiguration()
            val route = checkNotNull(destination)
            check(route.javaClass.name.endsWith("ScreenRoute\$ModConfigContent"))
            check(route.javaClass.getMethod("getPluginId").invoke(route) == com.jethuit.spw.listenstats.PluginState.pluginId)
            check(!StatsHostState.visible)
        } finally { binding.close(); StatsHostState.close() }
    }
    println("PASS configuration gear navigates to this plugin's real host route and honors rejected navigation")
}

/** Exercise the installed host's real transition factories and the outgoing-scene adapter. */
private fun checkNativeForwardMotion() {
    val scopeType = Class.forName("androidx.compose.animation.AnimatedContentTransitionScope")
    val scope = Proxy.newProxyInstance(scopeType.classLoader, arrayOf(scopeType)) { _, method, _ ->
        error("Native transition unexpectedly reads ${method.name}")
    }
    val presenter = StatsHostState.attachPresenter()
    val handle = StatsHostTransitionHooks.register { name, parameters, hook ->
        val types = parameters.map { if (it == "int") Int::class.javaPrimitiveType!! else Class.forName(it) }
        val resolvedMethod = Class.forName(StatsHostTransitionHooks.TARGET_CLASS).getDeclaredMethod(name, *types.toTypedArray())
        check(resolvedMethod.trySetAccessible())
        val businessArgs: Array<Any?> = if (types.size == 2) arrayOf(scope, 0) else arrayOf(scope)
        val original = resolvedMethod.invoke(null, *businessArgs) as ContentTransform
        var returned: Any? = original
        val call = object : MethodHookParam() {
            override val method = resolvedMethod
            override val thisObject: Any? = null
            override val args = businessArgs
            override val result: Any? get() = returned
            override val throwable: Throwable? = null
            override var isOriginalSkipped = false
            override fun setResult(result: Any?) { returned = result; isOriginalSkipped = true }
            override fun setThrowable(throwable: Throwable) { error("Unexpected exception") }
            override fun invokeOriginal(): Any? = error("Must not replay native animation factory")
        }
        StatsHostState.close()
        hook.after(call)
        check(call.result === original)
        StatsHostState.open()
        StatsHostState.requestClose()
        hook.after(call)
        check(call.result === original) { "Explicit return's native transition was changed" }
        StatsHostState.open()
        StatsHostState.requestNavigation()
        hook.after(call)
        val adapted = call.result as ContentTransform
        check(adapted !== original && adapted.targetContentEnter === original.targetContentEnter &&
            adapted.initialContentExit !== original.initialContentExit && adapted.sizeTransform === original.sizeTransform)
        AutoCloseable { }
    }
    handle.close()
    StatsHostState.close()
    presenter.close()
    println("PASS real native transition factories: incoming motion retained, obsolete host scene removed only for forward cover")
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
                check(StatsHostState.exitMotion == StatsExitMotion.Navigate && !StatsHostState.nativeNavigation) {
                    "Plugin switch must cover statistics instead of playing a return"
                }
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
            val previousDestination = StatsHostState.closeTicket
            guard.open()
            StatsHostState.finishClose(previousDestination)
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

/** Native navigation prepares the destination under the isolated statistics exit. */
private fun checkExitNavigation() {
    val registration = TestNavigationRegistration()
    val host = registration.host
    val presenter = StatsHostState.attachPresenter()
    StatsHostState.setAnimationsEnabled(true)
    try {
        val songs = host.currentRoute
        val albums = Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRoute\$Album")
            .getField("INSTANCE").get(null)
        host.currentRoute = albums
        StatsHostState.open()
        registration.dispatch("navigate", arrayOf(songs)) {
            check(StatsHostState.visible) { "Statistics disappeared before target preparation" }
            host.currentRoute = songs
        }
        val ticket = StatsHostState.closeTicket
        check(StatsHostState.visible && StatsHostState.closing && host.originalCalls == 1)
        check(host.currentRoute == songs && !StatsHostState.revealHostOnExit) {
            "Album -> statistics -> songs restored the old Album page"
        }
        check(StatsHostState.exitMotion == StatsExitMotion.Navigate && StatsHostState.nativeNavigation)
        registration.dispatch("replace", arrayOf("artists")) { host.currentRoute = "artists" }
        check(StatsHostState.closeTicket == ticket)
        StatsHostState.finishClose(ticket)
        check(!StatsHostState.visible && host.originalCalls == 2 && host.currentRoute == "artists" && host.lastReplay == null)
        StatsHostState.finishClose(ticket)
        check(host.originalCalls == 2)
        host.currentRoute = songs
        println("PASS Album -> statistics -> songs: target prepared immediately, old page isolated, no second navigation at exit")

        StatsHostState.open()
        registration.dispatch("navigate", arrayOf("accepted"))
        val accepted = StatsHostState.closeTicket
        registration.dispatch("back") { error("Back escaped to host history during exit") }
        StatsHostState.finishClose(accepted)
        check(!StatsHostState.visible && host.originalCalls == 3)

        StatsHostState.open()
        registration.dispatch("forward") { host.advanceRoute() }
        val interrupted = StatsHostState.closeTicket
        StatsNavigationHooks.openStats()
        StatsHostState.finishClose(interrupted)
        check(StatsHostState.visible && !StatsHostState.closing && host.originalCalls == 4)
        StatsHostState.requestClose()
        check(StatsHostState.revealHostOnExit) { "Ordinary back no longer restores the original page" }
        check(StatsHostState.exitMotion == StatsExitMotion.Return)
        val next = StatsHostState.closeTicket
        StatsHostState.finishClose(interrupted)
        check(StatsHostState.visible && StatsHostState.closing)
        StatsHostState.finishClose(next)

        val guard = LyricsGuard(registration).apply { dirty = true }
        registration.extra += TestNavigationRegistration.Entry("navigate", guard)
        StatsHostState.open()
        registration.dispatch("navigate", arrayOf("lyrics"))
        check(guard.pending != null && StatsHostState.visible && !StatsHostState.closing && host.originalCalls == 4)
        guard.cancel()
        check(StatsHostState.visible && !StatsHostState.closing) { "Canceled leave guard still closed statistics" }
        registration.dispatch("navigate", arrayOf("lyrics"))
        guard.confirm()
        check(host.originalCalls == 5 && StatsHostState.closing && !StatsHostState.revealHostOnExit)
        StatsHostState.finishClose(StatsHostState.closeTicket)

        StatsHostState.setAnimationsEnabled(false)
        StatsHostState.open()
        registration.dispatch("forward") { check(StatsHostState.visible); host.advanceRoute() }
        check(!StatsHostState.visible)
        check(host.originalCalls == 6 && !StatsHostState.closing)
        StatsHostState.setAnimationsEnabled(true)
        StatsHostState.open()
        registration.dispatch("navigate", arrayOf("after-disable"))
        val disabled = StatsHostState.closeTicket
        registration.close()
        StatsHostState.finishClose(disabled)
        check(!StatsHostState.visible && host.originalCalls == 7)
        println("PASS exit: latest prepared destination, no replay, repeated back, reopen reversal and stale completion guards")
        println("PASS exit: full Lyrics leave guard runs before handoff; cancel retains statistics, confirm prepares target")
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

private fun checkStatisticsForwardRestore() {
    for (animated in listOf(false, true)) TestNavigationRegistration().use { registration ->
        val presenter = StatsHostState.attachPresenter()
        try {
            StatsHostState.setAnimationsEnabled(animated)
            repeat(3) {
                StatsHostState.open()
                val before = registration.host.pages.toList()
                val calls = registration.host.originalCalls
                registration.dispatch("back") { error("Statistics return changed host history") }
                val oldExit = StatsHostState.closeTicket
                registration.dispatch("forward") { error("Statistics restoration consumed native forward history") }
                check(StatsHostState.visible && !StatsHostState.closing)
                StatsHostState.finishClose(oldExit)
                check(StatsHostState.visible && registration.host.pages == before && registration.host.originalCalls == calls)
                registration.dispatch("forward") // An empty native forward stack must not close the restored page.
                check(StatsHostState.visible && !StatsHostState.closing)
            }
        } finally { presenter.close(); StatsHostState.setAnimationsEnabled(true) }
    }
    println("PASS mouse back/forward restores statistics, reverses unfinished exits and ignores empty native forward, both animation modes")

    TestNavigationRegistration().use { registration ->
        val host = registration.host
        StatsHostState.open()
        registration.dispatch("back")
        val anchor = host.currentRoute
        val entry = Any()
        registration.dispatch("back") { host.forwardHistory += entry; host.advanceRoute() }
        registration.dispatch("forward") { host.currentRoute = anchor; host.forwardHistory.removeLast() }
        check(!StatsHostState.visible)
        registration.dispatch("forward") { error("Forward skipped statistics checkpoint") }
        check(StatsHostState.visible)
        StatsHostState.close()
    }
    println("PASS intervening native back/forward replays host history first, then restores the statistics step")

    TestNavigationRegistration().use { registration ->
        val host = registration.host
        StatsHostState.open(); registration.dispatch("back")
        host.overlayOpen = true
        registration.dispatch("back") { host.overlayOpen = false; host.forwardHistory += Any() }
        registration.dispatch("forward") { host.forwardHistory.removeLast(); host.overlayOpen = true }
        check(host.overlayOpen && !StatsHostState.visible)
        host.overlayOpen = false // Collapse the player directly, without adding another history entry.
        registration.dispatch("forward") { error("Overlay step swallowed the saved statistics step") }
        check(StatsHostState.visible)
        StatsHostState.close()
    }
    println("PASS native overlay history retains priority and cannot be mistaken for the statistics forward step")

    for (method in listOf("navigate", "replace")) TestNavigationRegistration().use { registration ->
        StatsHostState.open(); registration.dispatch("back")
        registration.dispatch(method, arrayOf(registration.host.currentRoute))
        val calls = registration.host.originalCalls
        registration.dispatch("forward")
        check(!StatsHostState.visible && registration.host.originalCalls == calls + 1)
    }
    TestNavigationRegistration().use { registration ->
        StatsHostState.open(); registration.dispatch("back")
        val reject = TestNavigationRegistration.Entry("navigate", object : MethodHook(80) {
            override fun before(call: MethodHookParam) { call.setResult(null) }
        })
        registration.extra += reject
        registration.dispatch("navigate", arrayOf("rejected"))
        registration.extra.remove(reject)
        registration.dispatch("forward") { error("Rejected navigation discarded the statistics checkpoint") }
        check(StatsHostState.visible)
        StatsHostState.close()
    }
    println("PASS accepted new/same-route navigation clears the checkpoint; rejected navigation retains it")

    TestNavigationRegistration().use { registration ->
        StatsHostState.open(); registration.dispatch("back")
        val guard = LyricsGuard(registration).apply { dirty = true }
        registration.extra += TestNavigationRegistration.Entry("navigate", guard)
        registration.dispatch("forward")
        check(!StatsHostState.visible && guard.pending != null)
        guard.cancel()
        check(!StatsHostState.visible)
        registration.dispatch("forward")
        guard.confirm()
        check(StatsHostState.visible && !guard.active)
        StatsHostState.close()
    }
    TestNavigationRegistration().use { registration ->
        StatsHostState.open(); registration.dispatch("back")
        val guard = LyricsGuard(registration).apply { dirty = true }
        registration.extra += TestNavigationRegistration.Entry("navigate", guard)
        registration.dispatch("forward")
        val stale = checkNotNull(guard.pending)
        registration.dispatch("back")
        stale()
        check(!StatsHostState.visible)
    }
    TestNavigationRegistration().use { registration ->
        StatsHostState.open(); registration.dispatch("back")
        val guard = LyricsGuard(registration).apply { dirty = true }
        registration.extra += TestNavigationRegistration.Entry("navigate", guard)
        registration.dispatch("forward")
        val stale = checkNotNull(guard.pending)
        registration.close()
        stale()
        check(!StatsHostState.visible)
    }
    TestNavigationRegistration().use { registration ->
        val calls = registration.host.originalCalls
        registration.dispatch("forward")
        check(!StatsHostState.visible && registration.host.originalCalls == calls + 1)
    }
    println("PASS forward restoration honors Lyrics confirmation/cancel and cannot reopen after a later back or disable/re-enable")

    val native = TestNativeNavigator()
    val bridge = com.jethuit.spw.listenstats.ui.HostUiRuntime.require()
    check(bridge.forwardHistory(native.navigator)?.isEmpty() == true)
    val dialogs = Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRouteKt")
        .getDeclaredMethod("getDialogScreenRoutes").invoke(null) as Set<*>
    val dialog = dialogs.first()!!
    native.history += dialog
    native.navigator.javaClass.getDeclaredMethod("back").invoke(native.navigator)
    val snapshot = checkNotNull(bridge.forwardHistory(native.navigator))
    check(snapshot.size == 1)
    native.navigator.javaClass.getDeclaredMethod("forward").invoke(native.navigator)
    check(native.history.last() === dialog && bridge.forwardHistory(native.navigator)?.isEmpty() == true)
    check(snapshot.size == 1) // Reader returns a snapshot, not the mutable host list.
    println("PASS installed SPW forward-history reader observes real native dialog back/forward without modifying either stack")
}
