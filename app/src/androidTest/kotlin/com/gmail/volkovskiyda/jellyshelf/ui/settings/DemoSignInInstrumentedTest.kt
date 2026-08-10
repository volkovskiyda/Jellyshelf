package com.gmail.volkovskiyda.jellyshelf.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.data.worker.SyncScheduler
import com.gmail.volkovskiyda.jellyshelf.domain.model.DEMO_USER
import com.gmail.volkovskiyda.jellyshelf.domain.model.DEMO_USER_ID
import com.gmail.volkovskiyda.jellyshelf.domain.model.MediaFolder
import com.gmail.volkovskiyda.jellyshelf.domain.model.Session
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
 * The magic-credentials entry into demo mode, driven through the real [SettingsViewModel].
 *
 * Two things are being pinned, and both are the kind that fail silently. First, that the demo path
 * never reaches the network: [RefusingJellyfin] throws on every call, so a demo sign-in that
 * accidentally fell through to the real one fails here instead of firing an HTTPS request at a
 * host called `jellyfin` on the user's LAN. Second, that entering a *real* connection while a demo
 * is loaded wipes it first — demo rows belong to no server, and a sync would spend its grace
 * period quietly deleting them.
 *
 * **Instrumented, but not a UI test.** [SettingsViewModel] needs an `Application`, which is the
 * only reason this can't be host-side (this project has no Robolectric, by deliberate decision).
 * Everything else follows the host-side ViewModel tests: `Dispatchers.setMain` with a
 * [StandardTestDispatcher] so `viewModelScope` runs on a clock this test controls, rather than on
 * the device's main looper — which the rest of the suite is also using, and which made an earlier
 * version of this class pass alone and time out inside a full run.
 *
 * The pure credential rules are host-side in [DemoCredentialsTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class DemoSignInInstrumentedTest {

    /** Every Jellyfin call throws: nothing on a demo path may reach it. */
    private class RefusingJellyfin : JellyfinRepository {
        val calls = mutableListOf<String>()

        override suspend fun signIn(serverUrl: String, username: String, password: String): Session {
            calls += "signIn($serverUrl)"
            error("the demo path must not sign in against a server")
        }

        override suspend fun getUsers(serverUrl: String, credential: String): List<User> {
            calls += "getUsers($serverUrl)"
            error("the demo path must not list users from a server")
        }

        override suspend fun getChildFolders(
            serverUrl: String,
            credential: String,
            userId: String,
            parentId: String?,
        ): List<MediaFolder> {
            calls += "getChildFolders($serverUrl)"
            error("the demo path must not browse a server")
        }
    }

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val jellyfin = RefusingJellyfin()
    private val library = FakeLibraryRepository()

    /**
     * Owns the ViewModels so [tearDown] can clear them. Without it each test would leave a live
     * `viewModelScope` — and its WorkManager subscription — running for the rest of the process.
     */
    private val store = ViewModelStore()

    @Before
    fun setUp() {
        // WorkManager is process-wide and feeds SettingsUiState.syncStatus (see
        // SettingsViewModel.state), so manual-sync work left enqueued by
        // SyncSchedulerInstrumentedTest surfaces here as a "Syncing…" — and, more to the point,
        // as a `busy` that makes every action below return early. Awaited, so the cancel has
        // landed before any ViewModel subscribes.
        WorkManager.getInstance(app).cancelAllWork().result.get()
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    /**
     * A ViewModel with the screen's subscription standing in.
     *
     * `state` shares `WhileUiSubscribed`, so with no collector the combine behind it never runs and
     * every assertion below would read the untouched default — passing or failing for reasons that
     * have nothing to do with demo mode.
     */
    private fun TestScope.viewModel(demoMode: Boolean = false): SettingsViewModel = SettingsViewModel(
        app,
        FakeSettingsRepository(emptySettings.copy(demoMode = demoMode)),
        library,
        jellyfin,
        SettingsCache(),
        SyncScheduler(WorkManager.getInstance(app)),
        inertUpdateChecker(),
    ).also {
        store.put("settings", it)
        backgroundScope.launch { it.state.collect { } }
    }

    private fun string(resId: Int, vararg args: Any) = app.getString(resId, *args)

    @Test
    fun connect_withTheDemoServer_offersTheDemoUserWithoutTouchingTheNetwork() = runTest {
        val viewModel = viewModel()
        viewModel.onServerUrlChange("Jellyfin")

        viewModel.connect()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(listOf(DEMO_USER), state.users.map { it.name })
        assertEquals(DEMO_USER_ID, state.users.single().id)
        assertTrue("no server may be contacted: ${jellyfin.calls}", jellyfin.calls.isEmpty())
        assertEquals("nothing may be seeded by merely connecting", 0, library.seeds)
    }

    /** The whole point of the easter egg: a real-looking failure with nothing to fail against. */
    @Test
    fun signIn_withTheDemoFailurePassword_showsTheRealAuthenticationError() = runTest {
        val viewModel = viewModel()
        viewModel.onServerUrlChange("jellyfin")
        viewModel.onUsernameChange("demo")
        viewModel.onPasswordChange("incorrect")

        viewModel.signIn()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.authStatus?.isError == true)
        assertEquals(
            string(R.string.sign_in_failed, string(R.string.invalid_username_or_password)),
            state.authStatus?.text,
        )
        assertFalse("a failed sign-in must not enter demo mode", state.demoMode)
        assertEquals("and must not seed anything", 0, library.seeds)
        assertEquals("", state.password)
        assertTrue("no server may be contacted: ${jellyfin.calls}", jellyfin.calls.isEmpty())
    }

    @Test
    fun signIn_withTheDemoCredentials_seedsTheDemoAndAnnouncesIt() = runTest {
        val viewModel = viewModel()
        viewModel.onServerUrlChange("jellyfin")
        viewModel.onUsernameChange("demo")
        viewModel.onPasswordChange("any password at all")

        viewModel.signIn()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(1, library.seeds)
        assertEquals(string(R.string.demo_library_loaded), state.authStatus?.text)
        assertFalse(state.authStatus?.isError == true)
        assertEquals("the typed password must not linger", "", state.password)
        assertTrue("no server may be contacted: ${jellyfin.calls}", jellyfin.calls.isEmpty())
    }

    @Test
    fun tryDemo_seedsTheDemoAndAnnouncesIt() = runTest {
        val viewModel = viewModel()

        viewModel.tryDemo()
        advanceUntilIdle()

        assertEquals(1, library.seeds)
        assertEquals(string(R.string.demo_library_loaded), viewModel.state.value.authStatus?.text)
    }

    /** A real sign-in over a demo wipes it *first*, before the server can be asked for anything. */
    @Test
    fun signIn_toARealServerWhileInDemo_clearsTheDemoLibraryFirst() = runTest {
        val viewModel = viewModel(demoMode = true)
        viewModel.onServerUrlChange("https://example.org")
        viewModel.onUsernameChange("alice")
        viewModel.onPasswordChange("hunter2")

        viewModel.signIn()
        advanceUntilIdle()

        // The fake server throws, so this settles on a failure — which is fine: the wipe has to
        // happen on the attempt, and it is the ordering that matters.
        assertTrue(viewModel.state.value.authStatus?.isError == true)
        assertEquals(1, library.clears)
        assertEquals(listOf("clear"), library.writeOrder)
        assertEquals(listOf("signIn(https://example.org)"), jellyfin.calls)
    }

    @Test
    fun connect_toARealServerWhileInDemo_clearsTheDemoLibraryFirst() = runTest {
        val viewModel = viewModel(demoMode = true)
        viewModel.onServerUrlChange("https://example.org")
        viewModel.onApiKeyChange("KEY")

        viewModel.connect()
        advanceUntilIdle()

        // The advanced connect reports on its own line, not the sign-in form's.
        assertTrue(viewModel.state.value.connectStatus?.isError == true)
        assertEquals(1, library.clears)
        assertEquals(listOf("clear"), library.writeOrder)
    }

    /**
     * A *successful* demo→real sign-in must leave the index field editable: the demo wipe zeroes
     * the persisted last-sync marker, and the state has to re-read it rather than keep the demo
     * seed's timestamp — a stale value keeps `indexProtected` true with nothing ever synced
     * against the server, which hides the Fill affordance behind a lock.
     */
    @Test
    fun signIn_toARealServerWhileInDemo_unlocksTheIndexField() = runTest {
        val settings = FakeSettingsRepository(
            emptySettings.copy(demoMode = true, lastSyncAt = 1_722_800_000_000L),
        )
        // The fake mirrors the real clearLocalData contract (LibraryRepository.clearLocalData):
        // the wipe is what zeroes the marker this test asserts got re-read.
        val library = FakeLibraryRepository(onClear = {
            settings.setLastSync(0L, "")
            settings.setDemoMode(false)
        })
        val jellyfin = object : JellyfinRepository {
            override suspend fun signIn(serverUrl: String, username: String, password: String) =
                Session(accessToken = "token", user = User(id = "user-1", name = "alice"))

            override suspend fun getUsers(serverUrl: String, credential: String): List<User> =
                error("not used here")

            override suspend fun getChildFolders(
                serverUrl: String,
                credential: String,
                userId: String,
                parentId: String?,
            ): List<MediaFolder> = error("not used here")
        }
        val viewModel = SettingsViewModel(
            app,
            settings,
            library,
            jellyfin,
            SettingsCache(),
            SyncScheduler(WorkManager.getInstance(app)),
            inertUpdateChecker(),
        ).also {
            store.put("settings-unlock", it)
            backgroundScope.launch { it.state.collect { } }
        }
        advanceUntilIdle() // the init collection lands the persisted demo state first

        viewModel.onServerUrlChange("https://example.org")
        viewModel.onUsernameChange("alice")
        viewModel.onPasswordChange("hunter2")
        viewModel.signIn()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.signedIn)
        assertEquals(1, library.clears)
        assertEquals("the wiped demo's sync timestamp must not linger", 0L, state.lastSyncAt)
        assertFalse("the index field must offer Fill after a demo→real sign-in", state.indexProtected)
    }

    /** Without a demo loaded there is nothing to clear, and a real connect must not wipe anything. */
    @Test
    fun signIn_toARealServerWithoutADemo_clearsNothing() = runTest {
        val viewModel = viewModel()
        viewModel.onServerUrlChange("https://example.org")
        viewModel.onUsernameChange("alice")
        viewModel.onPasswordChange("hunter2")

        viewModel.signIn()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.authStatus?.isError == true)
        assertEquals(0, library.clears)
    }
}
