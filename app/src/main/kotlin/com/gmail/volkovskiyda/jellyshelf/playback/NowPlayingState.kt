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
    /**
     * Whether the queue has an item after this one, so the bar can dim its next button at the end.
     * Read off the player rather than derived here: the bar has no player and no timeline.
     */
    val hasNext: Boolean = false,
    /**
     * The decoded video's pixel size, for Picture-in-Picture's aspect ratio; zero until the first
     * frame has been decoded, and zero forever for a stream that turns out to have no video.
     * Here rather than in a flow of its own because the activity reads it in the same breath as
     * [isPlaying], and the bar simply ignores it.
     */
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    /**
     * Where the video has got to, for the bar's progress line. Pushed on a tick while playing
     * rather than derived from a clock here: the bar has no player to ask, and a line that
     * advanced on its own would keep advancing through a buffering stall.
     *
     * Both are zero until the player reports them. [durationMs] stays zero for a stream whose
     * length is not known yet, which is what [progress] reads as "do not draw a line" rather than
     * as "at the start" — the two look identical in a progress bar and only one of them is true.
     */
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
) {

    /**
     * The fraction to draw, or null when there is nothing honest to draw — no duration yet, so no
     * denominator. Computed here rather than at the call site so the "unknown is not zero" rule
     * lives in one place; [MiniPlayerBar][com.gmail.volkovskiyda.jellyshelf.ui.MiniPlayerBar]
     * takes the answer, not the inputs.
     */
    val progress: Float?
        get() = durationMs
            .takeIf { it > 0L }
            ?.let { (positionMs.toFloat() / it).coerceIn(0f, 1f) }
}

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
 * is *not* on that thread still sees a consistent value. Since media3 1.11.0 the player half of
 * that is enforced rather than merely tidy: its state accessors throw when read off the player's
 * application looper, so a [Transport] call made from anywhere else is a crash, not a race.
 */
class NowPlayingState {

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    /**
     * What the bar's buttons reach. Registered by the service so every action lands on the real
     * player rather than on a copy of its rules — [stop] in particular has to be the exact
     * sequence the player screen's back uses, or the server gets the wrong stop report.
     *
     * Implementations run on the player's application looper, and every caller must already be on
     * it: these are called straight from a Compose `onClick`, so nothing here posts. The service's
     * implementation asserts that in debug builds.
     */
    interface Transport {
        fun playPause()
        fun next()
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

    /** The timeline moved or landed. A no-op when nothing is showing. */
    fun setHasNext(hasNext: Boolean) {
        _nowPlaying.value = _nowPlaying.value?.copy(hasNext = hasNext)
    }

    /** The decoder reported the video's size. A no-op when nothing is showing. */
    fun setVideoSize(width: Int, height: Int) {
        _nowPlaying.value = _nowPlaying.value?.copy(videoWidth = width, videoHeight = height)
    }

    /**
     * The playing position moved. A no-op when nothing is showing.
     *
     * Pushed on a tick while playing, and once more when playback stops, so a paused bar shows
     * where it was paused rather than freezing one tick short of it.
     */
    fun setProgress(positionMs: Long, durationMs: Long) {
        _nowPlaying.value = _nowPlaying.value?.copy(
            positionMs = positionMs,
            durationMs = durationMs,
        )
    }

    fun playPause() {
        transport.value?.playPause()
    }

    fun next() {
        transport.value?.next()
    }

    fun stop() {
        transport.value?.stop()
    }
}
