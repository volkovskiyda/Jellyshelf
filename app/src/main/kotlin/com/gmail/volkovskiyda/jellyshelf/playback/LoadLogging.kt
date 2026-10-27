@file:androidx.annotation.OptIn(UnstableApi::class)

package com.gmail.volkovskiyda.jellyshelf.playback

import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.util.EventLogger
import com.gmail.volkovskiyda.jellyshelf.util.stripCredentials
import java.io.IOException

/**
 * What the *loader* and the player are doing, for a stall that no error ever reaches.
 *
 * **Why this exists.** A video left paused for a long time can come back to a buffering spinner that
 * never clears, and nothing in the app could say why. `onPlayerError` only fires once ExoPlayer has
 * given up — and a retry that makes a little progress resets its error count
 * (`ProgressiveMediaPeriod.onLoadError` → `Loader.createRetryAction(resetErrorCount = madeProgress)`),
 * so a stream that trickles can spin indefinitely without ever raising one. The load lifecycle is
 * where that is visible, and media3 publishes it only through [AnalyticsListener].
 *
 * **What each field answers.** `loadDurationMs` on a failed load separates the two failure shapes
 * that look identical on screen: a socket the server closed cleanly fails in single-digit
 * milliseconds, while one that died silently burns the media client's whole 8 s socket timeout
 * (`MEDIA_TIMEOUT_MILLIS`, see `di/AppModule.kt`) before it does. `bytesLoaded` says whether the
 * stream was trickling or dead. `isLoading` false while buffering says the loader is not even trying,
 * which would point at the player's state machine rather than at the network.
 *
 * **On every build type**, into a [PlaybackTrail] — which is logcat in debug and, in release, the
 * buffer a [StallReporter] hands to Crashlytics. This was debug-only until 2026-10-27, when the one
 * stall that mattered happened on a release install and left no trace anywhere (see the trail's
 * KDoc). media3's own [EventLogger] stays debug-only: it writes straight to `android.util.Log` with
 * no gating, and one `V` line per decoder event is a lot of logcat to hand a release install; the
 * few state lines a stall is read by are [StateLogger]'s, which the trail carries on every build.
 *
 * [onStuck] is called on the application looper when [LongBufferingRule] says a buffering spell has
 * gone on long enough to report — see [StallReporter].
 *
 * Top-level rather than a member of [PlaybackService] for the same reason `checkOnApplicationLooper`
 * is: detekt's `TooManyFunctions` threshold for a class is 11 and that service is at it.
 */
internal fun ExoPlayer.installPlaybackLogging(trail: PlaybackTrail, isDebug: Boolean, onStuck: () -> Unit) {
    if (isDebug) addAnalyticsListener(EventLogger())
    addAnalyticsListener(LoadLogger(trail))
    addAnalyticsListener(StateLogger(trail))
    addAnalyticsListener(BufferingLogger(this, trail, onStuck = onStuck))
}

/**
 * The load half of [installPlaybackLogging] — every line into the trail (and so under
 * `Playback.TAG` in debug), so one `adb logcat -s Playback` follows a stall from the resume that
 * triggered it to the exception that ended it.
 *
 * Every URI goes through [stripCredentials]. A direct stream URL carries no credential today (the
 * token travels as a header — `Playback.streamUrl(..., credential = null)`), but an HLS fallback URL
 * is built with query parameters and these lines now leave the device in a crash report, so
 * scrubbing is the rule rather than an assessment of this one call site.
 */
private class LoadLogger(private val trail: PlaybackTrail) : AnalyticsListener {

