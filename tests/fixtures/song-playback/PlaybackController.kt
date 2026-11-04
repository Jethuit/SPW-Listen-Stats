package com.xuncorp.voxzen.service

import com.xuncorp.pisces.PiscesMediaItem

/** Records native command order without loading the actual playback controller or audio engine. */
object PlaybackController {
    @JvmField val calls = mutableListOf<String>()
    @JvmField var item: PiscesMediaItem? = null
    @JvmField var engineReady = false
    @JvmField var playing = false
    @JvmField var failPreparation = false

    fun addToPlay(value: PiscesMediaItem) { calls += "addToPlay"; item = value; engineReady = false; playing = false }
    fun prepare() {
        calls += "prepare"
        if (failPreparation) error("synthetic preparation failure")
        engineReady = true
    }
    fun play() { check(engineReady); calls += "play"; playing = true }
}
