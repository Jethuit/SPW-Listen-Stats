@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

import com.jethuit.spw.listenstats.ui.QueueNotificationHooks
import com.xuncorp.spw.workshop.api.hook.MethodHook
import com.xuncorp.spw.workshop.api.hook.MethodHookParam
import java.lang.reflect.Method
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

private class QueueCoroutineProbe(@JvmField val items: Array<Any>) {
    fun invokeSuspend(value: Any?): Any? = value
}
private object QueueSuccessProbe {
    @JvmStatic fun success(message: String) { error("Synthetic toast should never execute: $message") }
}
private class QueueHookCall(override val method: Method, override val thisObject: Any?,
                            initialResult: Any? = null) : MethodHookParam() {
    override val args: Array<Any?> = arrayOf(null)
    private var returned: Any? = initialResult
    private var failure: Throwable? = null
    override val result: Any? get() = returned
    override val throwable: Throwable? get() = failure
    override var isOriginalSkipped = false
        private set
    override fun setResult(result: Any?) { returned = result; isOriginalSkipped = true }
    override fun setThrowable(throwable: Throwable) { failure = throwable; isOriginalSkipped = true }
    override fun invokeOriginal(): Any? = returned
}

fun main() = runBlocking {
    val actual = QueueNotificationHooks.prepare(QueueHookCall::class.java.classLoader)
    check(actual.coroutine.name == "invokeSuspend")
    check(actual.items.type.componentType.name == "com.xuncorp.pisces.PiscesMediaItem")
    check(actual.success.parameterTypes.toList() == listOf(String::class.java))
    println("PASS current SPW notification targets discovered without invoking player/service")

    val coroutine = QueueCoroutineProbe::class.java.getDeclaredMethod("invokeSuspend", Any::class.java)
    val toast = QueueSuccessProbe::class.java.getDeclaredMethod("success", String::class.java)
    val targets = QueueNotificationHooks.Targets(coroutine,
        QueueCoroutineProbe::class.java.getDeclaredField("items").also { it.isAccessible = true }, toast)
    val hooks = mutableMapOf<String, MethodHook>()
    val closed = mutableListOf<String>()
    val registration = QueueNotificationHooks.register(targets) { _, name, _, callback ->
        hooks[name] = callback
        AutoCloseable { closed += name }
    }
    fun successSkipped(): Boolean = QueueHookCall(toast, null).also { hooks.getValue("success").before(it) }.isOriginalSkipped
    val item = Any()
    val owned = QueueCoroutineProbe(arrayOf(item))
    val unrelated = QueueCoroutineProbe(arrayOf(Any()))
    QueueNotificationHooks.track(item)
    val first = QueueHookCall(coroutine, owned, COROUTINE_SUSPENDED)
    hooks.getValue("invokeSuspend").before(first)
    check(successSkipped())
    val nested = QueueHookCall(coroutine, unrelated)
    hooks.getValue("invokeSuspend").before(nested)
    check(!successSkipped())
    hooks.getValue("invokeSuspend").after(nested)
    check(successSkipped())
    hooks.getValue("invokeSuspend").after(first)
    check(!successSkipped())
    println("PASS only our queue coroutine suppresses success; unrelated nested/native toasts stay intact")

    withContext(Dispatchers.Default) {
        val resumed = QueueHookCall(coroutine, owned)
        hooks.getValue("invokeSuspend").before(resumed)
        check(successSkipped())
        hooks.getValue("invokeSuspend").after(resumed)
        check(!successSkipped())
    }
    val completed = QueueHookCall(coroutine, owned)
    hooks.getValue("invokeSuspend").before(completed)
    check(!successSkipped())
    hooks.getValue("invokeSuspend").after(completed)
    println("PASS suspension/thread changes preserve item ownership; completion removes suppression")

    registration.close()
    check(closed == listOf("success", "invokeSuspend"))
    check(runCatching { QueueNotificationHooks.track(Any()) }.isFailure)
    check(!successSkipped())
    println("PASS disable releases both hooks and stops tracking new requests")

    val rollback = mutableListOf<String>()
    val failure = runCatching {
        QueueNotificationHooks.register(targets) { _, name, _, _ ->
            if (name == "success") error("synthetic registration failure")
            AutoCloseable { rollback += name }
        }
    }.exceptionOrNull()
    check(failure != null && rollback == listOf("invokeSuspend"))
    check(runCatching { QueueNotificationHooks.track(Any()) }.isFailure)
    println("PASS partial registration rolls back; no real notifications or playback were invoked")
}
