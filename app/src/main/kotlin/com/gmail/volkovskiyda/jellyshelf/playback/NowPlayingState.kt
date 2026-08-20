package com.gmail.volkovskiyda.jellyshelf.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The playing video, as much of it as a screen that is not the player needs: enough to draw a row
 * and to know which video reopening the player should land on.
 *
 * [isPlaying] is the player's own `isPlaying`, so it is false while buffering as well as while
 * paused — which is what the bar's icon should say, since neither state is "sound is coming out".
 */
data class NowPlaying(
    val youtubeId: String,
    val title: String?,
    val artworkUri: String?,
    val isPlaying: Boolean,
)

/**
 * App-scoped "something is playing", written by [PlaybackService] and read by whatever is on
 * screen. Null means nothing is playing and no bar is shown.
 *
 * A Koin single the service pushes into, deliberately *not* a second [MediaController] at activity
 * scope: connecting a controller instantiates [PlaybackService] and its ExoPlayer, so an app that
 * merely launched would start the playback service to discover that nothing is playing. The
 * service is in this process already, so it can simply say.
 *
 * Everything here runs on the main thread — the service's player lives there and composition reads
 * there — and the state is held in [MutableStateFlow]s rather than plain fields so a reader that
 * is *not* on that thread still sees a consistent value.
 */
class NowPlayingState {

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    /**
     * What the bar's buttons reach. Registered by the service so both actions land on the real
     * player rather than on a copy of its rules — [stop] in particular has to be the exact
     * sequence the player screen's back uses, or the server gets the wrong stop report.
     */
    interface Transport {
        fun playPause()
        fun stop()
    }

    private val transport = MutableStateFlow<Transport?>(null)

    fun attach(transport: Transport) {
        this.transport.value = transport
    }

    /** The service is going away: no player to command, and nothing is playing any more. */
    fun detach() {
        transport.value = null
        _nowPlaying.value = null
    }

    /** A new current item, or null when the queue empties. */
    fun show(nowPlaying: NowPlaying?) {
        _nowPlaying.value = nowPlaying
    }

    /** Play/pause moved without the item changing. A no-op when nothing is showing. */
    fun setPlaying(isPlaying: Boolean) {
        _nowPlaying.value = _nowPlaying.value?.copy(isPlaying = isPlaying)
    }

    fun playPause() {
        transport.value?.playPause()
    }

    fun stop() {
        transport.value?.stop()
    }
}
