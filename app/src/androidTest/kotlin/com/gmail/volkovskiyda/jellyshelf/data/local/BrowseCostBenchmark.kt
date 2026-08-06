package com.gmail.volkovskiyda.jellyshelf.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.data.mapper.toDomain
import com.gmail.volkovskiyda.jellyshelf.domain.model.Chapter
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_INDEX
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

/**
 * What one browse emission costs on a full-sized library, measured on the device rather than
 * argued about — the input to item 12's verdict on whether the list is worth projecting.
 *
 * Timings over the same 10,000 rows:
 *  1. `observeAll()` — the query plus Room's type converters (three JSON parses per row),
 *  2. the same through `VideoEntity::toDomain`, which is what the repository emitted *before*
 *     `20260731-browse-projection-plan`, and still emits on the ranked-search path,
 *  3. `observeAllBrowse()` through `VideoBrowseRow::toDomain` — what a browse emission costs now,
 *  4. a throwaway projected query listing only the ten columns a row renders,
 *  5. two raw-cursor reads that decompose the difference.
 *
 * (4) exists purely for the comparison and touches no production code — it was the floor the plan
 * was priced against before anything was built. Keep it: (3) is the shipped path and (4) is the
 * ceiling on what a *full* projection could still buy, and the verdict is the gap between them.
 * Note they are not the same shape — (4) is a hand-written ten-column cursor read, (3) goes
 * through Room and carries twelve columns (`metadataSource` and `jellyfinItemId`, both `TEXT` with
 * no converter).
 *
 * The numbers are printed rather than asserted — this is a measurement, and a threshold assertion
 * would turn a slow CI machine into a build failure. The assertions that *are* here only confirm
 * each path returned the rows it claimed to.
 *
 * `@Ignore`d on purpose: seeding 10,000 rows and timing six paths costs ~50 s, which is a lot to
 * add to every suite run for output nobody reads unless they are asking this question. Run it
 * deliberately, and re-run it to confirm whatever the verdict leads to.
 *
 * **Comment out the `@Ignore` first.** Naming the class on the command line does not override it —
 * the runner still reports the class as skipped, silently, and prints nothing. (Corrected
 * 2026-08-06: the command below was documented without that step and looks like it worked.)
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=\
 * com.gmail.volkovskiyda.jellyshelf.data.local.BrowseCostBenchmark
 * adb logcat -d | grep BrowseCost:
 * ```
 *
 * Run it more than once. The raw-cursor rows are repeatable to within a few ms, but the two rows
 * that allocate 10,000 domain objects have a long tail — `observeAllBrowse + toDomain` measured
 * medians of 545/356/434 ms across three runs with minimums pinned near 325 ms, so a single median
 * is not a number to draw a conclusion from.
 */
@RunWith(AndroidJUnit4::class)
@Ignore("Measurement, not a regression test — see the KDoc for how to run it")
class BrowseCostBenchmark {

