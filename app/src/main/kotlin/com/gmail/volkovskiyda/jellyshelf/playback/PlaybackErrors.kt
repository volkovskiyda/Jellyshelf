package com.gmail.volkovskiyda.jellyshelf.playback

import androidx.media3.common.PlaybackException

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
