import com.jethuit.spw.listenstats.ui.*
import java.awt.EventQueue
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

fun main(args: Array<String>) {
    val path = Path.of(args.single())
    val index = HostBytecodeIndex.read(path)
    val targets = HostUiDiscovery.discover(index)
    check(targets.caption != null && targets.theme != null && targets.warnings.isEmpty())
    val bridge = HostUiBridge(targets, HostUiBridge::class.java.classLoader)
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
    if (path.fileName.toString() == "spw-host.jar") checkLifecycle(bridge)
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
