package com.jethuit.spw.listenstats.ui

/**
 * Structural discovery adapted from SPW-Lyrics (MIT, GaBoron, 2026).
 * Salt Layer, native transitions and back-overlay priority are statistics-specific rules.
 * See META-INF/LICENSE-SPW-Lyrics and docs/THIRD-PARTY.md.
 */
object HostUiDiscovery {
    private const val COMPOSE = "(Landroidx/compose/runtime/Composer;I)V"
    private const val STACK = "Landroidx/navigation3/runtime/NavBackStack;"
    private const val LOCAL = "Landroidx/compose/runtime/ProvidableCompositionLocal;"
    private const val LAYER = "(Landroidx/compose/ui/Modifier;ZLkotlin/jvm/functions/Function3;Landroidx/compose/runtime/Composer;II)V"
    private const val CAPTION = "(Lkotlin/jvm/functions/Function0;Ljava/lang/String;Landroidx/compose/ui/Modifier;ZLjava/lang/String;FLkotlin/jvm/functions/Function3;Landroidx/compose/runtime/Composer;II)V"
    private const val CONFIG = "(Landroidx/compose/runtime/Composer;)L"

    fun discover(index: HostBytecodeIndex): HostUiTargets {
        val bodies = index.methods
        val content = unique(bodies.filter { b -> b.method.descriptor == COMPOSE && b.calls.any {
            it.owner.startsWith("androidx/navigation3/ui/") && it.descriptor.contains("Landroidx/compose/runtime/Composer;")
        } }, "navigation content")
        val root = unique(bodies.filter { b -> b.method.owner == content.method.owner &&
            b.method.descriptor == COMPOSE && b.isStatic && b.calls.any {
                it.owner == "androidx/compose/animation/SharedTransitionScopeKt"
            } }, "navigation root")
        val page = site(index, root.method)
        // Stay outside AppNavigation, where other tools draw their pages. Its navigation
        // lambda and the call of the containing Layer must belong to the same class.
        val layerCall = unique(bodies.filter { it.method.owner == page.caller }.flatMap { it.calls }
            .filter { it.descriptor == LAYER }, "containing Layer call")
        val layerBody = requireNotNull(index.body(layerCall)) { "Containing Layer is not indexed" }
        check(layerBody.isStatic && layerBody.calls.any { it.descriptor == LAYER } &&
            layerBody.calls.any { it.owner == "androidx/compose/ui/draw/DrawModifierKt" }) {
            "Containing Layer decoration structure changed"
        }
        val layer = HostUiSite(layerCall, page.caller)
        checkSite(index, layer)

        val stackRead = unique(content.calls.filter { it.descriptor == "()$STACK" }.distinct(), "back stack getter")
        val navOwner = stackRead.owner
        val stack = unique(index.fields.filter { it.owner == navOwner && it.descriptor == STACK }, "back stack")
        val getter = requireNotNull(index.body(stackRead)) { "Missing back stack accessor" }
        check(!getter.isStatic && getter.fields == listOf(stack)) { "Back stack accessor changed" }
        val operations = bodies.filter { it.method.owner == navOwner && !it.isStatic && it.isPublic &&
            it.method.returnType == "V" && !it.method.name.startsWith("<") && stack in it.fields }
        fun HostMethodBody.adds() = calls.any { it.owner == "androidx/navigation3/runtime/NavBackStack" && it.name == "add" }
        val back = unique(operations.filter { b -> b.method.descriptor == "()V" && !b.adds() && b.calls.any {
            it.owner == "kotlin/collections/CollectionsKt" && it.name == "removeLastOrNull"
        } }, "back action")
        val navigate = unique(operations.filter { b -> b.method.parameters.size == 1 && b.adds() && b.calls.any {
            it.owner == "kotlin/jvm/internal/Intrinsics" && it.name == "areEqual"
        } }, "navigate action")
        val replace = unique(operations.filter { it.method.descriptor == navigate.method.descriptor &&
            it.method != navigate.method && it.adds() }, "replace action")
        val forward = unique(operations.filter { it.method.descriptor == "()V" && it.adds() }, "forward action")
        check(operations.map { it.method }.toSet() == setOf(back.method, navigate.method, replace.method, forward.method)) {
            "Unrecognized back stack operations"
        }
        val local = unique(content.fields.filter { it.owner == root.method.owner && it.descriptor == LOCAL }.distinct(),
            "navigator composition local")
        val dialog = unique(back.calls.filter { it.descriptor == "(Landroidx/navigation3/runtime/NavKey;)Z" }.distinct(),
            "dialog predicate")
        check(index.body(dialog)?.isStatic == true) { "Dialog predicate must be static" }
        val playerStates = back.fields.filter { it.owner == navOwner && it.descriptor.startsWith("L") }.distinct()
            .flatMap { field -> back.calls.filter { it.owner == field.descriptor.removePrefix("L").removeSuffix(";") &&
                it.descriptor == "()Z" && index.body(it)?.isStatic == false }.distinct().map { field to it } }
        val (player, expanded) = unique(playerStates, "player expansion state used by back")
        val navigation = HostNavigationTargets(stack, local, navigate.method, replace.method, forward.method, back.method,
            player, expanded, dialog)

        // Restrict to the root's desktop spring transforms, excluding iOS container slides.
        val transforms = bodies.filter { it.method.owner == root.method.owner && it.isStatic &&
            it.method.returnType == "Landroidx/compose/animation/ContentTransform;" && it.calls.any {
                it.owner == "androidx/compose/animation/core/AnimationSpecKt" && it.name.startsWith("spring")
            } }
        fun transform(parameters: List<String>, incomingSlide: Boolean): HostMethod = unique(transforms.filter { b ->
            b.method.parameters == parameters && b.calls.any { it.owner == "androidx/compose/animation/ContentTransform" && it.name == "<init>" } &&
                b.calls.any { it.owner == "androidx/compose/animation/EnterExitTransitionKt" && it.name.startsWith("fadeOut") } &&
                b.calls.any { it.owner == "androidx/compose/animation/EnterExitTransitionKt" &&
                    it.name == (if (incomingSlide) "slideIn" else "slideOut") } &&
                (incomingSlide || b.calls.any { it.owner == "androidx/compose/animation/EnterExitTransitionKt" && it.name.startsWith("fadeIn") })
        }, "desktop ${if (incomingSlide) "forward" else "back"} transform $parameters").method
        val scope = listOf("androidx.compose.animation.AnimatedContentTransitionScope")
        val transitions = listOf(transform(scope, true), transform(scope, false), transform(scope + "int", false))
        check(transforms.map { it.method }.toSet() == transitions.toSet()) { "Desktop transition structure changed" }

        // A missing optional caption must leave the configuration entry usable.
        val warnings = mutableListOf<String>()
        fun <T> optional(role: String, discover: () -> T): T? = try { discover() } catch (failure: IllegalStateException) {
            warnings += "$role: ${failure.message}"; null
        }
        val caption = optional("titlebar") { caption(index) }
        val theme = caption?.let { optional("theme") { theme(index, it.control.owner) } }
        return HostUiTargets(page, layer, navigation, transitions, caption, theme, warnings)
    }