    override fun onLoadStarted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        retryCount: Int,
    ) {
        // The four-argument overload: its three-argument sibling is deprecated in 1.11.0, and
        // retryCount is the one field that distinguishes a first attempt from ExoPlayer grinding
        // through its three retries — which is exactly what a stall looks like from here.
        trail.d(
            "load start ${loadEventInfo.scrubbedUri()} ${eventTime.window()} " +
                "${loadEventInfo.range()} retry=$retryCount",
        )
    }

    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        trail.d("load done ${loadEventInfo.progress()} ${eventTime.window()}")
    }

    override fun onLoadCanceled(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        // A cancel is ordinary — a seek, a stop, an item swap all cancel loads in flight — but an
        // unexplained one while a spinner sits is the signal that something above cancelled the
        // load rather than the network failing it.
        trail.d("load canceled ${loadEventInfo.progress()} ${eventTime.window()}")
    }

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean,
    ) {
        // Warn, not error: media3's own KDoc for this callback is explicit that a load error does
        // not mean playback has failed — the player usually recovers — so this must not read like a
        // terminal failure in the log.
        trail.w(
            "load error ${loadEventInfo.progress()} ${eventTime.window()} wasCanceled=$wasCanceled " +
                "${error.javaClass.simpleName}: ${error.message}${error.causeChain()}",
        )
    }

    override fun onIsLoadingChanged(eventTime: AnalyticsListener.EventTime, isLoading: Boolean) {
        trail.d("isLoading=$isLoading")
    }

    /** The load's URI with any embedded credential removed — see the class KDoc. */
    private fun LoadEventInfo.scrubbedUri(): String? = stripCredentials(uri.toString())

    /**
     * Which queue item the load belongs to, and — when that is not the item playing — which one is.
     *
     * A load for a window *after* the current one is the preload of the next item (`PlaybackService`'s
     * `PRELOAD_TARGET_DURATION_US`), and that is the one load no other line can identify: it starts
     * while the previous video plays, under that video's lines, and whatever it did or did not finish
     * is what the queue advance then inherits. The 2026-10-08 paused-skip stall was exactly that — a
     * preload that stopped 1.5 MB in and never prepared, read as a cancel thirty seconds later with
     * nothing to say which item it had been for. A load for the window *before* is the old item's,
     * cancelled by the move; both are printed the same way and told apart by the index.
     */
    private fun AnalyticsListener.EventTime.window(): String =
        if (windowIndex == currentWindowIndex) "window=$windowIndex" else "window=$windowIndex cur=$currentWindowIndex"

    /**
     * The byte range the load asked for. The position is the diagnostic half: a progressive MP4 whose
     * index sits at its end makes the extractor reopen the stream near the file size, and a load that
     * starts at a huge offset is that reopen rather than a restart from the top.
     */
    private fun LoadEventInfo.range(): String {
        val length = dataSpec.length.takeIf { it != C.LENGTH_UNSET.toLong() }?.toString() ?: "unset"
        return "pos=${dataSpec.position} len=$length"
    }

    /**
     * How far a load got and how long it took, which together say whether a stream was dead or merely
     * slow. Shared by the three callbacks that report a finished attempt.
     */
    private fun LoadEventInfo.progress(): String =
        "${scrubbedUri()} bytes=$bytesLoaded ms=$loadDurationMs"

    /**
     * The exception classes wrapping the real cause, innermost last.
     *
     * **This is the whole diagnostic value of the error line.** media3 wraps a datasource failure in a
     * load error before it reaches a listener, so the outermost class is always the generic wrapper; the
     * cause chain is where a Ktor `SocketTimeoutException` can be told apart from a
     * `ConnectException`, a `ResourceNotFoundException`-shaped `InvalidResponseCodeException`, or an
     * `InterruptedIOException` from a cancelled read. Empty when the exception has no cause.
     *
     * `javaClass.simpleName` rather than `this::class.simpleName`: an anonymous or synthetic exception
     * class makes the Kotlin reflection property null, and a blank name is the one thing this must not
     * print.
     */
    private fun IOException.causeChain(): String {
        val chain = generateSequence(cause) { it.cause }
            .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
        return if (chain.isEmpty()) "" else " (caused by $chain)"
    }
}

/**
 * The player's state machine, one line per change: the handful of [EventLogger] lines a stall is
 * read by, so that a release trail says what the player was doing between the loads. Which item the
 * queue moved to and why, whether it was asked to play, where it jumped, when it drew, and the one
 * error that ends it all — everything else EventLogger prints is decoder lifecycle, which a stall
 * never reaches.
 */
private class StateLogger(private val trail: PlaybackTrail) : AnalyticsListener {

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        trail.d("state ${stateName(state)}")
    }

    override fun onPlayWhenReadyChanged(eventTime: AnalyticsListener.EventTime, playWhenReady: Boolean, reason: Int) {
        trail.d("playWhenReady=$playWhenReady reason=$reason")
    }

    override fun onMediaItemTransition(eventTime: AnalyticsListener.EventTime, mediaItem: MediaItem?, reason: Int) {
        trail.d("item ${mediaItem?.mediaId} window=${eventTime.currentWindowIndex} reason=${transitionName(reason)}")
    }

    override fun onPositionDiscontinuity(
        eventTime: AnalyticsListener.EventTime,
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        trail.d(
            "discontinuity reason=$reason ${oldPosition.mediaItemIndex}@${oldPosition.positionMs}ms -> " +
                "${newPosition.mediaItemIndex}@${newPosition.positionMs}ms",
        )
    }

    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
        trail.d("first frame window=${eventTime.currentWindowIndex}")
    }

    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
        trail.w(error, "player error ${error.errorCodeName}")
    }

    private fun transitionName(reason: Int): String = when (reason) {
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "auto"
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "seek"
        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "repeat"
        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "playlist"
        else -> reason.toString()
    }
}

