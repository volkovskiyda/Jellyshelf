package com.gmail.volkovskiyda.jellyshelf.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transcode-or-surface decision behind the playback service's fallback: decode-class
 * failures retry as HLS, everything else must surface. Tested over raw codes because
 * [PlaybackException]'s constructor stamps a SystemClock time the JVM can't provide.
 */
class PlaybackErrorsTest {

    @Test
    fun `decode and container failures trigger the transcode fallback`() {
        listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        ).forEach { code -> assertTrue("code $code should fall back", isDecodeFailureCode(code)) }
    }

    @Test
    fun `network, drm and generic failures surface instead of transcoding`() {
        listOf(
            PlaybackException.ERROR_CODE_UNSPECIFIED,
            PlaybackException.ERROR_CODE_REMOTE_ERROR,
            PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
        ).forEach { code -> assertFalse("code $code should surface", isDecodeFailureCode(code)) }
    }
}
