package com.jethuit.spw.listenstats.data

import com.jethuit.spw.listenstats.PluginLog
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.filechooser.FileNameExtensionFilter
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.atomic.AtomicLong

object BackupService {
    private val operationSequence = AtomicLong(0)
    private val dialogLock = Any()
    @Volatile private var activeChooser: JFileChooser? = null
    private data class ImportSignature(
        val path: Path,
        val modified: java.nio.file.attribute.FileTime,
        val size: Long,
        val fileKey: Any?
    )
    private data class CachedImport(val path: Path, val modified: java.nio.file.attribute.FileTime,
        val size: Long, val fileKey: Any?, val bundle: BackupBundle)
    private var cache: CachedImport? = null
    private var observedSignature: ImportSignature? = null
    private var inputRevision = 0L

    /** Cheap change token for the imported backup; parsing remains lazy in imported(). */
    @Synchronized fun revision(): Long {
        val current = importSignature()
        if (current != observedSignature) {
            observedSignature = current
            inputRevision++
        }
        return inputRevision
    }

    /** Tracks external deletion/replacement of files referenced by imported events. */
    @Synchronized fun visibilityFingerprint(): Long = runCatching {
        val bundle = imported()
        TrackVisibility.fingerprint(bundle.events.map { it.track } + bundle.legacy.tracks.values)
    }.getOrDefault(0L)

    private fun importSignature(): ImportSignature? {
        val path = importedFile().toAbsolutePath().normalize()
        if (!Files.isRegularFile(path)) return null
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
        return ImportSignature(path, attributes.lastModifiedTime(), attributes.size(), attributes.fileKey())
    }

    private fun importedFile() = DataFiles.directory().resolve("imported.json")
    @Synchronized private fun imported(): BackupBundle {
        val path = importedFile().toAbsolutePath().normalize()
        if (!Files.isRegularFile(path)) { cache = null; return BackupBundle(emptyList()) }
        val before = Files.readAttributes(path, BasicFileAttributes::class.java)
        cache?.takeIf { it.path == path && it.modified == before.lastModifiedTime() &&
            it.size == before.size() && it.fileKey == before.fileKey() }?.let { return it.bundle }
        val bundle = BackupCodec.read(path)
        val after = Files.readAttributes(path, BasicFileAttributes::class.java)
        require(before.lastModifiedTime() == after.lastModifiedTime() && before.size() == after.size() &&
            before.fileKey() == after.fileKey()) { "备份在读取期间发生变化，请重试" }
        cache = CachedImport(path, after.lastModifiedTime(), after.size(), after.fileKey(), bundle)
        return bundle
    }

    @Synchronized fun importFile(file: Path): BackupBundle {
        val incoming = BackupCodec.read(file)
        val existing = imported()
        val merged = BackupCodec.union(existing.copy(events = LiveStore.correctImportedEvents(existing.events)),
            incoming.copy(events = LiveStore.correctImportedEvents(incoming.events)))
        val text = BackupCodec.encode(merged)
        BackupCodec.decode(text) // Validate the result before replacing any durable state.
        if (Files.isRegularFile(importedFile())) {
            DataFiles.writeAtomic(importedFile().resolveSibling("imported.previous.json"), Files.readString(importedFile()))
        }
        DataFiles.writeAtomic(importedFile(), text)
        cache = null
        observedSignature = null
        inputRevision++
        return incoming
    }

    @Synchronized fun exportFile(file: Path, data: StatsData) {
        val existing = imported()
        val bundle = BackupCodec.union(BackupBundle(LiveStore.correctImportedEvents(data.events)),
            existing.copy(events = LiveStore.correctImportedEvents(existing.events)))
        val text = BackupCodec.encode(bundle)
        BackupCodec.decode(text)
        DataFiles.writeAtomic(file, text)
    }

    /**
     * Swing's modal chooser must not run in Compose's AWT event loop.  A
     * dedicated worker also avoids the Compose 1.7 coroutine continuation
     * failure that occurs when showOpenDialog/showSaveDialog blocks a click
     * coroutine.  The worker is daemonized so a cancelled plugin cannot keep
     * the host process alive.
     */
    private suspend fun <T> onDialogThread(action: String, block: () -> T): T = suspendCancellableCoroutine { continuation ->
        PluginLog.info("backup dialog start action=$action callerThread=${Thread.currentThread().name}")
        continuation.invokeOnCancellation {
            val chooser = synchronized(dialogLock) { activeChooser }
            if (chooser != null) {
                PluginLog.info("backup dialog cancelled action=$action")
                runCatching { chooser.cancelSelection() }
            }
        }
        thread(start = true, isDaemon = true, name = "spw-listen-stats-file-dialog") {
            PluginLog.info("backup dialog running action=$action thread=${Thread.currentThread().name}")
            runCatching(block).fold(
                {
                    PluginLog.info("backup dialog finish action=$action result=${if (it == null) "cancel" else "selected"}")
                    if (continuation.isActive) continuation.resume(it)
                },
                {
                    PluginLog.error("backup dialog failed action=$action", it)
                    if (continuation.isActive) continuation.resumeWithException(it)
                }
            )
        }
    }

