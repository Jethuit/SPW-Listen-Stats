package com.xuncorp.voxzen.service

import com.xuncorp.pisces.PiscesMediaItem

// Deliberately a different class name and enum order from the real host.
enum class SyntheticQueuePosition { AtQueueEnd, AfterCurrent, AfterLastAdded }

/** Records native command order without loading the actual playback controller or audio engine. */
object PlaybackController {
    @JvmField val calls = mutableListOf<String>()
    @JvmField var item: PiscesMediaItem? = null
    @JvmField var engineReady = false
    @JvmField var playing = false
    @JvmField var failPreparation = false
    @JvmField var failQueue = false
    @JvmField val queued = mutableListOf<PiscesMediaItem>()
    @JvmField var queuePosition: String? = null

    fun addToPlay(value: PiscesMediaItem) { calls += "addToPlay"; item = value; engineReady = false; playing = false }
    fun prepare() {
        calls += "prepare"
        if (failPreparation) error("synthetic preparation failure")
        engineReady = true
    }
    fun play() { check(engineReady); calls += "play"; playing = true }

    fun addToPlayNext(position: SyntheticQueuePosition, vararg values: PiscesMediaItem) {
        calls += "addToPlayNext"
        if (failQueue) error("synthetic queue failure")
        queuePosition = position.name
        queued.addAll(values)
    }
}
