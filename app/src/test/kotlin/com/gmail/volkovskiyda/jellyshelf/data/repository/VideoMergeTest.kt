package com.gmail.volkovskiyda.jellyshelf.data.repository

import com.gmail.volkovskiyda.jellyshelf.data.local.VideoEntity
import com.gmail.volkovskiyda.jellyshelf.data.remote.BaseItemDto
import com.gmail.volkovskiyda.jellyshelf.data.remote.IndexChapter
import com.gmail.volkovskiyda.jellyshelf.data.remote.IndexEntry
import com.gmail.volkovskiyda.jellyshelf.data.remote.UserDataDto
import com.gmail.volkovskiyda.jellyshelf.domain.model.Chapter
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_API
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_API_INDEX
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_INDEX
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_JELLYFIN
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_YTDLP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoMergeTest {

    private val serverBase = "http://server:8096"
    private val now = 1_000_000L
    private val youtubeId = "dQw4w9WgXcQ"

    private fun context(indexAvailable: Boolean, apiAvailable: Boolean = false) =
        SyncMergeContext(serverBase, now, indexAvailable, apiAvailable)

    /** An index-only candidate, built the way the sync builds one. */
    private fun indexMeta(entry: IndexEntry?) = combinedMeta(api = null, index = entry)

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
            context = context(indexAvailable = false),
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
            context = context(indexAvailable = true),
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
            meta = indexMeta(meta(fetchedAtSeconds = 2_000L)), // 2_000_000 ms < 3_000_000 ms
            context = context(indexAvailable = true),
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
            meta = indexMeta(meta(fetchedAtSeconds = 2_000L)), // 2_000_000 ms > 1_000_000 ms
            context = context(indexAvailable = true),
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
            context = context(indexAvailable = false),
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
            meta = indexMeta(meta()),
            context = context(indexAvailable = true),
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
            context = context(indexAvailable = true),
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
            meta = indexMeta(meta()),
            context = context(indexAvailable = true),
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
            context = context(indexAvailable = true),
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
            context = context(indexAvailable = false),
        )
        assertEquals("$serverBase/Items/jf-item-1/Images/Primary?maxWidth=480", merged.thumbnailUrl)
    }

    /**
     * The Jellyfin-only branch rebuilds the row from scratch, so without explicit carrying it
     * would erase the fetch error on every sync — hiding exactly the persistent failures the
     * field exists to report, since the auto-fill pass retries those videos every sync.
     */
    @Test
    fun `a recorded fetch error survives a sync that still finds no metadata`() {
        val failed = existing(METADATA_SOURCE_JELLYFIN).copy(
            lastFetchError = "yt-dlp timed out",
            lastFetchErrorAt = 900_000L,
        )
        val merged = mergeVideo(
            existing = failed,
            youtubeId = youtubeId,
            item = item(),
            meta = null,
            context = context(indexAvailable = true),
        )
        assertEquals(METADATA_SOURCE_JELLYFIN, merged.metadataSource)
        assertEquals("yt-dlp timed out", merged.lastFetchError)
        assertEquals(900_000L, merged.lastFetchErrorAt)
    }

    /** Once the index supplies the metadata, the old failure is history rather than news. */
    @Test
    fun `an index match clears a recorded fetch error`() {
        val failed = existing(METADATA_SOURCE_JELLYFIN).copy(
            lastFetchError = "yt-dlp timed out",
            lastFetchErrorAt = 900_000L,
        )
        val merged = mergeVideo(
            existing = failed,
            youtubeId = youtubeId,
            item = item(),
            meta = indexMeta(meta()),
            context = context(indexAvailable = true),
        )
        assertEquals(METADATA_SOURCE_INDEX, merged.metadataSource)
        assertNull(merged.lastFetchError)
        assertEquals(0L, merged.lastFetchErrorAt)
    }

    // --- structured chapters ride the index merge like the other index fields ---

    /** Wire chapters tighten on the way in: millis, blank/negative entries dropped, sorted. */
    @Test
    fun `index chapters are carried into the entity as clean domain chapters`() {
        val merged = mergeVideo(
            existing = null,
            youtubeId = youtubeId,
            item = item(),
            meta = indexMeta(
                meta().copy(
                    chapters = listOf(
                        IndexChapter(startSeconds = 120.5, title = "Main part"),
                        IndexChapter(startSeconds = 0.0, title = "Intro"),
                        IndexChapter(startSeconds = -3.0, title = "Negative start"),
                        IndexChapter(startSeconds = 300.0, title = "   "),
                        IndexChapter(startSeconds = null, title = "No start"),
                    ),
                ),
            ),
            context = context(indexAvailable = true),
        )
        assertEquals(
            listOf(Chapter(0L, "Intro"), Chapter(120_500L, "Main part")),
            merged.chapters,
        )
    }

    /** No index entry means no structured chapters — Jellyfin has nothing to fall back to. */
    @Test
    fun `a row rebuilt without an index entry has no chapters`() {
        val merged = mergeVideo(
            existing = null,
            youtubeId = youtubeId,
            item = item(),
            meta = null,
            context = context(indexAvailable = true),
        )
        assertEquals(emptyList<Chapter>(), merged.chapters)
    }

    // --- the metadata API feed and the per-field API+index combine ---

    private fun apiMeta(fetchedAtSeconds: Long? = 3_000L) = IndexEntry(
        id = youtubeId,
        title = "Api Title",
        description = "Api description",
        fetchedAt = fetchedAtSeconds,
    )

    @Test
    fun `api-only entry labels the candidate API`() {
        val combined = combinedMeta(api = apiMeta(), index = null)!!
        assertEquals(METADATA_SOURCE_API, combined.source)
        assertEquals("Api Title", combined.entry.title)
    }

    @Test
    fun `both entries combine newest-first per field`() {
        // The API entry is fresher: its fields win, the index fills what it lacks.
        val combined = combinedMeta(api = apiMeta(fetchedAtSeconds = 3_000L), index = meta(fetchedAtSeconds = 2_000L))!!
        assertEquals(METADATA_SOURCE_API_INDEX, combined.source)
        assertEquals("Api Title", combined.entry.title)
        assertEquals("Index Channel", combined.entry.channel)
        assertEquals(620L, combined.entry.duration)
        assertEquals(3_000L, combined.entry.fetchedAt)
    }

    @Test
    fun `an older api entry only fills the newer index entry's gaps`() {
        val combined = combinedMeta(api = apiMeta(fetchedAtSeconds = 1_000L), index = meta(fetchedAtSeconds = 2_000L))!!
        assertEquals(METADATA_SOURCE_API_INDEX, combined.source)
        assertEquals("Index Title", combined.entry.title)
        // The index entry has no description; the older API entry supplies it.
        assertEquals("Api description", combined.entry.description)
        assertEquals(2_000L, combined.entry.fetchedAt)
    }

    @Test
    fun `new video with an api entry uses api metadata`() {
        val merged = mergeVideo(
            existing = null,
            youtubeId = youtubeId,
            item = item(),
            meta = combinedMeta(api = apiMeta(), index = null),
            context = context(indexAvailable = false, apiAvailable = true),
        )
        assertEquals(METADATA_SOURCE_API, merged.metadataSource)
        assertEquals("Api Title", merged.title)
        assertEquals(3_000_000L, merged.metadataUpdatedAt)
    }

    @Test
    fun `api-sourced row keeps its metadata when the api fetch failed`() {
        val merged = mergeVideo(
            existing = existing(METADATA_SOURCE_API),
            youtubeId = youtubeId,
            item = item(),
            meta = null,
            context = context(indexAvailable = true, apiAvailable = false),
        )
        assertEquals(METADATA_SOURCE_API, merged.metadataSource)
        assertEquals("Rich Title", merged.title)
    }

    @Test
    fun `api-sourced row downgrades when a healthy api genuinely lacks the entry`() {
        val merged = mergeVideo(
            existing = existing(METADATA_SOURCE_API),
            youtubeId = youtubeId,
            item = item(),
            meta = null,
            context = context(indexAvailable = false, apiAvailable = true),
        )
        assertEquals(METADATA_SOURCE_JELLYFIN, merged.metadataSource)
    }

    @Test
    fun `api-sourced row upgrades to a genuinely newer index entry`() {
        val merged = mergeVideo(
            existing = existing(METADATA_SOURCE_API, metadataUpdatedAt = 1_000_000L),
            youtubeId = youtubeId,
            item = item(),
            meta = indexMeta(meta(fetchedAtSeconds = 2_000L)),
            context = context(indexAvailable = true, apiAvailable = true),
        )
        assertEquals(METADATA_SOURCE_INDEX, merged.metadataSource)
        assertEquals("Index Title", merged.title)
    }

    @Test
    fun `ytdlp row is kept over an equal-or-older api entry`() {
        val merged = mergeVideo(
            existing = existing(METADATA_SOURCE_YTDLP, metadataUpdatedAt = 3_000_000L),
            youtubeId = youtubeId,
            item = item(),
            meta = combinedMeta(api = apiMeta(fetchedAtSeconds = 3_000L), index = null),
            context = context(indexAvailable = false, apiAvailable = true),
        )
        assertEquals(METADATA_SOURCE_YTDLP, merged.metadataSource)
    }

    /** A combined row is demoted only when *both* feeds vouched for its absence. */
    @Test
    fun `api-index row survives a sync where only one of its feeds is reachable`() {
        val merged = mergeVideo(
            existing = existing(METADATA_SOURCE_API_INDEX),
            youtubeId = youtubeId,
            item = item(),
            meta = null,
            context = context(indexAvailable = true, apiAvailable = false),
        )
        assertEquals(METADATA_SOURCE_API_INDEX, merged.metadataSource)
        assertEquals("Rich Title", merged.title)
    }

    @Test
    fun `api-index row downgrades when both healthy feeds lack the entry`() {
        val merged = mergeVideo(
            existing = existing(METADATA_SOURCE_API_INDEX),
            youtubeId = youtubeId,
            item = item(),
            meta = null,
            context = context(indexAvailable = true, apiAvailable = true),
        )
        assertEquals(METADATA_SOURCE_JELLYFIN, merged.metadataSource)
    }
}
