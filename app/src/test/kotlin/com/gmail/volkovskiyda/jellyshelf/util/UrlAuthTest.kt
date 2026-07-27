package com.gmail.volkovskiyda.jellyshelf.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlAuthTest {

    private val server = "http://server:8096"
    private val jellyfinThumb = "$server/Items/abc/Images/Primary?maxWidth=480"

    @Test
    fun `appends api key to jellyfin image urls on the configured server`() {
        assertEquals(
            "$jellyfinThumb&api_key=KEY",
            authorizedImageUrl(jellyfinThumb, server, "KEY"),
        )
    }

    @Test
    fun `uses question mark when the url has no query yet`() {
        assertEquals(
            "$server/Items/abc/Images/Primary?api_key=KEY",
            authorizedImageUrl("$server/Items/abc/Images/Primary", server, "KEY"),
        )
    }

    @Test
    fun `tolerates a trailing slash and padding on the configured server url`() {
        assertEquals(
            "$jellyfinThumb&api_key=KEY",
            authorizedImageUrl(jellyfinThumb, " $server/ ", "KEY"),
        )
    }

    @Test
    fun `never appends the key to a jellyfin-shaped url on another host`() {
        val foreign = "https://evil.example/Items/abc/Images/Primary"
        assertEquals(foreign, authorizedImageUrl(foreign, server, "KEY"))
    }

    @Test
    fun `never appends the key when no server is configured`() {
        assertEquals(jellyfinThumb, authorizedImageUrl(jellyfinThumb, null, "KEY"))
        assertEquals(jellyfinThumb, authorizedImageUrl(jellyfinThumb, " ", "KEY"))
    }

    @Test
    fun `url-encodes the api key`() {
        assertEquals(
            "$jellyfinThumb&api_key=K%26Y%3D1",
            authorizedImageUrl(jellyfinThumb, server, "K&Y=1"),
        )
    }

    @Test
    fun `passes youtube cdn thumbnails through untouched`() {
        val cdn = "https://i.ytimg.com/vi/abc/maxresdefault.jpg"
        assertEquals(cdn, authorizedImageUrl(cdn, server, "KEY"))
    }

    @Test
    fun `does not double-append to a legacy url that already carries a key`() {
        val legacy = "$jellyfinThumb&api_key=OLD"
        assertEquals(legacy, authorizedImageUrl(legacy, server, "NEW"))
    }

    @Test
    fun `null url or missing key pass through`() {
        assertNull(authorizedImageUrl(null, server, "KEY"))
        assertEquals(jellyfinThumb, authorizedImageUrl(jellyfinThumb, server, null))
        assertEquals(jellyfinThumb, authorizedImageUrl(jellyfinThumb, server, ""))
    }

    @Test
    fun `stripCredentials removes an embedded key and keeps other params`() {
        assertEquals(
            jellyfinThumb,
            stripCredentials("$jellyfinThumb&api_key=SECRET"),
        )
    }

    @Test
    fun `stripCredentials removes a leading key param`() {
        assertEquals(
            "$server/Items/abc/Images/Primary?maxWidth=480",
            stripCredentials("$server/Items/abc/Images/Primary?api_key=SECRET&maxWidth=480"),
        )
    }

    @Test
    fun `stripCredentials drops the query entirely when the key was the only param`() {
        assertEquals(
            "$server/Items/abc/Images/Primary",
            stripCredentials("$server/Items/abc/Images/Primary?api_key=SECRET"),
        )
    }

    @Test
    fun `stripCredentials leaves keyless urls untouched`() {
        assertEquals(jellyfinThumb, stripCredentials(jellyfinThumb))
        assertNull(stripCredentials(null))
    }

    /** A URL from another Jellyfin client can spell the credential param differently. */
    @Test
    fun `stripCredentials removes every spelling of the credential param`() {
        assertEquals(jellyfinThumb, stripCredentials("$jellyfinThumb&ApiKey=SECRET"))
        assertEquals(jellyfinThumb, stripCredentials("$jellyfinThumb&API_KEY=SECRET"))
        assertEquals(jellyfinThumb, stripCredentials("$jellyfinThumb&X-Emby-Token=SECRET"))
    }

    /** The value is what must not survive; a param merely *containing* the word is not one. */
    @Test
    fun `stripCredentials keeps params that only look like a credential`() {
        assertEquals(
            "$jellyfinThumb&api_key_hint=none",
            stripCredentials("$jellyfinThumb&api_key_hint=none"),
        )
    }

    @Test
    fun `escapeLikePattern escapes wildcards and backslashes`() {
        assertEquals("100\\% legit", escapeLikePattern("100% legit"))
        assertEquals("a\\_b", escapeLikePattern("a_b"))
        assertEquals("c\\\\d", escapeLikePattern("c\\d"))
        assertEquals("plain", escapeLikePattern("plain"))
    }
}
