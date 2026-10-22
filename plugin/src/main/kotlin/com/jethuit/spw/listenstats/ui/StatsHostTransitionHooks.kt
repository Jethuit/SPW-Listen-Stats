@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package com.jethuit.spw.listenstats.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.snap
import com.xuncorp.spw.workshop.api.hook.MethodHook
import com.xuncorp.spw.workshop.api.hook.MethodHookParam

/** Native incoming scenes cover statistics; their obsolete underlying scene must not reappear. */
object StatsHostTransitionHooks {
    const val TARGET_CLASS = "com.xuncorp.voxzen.ui.navigation.AppNavigationKt"

    fun register(addHook: (String, List<String>, MethodHook) -> AutoCloseable): AutoCloseable {
        val handles = mutableListOf<AutoCloseable>()
        try {
            for (prefix in listOf("voxzenTransitionSpec", "voxzenPopTransitionSpec", "voxzenPredictivePopTransitionSpec")) {
                val parameters = listOf("androidx.compose.animation.AnimatedContentTransitionScope") +
                    if (prefix == "voxzenPredictivePopTransitionSpec") listOf("int") else emptyList()
                handles += addHook("${prefix}\$lambda\$0", parameters, object : MethodHook(50) {
                    override fun after(call: MethodHookParam) {
                        if (!StatsHostState.navigating || !StatsHostState.nativeNavigation || call.throwable != null) return
                        val original = call.result as? ContentTransform ?: return
                        call.setResult(ContentTransform(original.targetContentEnter, fadeOut(snap()),
                            original.targetContentZIndex, original.sizeTransform))
                    }
                })
            }
        } catch (failure: Throwable) {
            handles.asReversed().forEach { runCatching { it.close() } }
            throw failure
        }
        return AutoCloseable { handles.asReversed().forEach { runCatching { it.close() } } }
    }
}
