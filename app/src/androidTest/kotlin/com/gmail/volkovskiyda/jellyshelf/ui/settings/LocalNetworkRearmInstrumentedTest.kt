package com.gmail.volkovskiyda.jellyshelf.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.gmail.volkovskiyda.jellyshelf.data.worker.SyncScheduler
import com.gmail.volkovskiyda.jellyshelf.domain.model.MediaFolder
import com.gmail.volkovskiyda.jellyshelf.domain.model.Session
import com.gmail.volkovskiyda.jellyshelf.domain.model.User
import com.gmail.volkovskiyda.jellyshelf.domain.repository.JellyfinRepository
import com.gmail.volkovskiyda.jellyshelf.ui.FakeLibraryRepository
import com.gmail.volkovskiyda.jellyshelf.ui.FakeSettingsRepository
import com.gmail.volkovskiyda.jellyshelf.ui.emptySettings
import com.gmail.volkovskiyda.jellyshelf.ui.inertUpdateChecker
import com.gmail.volkovskiyda.jellyshelf.ui.testLocalNetworkPrompt
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Which failed connections may cancel the local-network snooze, through the real
 * [SettingsViewModel].
 *
 * The snooze is the only thing standing between a user whose server is unreachable for an ordinary
 * reason — off the LAN, offline, wrong port — and a rationale dialog on every visit to this screen.
 * It is easy to lose: the ViewModel starts a silent connect from its own `init` whenever an
 * API-key install hasn't loaded its user list yet, so *every* visit produces a failure, and a
 * re-arm on that failure would put the dialog back seconds after the user tapped "Not now" and
 * again on the next visit. Only a connection the user asked for is evidence worth cancelling a
 * decline over — which is what these two tests pin, in both directions.
 *
 * **Instrumented, but not a UI test**, for the reason [DemoSignInInstrumentedTest] gives:
 * [SettingsViewModel] needs an `Application`, and this project has no Robolectric by deliberate
 * decision. The prompt's own gates are host-side in `LocalNetworkPromptTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LocalNetworkRearmInstrumentedTest {

    /**
     * Fails the way a blocked LAN connection fails: no reply at all, which is exactly the failure
     * `rearmLocalNetworkPrompt` treats as evidence (a 401 or a cleartext block would be excluded
     * for reasons of their own).
     */
    private class UnreachableJellyfin : JellyfinRepository {
        var userListCalls = 0

        override suspend fun signIn(serverUrl: String, username: String, password: String): Session =
            throw IOException("timeout")

        override suspend fun getUsers(serverUrl: String, credential: String): List<User> {
            userListCalls++
            throw IOException("timeout")
        }

        override suspend fun getChildFolders(
            serverUrl: String,
            credential: String,
            userId: String,
            parentId: String?,
        ): List<MediaFolder> = throw IOException("timeout")
    }

    /** A plausible instant, so a preserved answer can't be confused with the store's "never". */
    private val answeredAt = 1_800_000_000_000L

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val jellyfin = UnreachableJellyfin()
    private val library = FakeLibraryRepository()
    private val store = ViewModelStore()

    /** An API-key install that is not signed in: the one shape whose `init` connects by itself. */
    private val settings = FakeSettingsRepository(
        emptySettings.copy(serverUrl = "https://example.org", apiKey = "KEY"),
        localNetworkPromptAt = answeredAt,
    )

    @Before
    fun setUp() {
        // Process-wide leftovers would surface as a `busy` that makes connect() return early —
        // see DemoSignInInstrumentedTest.setUp.
        WorkManager.getInstance(app).cancelAllWork().result.get()
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(): SettingsViewModel = SettingsViewModel(
        app,
        settings,
        library,
        jellyfin,
        SettingsCache(),
        SyncScheduler(WorkManager.getInstance(app)),
        inertUpdateChecker(),
        // The same store the ViewModel was handed, so a re-arm is readable straight back off it.
        testLocalNetworkPrompt(settings),
    ).also {
        store.put("settings", it)
        backgroundScope.launch { it.state.collect { } }
    }

    @Test
    fun theAutomaticConnect_failing_leavesTheSnoozeAlone() = runTest {
        viewModel()

        advanceUntilIdle()

        assertTrue("init must have tried the server by itself", jellyfin.userListCalls > 0)
        assertEquals(
            "an unasked-for failure must not cancel the user's answer",
            answeredAt to false,
            settings.savedLocalNetworkPrompt,
        )
    }

    @Test
    fun aConnectTheUserAsksFor_failing_endsTheSnooze() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.connect()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.connectStatus?.isError == true)
        assertEquals(
            "a failure the user provoked is evidence the permission is missing",
            0L to false,
            settings.savedLocalNetworkPrompt,
        )
    }
}
