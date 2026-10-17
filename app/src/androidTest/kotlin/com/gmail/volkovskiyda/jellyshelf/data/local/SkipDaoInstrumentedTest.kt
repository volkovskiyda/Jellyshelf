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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The skip list against a real in-memory database: what the foreign key and the cascade promise
 * only holds if SQLite actually enforces them, which a JVM fake cannot show.
 */
@RunWith(AndroidJUnit4::class)
class SkipDaoInstrumentedTest {

    private lateinit var db: JellyshelfDatabase
    private lateinit var videos: VideoDao
    private lateinit var skips: SkipDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, JellyshelfDatabase::class.java).build()
        videos = db.videoDao()
        skips = db.skipDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun skip_marksStoredVideosAndIgnoresUnknownIds() = runTest {
        videos.upsert(listOf(video("a"), video("b")))

        skips.skip(listOf("a", "nope"))
        // Skipping twice is not an error, and changes nothing.
        skips.skip(listOf("a"))

        assertEquals(listOf("a"), skips.getIds())
        assertEquals(1, skips.count().first())
    }

    @Test
    fun unskip_dropsOnlyTheNamedMarks() = runTest {
        videos.upsert(listOf(video("a"), video("b")))
        skips.skip(listOf("a", "b"))

        skips.unskip(listOf("a"))

        assertEquals(listOf("b"), skips.getIds())
    }

    /** What a sync does to a row it still sees: an upsert, which must not take the mark away. */
    @Test
    fun aSyncRewritingTheRow_keepsTheMark() = runTest {
        videos.upsert(video("a"))
        skips.skip(listOf("a"))

        videos.upsert(video("a", title = "Renamed on the server", syncedAt = 2L))

        assertEquals(listOf("a"), skips.getIds())
    }

    /** Every way a video leaves the library takes its mark with it, through the cascade. */
    @Test
    fun aVideoLeavingTheLibrary_dropsItsMark() = runTest {
        videos.upsert(
            listOf(video("removed"), video("pruned", syncedAt = 1L), video("graced", syncedAt = 1L), video("kept")),
        )
        skips.skip(listOf("removed", "pruned", "graced", "kept"))

        videos.delete("removed")
        videos.upsert(video("kept", syncedAt = 5L))
        videos.markMissedSince(5L)
        videos.deleteAfterMissedSyncs(5L, maxMissedSyncs = 1)

        assertEquals(listOf("kept"), skips.getIds())

        videos.clear()

        assertEquals(emptyList<String>(), skips.getIds())
    }

    @Test
    fun theSkippedFilter_listsSkippedVideosInFileNameOrder() = runTest {
        videos.upsert(listOf(video("c"), video("a"), video("b")))
        skips.skip(listOf("c", "a"))

        assertEquals(listOf("a", "c"), skips.observeSkippedBrowse().first().map { it.youtubeId })
    }

    private fun video(id: String, title: String = "Title $id", syncedAt: Long = 1L) = VideoEntity(
        youtubeId = id,
        jellyfinItemId = "jf-$id",
        fileName = "$id.mp4",
        title = title,
        channel = "Channel",
        channelId = "UC1",
        durationSeconds = 100L,
        uploadDate = "20240101",
        description = "desc",
        tags = emptyList(),
        youtubeCategories = emptyList(),
        thumbnailUrl = null,
        played = false,
        playbackPositionTicks = 0L,
        playCount = 0,
        lastSyncedAt = syncedAt,
        metadataSource = METADATA_SOURCE_YTDLP,
    )
}
