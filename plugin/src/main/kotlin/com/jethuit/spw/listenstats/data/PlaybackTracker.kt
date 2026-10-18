package com.jethuit.spw.listenstats.data

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** Callback timestamps, not async lookup completion times. Pauses never fill the gaps. */
class PlaybackAccumulator(
    private val persist: (LiveSession) -> Unit,
    private val resume: (TrackMeta) -> LiveSession?
) {
    constructor(persist: (LiveSession) -> Unit) : this(persist, { null })

    private var current: LiveSession? = null
    private var playing = false
    private var position = -1L
    private var nano = 0L
    private var wall = 0L
    private var recoveryAvailable = true

    fun playing(value: Boolean) {
        if (playing != value) resetBaseline()
        playing = value
    }

    fun seek(target: Long) {
        if (target == 0L) recoveryAvailable = false
        // An explicit restart at zero begins a new play; other backward seeks stay in this play.
        if (target == 0L && (current?.durationMs ?: 0) > 0) finish()
        resetBaseline()
    }

    fun suspendSampling() = resetBaseline()

    fun sample(track: TrackMeta?, nextPosition: Long, nowWall: Long, nowNano: Long) {
        if (track == null || track.id.isBlank() || !playing) {
            resetBaseline()
            return
        }
        var session = current
        val loop = session != null && session.trackId == track.id && nextPosition in 0..2_000L &&
            session.trackDurationMs > 0 && position >= session.trackDurationMs - 3_000 &&
            nextPosition < position
        if (session?.trackId != track.id || loop) {
            val recover = recoveryAvailable && !loop
            recoveryAvailable = false
            finish()
            val resumed = if (recover) resume(track) else null
            if (resumed != null) {
                current = resumed
                if (track.durationMs > 0) resumed.trackDurationMs = track.durationMs
                position = nextPosition
                nano = nowNano
                wall = nowWall
                return
            }
            session = LiveSession(UUID.randomUUID().toString(), track.id,
                nowWall - nextPosition.coerceAtLeast(0), 0,
                track.title, track.artist, track.album, track.albumArtist, track.durationMs, false,
                spans = mutableListOf())
            current = session
        }
        session!!
        if (track.durationMs > 0) session.trackDurationMs = track.durationMs
        val elapsed = (nowNano - nano) / 1_000_000
        val delta = nextPosition - position
        // Position confirms progress; monotonic time caps actual listening. Discard sleep and seeks.
        if (position >= 0 && elapsed in 1..3_000 && delta in 1..3_000) {
            val listened = elapsed
            session.durationMs += listened
            val previous = session.spans.lastOrNull()
            val observedStart = nowWall - listened
            // Clock corrections must not create overlapping intervals or lose elapsed listening.
            val start = maxOf(observedStart, previous?.let { it.startMs + it.durationMs } ?: observedStart)
            if (start != observedStart || kotlin.math.abs(nowWall - wall - elapsed) > 100) {
                session.approximate = true
            }
            val span = ListeningSpan(start, listened)
            if (previous != null && previous.startMs + previous.durationMs == span.startMs) {
                session.spans[session.spans.lastIndex] = previous.copy(durationMs = previous.durationMs + listened)
            } else session.spans.add(span)
            persist(session)
        }
        position = nextPosition
        nano = nowNano
        wall = nowWall
    }

    fun finish() {
        recoveryAvailable = false
        current?.let {
            it.finalized = true
            if (it.durationMs > 0) persist(it)
        }
        current = null
        resetBaseline()
    }

    private fun resetBaseline() { position = -1L; nano = 0; wall = 0 }
}

/** Serialized callbacks; generation guards cover lookups returning after stop/re-enable. */
object PlaybackTracker {
    private val lock = Any()
    private var executor = newExecutor()
    private var generation = 0L
    private var active = false
    private var accumulator = PlaybackAccumulator(::persistSession)
    private var lastSaveNano = 0L

    fun initialize() = synchronized(lock) {
        generation++
        if (executor.isShutdown) executor = newExecutor()
        LiveStore.load()
        accumulator = PlaybackAccumulator(::persistSession) { track -> LiveStore.recoverOpen(track.id) }
        lastSaveNano = System.nanoTime()
        active = true
    }

    fun shutdown() {
        val old = synchronized(lock) {
            active = false
            generation++
            accumulator.finish()
            LiveStore.finalizeOpenExcept()
            LiveStore.save()
            executor.also { it.shutdownNow() }
        }
        try { old.awaitTermination(2, TimeUnit.SECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private fun enqueue(action: () -> Unit) = synchronized(lock) {
        if (!active) return@synchronized
        val expected = generation
        try {
            executor.execute {
                synchronized(lock) {
                    if (!active || generation != expected) return@execute
                    action()
                }
            }
        } catch (_: RejectedExecutionException) { }
    }

    fun onStateChanged(state: PlaybackExtensionPoint.State) = enqueue {
        if (state == PlaybackExtensionPoint.State.Ended || state == PlaybackExtensionPoint.State.Idle) {
            accumulator.finish()
            accumulator.playing(false)
            LiveStore.finalizeOpenExcept()
            LiveStore.save()
        } else if (state == PlaybackExtensionPoint.State.Buffering) accumulator.suspendSampling()
    }

    fun onIsPlayingChanged(isPlaying: Boolean) = enqueue {
        accumulator.playing(isPlaying)
        if (!isPlaying) LiveStore.save()
    }

    fun onSeekTo(position: Long) = enqueue {
        accumulator.seek(position)
        if (position == 0L) LiveStore.finalizeOpenExcept()
        LiveStore.save()
    }

    fun onPositionUpdated(position: Long) {
        val wall = System.currentTimeMillis()
        val nano = System.nanoTime()
        synchronized(lock) {
            if (!active) return
            val expected = generation
            try {
                executor.execute {
                    if (!synchronized(lock) { active && generation == expected }) return@execute
                    // Host IO stays outside the lifecycle lock, so shutdown can invalidate this result.
                    val track = try {
                        WorkshopApi.playback.getCurrentMediaItem()?.let {
                            TrackMeta(it.id, it.title, it.artist, it.album, it.albumArtist, it.duration)
                        }
                    } catch (_: Exception) { null }
                    synchronized(lock) {
                        if (!active || generation != expected) return@execute
                        if (System.nanoTime() - nano > 3_000_000_000L) {
                            accumulator.suspendSampling()
                            return@execute
                        }
                        accumulator.sample(track, position, wall, nano)
                        if (nano - lastSaveNano >= 10_000_000_000L) {
                            lastSaveNano = nano
                            LiveStore.save()
                        }
                    }
                }
            } catch (_: RejectedExecutionException) { }
        }
    }

    // Lyrics prefetch is not a track-change notification and must never create sessions.
    fun onMediaItemHint(mediaItem: PlaybackExtensionPoint.MediaItem) = Unit

    private fun persistSession(session: LiveSession) {
        LiveStore.upsert(session)
        if (session.finalized) LiveStore.save()
    }

    private fun newExecutor() = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ListenStats-Playback").apply { isDaemon = true }
    }
}
