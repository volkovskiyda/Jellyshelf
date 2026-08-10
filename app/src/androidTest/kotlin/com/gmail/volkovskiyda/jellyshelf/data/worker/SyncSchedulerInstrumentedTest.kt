package com.gmail.volkovskiyda.jellyshelf.data.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [SyncScheduler]'s enqueue policies (the plan's item 02). These are the rules that decide whether
 * a double-tapped "Sync now" runs one sync or two, whether a changed period reaches an
 * already-scheduled worker, and whether Sign out's wipe genuinely stops background syncing —
 * none of which is visible in the app until it misbehaves.
 *
 * Uses `WorkManagerTestInitHelper`, which swaps in a test driver and a synchronous executor, so no
 * worker actually executes here; only the enqueued work is inspected.
 */
@RunWith(AndroidJUnit4::class)
class SyncSchedulerInstrumentedTest {

    private lateinit var workManager: WorkManager
    private lateinit var scheduler: SyncScheduler

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
        scheduler = SyncScheduler(workManager)
    }

    private fun infosFor(name: String): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(name).get()

    @Test
    fun syncNow_enqueuesAManualSyncAndCreatesThePeriodicOne() {
        scheduler.syncNow()

        assertEquals(1, infosFor(MANUAL_WORK_NAME).size)
        assertEquals(1, infosFor(PERIODIC_WORK_NAME).size)
    }

    /** KEEP: a double-tap folds into the sync already queued rather than running two. */
    @Test
    fun syncNow_twiceKeepsASingleManualSync() {
        scheduler.syncNow()
        val first = infosFor(MANUAL_WORK_NAME).single().id

        scheduler.syncNow()

        val manual = infosFor(MANUAL_WORK_NAME)
        assertEquals(1, manual.size)
        assertEquals("the queued sync must be kept, not replaced", first, manual.single().id)
    }

    @Test
    fun periodicSync_requiresANetworkConnection() {
        scheduler.schedulePeriodic()

        // The constraint is what stops the worker burning retries with no connectivity at all.
        val info = infosFor(PERIODIC_WORK_NAME).single()
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
    }

    @Test
    fun manualSync_requiresANetworkConnection() {
        scheduler.syncNow()

        assertEquals(
            NetworkType.CONNECTED,
            infosFor(MANUAL_WORK_NAME).single().constraints.requiredNetworkType,
        )
    }

    /** Sign out must genuinely stop syncing — a survivor would refill the tables it just wiped. */
    @Test
    fun cancelAll_cancelsBothTheManualAndThePeriodicWork() {
        scheduler.syncNow()

        scheduler.cancelAll()

        assertTrue(infosFor(MANUAL_WORK_NAME).all { it.state == WorkInfo.State.CANCELLED })
        assertTrue(infosFor(PERIODIC_WORK_NAME).all { it.state == WorkInfo.State.CANCELLED })
    }

    /** UPDATE, not KEEP: a changed period or constraint has to reach the enqueued worker. */
    @Test
    fun schedulePeriodic_keepsExactlyOnePeriodicWorkerAcrossRepeatedCalls() {
        repeat(3) { scheduler.schedulePeriodic() }

        assertEquals(1, infosFor(PERIODIC_WORK_NAME).size)
    }

    @Test
    fun manualSyncInfo_reportsTheEnqueuedManualSync() = runBlocking {
        scheduler.syncNow()

        val infos = workManager.getWorkInfosForUniqueWork(MANUAL_WORK_NAME).get()
        assertEquals(1, infos.size)
        assertTrue(
            "expected a live manual sync, got ${infos.single().state}",
            infos.single().state == WorkInfo.State.ENQUEUED ||
                infos.single().state == WorkInfo.State.RUNNING ||
                infos.single().state == WorkInfo.State.SUCCEEDED,
        )
    }

    private companion object {
        // Mirrors SyncScheduler's private unique-work names; a rename there should fail here,
        // since the names are what make "unique" work unique across process restarts.
        const val PERIODIC_WORK_NAME = "jellyshelf-periodic-sync"
        const val MANUAL_WORK_NAME = "jellyshelf-manual-sync"
    }
}
