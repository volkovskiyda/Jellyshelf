package com.gmail.volkovskiyda.jellyshelf.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlaybackSpeed
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The player's speed across a process restart, against the real DataStore. "Restart" here is a
 * fresh [DefaultSettingsRepository] over the same store, which is what a cold launch builds — the
 * point of persisting the speed at all is that the next launch starts at it.
 *
 * No `awaitPersisted` poll like [FilterPersistenceInstrumentedTest] has, deliberately: that helper
 * exists because the filter holders write fire-and-forget on `applicationScope`, so a read straight
 * afterwards races the disk. Here the test awaits the suspend `ds.edit` itself, and `first()` on a
 * *fresh* collection always sees the current value — the API 30 defect it works around is about
 * updates dropped for an already-collecting flow, which nothing here does.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackSpeedPersistenceInstrumentedTest {

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
    fun aPickedSpeed_survivesARestart() = runBlocking {
        repository().setPlaybackSpeed(1.5f)

        assertEquals(1.5f, repository().settings.first().playbackSpeed, 0f)
    }

    @Test
    fun nothingSaved_startsAtNormalSpeed() = runBlocking {
        assertEquals(PlaybackSpeed.DEFAULT, repository().settings.first().playbackSpeed, 0f)
    }

    /**
     * What a speed dropped from the menu in a later build leaves behind. It must degrade to 1×
     * rather than come back as a chip no menu item can tick.
     */
    @Test
    fun aSpeedThisBuildNoLongerOffers_readsBackAsNormalSpeed() = runBlocking {
        context.dataStore.edit { it[floatPreferencesKey("playback_speed")] = 1.3f }

        assertEquals(PlaybackSpeed.DEFAULT, repository().settings.first().playbackSpeed, 0f)
    }

    /** Every option is a binary fraction, so each one must come back bit-exact — see [PlaybackSpeed]. */
    @Test
    fun everyMenuOption_survivesARoundTrip() = runBlocking {
        PlaybackSpeed.options.forEach { speed ->
            repository().setPlaybackSpeed(speed)
            assertEquals(speed, repository().settings.first().playbackSpeed, 0f)
        }
    }
}
