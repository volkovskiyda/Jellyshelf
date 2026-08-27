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

    /**
     * The stale-id case: the row looks healthy, only the stream 404s. Measured against a real
     * server on 2026-08-27 — 18 retries for one dead id, none of which could ever have worked.
     */
    @Test
    fun `a 404 on the stream means the item is gone from the server`() {
        assertTrue(
            isMissingOnServerCode(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, responseCode = 404),
        )
        assertTrue(
            isMissingOnServerCode(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, responseCode = 410),
        )
    }

    /**
     * Other HTTP failures must **not** be read as a stale id: a 401 is a signed-out session and a
     * 5xx is the server having a bad moment. Both are worth retrying or surfacing; neither means
     * the item has gone, and treating them as stale would fire a pointless sync every time.
     */
    @Test
    fun `other http statuses are not a stale id`() {
        listOf(401, 403, 429, 500, 502, 503).forEach { code ->
            assertFalse(
                "HTTP $code must not be read as a missing item",
                isMissingOnServerCode(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, code),
            )
        }
    }

    /**
     * A network error carries no status at all. Without the null guard this would depend on
     * whatever the comparison did with a missing code.
     */
    @Test
    fun `an error with no http status is not a stale id`() {
        assertFalse(
            isMissingOnServerCode(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null),
        )
        assertFalse(isMissingOnServerCode(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, null))
    }

    /**
     * The two decisions must stay disjoint. A stale id routed into the transcode fallback would ask
     * the *same absent item* for an HLS rendition — the fallback URL is built from the same id.
     */
    @Test
    fun `a stale id is not a decode failure`() {
        assertFalse(isDecodeFailureCode(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
    }
}
