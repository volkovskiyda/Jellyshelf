package com.gmail.volkovskiyda.jellyshelf.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.data.DefaultTimeProvider
import com.gmail.volkovskiyda.jellyshelf.data.local.JellyshelfDatabase
import com.gmail.volkovskiyda.jellyshelf.data.remote.ApiSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.IndexEntry
import com.gmail.volkovskiyda.jellyshelf.data.remote.IndexSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.JellyfinClient
import com.gmail.volkovskiyda.jellyshelf.data.remote.TestDemoBackend
import com.gmail.volkovskiyda.jellyshelf.data.remote.YtDlpMetadataSource
import com.gmail.volkovskiyda.jellyshelf.di.provideJson
import com.gmail.volkovskiyda.jellyshelf.domain.DispatcherProvider
import com.gmail.volkovskiyda.jellyshelf.domain.model.BulkProgress
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_OTHERS
import com.gmail.volkovskiyda.jellyshelf.domain.model.Settings
import com.gmail.volkovskiyda.jellyshelf.domain.model.VIRTUAL_CATEGORY_MISSING
import com.gmail.volkovskiyda.jellyshelf.domain.model.VIRTUAL_CATEGORY_UNWATCHED
import com.gmail.volkovskiyda.jellyshelf.ui.FakeSettingsRepository
import com.gmail.volkovskiyda.jellyshelf.util.fakeMetrics
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The "Missing from server" filter and the local-only bulk removal behind it, against a real
 * in-memory Room database and a MockEngine-backed [JellyfinDataSource].
 *
 * Sync does not delete a video the first time the server stops listing it — it counts a miss and
 * waits out `MAX_MISSED_SYNCS`, so that a library mid-rescan can't take user-authored data with
 * it. The cost is that videos deleted on the server sit in the library looking playable in the
 * meantime. This filter is where they can be seen and dropped early, and what these tests pin is
 * that dropping them is *local*: no DELETE ever leaves the device, no connection is required, and
 * a video that turns out to still be on the server simply comes back on the next sync.
 *
 * That last point is why [removeMissing_neverAsksTheServerToDeleteAnything] matters more than it
 * looks. This action sits one screen away from "Remove N watched videos", which deletes the media
 * on the server irrecoverably; wiring the two up the wrong way round is the failure mode worth a
 * test of its own.
 *
 * Instrumented, `runBlocking` and MockEngine for the same reasons as [SyncInstrumentedTest] — see
 * its class comment before changing any of those three.
 */
@RunWith(AndroidJUnit4::class)
class RemoveMissingInstrumentedTest {

    private lateinit var db: JellyshelfDatabase

    private val serverUrl = "http://server:8096"
    private val indexUrl = "http://server/jellyshelf-index.json"

    private val connected = Settings(
        serverUrl = serverUrl,
        apiKey = "KEY",
        accessToken = "",
        userId = "user-1",
        userName = "User",
        libraryId = "",
        libraryName = "",
        indexUrl = indexUrl,
        lastSyncAt = 0L,
        lastSyncLibraryId = "",
    )

