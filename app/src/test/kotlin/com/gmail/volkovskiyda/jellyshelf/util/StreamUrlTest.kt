package com.gmail.volkovskiyda.jellyshelf.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The stream URL handed to external players. Whether the credential appears in it at all is the
 * whole point of the header handoff, so both shapes are pinned here — host-side, since building
 * the URL needs no Android (the Intent around it does, and is not covered here).
 */
class StreamUrlTest {

    private val server = "https://jellyfin.example.org"
    private val itemId = "420a0d37859837117add1115b61a7368"

    @Test
    fun `omits the credential entirely when it is to travel as a header`() {
        val url = Playback.streamUrl(server, itemId, credential = null)

        assertEquals("$server/Videos/$itemId/stream?static=true", url)
        assertFalse(url, url.contains(CREDENTIAL_PARAM, ignoreCase = true))
    }

    /**
     * `ApiKey`, not the `api_key` Jellyfin accepted up to 12.0 — the old spelling is now ignored,
     * which on a server that requires auth for streams reads as an anonymous request.
     */
    @Test
    fun `appends the credential when it is to travel in the query`() {
        assertEquals(
            "$server/Videos/$itemId/stream?static=true&ApiKey=TOKEN",
            Playback.streamUrl(server, itemId, credential = "TOKEN"),
        )
    }

    /**
     * The inconsistency this fixes: image URLs already URL-encoded their credential while the
     * stream URL interpolated it raw, so a token containing a `+` or `&` would have produced a
     * URL that silently authenticated as something else — or not at all.
     */
    @Test
    fun `url-encodes a credential with reserved characters`() {
        val url = Playback.streamUrl(server, itemId, credential = "a+b&c=d/e")

        assertEquals("$server/Videos/$itemId/stream?static=true&ApiKey=a%2Bb%26c%3Dd%2Fe", url)
    }

    @Test
    fun `treats a blank credential as nothing to append`() {
        assertEquals(
            "$server/Videos/$itemId/stream?static=true",
            Playback.streamUrl(server, itemId, credential = ""),
        )
    }

    @Test
    fun `tolerates a trailing slash on the server url`() {
        assertEquals(
            "$server/Videos/$itemId/stream?static=true",
            Playback.streamUrl("$server/", itemId, credential = null),
        )
    }

    /**
     * The transcoding fallback URL. Still no credential — it travels as a header — but the rest
     * of the query is required, and a *bare* `main.m3u8` request does not work at all: Jellyfin
     * appends `&runtimeTicks=…` to the playlist request's query when it writes each segment URL,
     * so with no query there is no `?` and every segment 400s as an unroutable path. The codec
     * pair matters just as much — without it the server stream-copies the very video codec the
     * device just failed to decode. See [Playback.hlsUrl] for the full account; this pins the
     * exact shape a real server was seen to accept.
     */
    @Test
    fun `hls fallback url carries transcode params but no credential`() {
        val expected = "$server/Videos/$itemId/main.m3u8?mediaSourceId=$itemId" +
            "&playSessionId=SESSION&videoCodec=h264&audioCodec=aac" +
            "&videoBitrate=8000000&audioBitrate=192000"

        assertEquals(expected, Playback.hlsUrl(server, itemId, playSessionId = "SESSION"))
        assertEquals(expected, Playback.hlsUrl("$server/", itemId, playSessionId = "SESSION"))
        assertFalse(expected, expected.contains(CREDENTIAL_PARAM, ignoreCase = true))
    }
}
