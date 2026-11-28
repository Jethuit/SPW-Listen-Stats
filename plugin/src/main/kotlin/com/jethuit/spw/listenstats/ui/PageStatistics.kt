@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, com.moriafly.salt.ui.UnstableSaltUiApi::class)

package com.jethuit.spw.listenstats.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.jethuit.spw.listenstats.Prefs
import com.jethuit.spw.listenstats.data.OverviewSelection
import com.jethuit.spw.listenstats.data.PreparedStatistics
import com.jethuit.spw.listenstats.data.RankedEntry
import com.jethuit.spw.listenstats.data.RankingIndex
import com.jethuit.spw.listenstats.data.RankingMetric
import com.jethuit.spw.listenstats.data.RankingMode
import com.jethuit.spw.listenstats.data.Rankings
import com.jethuit.spw.listenstats.data.StatsData
import com.jethuit.spw.listenstats.data.StatsSnapshotSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@Composable
internal fun rememberPageStatistics(snapshots: StatsSnapshotSession, initial: PreparedStatistics?,
                                    selection: OverviewSelection, reloadTick: Int,
                                    canApply: Boolean): PreparedStatistics? {
    val allowed by rememberUpdatedState(canApply)
    val currentSelection by rememberUpdatedState(selection)
    var current by remember { mutableStateOf(initial, referentialEqualityPolicy()) }
    LaunchedEffect(reloadTick) {
        // The embedded entry is prepared before navigation. Standalone previews may load here.
        if (current == null) withContext(Dispatchers.IO) { Prefs.initialize() }
        if (reloadTick == 0 && initial != null) {
            snapshotFlow { allowed }.first { it }
            if (initial.data.error == null) delay(initial.refreshDelayMillis())
        }
        while (true) {
            UiPerformanceProbe.mark("refresh_start", "tick=$reloadTick")
            val requestedSelection = currentSelection
            val loaded = withContext(Dispatchers.IO) { snapshots.load(selection = requestedSelection) }
            UiPerformanceProbe.mark("refresh_prepared", "tick=$reloadTick")
            if (current != null && !loaded.data.libraryAccessDenied) {
                snapshotFlow { allowed }.first { it }
            }
            val shown = current
            if (shown == null || shown.data !== loaded.data || shown.preferences != loaded.preferences ||
                shown.overview !== loaded.overview || shown.today != loaded.today) {
                current = loaded
            }
            UiPerformanceProbe.mark("refresh_applied", "tick=$reloadTick error=${loaded.data.error != null}")
            delay(loaded.preferences.refreshIntervalSeconds * 1_000L)
        }
    }
    return current
}

internal data class PageRanking(val entries: List<RankedEntry>, val ready: Boolean)
private data class RankingRequest(val dataIdentity: Any?, val mode: RankingMode, val metric: RankingMetric)

@Composable
internal fun rememberPageRanking(snapshot: StatsData?, rankingMode: RankingMode, rankingMetric: RankingMetric,
                                 query: String, mayPrepare: Boolean): PageRanking {
    val allowed by rememberUpdatedState(mayPrepare)
    var rankingIndex by remember { mutableStateOf<Pair<RankingRequest, RankingIndex>?>(null, referentialEqualityPolicy()) }
    var rankingEntries by remember { mutableStateOf<List<RankedEntry>>(emptyList(), referentialEqualityPolicy()) }
    var rankingReadyKey by remember { mutableStateOf<Pair<RankingRequest, String>?>(null) }
    val request = RankingRequest(snapshot?.snapshotIdentity, rankingMode, rankingMetric)
    val rankingKey = request to query
    LaunchedEffect(request) {
        rankingReadyKey = null
        if (snapshot == null) {
            rankingIndex = null
            rankingEntries = emptyList()
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
        rankingIndex = request to computed
        UiPerformanceProbe.mark("ranking_index_ready")
    }
    val availableIndex = rankingIndex
    LaunchedEffect(request, availableIndex?.first, query) {
        if (availableIndex == null || availableIndex.first != request) return@LaunchedEffect
        rankingReadyKey = null
        if (query.isNotBlank()) delay(150)
        UiPerformanceProbe.mark("ranking_filter_start", "queryLength=${query.length}")
        val computed = withContext(Dispatchers.Default) { availableIndex.second.filter(query) }
        rankingEntries = computed
        rankingReadyKey = rankingKey
        UiPerformanceProbe.mark("ranking_filter_ready", "entries=${computed.size}")
    }
    return PageRanking(rankingEntries, snapshot == null || rankingReadyKey == rankingKey)
}