    private fun chooseExportFileBlocking(): Path? {
        val chooser = JFileChooser().apply {
            dialogTitle = "导出听歌统计备份"
            fileFilter = FileNameExtensionFilter("JSON 备份 (*.json)", "json")
            selectedFile = java.io.File("spw-listen-stats-${LocalDate.now()}.json")
        }
        synchronized(dialogLock) { activeChooser = chooser }
        return try {
            if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) null
            else chooser.selectedFile.toPath()
        } finally { synchronized(dialogLock) { if (activeChooser === chooser) activeChooser = null } }
    }

    private fun chooseImportFileBlocking(): Path? {
        val chooser = JFileChooser().apply {
            dialogTitle = "导入听歌统计备份"
            fileFilter = FileNameExtensionFilter("JSON 备份 (*.json)", "json")
        }
        synchronized(dialogLock) { activeChooser = chooser }
        return try {
            if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) null
            else chooser.selectedFile.toPath()
        } finally { synchronized(dialogLock) { if (activeChooser === chooser) activeChooser = null } }
    }

    private fun confirmOverwriteBlocking(file: Path): Boolean {
        val exists = Files.exists(file)
        val accepted = !exists || JOptionPane.showConfirmDialog(
            null, "替换现有备份文件？", "导出备份", JOptionPane.YES_NO_OPTION
        ) == JOptionPane.YES_OPTION
        PluginLog.info("backup overwrite decision accepted=$accepted exists=$exists path=${file.toAbsolutePath()}")
        return accepted
    }

    suspend fun export(data: StatsData) {
        val operationId = operationSequence.incrementAndGet()
        PluginLog.info("backup export requested operation=$operationId thread=${Thread.currentThread().name} events=${data.events.size}")
        val file = onDialogThread("export-select#$operationId") { chooseExportFileBlocking() } ?: return
        PluginLog.info("backup export selected operation=$operationId path=${file.toAbsolutePath()}")
        if (!onDialogThread("export-overwrite#$operationId") { confirmOverwriteBlocking(file) }) return
        try {
            withContext(Dispatchers.IO) { exportFile(file, data) }
            PluginLog.info("backup export completed operation=$operationId path=${file.toAbsolutePath()} bytes=${runCatching { Files.size(file) }.getOrDefault(-1L)}")
            WorkshopApi.ui.toast("已导出听歌统计备份", WorkshopApi.Ui.ToastType.Success)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) { report("导出", failure) }
    }

    suspend fun importBackup(): Boolean {
        val operationId = operationSequence.incrementAndGet()
        PluginLog.info("backup import requested operation=$operationId thread=${Thread.currentThread().name}")
        val path = onDialogThread("import-select#$operationId") { chooseImportFileBlocking() } ?: return false
        PluginLog.info("backup import selected operation=$operationId path=${path.toAbsolutePath()} bytes=${runCatching { Files.size(path) }.getOrDefault(-1L)}")
        return try {
            val incoming = withContext(Dispatchers.IO) { importFile(path) }
            if (incoming.legacy.hours.isNotEmpty() || incoming.legacy.tracks.isNotEmpty()) {
                onDialogThread("legacy-import-warning#$operationId") {
                    JOptionPane.showMessageDialog(null,
                        "已合并备份。旧版汇总没有会话 ID，按小时和次数取较大值保留，可能少计重叠日期中的不同收听。",
                        "旧版备份已兼容导入", JOptionPane.INFORMATION_MESSAGE)
                }
            }
            PluginLog.info("backup import completed operation=$operationId path=${path.toAbsolutePath()} events=${incoming.events.size} legacyHours=${incoming.legacy.hours.size} legacyTracks=${incoming.legacy.tracks.size}")
            WorkshopApi.ui.toast("已合并备份，重复会话不会重复累计", WorkshopApi.Ui.ToastType.Success)
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) { report("导入", failure); false }
    }

    fun merge(host: StatsData): StatsData = try {
        val bundle = imported()
        val visibleTracks = bundle.legacy.tracks.filterValues(TrackVisibility::isVisible)
        val visible = bundle.copy(
            events = bundle.events.filter { TrackVisibility.isVisible(it.track) },
            legacy = bundle.legacy.copy(
                tracks = visibleTracks,
                counts = bundle.legacy.counts.filterKeys { it in visibleTracks }
            )
        )
        BackupCodec.apply(host, visible)
    } catch (failure: Exception) {
        PluginLog.error("merging imported backup failed", failure)
        host.copy(error = listOfNotNull(host.error, "备份读取失败，原文件已保留：${failure.message}").joinToString("；"))
    }

    fun mergeWithLive(host: StatsData): StatsData {
        val bundle = try { imported() } catch (failure: Exception) {
            PluginLog.error("reading imported backup failed", failure)
            return LiveStore.merge(host).copy(error = listOfNotNull(host.error,
                "备份读取失败，原文件已保留：${failure.message}").joinToString("；"))
        }
        val visibleLegacyTracks = bundle.legacy.tracks.filterValues(TrackVisibility::isVisible)
        val visibleLegacy = bundle.legacy.copy(
            tracks = visibleLegacyTracks,
            counts = bundle.legacy.counts.filterKeys { it in visibleLegacyTracks }
        )
        return BackupCodec.applyLegacy(
            LiveStore.merge(host, bundle.events, TrackVisibility::isVisible), visibleLegacy)
    }

    private suspend fun report(action: String, failure: Throwable) {
        PluginLog.error("backup $action failed", failure)
        // Error reporting is best effort; the original failure remains in the log.
        runCatching {
            onDialogThread("error-$action") {
                    JOptionPane.showMessageDialog(null, "$action 失败：${failure.message}", "$action 失败",
                        JOptionPane.ERROR_MESSAGE)
            }
        }.onFailure { PluginLog.error("showing backup $action error failed", it) }
    }
}
