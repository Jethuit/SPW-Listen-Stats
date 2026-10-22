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
    // WorkshopApi has no playing-state getter and enable may miss the last callback.
    // Until the first explicit state, only observed, plausible progress can establish playback.
    private var playing: Boolean? = null
    private var position = -1L
    private var nano = 0L
    private var wall = 0L
    private var recoveryAvailable = true
    private val lifecycleId = UUID.randomUUID().toString()
    private var loopPosition = -1L
    private var loopNano = 0L
    private var hintTrackId: String? = null
    private var originHint: Long? = null

    fun playing(value: Boolean) {
        if (playing != value) resetBaseline()
        playing = value
    }

    fun seek(target: Long) {
        loopPosition = -1L // Explicit seeks must not look like an automatic rollover.
        if (target == 0L) recoveryAvailable = false
        // An explicit restart at zero begins a new play; other backward seeks stay in this play.
        if (target == 0L && (current?.durationMs ?: 0) > 0) finish()
        resetBaseline()
    }

    fun suspendSampling() = resetBaseline()

    /** Metadata can close an old play even when the accompanying position cannot be accepted. */
    fun observeTrack(track: TrackMeta?, originHintMs: Long? = null) {
        if (track != null && track.id.isNotBlank() && current?.let { it.trackId != track.id } == true) {
            finish()
        }
        if (track != null && track.id.isNotBlank() && hintTrackId != track.id) {
            hintTrackId = track.id
            originHint = originHintMs
        }
    }

    fun sample(track: TrackMeta?, nextPosition: Long, nowWall: Long, nowNano: Long) {
        if (track == null || track.id.isBlank() || playing == false) {
            resetBaseline()
            return
        }
        var session = current
        val sinceEvidence = ((nowNano - loopNano) / 1_000_000).coerceAtLeast(0)
        val window = (sinceEvidence * 4 + 250).coerceIn(3_000, 12_000)
        val loop = session != null && session.trackId == track.id && session.trackDurationMs > 0 &&
            loopPosition >= 0 && sinceEvidence <= 15_000 &&
            loopPosition >= session.trackDurationMs - window && nextPosition in 0..window &&
            loopPosition - nextPosition >= maxOf(1_000, session.trackDurationMs / 2)
        if (session?.trackId != track.id || loop) {
            val recover = recoveryAvailable && !loop
            val hint = if (!loop && hintTrackId == track.id) originHint else null
            recoveryAvailable = false
            finish()
            val resumed = if (recover) resume(track) else null
            if (resumed != null) {
                current = resumed
                if (track.durationMs > 0) resumed.trackDurationMs = track.durationMs
                position = nextPosition
                nano = nowNano
                wall = nowWall
                resumed.lastPositionMs = nextPosition
                loopPosition = nextPosition
                loopNano = nowNano
                return
            }
            session = LiveSession(UUID.randomUUID().toString(), track.id,
                nowWall - nextPosition.coerceAtLeast(0), 0,
                track.title, track.artist, track.album, track.albumArtist, track.durationMs, false,
                spans = mutableListOf(), firstPositionMs = nextPosition,
                lastPositionMs = nextPosition, lifecycleId = lifecycleId, observedOriginMs = hint)
            current = session
        }
        session!!
        if (track.durationMs > 0) session.trackDurationMs = track.durationMs
        session.lastPositionMs = nextPosition
        val elapsed = (nowNano - nano) / 1_000_000
        val delta = nextPosition - position
        // Position confirms progress; monotonic time caps actual listening. Discard sleep and seeks.
        if (position >= 0 && elapsed in 1..3_000 && delta in 1..maxOf(3_000, elapsed * 4 + 250)) {
            playing = true
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
        loopPosition = nextPosition
        loopNano = nowNano
    }

    fun finish() {
        recoveryAvailable = false
        current?.let {
            it.finalized = true
            if (it.durationMs > 0) persist(it)
        }
        current = null
        loopPosition = -1L
        hintTrackId = null
        originHint = null
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
    private var observationRevision = 0L
    private var positionSerial = 0L
    private var observedTrack: TrackMeta? = null
    private var stableTrackId: String? = null

    fun diagnostics(): String = synchronized(lock) {
        "trackerActive=$active; generation=$generation; liveSessions=${LiveStore.snapshot().size}"
    }

    fun initialize() = synchronized(lock) {
        generation++
        observationRevision++
        positionSerial = 0
        observedTrack = null
        stableTrackId = null
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
            observedTrack = null
            accumulator.finish()
            LiveStore.finalizeOpenExcept()
            LiveStore.save()
            executor.also { it.shutdownNow() }
        }
        try { old.awaitTermination(2, TimeUnit.SECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private fun enqueue(invalidateObservation: Boolean = false, action: () -> Unit) = synchronized(lock) {
        if (!active) return@synchronized
        // Invalidate at callback receipt, not after an older, potentially blocked lookup.
        if (invalidateObservation) {
            observationRevision++
            observedTrack = null
        }
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

    fun onStateChanged(state: PlaybackExtensionPoint.State) = enqueue(invalidateObservation = true) {
        if (state == PlaybackExtensionPoint.State.Ended || state == PlaybackExtensionPoint.State.Idle) {
            accumulator.finish()
            accumulator.playing(false)
            LiveStore.finalizeOpenExcept()
            LiveStore.save()
        } else if (state == PlaybackExtensionPoint.State.Buffering) accumulator.suspendSampling()
    }

    fun onIsPlayingChanged(isPlaying: Boolean) = enqueue(invalidateObservation = true) {
        accumulator.playing(isPlaying)
        if (!isPlaying) LiveStore.save()
    }

    fun onSeekTo(position: Long) = enqueue(invalidateObservation = true) {
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
            val revision = observationRevision
            val serial = ++positionSerial
            val before = observedTrack
            try {
                executor.execute {
                    if (!synchronized(lock) {
                        if (!active || generation != expected) false
                        else if (revision != observationRevision || serial != positionSerial) {
                            accumulator.suspendSampling()
                            false
                        } else true
                    }) return@execute
                    // Host IO stays outside the lifecycle lock, so shutdown can invalidate this result.
                    val track = try {
                        WorkshopApi.playback.getCurrentMediaItem()?.let {
                            TrackMeta(it.id, it.title, it.artist, it.album, it.albumArtist, it.duration)
                        }
                    } catch (_: Exception) { null }
                    synchronized(lock) {
                        if (!active || generation != expected) return@execute
                        if (revision != observationRevision || serial != positionSerial ||
                            System.nanoTime() - nano > 3_000_000_000L) {
                            accumulator.suspendSampling()
                            observedTrack = null
                            return@execute
                        }
                        observedTrack = track
                        // Track changes and position acceptance are separate decisions. A brief B
                        // observation must close A even if B never gets a second, stable sample.
                        val origin = when {
                            track == null -> null
                            position in 0..2_000 -> wall - position
                            stableTrackId != null && stableTrackId != track.id -> System.currentTimeMillis()
                            else -> null
                        }
                        accumulator.observeTrack(track, origin)
                        // The API has no atomic track+position snapshot. Only accept a position
                        // bracketed by matching metadata observations, with no queued transition.
                        // The first/uncertain observation is used to establish identity, not a play.
                        if (track == null || before?.id != track.id || position < 0 ||
                            (track.durationMs > 0 && position > track.durationMs + 3_000)) {
                            accumulator.suspendSampling()
                            // Same-song transitions already have identity evidence. Establish a
                            // baseline only (never bridge across the invalidated transition).
                            if (track != null && stableTrackId == track.id && position >= 0 &&
                                (track.durationMs <= 0 || position <= track.durationMs + 3_000)) {
                                accumulator.sample(track, position, wall, nano)
                            }
                            stableTrackId = track?.id
                            return@execute
                        }
                        stableTrackId = track.id
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
