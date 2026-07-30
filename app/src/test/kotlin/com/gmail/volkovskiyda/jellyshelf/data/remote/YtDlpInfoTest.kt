package com.gmail.volkovskiyda.jellyshelf.data.remote

import com.gmail.volkovskiyda.jellyshelf.di.provideJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins [YtDlpInfo]'s field names against a trimmed real `--dump-single-json` payload, decoded
 * with the exact Json the app injects ([provideJson]). yt-dlp's snake_case names are the wire
 * contract: with ignoreUnknownKeys on, a renamed property here would silently drop metadata
 * rather than fail — this test is what turns that into a red bar.
 */
class YtDlpInfoTest {

    private val json = provideJson()

    @Test
    fun `a payload with chapters decodes every consumed field`() {
        val payload = """
            {
              "id": "dQw4w9WgXcQ",
              "title": "Sample video",
              "channel": "Sample Channel",
              "channel_id": "UC123",
              "uploader": "Sample Uploader",
              "uploader_id": "@sample",
              "duration": 753.96,
              "upload_date": "20260721",
              "tags": ["one", "two"],
              "categories": ["Music"],
              "description": "0:00 Intro\n2:00 Main",
              "thumbnail": "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg",
              "chapters": [
                {"start_time": 0.0, "end_time": 120.0, "title": "Intro"},
                {"start_time": 120.0, "end_time": 753.96, "title": "Main"}
              ],
              "formats": [{"format_id": "251"}],
              "extractor": "youtube",
              "epoch": 1753795200
            }
        """.trimIndent()

        val entry = json.decodeFromString(YtDlpInfo.serializer(), payload).toIndexEntry("dQw4w9WgXcQ")

        assertEquals("dQw4w9WgXcQ", entry.id)
        assertEquals("Sample video", entry.title)
        // channel/channel_id win over the uploader fallbacks, matching build-library-index.sh.
        assertEquals("Sample Channel", entry.channel)
        assertEquals("UC123", entry.channelId)
        assertEquals(753L, entry.duration)
        assertEquals("20260721", entry.uploadDate)
        assertEquals(listOf("one", "two"), entry.tags)
        assertEquals(listOf("Music"), entry.categories)
        assertEquals("0:00 Intro\n2:00 Main", entry.description)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg", entry.thumbnail)
        assertEquals(
            listOf(IndexChapter(0.0, "Intro"), IndexChapter(120.0, "Main")),
            entry.chapters,
        )
        // Deliberately not mapped from the payload's epoch: the repository stamps fetch time.
        assertNull(entry.fetchedAt)
    }

    /** Older/limited extractions: no chapters, only uploader fields, integer duration. */
    @Test
    fun `a payload without chapters or channel falls back cleanly`() {
        val payload = """
            {
              "id": "abc",
              "title": "Old style",
              "uploader": "Only Uploader",
              "uploader_id": "UCold",
              "duration": 60
            }
        """.trimIndent()

        val entry = json.decodeFromString(YtDlpInfo.serializer(), payload).toIndexEntry("abc")

        assertEquals("Only Uploader", entry.channel)
        assertEquals("UCold", entry.channelId)
        assertEquals(60L, entry.duration)
        assertNull(entry.chapters)
    }
}
