package com.gmail.volkovskiyda.jellyshelf.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The last-played video id across a process restart, against the real DataStore.
 *
 * Surviving a restart is the entire point of this key: the system's media-resumption surfaces ask
 * a process that is already dead, so a value that lived only in memory would answer nothing every
 * single time — and would do it silently, since "nothing to resume" is also a legitimate answer.
 *
 * Persistence is asserted by **re-reading from a fresh repository**, the house style for these
 * tests: each read builds its own [DefaultSettingsRepository] over the same store, which is what a
 * cold launch builds. Deliberate — do not collapse these into one collector.
 */
@RunWith(AndroidJUnit4::class)
class LastPlayedPersistenceInstrumentedTest {

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

    @Test
    fun nothingPlayed_hasNothingToResume() = runBlocking {
        assertNull(repository().lastPlayedVideoId.first())
    }

    @Test
    fun theLastPlayedId_survivesARestart() = runBlocking {
        repository().setLastPlayedVideoId("aaaaaaaaaaa")

        assertEquals("aaaaaaaaaaa", repository().lastPlayedVideoId.first())
    }

    @Test
    fun playingSomethingElse_replacesIt() = runBlocking {
        repository().setLastPlayedVideoId("aaaaaaaaaaa")

        repository().setLastPlayedVideoId("bbbbbbbbbbb")

        assertEquals("bbbbbbbbbbb", repository().lastPlayedVideoId.first())
    }

    /**
     * The explicit stop. It has to read back as *absent*, not as an empty string, because absent is
     * the one spelling of "nothing" the resumption path checks — an empty id would survive that
     * check and go looking for a video with no name.
     */
    @Test
    fun clearing_leavesNothingToResume() = runBlocking {
        repository().setLastPlayedVideoId("aaaaaaaaaaa")

        repository().setLastPlayedVideoId(null)

        assertNull(repository().lastPlayedVideoId.first())
    }
}
