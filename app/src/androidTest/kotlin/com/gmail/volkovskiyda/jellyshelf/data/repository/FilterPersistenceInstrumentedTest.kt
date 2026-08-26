package com.gmail.volkovskiyda.jellyshelf.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.domain.DispatcherProvider
import com.gmail.volkovskiyda.jellyshelf.ui.categories.CategoriesFilterState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Categories search-all toggle across a process restart, against the real DataStore — the state
 * holder is a process-lifetime singleton, so "restart" here means building a fresh holder over the
 * same store, which is exactly what a cold launch does.
 *
 * The interesting part is not the round-trip but the restore's own signal: the Categories pager
 * waits on `selectionLoaded`, so the toggle has to be in place by the time that flips.
 */
@RunWith(AndroidJUnit4::class)
class FilterPersistenceInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val dispatchers = object : DispatcherProvider {
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.IO
        override val ioSequential = Dispatchers.IO

        // A real dispatcher, like production: restores and writes are awaited below rather than
        // assumed to have finished by the time a constructor or setter returns.
        override val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        override fun ioScope(tag: String, failureMessage: String) =
            CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

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
     * Waits for a fire-and-forget write to reach disk.
     *
     * The holders persist on [DispatcherProvider.applicationScope] without awaiting the result —
     * deliberately, since nothing in the UI should block on a preference write — so a test that
     * reads straight back is racing the disk rather than testing anything.
     *
     * Re-reads the store rather than watching one collection for the value to arrive: a poll tests
     * what this class means to test, "the value is in the store", rather than the platform's
     * willingness to announce it to a collector that subscribed first.
     */
    private suspend fun <T> awaitPersisted(flow: Flow<T>, expected: T) {
        val reached = withTimeoutOrNull(WRITE_TIMEOUT_MS) {
            while (flow.first() != expected) delay(POLL_INTERVAL_MS)
            true
        }
        assertTrue("value never appeared in the store: expected $expected", reached == true)
    }

    @Test
    fun theSearchAllToggle_survivesARestart() = runBlocking {
        val repo = repository()
        CategoriesFilterState(repo, dispatchers).setSearchAll(true)
        awaitPersisted(repo.categoriesSearchAll, true)

        // selectionLoaded is the exact signal that the restore finished — the Categories pager
        // waits on the same flag, which is why searchAll must land before it flips.
        val restored = CategoriesFilterState(repository(), dispatchers)
        assertTrue(
            "restore never finished",
            withTimeoutOrNull(WRITE_TIMEOUT_MS) { restored.selectionLoaded.first { it } } == true,
        )
        assertTrue(restored.searchAll.value)
    }

    @Test
    fun searchAllDefaultsToOff_withNothingSaved() = runBlocking {
        assertFalse(repository().categoriesSearchAll.first())

        val restored = CategoriesFilterState(repository(), dispatchers)
        withTimeout(WRITE_TIMEOUT_MS) { restored.selectionLoaded.first { it } }
        assertFalse(restored.searchAll.value)
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 20L
    }
}
