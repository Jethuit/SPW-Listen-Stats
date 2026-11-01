@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

import com.jethuit.spw.listenstats.ui.*
import java.awt.EventQueue
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

fun main(args: Array<String>) {
    val path = Path.of(args.single())
    val scanStarted = System.nanoTime()
    val index = HostBytecodeIndex.read(path)
    val targets = HostUiDiscovery.discover(index)
    println("OFFLINE host=$path full scan_ms=${(System.nanoTime() - scanStarted) / 1_000_000}")
    check(targets.caption != null && targets.theme != null && targets.warnings.isEmpty())
    val prepareStarted = System.nanoTime()
    val bridge = HostUiBridge(targets, HostUiBridge::class.java.classLoader)
    println("OFFLINE host=$path prepare_ms=${(System.nanoTime() - prepareStarted) / 1_000_000}")
    val realCacheRoot = java.nio.file.Files.createTempDirectory(Path.of("build/tests/host-discovery"), "real-host-validation-")
    val realCache = HostTargetCache(realCacheRoot.resolve("targets.json"), "offline-test")
    val realIdentity = realCache.identity(path)!!
    realCache.publish(realCache.prepare(realIdentity, targets))
    val validateStarted = System.nanoTime()
    check(realCache.read(realCache.identity(path)!!) == targets)
    println("OFFLINE host=$path cache_validate_ms=${(System.nanoTime() - validateStarted) / 1_000_000}; includes realpath+full SHA256+JSON, no real hooks")
    val names = mutableMapOf<String, String>()
    fun owner(value: String): String = if (value.startsWith("com/xuncorp/voxzen/") || value.any { it.code > 127 })
        names.getOrPut(value) { "test/renamed/Type${names.size}" } else value
    fun descriptor(value: String) = Regex("L([^;]+);").replace(value) { "L${owner(it.groupValues[1])};" }
    fun method(value: HostMethod) = HostMethod(owner(value.owner),
        if (owner(value.owner) != value.owner && !value.name.startsWith("<")) "renamed_${value.name}" else value.name,
        descriptor(value.descriptor))
    fun field(value: HostField) = HostField(owner(value.owner),
        if (owner(value.owner) != value.owner) "renamed_${value.name}" else value.name, descriptor(value.descriptor))
    fun site(value: HostUiSite) = HostUiSite(method(value.target), owner(value.caller))
    val renamed = HostUiDiscovery.discover(HostBytecodeIndex(index.methods.map { body ->
        body.copy(method = method(body.method), calls = body.calls.map(::method), fields = body.fields.map(::field))
    }, index.fields.map(::field)))
    check(renamed.page == site(targets.page) && renamed.layer == site(targets.layer))
    check(renamed.navigation.navigate == method(targets.navigation.navigate))
    check(renamed.navigation.back == method(targets.navigation.back))
    check(renamed.navigation.stack == field(targets.navigation.stack))
    check(renamed.navigation.expanded == method(targets.navigation.expanded))
    check(renamed.caption!!.entry == site(targets.caption!!.entry))
    check(renamed.caption!!.control == method(targets.caption!!.control))
    check(renamed.transitions == targets.transitions.map(::method))
    check(renamed.theme!!.configs == method(targets.theme!!.configs) && renamed.theme!!.dark == method(targets.theme!!.dark))
    check(renamed.warnings.isEmpty())
    println("PASS renamed application classes, methods and fields retain all structural targets")

    fun rejected(label: String, bodies: List<HostMethodBody>) {
        check(runCatching { HostUiDiscovery.discover(HostBytecodeIndex(bodies, index.fields)) }.exceptionOrNull()
            is IllegalStateException) { "Unsafe discovery accepted $label" }
    }
    val root = index.body(targets.page.target)!!
    rejected("missing root", index.methods - root)
    rejected("ambiguous root", index.methods + root.copy(method = root.method.copy(name = "duplicateRoot")))
    rejected("duplicate Layer call", index.methods.map { b ->
        if (b.method.owner == targets.layer.caller && targets.layer.target in b.calls)
            b.copy(calls = b.calls + targets.layer.target) else b
    })
    rejected("missing desktop transform", index.methods.filter { it.method != targets.transitions.first() })
    rejected("missing back", index.methods.filter { it.method != targets.navigation.back })
    rejected("missing player predicate", index.methods.map { b ->
        if (b.method == targets.navigation.back) b.copy(calls = b.calls - targets.navigation.expanded) else b
    })
    println("PASS missing/ambiguous core targets and duplicate call sites fail before registration")

    val captionBody = index.body(targets.caption!!.control)!!
    val noCaption = HostUiDiscovery.discover(HostBytecodeIndex(index.methods - captionBody, index.fields))
    check(noCaption.caption == null && noCaption.warnings.isNotEmpty() && noCaption.layer == targets.layer)
    val ambiguousCaption = HostUiDiscovery.discover(HostBytecodeIndex(index.methods +
        captionBody.copy(method = captionBody.method.copy(owner = "test/DuplicateCaption")), index.fields))
    check(ambiguousCaption.caption == null && ambiguousCaption.warnings.isNotEmpty())
    val noTheme = HostUiDiscovery.discover(HostBytecodeIndex(index.methods.filter {
        it.method != targets.theme!!.dark
    }, index.fields))
    check(noTheme.caption != null && noTheme.theme == null && noTheme.warnings.isNotEmpty())
    println("PASS optional caption/theme failures keep valid core targets without selecting an arbitrary candidate")

    HostUiRuntime.activate(bridge).use {
        check(HostUiRuntime.current === bridge)
        check(bridge.caption != null)
    }
    check(HostUiRuntime.current == null)
    if (path.fileName.toString() == "spw-host.jar") { checkLifecycle(bridge); checkTargetCache(bridge) }
    Thread.currentThread().interrupt()
    try { check(runCatching { HostBytecodeIndex.read(path) }.isFailure) }
    finally { Thread.interrupted() }
    println("PASS scan cancellation and per-session reflected target cleanup")
}

