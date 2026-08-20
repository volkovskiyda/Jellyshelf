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
    fun updateServerWatchState_carriesThePlayCountToo() = runTest {
        dao.upsert(video("v1"))

        dao.updateWatchState("v1", played = true, positionTicks = 42L)
        // The local write leaves the count alone — nothing on the device counts plays…
        assertEquals(0, dao.get("v1")?.playCount)

        // …and the server's own snapshot is the only thing that sets it.
        dao.updateServerWatchState("v1", played = true, positionTicks = 0L, playCount = 3)

        val got = dao.get("v1")
        assertEquals(true, got?.played)
        assertEquals(0L, got?.playbackPositionTicks)
        assertEquals(3, got?.playCount)
    }

    @Test
    fun deleteNotSyncedAt_removesStaleRows() = runTest {
        dao.upsert(listOf(video("keep", syncedAt = 100L), video("stale", syncedAt = 99L)))

        dao.deleteNotSyncedAt(100L)

        assertEquals(listOf("keep"), dao.getAll().map { it.youtubeId })
        assertNull(dao.get("stale"))
    }

    /**
     * The projected twin is the only version of this query — the full-row one had no callers left
     * once the repository routed through the projection. The `WHERE` clause is verbatim the same
     * and `youtubeId` is a projected column, so the case keeps its exact meaning.
     */
    @Test
    fun observeContinueWatching_filtersUnwatchedWithProgress() = runTest {
        dao.upsert(
            listOf(
                video("watching", played = false, positionTicks = 10L),
                video("done", played = true, positionTicks = 10L),
                video("fresh", played = false, positionTicks = 0L),
            ),
        )

        val continueWatching = dao.observeContinueWatchingBrowse().first().map { it.youtubeId }
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
                    appendLine(normalizePlan(cursor.getString(cursor.getColumnIndexOrThrow("detail"))))
                }
            }
        }

    /**
     * One wording for a plan line, whatever SQLite the platform ships.
     *
     * SQLite 3.36 dropped the `TABLE` keyword from query plans, and Android crosses that line
     * well above minSdk. Measured through this very path on 2026-08-20: API 31 ships SQLite
     * 3.32.2 and prints `SCAN TABLE videos`, API 34 ships 3.39.2 and prints `SCAN videos` (32 and
     * 33 were not measured, and do not need to be — they can only be one of the two). That is a
     * rename, not a different plan, but the assertions below match plan text, so without this the
     * same query reads as two different outcomes.
     *
     * It bites in the direction that does not announce itself: [assertNoTableScan] compares a line
     * to `"SCAN videos"` exactly, so on a pre-3.36 platform a real full-table scan prints
     * `SCAN TABLE videos`, fails to match, and the guard passes while the thing it guards against
     * is happening. **The CI tablet runs API 31**, so this is load-bearing today, not history —
     * deleting it silently disarms the guard on the one device that carries minSdk.
     * Normalising here rather than in each matcher keeps that in one place.
     */
    private fun normalizePlan(detail: String): String =
        detail.replace(PLAN_TABLE_KEYWORD, "$1 ")

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
    fun theProjectedBrowseQueries_keepTheirTwinsPlans() {
        // @RewriteQueriesToDropUnusedColumns wraps the query in an outer SELECT of the eleven
        // columns VideoBrowseRow declares. SQLite flattens that subquery away, so the projected
        // twin should plan exactly like the full-row query it was copied from — but "should" is
        // the planner's call, not the annotation's promise, and a projection that lost an index
        // would cost more than the two columns it saves. These are the strings Room generated
        // (app/build/generated/ksp/debug/kotlin/.../VideoDao_Impl.kt), pasted for the same reason
        // the plans above are.
        assertReadsInOrderFromIndex(
            "SELECT `youtubeId`, `jellyfinItemId`, `fileName`, `title`, `channel`, " +
                "`durationSeconds`, `uploadDate`, `thumbnailUrl`, `played`, " +
                "`playbackPositionTicks`, `metadataSource`, `missedSyncs` " +
                "FROM (SELECT * FROM videos ORDER BY fileName)",
        )

        val bucketPlan = explain(
            "SELECT `youtubeId`, `jellyfinItemId`, `fileName`, `title`, `channel`, " +
                "`durationSeconds`, `uploadDate`, `thumbnailUrl`, `played`, " +
                "`playbackPositionTicks`, `metadataSource`, `missedSyncs` " +
                "FROM (SELECT * FROM videos WHERE durationSeconds >= 60 AND durationSeconds < 600 " +
                "ORDER BY fileName)",
        )
        assertTrue(bucketPlan, "index_videos_durationSeconds" in bucketPlan)
        assertNoTableScan(bucketPlan)
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

    private companion object {
        /** `SCAN TABLE x` / `SEARCH TABLE x` — the pre-3.36 spelling, captured to drop `TABLE`. */
        val PLAN_TABLE_KEYWORD = Regex("""\b(SCAN|SEARCH) TABLE """)
    }
}
