package com.gmail.volkovskiyda.jellyshelf.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.domain.model.VideoScaleMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The player's scale mode across a process restart, against the real DataStore — a fresh
 * [DefaultSettingsRepository] over the same store, which is what a cold launch builds. Persisting
 * it at all is only worth anything if the next launch comes back in the same mode.
 *
 * Shaped like [PlaybackSpeedPersistenceInstrumentedTest], including why no `awaitPersisted` poll is
 * needed: the test awaits the suspend `ds.edit` itself.
 */
@RunWith(AndroidJUnit4::class)
class VideoScaleModePersistenceInstrumentedTest {

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
    fun everyMode_survivesARestart() = runBlocking {
        VideoScaleMode.entries.forEach { mode ->
            repository().setVideoScaleMode(mode)
            assertEquals(mode, repository().settings.first().videoScaleMode)
        }
    }

    @Test
    fun nothingSaved_startsFitted() = runBlocking {
        assertEquals(VideoScaleMode.FIT, repository().settings.first().videoScaleMode)
    }

    /** A corrupt value, or one a later build renamed. Never a stretched picture on next launch. */
    @Test
    fun anUnknownStoredMode_readsBackAsFit() = runBlocking {
        context.dataStore.edit { it[stringPreferencesKey("video_scale_mode")] = "sideways" }

        assertEquals(VideoScaleMode.FIT, repository().settings.first().videoScaleMode)
    }
}
