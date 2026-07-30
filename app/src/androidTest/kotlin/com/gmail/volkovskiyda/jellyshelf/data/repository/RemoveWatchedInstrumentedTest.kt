package com.gmail.volkovskiyda.jellyshelf.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.data.local.JellyshelfDatabase
import com.gmail.volkovskiyda.jellyshelf.data.remote.IndexEntry
import com.gmail.volkovskiyda.jellyshelf.data.remote.IndexSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.JellyfinClient
import com.gmail.volkovskiyda.jellyshelf.data.remote.YtDlpMetadataSource
import com.gmail.volkovskiyda.jellyshelf.di.provideJson
import com.gmail.volkovskiyda.jellyshelf.domain.DispatcherProvider
import com.gmail.volkovskiyda.jellyshelf.domain.model.BulkProgress
import com.gmail.volkovskiyda.jellyshelf.domain.model.Settings
import com.gmail.volkovskiyda.jellyshelf.ui.FakeSettingsRepository
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for [DefaultLibraryRepository.startRemoveWatched] — the "Remove N watched videos" action —
 * against a real in-memory Room database and a MockEngine-backed [JellyfinDataSource].
 *
 * This is the one action in the app that destroys data the user cannot get back: it deletes the
 * media on the Jellyfin server. The rules worth pinning down are that a video only leaves the
 * local library once the server has confirmed its delete, that one refused delete never strands
 * the rest of the run, and that the rows it does remove leave no orphan category memberships.
 *
 * Instrumented, `runBlocking` and MockEngine for the same reasons as [SyncInstrumentedTest] —
 * see its class comment before changing any of those three.
 */
@RunWith(AndroidJUnit4::class)
class RemoveWatchedInstrumentedTest {

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

    /** Jellyfin item ids the fake server refuses to delete (403), as a real one does without rights. */
    private val refusedDeletes = mutableSetOf<String>()

    /** Every item id the fake server was asked to delete, in the order it was asked. */
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
                    val itemId = request.url.segments.last()
                    deleteRequests += itemId
                    if (itemId in refusedDeletes) {
                        respondError(HttpStatusCode.Forbidden)
                    } else {
                        respond(content = "", status = HttpStatusCode.NoContent)
                    }
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
        val indexSource = IndexSource(httpClient, dispatchers, json)
        val ytDlp = UnusedYtDlp(ApplicationProvider.getApplicationContext(), dispatchers)
        return DefaultLibraryRepository(db, dataSource, indexSource, settings, ytDlp, dispatchers)
    }

    private suspend fun storedIds() = db.videoDao().observeAll().first().map { it.youtubeId }.sorted()

    private suspend fun markWatched(vararg youtubeIds: String) = youtubeIds.forEach {
        db.videoDao().updateWatchState(it, played = true, positionTicks = 0L)
    }

    /** Waits for the run to reach its terminal state rather than guessing at a delay. */
    private suspend fun awaitRemoveDone(repo: DefaultLibraryRepository): BulkProgress.Done =
        withTimeout(30_000) { repo.bulkRemove.first { it is BulkProgress.Done } } as BulkProgress.Done

    @Test
    fun removeWatched_deletesConfirmedVideosAndKeepsGoingPastARefusal() = runBlocking {
        val repo = repository(
            serverIds = listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"),
            index = listOf(
                indexEntry("aaaaaaaaaaa", "Channel A"),
                indexEntry("bbbbbbbbbbb", "Channel B"),
                indexEntry("ccccccccccc", "Channel C"),
            ),
            settings = FakeSettingsRepository(connected),
        )
        repo.sync()
        markWatched("aaaaaaaaaaa", "bbbbbbbbbbb")
        // The server refuses this one, as it would for a user without deletion rights.
        refusedDeletes += "jf-bbbbbbbbbbb"

        repo.startRemoveWatched()
        val done = awaitRemoveDone(repo)

        assertEquals(2, done.total)
        assertEquals(1, done.failed)
        // Both watched videos were attempted — the refusal did not abort the run.
        assertEquals(listOf("jf-aaaaaaaaaaa", "jf-bbbbbbbbbbb"), deleteRequests.sorted())
        // Only the confirmed delete left the library. The refused video is still there (it is still
        // on the server), and so is the unwatched one the run never touched.
        assertEquals(listOf("bbbbbbbbbbb", "ccccccccccc"), storedIds())
    }

    @Test
    fun removeWatched_leavesNoOrphanCategoryMembershipsBehind() = runBlocking {
        val repo = repository(
            serverIds = listOf("aaaaaaaaaaa", "bbbbbbbbbbb"),
            index = listOf(
                indexEntry("aaaaaaaaaaa", "Channel A"),
                indexEntry("bbbbbbbbbbb", "Channel B"),
            ),
            settings = FakeSettingsRepository(connected),
        )
        repo.sync()
        markWatched("aaaaaaaaaaa")

        repo.startRemoveWatched()
        assertEquals(0, awaitRemoveDone(repo).failed)

        // The emptied auto-category is gone, not left behind counting a video that no longer
        // exists — an orphan cross-ref would keep inflating its count on the Categories screen.
        val categories = db.categoryDao().observeWithCounts().first()
        assertFalse("$categories", categories.any { it.category.id == "channel:Channel A" })
        assertEquals(
            listOf("channel:Channel B" to 1),
            categories.filter { it.category.id.startsWith("channel:") }
                .map { it.category.id to it.videoCount },
        )
    }

    @Test
    fun removeWatched_deletesNothingLocallyWhenThereIsNoServerToDeleteOn() = runBlocking {
        val settings = FakeSettingsRepository(connected)
        val repo = repository(
            serverIds = listOf("aaaaaaaaaaa", "bbbbbbbbbbb"),
            index = listOf(
                indexEntry("aaaaaaaaaaa", "Channel A"),
                indexEntry("bbbbbbbbbbb", "Channel B"),
            ),
            settings = settings,
        )
        repo.sync()
        markWatched("aaaaaaaaaaa", "bbbbbbbbbbb")
        settings.clearSession()

        repo.startRemoveWatched()
        val done = awaitRemoveDone(repo)

        // Reported as all-failed rather than as a clean run: a "Removed 2/2" on videos that are
        // all still on the server is the one summary this must never show.
        assertEquals(2, done.total)
        assertEquals(2, done.failed)
        assertEquals(emptyList<String>(), deleteRequests)
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), storedIds())
    }
}
