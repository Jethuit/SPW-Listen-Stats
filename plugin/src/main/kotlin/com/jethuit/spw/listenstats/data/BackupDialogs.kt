package com.jethuit.spw.listenstats.data

import com.jethuit.spw.listenstats.PluginLog
import kotlinx.coroutines.suspendCancellableCoroutine
import java.awt.EventQueue
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object BackupDialogs {
    // Keep modal show off the host Compose event loop. Each continuation owns its own window.
    private suspend fun <T> show(title: String, create: () -> Pair<JDialog, () -> T>): T =
        suspendCancellableCoroutine { continuation ->
            val window = AtomicReference<JDialog?>()
            continuation.invokeOnCancellation {
                EventQueue.invokeLater { window.get()?.dispose() }
            }
            thread(isDaemon = true, name = "ListenStats-BackupDialog") {
                if (!continuation.isActive) return@thread
                try {
                    val (dialog, result) = create()
                    window.set(dialog)
                    dialog.addWindowListener(object : WindowAdapter() {
                        override fun windowOpened(event: WindowEvent) {
                            if (!continuation.isActive) dialog.dispose()
                        }
                    })
                    if (continuation.isActive) {
                        PluginLog.info("backup dialog opened title=$title")
                        dialog.isVisible = true
                        if (continuation.isActive) continuation.resume(result())
                    }
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                } finally {
                    window.getAndSet(null)?.dispose()
                }
            }
        }

    suspend fun chooseFile(export: Boolean): Path? {
        val title = if (export) "导出听歌统计备份" else "导入听歌统计备份"
        return show(title) {
            val chooser = object : JFileChooser() { fun window() = createDialog(null) }
            chooser.dialogTitle = title
            chooser.dialogType = if (export) JFileChooser.SAVE_DIALOG else JFileChooser.OPEN_DIALOG
            chooser.fileFilter = FileNameExtensionFilter("JSON 备份 (*.json)", "json")
            if (export) chooser.selectedFile = java.io.File("spw-listen-stats-${LocalDate.now()}.json")
            val dialog = chooser.window()
            var selected: Path? = null
            chooser.addActionListener {
                if (it.actionCommand == JFileChooser.APPROVE_SELECTION) selected = chooser.selectedFile?.toPath()
                dialog.dispose()
            }
            dialog to { selected }
        }
    }

    suspend fun confirm(title: String, text: String): Boolean = show(title) {
        val pane = JOptionPane(text, JOptionPane.QUESTION_MESSAGE, JOptionPane.YES_NO_OPTION)
        pane.createDialog(null, title) to { pane.value == JOptionPane.YES_OPTION }
    }

    suspend fun message(title: String, text: String, error: Boolean = false) = show(title) {
        val pane = JOptionPane(text, if (error) JOptionPane.ERROR_MESSAGE else JOptionPane.INFORMATION_MESSAGE)
        pane.createDialog(null, title) to { Unit }
    }
}
