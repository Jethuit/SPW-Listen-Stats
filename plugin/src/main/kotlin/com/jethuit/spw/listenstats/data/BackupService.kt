package com.jethuit.spw.listenstats.data

import com.jethuit.spw.listenstats.PluginLog
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

object BackupService {
    private val operationSequence = AtomicLong(0)
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

    // The active imported.json points to one of two bounded undo slots. Only its
    // atomic replacement commits an import; an interrupted staging write leaves the old slot usable.
    private const val PREVIOUS = "imported.previous.json"
    private const val ALTERNATE = "imported.previous-alt.json"
    private const val UNDO_KEY = "localUndoSlot"

    private fun undoSlot(current: Path): String? {
        if (!Files.isRegularFile(current)) return null
        require(Files.size(current) <= BackupCodec.MAX_BYTES) { "导入文件过大" }
        val root = Json.parseToJsonElement(Files.readString(current)).jsonObject
        val name = root[UNDO_KEY]?.jsonPrimitive?.content ?: PREVIOUS // previous versions
        return name.takeIf { (it == PREVIOUS || it == ALTERNATE) && Files.isRegularFile(current.resolveSibling(it)) }
    }

    private fun withUndoSlot(text: String, slot: String): String {
        val root = Json.parseToJsonElement(text).jsonObject
        return JsonObject(root + (UNDO_KEY to JsonPrimitive(slot))).toString().also { BackupCodec.decode(it) }
    }

    @Synchronized fun undoRevision(): Long? =
        if (undoSlot(importedFile()) == null) null else revision()

    @Synchronized fun undoImport(expectedRevision: Long) {
        check(revision() == expectedRevision) { "导入记录已变化，请重新选择撤销" }
        val current = importedFile()
        val previous = current.resolveSibling(checkNotNull(undoSlot(current)) { "没有可撤销的导入" })
        val restored = BackupCodec.read(previous)
        // An empty checkpoint represents the state before a first import.
        if (restored.events.isEmpty() && restored.legacy.hours.isEmpty() && restored.legacy.tracks.isEmpty()) {
            Files.delete(current)
        } else {
            DataFiles.writeAtomic(current, withUndoSlot(Files.readString(previous), "none"))
        }
        cache = null
        revision()
    }

    @Synchronized fun importFile(file: Path): BackupBundle {
        val incoming = BackupCodec.read(file)
        val existing = imported()
        val merged = BackupCodec.union(existing.copy(events = LiveStore.correctImportedEvents(existing.events)),
            incoming.copy(events = LiveStore.correctImportedEvents(incoming.events)))
        val text = BackupCodec.encode(merged)
        val current = importedFile()
        val slot = if (undoSlot(current) == ALTERNATE) PREVIOUS else ALTERNATE
        val committed = withUndoSlot(text, slot)
        // Preserve the exact prior checkpoint. Restoration consumes any embedded undo pointer.
        val checkpoint = if (Files.isRegularFile(current)) Files.readString(current) else BackupCodec.encode(existing)
        BackupCodec.decode(checkpoint)
        DataFiles.writeAtomic(current.resolveSibling(slot), checkpoint)
        DataFiles.writeAtomic(current, committed)
        cache = null
        observedSignature = null
        inputRevision++
        return incoming
    }

    @Synchronized fun exportFile(file: Path, data: StatsData) {
        check(data.importedRevision == null || data.importedRevision == revision()) { "导入记录已更新，请刷新统计后再导出" }
        val existing = imported()
        val bundle = BackupCodec.union(BackupBundle(LiveStore.correctImportedEvents(data.events)),
            existing.copy(events = LiveStore.correctImportedEvents(existing.events)))
        val text = BackupCodec.encode(bundle)
        BackupCodec.decode(text)
        DataFiles.writeAtomic(file, text)
    }

