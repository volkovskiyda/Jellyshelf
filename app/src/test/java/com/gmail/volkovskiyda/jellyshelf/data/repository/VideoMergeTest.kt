package com.gmail.volkovskiyda.jellyshelf.data.repository

import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_INDEX
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_JELLYFIN
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_YTDLP
import com.gmail.volkovskiyda.jellyshelf.data.local.VideoEntity
import com.gmail.volkovskiyda.jellyshelf.data.remote.BaseItemDto
import com.gmail.volkovskiyda.jellyshelf.data.remote.IndexEntry
import com.gmail.volkovskiyda.jellyshelf.data.remote.UserDataDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoMergeTest {

    private val serverBase = "http://server:8096"
    private val now = 1_000_000L
    private val youtubeId = "dQw4w9WgXcQ"

    private fun item(
        played: Boolean = false,
        positionTicks: Long = 0L,
    ) = BaseItemDto(
        id = "jf-item-1",
        name = "Jellyfin Name",
        path = "/media/Title [$youtubeId].mp4",
        runTimeTicks = 600L * 10_000_000, // 600s
        userData = UserDataDto(played = played, playbackPositionTicks = positionTicks, playCount = 1),
    )

    private fun existing(
        source: String,
        metadataUpdatedAt: Long = 500_000L,
    ) = VideoEntity(
        youtubeId = youtubeId,
        jellyfinItemId = "jf-item-1",
        fileName = "Title [$youtubeId].mp4",
        title = "Rich Title",
        channel = "Rich Channel",
        channelId = "UC123",
        durationSeconds = 601L,
        uploadDate = "20240101",
        description = "Rich description",
        tags = listOf("tag"),
        youtubeCategories = listOf("Music"),
        thumbnailUrl = "https://i.ytimg.com/vi/$youtubeId/hq720.jpg",
        played = false,
        playbackPositionTicks = 0L,
        playCount = 0,
        lastSyncedAt = 400_000L,
        metadataSource = source,
        metadataUpdatedAt = metadataUpdatedAt,
    )

    private fun meta(fetchedAtSeconds: Long? = 2_000L) = IndexEntry(
        id = youtubeId,
        title = "Index Title",
        channel = "Index Channel",
        duration = 620L,
        fetchedAt = fetchedAtSeconds,
    )

    // --- the index-failure regression: a failed fetch must never wipe rich metadata ---

    @Test
    fun `index-sourced row keeps its metadata when the index fetch failed`() {
        val merged = mergeVideo(
            existing = existing(METADATA_SOURCE_INDEX),
            youtubeId = youtubeId,
            item = item(played = true, positionTicks = 123L),
            meta = null,
            indexAvailable = false,
            serverBase = serverBase,
            now = now,
        )
        assertEquals(METADATA_SOURCE_INDEX, merged.metadataSource)
        assertEquals("Rich Title", merged.title)
        assertEquals("Rich Channel", merged.channel)
        // Jellyfin-owned fields still refresh.
        assertTrue(merged.played)
        assertEquals(123L, merged.playbackPositionTicks)
        assertEquals(now, merged.lastSyncedAt)
    }

    @Test
    fun `index-sourced row downgrades when a healthy index genuinely lacks the entry`() {
        val merged = mergeVideo(
            existing = existing(METADATA_SOURCE_INDEX),
            youtubeId = youtubeId,
            item = item(),
            meta = null,
            indexAvailable = true,
            serverBase = serverBase,
            now = now,
        )
        assertEquals(METADATA_SOURCE_JELLYFIN, merged.metadataSource)
        assertEquals("Jellyfin Name", merged.title)
    }

    // --- yt-dlp newest-wins ---

    @Test
    fun `ytdlp row is kept over an older index entry`() {
        val merged = mergeVideo(
            existing = existing(METADATA_SOURCE_YTDLP, metadataUpdatedAt = 3_000_000L),
            youtubeId = youtubeId,
            item = item(),
            meta = meta(fetchedAtSeconds = 2_000L), // 2_000_000 ms < 3_000_000 ms
            indexAvailable = true,
            serverBase = serverBase,
            now = now,
        )
        assertEquals(METADATA_SOURCE_YTDLP, merged.metadataSource)
        assertEquals("Rich Title", merged.title)
    }

    @Test
    fun `ytdlp row is replaced by a genuinely newer index entry`() {
        val merged = mergeVideo(
            existing = existing(METADATA_SOURCE_YTDLP, metadataUpdatedAt = 1_000_000L),
            youtubeId = youtubeId,
            item = item(),
            meta = meta(fetchedAtSeconds = 2_000L), // 2_000_000 ms > 1_000_000 ms
            indexAvailable = true,
            serverBase = serverBase,
            now = now,
        )
        assertEquals(METADATA_SOURCE_INDEX, merged.metadataSource)
        assertEquals("Index Title", merged.title)
        assertEquals(2_000_000L, merged.metadataUpdatedAt)
    }

    @Test
    fun `ytdlp row is kept when the index fetch failed`() {
        val merged = mergeVideo(
            existing = existing(METADATA_SOURCE_YTDLP),
            youtubeId = youtubeId,
            item = item(),
            meta = null,
            indexAvailable = false,
            serverBase = serverBase,
            now = now,
        )
        assertEquals(METADATA_SOURCE_YTDLP, merged.metadataSource)
    }

    // --- new rows ---

    @Test
    fun `new video with index entry uses index metadata`() {
        val merged = mergeVideo(
            existing = null,
            youtubeId = youtubeId,
            item = item(),
            meta = meta(),
            indexAvailable = true,
            serverBase = serverBase,
            now = now,
        )
        assertEquals(METADATA_SOURCE_INDEX, merged.metadataSource)
        assertEquals("Index Title", merged.title)
        assertEquals(620L, merged.durationSeconds)
    }

    @Test
    fun `new video without index entry falls back to jellyfin fields`() {
        val merged = mergeVideo(
            existing = null,
            youtubeId = youtubeId,
            item = item(),
            meta = null,
            indexAvailable = true,
            serverBase = serverBase,
            now = now,
        )
        assertEquals(METADATA_SOURCE_JELLYFIN, merged.metadataSource)
        assertEquals("Jellyfin Name", merged.title)
        assertEquals(600L, merged.durationSeconds) // from runTimeTicks
    }

    @Test
    fun `local watch state written after the server snapshot is kept`() {
        val local = existing(METADATA_SOURCE_INDEX).copy(played = true, playbackPositionTicks = 999L)
        val merged = mergeVideo(
            existing = local,
            youtubeId = youtubeId,
            item = item(played = false, positionTicks = 0L), // stale pre-write server snapshot
            meta = meta(),
            indexAvailable = true,
            serverBase = serverBase,
            now = now,
            keepLocalWatchState = true,
        )
        assertTrue(merged.played)
        assertEquals(999L, merged.playbackPositionTicks)
        // Metadata still merges normally.
        assertEquals("Index Title", merged.title)
    }

    // --- credentials never persisted ---

    @Test
    fun `generated jellyfin thumbnail url carries no api key`() {
        val merged = mergeVideo(
            existing = null,
            youtubeId = youtubeId,
            item = item(),
            meta = null,
            indexAvailable = true,
            serverBase = serverBase,
            now = now,
        )
        assertEquals("$serverBase/Items/jf-item-1/Images/Primary?maxWidth=480", merged.thumbnailUrl)
        assertFalse(merged.thumbnailUrl!!.contains("api_key"))
    }

    @Test
    fun `legacy embedded api key is scrubbed from kept rows`() {
        val legacy = existing(METADATA_SOURCE_YTDLP).copy(
            thumbnailUrl = "$serverBase/Items/jf-item-1/Images/Primary?maxWidth=480&api_key=SECRET",
        )
        val merged = mergeVideo(
            existing = legacy,
            youtubeId = youtubeId,
            item = item(),
            meta = null,
            indexAvailable = false,
            serverBase = serverBase,
            now = now,
        )
        assertEquals("$serverBase/Items/jf-item-1/Images/Primary?maxWidth=480", merged.thumbnailUrl)
    }
}
