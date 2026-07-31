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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /**
     * What SQLite says it will do with [sql] — one line per step of the plan.
     *
     * The queries below are pasted from [VideoDao] rather than run through it, because what is
     * under test is the *planner's* choice, and a DAO method returns rows either way. An index the
     * planner declines to use is worse than no index: it still costs write time on every upsert
     * and on every 10-second playback-position save.
     */
    private fun explain(sql: String): String =
        db.openHelper.writableDatabase.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
            buildString {
                while (cursor.moveToNext()) {
                    appendLine(cursor.getString(cursor.getColumnIndexOrThrow("detail")))
                }
            }
        }

    /**
     * The failure this whole group exists to catch: reading every row of the table.
     *
     * Only a bare `SCAN videos` counts. `SCAN videos USING INDEX …` is an ordered walk *of the
     * index*, which is the good outcome for an unfiltered `ORDER BY fileName` — it is how the
     * sort disappears.
     */
    private fun assertNoTableScan(plan: String) {
        assertFalse(plan, plan.lineSequence().any { it.trim() == "SCAN videos" })
    }

    /** Reaches the rows through an index and delivers them already ordered — no sort at all. */
    private fun assertReadsInOrderFromIndex(sql: String) {
        val plan = explain(sql)
        assertTrue(plan, "USING INDEX" in plan || "USING COVERING INDEX" in plan)
        assertNoTableScan(plan)
        assertFalse(plan, "TEMP B-TREE" in plan)
    }

    @Test
    fun theUnfilteredBrowseList_readsInOrderFromAnIndex() {
        assertReadsInOrderFromIndex("SELECT * FROM videos ORDER BY fileName")
    }

    @Test
    fun theWatchedAndUnwatchedLists_seekByPlayedAndReadInOrder() {
        assertReadsInOrderFromIndex("SELECT * FROM videos WHERE played = 1 ORDER BY fileName")
        assertReadsInOrderFromIndex("SELECT * FROM videos WHERE played = 0 ORDER BY fileName")
    }

    @Test
    fun continueWatching_seeksBothColumnsAndSortsOnlyTheMatches() {
        // An inequality mid-index (playbackPositionTicks > 0) means the columns after it are no
        // longer in order, so the sort survives — and should. The alternative the planner passed
        // over is reading every unwatched video in fileName order to keep a handful with
        // progress; seeking straight to that handful and sorting it is the cheaper shape.
        val plan = explain(
            "SELECT * FROM videos WHERE played = 0 AND playbackPositionTicks > 0 ORDER BY fileName",
        )
        assertTrue(plan, "index_videos_played_playbackPositionTicks_fileName" in plan)
        assertNoTableScan(plan)
    }

    @Test
    fun theUncategorizedFilter_seeksBySourceAndReadsInOrder() {
        assertReadsInOrderFromIndex(
            "SELECT * FROM videos WHERE metadataSource = 'ytdlp' ORDER BY fileName",
        )
    }

    @Test
    fun theDurationBucketFilter_seeksTheRangeInsteadOfScanning() {
        // Same rule as continue-watching: a range cannot also deliver fileName order, so this
        // sorts a bucket rather than the library.
        val plan = explain(
            "SELECT * FROM videos WHERE durationSeconds >= 60 AND durationSeconds < 600 " +
                "ORDER BY fileName",
        )
        assertTrue(plan, "index_videos_durationSeconds" in plan)
        assertNoTableScan(plan)
    }

    @Test
    fun theSyncPrune_scansOnPurpose_becauseAnIndexCannotServeInequality() {
        // Deliberately *not* indexed on lastSyncedAt. `!=` is not a range, so SQLite scans even
        // when the index exists (verified by adding one and re-reading this plan) — it would be
        // write cost on every upsert buying nothing. This asserts the scan so that a future
        // reader sees the omission is a decision rather than an oversight.
        val plan = explain("DELETE FROM videos WHERE lastSyncedAt != 5")
        assertTrue(plan, "SCAN videos" in plan)
    }

    @Test
    fun theWatchStateCounts_areServedFromAnIndexWithoutTouchingTheTable() {
        // These are the queries a playback-position save re-runs every 10 seconds, on every
        // browse Flow left mounted behind the player — the reason the index matters at all.
        for (sql in listOf(
            "SELECT COUNT(*) FROM videos WHERE played = 1",
            "SELECT COUNT(*) FROM videos WHERE played = 0",
            "SELECT COUNT(*) FROM videos WHERE played = 0 AND playbackPositionTicks > 0",
        )) {
            val plan = explain(sql)
            assertTrue(plan, "USING COVERING INDEX" in plan)
            assertNoTableScan(plan)
        }
    }
}
