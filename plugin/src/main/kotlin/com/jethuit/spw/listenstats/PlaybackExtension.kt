package com.jethuit.spw.listenstats

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import org.pf4j.Extension

@Extension
class PlaybackExtension : PlaybackExtensionPoint {
    override fun onStateChanged(state: PlaybackExtensionPoint.State) {
        PluginLog.info("state=$state")
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        PluginLog.info("isPlaying=$isPlaying")
    }

    override fun onSeekTo(position: Long) {
        PluginLog.info("seekTo=$position")
    }
}
