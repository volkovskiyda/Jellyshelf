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
import com.gmail.volkovskiyda.jellyshelf.domain.model.ThemeMode
import com.gmail.volkovskiyda.jellyshelf.domain.model.ThemeState
import com.gmail.volkovskiyda.jellyshelf.domain.model.User
import com.gmail.volkovskiyda.jellyshelf.domain.repository.JellyfinRepository
import com.gmail.volkovskiyda.jellyshelf.ui.FakeLibraryRepository
import com.gmail.volkovskiyda.jellyshelf.ui.FakeSettingsRepository
import com.gmail.volkovskiyda.jellyshelf.ui.emptySettings
import com.gmail.volkovskiyda.jellyshelf.ui.inertUpdateChecker
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
 * What Sign out actually does, through the real [SettingsViewModel].
 *
 * It is now two wipes in one action — the credential *and* the library the credential produced —
 * which is what the separate "Reset local data" button used to be half of. Both halves have to
 * land, and the ways this fails are quiet ones: a connection cleared but a full library left
 * behind (the next sync would report every video as deleted), or a library cleared while the
 * periodic worker survives to refill it.
 *
 * **Instrumented, but not a UI test.** [SettingsViewModel] needs an `Application`, and
 * [SyncScheduler] needs a real WorkManager — the two reasons this can't be host-side, in a project
 * that has no Robolectric by deliberate decision. [SignOutButtonTest] covers the affordance and
 * its confirmation; nothing here goes through the UI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SignOutInstrumentedTest {

    /** No call may be made once the credentials are being dropped. */
    private class RefusingJellyfin : JellyfinRepository {
        override suspend fun signIn(serverUrl: String, username: String, password: String): Session =
            error("no server call expected")

        override suspend fun getUsers(serverUrl: String, credential: String): List<User> =
            error("no server call expected")

        override suspend fun getChildFolders(
            serverUrl: String,
            credential: String,
            userId: String,
            parentId: String?,
        ): List<MediaFolder> = error("no server call expected")
    }

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val workManager = WorkManager.getInstance(app)
    private val library = FakeLibraryRepository()
    private val store = ViewModelStore()

    /** A signed-in install with a scope, an index URL and an advanced key — everything to lose. */
    private val configured = emptySettings.copy(
        serverUrl = "https://example.org",
        apiKey = "server-wide-key",
        accessToken = "user-token",
        userId = "user-1",
        userName = "alice",
        libraryId = "folder-1",
        libraryName = "Downloads",
        indexUrl = "https://example.org/jellyshelf-index.json",
        tokenInQuery = true,
        lastSyncAt = 1_722_800_000_000L,
        lastSyncLibraryId = "folder-1",
    )

    @Before
    fun setUp() {
        // Process-wide, and this class asserts on what is left enqueued — so anything an earlier
        // class left behind would read as a survivor of the cancel below. Awaited, so it has
        // landed before any ViewModel subscribes.
        workManager.cancelAllWork().result.get()
        workManager.pruneWork().result.get()
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        store.clear()
        workManager.cancelAllWork().result.get()
        workManager.pruneWork().result.get()
        Dispatchers.resetMain()
    }

    /**
     * `state` shares `WhileUiSubscribed`, so without a collector the combine behind it never runs
     * and every assertion would read the untouched default.
     */
    private fun TestScope.viewModel(settings: FakeSettingsRepository): SettingsViewModel =
        SettingsViewModel(
            app,
            settings,
            library,
            RefusingJellyfin(),
            SettingsCache(),
            SyncScheduler(workManager),
            inertUpdateChecker(),
        ).also {
            store.put("settings", it)
            backgroundScope.launch { it.state.collect { } }
        }

    @Test
    fun signOut_clearsTheConnectionAndTheLibraryTogether() = runTest {
        val settings = FakeSettingsRepository(configured)
        val viewModel = viewModel(settings)
        advanceUntilIdle() // the init load lands the persisted connection first

        viewModel.signOut()
        advanceUntilIdle()

        assertEquals("the library is wiped, not just the credential", 1, library.clears)
        val stored = settings.snapshot()
        assertEquals("", stored.accessToken)
        assertEquals("", stored.serverUrl)
        assertEquals("the advanced key goes too, or the install is still connected", "", stored.apiKey)
        assertEquals("", stored.indexUrl)
        assertEquals("", stored.libraryId)
        assertEquals("", stored.userId)
        assertFalse(stored.tokenInQuery)
    }

    /** The screen has to read as a fresh install afterwards, not as one with stale fields. */
    @Test
    fun signOut_leavesTheScreenLookingLikeAFreshInstall() = runTest {
        val settings = FakeSettingsRepository(configured, themeState = ThemeState(ThemeMode.DARK))
        val viewModel = viewModel(settings)
        advanceUntilIdle()

        viewModel.signOut()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.signedIn)
        assertFalse("nothing left to sign out of", state.canSignOut)
        assertTrue("the demo is on offer again", state.canTryDemo)
        assertEquals("", state.serverUrl)
        assertEquals("", state.apiKey)
        assertEquals("", state.username)
        assertEquals("", state.selectedUserId)
        assertEquals(0L, state.lastSyncAt)
        assertFalse(state.busy)
        assertEquals(app.getString(R.string.signed_out), state.authStatus?.text)
        assertFalse(state.authStatus?.isError == true)
        // The theme is the device's, not the server's — it must survive.
        assertEquals(ThemeMode.DARK, state.themeState.mode)
    }

    /**
     * Sync is cancelled *before* the wipe, and both workers with it: a manual one running through
     * the wipe would refill the tables, and the periodic one would go on doing it against
     * credentials that no longer exist.
     */
    @Test
    fun signOut_stopsSyncingFirst() = runTest {
        val settings = FakeSettingsRepository(configured)
        val viewModel = viewModel(settings)
        advanceUntilIdle()
        SyncScheduler(workManager).syncNow()

        viewModel.signOut()
        advanceUntilIdle()

        val states = workManager.getWorkInfosForUniqueWork("jellyshelf-manual-sync").get()
        assertTrue(
            "a surviving worker would refill the tables: ${states.map { it.state }}",
            states.all { it.state == WorkInfo.State.CANCELLED },
        )
    }

    /** Leaving the demo is the same action, said in the demo's own words. */
    @Test
    fun signOut_fromTheDemo_saysSoAndClearsTheSeededLibrary() = runTest {
        val settings = FakeSettingsRepository(emptySettings.copy(demoMode = true))
        val viewModel = viewModel(settings)
        advanceUntilIdle()

        viewModel.signOut()
        advanceUntilIdle()

        assertEquals(1, library.clears)
        assertEquals(app.getString(R.string.demo_left), viewModel.state.value.authStatus?.text)
    }

    /** `busy` guards the action, so a double tap can't run two concurrent wipes. */
    @Test
    fun signOut_whileBusy_doesNothing() = runTest {
        val settings = FakeSettingsRepository(configured)
        val viewModel = viewModel(settings)
        advanceUntilIdle()

        viewModel.signOut()
        viewModel.signOut() // same frame, before the first has cleared anything
        advanceUntilIdle()

        assertEquals(1, library.clears)
    }
}
