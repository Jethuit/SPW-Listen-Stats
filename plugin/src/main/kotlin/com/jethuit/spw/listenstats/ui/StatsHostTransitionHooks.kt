@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package com.jethuit.spw.listenstats.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.snap
import com.xuncorp.spw.workshop.api.hook.MethodHook
import com.xuncorp.spw.workshop.api.hook.MethodHookParam

/** Native incoming scenes cover statistics; their obsolete underlying scene must not reappear. */
object StatsHostTransitionHooks {
    val TARGET_CLASS: String get() = HostUiRuntime.require().targets.page.target.className

    fun register(addHook: (String, List<String>, MethodHook) -> AutoCloseable): AutoCloseable {
        val handles = mutableListOf<AutoCloseable>()
        try {
            for (target in HostUiRuntime.require().targets.transitions) {
                handles += addHook(target.name, target.parameters, object : MethodHook(50) {
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
