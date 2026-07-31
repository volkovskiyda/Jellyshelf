package com.gmail.volkovskiyda.jellyshelf.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.domain.DispatcherProvider
import com.gmail.volkovskiyda.jellyshelf.domain.model.DurationBucket
import com.gmail.volkovskiyda.jellyshelf.ui.categories.CategoriesFilterState
import com.gmail.volkovskiyda.jellyshelf.ui.library.LibraryFilterState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The duration filter and the search-all toggle across a process restart, against the real
 * DataStore — the state holders are process-lifetime singletons, so "restart" here means building
 * a fresh holder over the same store, which is exactly what a cold launch does.
 *
 * The interesting cases are the two that are not a plain round-trip: an id this version no longer
 * knows must degrade to "no filter" rather than fail a launch, and a value the user set before the
 * async read landed must win over the one coming off disk.
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
     */
    private suspend fun <T> awaitPersisted(flow: Flow<T>, expected: T) {
        val reached = withTimeoutOrNull(WRITE_TIMEOUT_MS) {
            flow.first { it == expected }
            true
        }
        assertTrue("write never reached disk: expected $expected", reached == true)
    }

    @Test
    fun theDurationFilter_survivesARestart() = runBlocking {
        val repo = repository()
        LibraryFilterState(repo, dispatchers).setDurationFilter(DurationBucket.FROM_10_TO_30)
        awaitPersisted(repo.libraryDurationFilter, DurationBucket.FROM_10_TO_30)

        // A fresh holder over the same store is what a cold launch builds. Its restore is async
        // too, so wait for it rather than reading .value out from under the disk read.
        val restored = LibraryFilterState(repository(), dispatchers)
        assertEquals(
            DurationBucket.FROM_10_TO_30,
            withTimeout(WRITE_TIMEOUT_MS) { restored.durationFilter.first { it != null } },
        )
    }

    @Test
    fun clearingTheDurationFilter_removesItFromDisk() = runBlocking {
        // Asserted at the repository rather than through a holder: "restores as null" and "has
        // not restored yet" look identical from outside, so only the store can answer exactly.
        val repo = repository()
        LibraryFilterState(repo, dispatchers).setDurationFilter(DurationBucket.OVER_60)
        awaitPersisted(repo.libraryDurationFilter, DurationBucket.OVER_60)

        LibraryFilterState(repo, dispatchers).setDurationFilter(null)
        awaitPersisted(repo.libraryDurationFilter, null)
        assertNull(repo.libraryDurationFilter.first())
    }

    @Test
    fun anUnknownBucketId_readsBackAsNoFilterInsteadOfThrowing() = runBlocking {
        // What a bucket removed or renumbered in a later version leaves behind on disk.
        context.dataStore.edit { it[stringPreferencesKey("library_duration_filter")] = "99" }

        assertNull(repository().libraryDurationFilter.first())
    }

    @Test
    fun aFilterPickedBeforeTheReadLands_isNotClobberedByTheRestore() = runBlocking {
        // A different bucket on disk to the one the user picks, so the two outcomes are
        // distinguishable: guard working → the pick stands; guard missing → the disk value wins.
        context.dataStore.edit { it[stringPreferencesKey("library_duration_filter")] = "0" }

        val holder = LibraryFilterState(repository(), dispatchers)
        // Straight after construction, so the disk read cannot have landed yet — this is the user
        // reaching the filter menu before the restore does.
        holder.durationFilter.value = DurationBucket.OVER_60

        // An absence assertion, so the timeout is the bug detector rather than a synchronisation
        // point: it waits for the clobber to happen, and passes because it never does.
        val clobbered = withTimeoutOrNull(CLOBBER_WATCH_MS) {
            holder.durationFilter.first { it == DurationBucket.UNDER_10 }
        }
        assertNull("the restore overwrote a filter the user had already picked", clobbered)
        assertEquals(DurationBucket.OVER_60, holder.durationFilter.value)
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

        /** Generous next to a preferences read, which is what the clobber would ride in on. */
        const val CLOBBER_WATCH_MS = 2_000L
    }
}
