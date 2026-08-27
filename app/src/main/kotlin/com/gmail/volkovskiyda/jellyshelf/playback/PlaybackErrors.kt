package com.gmail.volkovskiyda.jellyshelf.playback

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException

/**
 * Whether a Jellyfin HLS transcode is worth trying after this error: true when the device could
 * not decode — or even parse — what direct play handed it, which re-encoding to the server's
 * default H.264/AAC fixes. Anything else (network, HTTP status, DRM) would fail transcoded just
 * the same, so those errors should surface instead.
 *
 * Shared by [PlaybackService] (decides the retry) and the player screen (phrases the toast).
 */
fun PlaybackException.isDecodeFailure(): Boolean = isDecodeFailureCode(errorCode)

/**
 * [isDecodeFailure] over the raw code, separable because constructing a [PlaybackException]
 * stamps a timestamp via android.os.SystemClock — which a JVM unit test cannot call.
 */
internal fun isDecodeFailureCode(errorCode: Int): Boolean = when (errorCode) {
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    -> true
    else -> false
}

/**
 * Whether the server says it no longer has this item — i.e. our stored `jellyfinItemId` has gone
 * stale, and no amount of retrying will help.
 *
 * **This is invisible without it.** A row whose id has gone stale looks perfectly healthy: title,
 * duration, channel and thumbnail all come from the local row and render fine. Only the stream
 * request fails, so the whole symptom is a player that sits at 0:00 while the load is retried.
 * Observed against a real server on 2026-08-27: 18 requests for one dead id across 97 seconds,
 * every one answered `ResourceNotFoundException`, with nothing anywhere saying why.
 *
 * `410 Gone` is treated the same as `404`: Jellyfin answers a missing item with 404, but an id that
 * a proxy or a future version reports as deliberately removed means exactly the same thing to us.
 *
 * Deliberately **not** part of [isDecodeFailure]: the HLS transcode this app falls back to is
 * addressed by the same item id, so it would ask the same absent item the same question.
 */
fun PlaybackException.isMissingOnServer(): Boolean =
    isMissingOnServerCode(errorCode, invalidResponseCode())

/**
 * [isMissingOnServer] over raw values, separable for the same reason [isDecodeFailureCode] is: a
 * JVM unit test cannot construct a [PlaybackException].
 */
internal fun isMissingOnServerCode(errorCode: Int, responseCode: Int?): Boolean =
    errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS &&
        (responseCode == HTTP_NOT_FOUND || responseCode == HTTP_GONE)

/**
 * The HTTP status buried in the cause chain, or null when this error did not come from one.
 *
 * The chain is walked rather than the immediate cause read: media3 wraps a datasource exception in
 * a load error before it reaches the listener, so the [InvalidResponseCodeException] is never the
 * outermost cause. Verified to be the type our datasource throws — `KtorDataSource` (media3
 * 1.11.0) raises `HttpDataSource.InvalidResponseCodeException` for a non-2xx response, same as the
 * OkHttp and default sources it replaced.
 */
private fun PlaybackException.invalidResponseCode(): Int? =
    generateSequence(cause) { it.cause }
        .filterIsInstance<InvalidResponseCodeException>()
        .firstOrNull()
        ?.responseCode

private const val HTTP_NOT_FOUND = 404
private const val HTTP_GONE = 410
