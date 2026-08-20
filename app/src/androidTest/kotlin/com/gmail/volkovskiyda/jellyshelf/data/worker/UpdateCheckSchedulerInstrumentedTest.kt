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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [UpdateCheckScheduler]'s enqueue policy — the half of the daily update check that no unit test
 * can see, because it is entirely about what WorkManager ends up holding.
 *
 * The rules here are the ones that misbehave quietly: a schedule that does not replace itself
 * leaves an old period running forever, and a cancel that does not take means a user who turned
 * update checks off still has a daily wake-up asking about them.
 *
 * `WorkManagerTestInitHelper` swaps in a test driver and a synchronous executor, so nothing
 * actually runs; only the enqueued work is inspected.
 */
@RunWith(AndroidJUnit4::class)
class UpdateCheckSchedulerInstrumentedTest {

    private lateinit var workManager: WorkManager
    private lateinit var scheduler: UpdateCheckScheduler

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
        scheduler = UpdateCheckScheduler(workManager)
    }

    private fun infos(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(UpdateCheckScheduler.WORK_NAME).get()

    @Test
    fun schedule_enqueuesTheDailyCheck() {
        scheduler.schedule()

        assertEquals(1, infos().size)
    }

    /** Only ever one: the unique name is what keeps a second call from stacking a second worker. */
    @Test
    fun scheduling_twiceKeepsASingleWorker() {
        scheduler.schedule()

        scheduler.schedule()

        assertEquals(1, infos().size)
    }

    /**
     * UPDATE, not KEEP. The period and the constraint are both things a later version may change,
     * and KEEP would leave every existing install on the old ones — with nothing to notice, since
     * a check that runs at the wrong interval still looks like it works.
     */
    @Test
    fun rescheduling_replacesTheEnqueuedWorkerRatherThanKeepingIt() {
        scheduler.schedule()
        val first = infos().single().id

        scheduler.schedule()

        assertEquals(first, infos().single().id)
        assertTrue(infos().single().state != WorkInfo.State.CANCELLED)
    }

    /** Turning the channel off has to stop the wake-up, or the setting is a lie. */
    @Test
    fun cancel_stopsTheDailyCheck() {
        scheduler.schedule()

        scheduler.cancel()

        assertEquals(WorkInfo.State.CANCELLED, infos().single().state)
    }

    /** Nothing to cancel is not an error — start-up cancels unconditionally when updates are off. */
    @Test
    fun cancel_withNothingScheduledIsHarmless() {
        scheduler.cancel()

        assertTrue(infos().isEmpty())
    }

    /** A check that fires without a network wastes a wake-up and reports a failure nobody sees. */
    @Test
    fun theCheck_waitsForANetwork() {
        scheduler.schedule()

        assertEquals(
            NetworkType.CONNECTED,
            infos().single().constraints.requiredNetworkType,
        )
    }
}
