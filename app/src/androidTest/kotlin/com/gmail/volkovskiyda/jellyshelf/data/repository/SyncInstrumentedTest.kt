package com.gmail.volkovskiyda.jellyshelf.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.data.local.JellyshelfDatabase
import com.gmail.volkovskiyda.jellyshelf.data.remote.IndexEntry
import com.gmail.volkovskiyda.jellyshelf.data.remote.JellyfinClient
import com.gmail.volkovskiyda.jellyshelf.data.remote.YtDlpMetadataSource
import com.gmail.volkovskiyda.jellyshelf.di.provideJson
import com.gmail.volkovskiyda.jellyshelf.domain.DispatcherProvider
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_INDEX
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_JELLYFIN
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_YTDLP
import com.gmail.volkovskiyda.jellyshelf.domain.model.Settings
import com.gmail.volkovskiyda.jellyshelf.domain.model.SyncResult
import com.gmail.volkovskiyda.jellyshelf.ui.FakeSettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end tests for [DefaultLibraryRepository.sync] against a real in-memory Room database and
 * a MockEngine-backed [JellyfinDataSource] — the highest-value untested path in the codebase, since
 * every one of these rules silently deletes or downgrades user-visible data when it breaks.
 *
 * Instrumented rather than host-side because Room needs a real Android runtime and there is no
 * Robolectric here (a deliberate decision — see the plan's item 17). The Jellyfin side is a mock
 * *engine*, not a fake data source: `JellyfinDataSource` is a concrete class, and driving real
 * HTTP + real kotlinx decoding through it exercises the paging loop and DTO mapping too.
 *
 * yt-dlp is the one dependency that is faked outright ([FakeYtDlp]) — the real one shells out to a
 * Python runtime, and the auto-fill cases below would otherwise hit YouTube from a test.
 *
 * **`runBlocking`, deliberately, not `runTest`.** These do real Room and real (mock-engine) HTTP
 * work, so the coroutine genuinely parks on other dispatchers — and `runTest`'s virtual clock
 * treats every such park as idle and fast-forwards. That silently blew through the auto-fill
 * pass's own `withTimeoutOrNull` budget, cancelling it and reporting 0 filled on a run that was
 * working perfectly. Virtual time buys nothing here; don't convert these back.
 */
@RunWith(AndroidJUnit4::class)
class SyncInstrumentedTest {

    private lateinit var db: JellyshelfDatabase

    private val serverUrl = "http://server:8096"
    private val indexUrl = "http://server/jellyshelf-index.json"

    private val connected = Settings(
        serverUrl = serverUrl,
        apiKey = "KEY",
        userId = "user-1",
        userName = "User",
        libraryId = "",
        libraryName = "",
        indexUrl = "",
        lastSyncAt = 0L,
        lastSyncLibraryId = "",
    )

    /** Everything on one real dispatcher; the repository only needs these to exist. */
    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val ioSequential = Dispatchers.Unconfined
        override val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        override fun ioScope(tag: String, failureMessage: String) =
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    /** Serves a canned yt-dlp answer, or throws for ids listed in [failing]. Never touches Python. */
    private class FakeYtDlp(
        context: Context,
        dispatchers: DispatcherProvider,
        private val failing: Set<String> = emptySet(),
    ) : YtDlpMetadataSource(context, dispatchers) {
        val fetched = mutableListOf<String>()

        override suspend fun fetch(youtubeId: String): IndexEntry {
            fetched += youtubeId
            if (youtubeId in failing) error("yt-dlp exploded for $youtubeId")
            return IndexEntry(
                id = youtubeId,
                title = "yt-dlp $youtubeId",
                channel = "Fetched Channel",
                duration = 300L,
                uploadDate = "20260101",
                fetchedAt = 9_000L,
            )
        }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, JellyshelfDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private fun itemJson(youtubeId: String, name: String = "Video $youtubeId") = """
        {"Id":"jf-$youtubeId","Name":"$name","Path":"/media/$name [$youtubeId].mp4",
         "RunTimeTicks":6000000000,"UserData":{"Played":false,"PlaybackPositionTicks":0,"PlayCount":0}}
    """.trimIndent()

    /**
     * Builds the repository under test.
     *
     * @param serverIds youtube ids the server listing contains this sync.
     * @param index entries the metadata index serves, or null to make the index fetch fail.
     */
    private fun repository(
        serverIds: List<String>,
        index: List<IndexEntry>? = emptyList(),
        settings: FakeSettingsRepository = FakeSettingsRepository(connected),
        ytDlp: YtDlpMetadataSource = FakeYtDlp(
            ApplicationProvider.getApplicationContext(),
            dispatchers,
        ),
    ): DefaultLibraryRepository {
        val json = provideJson()
        val engine = MockEngine { request ->
            val url = request.url.toString()
            when {
                url.contains("jellyshelf-index.json") -> when (index) {
                    null -> respondError(HttpStatusCode.NotFound)
                    else -> respond(
                        content = json.encodeToString(index),
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                // Honour StartIndex so the paging loop terminates the way it does in production:
                // the first page carries everything, the second comes back short (empty).
                url.contains("/Items") -> {
                    val startIndex = request.url.parameters["startIndex"]?.toInt() ?: 0
                    val page = serverIds.drop(startIndex).joinToString(",") { itemJson(it) }
                    respond(
                        content = """{"Items":[$page],"TotalRecordCount":${serverIds.size}}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        val httpClient = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(json) }
        }
        val dataSource = JellyfinDataSource(JellyfinClient(httpClient), httpClient, dispatchers, json)
        return DefaultLibraryRepository(db, dataSource, settings, ytDlp, dispatchers)
    }

    private suspend fun storedIds() = db.videoDao().observeAll().first().map { it.youtubeId }.sorted()

    // --- the happy path ---

    @Test
    fun sync_storesServerItemsAndDerivesAutoCategories() = runBlocking {
        val repo = repository(
            serverIds = listOf("aaaaaaaaaaa", "bbbbbbbbbbb"),
            index = listOf(
                IndexEntry(
                    id = "aaaaaaaaaaa",
                    title = "Indexed A",
                    channel = "Channel One",
                    duration = 120L,
                    uploadDate = "20260315",
                    categories = listOf("Music"),
                    fetchedAt = 5_000L,
                ),
            ),
            settings = FakeSettingsRepository(connected.copy(indexUrl = indexUrl)),
        )

        val result = repo.sync()

        assertTrue("expected success, got $result", result is SyncResult.Success)
        result as SyncResult.Success
        assertEquals(2, result.matched)
        assertEquals(1, result.indexed)
        assertFalse(result.indexDegraded)
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), storedIds())

        val indexed = db.videoDao().get("aaaaaaaaaaa")!!
        assertEquals(METADATA_SOURCE_INDEX, indexed.metadataSource)
        assertEquals("Indexed A", indexed.title)
        // Auto-categories along every dimension the metadata supports.
        val categories = db.categoryDao().observeWithCounts().first().map { it.category.id }
        assertTrue("$categories", categories.contains("channel:Channel One"))
        assertTrue("$categories", categories.contains("year:2026"))
        assertTrue("$categories", categories.contains("month:2026-03"))
        assertTrue("$categories", categories.contains("ytcat:Music"))
    }

    // --- item 09: a failed index fetch is reported, never silently swallowed ---

    @Test
    fun sync_reportsIndexDegradedWhenTheIndexCannotBeFetched() = runBlocking {
        val repo = repository(
            serverIds = listOf("aaaaaaaaaaa"),
            index = null,
            settings = FakeSettingsRepository(connected.copy(indexUrl = indexUrl)),
        )

        val result = repo.sync() as SyncResult.Success

        assertTrue(result.indexDegraded)
        // The sync still succeeded — the video is stored, just without index metadata.
        assertEquals(listOf("aaaaaaaaaaa"), storedIds())
    }

    @Test
    fun sync_doesNotReportDegradedWhenNoIndexIsConfigured() = runBlocking {
        val repo = repository(serverIds = listOf("aaaaaaaaaaa"))

        assertFalse((repo.sync() as SyncResult.Success).indexDegraded)
    }

    /** A previously indexed row must survive a failed index fetch rather than downgrade. */
    @Test
    fun sync_keepsIndexMetadataWhenTheIndexFetchFails() = runBlocking {
        val settings = FakeSettingsRepository(connected.copy(indexUrl = indexUrl))
        repository(
            serverIds = listOf("aaaaaaaaaaa"),
            index = listOf(IndexEntry(id = "aaaaaaaaaaa", title = "Indexed A", fetchedAt = 5_000L)),
            settings = settings,
        ).sync()

        repository(serverIds = listOf("aaaaaaaaaaa"), index = null, settings = settings).sync()

        val row = db.videoDao().get("aaaaaaaaaaa")!!
        assertEquals(METADATA_SOURCE_INDEX, row.metadataSource)
        assertEquals("Indexed A", row.title)
    }

    // --- item 03: the delete grace period ---

    @Test
    fun sync_countsAMissBeforeDeletingAVideoTheServerStoppedListing() = runBlocking {
        val settings = FakeSettingsRepository(connected)
        val present = List(4) { "video-0000$it" }
        repository(serverIds = present, settings = settings).sync()

        // One video disappears; the listing is still long enough to be trusted.
        val remaining = present.dropLast(1)
        repository(serverIds = remaining, settings = settings).sync()

        assertEquals(present.sorted(), storedIds())
        assertEquals(1, db.videoDao().get(present.last())!!.missedSyncs)
    }

    @Test
    fun sync_deletesAVideoOnlyAfterTheGraceLimit() = runBlocking {
        val settings = FakeSettingsRepository(connected)
        val present = List(4) { "video-0000$it" }
        val remaining = present.dropLast(1)
        repository(serverIds = present, settings = settings).sync()

        // MAX_MISSED_SYNCS is 3: still present after two misses, gone on the third.
        repeat(2) { repository(serverIds = remaining, settings = settings).sync() }
        assertEquals(present.sorted(), storedIds())

        repository(serverIds = remaining, settings = settings).sync()
        assertEquals(remaining.sorted(), storedIds())
    }

    @Test
    fun sync_resetsTheMissCounterWhenTheVideoComesBack() = runBlocking {
        val settings = FakeSettingsRepository(connected)
        val present = List(4) { "video-0000$it" }
        repository(serverIds = present, settings = settings).sync()
        repository(serverIds = present.dropLast(1), settings = settings).sync()
        assertEquals(1, db.videoDao().get(present.last())!!.missedSyncs)

        repository(serverIds = present, settings = settings).sync()

        assertEquals(0, db.videoDao().get(present.last())!!.missedSyncs)
    }

    /**
     * A listing far shorter than the library is a server mid-rescan, not a mass deletion — such a
     * sync must not even increment the miss counter, or three flaky syncs would empty the library.
     */
    @Test
    fun sync_prunesNothingWhenTheListingIsTooShortToBelieve() = runBlocking {
        val settings = FakeSettingsRepository(connected)
        val present = List(10) { "video-0000$it" }
        repository(serverIds = present, settings = settings).sync()

        repository(serverIds = present.take(2), settings = settings).sync()

        assertEquals(present.sorted(), storedIds())
        assertEquals(0, db.videoDao().get(present.last())!!.missedSyncs)
    }

    /** A re-scoped library is the one case where a shorter listing means deliberate deletions. */
    @Test
    fun sync_deletesImmediatelyWhenTheScopeChanged() = runBlocking {
        val settings = FakeSettingsRepository(connected)
        val present = List(10) { "video-0000$it" }
        repository(serverIds = present, settings = settings).sync()

        settings.setLibrary("other-folder", "Other")
        repository(serverIds = present.take(2), settings = settings).sync()

        assertEquals(present.take(2).sorted(), storedIds())
    }

    // --- item 20: the sync auto-fill ---

    @Test
    fun sync_autoFillsASmallMetadataGapWithYtDlp() = runBlocking {
        val ytDlp = FakeYtDlp(ApplicationProvider.getApplicationContext(), dispatchers)
        val repo = repository(serverIds = listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), ytDlp = ytDlp)

        val result = repo.sync() as SyncResult.Success

        assertEquals(2, result.autoFilled)
        assertEquals(0, result.autoFillFailed)
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), ytDlp.fetched.sorted())
        assertEquals(METADATA_SOURCE_YTDLP, db.videoDao().get("aaaaaaaaaaa")!!.metadataSource)
    }

    @Test
    fun sync_leavesALargeMetadataGapToTheDeliberateBulkFetch() = runBlocking {
        val ytDlp = FakeYtDlp(ApplicationProvider.getApplicationContext(), dispatchers)
        // Ten missing videos is the threshold: the pass must not run at all.
        val repo = repository(serverIds = List(10) { "video-0000$it" }, ytDlp = ytDlp)

        val result = repo.sync() as SyncResult.Success

        assertEquals(0, result.autoFilled)
        assertTrue("yt-dlp must not be called: ${ytDlp.fetched}", ytDlp.fetched.isEmpty())
    }

    @Test
    fun sync_recordsAFailedAutoFillWithoutFailingTheSync() = runBlocking {
        val ytDlp = FakeYtDlp(
            ApplicationProvider.getApplicationContext(),
            dispatchers,
            failing = setOf("bbbbbbbbbbb"),
        )
        val repo = repository(serverIds = listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), ytDlp = ytDlp)

        val result = repo.sync() as SyncResult.Success

        assertEquals(1, result.autoFilled)
        assertEquals(1, result.autoFillFailed)
        val failed = db.videoDao().get("bbbbbbbbbbb")!!
        assertEquals(METADATA_SOURCE_JELLYFIN, failed.metadataSource)
        assertNotNull("the failure reason must be stored", failed.lastFetchError)
        assertTrue(failed.lastFetchErrorAt > 0L)
    }

    /** A later success has to clear the stored reason, or the detail screen reports a stale one. */
    @Test
    fun sync_clearsAStoredFetchErrorOnceTheFetchSucceeds() = runBlocking {
        val settings = FakeSettingsRepository(connected)
        val ids = listOf("aaaaaaaaaaa")
        repository(
            serverIds = ids,
            settings = settings,
            ytDlp = FakeYtDlp(
                ApplicationProvider.getApplicationContext(),
                dispatchers,
                failing = ids.toSet(),
            ),
        ).sync()
        assertNotNull(db.videoDao().get("aaaaaaaaaaa")!!.lastFetchError)

        repository(serverIds = ids, settings = settings).sync()

        val row = db.videoDao().get("aaaaaaaaaaa")!!
        assertNull(row.lastFetchError)
        assertEquals(0L, row.lastFetchErrorAt)
    }

    // --- failure classification and removal ---

    @Test
    fun sync_withoutCredentialsFailsPermanently() = runBlocking {
        val repo = repository(serverIds = emptyList(), settings = FakeSettingsRepository())

        val result = repo.sync()

        assertTrue(result is SyncResult.Error)
        assertFalse((result as SyncResult.Error).retryable)
    }

    @Test
    fun removeVideo_dropsTheRowAndItsCategoryMemberships() = runBlocking {
        val repo = repository(
            serverIds = listOf("aaaaaaaaaaa", "bbbbbbbbbbb"),
            index = listOf(
                IndexEntry(id = "aaaaaaaaaaa", channel = "Channel One", fetchedAt = 5_000L),
                IndexEntry(id = "bbbbbbbbbbb", channel = "Channel Two", fetchedAt = 5_000L),
            ),
            settings = FakeSettingsRepository(connected.copy(indexUrl = indexUrl)),
        )
        repo.sync()

        repo.removeVideo("aaaaaaaaaaa")

        assertEquals(listOf("bbbbbbbbbbb"), storedIds())
        // Its channel category had one member and must not linger empty.
        val categories = db.categoryDao().observeWithCounts().first().map { it.category.id }
        assertFalse("$categories", categories.contains("channel:Channel One"))
        assertTrue("$categories", categories.contains("channel:Channel Two"))
    }
}
