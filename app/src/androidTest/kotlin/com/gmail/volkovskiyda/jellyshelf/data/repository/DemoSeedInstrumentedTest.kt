package com.gmail.volkovskiyda.jellyshelf.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.data.DefaultTimeProvider
import com.gmail.volkovskiyda.jellyshelf.data.local.JellyshelfDatabase
import com.gmail.volkovskiyda.jellyshelf.data.remote.IndexSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.JellyfinClient
import com.gmail.volkovskiyda.jellyshelf.data.remote.TestDemoBackend
import com.gmail.volkovskiyda.jellyshelf.data.remote.YtDlpMetadataSource
import com.gmail.volkovskiyda.jellyshelf.di.provideJson
import com.gmail.volkovskiyda.jellyshelf.domain.DispatcherProvider
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_AUTO_CHANNEL
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_AUTO_DURATION
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_AUTO_MONTH
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_AUTO_YEAR
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_AUTO_YT_CATEGORY
import com.gmail.volkovskiyda.jellyshelf.domain.model.DEMO_ITEM_ID
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_INDEX
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_JELLYFIN
import com.gmail.volkovskiyda.jellyshelf.ui.FakeSettingsRepository
import com.gmail.volkovskiyda.jellyshelf.util.parseTimecodes
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers [DefaultLibraryRepository.seedDemoLibrary] against a real in-memory Room database and the
 * real bundled asset — the point of demo mode is that it produces a library indistinguishable from
 * a synced one, and only a real database can show that.
 *
 * The Jellyfin and index sources are wired to a [MockEngine] that fails every request: seeding
 * must not touch the network, and a call would surface here as a test failure rather than as a
 * silent success on a machine that happens to have connectivity.
 *
 * `runBlocking` rather than `runTest` for the same reason as [SyncInstrumentedTest]: this parks on
 * Room's own executor, which a virtual clock would treat as idle.
 */
@RunWith(AndroidJUnit4::class)
class DemoSeedInstrumentedTest {

    private lateinit var db: JellyshelfDatabase

    private val dispatchers = object : DispatcherProvider {
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val ioSequential = Dispatchers.Unconfined
        override val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        override fun ioScope(tag: String, failureMessage: String) =
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    private val settings = FakeSettingsRepository()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, JellyshelfDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private fun repository(): DefaultLibraryRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val json = provideJson()
        val engine = MockEngine { error("the demo seeder must not make network requests") }
        val httpClient = HttpClient(engine) { expectSuccess = true }
        val indexSource = IndexSource(context, httpClient, dispatchers, json)
        return DefaultLibraryRepository(
            db = db,
            settings = settings,
            dispatchers = dispatchers,
            time = DefaultTimeProvider(),
            // Seeding never asks the demo backend for anything — the dataset comes straight off
            // the asset — but it is what every action *after* the seed goes through.
            sources = LibrarySources(
                JellyfinDataSource(JellyfinClient(httpClient)),
                indexSource,
                YtDlpMetadataSource(context, dispatchers, json),
                TestDemoBackend(indexSource),
            ),
        )
    }

    private suspend fun categoryTypes() =
        db.categoryDao().observeWithCounts().first().map { it.category.type }.toSet()

    @Test
    fun seed_populatesTheLibraryFromTheBundledAsset() = runBlocking {
        repository().seedDemoLibrary()

        val rows = db.videoDao().getAll()
        assertTrue("expected a populated library, got ${rows.size}", rows.size >= 50)
        assertTrue("every demo row carries the sentinel item id", rows.all { it.jellyfinItemId == DEMO_ITEM_ID })
        assertTrue("file names drive the browse order", rows.all { it.fileName.endsWith(".mp4") })
        assertTrue(
            "entries carrying index metadata are index-sourced",
            rows.count { it.metadataSource == METADATA_SOURCE_INDEX } >= 50,
        )
        // The deliberately bare entries: Jellyfin-sourced, which is what the Uncategorized
        // filter queries for.
        assertTrue(
            "the Uncategorized filter needs members",
            db.videoDao().getBySource(METADATA_SOURCE_JELLYFIN).isNotEmpty(),
        )
    }

    @Test
    fun seed_marksTheInstallAsADemoWithoutTouchingTheConnection() = runBlocking {
        repository().seedDemoLibrary()

        val stored = settings.snapshot()
        assertTrue("demo mode must be recorded", stored.demoMode)
        assertTrue("the app must start on the library after a seed", stored.lastSyncAt > 0L)
        assertEquals("a demo has no library scope", "", stored.lastSyncLibraryId)
        assertFalse("a demo must never look connected", stored.isConnected)
        assertEquals("", stored.serverUrl)
    }

    /** The seed has to produce the same Categories screen a real sync does — every dimension. */
    @Test
    fun seed_derivesAutoCategoriesAlongEveryDimension() = runBlocking {
        repository().seedDemoLibrary()

        assertEquals(
            setOf(
                CATEGORY_TYPE_AUTO_CHANNEL,
                CATEGORY_TYPE_AUTO_YEAR,
                CATEGORY_TYPE_AUTO_MONTH,
                CATEGORY_TYPE_AUTO_DURATION,
                CATEGORY_TYPE_AUTO_YT_CATEGORY,
            ),
            categoryTypes(),
        )
    }

    /** Watched, Continue watching and Unwatched are three tabs; all three must have content. */
    @Test
    fun seed_producesAMixOfWatchStates() = runBlocking {
        repository().seedDemoLibrary()

        assertTrue("watched videos", db.videoDao().getWatched().isNotEmpty())
        assertTrue("continue watching", db.videoDao().getContinueWatching().isNotEmpty())
        assertTrue("unwatched videos", db.videoDao().getUnwatched().isNotEmpty())
        assertTrue(
            "a part-watched video needs a position inside its own duration",
            db.videoDao().getContinueWatching().all { it.playbackPositionTicks > 0L },
        )
    }

    @Test
    fun seed_carriesChaptersFromBothSources() = runBlocking {
        repository().seedDemoLibrary()

        val rows = db.videoDao().getAll()
        assertTrue(
            "some videos must expose structured chapters",
            rows.any { it.chapters.isNotEmpty() },
        )
        assertTrue(
            "some descriptions must parse into chapters",
            rows.any { parseTimecodes(it.description, it.durationSeconds).isNotEmpty() },
        )
    }

    /** Entering demo over an existing demo re-seeds; it must not double the library. */
    @Test
    fun seed_isIdempotent() = runBlocking {
        val repo = repository()
        repo.seedDemoLibrary()
        val firstCount = db.videoDao().getAll().size
        val firstCategories = db.categoryDao().observeWithCounts().first().size

        repo.seedDemoLibrary()

        assertEquals(firstCount, db.videoDao().getAll().size)
        assertEquals(firstCategories, db.categoryDao().observeWithCounts().first().size)
    }

    /** Sign out is the documented way out of demo mode, so this wipe has to be a complete one. */
    @Test
    fun clearLocalData_leavesDemoMode() = runBlocking {
        val repo = repository()
        repo.seedDemoLibrary()

        repo.clearLocalData()

        assertTrue("videos", db.videoDao().getAll().isEmpty())
        assertTrue("categories", db.categoryDao().observeWithCounts().first().isEmpty())
        val stored = settings.snapshot()
        assertFalse("the demo flag must not outlive the demo data", stored.demoMode)
        assertEquals(0L, stored.lastSyncAt)
    }
}