    private fun caption(index: HostBytecodeIndex): HostCaptionTargets {
        val control = unique(index.methods.filter { b -> b.method.descriptor == CAPTION && b.isStatic && b.calls.any {
            it.descriptor == CAPTION.replace("String;F", "String;")
        } }, "caption control").method
        val quick = unique(index.methods.filter { it.method.descriptor == COMPOSE && control in it.calls }, "caption entry").method
        val quickSite = site(index, quick)
        val row = unique(index.methods.filter { it.method.owner == quickSite.caller && quick in it.calls }, "caption row")
        check(row.calls.count { it.owner == "androidx/compose/foundation/layout/RowKt" && it.name == "rowMeasurePolicy" } == 1) {
            "Caption row structure changed"
        }
        val entries = row.calls.filter { it.descriptor == COMPOSE && index.body(it)?.isStatic == true &&
            it.owner != "androidx/compose/runtime/ComposerKt" }
        val position = entries.indexOf(quick)
        check(position > 0) { "Caption predecessor missing" }
        // Hook the last zero-argument composable before Quick. This is the debug entry
        // in the inspected hosts, whose release build may deliberately render nothing.
        val predecessor = entries[position - 1]
        return HostCaptionTargets(site(index, predecessor).also { check(it.caller == quickSite.caller) }, control)
    }

    private fun theme(index: HostBytecodeIndex, captionOwner: String): HostThemeTargets {
        fun objectType(method: HostMethod) = method.returnType.removePrefix("L").removeSuffix(";")
        fun palette(method: HostMethod): Boolean {
            val body = index.body(method) ?: return false
            if (!body.isStatic) return false
            val owner = objectType(method)
            val colors = index.methods.count { b -> b.method.owner == owner && b.method.descriptor == "()J" &&
                b.calls.any { it.owner == "androidx/compose/ui/graphics/Color" && it.descriptor == "()J" } }
            return colors >= 3 && body.calls.filter { it.descriptor == "()L$owner;" }.distinct().size == 2
        }
        val access = unique(index.methods.filter { it.method.owner == captionOwner }.flatMap { it.calls }.distinct()
            .filter { it.descriptor.startsWith(CONFIG) && palette(it) }, "caption palette")
        val body = index.body(access)!!
        val configs = body.calls.distinct().filter { method -> method.descriptor.startsWith(CONFIG) &&
            index.body(method)?.let { b -> b.isStatic && b.calls.any {
                it.owner == "androidx/compose/runtime/Composer" && it.name == "consume"
            } } == true }
        return unique(configs.flatMap { config -> body.calls.distinct().filter { method ->
            method.owner == objectType(config) && method.descriptor == "()Z" && index.body(method)?.let { b ->
                !b.isStatic && b.fields.any { it.descriptor == "Landroidx/compose/runtime/MutableState;" } &&
                    b.calls.any { it.owner == "java/lang/Boolean" && it.name == "booleanValue" }
            } == true
        }.map { HostThemeTargets(config, it) } }, "reactive dark theme")
    }

    private fun site(index: HostBytecodeIndex, target: HostMethod): HostUiSite {
        val caller = unique(index.methods.filter { it.method.owner != target.owner && target in it.calls &&
            it.method.descriptor.contains("Landroidx/compose/runtime/Composer;") }.map { it.method.owner }.distinct(), "UI caller of $target")
        return HostUiSite(target, caller).also { checkSite(index, it) }
    }

    private fun checkSite(index: HostBytecodeIndex, site: HostUiSite) {
        val count = index.methods.filter { it.method.owner == site.caller }.sumOf { b -> b.calls.count { it == site.target } }
        check(count == 1) { "UI call site $site is not unique: $count" }
    }

    private fun <T> unique(matches: List<T>, role: String): T {
        check(matches.size == 1) { "$role: expected one match, found ${matches.size}" }
        return matches.single()
    }
}
