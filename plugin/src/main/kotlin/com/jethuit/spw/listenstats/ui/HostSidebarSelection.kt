@file:OptIn(androidx.compose.runtime.InternalComposeApi::class)

package com.jethuit.spw.listenstats.ui

import androidx.compose.runtime.*
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal val LocalUnmaskedSidebarStyle = staticCompositionLocalOf<Any?> { null }

/** A scoped style copy hides the underlying route's highlight while statistics owns the sidebar. */
class HostSidebarSelection(control: Method) {
    private val owner = control.declaringClass
    private val styleType = owner.declaredMethods.single {
        Modifier.isStatic(it.modifiers) && it.parameterTypes.toList() == listOf(Composer::class.java) &&
            !it.returnType.isPrimitive
    }.returnType
    @Suppress("UNCHECKED_CAST")
    val local = owner.declaredMethods.single { Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
        it.returnType == ProvidableCompositionLocal::class.java }.invoke(null) as ProvidableCompositionLocal<Any?>
    private val constructor = styleType.declaredConstructors.single {
        val p = it.parameterTypes
        p.size == 10 && p[0].isEnum && p[1] == control.parameterTypes[6] &&
            p.slice(2..5).all { type -> type == Long::class.javaPrimitiveType } &&
            p[6].name == "androidx.compose.ui.text.TextStyle" && p[7] == p[6] &&
            p[8].name == "androidx.compose.ui.graphics.Shape" && p[9] == Boolean::class.javaPrimitiveType
    }.also { check(it.trySetAccessible()) }
    private fun getter(type: Class<*>) = styleType.declaredMethods.single {
        it.parameterCount == 0 && it.returnType == type && !Modifier.isStatic(it.modifiers)
    }
    private val mode = getter(constructor.parameterTypes[0])
    private val dimensions = getter(constructor.parameterTypes[1])
    private val shape = getter(constructor.parameterTypes[8])
    private val indicator = getter(Boolean::class.javaPrimitiveType!!)
    private val color = styleType.declaredMethods.single {
        it.parameterTypes.toList() == listOf(Boolean::class.javaPrimitiveType) && it.returnType == Long::class.javaPrimitiveType
    }
    private val text = styleType.declaredMethods.single {
        it.parameterTypes.toList() == listOf(Boolean::class.javaPrimitiveType) && it.returnType == constructor.parameterTypes[6]
    }
    private val background = styleType.declaredMethods.single {
        it.parameterTypes.toList() == listOf(Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType) &&
            it.returnType == Long::class.javaPrimitiveType
    }
    private val invokeContent = Class.forName("kotlin.jvm.functions.Function2", false, control.declaringClass.classLoader)
        .getMethod("invoke", Any::class.java, Any::class.java)

    fun unselected(original: Any): Any {
        check(styleType.isInstance(original))
        val normalColor = color.invoke(original, false)
        val normalText = text.invoke(original, false)
        val normalBackground = background.invoke(original, false, false)
        val hoverBackground = background.invoke(original, false, true)
        val result = constructor.newInstance(mode.invoke(original), dimensions.invoke(original),
            normalColor, normalColor, normalBackground, hoverBackground,
            normalText, normalText, shape.invoke(original), false)
        check(color.invoke(result, false) == normalColor && color.invoke(result, true) == normalColor &&
            text.invoke(result, false) == normalText && text.invoke(result, true) == normalText &&
            background.invoke(result, true, false) == normalBackground &&
            background.invoke(result, false, true) == hoverBackground && indicator.invoke(result) == false) {
            "Native sidebar style contract changed"
        }
        return result
    }

    @Composable
    fun Provider(value: ProvidedValue<Any?>, content: Any) {
        val original = value.value
        val active = StatsEntryPlacement.showSidebar && StatsHostState.visible && !StatsHostState.closing
        val matches = value.compositionLocal === local
        val neutral = remember(original, matches) {
            if (matches && original != null) unselected(original) else null
        }
        val effective = if (active && neutral != null) local provides neutral else value
        // Keep one provider/content group for both states so native lists and effects stay mounted.
        CompositionLocalProvider(effective, LocalUnmaskedSidebarStyle provides original.takeIf { matches }) {
            invokeContent.invoke(content, currentComposer, 0)
        }
    }
}
