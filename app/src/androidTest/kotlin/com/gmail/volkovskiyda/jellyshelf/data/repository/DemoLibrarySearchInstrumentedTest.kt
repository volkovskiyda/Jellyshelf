package com.gmail.volkovskiyda.jellyshelf.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.data.local.JellyshelfDatabase
import com.gmail.volkovskiyda.jellyshelf.data.remote.IndexSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.JellyfinClient
import com.gmail.volkovskiyda.jellyshelf.data.remote.TestDemoBackend
import com.gmail.volkovskiyda.jellyshelf.data.remote.YtDlpMetadataSource
import com.gmail.volkovskiyda.jellyshelf.di.provideJson
import com.gmail.volkovskiyda.jellyshelf.domain.DispatcherProvider
import com.gmail.volkovskiyda.jellyshelf.domain.model.DurationBucket
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.ui.FakeSettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Library search and duration filtering end to end over the seeded demo library: the real
 * repository, the real Room database and the real bundled asset.
 *
 * This is the half [DemoLibrarySearchTest] cannot reach. That one runs the ranking host-side over
 * the same dataset; the filter it models is Kotlin. In production the bucket is a **SQL range**
 * (`observeByDurationRange`) and the query is ranking applied to whatever those rows are, so the
 * two only meet on a device. What can go wrong here is invisible host-side: an inclusive upper
 * bound in the SQL, a bucket that silently sweeps up the unknown-duration rows, or a filter that
 * ranking then re-widens.
 *
 * The duration assertions compare the SQL result against [DurationBucket.contains] — the pure
 * definition of the same range — rather than against copied-out expected counts, so they keep
 * their meaning when the demo dataset is edited. The search assertions do name titles: the demo
 * dataset is a committed fixture, and an edit that removed the video a search is built on should
 * fail loudly rather than pass vacuously.
 *
 * `runBlocking` rather than `runTest`, and a MockEngine that refuses every request, for the reasons
 * [DemoSeedInstrumentedTest] gives.
 */
@RunWith(AndroidJUnit4::class)
class DemoLibrarySearchInstrumentedTest {

    private lateinit var db: JellyshelfDatabase
    private lateinit var repo: DefaultLibraryRepository