/** `Player.State` as the word EventLogger would print, for [ExoPlayer.describe] and [StateLogger]. */
internal fun stateName(state: Int): String = when (state) {
    Player.STATE_IDLE -> "IDLE"
    Player.STATE_BUFFERING -> "BUFFERING"
    Player.STATE_READY -> "READY"
    Player.STATE_ENDED -> "ENDED"
    else -> state.toString()
}

/**
 * The player's own view of a buffering spell that will not end, logged every [SNAPSHOT_INTERVAL_MS]
 * for as long as it lasts — the half of a stall that [LoadLogger] cannot see.
 *
 * The load lines say what the *network* is doing. This says what the player is waiting for: whether
 * it is even asking to load, whether the item has been prepared yet (a prepared item has tracks; one
 * still waiting for its index has none, and `EventLogger` prints the same empty `tracks []` for both
 * a transition and an unprepared period), how much it has buffered, and whether anyone has asked it
 * to play — because a paused player in `STATE_BUFFERING` is a spell [StallWatchdog] deliberately does
 * not watch, and that is the one that sat for sixteen seconds on 2026-10-08.
 *
 * The same tick feeds [LongBufferingRule]: a spell that has kept the user waiting [LONG_BUFFERING_MS]
 * is reported through [onStuck], once per item. Waiting is counted from the later of the spell
 * starting and `playWhenReady` turning true, so a video paused mid-buffer and resumed is measured
 * from the resume, not from the pause.
 *
 * Posted on the player's application looper rather than a coroutine scope: this is an analytics
 * listener with no scope of its own, and the looper is what every other player read here already
 * runs on. The job is a single pending message, cancelled whenever the state stops being buffering
 * and rearmed whenever it starts again, so an item that buffers for a second costs one post.
 */
private class BufferingLogger(
    private val player: ExoPlayer,
    private val trail: PlaybackTrail,
    private val rule: LongBufferingRule = LongBufferingRule(),
    private val onStuck: () -> Unit,
) : AnalyticsListener {

    private val handler = Handler(player.applicationLooper)
    private var sinceMs = 0L
    private var wantedSinceMs = 0L

    private val snapshot = object : Runnable {
        override fun run() {
            if (player.playbackState != Player.STATE_BUFFERING) return
            val now = SystemClock.elapsedRealtime()
            trail.d("buffering ${(now - sinceMs) / MILLIS_PER_SECOND}s: ${player.describe()}")
            if (rule.shouldReport(now - maxOf(sinceMs, wantedSinceMs), player.playWhenReady)) onStuck()
            handler.postDelayed(this, SNAPSHOT_INTERVAL_MS)
        }
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        handler.removeCallbacks(snapshot)
        if (state != Player.STATE_BUFFERING) return
        sinceMs = SystemClock.elapsedRealtime()
        handler.postDelayed(snapshot, SNAPSHOT_INTERVAL_MS)
    }

    override fun onPlayWhenReadyChanged(eventTime: AnalyticsListener.EventTime, playWhenReady: Boolean, reason: Int) {
        if (playWhenReady) wantedSinceMs = SystemClock.elapsedRealtime()
    }

    /**
     * A snapshot on the move itself, not only after the first interval: the state is usually already
     * `BUFFERING` from the seek that preceded the move (the skip to the end seeks first), so no state
     * change marks the moment the new item took over — and what it looked like *then* is the baseline
     * the later snapshots are read against.
     */
    override fun onMediaItemTransition(eventTime: AnalyticsListener.EventTime, mediaItem: MediaItem?, reason: Int) {
        rule.onItemChanged()
        if (player.playbackState != Player.STATE_BUFFERING) return
        trail.d("buffering into item: ${player.describe()}")
    }
}

/** Five seconds: often enough to show a trickle, rare enough that a long pause stays readable. */
private const val SNAPSHOT_INTERVAL_MS = 5_000L

private const val MILLIS_PER_SECOND = 1_000L
