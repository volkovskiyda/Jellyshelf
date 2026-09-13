package com.gmail.volkovskiyda.jellyshelf.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.data.DefaultTimeProvider
import com.gmail.volkovskiyda.jellyshelf.data.local.JellyshelfDatabase
import com.gmail.volkovskiyda.jellyshelf.data.remote.ApiSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.IndexSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.JellyfinClient
import com.gmail.volkovskiyda.jellyshelf.data.remote.TestDemoBackend
import com.gmail.volkovskiyda.jellyshelf.data.remote.YtDlpMetadataSource
import com.gmail.volkovskiyda.jellyshelf.di.provideJson
import com.gmail.volkovskiyda.jellyshelf.domain.DispatcherProvider
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.ui.FakeSettingsRepository
import com.gmail.volkovskiyda.jellyshelf.util.fakeMetrics
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
 * Library search end to end over the seeded demo library: the real repository, the real Room
 * database and the real bundled asset.
 *
 * This is the half [DemoLibrarySearchTest] cannot reach. That one runs the ranking host-side over
 * the same dataset, on rows it builds itself. In production those rows come out of SQL, so the two
 * only meet on a device — a read that returned the wrong columns would leave the ranking scoring
 * blanks, and score them consistently enough to look like a working search.
 *
 * The assertions name titles: the demo dataset is a committed fixture, and an edit that removed the
 * video a search is built on should fail loudly rather than pass vacuously.
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
            time = DefaultTimeProvider(),
            metrics = fakeMetrics(),
            sources = LibrarySources(
                JellyfinDataSource(JellyfinClient(httpClient)),
                ApiSource(httpClient, dispatchers, json),
                indexSource,
                YtDlpMetadataSource(context, dispatchers, json),
                TestDemoBackend(indexSource),
            ),
        )
        repo.seedDemoLibrary()
    }

    @After
    fun tearDown() = db.close()

    /** One emission of the search flow — what the library screen renders for this query. */
    private fun results(query: String = ""): List<Video> =
        runBlocking { repo.searchVideos(query).first() }

    private fun titles(query: String = ""): List<String> = results(query).map { it.title }

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

    /**
     * The search path with a blank query must agree with the plain browse path — same videos, same
     * order. They are different queries (`observeAll` versus the projected browse read), and the
     * agreement is what makes the blank query safe to fall through: nothing in the library sends
     * one here, and one that arrived would come back whole and in file-name order rather than
     * ranked into some arbitrary shape.
     */
    @Test
    fun aBlankQuery_matchesThePlainBrowseList() {
        val browsed = runBlocking { repo.observeVideos().first() }

        assertEquals(browsed.map { it.youtubeId }, results().map { it.youtubeId })
        assertEquals("the browse order is by file name", browsed.sortedBy { it.fileName }, browsed)
    }
}
