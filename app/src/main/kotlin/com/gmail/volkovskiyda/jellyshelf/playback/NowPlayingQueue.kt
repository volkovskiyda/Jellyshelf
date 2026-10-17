package com.gmail.volkovskiyda.jellyshelf.playback

import androidx.media3.common.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The session's whole queue, as the screens that list it need it: the player's Now playing panel
 * and the Others tab's Now playing filter. Written by [PlaybackService] through [QueueKeeper], read
 * by those screens — the same arrangement as [NowPlayingState], and for the same reason: a
 * controller connected just to read this would start the service to find out nothing is queued.
 *
 * Not the player's timeline: that one leaves skipped videos out, which is how playback passes over
 * them, while this keeps them in their places. Main thread only, like the player behind it.
 */
class NowPlayingQueue {

    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())

    /**
     * The queue in play order, skipped videos included; empty when nothing is queued. Each item
     * carries the metadata the service attached when it resolved the queue.
     */
    val items: StateFlow<List<MediaItem>> = _items.asStateFlow()

    /** Registered by the service, so a jump lands on the real player. */
    fun interface Jumper {
        fun jumpTo(youtubeId: String, play: Boolean)
    }

    private val jumper = MutableStateFlow<Jumper?>(null)

    fun attach(jumper: Jumper) {
        this.jumper.value = jumper
    }

    /** The service is going away: no player to jump on, and nothing queued any more. */
    fun detach() {
        jumper.value = null
        _items.value = emptyList()
    }

    /** The queue was loaded, replaced, or emptied. */
    fun show(items: List<MediaItem>) {
        _items.value = items
    }

    /**
     * Plays [youtubeId] out of the queue — a row tap in the panel or in the Others filter —
     * unskipping it first if it was skipped, since picking it is the user choosing it. [play]
     * starts playback too; otherwise a pause carries over, as the panel's jump always has. A no-op
     * for a video not in the queue, and with no service running.
     */
    fun jumpTo(youtubeId: String, play: Boolean) {
        jumper.value?.jumpTo(youtubeId, play)
    }
}
