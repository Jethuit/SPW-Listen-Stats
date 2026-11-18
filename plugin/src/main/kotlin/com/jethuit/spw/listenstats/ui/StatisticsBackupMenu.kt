@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, com.moriafly.salt.ui.UnstableSaltUiApi::class)

package com.jethuit.spw.listenstats.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import com.jethuit.spw.listenstats.data.StatsSnapshots
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import androidx.compose.runtime.setValue
import com.jethuit.spw.listenstats.PluginLog
import com.jethuit.spw.listenstats.data.BackupService
import com.jethuit.spw.listenstats.data.StatsData
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch

@Composable
internal fun StatisticsBackupMenu(snapshot: StatsData, onImported: () -> Unit) {
    val session = StatsSnapshots.current
    val exiting = LocalStatsPageExiting.current
    val scope = remember(session, exiting) {
        CoroutineScope(Dispatchers.Main + SupervisorJob(session?.pageActions))
    }
    DisposableEffect(scope) { onDispose { scope.cancel() } }
    var checkpoint by remember { mutableStateOf<Long?>(null) }
    var update by remember { mutableStateOf(0) }
    LaunchedEffect(update) {
        checkpoint = withContext(Dispatchers.IO) {
            runCatching { BackupService.undoRevision() }.onFailure {
                PluginLog.error("checking import undo availability failed", it)
            }.getOrNull()
        }
    }
    var busy by remember(scope) { mutableStateOf(false) }
    val backupExceptionHandler = remember {
        CoroutineExceptionHandler { _, failure ->
            PluginLog.error("backup coroutine uncaught failure", failure)
        }
    }
    fun start(operation: suspend () -> Unit) {
        if (busy || exiting) return
        busy = true
        scope.launch(backupExceptionHandler) {
            try { operation() } finally { busy = false; update++ }
        }
    }
    BackupMenu(onImport = { start { if (BackupService.importBackup()) onImported() } },
        onExport = { start { BackupService.export(snapshot) } },
        onUndo = checkpoint?.let { revision ->
            { start { if (BackupService.undoBackup(revision)) onImported() } }
        })
}