private fun checkLifecycle(bridge: HostUiBridge) {
    val scans = AtomicInteger()
    val installs = AtomicInteger()
    val closes = AtomicInteger()
    val entered = CountDownLatch(1)
    val releaseScan = CountDownLatch(1)
    lateinit var scanner: Thread
    fun integration(ready: CountDownLatch, slow: Boolean) = HostUiIntegration(
        cache = null,
        source = { Path.of("fresh-host-source-${scans.incrementAndGet()}") },
        loader = HostUiBridge::class.java.classLoader,
        install = { installs.incrementAndGet(); AutoCloseable { closes.incrementAndGet() } },
        failed = { throw AssertionError("Unexpected lifecycle error", it) },
        report = { if (it.startsWith("ready")) ready.countDown() },
        discover = { _, _ ->
            if (slow) {
                scanner = Thread.currentThread(); entered.countDown()
                // Even an uncooperative scanner which returns after cancellation must be ignored.
                while (releaseScan.count != 0L) try { releaseScan.await() } catch (_: InterruptedException) { }
            }
            bridge
        }
    )
    val canceled = integration(CountDownLatch(1), true)
    canceled.start()
    check(entered.await(5, TimeUnit.SECONDS))
    EventQueue.invokeAndWait { canceled.close(); canceled.close() }
    val ready = CountDownLatch(1)
    val current = integration(ready, false)
    current.start()
    check(ready.await(5, TimeUnit.SECONDS))
    releaseScan.countDown(); scanner.join(5_000)
    check(!scanner.isAlive)
    EventQueue.invokeAndWait { }
    check(installs.get() == 1 && scans.get() == 2 && closes.get() == 0)
    EventQueue.invokeAndWait { current.close(); current.close() }
    check(closes.get() == 1)
    println("PASS stop during scan is nonblocking; stale results cannot install after re-enable; fresh enable scans again")
}

