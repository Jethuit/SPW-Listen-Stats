package com.jethuit.spw.listenstats.updates

import java.math.BigInteger
import java.net.URI
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.SocketTimeoutException
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import kotlinx.serialization.json.*

const val ReleasesUrl = "https://github.com/Jethuit/SPW-Listen-Stats/releases"

/** SemVer precedence; the optional tag prefix and build metadata do not affect ordering. */
class PluginVersion private constructor(
    private val core: List<BigInteger>,
    private val pre: List<String>
) : Comparable<PluginVersion> {
    val isPrerelease: Boolean get() = pre.isNotEmpty()
    override fun equals(other: Any?): Boolean = other is PluginVersion && core == other.core && pre == other.pre
    override fun hashCode(): Int = 31 * core.hashCode() + pre.hashCode()
    override fun compareTo(other: PluginVersion): Int {
        for (index in core.indices) core[index].compareTo(other.core[index]).let { if (it != 0) return it }
        if (pre.isEmpty() || other.pre.isEmpty()) return when {
            pre.isEmpty() && other.pre.isEmpty() -> 0
            pre.isEmpty() -> 1
            else -> -1
        }
        for (index in 0 until minOf(pre.size, other.pre.size)) {
            val left = pre[index]; val right = other.pre[index]
            val a = left.takeIf { it.all(Char::isDigit) }?.toBigInteger()
            val b = right.takeIf { it.all(Char::isDigit) }?.toBigInteger()
            val order = when {
                a != null && b != null -> a.compareTo(b)
                a != null -> -1
                b != null -> 1
                else -> left.compareTo(right)
            }
            if (order != 0) return order
        }
        return pre.size.compareTo(other.pre.size)
    }

    companion object {
        private val format = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")
        fun parse(raw: String): PluginVersion? {
            if (raw.length > 256) return null
            val match = format.matchEntire(raw.trim().removePrefix("v")) ?: return null
            val pre = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            if (pre.any { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') }) return null
            return PluginVersion((1..3).map { match.groupValues[it].toBigInteger() }, pre)
        }
    }
}

data class GitHubRelease(val tag: String, val version: PluginVersion) {
    val label: String get() = tag.removePrefix("v")
    val url: String get() = "$ReleasesUrl/tag/${URLEncoder.encode(tag, UTF_8).replace("+", "%20")}"
}

sealed class ReleaseLookup {
    data class Found(val release: GitHubRelease) : ReleaseLookup()
    data class Failed(val message: String) : ReleaseLookup()
}

object GitHubReleases {
    fun decode(status: Int, body: String): ReleaseLookup {
        if (status == 403 || status == 429) return ReleaseLookup.Failed("GitHub 暂时限制了检查请求，请稍后重试。")
        if (status == 404) return ReleaseLookup.Failed("暂未找到可比较的 GitHub 正式版本，请打开发布页核对。")
        if (status != 200) return ReleaseLookup.Failed("检查更新失败，请稍后重试（HTTP $status）。")
        return try {
            val data = Json.parseToJsonElement(body).jsonObject
            val tag = data["tag_name"]?.jsonPrimitive?.contentOrNull
            val version = tag?.let(PluginVersion::parse)
            if (tag == null || version == null || version.isPrerelease ||
                data["draft"]?.jsonPrimitive?.booleanOrNull != false ||
                data["prerelease"]?.jsonPrimitive?.booleanOrNull != false) {
                ReleaseLookup.Failed("GitHub 版本信息无法比较，请打开发布页核对。")
            } else ReleaseLookup.Found(GitHubRelease(tag, version))
        } catch (_: Exception) {
            ReleaseLookup.Failed("GitHub 返回的版本信息无效，请稍后重试。")
        }
    }

    /** Only public release metadata is requested; no credentials or listening data are sent. */
    fun fetch(): ReleaseLookup {
        // The host's trimmed runtime has java.base, but does not include java.net.http.
        val connection = URI("https://api.github.com/repos/Jethuit/SPW-Listen-Stats/releases/latest")
            .toURL().openConnection() as HttpURLConnection
        val started = System.nanoTime()
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "SPW-Listen-Stats-UpdateCheck")
        return try {
            val status = connection.responseCode
            if (status != 200) return decode(status, "")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val remaining = 10_000 - (System.nanoTime() - started) / 1_000_000
                    if (remaining <= 0) throw SocketTimeoutException()
                    connection.readTimeout = minOf(5_000L, remaining).toInt()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 1_048_576) return ReleaseLookup.Failed("GitHub 版本信息过大，请打开发布页核对。")
                    output.write(buffer, 0, count)
                }
            }
            decode(status, output.toString(UTF_8))
        } finally {
            connection.disconnect()
        }
    }
}
