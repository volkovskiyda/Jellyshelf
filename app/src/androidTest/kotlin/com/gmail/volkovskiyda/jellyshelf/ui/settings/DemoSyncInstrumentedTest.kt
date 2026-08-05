package com.gmail.volkovskiyda.jellyshelf.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.data.worker.SyncScheduler
import com.gmail.volkovskiyda.jellyshelf.domain.model.MediaFolder
import com.gmail.volkovskiyda.jellyshelf.domain.model.Session
import com.gmail.volkovskiyda.jellyshelf.domain.model.SyncResult
import com.gmail.volkovskiyda.jellyshelf.domain.model.User
import com.gmail.volkovskiyda.jellyshelf.domain.repository.JellyfinRepository
import com.gmail.volkovskiyda.jellyshelf.ui.FakeLibraryRepository
import com.gmail.volkovskiyda.jellyshelf.ui.FakeSettingsRepository
import com.gmail.volkovskiyda.jellyshelf.ui.emptySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Sync now" on a demo install, through the real [SettingsViewModel].
 *
 * A demo sync deliberately does *not* go through [SyncScheduler]. The worker is constrained to
 * `NetworkType.CONNECTED`, so on the offline device a demo is most likely to be shown on, an
 * enqueued sync would never run and the screen would sit on "Syncing…" indefinitely; and the same
 * call also (re)creates the periodic worker, which a demo install has no server to run against.
 * This class pins both halves: the scheduler is left alone, and the repository is called directly
 * and its result reported on the status line in the same words a real sync uses.
 *
 * Instrumented for the same reason as [DemoSignInInstrumentedTest] — [SettingsViewModel] needs an
 * `Application` and this project has no Robolectric — and it follows that class's harness exactly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class DemoSyncInstrumentedTest {

    /** A demo touches no server; anything reaching this is a bug, not a test failure to relax. */
    private class RefusingJellyfin : JellyfinRepository {
        override suspend fun signIn(serverUrl: String, username: String, password: String): Session =
            error("a demo sync must not sign in")

        override suspend fun getUsers(serverUrl: String, credential: String): List<User> =
            error("a demo sync must not list users")

        override suspend fun getChildFolders(
            serverUrl: String,
            credential: String,
            userId: String,
            parentId: String?,
        ): List<MediaFolder> = error("a demo sync must not browse a server")
    }

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val library = FakeLibraryRepository()
    private val workManager = WorkManager.getInstance(app)
    private val store = ViewModelStore()

    @Before
    fun setUp() {
        clearWork()
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
        // The real-path case genuinely enqueues, and both the manual and the periodic work would
        // otherwise outlive this class in a process-wide WorkManager.
        clearWork()
    }

    /**
     * WorkManager is process-wide and its state feeds the same status line these tests read, so
     * work left behind by another class — or by the real-path case here — would surface as a
     * "Syncing…" that wins over everything asserted below.
     *
     * Pruned as well as cancelled: cancelling only moves work to `CANCELLED`, and it stays
     * queryable in that state, so "nothing was enqueued" would be true of a demo sync and still
     * read as false.
     */
    private fun clearWork() {
        workManager.cancelAllWork().result.get()
        workManager.pruneWork().result.get()
    }

    /**
     * A ViewModel with the screen's `WhileUiSubscribed` subscription standing in.
     *
     * `syncScopeNudged` is pre-set: a first "Sync now" at the root scope otherwise spends its tap
     * pointing at the sync scope instead of syncing (see `shouldNudgeSyncScope`), which is a rule
     * of its own and not the one under test here.
     */
    private fun TestScope.viewModel(demoMode: Boolean): SettingsViewModel = SettingsViewModel(
        app,
        FakeSettingsRepository(emptySettings.copy(demoMode = demoMode), syncScopeNudged = true),
        library,
        RefusingJellyfin(),
        SettingsCache(),
        SyncScheduler(workManager),
    ).also {
        store.put("settings", it)
        backgroundScope.launch { it.state.collect { } }
    }

    /** States of the work the scheduler would have enqueued, by the unique name "Sync now" uses. */
    private fun manualSyncWork() =
        workManager.getWorkInfosForUniqueWork("jellyshelf-manual-sync").get().map { it.state }

    @Test
    fun syncNow_inDemoMode_syncsInPlaceInsteadOfEnqueueingAWorker() = runTest {
        library.syncResult = SyncResult.Success(
            itemCount = 60,
            matched = 60,
            indexed = 56,
            categories = 24,
            autoFilled = 3,
            autoFillFailed = 1,
        )
        val viewModel = viewModel(demoMode = true)

        viewModel.syncNow()
        advanceUntilIdle()

        assertEquals("the repository is called directly", 1, library.syncs)
        assertEquals("no worker may be enqueued for a demo", emptyList<WorkInfo.State>(), manualSyncWork())
        val state = viewModel.state.value
        assertFalse("the sync is over", state.syncRunning)
        // Reported exactly as a real sync is — same summary, same auto-fill tail.
        assertEquals(
            app.getString(
                R.string.sync_auto_filled_partial,
                app.getString(R.string.sync_summary, 56, 60, 24),
                3,
                1,
            ),
            state.status,
        )
    }

    @Test
    fun syncNow_inDemoMode_reportsAFailureAsAnError() = runTest {
        library.syncResult = SyncResult.Error("Something broke")
        val viewModel = viewModel(demoMode = true)

        viewModel.syncNow()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("Something broke", state.status)
        assertTrue(state.statusIsError)
    }

    /** The demo branch must not swallow the real path: without the flag, this still enqueues. */
    @Test
    fun syncNow_withoutDemoMode_stillGoesThroughTheScheduler() = runTest {
        val viewModel = viewModel(demoMode = false)

        viewModel.syncNow()
        advanceUntilIdle()

        assertEquals("the repository must not be called directly", 0, library.syncs)
        assertFalse("the worker owns a real sync", manualSyncWork().isEmpty())
    }
}
