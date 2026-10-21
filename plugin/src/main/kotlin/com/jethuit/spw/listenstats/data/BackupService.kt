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
import kotlinx.coroutines.withContext

object BackupService {
    private data class CachedImport(val path: Path, val modified: java.nio.file.attribute.FileTime,
        val size: Long, val fileKey: Any?, val bundle: BackupBundle)
    private var cache: CachedImport? = null
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
        val merged = BackupCodec.union(imported(), incoming)
        val text = BackupCodec.encode(merged)
        BackupCodec.decode(text) // Validate the result before replacing any durable state.
        if (Files.isRegularFile(importedFile())) {
            DataFiles.writeAtomic(importedFile().resolveSibling("imported.previous.json"), Files.readString(importedFile()))
        }
        DataFiles.writeAtomic(importedFile(), text)
        cache = null
        return incoming
    }

    @Synchronized fun exportFile(file: Path, data: StatsData) {
        val bundle = BackupCodec.union(imported(), BackupBundle(data.events))
        val text = BackupCodec.encode(bundle)
        BackupCodec.decode(text)
        DataFiles.writeAtomic(file, text)
    }

    suspend fun export(data: StatsData) {
        val chooser = JFileChooser().apply {
            dialogTitle = "导出听歌统计备份"
            fileFilter = FileNameExtensionFilter("JSON 备份 (*.json)", "json")
            selectedFile = java.io.File("spw-listen-stats-${LocalDate.now()}.json")
        }
        if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile.toPath()
        if (Files.exists(file) && JOptionPane.showConfirmDialog(null, "替换现有备份文件？", "导出备份",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return
        try {
            withContext(Dispatchers.IO) { exportFile(file, data) }
            WorkshopApi.ui.toast("已导出听歌统计备份", WorkshopApi.Ui.ToastType.Success)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { report("导出", failure) }
    }

    suspend fun importBackup(): Boolean {
        val chooser = JFileChooser().apply {
            dialogTitle = "导入听歌统计备份"
            fileFilter = FileNameExtensionFilter("JSON 备份 (*.json)", "json")
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return false
        return try {
            val path = chooser.selectedFile.toPath()
            val incoming = withContext(Dispatchers.IO) { importFile(path) }
            if (incoming.legacy.hours.isNotEmpty() || incoming.legacy.tracks.isNotEmpty()) {
                JOptionPane.showMessageDialog(null,
                    "已合并备份。旧版汇总没有会话 ID，按小时和次数取较大值保留，可能少计重叠日期中的不同收听。",
                    "旧版备份已兼容导入", JOptionPane.INFORMATION_MESSAGE)
            }
            WorkshopApi.ui.toast("已合并备份，重复会话不会重复累计", WorkshopApi.Ui.ToastType.Success)
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { report("导入", failure); false }
    }

    fun merge(host: StatsData): StatsData = try {
        BackupCodec.apply(host, imported())
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
        return BackupCodec.applyLegacy(LiveStore.merge(host, bundle.events), bundle.legacy)
    }

    private fun report(action: String, failure: Exception) {
        PluginLog.error("backup $action failed", failure)
        JOptionPane.showMessageDialog(null, "$action 失败：${failure.message}", "$action 失败", JOptionPane.ERROR_MESSAGE)
    }
}