    private val dispatchers = object : DispatcherProvider {
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val ioSequential = Dispatchers.Unconfined
        override val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        override fun ioScope(tag: String, failureMessage: String) =
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, JellyshelfDatabase::class.java).build()
        val json = provideJson()
        val httpClient = HttpClient(MockEngine { error("searching a seeded library must not use the network") }) {
            expectSuccess = true
        }
        val indexSource = IndexSource(context, httpClient, dispatchers, json)
        repo = DefaultLibraryRepository(
            db = db,
            settings = FakeSettingsRepository(),
            dispatchers = dispatchers,
            sources = LibrarySources(
                JellyfinDataSource(JellyfinClient(httpClient)),
                indexSource,
                YtDlpMetadataSource(context, dispatchers, json),
                TestDemoBackend(indexSource),
            ),
        )
        repo.seedDemoLibrary()
    }

    @After
    fun tearDown() = db.close()

    /** One emission of the search/filter flow — what the library screen renders for these terms. */
    private fun results(query: String = "", bucket: DurationBucket? = null): List<Video> =
        runBlocking { repo.searchVideos(query, bucket).first() }

    private fun titles(query: String = "", bucket: DurationBucket? = null): List<String> =
        results(query, bucket).map { it.title }

    @Test
    fun search_overTheSeededLibrary_returnsTheMatchesRanked() {
        assertEquals(
            listOf("Ferry Timetables of the Outer Sound", "Night Ferry to Kirkwall"),
            titles(query = "ferry"),
        )
    }

    @Test
    fun search_thatMatchesNothing_returnsEmptyRatherThanTheLibrary() {
        assertTrue("the library must be populated for this to mean anything", titles().size >= 50)
        assertEquals(emptyList<String>(), titles(query = "zeppelin"))
    }

    @Test
    fun aDurationBucket_returnsExactlyTheVideosInItsRange() {
        val all = results()
        for (bucket in DurationBucket.entries) {
            val expected = all.filter { bucket.contains(it.durationSeconds) }.map { it.youtubeId }.toSet()
            val actual = results(bucket = bucket).map { it.youtubeId }.toSet()

            assertTrue("${bucket.label} needs members in the demo dataset", expected.isNotEmpty())
            // The SQL range against the enum's own definition of it: an inclusive upper bound, or a
            // boundary video counted twice, shows up here as a set difference.
            assertEquals("${bucket.label} filtered the wrong videos", expected, actual)
        }
    }

    @Test
    fun theDurationBuckets_neverReturnAVideoOfUnknownLength() {
        val unknown = results().filter { it.durationSeconds <= 0 }.map { it.youtubeId }.toSet()
        assertTrue("the demo dataset needs entries with no duration", unknown.isNotEmpty())

        for (bucket in DurationBucket.entries) {
            val filtered = results(bucket = bucket).map { it.youtubeId }.toSet()
            assertEquals(
                "${bucket.label} swept up videos of unknown length",
                emptySet<String>(),
                filtered intersect unknown,
            )
        }
    }

    @Test
    fun aQueryAndABucket_narrowTogether() {
        // The two "ferry" videos sit either side of the ten-minute boundary (486s and 1580s), so
        // each bucket keeps exactly one of them — a filter applied after ranking, or a query
        // applied to the unfiltered table, both fail here.
        assertEquals(listOf("Ferry Timetables of the Outer Sound"), titles("ferry", DurationBucket.UNDER_10))
        assertEquals(listOf("Night Ferry to Kirkwall"), titles("ferry", DurationBucket.FROM_10_TO_30))
        assertEquals(emptyList<String>(), titles("ferry", DurationBucket.OVER_60))
    }

    /**
     * The SQL range is half-open, `[min, max)`, and no demo video happens to sit on a boundary — so
     * an inclusive upper bound in the query would pass every assertion above. This adds the video
     * the dataset lacks: exactly ten minutes long, which belongs to the bucket that *starts* there.
     *
     * The row is a copy of a seeded one so it is a realistic row rather than a hand-built stub;
     * only the id, the file name and the duration are its own.
     */
    @Test
    fun aVideoExactlyOnABoundary_belongsToTheBucketThatStartsThere() = runBlocking {
        val boundarySeconds = DurationBucket.FROM_10_TO_30.minSeconds
        val id = "boundary-10m"
        db.videoDao().upsert(
            db.videoDao().getAll().first().copy(
                youtubeId = id,
                // Sorts last, so it cannot displace any title an assertion above names.
                fileName = "zzz-boundary.mp4",
                title = "Exactly Ten Minutes",
                durationSeconds = boundarySeconds,
            ),
        )

        assertTrue(
            "the lower bound is inclusive: ${boundarySeconds}s is in ${DurationBucket.FROM_10_TO_30.label}",
            results(bucket = DurationBucket.FROM_10_TO_30).any { it.youtubeId == id },
        )
        assertTrue(
            "the upper bound is exclusive: ${boundarySeconds}s is not in ${DurationBucket.UNDER_10.label}",
            results(bucket = DurationBucket.UNDER_10).none { it.youtubeId == id },
        )
    }

    /**
     * The unfiltered search path must agree with the plain browse path — same videos, same order.
     * They are different queries (`observeAll` versus the browse read), and a blank query is the
     * state the library spends most of its life in.
     */
    @Test
    fun aBlankQueryWithNoBucket_matchesThePlainBrowseList() {
        val browsed = runBlocking { repo.observeVideos().first() }

        assertEquals(browsed.map { it.youtubeId }, results().map { it.youtubeId })
        assertEquals("the browse order is by file name", browsed.sortedBy { it.fileName }, browsed)
    }
}
