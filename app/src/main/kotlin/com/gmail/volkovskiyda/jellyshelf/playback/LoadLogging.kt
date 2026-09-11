@file:androidx.annotation.OptIn(UnstableApi::class)

package com.gmail.volkovskiyda.jellyshelf.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.util.EventLogger
import com.gmail.volkovskiyda.jellyshelf.util.Playback
import com.gmail.volkovskiyda.jellyshelf.util.stripCredentials
import timber.log.Timber
import java.io.IOException

/**
 * Debug-only: what the *loader* is doing, for a stall that no error ever reaches.
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
 * Paired with media3's own [EventLogger], which covers states, timelines, tracks and decoder
 * lifecycle but logs nothing about a load except its error — so the two do not overlap except there,
 * where this one adds the byte count, the duration and the cause chain.
 *
 * **Debug-only, deliberately.** [EventLogger] writes through `android.util.Log` with no build
 * gating of its own, and one line per 1 MB load chunk is a lot of logcat to hand a release install.
 * `Timber` would drop this app's own lines in release anyway (no tree is planted —
 * `JellyshelfApplication`), but the gate is what keeps `EventLogger` out as well.
 *
 * Top-level rather than a member of [PlaybackService] for the same reason `checkOnApplicationLooper`
 * is: detekt's `TooManyFunctions` threshold for a class is 11 and that service is at it.
 */
internal fun ExoPlayer.logLoadsInDebug(isDebug: Boolean) {
    if (!isDebug) return
    addAnalyticsListener(EventLogger())
    addAnalyticsListener(LoadLogger())
}

/**
 * The load half of [logLoadsInDebug] — every line tagged [Playback.TAG], so one
 * `adb logcat -s Playback` follows a stall from the resume that triggered it to the exception that
 * ended it.
 *
 * Every URI goes through [stripCredentials]. A direct stream URL carries no credential today (the
 * token travels as a header — `Playback.streamUrl(..., credential = null)`), but an HLS fallback URL
 * is built with query parameters and logs are readable by other apps on a dev device, so scrubbing
 * is the habit rather than an assessment of this one call site.
 */
private class LoadLogger : AnalyticsListener {

    override fun onLoadStarted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        retryCount: Int,
    ) {
        // The four-argument overload: its three-argument sibling is deprecated in 1.11.0, and
        // retryCount is the one field that distinguishes a first attempt from ExoPlayer grinding
        // through its three retries — which is exactly what a stall looks like from here.
        Timber.tag(Playback.TAG).d("load start ${loadEventInfo.scrubbedUri()} retry=$retryCount")
    }

    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        Timber.tag(Playback.TAG).d("load done ${loadEventInfo.progress()}")
    }

    override fun onLoadCanceled(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        // A cancel is ordinary — a seek, a stop, an item swap all cancel loads in flight — but an
        // unexplained one while a spinner sits is the signal that something above cancelled the
        // load rather than the network failing it.
        Timber.tag(Playback.TAG).d("load canceled ${loadEventInfo.progress()}")
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
        Timber.tag(Playback.TAG).w(
            "load error ${loadEventInfo.progress()} wasCanceled=$wasCanceled " +
                "${error.javaClass.simpleName}: ${error.message}${error.causeChain()}",
        )
    }

    override fun onIsLoadingChanged(eventTime: AnalyticsListener.EventTime, isLoading: Boolean) {
        Timber.tag(Playback.TAG).d("isLoading=$isLoading")
    }
}

/** The load's URI with any embedded credential removed — see [LoadLogger]. */
private fun LoadEventInfo.scrubbedUri(): String? = stripCredentials(uri.toString())

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
