package com.gmail.volkovskiyda.jellyshelf.playback

import com.gmail.volkovskiyda.jellyshelf.util.Metrics
import com.gmail.volkovskiyda.jellyshelf.util.Spans
import kotlin.math.absoluteValue

/**
 * The three playback spans that begin in one callback and end in another, and the rules that close
 * them.
 *
 * A tap, a queue advance and a seek are all waits that end at a rendered frame, and the player
 * reports that frame in one place for all three. Keeping the bookkeeping here rather than in
 * [PlaybackService] is partly that the service is already at detekt's function ceiling, and mostly
 * that these rules are worth testing on their own — every way of getting them wrong produces a
 * plausible number rather than a failure.
 *
 * **A rendered frame closes everything open.** Not the newest, not one by priority: all of them.
 * Overlap is rare but real — tapping next before the first item has drawn, scrubbing while an
 * advance is still buffering — and a span left open then would not be closed until the *next*
 * frame, which can be minutes away on a paused player. A span reporting minutes because nobody
 * closed it is the exact failure the player's tracing has always been written to avoid, and it
 * looks like a catastrophic regression rather than a bug.
 *
 * **Abandoning is not failing.** A span that will never see its frame — the queue emptied, the
 * service was torn down, a startup the user replaced by starting something else — is dropped
 * rather than reported. Its system-trace section closes (an open section is drawn as a slice
 * running to the end of the capture) while its Firebase trace is simply never stopped, and an
 * unstopped trace is never reported at all.
 *
 * Confined to the player's application thread, which is the service's main thread — every caller
 * below is a `Player.Listener` callback or a session callback on that thread.
 */
internal class PlaybackSpans(private val metrics: Metrics) {

    private var startup: Metrics.OpenSpan? = null
    private var transition: Metrics.OpenSpan? = null
    private var seek: Metrics.OpenSpan? = null

    private var transitionReason = REASON_AUTO
    private var seekDistanceMs = 0L

    /**
     * A controller handed the session a queue: the closest observable moment to the user's tap.
     *
     * Replacing a pending startup abandons it — the user gave up on that one and began another.
     */
    fun startupBegan(cookie: Int) {
        startup?.abandon()
        startup = metrics.begin(Spans.PLAYER_STARTUP, track = TRACK_PLAYBACK, cookie = cookie)
    }

    /** Resolving the ids into playable items, inside the startup it is the first slice of. */
    suspend fun <T> resolving(block: suspend () -> T): T =
        metrics.suspendSpan(Spans.PLAYER_RESOLVE) { block() }

    /**
     * The queue moved to another item, for a reason that is not the queue's first item arriving.
     *
     * A pending startup is abandoned here rather than left to the frame: an advance before the
     * first item ever drew means the user moved on, and whatever that startup was measuring is no
     * longer a wait anybody sat through.
     */
    fun transitionBegan(cookie: Int, reason: String) {
        startup?.abandon()
        startup = null
        transition?.abandon()
        transitionReason = reason
        transition = metrics.begin(Spans.PLAYER_TRANSITION, track = TRACK_PLAYBACK, cookie = cookie)
    }

    /** A seek inside the item already playing. [distanceMs] is signed; the sign is the direction. */
    fun seekBegan(cookie: Int, distanceMs: Long) {
        seek?.abandon()
        seekDistanceMs = distanceMs
        seek = metrics.begin(Spans.PLAYER_SEEK, track = TRACK_PLAYBACK, cookie = cookie)
    }

    /**
     * The frame that ends whatever was being waited for.
     *
     * @param source `hls` for an item playing the server's transcode, `direct` otherwise — a
     *   startup that went through the transcode fallback naturally runs worse, and the two should
     *   not be averaged together.
     * @param demo the bundled sample clip, which plays from local storage and would only flatter
     *   the distribution. Its startup is dropped, the same way a demo sync is never traced.
     */
    fun firstFrame(source: String, demo: Boolean) {
        seek?.end(
            attributes = mapOf("direction" to if (seekDistanceMs < 0) BACK else FORWARD),
            metrics = mapOf("distance_ms" to seekDistanceMs.absoluteValue),
        )
        seek = null
        transition?.end(attributes = mapOf("reason" to transitionReason))
        transition = null
        if (demo) startup?.abandon() else startup?.end(attributes = mapOf("source" to source))
        startup = null
    }

    /** Nothing will render now: the queue emptied, the service is going away, playback failed. */
    fun abandonAll() {
        seek?.abandon()
        seek = null
        transition?.abandon()
        transition = null
        startup?.abandon()
        startup = null
    }

    internal companion object {

        /** Kotzilla groups this area's marks under its own track. */
        const val TRACK_PLAYBACK = "playback"

        const val REASON_AUTO = "auto"
        const val REASON_SEEK = "seek"
        const val REASON_REPEAT = "repeat"

        const val SOURCE_HLS = "hls"
        const val SOURCE_DIRECT = "direct"

        private const val BACK = "back"
        private const val FORWARD = "forward"
    }
}
