package com.jethuit.spw.listenstats.updates

import com.jethuit.spw.listenstats.PluginLog
import com.jethuit.spw.listenstats.data.DataFiles
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*

data class UpdateNotice(
    val checking: Boolean = false,
    val message: String = "尚未检查更新",
    val hasUpdate: Boolean = false,
    val error: Boolean = false,
    val checkedAt: Long = 0,
    val releaseUrl: String = ReleasesUrl,
    val releaseVersion: String? = null
)

/** Serialized checks shared by the automatic monitor and the About dialog. */
class UpdateChecker(
    private val currentVersion: String,
    private val stateFile: Path,
    private val enabled: () -> Boolean,
    private val fetch: () -> ReleaseLookup = GitHubReleases::fetch,
    private val clock: () -> Long = System::currentTimeMillis,
    private val changed: (UpdateNotice) -> Unit = {},
    private val notify: (GitHubRelease) -> Unit = {}
) {
    private val mutex = Mutex()
    private var lastAttempt = 0L
    private var lastNotified: String? = null
    private var cachedTag: String? = null
    var notice = UpdateNotice()
        private set

    init {
        runCatching {
            if (Files.isRegularFile(stateFile)) {
                val data = Json.parseToJsonElement(Files.readString(stateFile)).jsonObject
                lastAttempt = data["lastAttempt"]?.jsonPrimitive?.longOrNull?.coerceAtLeast(0) ?: 0L
                lastNotified = data["lastNotified"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { PluginVersion.parse(it) != null }
                val tag = data["lastRelease"]?.jsonPrimitive?.contentOrNull
                val version = tag?.let(PluginVersion::parse)?.takeUnless { it.isPrerelease }
                val checkedAt = data["checkedAt"]?.jsonPrimitive?.longOrNull ?: 0L
                if (tag != null && version != null && checkedAt > 0) {
                    cachedTag = tag
                    notice = describe(GitHubRelease(tag, version), checkedAt)
                }
            }
        }.onFailure { PluginLog.info("update-check cache unavailable; using empty state") }
        changed(notice)
    }

    suspend fun check(manual: Boolean) {
        if (!mutex.tryLock()) return
        val previous = notice
        try {
            val now = clock()
            if (!manual && (!enabled() || !due(now, lastAttempt))) return
            coroutineContext.ensureActive()
            publish(previous.copy(checking = true))
            lastAttempt = now
            persist()
            val result = try {
                runInterruptible { fetch() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ReleaseLookup.Failed("检查更新失败，暂时无法连接 GitHub，请稍后重试。")
            }
            coroutineContext.ensureActive()
            when (result) {
                is ReleaseLookup.Failed -> {
                    if (manual) publish(previous.copy(message = result.message, error = true))
                    else {
                        publish(previous)
                        PluginLog.info("automatic update check failed: ${result.message}")
                    }
                }
                is ReleaseLookup.Found -> {
                    val next = describe(result.release, clock())
                    if (next.error && !manual) { publish(previous); return }
                    cachedTag = result.release.tag
                    publish(next)
                    if (!manual && next.hasUpdate && enabled() &&
                        lastNotified?.let(PluginVersion::parse) != result.release.version) {
                        lastNotified = result.release.tag
                        persist()
                        notify(result.release)
                    } else persist()
                }
            }
        } finally {
            if (notice.checking) publish(notice.copy(checking = false))
            mutex.unlock()
        }
    }

    private fun describe(release: GitHubRelease, checkedAt: Long): UpdateNotice {
        val current = PluginVersion.parse(currentVersion)
            ?: return UpdateNotice(message = "当前插件版本无法比较，请打开发布页核对。", error = true)
        val newer = release.version > current
        val message = if (newer) "发现新正式版本 ${release.label}"
            else "未发现更高的正式版本"
        return UpdateNotice(message = message, hasUpdate = newer, checkedAt = checkedAt,
            releaseUrl = release.url, releaseVersion = release.label)
    }

    private fun publish(value: UpdateNotice) { notice = value; changed(value) }

    private fun persist() {
        runCatching {
            DataFiles.writeAtomic(stateFile, buildJsonObject {
                put("lastAttempt", lastAttempt)
                put("checkedAt", notice.checkedAt)
                cachedTag?.let { put("lastRelease", it) }
                lastNotified?.let { put("lastNotified", it) }
            }.toString())
        }.onFailure { PluginLog.info("saving update-check cache failed") }
    }

    companion object {
        const val IntervalMillis = 24 * 60 * 60 * 1_000L
        fun due(now: Long, lastAttempt: Long): Boolean =
            lastAttempt == 0L || now < lastAttempt || now - lastAttempt >= IntervalMillis
    }
}
