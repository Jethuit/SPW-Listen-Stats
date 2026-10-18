package com.jethuit.spw.listenstats.data

import com.jethuit.spw.listenstats.PluginLog
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.max

/**
 * Turns the host's playback callbacks into durable listening sessions.
 *
 * Position callbacks arrive every second. Only forward gaps of at most three
 * seconds are treated as listened time; pause/seek/loop jumps are discarded.
 * A session is finalized on track change, Ended/Idle, or plugin shutdown.
 */
object PlaybackTracker {
    private val lock = ReentrantLock()
    private var executor = newExecutor()
    private val resolvePending = AtomicBoolean(false)

    private var current: LiveSession? = null
    private var playing = false
    private var lastPosition = -1L
    private var lastResolveWall = 0L
    private var lastSaveWall = 0L
    private var hint: PlaybackExtensionPoint.MediaItem? = null
    private var permissionLogged = false

    @Volatile
    private var stopping = false

    fun initialize() {
        stopping = false
        if (executor.isShutdown) {
            executor = newExecutor()
        }
        resolvePending.set(false)
        LiveStore.load()
        lastSaveWall = System.currentTimeMillis()
    }

    fun shutdown() {
        stopping = true
        withLock {
            finalizeCurrent()
        }
        LiveStore.save()
        executor.shutdown()
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun onStateChanged(state: PlaybackExtensionPoint.State) {
        PluginLog.info("playback state=$state")
        if (state == PlaybackExtensionPoint.State.Ended ||
            state == PlaybackExtensionPoint.State.Idle
        ) {
            withLock {
                finalizeCurrent()
                playing = false
            }
            LiveStore.save()
        }
    }

    fun onIsPlayingChanged(isPlaying: Boolean) {
        PluginLog.info("playing=$isPlaying")
        withLock {
            playing = isPlaying
            if (isPlaying) {
                lastResolveWall = 0L
            }
        }
        if (isPlaying) {
            scheduleResolve()
        } else {
            LiveStore.save()
        }
    }

    fun onSeekTo(position: Long) {
        withLock { lastPosition = position }
    }

    fun onPositionUpdated(position: Long) {
        var schedule = false
        var persist = false
        withLock {
            val session = current
            if (session != null && playing) {
                if (lastPosition >= 0) {
                    val delta = position - lastPosition
                    if (delta in 1..3_000) {
                        session.durationMs += delta
                        LiveStore.upsert(session)
                    } else if (delta < -2_000) {
                        // Loop/repeat or a backward seek that did not fire onSeekTo.
                        finalizeCurrent()
                        schedule = true
                    }
                }
                lastPosition = position
            } else {
                lastPosition = position
            }
            val now = System.currentTimeMillis()
            if (playing && (current == null || now - lastResolveWall > 1_500)) {
                lastResolveWall = now
                schedule = true
            }
            if (now - lastSaveWall > 10_000) {
                lastSaveWall = now
                persist = true
            }
        }
        if (schedule) scheduleResolve()
        if (persist) LiveStore.save()
    }

    fun onMediaItemHint(mediaItem: PlaybackExtensionPoint.MediaItem) {
        hint = mediaItem
        withLock {
            if (playing && (current == null || current?.trackId != mediaItem.id)) {
                lastResolveWall = 0L
            }
        }
        scheduleResolve()
    }

    private fun scheduleResolve() {
        if (!resolvePending.compareAndSet(false, true)) return
        executor.execute {
            try {
                val item = try {
                    WorkshopApi.playback.getCurrentMediaItem()
                } catch (failure: Throwable) {
                    if (!permissionLogged) {
                        permissionLogged = true
                        PluginLog.error("reading current media item failed", failure)
                    }
                    null
                }
                if (item != null) {
                    applyResolved(item, lastPosition)
                }
            } finally {
                resolvePending.set(false)
            }
        }
    }

    private fun applyResolved(item: PlaybackExtensionPoint.MediaItem, position: Long) {
        if (stopping || item.id.isBlank()) return
        var duration = item.duration
        if (duration <= 0) {
            duration = try {
                WorkshopApi.library.getTrackById(item.id)
                    .toCompletableFuture()
                    .get(2, TimeUnit.SECONDS)
                    ?.duration ?: 0L
            } catch (failure: Throwable) {
                0L
            }
        }
        withLock {
            val session = current
            if (session != null && session.trackId == item.id) {
                mergeMetadata(session, item, duration)
                LiveStore.upsert(session)
            } else {
                if (session != null) {
                    finalizeCurrent()
                }
                val observedPosition = position.coerceAtLeast(0L)
                val start = System.currentTimeMillis() -
                    observedPosition.coerceAtMost(if (duration > 0) duration else observedPosition)
                val existing = LiveStore.findResumable(item.id, start)
                val next = existing ?: LiveSession(
                    id = UUID.randomUUID().toString(),
                    trackId = item.id,
                    startTimeMs = start,
                    durationMs = 0L,
                    title = item.title,
                    artist = item.artist,
                    album = item.album,
                    albumArtist = item.albumArtist,
                    trackDurationMs = duration,
                    finalized = false
                )
                next.finalized = false
                mergeMetadata(next, item, duration)
                current = next
                LiveStore.upsert(next)
                lastPosition = observedPosition
                PluginLog.info(
                    "session start ${next.trackId} '${next.title}' " +
                        "position=$observedPosition track=${next.trackDurationMs}"
                )
            }
        }
    }

    private fun mergeMetadata(
        session: LiveSession,
        item: PlaybackExtensionPoint.MediaItem,
        duration: Long
    ) {
        if (item.title.isNotBlank()) session.title = item.title
        if (item.artist.isNotBlank()) session.artist = item.artist
        if (item.album.isNotBlank()) session.album = item.album
        if (item.albumArtist.isNotBlank()) session.albumArtist = item.albumArtist
        if (duration > 0) session.trackDurationMs = duration
    }

    private fun finalizeCurrent() {
        val session = current ?: return
        session.finalized = true
        PluginLog.info(
            "finalize session ${session.trackId} start=${session.startTimeMs} " +
                "listened=${session.durationMs} track=${session.trackDurationMs}"
        )
        LiveStore.upsert(session)
        current = null
    }

    private fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    private fun newExecutor() = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ListenStats-Playback").apply { isDaemon = true }
    }
}
