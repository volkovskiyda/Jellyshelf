package com.gmail.volkovskiyda.jellyshelf.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.data.DefaultTimeProvider
import com.gmail.volkovskiyda.jellyshelf.data.local.JellyshelfDatabase
import com.gmail.volkovskiyda.jellyshelf.data.remote.IndexEntry
import com.gmail.volkovskiyda.jellyshelf.data.remote.IndexSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.JellyfinClient
import com.gmail.volkovskiyda.jellyshelf.data.remote.TestDemoBackend
import com.gmail.volkovskiyda.jellyshelf.data.remote.YtDlpMetadataSource
import com.gmail.volkovskiyda.jellyshelf.di.provideJson
import com.gmail.volkovskiyda.jellyshelf.domain.DispatcherProvider
import com.gmail.volkovskiyda.jellyshelf.domain.model.BulkProgress
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlaylistResult
import com.gmail.volkovskiyda.jellyshelf.domain.model.SelectionAction
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [DefaultLibraryRepository.startSelectionAction] — the four things a multi-selection can do.
 *
 * [SelectionAction.REMOVE] is what most of this class is about, because it is the only one whose
 * meaning depends on the video it lands on. A hand-made selection mixes videos the server still
 * serves with ones it stopped listing weeks ago and ones that never matched an item at all; the
 * user means the same thing by each, and the run has to turn that into a server delete or a local
 * drop per row. Nothing on screen distinguishes the outcomes, so this is the only place the rule
 * can be checked.
 *
 * Instrumented, `runBlocking` and MockEngine for the same reasons as [RemoveWatchedInstrumentedTest]
 * — see its class comment before changing any of those three.
 */
@RunWith(AndroidJUnit4::class)
class SelectionActionsInstrumentedTest {

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

