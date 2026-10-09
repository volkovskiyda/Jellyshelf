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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Unsaved edits to the Settings form survive the ViewModel being cleared.
 *
 * A tab switch rebuilds the back stack, which drops the Settings entry and with it the ViewModel
 * and every field the user had typed into but not yet saved — a metadata API URL entered and then
 * checked against the Library tab came back blank. The edits live in [SettingsCache] for the
 * process's lifetime, keyed to nothing, until something persists the form or signs out.
 *
 * **Instrumented, but not a UI test** — [SettingsViewModel] needs an `Application`, and this
 * project has no Robolectric by deliberate decision. "The tab switch" here is clearing the
 * [ViewModelStore] and building a second ViewModel over the same cache, which is exactly what the
 * navigation does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsFormDraftInstrumentedTest {

    /** Accepts every sign-in as `alice`; nothing else is reached. */
    private class StubJellyfin : JellyfinRepository {
        override suspend fun signIn(serverUrl: String, username: String, password: String): Session =
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

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val library = FakeLibraryRepository()
    private val store = ViewModelStore()

    /** Shared across the ViewModels of one test, like the Koin single it stands in for. */
    private val cache = SettingsCache()

    /** A signed-in install, so the Advanced fields are reachable without a sign-in first. */
    private val signedIn = FakeSettingsRepository(
        emptySettings.copy(
            serverUrl = "https://example.org",
            accessToken = "token",
            userId = "user-1",
            userName = "alice",
        ),
    )

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

    private fun TestScope.viewModel(settings: FakeSettingsRepository = signedIn): SettingsViewModel =
        SettingsViewModel(
            app,
            settings,
            library,
            StubJellyfin(),
            cache,
            SyncScheduler(WorkManager.getInstance(app)),
            inertUpdateChecker(),
            testLocalNetworkPrompt(),
        ).also {
            store.put("settings", it)
            backgroundScope.launch { it.state.collect { } }
            advanceUntilIdle()
        }

    /** What a tab switch does to the Settings ViewModel. */
    private fun TestScope.switchTabs(): SettingsViewModel {
        store.clear()
        return viewModel()
    }

    @Test
    fun advancedEdits_surviveATabSwitch() = runTest {
        val before = viewModel()
        before.onIndexUrlChange("https://db.example.org/index.json")
        before.onMetadataApiUrlChange("https://db.example.org/api")
        before.onMetadataApiTokenChange("secret")

        val after = switchTabs()

        assertEquals("https://db.example.org/index.json", after.state.value.indexUrl)
        assertEquals("https://db.example.org/api", after.state.value.metadataApiUrl)
        assertEquals("secret", after.state.value.metadataApiToken)
    }

    @Test
    fun connectionEdits_surviveATabSwitch() = runTest {
        val before = viewModel(FakeSettingsRepository(emptySettings))
        before.onServerUrlChange("https://typed.example.org")
        before.onUsernameChange("bob")
        before.onApiKeyChange("key")

        store.clear()
        val after = viewModel(FakeSettingsRepository(emptySettings))

        assertEquals("https://typed.example.org", after.state.value.serverUrl)
        assertEquals("bob", after.state.value.username)
        assertEquals("key", after.state.value.apiKey)
    }

    /** The one field that must not outlive the screen it was typed into. */
    @Test
    fun thePassword_isNotCarriedAcross() = runTest {
        val before = viewModel(FakeSettingsRepository(emptySettings))
        before.onUsernameChange("bob")
        before.onPasswordChange("hunter2")

        store.clear()
        val after = viewModel(FakeSettingsRepository(emptySettings))

        assertEquals("", after.state.value.password)
    }

    @Test
    fun theAdvancedSection_staysOpenAcrossATabSwitch() = runTest {
        val before = viewModel()
        assertFalse("collapsed by default", before.state.value.advancedExpanded)
        before.onAdvancedExpandedChange(true)

        val after = switchTabs()

        assertTrue(after.state.value.advancedExpanded)
    }

    /** Once persisted, the edits are the saved values — a kept draft would shadow later writes. */
    @Test
    fun aSignIn_persistsTheEditsAndDropsTheDraft() = runTest {
        val settings = FakeSettingsRepository(emptySettings)
        val before = viewModel(settings)
        before.onServerUrlChange("https://example.org")
        before.onUsernameChange("alice")
        before.onMetadataApiUrlChange("https://db.example.org/api")
        before.onMetadataApiTokenChange("secret")
        before.onPasswordChange("hunter2")
        before.signIn()
        advanceUntilIdle()
        assertEquals("https://db.example.org/api", settings.snapshot().metadataApiUrl)

        // Something else moves the persisted value; a stale draft must not win over it.
        settings.setMetadataApi("https://elsewhere.example.org/api", "other")
        store.clear()
        val after = viewModel(settings)

        assertEquals("https://elsewhere.example.org/api", after.state.value.metadataApiUrl)
        assertEquals("other", after.state.value.metadataApiToken)
    }

    @Test
    fun aSignOut_dropsTheDraft() = runTest {
        val before = viewModel()
        before.onMetadataApiUrlChange("https://db.example.org/api")
        before.onAdvancedExpandedChange(true)
        before.signOut()
        advanceUntilIdle()

        val after = switchTabs()

        assertEquals("", after.state.value.metadataApiUrl)
        assertFalse(after.state.value.advancedExpanded)
    }
}
