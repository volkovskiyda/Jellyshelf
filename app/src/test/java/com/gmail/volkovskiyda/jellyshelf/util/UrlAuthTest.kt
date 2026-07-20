package com.gmail.volkovskiyda.jellyshelf.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlAuthTest {

    private val jellyfinThumb = "http://server:8096/Items/abc/Images/Primary?maxWidth=480"

    @Test
    fun `appends api key to jellyfin image urls`() {
        assertEquals(
            "$jellyfinThumb&api_key=KEY",
            authorizedImageUrl(jellyfinThumb, "KEY"),
        )
    }

    @Test
    fun `uses question mark when the url has no query yet`() {
        assertEquals(
            "http://server:8096/Items/abc/Images/Primary?api_key=KEY",
            authorizedImageUrl("http://server:8096/Items/abc/Images/Primary", "KEY"),
        )
    }

    @Test
    fun `passes youtube cdn thumbnails through untouched`() {
        val cdn = "https://i.ytimg.com/vi/abc/maxresdefault.jpg"
        assertEquals(cdn, authorizedImageUrl(cdn, "KEY"))
    }

    @Test
    fun `does not double-append to a legacy url that already carries a key`() {
        val legacy = "$jellyfinThumb&api_key=OLD"
        assertEquals(legacy, authorizedImageUrl(legacy, "NEW"))
    }

    @Test
    fun `null url or missing key pass through`() {
        assertNull(authorizedImageUrl(null, "KEY"))
        assertEquals(jellyfinThumb, authorizedImageUrl(jellyfinThumb, null))
        assertEquals(jellyfinThumb, authorizedImageUrl(jellyfinThumb, ""))
    }

    @Test
    fun `stripApiKey removes an embedded key and keeps other params`() {
        assertEquals(
            jellyfinThumb,
            stripApiKey("$jellyfinThumb&api_key=SECRET"),
        )
    }

    @Test
    fun `stripApiKey removes a leading key param`() {
        assertEquals(
            "http://server:8096/Items/abc/Images/Primary?maxWidth=480",
            stripApiKey("http://server:8096/Items/abc/Images/Primary?api_key=SECRET&maxWidth=480"),
        )
    }

    @Test
    fun `stripApiKey drops the query entirely when the key was the only param`() {
        assertEquals(
            "http://server:8096/Items/abc/Images/Primary",
            stripApiKey("http://server:8096/Items/abc/Images/Primary?api_key=SECRET"),
        )
    }

    @Test
    fun `stripApiKey leaves keyless urls untouched`() {
        assertEquals(jellyfinThumb, stripApiKey(jellyfinThumb))
        assertNull(stripApiKey(null))
    }

    @Test
    fun `escapeLikePattern escapes wildcards and backslashes`() {
        assertEquals("100\\% legit", escapeLikePattern("100% legit"))
        assertEquals("a\\_b", escapeLikePattern("a_b"))
        assertEquals("c\\\\d", escapeLikePattern("c\\d"))
        assertEquals("plain", escapeLikePattern("plain"))
    }
}