    private val dispatchers = object : DispatcherProvider {
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val ioSequential = Dispatchers.Unconfined
        override val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        override fun ioScope(tag: String, failureMessage: String) =
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    /** Item ids the fake server refuses to delete (403), as a real one does without rights. */
    private val refusedDeletes = mutableSetOf<String>()

    /** Item ids the fake server has never heard of (404) — already deleted by something else. */
    private val unknownDeletes = mutableSetOf<String>()

    /** Every item id the fake server was asked to delete, in the order it was asked. */
    private val deleteRequests = mutableListOf<String>()

    /** Every played-state write the fake server received, as item id to played. */
    private val playedWrites = mutableListOf<Pair<String, Boolean>>()

    /** The item ids the fake server was asked to put in a playlist. */
    private val playlistItemIds = mutableListOf<String>()

    /** Never called here — every video gets index metadata, so no auto-fill pass has work to do. */
    private class UnusedYtDlp(context: Context, dispatchers: DispatcherProvider) :
        YtDlpMetadataSource(context, dispatchers, provideJson()) {
        override suspend fun fetch(youtubeId: String): IndexEntry =
            error("yt-dlp must not be called by the selection tests (asked for $youtubeId)")
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

    private fun repository(
        serverIds: List<String>,
        index: List<IndexEntry>,
        settings: FakeSettingsRepository,
    ): DefaultLibraryRepository {
        val json = provideJson()
        val engine = MockEngine { request ->
            val url = request.url.toString()
            when {
                // Before the Delete branch below: marking a video *unwatched* is a DELETE on this
                // path, and counting it as an item deletion would make the removal assertions lie.
                url.contains("/PlayedItems/") -> {
                    playedWrites += request.url.segments.last() to (request.method == HttpMethod.Post)
                    respond(content = "", status = HttpStatusCode.NoContent)
                }
                request.method == HttpMethod.Delete -> {
                    val itemId = request.url.segments.last()
                    deleteRequests += itemId
                    when (itemId) {
                        in refusedDeletes -> respondError(HttpStatusCode.Forbidden)
                        in unknownDeletes -> respondError(HttpStatusCode.NotFound)
                        else -> respond(content = "", status = HttpStatusCode.NoContent)
                    }
                }
                url.contains("/Playlists") -> {
                    val body = (request.body as io.ktor.http.content.TextContent).text
                    playlistItemIds += Regex("\"Ids\":\\[([^]]*)]").find(body)
                        ?.groupValues?.get(1).orEmpty()
                        .split(",").map { it.trim('"', ' ') }.filter { it.isNotBlank() }
                    respond(
                        content = """{"Id":"playlist-1"}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
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
            sources = LibrarySources(dataSource, indexSource, ytDlp, TestDemoBackend(indexSource)),
        )
    }

    private suspend fun storedIds() = db.videoDao().observeAll().first().map { it.youtubeId }.sorted()

    /** Waits for the run to reach its terminal state rather than guessing at a delay. */
    private suspend fun awaitDone(repo: DefaultLibraryRepository): BulkProgress.Done =
        withTimeout(30_000) {
            repo.selectionRun.first { it?.progress is BulkProgress.Done }!!.progress
        } as BulkProgress.Done

    private val a = "aaaaaaaaaaa"
    private val b = "bbbbbbbbbbb"
    private val c = "ccccccccccc"

    @Test
    fun remove_deletesOnTheServerAndKeepsGoingPastARefusal() = runBlocking {
        val repo = repository(
            serverIds = listOf(a, b, c),
            index = listOf(indexEntry(a, "Channel A"), indexEntry(b, "Channel B"), indexEntry(c, "Channel C")),
            settings = FakeSettingsRepository(connected),
        )
        repo.sync()
        refusedDeletes += "jf-$b"

        repo.startSelectionAction(SelectionAction.REMOVE, listOf(a, b))
        val done = awaitDone(repo)

        assertEquals(2, done.total)
        assertEquals(1, done.failed)
        // Both were attempted — the refusal did not abort the run.
        assertEquals(listOf("jf-$a", "jf-$b"), deleteRequests.sorted())
        // Only the confirmed delete left the library; the refused video is still on the server.
        assertEquals(listOf(b, c).sorted(), storedIds())
    }

    /**
     * The rule the multi-selection adds to the removal it inherits. A video the server has stopped
     * listing has nothing left to delete there, so it is dropped locally and counted as a success
     * — the alternative is reporting a failure the user can do nothing about, over exactly the
     * videos that are hardest to be rid of.
     */
    @Test
    fun remove_dropsAVideoTheServerNoLongerHasWithoutCallingTheServer() = runBlocking {
        val settings = FakeSettingsRepository(connected)
        val repo = repository(
            serverIds = listOf(a, b),
            index = listOf(indexEntry(a, "Channel A"), indexEntry(b, "Channel B")),
            settings = settings,
        )
        repo.sync()
        // A second sync over a listing that no longer holds `a` marks it missing rather than
        // deleting it — the grace period this action exists to skip.
        repository(listOf(b), listOf(indexEntry(b, "Channel B")), settings).sync()
        assertEquals(1, db.videoDao().get(a)?.missedSyncs)
        deleteRequests.clear()

        repo.startSelectionAction(SelectionAction.REMOVE, listOf(a))
        val done = awaitDone(repo)

        assertEquals(1, done.total)
        assertEquals("a video the server no longer lists is not a failure", 0, done.failed)
        assertTrue("no server call for a video the server has stopped listing", deleteRequests.isEmpty())
        assertEquals(listOf(b), storedIds())
    }

    /** Already gone on the server is the outcome asked for, not something to report as failed. */
    @Test
    fun remove_treatsA404AsDoneAndDropsTheRow() = runBlocking {
        val repo = repository(
            serverIds = listOf(a, b),
            index = listOf(indexEntry(a, "Channel A"), indexEntry(b, "Channel B")),
            settings = FakeSettingsRepository(connected),
        )
        repo.sync()
        unknownDeletes += "jf-$a"

        repo.startSelectionAction(SelectionAction.REMOVE, listOf(a))
        val done = awaitDone(repo)

        assertEquals(0, done.failed)
        assertEquals(listOf("jf-$a"), deleteRequests)
        assertEquals(listOf(b), storedIds())
    }

    @Test
    fun remove_failsRatherThanDeletingLocallyWhenThereIsNoServer() = runBlocking {
        val settings = FakeSettingsRepository(connected)
        val repo = repository(
            serverIds = listOf(a, b),
            index = listOf(indexEntry(a, "Channel A"), indexEntry(b, "Channel B")),
            settings = settings,
        )
        repo.sync()
        settings.clearSession()

        repo.startSelectionAction(SelectionAction.REMOVE, listOf(a, b))
        val done = awaitDone(repo)

        // "Removed 2/2" over videos the server still has is the one summary this must never show.
        assertEquals(2, done.total)
        assertEquals(2, done.failed)
        assertEquals(listOf(a, b).sorted(), storedIds())
    }

    @Test
    fun remove_leavesNoOrphanCategoryMembershipsBehind() = runBlocking {
        val repo = repository(
            serverIds = listOf(a, b),
            index = listOf(indexEntry(a, "Channel A"), indexEntry(b, "Channel B")),
            settings = FakeSettingsRepository(connected),
        )
        repo.sync()

        repo.startSelectionAction(SelectionAction.REMOVE, listOf(a))
        assertEquals(0, awaitDone(repo).failed)

        val categories = db.categoryDao().observeWithCounts().first()
        assertFalse("$categories", categories.any { it.category.id == "channel:Channel A" })
    }

    @Test
    fun markWatched_writesEverySelectedVideoLocallyAndOnTheServer() = runBlocking {
        val repo = repository(
            serverIds = listOf(a, b, c),
            index = listOf(indexEntry(a, "Channel A"), indexEntry(b, "Channel B"), indexEntry(c, "Channel C")),
            settings = FakeSettingsRepository(connected),
        )
        repo.sync()

        repo.startSelectionAction(SelectionAction.MARK_WATCHED, listOf(a, c))
        val done = awaitDone(repo)

        assertEquals(2, done.total)
        assertEquals(0, done.failed)
        assertEquals(true, db.videoDao().get(a)?.played)
        assertEquals(true, db.videoDao().get(c)?.played)
        assertEquals("the unselected video was left alone", false, db.videoDao().get(b)?.played)
        assertEquals(listOf("jf-$a", "jf-$c"), playedWrites.map { it.first }.sorted())
    }

    @Test
    fun markUnwatched_clearsWhatMarkWatchedSet() = runBlocking {
        val repo = repository(
            serverIds = listOf(a, b),
            index = listOf(indexEntry(a, "Channel A"), indexEntry(b, "Channel B")),
            settings = FakeSettingsRepository(connected),
        )
        repo.sync()
        db.videoDao().updateWatchState(a, played = true, positionTicks = 0L)

        repo.startSelectionAction(SelectionAction.MARK_UNWATCHED, listOf(a))
        assertEquals(0, awaitDone(repo).failed)

        assertEquals(false, db.videoDao().get(a)?.played)
    }

    /**
     * A playlist is built from the selection rather than from a whole category — the point of
     * building one by hand. Videos the server has no item for are left out rather than failing the
     * call, since there is nothing to put in a playlist for them.
     */
    @Test
    fun createPlaylist_usesTheSelectedVideosAndSkipsOnesTheServerHasNoItemFor() = runBlocking {
        val repo = repository(
            serverIds = listOf(a, b, c),
            index = listOf(indexEntry(a, "Channel A"), indexEntry(b, "Channel B"), indexEntry(c, "Channel C")),
            settings = FakeSettingsRepository(connected),
        )
        repo.sync()
        // As an unmatched local row would be: in the library, with nothing on the server behind it.
        db.videoDao().upsert(db.videoDao().get(c)!!.copy(jellyfinItemId = null))

        val result = repo.createPlaylistFromVideos(listOf(a, c), "Two of them")

        assertEquals(PlaylistResult.Success("Two of them", 1), result)
        assertEquals(listOf("jf-$a"), playlistItemIds)
    }

    @Test
    fun createPlaylist_refusesASelectionWithNothingTheServerCanHold() = runBlocking {
        val repo = repository(
            serverIds = listOf(a),
            index = listOf(indexEntry(a, "Channel A")),
            settings = FakeSettingsRepository(connected),
        )
        repo.sync()
        db.videoDao().upsert(db.videoDao().get(a)!!.copy(jellyfinItemId = null))

        val result = repo.createPlaylistFromVideos(listOf(a), "Empty")

        assertTrue("$result", result is PlaylistResult.Error)
        assertTrue("no server call", playlistItemIds.isEmpty())
    }

    /**
     * Ids whose row has gone — a sync deleted it, an earlier run removed it — are simply not among
     * the targets, and the run's total is what it actually found. A selection outlives the emission
     * it was made from, so this is a normal case rather than a broken one.
     */
    @Test
    fun aRunOverIdsThatNoLongerExist_reportsWhatItActuallyFound() = runBlocking {
        val repo = repository(
            serverIds = listOf(a),
            index = listOf(indexEntry(a, "Channel A")),
            settings = FakeSettingsRepository(connected),
        )
        repo.sync()

        repo.startSelectionAction(SelectionAction.MARK_WATCHED, listOf(a, "zzzzzzzzzzz"))
        val done = awaitDone(repo)

        assertEquals(1, done.total)
        assertEquals(0, done.failed)
    }
}
