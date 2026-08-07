package com.gmail.volkovskiyda.jellyshelf.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The update-check preferences across a process restart, against the real DataStore. "Restart" is a
 * fresh [DefaultSettingsRepository] over the same store, which is what a cold launch builds.
 *
 * Persistence is asserted by **re-reading from a fresh repository**, never by watching one
 * collection: on API 30 DataStore drops 1–2 flow update notifications per 25 writes, so a test that
 * collects once and waits for the next emission is flaky there by construction. Every read below
 * therefore builds its own repository and takes `first()` — a fresh collection always sees the
 * current value. Do not "simplify" these into a single collector.
 */
@RunWith(AndroidJUnit4::class)
class UpdatePreferencesInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The DataStore is a process singleton, so each test starts and leaves it empty. */
    @Before
    fun clearBefore() {
        runBlocking { context.dataStore.edit { it.clear() } }
    }

    @After
    fun clearAfter() {
        runBlocking { context.dataStore.edit { it.clear() } }
    }

    private fun repository() = DefaultSettingsRepository(context)

    /**
     * The gate that keeps the whole feature dark unless a user opts in — and the one that keeps it
     * dark for Test Lab, `LiveUiJourneyTest` and the `nonMinifiedRelease` / `benchmarkRelease`
     * profiling variants, which are non-debug builds with a real version code and so are not caught
     * by any build-type check. If this ever defaults to anything else, a baseline-profile run starts
     * making network calls.
     */
    @Test
    fun nothingSaved_checksNothing() = runBlocking {
        assertEquals(UpdateSource.NONE, repository().updateSource.first())
    }

    @Test
    fun everySource_survivesARestart() = runBlocking {
        UpdateSource.entries.forEach { source ->
            repository().setUpdateSource(source)
            assertEquals(source, repository().updateSource.first())
        }
    }

    /** What a source removed in a later build (or a corrupt value) leaves behind: stop checking. */
    @Test
    fun anUnrecognizedSource_readsBackAsOff() = runBlocking {
        context.dataStore.edit { it[stringPreferencesKey("update_source")] = "carrier-pigeon" }

        assertEquals(UpdateSource.NONE, repository().updateSource.first())
    }

    @Test
    fun nothingSaved_hasDismissedNothing() = runBlocking {
        val repository = repository()

        assertEquals(0, repository.dismissedUpdate(UpdateSource.GITHUB).first())
        assertEquals(0L, repository.dismissedUpdateAt(UpdateSource.GITHUB).first())
        assertEquals(0, repository.dismissedUpdate(UpdateSource.APP_DISTRIBUTION).first())
        assertEquals(0L, repository.dismissedUpdateAt(UpdateSource.APP_DISTRIBUTION).first())
    }

    @Test
    fun aDismissal_persistsBothItsCodeAndItsTime() = runBlocking {
        repository().setDismissedUpdate(UpdateSource.GITHUB, versionCode = 170, timestamp = 1_700L)

        val repository = repository()
        assertEquals(170, repository.dismissedUpdate(UpdateSource.GITHUB).first())
        assertEquals(1_700L, repository.dismissedUpdateAt(UpdateSource.GITHUB).first())
    }

    /** Switching channel must not inherit the other one's silence — both halves stay separate. */
    @Test
    fun theTwoChannelsDismissIndependently() = runBlocking {
        repository().setDismissedUpdate(UpdateSource.GITHUB, versionCode = 170, timestamp = 1_700L)

        val afterGitHub = repository()
        assertEquals(0, afterGitHub.dismissedUpdate(UpdateSource.APP_DISTRIBUTION).first())
        assertEquals(0L, afterGitHub.dismissedUpdateAt(UpdateSource.APP_DISTRIBUTION).first())

        repository().setDismissedUpdate(
            UpdateSource.APP_DISTRIBUTION,
            versionCode = 181,
            timestamp = 1_800L,
        )

        val afterBoth = repository()
        assertEquals(170, afterBoth.dismissedUpdate(UpdateSource.GITHUB).first())
        assertEquals(1_700L, afterBoth.dismissedUpdateAt(UpdateSource.GITHUB).first())
        assertEquals(181, afterBoth.dismissedUpdate(UpdateSource.APP_DISTRIBUTION).first())
        assertEquals(1_800L, afterBoth.dismissedUpdateAt(UpdateSource.APP_DISTRIBUTION).first())
    }

    /** A channel that never checks has nothing to dismiss, and writing to it is a no-op. */
    @Test
    fun offNeverDismissesAnything() = runBlocking {
        repository().setDismissedUpdate(UpdateSource.NONE, versionCode = 170, timestamp = 1_700L)

        val repository = repository()
        assertEquals(0, repository.dismissedUpdate(UpdateSource.NONE).first())
        assertEquals(0L, repository.dismissedUpdateAt(UpdateSource.NONE).first())
        assertEquals(0, repository.dismissedUpdate(UpdateSource.GITHUB).first())
        assertEquals(0, repository.dismissedUpdate(UpdateSource.APP_DISTRIBUTION).first())
    }

    /** `0` is "never", which every window treats as already elapsed — so a fresh install checks. */
    @Test
    fun nothingSaved_hasNeverCheckedOrPrompted() = runBlocking {
        val repository = repository()

        assertEquals(0L, repository.lastUpdateCheckAt.first())
        assertEquals(0L, repository.lastUpdateDialogAt.first())
    }

    /** Asking and interrupting are throttled separately; one must not stamp the other. */
    @Test
    fun theCheckAndDialogTimesArePersistedIndependently() = runBlocking {
        repository().setLastUpdateCheckAt(2_600L)

        assertEquals(2_600L, repository().lastUpdateCheckAt.first())
        assertEquals(0L, repository().lastUpdateDialogAt.first())

        repository().setLastUpdateDialogAt(2_900L)

        val repository = repository()
        assertEquals(2_600L, repository.lastUpdateCheckAt.first())
        assertEquals(2_900L, repository.lastUpdateDialogAt.first())
    }

    // --- The notification prompt's two halves ---------------------------------------------------
    //
    // Tested here rather than only through FakeSettingsRepository, because the behaviour that
    // matters — the flag that only ever turns on — is a line of logic *inside*
    // DefaultSettingsRepository's edit block, and the fake reimplements it. A fake agreeing with
    // itself proves nothing about the store the app ships.

    /** `0` and `false`: a fresh install is due as soon as it has a library, not muted for a week. */
    @Test
    fun nothingSaved_hasNeverBeenPromptedForNotifications() = runBlocking {
        val repository = repository()

        assertEquals(0L, repository.notificationPromptAt.first())
        assertEquals(false, repository.notificationSystemAsked.first())
    }

    @Test
    fun aPromptAnswer_persistsBothHalvesTogether() = runBlocking {
        repository().setNotificationPrompt(timestamp = 3_300L, systemAsked = true)

        val repository = repository()
        assertEquals(3_300L, repository.notificationPromptAt.first())
        assertEquals(true, repository.notificationSystemAsked.first())
    }

    /**
     * The latch: once Android's dialog has been reached, a later "Not now" restarts the snooze but
     * cannot un-ask it — otherwise a permission locked by two denials would read as a fresh
     * install, and the prompt would return every week with an "Allow" that provably does nothing.
     */
    @Test
    fun theSystemAskedFlag_onlyEverTurnsOn() = runBlocking {
        repository().setNotificationPrompt(timestamp = 3_300L, systemAsked = true)
        repository().setNotificationPrompt(timestamp = 4_400L, systemAsked = false)

        val repository = repository()
        assertEquals(4_400L, repository.notificationPromptAt.first())
        assertEquals(true, repository.notificationSystemAsked.first())
    }
}