    suspend fun undoBackup(expectedRevision: Long): Boolean {
        if (!BackupDialogs.confirm("撤销上次导入", "恢复到上次导入前的统计？本机新听的记录会保留。")) return false
        return try {
            withContext(Dispatchers.IO) {
                check(StatsStore.hasLibraryAccess()) { "请先授权曲库读取权限" }
                undoImport(expectedRevision)
            }
            WorkshopApi.ui.toast("已撤销上次导入", WorkshopApi.Ui.ToastType.Success)
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { report("撤销导入", failure); false }
    }

    suspend fun export(data: StatsData) {
        val operationId = operationSequence.incrementAndGet()
        PluginLog.info("backup export requested operation=$operationId thread=${Thread.currentThread().name} events=${data.events.size}")
        if (!StatsStore.hasLibraryAccess() || data.libraryAccessDenied) {
            WorkshopApi.ui.toast("请先授权曲库读取权限", WorkshopApi.Ui.ToastType.Warning)
            return
        }
        val file = BackupDialogs.chooseFile(export = true) ?: return
        PluginLog.info("backup export selected operation=$operationId path=${file.toAbsolutePath()}")
        if (withContext(Dispatchers.IO) { Files.exists(file) }) {
            val overwrite = BackupDialogs.confirm("导出备份", "替换现有备份文件？")
            PluginLog.info("backup overwrite decision operation=$operationId accepted=$overwrite")
            if (!overwrite) return
        }
        try {
            withContext(Dispatchers.IO) {
                check(StatsStore.hasLibraryAccess()) { "请先授权曲库读取权限" }
                val latest = if (data.importedRevision != null && data.importedRevision != revision()) {
                    StatsStore.load().also { check(it.error == null) { it.error.orEmpty() } }
                } else data
                exportFile(file, latest)
            }
            PluginLog.info("backup export completed operation=$operationId path=${file.toAbsolutePath()} bytes=${runCatching { Files.size(file) }.getOrDefault(-1L)}")
            WorkshopApi.ui.toast("已导出听歌统计备份", WorkshopApi.Ui.ToastType.Success)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) { report("导出", failure) }
    }

    suspend fun importBackup(): Boolean {
        val operationId = operationSequence.incrementAndGet()
        PluginLog.info("backup import requested operation=$operationId thread=${Thread.currentThread().name}")
        val path = BackupDialogs.chooseFile(export = false) ?: return false
        PluginLog.info("backup import selected operation=$operationId path=${path.toAbsolutePath()} bytes=${runCatching { Files.size(path) }.getOrDefault(-1L)}")
        return try {
            val incoming = withContext(Dispatchers.IO) { importFile(path) }
            if (incoming.legacy.hours.isNotEmpty() || incoming.legacy.tracks.isNotEmpty()) {
                BackupDialogs.message("旧版备份已兼容导入",
                    "已合并备份。旧版汇总没有会话 ID，按小时和次数取较大值保留，可能少计重叠日期中的不同收听。")
            }
            PluginLog.info("backup import completed operation=$operationId path=${path.toAbsolutePath()} events=${incoming.events.size} legacyHours=${incoming.legacy.hours.size} legacyTracks=${incoming.legacy.tracks.size}")
            WorkshopApi.ui.toast("已合并备份，重复会话不会重复累计", WorkshopApi.Ui.ToastType.Success)
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) { report("导入", failure); false }
    }

    fun mergeWithLive(host: StatsData, visibility: TrackVisibility.Snapshot = TrackVisibility.Snapshot()): StatsData {
        val bundle = try { imported() } catch (failure: Exception) {
            PluginLog.error("reading imported backup failed", failure)
            return LiveStore.merge(host, trackVisible = visibility::isVisible).copy(error = listOfNotNull(host.error,
                "备份读取失败，原文件已保留：${failure.message}").joinToString("；"))
        }
        val visibleLegacyTracks = bundle.legacy.tracks.filterValues(visibility::isVisible)
        val visibleLegacy = bundle.legacy.copy(
            tracks = visibleLegacyTracks,
            counts = bundle.legacy.counts.filterKeys { it in visibleLegacyTracks }
        )
        return BackupCodec.applyLegacy(
            LiveStore.merge(host, bundle.events, visibility::isVisible), visibleLegacy)
    }

    private suspend fun report(action: String, failure: Throwable) {
        PluginLog.error("backup $action failed", failure)
        // Error reporting is best effort; the original failure remains in the log.
        try { BackupDialogs.message("$action 失败", "$action 失败：${failure.message}", error = true) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) { PluginLog.error("showing backup $action error failed", problem) }
    }
}
