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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * When the screen asks the password manager to save what was typed.
 *
 * The offer is one emission of [SettingsViewModel.credentialAccepted], which
 * [SettingsScreen] turns into an autofill commit. What matters is *which* sign-ins produce one:
 * committing on the button tap instead of on the server's answer would offer to save a password
 * the server just rejected, and committing on the demo path would file its magic credentials
 * alongside real ones.
 *
 * **Instrumented, but not a UI test** — [SettingsViewModel] needs an `Application`, and this
 * project has no Robolectric by deliberate decision. The commit itself is a platform call with no
 * autofill service behind it on a test device; [AuthAutofillTest] covers the form's side of the
 * contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CredentialSaveOfferTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val library = FakeLibraryRepository()
    private val store = ViewModelStore()

    /** Answers a sign-in with [session], or throws [rejection] when there is one. */
    private class StubJellyfin(private val rejection: Throwable? = null) : JellyfinRepository {
        override suspend fun signIn(serverUrl: String, username: String, password: String): Session {
            rejection?.let { throw it }
            return Session(accessToken = "token", user = User(id = "user-1", name = "alice"))
        }

        override suspend fun getUsers(serverUrl: String, credential: String): List<User> =
            error("not used here")

        override suspend fun getChildFolders(
            serverUrl: String,
            credential: String,
            userId: String,
            parentId: String?,
        ): List<MediaFolder> = error("not used here")
    }

    @Before
    fun setUp() {
        // Process-wide work left enqueued by another class surfaces here as a `busy` that makes
        // every action return early. Awaited, so the cancel has landed before anything subscribes.
        WorkManager.getInstance(app).cancelAllWork().result.get()
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(jellyfin: JellyfinRepository): SettingsViewModel =
        SettingsViewModel(
            app,
            FakeSettingsRepository(emptySettings),
            library,
            jellyfin,
            SettingsCache(),
            SyncScheduler(WorkManager.getInstance(app)),
            inertUpdateChecker(),
            testLocalNetworkPrompt(),
        ).also {
            store.put("settings", it)
            backgroundScope.launch { it.state.collect { } }
        }

    /** Collects the offers the way the screen does, from before the sign-in is attempted. */
    private fun TestScope.offers(viewModel: SettingsViewModel): List<Unit> {
        val seen = mutableListOf<Unit>()
        backgroundScope.launch { viewModel.credentialAccepted.collect { seen += it } }
        advanceUntilIdle()
        return seen
    }

    @Test
    fun anAcceptedSignIn_offersTheCredentialForSaving() = runTest {
        val viewModel = viewModel(StubJellyfin())
        val offers = offers(viewModel)
        viewModel.onServerUrlChange("https://example.org")
        viewModel.onUsernameChange("alice")
        viewModel.onPasswordChange("hunter2")

        viewModel.signIn()
        advanceUntilIdle()

        assertEquals(1, offers.size)
    }

    @Test
    fun aRejectedSignIn_offersNothing() = runTest {
        val viewModel = viewModel(StubJellyfin(rejection = IllegalStateException("nope")))
        val offers = offers(viewModel)
        viewModel.onServerUrlChange("https://example.org")
        viewModel.onUsernameChange("alice")
        viewModel.onPasswordChange("wrong")

        viewModel.signIn()
        advanceUntilIdle()

        assertEquals("a rejected password must not be offered for saving", 0, offers.size)
    }

    /** The magic credentials are an easter egg, not an account worth remembering. */
    @Test
    fun theDemoSignIn_offersNothing() = runTest {
        val viewModel = viewModel(StubJellyfin(rejection = IllegalStateException("unreachable")))
        val offers = offers(viewModel)
        viewModel.onServerUrlChange("jellyfin")
        viewModel.onUsernameChange("demo")
        viewModel.onPasswordChange("any password at all")

        viewModel.signIn()
        advanceUntilIdle()

        assertEquals(0, offers.size)
    }
}
