package com.gmail.volkovskiyda.jellyshelf.playback

import android.os.SystemClock
import com.gmail.volkovskiyda.jellyshelf.util.Metrics
import com.gmail.volkovskiyda.jellyshelf.util.Spans
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * What went wrong while one item played, counted onto one Firebase trace per item.
 *
 * [PlaybackSpans] measures waits that end at a rendered frame. This measures the rest of the
 * session: how often the video stopped to rebuffer and for how long, how often the user seeked, and
 * how often the stream had to be reconnected underneath them. None of it has a duration of its own
 * worth reporting — a rebuffer is interesting as a *count per item*, not as a distribution of
 * isolated events — so it all rides on `player_item`, which opens at the first frame and reports
 * when the item ends. One event per item played, with the context to read it: an item that
 * rebuffered four times and reconnected twice is a different story from four isolated stalls.
 *
 * **Not counted, deliberately:**
 *  - The bundled demo clip, which plays off local storage and cannot stall for any reason a real
 *    stream can. It never opens a trace at all.
 *  - Buffering before the first frame. That is startup, and `player_startup` already measures it.
 *  - Buffering that follows a seek inside [SEEK_SETTLE_MS]. The player always refills after a seek;
 *    counting that as a rebuffer would make scrubbing look like a network problem.
 *  - The preloaded *next* item's bytes and reconnects. `StallWatchdog` deliberately watches every
 *    loader, but only the item on screen is being reported on here.
 *  - The transcode fallback's re-prepare. That is a source change, and it shows up as the next
 *    item's `source = hls` rather than as a reconnect.
 *
 * Threading: the reconnect counter is incremented from media3 loader threads while everything else
 * runs on the player's application looper, so every counter is a [MutableStateFlow] slot — the
 * convention in this project for a slot two threads share.
 */
internal class PlaybackHealth(
    private val metrics: Metrics,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {

    private val rebuffers = MutableStateFlow(0L)
    private val rebufferMs = MutableStateFlow(0L)
    private val seeks = MutableStateFlow(0L)
    private val reconnectsIdle = MutableStateFlow(0L)
    private val reconnectsStall = MutableStateFlow(0L)

    private var open: Metrics.OpenSpan? = null
    private var mediaId: String? = null
    private var source = PlaybackSpans.SOURCE_DIRECT
    private var startedAt = 0L
    private var rebufferingSince = 0L
    private var lastSeekAt: Long? = null

    /**
     * The item is on screen. Opens its trace, or leaves the open one alone when the same item
     * renders again — which it does after every seek.
     */
    fun onFirstFrame(mediaId: String?, source: String, demo: Boolean) {
        if (demo || mediaId == null || mediaId == this.mediaId) return
        report(END_STOPPED)
        this.mediaId = mediaId
        this.source = source
        startedAt = elapsedRealtime()
        rebufferingSince = 0L
        lastSeekAt = null
        rebuffers.value = 0L
        rebufferMs.value = 0L
        seeks.value = 0L
        reconnectsIdle.value = 0L
        reconnectsStall.value = 0L
        open = metrics.begin(Spans.PLAYER_ITEM, track = PlaybackSpans.TRACK_PLAYBACK)
    }

    /** The player ran out of buffer. Before the first frame, or just after a seek, it is neither. */
    fun onBuffering() {
        val now = elapsedRealtime()
        if (open == null || rebufferingSince != 0L) return
        // Nullable rather than a sentinel: `now - Long.MIN_VALUE` overflows to a negative number,
        // which reads as "just seeked" and would silently suppress every rebuffer ever counted.
        if (lastSeekAt?.let { now - it < SEEK_SETTLE_MS } == true) return
        rebufferingSince = now
        rebuffers.update { it + 1 }
        metrics.mark(REBUFFER_BEGAN, PlaybackSpans.TRACK_PLAYBACK)
    }

    /** The player has buffer again. */
    fun onReady() {
        if (rebufferingSince == 0L) return
        rebufferMs.update { it + (elapsedRealtime() - rebufferingSince) }
        rebufferingSince = 0L
        metrics.mark(REBUFFER_ENDED, PlaybackSpans.TRACK_PLAYBACK)
    }

    /** A seek the user waited for — the same ones [PlaybackSpans] times. */
    fun onSeek() {
        lastSeekAt = elapsedRealtime()
        seeks.update { it + 1 }
    }

    /** A stream reopened after going quiet. Called from a media3 loader thread. */
    fun onIdleReconnect() {
        reconnectsIdle.update { it + 1 }
        metrics.mark(RECONNECT_IDLE, PlaybackSpans.TRACK_PLAYBACK)
    }

    /** The stall watchdog gave up waiting and re-prepared the player. */
    fun onStallRecovery() {
        reconnectsStall.update { it + 1 }
        metrics.mark(RECONNECT_STALL, PlaybackSpans.TRACK_PLAYBACK)
    }

    /** The item finished, or the queue moved off it, or playback was stopped. */
    fun onItemEnded(end: String) = report(end)

    /** The process is going away. Nothing is reported: an unstopped trace is never sent. */
    fun onAbandoned() {
        open?.abandon()
        open = null
        mediaId = null
    }

    private fun report(end: String) {
        val handle = open ?: return
        onReady()
        handle.end(
            attributes = mapOf("source" to source, "end" to end),
            metrics = mapOf(
                "rebuffers" to rebuffers.value,
                "rebuffer_ms" to rebufferMs.value,
                "seeks" to seeks.value,
                "reconnects_idle" to reconnectsIdle.value,
                "reconnects_stall" to reconnectsStall.value,
                "played_ms" to (elapsedRealtime() - startedAt),
            ),
        )
        open = null
        mediaId = null
    }

    internal companion object {

        /**
         * How long after a seek the refill that follows it is the seek's own, not a rebuffer. The
         * player always reloads from the new position; counting that would make scrubbing read as
         * a network problem.
         */
        const val SEEK_SETTLE_MS = 250L

        const val END_ENDED = "ended"
        const val END_NEXT = "next"
        const val END_STOPPED = "stopped"

        private const val REBUFFER_BEGAN = "rebuffer began"
        private const val REBUFFER_ENDED = "rebuffer ended"
        private const val RECONNECT_IDLE = "idle reconnect"
        private const val RECONNECT_STALL = "stall recovery"
    }
}
