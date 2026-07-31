package com.gmail.volkovskiyda.jellyshelf.ui

import com.gmail.volkovskiyda.jellyshelf.navigation.AppNavKey
import com.gmail.volkovskiyda.jellyshelf.navigation.PlayerOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Navigation tests for the start stack [MainViewModel] hands to the nav host.
 *
 * The invariant under test is the one Back depends on: Library is the app's home and always the
 * stack root, so however a session ended, Back walks down to Library and one more Back exits.
 * Plain JVM tests — the ViewModel takes a repository, so no Robolectric or Compose is involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationStackTest {

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun startStackOf(repo: FakeSettingsRepository) =
        MainViewModel(repo).startStack.filterNotNull().first()

    // Encoded with the same serializer MainViewModel decodes with, so these fixtures can't
    // drift from the real format (polymorphic "type" discriminators and all).
    private val json = Json { ignoreUnknownKeys = true }
    private fun stackJson(vararg keys: AppNavKey) =
        json.encodeToString(ListSerializer(AppNavKey.serializer()), keys.toList())

    @Test
    fun `a fresh install starts on Settings, not an empty Library`() = runTest {
        assertEquals(listOf(AppNavKey.Settings), startStackOf(FakeSettingsRepository()))
    }

    @Test
    fun `a configured install with no saved stack starts on Library`() = runTest {
        val repo = FakeSettingsRepository(
            emptySettings.copy(serverUrl = "https://example.org", apiKey = "key"),
        )
        assertEquals(listOf(AppNavKey.Library), startStackOf(repo))
    }

    @Test
    fun `a restored stack that does not start at Library gets Library prepended`() = runTest {
        val repo = FakeSettingsRepository(
            emptySettings.copy(serverUrl = "https://example.org", apiKey = "key"),
            backStackJson = stackJson(AppNavKey.Settings),
        )
        assertEquals(listOf(AppNavKey.Library, AppNavKey.Settings), startStackOf(repo))
    }

    @Test
    fun `a restored stack already rooted at Library is left alone`() = runTest {
        val saved = listOf(AppNavKey.Library, AppNavKey.Detail("1ubm7Q6DL-I"))
        val repo = FakeSettingsRepository(
            emptySettings.copy(serverUrl = "https://example.org", apiKey = "key"),
            backStackJson = stackJson(*saved.toTypedArray()),
        )
        assertEquals(saved, startStackOf(repo))
    }

    /**
     * A Detail entry written before the player origin existed carries no `origin` field. It has
     * to decode — an in-place upgrade restores this stack on the very first launch, and a
     * required field here would send that launch down the unreadable-stack path below, silently
     * dropping the user back to Library.
     */
    @Test
    fun `a Detail entry saved before origins existed still restores`() = runTest {
        val repo = FakeSettingsRepository(
            emptySettings.copy(serverUrl = "https://example.org", apiKey = "key"),
            backStackJson = """
                [{"type":"com.gmail.volkovskiyda.jellyshelf.navigation.AppNavKey.Library"},
                 {"type":"com.gmail.volkovskiyda.jellyshelf.navigation.AppNavKey.Detail",
                  "youtubeId":"1ubm7Q6DL-I"}]
            """.trimIndent(),
        )
        assertEquals(
            listOf(AppNavKey.Library, AppNavKey.Detail("1ubm7Q6DL-I", PlayerOrigin.None)),
            startStackOf(repo),
        )
    }

    /**
     * The other half of that upgrade: while `origin` was nullable the encoder wrote an explicit
     * `"origin": null` for every Detail opened from the notification path. Now that the field is
     * non-null, only `coerceInputValues` keeps that from failing the decode — and a failed decode
     * here is the same silent drop back to Library.
     */
    @Test
    fun `a Detail entry saved with a null origin restores as None`() = runTest {
        val repo = FakeSettingsRepository(
            emptySettings.copy(serverUrl = "https://example.org", apiKey = "key"),
            backStackJson = """
                [{"type":"com.gmail.volkovskiyda.jellyshelf.navigation.AppNavKey.Library"},
                 {"type":"com.gmail.volkovskiyda.jellyshelf.navigation.AppNavKey.Detail",
                  "youtubeId":"1ubm7Q6DL-I","origin":null}]
            """.trimIndent(),
        )
        assertEquals(
            listOf(AppNavKey.Library, AppNavKey.Detail("1ubm7Q6DL-I", PlayerOrigin.None)),
            startStackOf(repo),
        )
    }

    /** A stack written by an older schema must degrade to Library, never crash the launch. */
    @Test
    fun `an unreadable saved stack falls back to Library`() = runTest {
        val repo = FakeSettingsRepository(
            emptySettings.copy(serverUrl = "https://example.org", apiKey = "key"),
            backStackJson = """[{"type":"com.example.Removed","gone":true}]""",
        )
        assertEquals(listOf(AppNavKey.Library), startStackOf(repo))
    }

    @Test
    fun `an empty saved stack falls back to Library`() = runTest {
        val repo = FakeSettingsRepository(
            emptySettings.copy(serverUrl = "https://example.org", apiKey = "key"),
            backStackJson = "[]",
        )
        assertEquals(listOf(AppNavKey.Library), startStackOf(repo))
    }

    /** Never synced but credentials present: the user got as far as connecting, so Library holds. */
    @Test
    fun `credentials without a sync still start on Library`() = runTest {
        val repo = FakeSettingsRepository(
            emptySettings.copy(serverUrl = "https://example.org", apiKey = "key", lastSyncAt = 0L),
        )
        assertEquals(listOf(AppNavKey.Library), startStackOf(repo))
    }
}
