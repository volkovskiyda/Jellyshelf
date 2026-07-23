package com.gmail.volkovskiyda.jellyshelf.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_YTDLP
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room DAO round-trips against an in-memory database in a real APK process. Complements the JVM
 * MockEngine tests by exercising the Android-runtime pieces they can't reach — notably the
 * [Converters] `List<String>` type converter, which (de)serializes via kotlinx.serialization on-device.
 */
@RunWith(AndroidJUnit4::class)
class VideoDaoInstrumentedTest {

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

    private fun video(
        id: String,
        played: Boolean = false,
        positionTicks: Long = 0L,
        syncedAt: Long = 1L,
        tags: List<String> = listOf("a", "b"),
    ) = VideoEntity(
        youtubeId = id,
        jellyfinItemId = "jf-$id",
        fileName = "$id.mp4",
        title = "Title $id",
        channel = "Channel",
        channelId = "UC1",
        durationSeconds = 100L,
        uploadDate = "20240101",
        description = "desc",
        tags = tags,
        youtubeCategories = listOf("Music"),
        thumbnailUrl = null,
        played = played,
        playbackPositionTicks = positionTicks,
        playCount = 0,
        lastSyncedAt = syncedAt,
        metadataSource = METADATA_SOURCE_YTDLP,
        metadataUpdatedAt = 0L,
    )

    @Test
    fun upsertAndGet_roundTripsIncludingListConverter() = runTest {
        dao.upsert(video("v1", tags = listOf("x", "y", "z")))

        val got = dao.get("v1")
        assertEquals("v1", got?.youtubeId)
        assertEquals("jf-v1", got?.jellyfinItemId)
        // The kotlinx.serialization-backed Converters must survive the SQLite round-trip on-device.
        assertEquals(listOf("x", "y", "z"), got?.tags)
        assertEquals(listOf("Music"), got?.youtubeCategories)
    }

    @Test
    fun updateWatchState_persists() = runTest {
        dao.upsert(video("v1"))

        dao.updateWatchState("v1", played = true, positionTicks = 42L)

        val got = dao.get("v1")
        assertEquals(true, got?.played)
        assertEquals(42L, got?.playbackPositionTicks)
    }

    @Test
    fun deleteNotSyncedAt_removesStaleRows() = runTest {
        dao.upsert(listOf(video("keep", syncedAt = 100L), video("stale", syncedAt = 99L)))

        dao.deleteNotSyncedAt(100L)

        assertEquals(listOf("keep"), dao.getAll().map { it.youtubeId })
        assertNull(dao.get("stale"))
    }

    @Test
    fun observeContinueWatching_filtersUnwatchedWithProgress() = runTest {
        dao.upsert(
            listOf(
                video("watching", played = false, positionTicks = 10L),
                video("done", played = true, positionTicks = 10L),
                video("fresh", played = false, positionTicks = 0L),
            ),
        )

        val continueWatching = dao.observeContinueWatching().first().map { it.youtubeId }
        assertEquals(listOf("watching"), continueWatching)
    }
}
