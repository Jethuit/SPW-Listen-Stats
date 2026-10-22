package com.jethuit.spw.listenstats.ui

/** Internal call sites change with SPW's obfuscation, even in patch releases. */
data class HostUiTargets(
    val pageCaller: String,
    val titlebarCaller: String,
    val debugCaption: String,
    val quickCaption: String,
    val steamCaption: String
) {
    companion object {
        fun forVersion(version: String): HostUiTargets = when (version) {
            "1.19.0" -> HostUiTargets(
                "androidx.compose.ui.\u06c4", "androidx.compose.ui.\u0205",
                "androidx.compose.ui.\u04f4", "androidx.compose.ui.\u04cc", "androidx.compose.ui.\u04d6"
            )
            "1.19.1" -> HostUiTargets(
                "androidx.compose.ui.\u0443", "androidx.compose.ui.\u09f1",
                "androidx.compose.ui.\u063a", "androidx.compose.ui.\u01b0", "androidx.compose.ui.\u05e2"
            )
            else -> error("Unsupported SPW UI hook version: $version")
        }
    }
}
