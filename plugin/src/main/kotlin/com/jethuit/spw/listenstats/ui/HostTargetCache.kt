package com.jethuit.spw.listenstats.ui

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Persist descriptors only. Never deserialize executable objects or a class loader. */
open class HostTargetCache(val file: Path, val pluginVersion: String) {
    data class Identity(val realPath: String, val digest: String)
    fun identity(source: Path): Identity? {
        val real = source.toRealPath()
        if (Files.isDirectory(real)) return null
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(real).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Host cache cancelled")
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return Identity(real.toString(), digest.digest().joinToString("") { "%02x".format(it) })
    }
    fun read(identity: Identity): HostUiTargets? {
        if (!Files.exists(file)) return null
        check(Files.size(file) <= 128 * 1024) { "Host cache too large" }
        val root = Json.parseToJsonElement(Files.readString(file)).jsonObject
        if (root["format"]?.jsonPrimitive?.int != FORMAT ||
            root["plugin"]?.jsonPrimitive?.content != pluginVersion ||
            root["path"]?.jsonPrimitive?.content != identity.realPath ||
            root["sha256"]?.jsonPrimitive?.content != identity.digest) return null
        fun JsonElement.parts() = jsonArray.map { it.jsonPrimitive.content }
        fun method(value: JsonElement): HostMethod = value.parts().let { check(it.size == 3); HostMethod(it[0], it[1], it[2]) }
        fun field(value: JsonElement): HostField = value.parts().let { check(it.size == 3); HostField(it[0], it[1], it[2]) }
        fun site(value: JsonElement): HostUiSite = value.jsonArray.let { check(it.size == 2); HostUiSite(method(it[0]), it[1].jsonPrimitive.content) }
        val data = root.getValue("targets").jsonObject
        val nav = data.getValue("navigation").jsonArray.also { check(it.size == 9) }
        val caption = data.getValue("caption").jsonArray.also { check(it.size == 2) }
        val theme = data.getValue("theme").jsonArray.also { check(it.size == 2) }
        return HostUiTargets(site(data.getValue("page")), site(data.getValue("layer")),
            HostNavigationTargets(field(nav[0]), field(nav[1]), method(nav[2]), method(nav[3]),
                method(nav[4]), method(nav[5]), field(nav[6]), method(nav[7]), method(nav[8])),
            data.getValue("transitions").jsonArray.map(::method).also { check(it.size == 3) },
            HostCaptionTargets(site(caption[0]), method(caption[1])),
            HostThemeTargets(method(theme[0]), method(theme[1])), emptyList())
    }
    /** Serialize/write temporary off EDT. The caller guards only the final atomic publication. */
    open fun prepare(identity: Identity, targets: HostUiTargets): Path {
        check(targets.warnings.isEmpty())
        val caption = checkNotNull(targets.caption)
        val theme = checkNotNull(targets.theme)
        fun method(value: HostMethod) = buildJsonArray { add(value.owner); add(value.name); add(value.descriptor) }
        fun field(value: HostField) = buildJsonArray { add(value.owner); add(value.name); add(value.descriptor) }
        fun site(value: HostUiSite) = buildJsonArray { add(method(value.target)); add(value.caller) }
        val nav = targets.navigation
        val text = buildJsonObject {
            put("format", FORMAT); put("plugin", pluginVersion); put("path", identity.realPath); put("sha256", identity.digest)
            put("targets", buildJsonObject {
                put("page", site(targets.page)); put("layer", site(targets.layer))
                put("navigation", buildJsonArray {
                    add(field(nav.stack)); add(field(nav.local)); add(method(nav.navigate)); add(method(nav.replace))
                    add(method(nav.forward)); add(method(nav.back)); add(field(nav.player)); add(method(nav.expanded)); add(method(nav.dialog))
                })
                put("transitions", buildJsonArray { targets.transitions.forEach { add(method(it)) } })
                put("caption", buildJsonArray { add(site(caption.entry)); add(method(caption.control)) })
                put("theme", buildJsonArray { add(method(theme.configs)); add(method(theme.dark)) })
            })
        }.toString()
        Files.createDirectories(file.toAbsolutePath().parent)
        val temporary = Files.createTempFile(file.toAbsolutePath().parent, ".host-targets-", ".tmp")
        try { Files.writeString(temporary, text); return temporary }
        catch (error: Throwable) { Files.deleteIfExists(temporary); throw error }
    }
    fun publish(temporary: Path) {
        // No non-atomic replacement fallback: failed cache storage must not affect installed hooks.
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    companion object { const val FORMAT = 1 }
}
