package com.jethuit.spw.listenstats.ui

/** Fresh structural matches from the current host, never a version lookup or cached mapping. */
data class HostUiSite(val target: HostMethod, val caller: String) {
    val callerClass: String get() = caller.replace('/', '.')
}

data class HostNavigationTargets(val stack: HostField, val local: HostField,
    val navigate: HostMethod, val replace: HostMethod, val forward: HostMethod, val back: HostMethod,
    val player: HostField, val expanded: HostMethod, val dialog: HostMethod)
data class HostCaptionTargets(val entry: HostUiSite, val control: HostMethod)
data class HostThemeTargets(val configs: HostMethod, val dark: HostMethod)
data class HostUiTargets(val page: HostUiSite, val layer: HostUiSite,
    val navigation: HostNavigationTargets, val transitions: List<HostMethod>,
    val caption: HostCaptionTargets?, val theme: HostThemeTargets?, val warnings: List<String>)
