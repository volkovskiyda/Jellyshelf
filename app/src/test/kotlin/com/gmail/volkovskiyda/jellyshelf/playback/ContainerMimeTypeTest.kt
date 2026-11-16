package com.gmail.volkovskiyda.jellyshelf.playback

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [containerMimeType]: the container named on each in-app media item, which is what keeps
 * media3 from parsing every extension-less stream URL of a queue on the main thread to guess one.
 */
class ContainerMimeTypeTest {

    @Test
    fun `names the containers the library is made of`() {
        assertEquals(MimeTypes.VIDEO_MP4, containerMimeType("talk.mp4"))
        assertEquals(MimeTypes.VIDEO_MP4, containerMimeType("talk.m4v"))
        assertEquals(MimeTypes.VIDEO_MATROSKA, containerMimeType("talk.mkv"))
        assertEquals(MimeTypes.VIDEO_WEBM, containerMimeType("talk.webm"))
    }

    @Test
    fun `reads the extension whatever its case and however many dots precede it`() {
        assertEquals(MimeTypes.VIDEO_MATROSKA, containerMimeType("Talk.Part.2.MKV"))
    }

    /** Unnamed means media3 infers it from the URL, as it did for every item before. */
    @Test
    fun `leaves an unknown or missing extension unnamed`() {
        assertNull(containerMimeType("talk.avi"))
        assertNull(containerMimeType("talk"))
        assertNull(containerMimeType(""))
    }
}
