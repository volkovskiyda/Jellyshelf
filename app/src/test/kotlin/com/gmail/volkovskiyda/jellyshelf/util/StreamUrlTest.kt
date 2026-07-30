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
        assertFalse(url, url.contains("api_key"))
    }

    @Test
    fun `appends the credential when it is to travel in the query`() {
        assertEquals(
            "$server/Videos/$itemId/stream?static=true&api_key=TOKEN",
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

        assertEquals("$server/Videos/$itemId/stream?static=true&api_key=a%2Bb%26c%3Dd%2Fe", url)
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
     * The transcoding fallback URL: bare on purpose. No credential (it travels as a header) and
     * no codec constraints — an empty supported-codec list is what forces Jellyfin to transcode
     * instead of stream-copying the very codec the device just failed to decode.
     */
    @Test
    fun `hls fallback url is bare of credentials and codec constraints`() {
        assertEquals("$server/Videos/$itemId/main.m3u8", Playback.hlsUrl(server, itemId))
        assertEquals("$server/Videos/$itemId/main.m3u8", Playback.hlsUrl("$server/", itemId))
    }
}
