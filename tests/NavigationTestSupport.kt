@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

import com.jethuit.spw.listenstats.ui.StatsNavigationHooks
import com.xuncorp.spw.workshop.api.hook.MethodHook
import com.xuncorp.spw.workshop.api.hook.MethodHookParam

class TestNavigationHost {
    val pages = mutableListOf("songs", "settings", "plugin-config")
    var originalCalls = 0
    var currentRoute: Any = Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRoute\$Track").getField("INSTANCE").get(null)
    fun original() { originalCalls++ }
    var replay: ((String, Array<Any?>) -> Unit)? = null
    var lastReplay: Pair<String, List<Any?>>? = null
    private fun replay(method: String, args: Array<Any?>) {
        lastReplay = method to args.toList()
        replay?.invoke(method, args)
    }
    fun navigate(route: Any?) = replay("navigate", arrayOf(route))
    fun replace(route: Any?) = replay("replace", arrayOf(route))
    fun forward() = replay("forward", emptyArray())
    fun back() = replay("back", emptyArray())
}

class TestNavigationCall(val host: TestNavigationHost, override val args: Array<Any?>,
                         methodName: String,
                         private val original: () -> Unit) : MethodHookParam() {
    override val method = TestNavigationHost::class.java.getDeclaredMethod(methodName,
        *(if (methodName in listOf("navigate", "replace")) arrayOf(Any::class.java) else emptyArray()))
    override val thisObject: Any = host
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

/** Hook API ordering, including the priority-80 asynchronous leave guard used by Lyrics. */
class TestNavigationRegistration(val host: TestNavigationHost = TestNavigationHost(), bind: Boolean = true) : AutoCloseable {
    data class Entry(val method: String, val callback: MethodHook)
    val hooks = mutableListOf<Entry>()
    val extra = mutableListOf<Entry>()
    val closed = mutableListOf<String>()
    var lastRequest: Any? = null
    private val registration = StatsNavigationHooks.register { method, parameters, callback ->
        val types = parameters.map { Class.forName(it) }.toTypedArray()
        check(Class.forName(StatsNavigationHooks.NAVIGATOR_CLASS).getDeclaredMethod(method, *types).returnType == Void.TYPE)
        val entry = Entry(method, callback)
        hooks += entry
        AutoCloseable { closed += "$method@${callback.priority}"; hooks.remove(entry) }
    }
    private val binding = if (!bind) null else StatsNavigationHooks.bindNavigator(host, { host.currentRoute }) { route ->
        lastRequest = route
        dispatch("navigate", arrayOf(route)) {
            check(route == host.currentRoute) { "Open request was not an equal copy of the current host route" }
        }
    }
    fun dispatch(method: String, args: Array<Any?> = emptyArray(), original: () -> Unit = {}): TestNavigationCall {
        val call = TestNavigationCall(host, args, method, original)
        val entered = mutableListOf<MethodHook>()
        for (entry in (hooks + extra).filter { it.method == method }.sortedByDescending { it.callback.priority }) {
            entered += entry.callback
            entry.callback.before(call)
            if (call.isOriginalSkipped) break
        }
        if (!call.isOriginalSkipped) call.invokeOriginal()
        entered.asReversed().forEach { it.after(call) }
        return call
    }
    init { host.replay = { method, args -> dispatch(method, args); Unit } }
    override fun close() { host.replay = null; binding?.close(); registration.close() }
}

class TestNativeNavigator {
    private val loader = StatsNavigationHooks::class.java.classLoader
    val routeType = Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRoute", false, loader)
    val initial: Any = Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRoute\$Track", false, loader)
        .getField("INSTANCE").get(null)
    private val stackType = Class.forName("androidx.navigation3.runtime.NavBackStack", false, loader)
    @Suppress("UNCHECKED_CAST")
    val history = stackType.getConstructor().newInstance() as MutableList<Any>
    val navigator: Any
    val navigate: java.lang.reflect.Method
    init {
        history += initial
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").let {
            it.isAccessible = true; it.get(null) as sun.misc.Unsafe
        }
        val playerType = Class.forName("com.xuncorp.voxzen.ui.component.PlayerSheetState", false, loader)
        navigator = Class.forName(StatsNavigationHooks.NAVIGATOR_CLASS, false, loader)
            .getConstructor(routeType, Set::class.java, stackType, playerType)
            .newInstance(initial, emptySet<Any>(), history, unsafe.allocateInstance(playerType))
        navigate = navigator.javaClass.getDeclaredMethod("navigate", routeType)
    }
}