    private lateinit var db: JellyshelfDatabase
    private lateinit var dao: VideoDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // On disk, not in memory: the browse path's cost includes reading the description column
        // back off storage, and an in-memory database would measure that away.
        context.deleteDatabase(DB_NAME)
        db = Room.databaseBuilder(context, JellyshelfDatabase::class.java, DB_NAME).build()
        dao = db.videoDao()
        runBlocking { dao.upsert(seed()) }
    }

    @After
    fun tearDown() {
        db.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    /**
     * Rows realistic in the ways that cost: a description of a few thousand characters, a handful
     * of tags and categories each, and chapters on a third of them. A table of empty strings would
     * measure nothing.
     */
    private fun seed() = (0 until ROW_COUNT).map { i ->
        VideoEntity(
            youtubeId = "video-%05d".format(i),
            jellyfinItemId = "item-%05d".format(i),
            fileName = "20260721_%05d - A sample video title [video-%05d].mp4".format(i, i),
            title = "A reasonably long video title that wraps onto a second line, number $i",
            channel = "Sample Channel ${i % 50}",
            channelId = "UC%022d".format(i % 50),
            durationSeconds = 60L + (i % 7_200),
            uploadDate = "2026%02d%02d".format(1 + i % 12, 1 + i % 28),
            description = DESCRIPTION_PARAGRAPH.repeat(DESCRIPTION_PARAGRAPHS),
            chapters = if (i % 3 == 0) {
                List(CHAPTERS_PER_VIDEO) { c -> Chapter(c * 60_000L, "Chapter ${c + 1}") }
            } else {
                emptyList()
            },
            tags = listOf("sample", "preview", "tag-${i % 20}", "tag-${i % 7}"),
            youtubeCategories = listOf("Entertainment", "Category ${i % 15}"),
            thumbnailUrl = "https://jellyfin.example.org/Items/item-%05d/Images/Primary".format(i),
            played = i % 4 == 0,
            playbackPositionTicks = if (i % 5 == 0) 3_016_000_000L else 0L,
            playCount = i % 3,
            lastSyncedAt = 1_785_143_919_405L,
            metadataSource = METADATA_SOURCE_INDEX,
            metadataUpdatedAt = 1_785_143_919_405L,
            missedSyncs = 0,
        )
    }

    /** The ten fields `VideoRow` actually renders — what a projection POJO would hold. */
    private data class BrowseItem(
        val youtubeId: String,
        val fileName: String,
        val title: String,
        val channel: String?,
        val durationSeconds: Long,
        val uploadDate: String?,
        val thumbnailUrl: String?,
        val played: Boolean,
        val playbackPositionTicks: Long,
        val missedSyncs: Int,
    )

    /**
     * The projected read, run straight against the database rather than through a DAO method:
     * pricing the projection must not require building it. No `description`, no `chapters`, no
     * `tags`, no `youtubeCategories` — so none of the three JSON parses happen at all.
     */
    private fun readProjection(): List<BrowseItem> =
        db.openHelper.readableDatabase.query(
            "SELECT youtubeId, fileName, title, channel, durationSeconds, uploadDate, " +
                "thumbnailUrl, played, playbackPositionTicks, missedSyncs " +
                "FROM videos ORDER BY fileName",
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        BrowseItem(
                            youtubeId = c.getString(0),
                            fileName = c.getString(1),
                            title = c.getString(2),
                            channel = c.takeIf { !it.isNull(3) }?.getString(3),
                            durationSeconds = c.getLong(4),
                            uploadDate = c.takeIf { !it.isNull(5) }?.getString(5),
                            thumbnailUrl = c.takeIf { !it.isNull(6) }?.getString(6),
                            played = c.getInt(7) != 0,
                            playbackPositionTicks = c.getLong(8),
                            missedSyncs = c.getInt(9),
                        ),
                    )
                }
            }
        }

    /** Median of [RUNS] timings after one warm-up, which is what the app's second emission sees. */
    private fun medianMillis(label: String, block: () -> Int): Long {
        val warmUpRows = block()
        assertEquals("$label returned the wrong row count", ROW_COUNT, warmUpRows)
        val timings = (0 until RUNS).map { measureTimeMillis { block() } }.sorted()
        val median = timings[timings.size / 2]
        println("BrowseCost: $label median=${median}ms of $timings")
        return median
    }

    @Test
    fun browseEmissionCost() = runBlocking {
        val entities = medianMillis("observeAll (query + converters)") {
            runBlocking { dao.observeAll().first().size }
        }
        val domain = medianMillis("observeAll + toDomain (what the repository emits)") {
            runBlocking { dao.observeAll().first().map(VideoEntity::toDomain).size }
        }
        val projected = medianMillis("projected query (ten columns the row renders)") {
            readProjection().size
        }
        // What a browse emission costs *after* the projection landed — the number the verdict
        // turns on. The line above is a raw-cursor floor of ten hand-listed columns; this is the
        // shipped path: Room, twelve columns, and VideoBrowseRow::toDomain on top.
        val browse = medianMillis("observeAllBrowse + toDomain (what the repository emits now)") {
            runBlocking { dao.observeAllBrowse().first().map(VideoBrowseRow::toDomain).size }
        }
        // The "cheap half" the verdict weighs: keep every other column and the one Video model,
        // just stop reading the two big ones. Prices the columns separately from the mapping.
        val withoutBigColumns = medianMillis("all columns except description and chapters") {
            countWithoutBigColumns()
        }

        // Splits the two suspects apart: same 20 columns as the current path, same raw-cursor
        // read as the line above, but *including* description and chapters. If this is slow the
        // cost is reading those columns; if it is fast the cost is the JSON parsing on top.
        val allColumnsRaw = medianMillis("all columns, raw read (no converters, no mapping)") {
            countAllColumnsRaw()
        }

        println(
            "BrowseCost: rows=$ROW_COUNT entities=${entities}ms domain=${domain}ms " +
                "browse=${browse}ms projected=${projected}ms cheapHalf=${withoutBigColumns}ms " +
                "allColumnsRaw=${allColumnsRaw}ms saving=${domain - browse}ms " +
                "residualToFloor=${browse - projected}ms",
        )
    }

    /** Every column, read but never converted — isolates the read from the deserialization. */
    private fun countAllColumnsRaw(): Int =
        db.openHelper.readableDatabase.query("SELECT * FROM videos ORDER BY fileName").use { c ->
            var rows = 0
            val columns = c.columnCount
            while (c.moveToNext()) {
                for (i in 0 until columns) if (!c.isNull(i)) c.getString(i)
                rows++
            }
            rows
        }

    /**
     * Everything but `description` and `chapters`, still parsing `tags` and `youtubeCategories` —
     * the shape the cheap-half option would read. Counted rather than mapped, because what is
     * being priced is the read.
     */
    private fun countWithoutBigColumns(): Int =
        db.openHelper.readableDatabase.query(
            "SELECT youtubeId, jellyfinItemId, fileName, title, channel, channelId, " +
                "durationSeconds, uploadDate, tags, youtubeCategories, thumbnailUrl, played, " +
                "playbackPositionTicks, playCount, lastSyncedAt, metadataSource, " +
                "metadataUpdatedAt, missedSyncs, lastFetchError, lastFetchErrorAt " +
                "FROM videos ORDER BY fileName",
        ).use { c ->
            var rows = 0
            while (c.moveToNext()) {
                // Touch the JSON columns so their read cost is included, as it would be.
                c.getString(8)
                c.getString(9)
                rows++
            }
            rows
        }

    private companion object {
        const val DB_NAME = "browse-cost-benchmark"
        const val ROW_COUNT = 10_000
        const val RUNS = 5
        const val CHAPTERS_PER_VIDEO = 8
        const val DESCRIPTION_PARAGRAPHS = 8
        const val DESCRIPTION_PARAGRAPH =
            "A sample description paragraph, long enough to stand in for the real thing: yt-dlp " +
                "descriptions routinely run to several thousand characters of links, timestamps " +
                "and boilerplate, and the browse query reads every byte of it for a list row " +
                "that never shows a single one. "
    }
}
