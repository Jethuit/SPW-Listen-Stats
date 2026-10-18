package com.jethuit.spw.listenstats

import com.jethuit.spw.listenstats.data.PlaybackTracker
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import org.pf4j.Extension

@Extension
class PlaybackExtension : PlaybackExtensionPoint {
    override fun onStateChanged(state: PlaybackExtensionPoint.State) {
        PlaybackTracker.onStateChanged(state)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        PlaybackTracker.onIsPlayingChanged(isPlaying)
    }

    override fun onSeekTo(position: Long) {
        PlaybackTracker.onSeekTo(position)
    }

    override fun onPositionUpdated(position: Long) {
        PlaybackTracker.onPositionUpdated(position)
    }

    override fun onBeforeLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        PlaybackTracker.onMediaItemHint(mediaItem)
        return null
    }
}
