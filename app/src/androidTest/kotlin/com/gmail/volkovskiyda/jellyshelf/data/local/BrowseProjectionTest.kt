package com.gmail.volkovskiyda.jellyshelf.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.data.mapper.toDomain
import com.gmail.volkovskiyda.jellyshelf.domain.model.Chapter
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_YTDLP
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the browse projection loads, and what it deliberately does not.
 *
 * The browse `Flow`s emit `Video`s whose `description` is null and whose `chapters`, `tags` and
 * `youtubeCategories` are empty *whatever the stored row holds*. Nothing reads those from a list
 * today — they are read on the single-row `observeVideo` path — so the projection is safe now, and
 * the risk is entirely future drift. A KDoc alone would let that drift fail silently: a blank
 * description on the detail screen, no crash, no red test. This makes the contract executable.
 *
 * Instrumented rather than host-side because the type converters are part of what is under test —
 * the "not loaded" fields are exactly the converted ones, and the assertion that the single-row
 * read still carries them only means something against a real SQLite.
 */
@RunWith(AndroidJUnit4::class)
class BrowseProjectionTest {

    private lateinit var db: JellyshelfDatabase
    private lateinit var dao: VideoDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, JellyshelfDatabase::class.java).build()
        dao = db.videoDao()
    }

    @After
    fun tearDown() = db.close()

    /** Deliberately fat: every field the projection drops is non-empty, so dropping is visible. */
    private fun seeded() = VideoEntity(
        youtubeId = "v1",
        jellyfinItemId = "jf-v1",
        fileName = "v1.mp4",
        title = "Title v1",
        channel = "Channel",
        channelId = "UC1",
        durationSeconds = 321L,
        uploadDate = "20240101",
        description = "A long description nobody renders in a list",
        chapters = listOf(Chapter(startMs = 0L, title = "Intro")),
        tags = listOf("a", "b"),
        youtubeCategories = listOf("Music"),
        thumbnailUrl = "https://example.invalid/t.jpg",
        played = true,
        playbackPositionTicks = 4_200L,
        playCount = 2,
        lastSyncedAt = 99L,
        metadataSource = METADATA_SOURCE_YTDLP,
        metadataUpdatedAt = 77L,
        missedSyncs = 3,
    )

    @Test
    fun aProjectedRead_dropsTheFieldsAListNeverRenders() = runTest {
        dao.upsert(seeded())

        val video = dao.observeAllBrowse().first().single().toDomain()

        // Not "absent" — not loaded. The row on disk has all four, as the single-row read below
        // proves; a browse emission simply never carries them.
        assertNull(video.description)
        assertEquals(emptyList<Chapter>(), video.chapters)
        assertEquals(emptyList<String>(), video.tags)
        assertEquals(emptyList<String>(), video.youtubeCategories)
    }

    @Test
    fun aProjectedRead_keepsEveryFieldARowDoesRender() = runTest {
        val seeded = seeded()
        dao.upsert(seeded)

        val video = dao.observeAllBrowse().first().single().toDomain()

        assertEquals(seeded.youtubeId, video.youtubeId)
        assertEquals(seeded.fileName, video.fileName)
        assertEquals(seeded.title, video.title)
        assertEquals(seeded.channel, video.channel)
        assertEquals(seeded.durationSeconds, video.durationSeconds)
        assertEquals(seeded.uploadDate, video.uploadDate)
        assertEquals(seeded.thumbnailUrl, video.thumbnailUrl)
        assertEquals(seeded.played, video.played)
        assertEquals(seeded.playbackPositionTicks, video.playbackPositionTicks)
        assertEquals(seeded.metadataSource, video.metadataSource)
        // The two least obvious of the twelve, and the two that broke when they were missing.
        // missedSyncs is the only input to missingFromServer, which the row renders as a notice…
        assertEquals(seeded.missedSyncs, video.missedSyncs)
        assertTrue(video.missingFromServer)
        // …and jellyfinItemId is what VideoRow branches on to send a thumbnail tap to the player
        // rather than to the detail screen. Projecting it away cost every row its play affordance,
        // silently, and only the live journey noticed (see the plan's 2026-08-06 decision).
        assertEquals(seeded.jellyfinItemId, video.jellyfinItemId)
    }

    @Test
    fun theSingleRowRead_stillCarriesWhatTheProjectionDropped() = runTest {
        dao.upsert(seeded())

        val video = dao.observe("v1").first()?.toDomain()

        // This is the assertion that makes the first test mean "not loaded" rather than "not
        // stored" — same row, same database, everything present.
        assertNotNull(video)
        assertEquals("A long description nobody renders in a list", video?.description)
        assertEquals(listOf(Chapter(startMs = 0L, title = "Intro")), video?.chapters)
        assertEquals(listOf("a", "b"), video?.tags)
        assertEquals(listOf("Music"), video?.youtubeCategories)
    }
}
