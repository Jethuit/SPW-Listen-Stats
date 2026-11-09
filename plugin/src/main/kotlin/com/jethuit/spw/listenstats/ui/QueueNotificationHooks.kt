@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package com.jethuit.spw.listenstats.ui

import com.xuncorp.spw.workshop.api.hook.MethodHook
import com.xuncorp.spw.workshop.api.hook.MethodHookParam
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.Type

/** Suppress only the success call made by native queue coroutines carrying our exact item. */
internal object QueueNotificationHooks {
    class Targets(val coroutine: Method, val items: Field, val success: Method)
    private class ItemRef(item: Any, queue: ReferenceQueue<Any>? = null) : WeakReference<Any>(item, queue) {
        private val identity = System.identityHashCode(item)
        override fun hashCode() = identity
        override fun equals(other: Any?) = this === other ||
            (other is ItemRef && get() != null && get() === other.get())
    }
    private class Session(val targets: Targets) {
        @Volatile var enabled = true
        val expired = ReferenceQueue<Any>()
        val items = ConcurrentHashMap<ItemRef, Boolean>()
        fun clean() { while (true) items.remove(expired.poll() as? ItemRef ?: break) }
    }
    private class Entered(val previous: Session?, val items: Array<*>?, val owned: Boolean)
    @Volatile private var current: Session? = null
    private val executing = ThreadLocal<Session?>()

    fun track(item: Any): AutoCloseable {
        val session = current?.takeIf { it.enabled }
            ?: throw SongPlaybackUnavailable("队列静默接入不可用，请重新启用插件或更新插件")
        session.clean()
        val key = ItemRef(item, session.expired)
        session.items[key] = true
        return AutoCloseable { session.items.remove(key) }
    }

    fun register(targets: Targets,
                 addHook: (String, String, List<String>, MethodHook) -> AutoCloseable): AutoCloseable {
        check(current == null) { "Queue notification hooks already active" }
        val session = Session(targets)
        val entered = Collections.synchronizedMap(IdentityHashMap<MethodHookParam, Entered>())
        val handles = HookHandles()
        fun hook(method: Method, callback: MethodHook): AutoCloseable = addHook(
            method.declaringClass.name, method.name, method.parameterTypes.map { it.name }, callback)
        try {
            handles += hook(targets.coroutine, object : MethodHook(100) {
                override fun before(call: MethodHookParam) {
                    session.clean()
                    val items = runCatching { targets.items.get(call.thisObject) as? Array<*> }.getOrNull()
                    val owned = session.enabled && items?.any { it != null && session.items.containsKey(ItemRef(it)) } == true
                    entered[call] = Entered(executing.get(), items, owned)
                    // An unrelated nested queue operation must not inherit an owned operation's scope.
                    if (owned) executing.set(session) else executing.remove()
                }
                override fun after(call: MethodHookParam) {
                    val scope = entered.remove(call) ?: return
                    try {
                        if (scope.owned && (call.throwable != null || call.result !== COROUTINE_SUSPENDED)) {
                            scope.items?.filterNotNull()?.forEach { session.items.remove(ItemRef(it)) }
                        }
                    } finally {
                        if (scope.previous == null) executing.remove() else executing.set(scope.previous)
                    }
                }
            })
            handles += hook(targets.success, object : MethodHook(100) {
                override fun before(call: MethodHookParam) {
                    if (session.enabled && executing.get() === session) call.setResult(null)
                }
            })
        } catch (failure: Throwable) {
            session.enabled = false
            throw handles.registrationFailed(failure)
        }
        current = session
        return AutoCloseable {
            session.enabled = false
            session.items.clear()
            if (current === session) current = null
            handles.close()
        }
    }

    /** Follow the controller -> service -> coroutine -> success-call structure, never an obfuscated name. */
    fun prepare(loader: ClassLoader): Targets {
        val controller = Class.forName("com.xuncorp.voxzen.service.PlaybackController", false, loader)
        val item = Class.forName("com.xuncorp.pisces.PiscesMediaItem", false, loader)
        val add = controller.methods.single { method ->
            val types = method.parameterTypes
            method.name == "addToPlayNext" && types.size == 2 && types[0].isEnum &&
                types[1].isArray && types[1].componentType == item && method.returnType == Void.TYPE
        }
        val serviceCall = body(add).calls.single { (_, target) ->
            target.name == add.name && target.descriptor == Type.getMethodDescriptor(add) && target.className != controller.name
        }.second
        val service = Class.forName(serviceCall.className, false, loader)
        val serviceMethod = service.getDeclaredMethod(serviceCall.name, *add.parameterTypes)
        val coroutineType = body(serviceMethod).created.map { Class.forName(it.replace('/', '.'), false, loader) }
            .single { it.superclass?.name == "kotlin.coroutines.jvm.internal.SuspendLambda" }
        val coroutine = coroutineType.getDeclaredMethod("invokeSuspend", Any::class.java)
        val items = coroutineType.declaredFields.single { !Modifier.isStatic(it.modifiers) &&
            it.type.isArray && it.type.componentType == item }.also { check(it.trySetAccessible()) }
        val successCall = body(coroutine).calls.single { (opcode, target) ->
            opcode == Opcodes.INVOKESTATIC && target.descriptor == "(Ljava/lang/String;)V"
        }.second
        val success = Class.forName(successCall.className, false, loader)
            .getDeclaredMethod(successCall.name, String::class.java)
        check(Modifier.isStatic(success.modifiers) && success.returnType == Void.TYPE)
        return Targets(coroutine, items, success)
    }

    private class Body(val calls: MutableList<Pair<Int, HostMethod>> = mutableListOf(),
                       val created: MutableList<String> = mutableListOf())
    private fun body(method: Method): Body {
        val result = Body()
        val owner = method.declaringClass
        val bytes = checkNotNull(owner.getResourceAsStream("/${owner.name.replace('.', '/')}.class"))
            .use { it.readBytes() }
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(access: Int, name: String, descriptor: String,
                signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                if (name != method.name || descriptor != Type.getMethodDescriptor(method)) return null
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(opcode: Int, owner: String, name: String,
                        descriptor: String, isInterface: Boolean) {
                        result.calls += opcode to HostMethod(owner, name, descriptor)
                    }
                    override fun visitTypeInsn(opcode: Int, type: String) {
                        if (opcode == Opcodes.NEW) result.created += type
                    }
                }
            }
        }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return result
    }
}
