package com.gmail.volkovskiyda.jellyshelf.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.domain.DispatcherProvider
import com.gmail.volkovskiyda.jellyshelf.domain.model.AnchorPosition
import com.gmail.volkovskiyda.jellyshelf.domain.model.ScrollPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The disk-seed half of [DefaultScrollPositionRepository], against the real DataStore.
 *
 * Specifically the blank-anchor guard (the plan's item 06): an interrupted write can leave only
 * the offset half of an anchor pair on disk, which seeds `AnchorPosition("", n)` — an anchor no
 * item can ever match. Reported as a saved position it would scroll the list nowhere and look
 * like the restore silently failing.
 */
@RunWith(AndroidJUnit4::class)
class ScrollPositionInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val dispatchers = object : DispatcherProvider {
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.IO
        override val ioSequential = Dispatchers.IO
        override val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        override fun ioScope(tag: String, failureMessage: String) =
            CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    /** The DataStore is a process singleton, so each test starts and leaves it empty. */
    @Before
    fun clearBefore() {
        runBlocking { context.scrollDataStore.edit { it.clear() } }
    }

    @After
    fun clearAfter() {
        runBlocking { context.scrollDataStore.edit { it.clear() } }
    }

    private fun repository() = DefaultScrollPositionRepository(context, dispatchers)

    @Test
    fun peekAnchor_reportsNothingSavedForAHalfWrittenPair() = runBlocking {
        // Only the offset half on disk — the anchor id never landed.
        context.scrollDataStore.edit { it[intPreferencesKey("library.anchorOffset")] = 42 }

        val repo = repository()
        repo.awaitSeeded()

        assertNull(repo.peekAnchor("library"))
    }

    @Test
    fun peekAnchor_returnsACompletePairFromDisk() = runBlocking {
        context.scrollDataStore.edit {
            it[stringPreferencesKey("library.anchor")] = "dQw4w9WgXcQ"
            it[intPreferencesKey("library.anchorOffset")] = 42
        }

        val repo = repository()
        repo.awaitSeeded()

        assertEquals(AnchorPosition("dQw4w9WgXcQ", 42), repo.peekAnchor("library"))
    }

    /** An anchor saved this session must win over the disk value the seed brings in later. */
    @Test
    fun peekAnchor_prefersAnAnchorSavedBeforeTheSeedLanded() = runBlocking {
        context.scrollDataStore.edit {
            it[stringPreferencesKey("library.anchor")] = "old-anchor-"
            it[intPreferencesKey("library.anchorOffset")] = 42
        }

        val repo = repository()
        repo.saveAnchor("library", AnchorPosition("new-anchor-", 7))
        repo.awaitSeeded()

        assertEquals(AnchorPosition("new-anchor-", 7), repo.peekAnchor("library"))
    }

    @Test
    fun peek_returnsZeroForAScreenWithNothingSaved() = runBlocking {
        val repo = repository()
        repo.awaitSeeded()

        assertEquals(ScrollPosition.Zero, repo.peek("never-seen"))
    }

    /**
     * The cache is the source of truth while running: a save is readable on the next frame, not
     * after a disk round-trip — that immediacy is the whole reason the cache exists.
     */
    @Test
    fun save_isReadableImmediatelyWithoutWaitingForDisk() = runBlocking {
        val repo = repository()

        repo.save("library", ScrollPosition(index = 12, offset = 34))

        assertEquals(ScrollPosition(12, 34), repo.peek("library"))
    }

    /** A disk position seeds the cache, so it survives a process restart. */
    @Test
    fun peek_restoresAPositionSeededFromDisk() = runBlocking {
        context.scrollDataStore.edit {
            it[intPreferencesKey("library.index")] = 12
            it[intPreferencesKey("library.offset")] = 34
        }

        val repo = repository()
        repo.awaitSeeded()

        assertEquals(ScrollPosition(12, 34), repo.peek("library"))
    }
}
