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
import com.gmail.volkovskiyda.jellyshelf.data.remote.TestDemoBackend
import com.gmail.volkovskiyda.jellyshelf.data.remote.YtDlpMetadataSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.testJellyfinDataSource
import com.gmail.volkovskiyda.jellyshelf.di.provideJson
import com.gmail.volkovskiyda.jellyshelf.domain.DispatcherProvider
import com.gmail.volkovskiyda.jellyshelf.domain.model.BulkProgress
import com.gmail.volkovskiyda.jellyshelf.domain.model.FetchResult
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_JELLYFIN
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_YTDLP
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlaylistResult
import com.gmail.volkovskiyda.jellyshelf.domain.model.SyncResult
import com.gmail.volkovskiyda.jellyshelf.domain.model.VIRTUAL_CATEGORY_WATCHED
import com.gmail.volkovskiyda.jellyshelf.ui.FakeSettingsRepository
import com.gmail.volkovskiyda.jellyshelf.util.fakeMetrics
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Generous: these runs are instant with the pauses stubbed out, so it only ever catches a hang. */
private const val AWAIT_TIMEOUT_MS = 30_000L

/**
 * The actions a demo install can take that would otherwise need a Jellyfin server or a yt-dlp
 * extraction: bulk metadata fetch, bulk removal, single-video fetch, playlist creation and sync.
 *
 * What is being pinned is that they *do the thing* rather than decline. A demo has no credentials,
 * so before [com.gmail.volkovskiyda.jellyshelf.data.remote.DemoBackend] existed the removal
 * reported every video as failed without attempting one, and the metadata fetch shelled out to a
 * real Python runtime to ask YouTube about ids that cannot exist. Both wrongs were silent: the
 * screens looked plausible and simply never worked. So every assertion below is about observable
 * library state — rows gone, metadata written, errors recorded — and both the network engine and
 * yt-dlp are wired to throw, which is what proves nothing reached them.
 *
 * The dice and the simulated latency are pinned by [TestDemoBackend]; everything else about the
 * backend is real, including the bundled entries it serves and the wording of its failures.
 *
 * Instrumented, `runBlocking` and a real in-memory Room for the same reasons as
 * [SyncInstrumentedTest] — see its class comment before changing any of those.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("TooManyFunctions") // one test per demo action, plus its failure case
class DemoActionsInstrumentedTest {

    private lateinit var db: JellyshelfDatabase

    private val settings = FakeSettingsRepository()

    private val dispatchers = object : DispatcherProvider {
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val ioSequential = Dispatchers.Unconfined
        override val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        override fun ioScope(tag: String, failureMessage: String) =
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    /** The whole point of the demo backend is that this is never reached. */
    private class RefusingYtDlp(context: Context, dispatchers: DispatcherProvider) :
        YtDlpMetadataSource(context, dispatchers, provideJson()) {
        override suspend fun fetch(youtubeId: String): IndexEntry =
            error("a demo fetch must not reach yt-dlp (asked for $youtubeId)")
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, JellyshelfDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    /**
     * A seeded demo library and the repository over it. [failCall] decides which of the backend's
     * calls fail, by 1-based call index — the default is a fake server that never refuses.
     */
    private suspend fun demoRepository(
        failCall: (call: Int) -> Boolean = { false },
    ): DefaultLibraryRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val json = provideJson()
        val engine = MockEngine { error("a demo install must not make network requests") }
        val httpClient = HttpClient(engine) { expectSuccess = true }
        val indexSource = IndexSource(context, httpClient, dispatchers, json)
        return DefaultLibraryRepository(
            db = db,
            settings = settings,
            dispatchers = dispatchers,
            time = DefaultTimeProvider(),
            metrics = fakeMetrics(),
            sources = LibrarySources(
                testJellyfinDataSource(httpClient),
                ApiSource(httpClient, dispatchers, json),
                indexSource,
                RefusingYtDlp(context, dispatchers),
                TestDemoBackend(indexSource, failCall),
            ),
        ).also { it.seedDemoLibrary() }
    }

    /** Waits for a bulk run to reach its terminal state rather than guessing at a delay. */
    private suspend fun awaitDone(progress: StateFlow<BulkProgress>) =
        withTimeout(AWAIT_TIMEOUT_MS) { progress.first { it is BulkProgress.Done } } as BulkProgress.Done

    private suspend fun uncategorized() = db.videoDao().getBySource(METADATA_SOURCE_JELLYFIN)

    // --- fetching metadata ---

    @Test
    fun fetchMissing_fillsTheBareRowsFromTheBundledDataset() = runBlocking {
        val repo = demoRepository()
        val before = uncategorized()
        assertTrue("the seed must leave rows for this action to work on", before.isNotEmpty())
        val categoriesBefore = db.categoryDao().observeWithCounts().first().size

        repo.startFetchMissing()
        val done = awaitDone(repo.bulkFetch)

        assertEquals(before.size, done.total)
        assertEquals(0, done.failed)
        assertEquals("nothing may be left uncategorized", emptyList<Any>(), uncategorized())
        before.forEach { was ->
            val now = db.videoDao().get(was.youtubeId)!!
            assertEquals(METADATA_SOURCE_YTDLP, now.metadataSource)
            assertNotEquals("a fetch must visibly retitle the row", was.title, now.title)
            assertTrue("fetched rows need a channel", !now.channel.isNullOrBlank())
            assertTrue("fetched rows need a duration", now.durationSeconds > 0)
            assertNull("a successful fetch clears the last error", now.lastFetchError)
        }
        assertTrue(
            "the new metadata must produce new auto-categories",
            db.categoryDao().observeWithCounts().first().size > categoriesBefore,
        )
    }

    @Test
    fun fetchMissing_recordsASimulatedFailureOnEveryRowItCouldNotFetch() = runBlocking {
        val repo = demoRepository(failCall = { true })
        val before = uncategorized()

        repo.startFetchMissing()
        val done = awaitDone(repo.bulkFetch)

        assertEquals(before.size, done.total)
        assertEquals("every fetch was refused", before.size, done.failed)
        assertEquals("the rows stay uncategorized", before.size, uncategorized().size)
        uncategorized().forEach { row ->
            // The detail screen renders this verbatim, so it has to read like the real thing.
            assertTrue(
                "expected yt-dlp wording, got ${row.lastFetchError}",
                row.lastFetchError?.startsWith("ERROR: [youtube] ${row.youtubeId}:") == true,
            )
            assertTrue("the failure needs a timestamp", row.lastFetchErrorAt > 0L)
        }
    }

    @Test
    fun fetchMetadata_worksForOneVideoAtATime() = runBlocking {
        val repo = demoRepository()
        val bare = uncategorized().first()

        val result = repo.fetchMetadata(bare.youtubeId)

        assertTrue("$result", result is FetchResult.Success)
        assertNotEquals(bare.title, (result as FetchResult.Success).title)
        assertEquals(METADATA_SOURCE_YTDLP, db.videoDao().get(bare.youtubeId)!!.metadataSource)
    }

    /** "Update metadata" on a row that already has some — every demo video offers it. */
    @Test
    fun fetchMetadata_refreshesARowThatAlreadyHasMetadata() = runBlocking {
        val repo = demoRepository()
        val described = db.videoDao().getAll().first { it.metadataSource != METADATA_SOURCE_JELLYFIN }

        val result = repo.fetchMetadata(described.youtubeId)

        assertEquals(FetchResult.Success(described.title), result)
        val now = db.videoDao().get(described.youtubeId)!!
        assertEquals(METADATA_SOURCE_YTDLP, now.metadataSource)
        assertEquals("a refresh must not lose the row's own metadata", described.channel, now.channel)
    }

    @Test
    fun fetchMetadata_surfacesASimulatedFailureToTheCaller() = runBlocking {
        val repo = demoRepository(failCall = { true })
        val bare = uncategorized().first()

        val result = repo.fetchMetadata(bare.youtubeId)

        assertTrue("$result", result is FetchResult.Error)
        assertTrue("$result", (result as FetchResult.Error).message.startsWith("ERROR: [youtube]"))
    }

    // --- removing watched videos ---

    @Test
    fun removeWatched_dropsTheRowsWithNoServerAnywhere() = runBlocking {
        val repo = demoRepository()
        val watched = db.videoDao().getWatched()
        assertTrue("the seed must produce watched videos", watched.isNotEmpty())

        repo.startRemoveWatched()
        val done = awaitDone(repo.bulkRemove)

        assertEquals(watched.size, done.total)
        assertEquals(0, done.failed)
        assertEquals(
            "a confirmed delete leaves the library",
            emptyList<String>(),
            watched.map { it.youtubeId }.filter { db.videoDao().get(it) != null },
        )
        // Removal is what orphans category memberships, and an orphan shows up as a category
        // still counting videos that no longer exist.
        assertEquals(
            "no category may be left empty",
            emptyList<String>(),
            db.categoryDao().observeWithCounts().first()
                .filter { it.videoCount == 0 }.map { it.category.id },
        )
    }

    @Test
    fun removeWatched_keepsExactlyTheVideosTheFakeServerRefused() = runBlocking {
        // Every second call is refused, so the run has to survive failures mid-way and report them.
        val repo = demoRepository(failCall = { it % 2 == 0 })
        val watched = db.videoDao().getWatched()

        repo.startRemoveWatched()
        val done = awaitDone(repo.bulkRemove)

        assertEquals(watched.size, done.total)
        assertTrue("expected a mix, got ${done.failed}/${done.total}", done.failed in 1..<done.total)
        // The invariant that matters: what is still watched is exactly what could not be deleted.
        assertEquals(done.failed, db.videoDao().getWatched().size)
    }

    // --- removing videos missing from the server ---

    /**
     * The demo's other removal, and the opposite of the one above: this one is local, so the fake
     * server is never asked and cannot refuse. Every video goes, whatever the dice would have said.
     */
    @Test
    fun removeMissing_dropsEveryMissingRowWithoutConsultingTheFakeServer() = runBlocking {
        // Every call refused — which is exactly what must not matter here.
        val repo = demoRepository(failCall = { true })
        val missing = db.videoDao().getMissing()
        assertTrue("the seed must produce missing videos", missing.isNotEmpty())
        val before = db.videoDao().getAll().size

        repo.startRemoveMissing()
        val done = awaitDone(repo.bulkRemoveMissing)

        assertEquals(missing.size, done.total)
        assertEquals("a local delete has nothing that can refuse it", 0, done.failed)
        assertEquals(
            emptyList<String>(),
            missing.map { it.youtubeId }.filter { db.videoDao().get(it) != null },
        )
        assertEquals("and nothing else went with them", before - missing.size, db.videoDao().getAll().size)
        assertEquals(
            "no category may be left empty",
            emptyList<String>(),
            db.categoryDao().observeWithCounts().first()
                .filter { it.videoCount == 0 }.map { it.category.id },
        )
    }

    /**
     * The two removals must not reach into each other's set. Removing the missing videos leaves
     * every watched one alone, and a watched removal afterwards still has its full set to work on
     * — which is what makes both actions demonstrable in one sitting.
     */
    @Test
    fun removeMissing_leavesTheWatchedVideosForTheOtherRemoval() = runBlocking {
        val repo = demoRepository()
        val watched = db.videoDao().getWatched().map { it.youtubeId }

        repo.startRemoveMissing()
        awaitDone(repo.bulkRemoveMissing)

        assertEquals(watched.sorted(), db.videoDao().getWatched().map { it.youtubeId }.sorted())
        assertEquals(emptyList<Any>(), db.videoDao().getMissing())
    }

    /**
     * A demo sync must leave the filter standing. The fake server has not started listing these
     * videos again, and a Sync now that silently cleared their miss counts would make the whole
     * filter vanish on the first press — the demo-mode mirror of
     * [sync_fillsMissingMetadataAndLeavesRemovedVideosRemoved].
     */
    @Test
    fun sync_leavesTheMissingVideosMissing() = runBlocking {
        val repo = demoRepository()
        val missing = db.videoDao().getMissing().map { it.youtubeId }.sorted()

        repo.sync()
        repo.sync()

        assertEquals(missing, db.videoDao().getMissing().map { it.youtubeId }.sorted())
        // Nor does it advance them: two more syncs at the grace limit would delete the rows.
        assertTrue(
            "the miss count must not creep toward the grace limit",
            db.videoDao().getMissing().all { it.missedSyncs == 1 },
        )
    }

    /** And once they are removed, a sync does not bring them back — like any other removal. */
    @Test
    fun sync_doesNotResurrectTheMissingVideosOnceRemoved() = runBlocking {
        val repo = demoRepository()
        val missing = db.videoDao().getMissing().map { it.youtubeId }
        repo.startRemoveMissing()
        awaitDone(repo.bulkRemoveMissing)

        repo.sync()

        assertEquals(emptyList<String>(), missing.filter { db.videoDao().get(it) != null })
    }

    // --- sync and playlists ---

    @Test
    fun sync_fillsMissingMetadataAndLeavesRemovedVideosRemoved() = runBlocking {
        val repo = demoRepository()
        val watched = db.videoDao().getWatched().map { it.youtubeId }
        repo.startRemoveWatched()
        awaitDone(repo.bulkRemove)
        val remaining = db.videoDao().getAll().size

        val result = repo.sync()

        assertTrue("$result", result is SyncResult.Success)
        result as SyncResult.Success
        assertEquals("the stored rows are the library", remaining, result.matched)
        assertTrue("categories are re-derived", result.categories > 0)
        // The auto-fill pass runs because the seed leaves fewer than its threshold uncategorized.
        assertTrue("expected the sync to fill metadata gaps", result.autoFilled > 0)
        assertEquals(0, result.autoFillFailed)
        assertEquals("nothing left uncategorized", emptyList<Any>(), uncategorized())
        assertEquals(
            "a sync must not resurrect what the removal deleted",
            emptyList<String>(),
            watched.filter { db.videoDao().get(it) != null },
        )
        assertTrue("the sync stamp moves", settings.snapshot().lastSyncAt > 0L)
    }

    @Test
    fun createPlaylist_reportsSuccessWithNoServerToCreateOneOn() = runBlocking {
        val repo = demoRepository()

        val result = repo.createPlaylistFromVideos(db.videoDao().getWatched().map { it.youtubeId }, "Watched")

        assertTrue("$result", result is PlaylistResult.Success)
        result as PlaylistResult.Success
        assertEquals("Watched", result.name)
        assertEquals(db.videoDao().getWatched().size, result.count)
    }

    @Test
    fun createPlaylist_reportsTheFakeServersRefusal() = runBlocking {
        val repo = demoRepository(failCall = { true })

        val result = repo.createPlaylistFromVideos(db.videoDao().getWatched().map { it.youtubeId }, "Watched")

        assertTrue("$result", result is PlaylistResult.Error)
        assertEquals(
            "Failed to create playlist: Server returned 500 Internal Server Error",
            (result as PlaylistResult.Error).message,
        )
    }
}
