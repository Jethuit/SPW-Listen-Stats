@file:OptIn(androidx.compose.runtime.InternalComposeApi::class)

package com.jethuit.spw.listenstats.ui

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocal
import java.lang.invoke.MethodType
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** All reflected host members belong to one enable session. No process-lifetime lazy targets. */
class HostUiBridge(val targets: HostUiTargets, private val loader: ClassLoader,
    val readAppearance: () -> HostAppearance = HostAppearanceReader::read) {
    // Prepared on the discovery worker before any statistics page can be presented.
    val initialAppearance = readAppearance()
    private val preparationWarnings = mutableListOf<String>()
    val warnings: List<String> get() = targets.warnings + preparationWarnings
    private fun <T> optional(role: String, prepare: () -> T): T? = runCatching(prepare)
        .onFailure { preparationWarnings += "$role reflection: ${it.message}" }.getOrNull()
    fun method(key: HostMethod): Method {
        val signature = MethodType.fromMethodDescriptorString(key.descriptor, loader)
        return Class.forName(key.className, false, loader).getDeclaredMethod(key.name, *signature.parameterArray()).also {
            check(it.returnType == signature.returnType() && it.trySetAccessible()) { "Host method unavailable: $key" }
        }
    }
    fun field(key: HostField): Field = Class.forName(key.owner.replace('/', '.'), false, loader)
        .getDeclaredField(key.name).also {
            check(it.type.descriptorString() == key.descriptor && it.trySetAccessible()) { "Host field unavailable: $key" }
        }

    val layer = method(targets.layer.target).also { check(Modifier.isStatic(it.modifiers)) }
    val navigatorType = Class.forName(targets.navigation.navigate.className, false, loader)
    val navigate = method(targets.navigation.navigate)
    val stack = field(targets.navigation.stack)
    private val navigatorLocal = field(targets.navigation.local).also { check(Modifier.isStatic(it.modifiers)) }
    private val player = field(targets.navigation.player)
    private val expanded = method(targets.navigation.expanded)
    private val dialog = method(targets.navigation.dialog).also { check(Modifier.isStatic(it.modifiers)) }
    init {
        listOf(targets.navigation.back, targets.navigation.forward, targets.navigation.replace).forEach { method(it) }
        targets.transitions.forEach { check(Modifier.isStatic(method(it).modifiers)) }
    }

    // Optional groups are prepared independently, before installing their hooks.
    val caption = targets.caption?.let { key -> optional("caption") {
        method(key.control).also { check(Modifier.isStatic(it.modifiers)) }
    } }
    private val theme = targets.theme?.let { key -> optional("theme") {
        method(key.configs).also { check(Modifier.isStatic(it.modifiers)) } to
            method(key.dark).also { check(!Modifier.isStatic(it.modifiers)) }
    } }
    @Suppress("UNCHECKED_CAST")
    fun local(): CompositionLocal<Any?> = navigatorLocal.get(null) as CompositionLocal<Any?>
    fun currentRoute(navigator: Any): Any? = (stack.get(navigator) as List<*>).lastOrNull()
    fun hasOverlay(navigator: Any): Boolean {
        val route = currentRoute(navigator)
        return (route != null && dialog.invoke(null, route) == true) || expanded.invoke(player.get(navigator)) == true
    }
    fun dark(composer: Composer): Boolean? = theme?.let { (configs, dark) ->
        dark.invoke(configs.invoke(null, composer)) as Boolean
    }
}

object HostUiRuntime {
    @Volatile var current: HostUiBridge? = null
        private set
    fun require(): HostUiBridge = checkNotNull(current) { "Host UI discovery is not ready" }
    fun activate(bridge: HostUiBridge): AutoCloseable {
        check(current == null) { "A host UI session is already active" }
        current = bridge
        return AutoCloseable { if (current === bridge) current = null }
    }
}
