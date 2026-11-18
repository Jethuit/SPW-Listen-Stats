@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, com.moriafly.salt.ui.UnstableSaltUiApi::class)

package com.jethuit.spw.listenstats.ui

import androidx.compose.runtime.Composable
import com.jethuit.spw.listenstats.DisplayPreferences
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.jethuit.spw.listenstats.Prefs
import com.jethuit.spw.listenstats.data.RankingMode
import com.jethuit.spw.listenstats.data.RankingMetric
import com.jethuit.spw.listenstats.data.RankedEntry
import com.jethuit.spw.listenstats.data.RankingIndex
import com.jethuit.spw.listenstats.data.Rankings
import com.jethuit.spw.listenstats.data.StatsData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import com.jethuit.spw.listenstats.data.StatsSnapshots
import com.jethuit.spw.listenstats.data.StatsSnapshotSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate

internal data class PageStatistics(val data: StatsData?, val preferences: DisplayPreferences, val today: LocalDate)

@Composable
internal fun rememberPageStatistics(reloadTick: Int, canApply: Boolean): PageStatistics {
    val allowed by rememberUpdatedState(canApply)
    val snapshots = remember { StatsSnapshots.current ?: StatsSnapshotSession() }
    val initial = remember { snapshots.latest }
    var data by remember { mutableStateOf(initial?.data) }
    var displayPreferences by remember { mutableStateOf(initial?.preferences ?: DisplayPreferences()) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(reloadTick) {
        // The embedded entry is prepared before navigation. Standalone previews may load here.
        if (initial == null) withContext(Dispatchers.IO) { Prefs.initialize() }
        while (true) {
            UiPerformanceProbe.mark("refresh_start", "tick=$reloadTick")
            val loaded = withContext(Dispatchers.IO) { snapshots.load() }
            if (data != null && !loaded.data.libraryAccessDenied) {
                snapshotFlow { allowed }.first { it }
            }
            data = loaded.data
            displayPreferences = loaded.preferences
            today = LocalDate.now()
            UiPerformanceProbe.mark("refresh_applied", "tick=$reloadTick error=${loaded.data.error != null}")
            delay(displayPreferences.refreshIntervalSeconds * 1_000L)
        }
    }
    return PageStatistics(data, displayPreferences, today)
}

internal data class PageRanking(val entries: List<RankedEntry>, val ready: Boolean)

@Composable
internal fun rememberPageRanking(snapshot: StatsData?, rankingMode: RankingMode, rankingMetric: RankingMetric,
                                 query: String, mayPrepare: Boolean): PageRanking {
    val allowed by rememberUpdatedState(mayPrepare)
    var rankingIndex by remember { mutableStateOf<RankingIndex?>(null) }
    var rankingIndexKey by remember { mutableStateOf<String?>(null) }
    var rankingEntries by remember { mutableStateOf<List<RankedEntry>>(emptyList()) }
    var rankingReadyKey by remember { mutableStateOf<String?>(null) }
    val rankingBaseKey = "${snapshot?.loadedAt ?: 0L}|${rankingMode.storage}|${rankingMetric.storage}"
    val rankingKey = "$rankingBaseKey|$query"
    LaunchedEffect(rankingBaseKey) {
        rankingIndex = null
        rankingIndexKey = null
        rankingReadyKey = null
        if (snapshot == null) {
            rankingEntries = emptyList()
            rankingIndexKey = rankingBaseKey
            rankingReadyKey = rankingKey
            return@LaunchedEffect
        }
        // Warm the hidden ranking after the overview's actual entrance finishes.
        // A user selecting ranking can request it immediately instead of waiting
        // for the old overview animation; no fixed-delay timer or duplicate job.
        snapshotFlow { allowed }.first { it }
        UiPerformanceProbe.mark("ranking_index_start")
        val source = snapshot
        val computed = withContext(Dispatchers.Default) {
            Rankings.index(source, rankingMode, rankingMetric)
        }
        rankingIndex = computed
        rankingIndexKey = rankingBaseKey
        UiPerformanceProbe.mark("ranking_index_ready")
    }
    LaunchedEffect(rankingBaseKey, rankingIndexKey, query) {
        if (rankingIndexKey != rankingBaseKey) return@LaunchedEffect
        val index = rankingIndex ?: return@LaunchedEffect
        rankingReadyKey = null
        if (query.isNotBlank()) delay(150)
        UiPerformanceProbe.mark("ranking_filter_start", "queryLength=${query.length}")
        val computed = withContext(Dispatchers.Default) { index.filter(query) }
        rankingEntries = computed
        rankingReadyKey = rankingKey
        UiPerformanceProbe.mark("ranking_filter_ready", "entries=${computed.size}")
    }
    return PageRanking(rankingEntries, rankingReadyKey == rankingKey)
}