    /** Everything on one real dispatcher; the repository only needs these to exist. */
    private val dispatchers = object : DispatcherProvider {
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val ioSequential = Dispatchers.Unconfined
        override val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        override fun ioScope(tag: String, failureMessage: String) =
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    /** Every item id the fake server was asked to delete. Expected to stay empty throughout. */
    private val deleteRequests = mutableListOf<String>()

    /** Never called here — every video gets index metadata, so no auto-fill pass has work to do. */
    private class UnusedYtDlp(context: Context, dispatchers: DispatcherProvider) :
        YtDlpMetadataSource(context, dispatchers, provideJson()) {
        override suspend fun fetch(youtubeId: String): IndexEntry =
            error("yt-dlp must not be called by the removal tests (asked for $youtubeId)")
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, JellyshelfDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private fun itemJson(youtubeId: String) = """
        {"Id":"jf-$youtubeId","Name":"Video $youtubeId",
         "Path":"/media/Video $youtubeId [$youtubeId].mp4",
         "RunTimeTicks":6000000000,"UserData":{"Played":false,"PlaybackPositionTicks":0,"PlayCount":0}}
    """.trimIndent()

    private fun indexEntry(youtubeId: String, channel: String) = IndexEntry(
        id = youtubeId,
        title = "Indexed $youtubeId",
        channel = channel,
        duration = 120L,
        uploadDate = "20260315",
        fetchedAt = 5_000L,
    )

    /** Builds the repository under test over a server holding [serverIds] and serving [index]. */
    private fun repository(
        serverIds: List<String>,
        index: List<IndexEntry>,
        settings: FakeSettingsRepository,
    ): DefaultLibraryRepository {
        val json = provideJson()
        val engine = MockEngine { request ->
            val url = request.url.toString()
            when {
                // Checked before the /Items branch below: the sync's listing hits the same path.
                request.method == HttpMethod.Delete -> {
                    deleteRequests += request.url.segments.last()
                    respond(content = "", status = HttpStatusCode.NoContent)
                }
                url.contains("jellyshelf-index.json") -> respond(
                    content = json.encodeToString(index),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
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
        val dataSource = JellyfinDataSource(JellyfinClient(httpClient))
        val indexSource =
            IndexSource(ApplicationProvider.getApplicationContext(), httpClient, dispatchers, json)
        val ytDlp = UnusedYtDlp(ApplicationProvider.getApplicationContext(), dispatchers)
        return DefaultLibraryRepository(
            db = db,
            settings = settings,
            dispatchers = dispatchers,
            time = DefaultTimeProvider(),
            metrics = fakeMetrics(),
            sources = LibrarySources(
                dataSource,
                ApiSource(httpClient, dispatchers, json),
                indexSource,
                ytDlp,
                TestDemoBackend(indexSource),
            ),
        )
    }

    // Four videos, one per channel, so an emptied channel category is visible as a pruned row.
    private val allIds = List(4) { "video-0000$it" }
    private val index = allIds.mapIndexed { i, id -> indexEntry(id, "Channel $i") }

    /**
     * Syncs the full library, then syncs again with [dropped] no longer listed — one missed sync
     * each, which is what puts them on the Missing from server filter. Returns a repository built
     * over the shortened listing, i.e. the state the app is in when the user opens that filter.
     *
     * A fresh repository per sync, as [SyncInstrumentedTest] does: the server listing is captured
     * by the MockEngine closure, so changing it means building a new one.
     */
    private suspend fun syncedWithMissing(
        settings: FakeSettingsRepository,
        dropped: List<String>,
    ): DefaultLibraryRepository {
        repository(allIds, index, settings).sync()
        val remaining = allIds - dropped.toSet()
        return repository(remaining, index, settings).also { it.sync() }
    }

    private suspend fun storedIds() = db.videoDao().observeAll().first().map { it.youtubeId }.sorted()

    private suspend fun missingIds(repo: DefaultLibraryRepository) =
        repo.observeVideosByCategory(VIRTUAL_CATEGORY_MISSING).first().map { it.youtubeId }.sorted()

    /** Waits for the run to reach its terminal state rather than guessing at a delay. */
    private suspend fun awaitRemoveDone(repo: DefaultLibraryRepository): BulkProgress.Done =
        withTimeout(30_000) {
            repo.bulkRemoveMissing.first { it is BulkProgress.Done }
        } as BulkProgress.Done

    @Test
    fun theMissingFilter_listsExactlyTheVideosTheServerStoppedListing() = runBlocking {
        val settings = FakeSettingsRepository(connected)
        val repo = syncedWithMissing(settings, dropped = listOf(allIds[1], allIds[3]))

        val missing = repo.observeVideosByCategory(VIRTUAL_CATEGORY_MISSING).first()

        assertEquals(listOf(allIds[1], allIds[3]), missing.map { it.youtubeId }.sorted())
        // The same flag the row and the detail screen already render — the filter is not a second,
        // separately-derived notion of "missing" that could disagree with them.
        assertTrue("$missing", missing.all { it.missingFromServer })
    }

    /**
     * The counter resets when the server lists the video again, so the filter has to empty itself
     * without anyone removing anything — otherwise a single flaky sync would leave a permanent
     * "12 missing" badge on the Categories screen.
     */
    @Test
    fun theMissingFilter_emptiesItselfWhenTheServerListsTheVideoAgain() = runBlocking {
        val settings = FakeSettingsRepository(connected)
        syncedWithMissing(settings, dropped = listOf(allIds[1]))

        val repo = repository(allIds, index, settings).also { it.sync() }

        assertEquals(emptyList<String>(), missingIds(repo))
        assertEquals(allIds.sorted(), storedIds())
    }

    /** The Others tab drops empty filters, so this one must appear only once there is a miss. */
    @Test
    fun theOthersTab_offersTheMissingFilterOnlyOnceSomethingIsMissing() = runBlocking {
        val settings = FakeSettingsRepository(connected)
        val synced = repository(allIds, index, settings).also { it.sync() }
        assertFalse(synced.observeOthers().first().any { it.category.id == VIRTUAL_CATEGORY_MISSING })

        val repo = repository(allIds.dropLast(1), index, settings).also { it.sync() }

        val others = repo.observeOthers().first()
        val row = others.single { it.category.id == VIRTUAL_CATEGORY_MISSING }
        assertEquals("Missing from server", row.category.name)
        assertEquals(CATEGORY_TYPE_OTHERS, row.category.type)
        assertEquals(1, row.videoCount)
        // Added to the tab, after the filters it sits with rather than in place of them. Only two
        // of those are here: every video in this fixture has index metadata and none is watched,
        // so Uncategorized, Watched and Continue watching are all empty and correctly dropped.
        assertEquals(
            listOf(VIRTUAL_CATEGORY_UNWATCHED, VIRTUAL_CATEGORY_MISSING),
            others.map { it.category.id },
        )
    }

    @Test
    fun removeMissing_dropsTheMissingRowsAndLeavesTheRestOfTheLibraryAlone() = runBlocking {
        val settings = FakeSettingsRepository(connected)
        val dropped = listOf(allIds[1], allIds[3])
        val repo = syncedWithMissing(settings, dropped)

        repo.startRemoveMissing()
        val done = awaitRemoveDone(repo)

        assertEquals(2, done.total)
        // A local delete has nothing that can fail, so a non-zero count here would be a bug in the
        // runner rather than a server that said no.
        assertEquals(0, done.failed)
        assertEquals((allIds - dropped.toSet()).sorted(), storedIds())
        assertEquals(emptyList<String>(), missingIds(repo))
    }

    /**
     * The point of the whole action: it is local. "Remove N watched videos" one filter over
     * deletes the media on the server irrecoverably, and these two must never be confused.
     */
    @Test
    fun removeMissing_neverAsksTheServerToDeleteAnything() = runBlocking {
        val settings = FakeSettingsRepository(connected)
        val repo = syncedWithMissing(settings, dropped = listOf(allIds[1], allIds[3]))

        repo.startRemoveMissing()
        awaitRemoveDone(repo)

        assertEquals(emptyList<String>(), deleteRequests)
    }

    /**
     * And because it is local, it works with no server at all — unlike the watched removal, which
     * reports every video as failed rather than claim a delete it could not perform.
     */
    @Test
    fun removeMissing_worksWithNoConnectionAtAll() = runBlocking {
        val settings = FakeSettingsRepository(connected)
        val dropped = listOf(allIds[2])
        val repo = syncedWithMissing(settings, dropped)
        settings.clearSession()

        repo.startRemoveMissing()
        val done = awaitRemoveDone(repo)

        assertEquals(1, done.total)
        assertEquals(0, done.failed)
        assertEquals((allIds - dropped.toSet()).sorted(), storedIds())
    }

    @Test
    fun removeMissing_leavesNoOrphanCategoryMembershipsBehind() = runBlocking {
        val settings = FakeSettingsRepository(connected)
        val repo = syncedWithMissing(settings, dropped = listOf(allIds[1]))

        repo.startRemoveMissing()
        assertEquals(0, awaitRemoveDone(repo).failed)

        // The emptied auto-category is gone, not left behind counting a video that no longer
        // exists — an orphan cross-ref would keep inflating its count on the Categories screen.
        val categories = db.categoryDao().observeWithCounts().first()
        assertFalse("$categories", categories.any { it.category.id == "channel:Channel 1" })
        assertEquals(
            listOf("channel:Channel 0" to 1, "channel:Channel 2" to 1, "channel:Channel 3" to 1),
            categories.filter { it.category.id.startsWith("channel:") }
                .map { it.category.id to it.videoCount }
                .sortedBy { it.first },
        )
    }

    /** Nothing missing is a clean, empty run rather than a no-op that strands the header. */
    @Test
    fun removeMissing_withNothingMissing_finishesImmediatelyWithNothingRemoved() = runBlocking {
        val settings = FakeSettingsRepository(connected)
        val repo = repository(allIds, index, settings).also { it.sync() }

        repo.startRemoveMissing()
        val done = awaitRemoveDone(repo)

        assertEquals(0, done.total)
        assertEquals(0, done.failed)
        assertEquals(allIds.sorted(), storedIds())
    }

    /**
     * The two runners are separate slots on the repository, so a watched removal left running
     * cannot show up as progress on this filter (or the other way round). One shared runner would
     * put a "Removing… 3/40" from a server-side delete under the local-only button's heading.
     */
    @Test
    fun theTwoRemovals_reportProgressOnSeparateSlots() = runBlocking {
        val settings = FakeSettingsRepository(connected)
        val repo = syncedWithMissing(settings, dropped = listOf(allIds[1]))

        repo.startRemoveMissing()
        awaitRemoveDone(repo)

        assertEquals(BulkProgress.Idle, repo.bulkRemove.value)
        repo.acknowledgeBulkRemoveMissing()
        assertEquals(BulkProgress.Idle, repo.bulkRemoveMissing.value)
    }
}