private fun checkTargetCache(bridge: HostUiBridge) {
    HostUiRuntime.activate(bridge).use {
        fun checkPartial(label: String, register: ((() -> AutoCloseable)) -> AutoCloseable) {
            val acquired = AtomicInteger()
            val closed = AtomicInteger()
            val error = runCatching { register {
                if (acquired.incrementAndGet() == 2) error("second registration failed")
                AutoCloseable { if (closed.incrementAndGet() == 1) error("first cleanup failed") }
            } }.exceptionOrNull()
            check(error is HookRegistrationFailure && !error.rollbackComplete && closed.get() == 1)
            error.remaining.close()
            check(closed.get() == 2 && error.remaining.isEmpty && !error.rollbackComplete)
            println("PASS $label partial registration propagates and retains failed cleanup")
        }
        checkPartial("transition") { acquire -> StatsHostTransitionHooks.register { _, _, _ -> acquire() } }
        checkPartial("navigation") { acquire -> StatsNavigationHooks.register(bridge.targets.navigation) { _, _, _ -> acquire() } }
    }
    val statusClears = AtomicInteger()
    val statusCloses = AtomicInteger()
    val statusHandles = HookHandles()
    statusHandles += AutoCloseable { if (statusCloses.incrementAndGet() == 1) error("close failed") }
    val statusInstallation = HostHookInstallation(statusHandles, true, onClosed = { statusClears.incrementAndGet() })
    check(runCatching { statusInstallation.close() }.isFailure && statusClears.get() == 0)
    statusInstallation.close(); check(statusClears.get() == 1)
    println("PASS registration state clears only after the installation fully releases")
    val root = java.nio.file.Files.createTempDirectory(Path.of("build/tests/host-discovery"), "hook-cache-")
    val host = root.resolve("host.bin")
    val file = root.resolve("host-targets.json")
    java.nio.file.Files.writeString(host, "host one")
    val cache = HostTargetCache(file, "test-dev.1")
    val identity = cache.identity(host)!!
    fun seed(targets: HostUiTargets = bridge.targets) {
        val temporary = cache.prepare(cache.identity(host)!!, targets)
        cache.publish(temporary)
    }
    seed()
    check(cache.read(identity) == bridge.targets)
    check(cache.identity(root) == null)
    check(cache.identity(root.resolve(".").resolve("host.bin")) == identity)
    check(HostTargetCache(file, "test-dev.2").read(identity) == null)
    val original = java.nio.file.Files.readString(file)
    java.nio.file.Files.writeString(file, original.replace("\"format\":1", "\"format\":99"))
    check(cache.read(identity) == null)
    val timestamp = java.nio.file.Files.getLastModifiedTime(host)
    java.nio.file.Files.writeString(host, "host two")
    java.nio.file.Files.setLastModifiedTime(host, timestamp)
    check(cache.identity(host) != identity && cache.read(cache.identity(host)!!) == null)
    println("PASS descriptor-only cache roundtrip; directory, real path, plugin/schema and same-size same-mtime content invalidation")

    fun runCase(label: String, cached: Boolean = true, corrupt: Boolean = false,
                targets: HostUiTargets = bridge.targets,
                result: HostUiBridge = bridge,
                expectCache: Boolean = true,
                hostLoader: ClassLoader = bridge.javaClass.classLoader,
                permission: () -> Unit = {},
                register: (Int) -> AutoCloseable = { AutoCloseable {} }): Pair<Int, Int> {
        if (cached) seed(targets) else java.nio.file.Files.deleteIfExists(file)
        if (corrupt) java.nio.file.Files.writeString(file, "{broken")
        val scans = AtomicInteger()
        val installs = AtomicInteger()
        val done = CountDownLatch(1)
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val session = HostUiIntegration(source = { host }, loader = hostLoader,
            cache = cache, checkInstallAllowed = permission,
            discover = { _, _ -> scans.incrementAndGet(); result },
            install = { check(EventQueue.isDispatchThread()); register(installs.incrementAndGet()) },
            failed = { done.countDown() },
            report = { logs += it; if (it.startsWith("failed") || it == "cancelled" || it == "cache saved" || it.startsWith("cache write skipped") ||
                (it.startsWith("ready") && (!expectCache || cached && !corrupt && scans.get() == 0 || !result.cacheable ||
                    (installs.get() > 0 && logs.any { value -> value.contains("cache write skipped") })))) done.countDown() })
        session.start()
        check(done.await(10, TimeUnit.SECONDS)) { "Timed out $label; $logs" }
        EventQueue.invokeAndWait { session.close() }
        println("PASS $label offline scans=${scans.get()} installs=${installs.get()}; $logs")
        return scans.get() to installs.get()
    }
    check(runCase("first enable generates cache", cached = false) == (1 to 1))
    check(runCase("later enable/restart hits cache") == (0 to 1))
    check(runCase("corrupt cache falls back", corrupt = true) == (1 to 1))
    val bad = bridge.targets.copy(layer = bridge.targets.layer.copy(target = bridge.targets.layer.target.copy(name = "missingMethod")))
    check(runCase("cached reflection failure falls back", targets = bad) == (1 to 1))
    val badTheme = bridge.targets.copy(theme = bridge.targets.theme!!.copy(dark = bridge.targets.theme!!.dark.copy(name = "missingTheme")))
    check(runCase("cached optional reflection failure falls back", targets = badTheme) == (1 to 1))
    for (failure in listOf(SecurityException("theme denied"), java.util.concurrent.CancellationException("theme cancelled"))) {
        val deniedTheme = bridge.targets.copy(theme = bridge.targets.theme!!.copy(dark = bridge.targets.theme!!.dark.copy(owner = "test/DeniedTheme")))
        val deniedLoader = object : ClassLoader(bridge.javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "test.DeniedTheme") throw failure
                return super.loadClass(name, resolve)
            }
        }
        check(runCase("optional reflection ${failure.javaClass.simpleName} forbids scan", targets = deniedTheme,
            hostLoader = deniedLoader) == (0 to 0))
    }
    check(runCase("cached registration rollback permits one retry", register = { attempt ->
        if (attempt == 1) throw HookHandles().registrationFailed(IllegalStateException("register failed"))
        AutoCloseable {}
    }) == (1 to 2))
    check(runCase("cached optional registration failure rolls back then scans", register = { attempt ->
        HostHookInstallation(HookHandles(), attempt > 1)
    }) == (1 to 2))
    check(runCase("permission before registration forbids retry", permission = { throw SecurityException("denied") }) == (0 to 0))
    check(runCase("permission during registration forbids retry", register = {
        throw HookHandles().registrationFailed(SecurityException("denied during registration"))
    }) == (0 to 1))
    check(runCase("cached optional permission failure forbids retry", register = {
        HostHookInstallation(HookHandles(), false, SecurityException("caption permission denied"))
    }) == (0 to 1))
    check(runCase("cached optional cancellation forbids retry", expectCache = false, register = {
        // No failure callback is emitted for an intentionally cancelled task.
        throw HookHandles().registrationFailed(java.util.concurrent.CancellationException("cancelled"))
    }) == (0 to 1))
    val failedCloses = AtomicInteger()
    check(runCase("nested rollback failure forbids retry and retains owner", register = {
        val nested = HookHandles()
        nested += AutoCloseable { if (failedCloses.incrementAndGet() == 1) error("first close failed") }
        val error = nested.registrationFailed(IllegalStateException("partial registration"))
        check(!error.rollbackComplete && failedCloses.get() == 1)
        val parent = HookHandles()
        throw parent.registrationFailed(error).also { check(!it.rollbackComplete && failedCloses.get() == 1) }
    }) == (0 to 1))
    check(failedCloses.get() == 2)
    val incomplete = HostUiBridge(bridge.targets.copy(caption = null, warnings = listOf("caption missing")), bridge.javaClass.classLoader)
    check(runCase("optional discovery downgrade does not cache", cached = false, result = incomplete) == (1 to 1))
    check(!java.nio.file.Files.exists(file))
    check(runCase("optional registration downgrade does not cache", cached = false, expectCache = false, register = {
        HostHookInstallation(HookHandles(), false)
    }) == (1 to 1))
    check(!java.nio.file.Files.exists(file))
    val oldEntered = CountDownLatch(1)
    val oldRelease = CountDownLatch(1)
    val oldReturned = CountDownLatch(1)
    val oldInstalls = AtomicInteger()
    val oldCache = HostTargetCache(file, "old-session")
    val old = HostUiIntegration(source = { host }, loader = bridge.javaClass.classLoader, cache = oldCache,
        discover = { _, _ ->
            oldEntered.countDown()
            while (oldRelease.count > 0) try { oldRelease.await() } catch (_: InterruptedException) { }
            oldReturned.countDown(); bridge
        }, install = { oldInstalls.incrementAndGet(); AutoCloseable {} }, failed = { error("old failure") }, report = {})
    old.start(); check(oldEntered.await(5, TimeUnit.SECONDS))
    EventQueue.invokeAndWait { old.close() }
    check(runCase("new session writes while cancelled old scan remains blocked", cached = false) == (1 to 1))
    val currentCache = java.nio.file.Files.readString(file)
    oldRelease.countDown(); check(oldReturned.await(5, TimeUnit.SECONDS)); EventQueue.invokeAndWait { }
    check(oldInstalls.get() == 0 && java.nio.file.Files.readString(file) == currentCache)
    println("PASS stale scan cannot install or overwrite a newer session cache")
    java.nio.file.Files.deleteIfExists(file)
    val writeFailed = CountDownLatch(1)
    val readyBeforeWrite = AtomicInteger()
    val closeAfterWrite = AtomicInteger()
    val failingCache = object : HostTargetCache(file, "write-failure") {
        override fun prepare(identity: Identity, targets: HostUiTargets): Path = throw java.io.IOException("simulated cache write failure")
    }
    val writeFailureSession = HostUiIntegration(source = { host }, loader = bridge.javaClass.classLoader,
        cache = failingCache, discover = { _, _ -> bridge }, install = { AutoCloseable { closeAfterWrite.incrementAndGet() } },
        failed = { error("cache IO must not fail host integration") }, report = {
            if (it.startsWith("ready")) readyBeforeWrite.incrementAndGet()
            if (it.startsWith("cache write skipped")) writeFailed.countDown()
        })
    writeFailureSession.start(); check(writeFailed.await(5, TimeUnit.SECONDS))
    check(readyBeforeWrite.get() == 1 && closeAfterWrite.get() == 0)
    EventQueue.invokeAndWait { writeFailureSession.close() }
    check(closeAfterWrite.get() == 1)
    println("PASS cache write failure after ready leaves installed hooks active until explicit stop")
    val writing = CountDownLatch(1)
    val releaseWrite = CountDownLatch(1)
    lateinit var writer: Thread
    val delayed = object : HostTargetCache(file, "old-writer") {
        override fun prepare(identity: Identity, targets: HostUiTargets): Path {
            val temporary = super.prepare(identity, targets)
            writer = Thread.currentThread(); writing.countDown()
            while (releaseWrite.count > 0) try { releaseWrite.await() } catch (_: InterruptedException) { }
            return temporary
        }
    }
    java.nio.file.Files.deleteIfExists(file)
    val oldWriter = HostUiIntegration(source = { host }, loader = bridge.javaClass.classLoader, cache = delayed,
        discover = { _, _ -> bridge }, install = { AutoCloseable {} }, failed = { error("writer failure") }, report = {})
    oldWriter.start(); check(writing.await(5, TimeUnit.SECONDS))
    EventQueue.invokeAndWait { oldWriter.close() }
    check(runCase("new session publishes before delayed old writer", cached = false) == (1 to 1))
    val newText = java.nio.file.Files.readString(file)
    releaseWrite.countDown(); writer.join(5_000)
    check(!writer.isAlive && java.nio.file.Files.readString(file) == newText)
    println("PASS delayed old cache writer cannot publish over the new session")
    for (replaceSession in listOf(false, true)) {
        val discarded = CountDownLatch(1)
        val staleReady = AtomicInteger()
        lateinit var reentrant: HostUiIntegration
        var replacement: HostUiIntegration? = null
        val reentrantFile = root.resolve("reentrant-$replaceSession.json")
        reentrant = HostUiIntegration(source = { host }, loader = bridge.javaClass.classLoader,
            cache = HostTargetCache(reentrantFile, "reentrant"), discover = { _, _ -> bridge },
            install = {
                if (replaceSession) replacement = HostUiIntegration(source = { host }, loader = bridge.javaClass.classLoader,
                    cache = null, install = { AutoCloseable {} }, failed = {}, report = {})
                else reentrant.close()
                AutoCloseable { check(EventQueue.isDispatchThread()); discarded.countDown() }
            }, failed = { error("stale install error") }, report = { if (it.startsWith("ready")) staleReady.incrementAndGet() })
        reentrant.start(); check(discarded.await(5, TimeUnit.SECONDS)); EventQueue.invokeAndWait { }
        check(staleReady.get() == 0 && !java.nio.file.Files.exists(reentrantFile))
        EventQueue.invokeAndWait { reentrant.close(); replacement?.close() }
    }
    println("PASS reentrant stop/new-session during install releases returned handle without ready/cache")
    val stopAttempts = AtomicInteger()
    val released = CountDownLatch(1)
    val closing = HostUiIntegration(source = { host }, loader = bridge.javaClass.classLoader, cache = null,
        discover = { _, _ -> bridge }, install = { AutoCloseable {
            if (stopAttempts.incrementAndGet() == 1) error("stop close failed")
        } }, failed = { error("close session failed") }, report = { if (it.startsWith("ready")) released.countDown() })
    closing.start(); check(released.await(5, TimeUnit.SECONDS))
    check(runCatching { closing.close() }.isFailure)
    check(stopAttempts.get() == 1)
    closing.close(); check(stopAttempts.get() == 2)
    println("PASS close failure propagates, retains ownership and only explicit next close retries release")
    val closes = AtomicInteger()
    val same = IllegalStateException("shared error")
    val group = HookHandles()
    repeat(3) { group += AutoCloseable { closes.incrementAndGet(); throw same } }
    check(runCatching { group.registrationFailed(same) }.getOrThrow().rollbackComplete == false)
    check(closes.get() == 3)
    println("PASS cleanup visits all handles even with shared exception instances")
}
