package com.gmail.volkovskiyda.jellyshelf.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YoutubeIdTest {

    @Test
    fun `extracts bracketed id from yt-dlp filename`() {
        assertEquals(
            "dQw4w9WgXcQ",
            YoutubeId.fromPath("/media/youtube/Some Title [dQw4w9WgXcQ].mp4"),
        )
    }

    @Test
    fun `prefers last bracketed group when several are present`() {
        assertEquals(
            "AAAAAAAAAA2",
            YoutubeId.fromPath("/m/Clip [AAAAAAAAAA1] extra [AAAAAAAAAA2].mkv"),
        )
    }

    @Test
    fun `falls back to last standalone 11-char token in the stem`() {
        assertEquals("dQw4w9WgXcQ", YoutubeId.fromPath("/media/dQw4w9WgXcQ.mp4"))
    }

    @Test
    fun `handles windows-style separators`() {
        assertEquals("dQw4w9WgXcQ", YoutubeId.fromPath("""C:\media\Title [dQw4w9WgXcQ].mp4"""))
    }

    @Test
    fun `returns null for null, blank and idless paths`() {
        assertNull(YoutubeId.fromPath(null))
        assertNull(YoutubeId.fromPath(""))
        assertNull(YoutubeId.fromPath("/media/clip.mp4"))
    }

    @Test
    fun `fileNameFromPath strips directories`() {
        assertEquals("clip.mp4", fileNameFromPath("/a/b/clip.mp4"))
        assertEquals("clip.mp4", fileNameFromPath("""C:\a\clip.mp4"""))
        assertNull(fileNameFromPath("/a/b/"))
        assertNull(fileNameFromPath(null))
    }
}
