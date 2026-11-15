package com.gmail.volkovskiyda.jellyshelf.data.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gmail.volkovskiyda.jellyshelf.domain.model.SyncResult
import com.gmail.volkovskiyda.jellyshelf.domain.repository.LibraryRepository
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

/** Reconciles watch state and library contents with Jellyfin, periodically and on demand. */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
    private val settingsRepository: SettingsRepository,
    private val libraryRepository: LibraryRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // No credentials is a configuration state, not a transient failure — retrying with
        // backoff would spin forever. An expired token clears the session, not the schedule.
        if (!settingsRepository.snapshot().isConnected) return Result.success()
        return when (val result = libraryRepository.sync()) {
            is SyncResult.Success -> Result.success(
                workDataOf(
                    KEY_MATCHED to result.matched,
                    KEY_INDEXED to result.indexed,
                    KEY_CATEGORIES to result.categories,
                    KEY_INDEX_DEGRADED to result.indexDegraded,
                    KEY_API_DEGRADED to result.apiDegraded,
                    KEY_API_AUTH_FAILED to result.apiAuthFailed,
                    KEY_AUTO_FILLED to result.autoFilled,
                    KEY_AUTO_FILL_FAILED to result.autoFillFailed,
                ),
            )
            is SyncResult.Error ->
                if (shouldRetrySync(result, inputData.getBoolean(KEY_MANUAL, false), runAttemptCount)) {
                    Result.retry()
                } else {
                    Result.failure(workDataOf(KEY_ERROR to result.message))
                }
        }
    }

    companion object {
        const val KEY_MANUAL = "manual"
        const val KEY_MATCHED = "matched"
        const val KEY_INDEXED = "indexed"
        const val KEY_CATEGORIES = "categories"
        const val KEY_INDEX_DEGRADED = "indexDegraded"
        const val KEY_API_DEGRADED = "apiDegraded"
        const val KEY_API_AUTH_FAILED = "apiAuthFailed"
        const val KEY_AUTO_FILLED = "autoFilled"
        const val KEY_AUTO_FILL_FAILED = "autoFillFailed"
        const val KEY_ERROR = "error"
    }
}

/**
 * How many times an unattended sync is run again after a transient failure before it gives up.
 *
 * Bounded because the commonest transient failure is not a blip but a state: the phone is away
 * from the network the server lives on, the `CONNECTED` constraint is satisfied by any network at
 * all, and every attempt then spends a full request timeout proving the server is still not
 * there. Unbounded, the periodic sync kept doing that on WorkManager's default 30 s backoff —
 * five full syncs in six minutes in a process the system had woken for nothing else.
 */
internal const val MAX_SYNC_RETRIES = 2

/**
 * Whether a failed sync should be handed back to WorkManager to run again, or reported as failed.
 *
 * A revoked key or deleted scope can't self-heal — retrying such a failure would burn network and
 * battery forever; the next manual sync surfaces the error. A manual sync never retries even when
 * the failure is transient: the user is watching the screen and deserves the error now, with the
 * button free to tap again. And an unattended one stops after [MAX_SYNC_RETRIES]: giving up costs
 * the periodic sync nothing but the wait for its next period, since a periodic request that fails
 * stays scheduled, while the repair sync is simply asked for again by the next stale item.
 *
 * [runAttemptCount] is WorkManager's: zero on the first run, one more on each retry, and back to
 * zero when a periodic request starts a new period.
 */
internal fun shouldRetrySync(error: SyncResult.Error, manual: Boolean, runAttemptCount: Int): Boolean =
    error.retryable && !manual && runAttemptCount < MAX_SYNC_RETRIES

/**
 * Owns every sync enqueue. Sync is WorkManager's job, not a ViewModel's: tab switches clear tab
 * ViewModels, so a sync running in `viewModelScope` was silently cancelled mid-flight.
 *
 * The periodic worker is created by "Sync now" rather than at app start, so Sign out
 * can genuinely stop background syncing — an Application-level schedule would just resurrect it
 * on the next launch.
 */
class SyncScheduler(private val workManager: WorkManager) {

    /** Enqueue an immediate sync and (re)create the periodic one. */
    fun syncNow() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(SyncWorker.KEY_MANUAL to true))
            .build()
        // KEEP: a double-tap folds into the sync already queued instead of running two.
        workManager.enqueueUniqueWork(MANUAL_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        schedulePeriodic()
    }

    /**
     * Enqueue a background repair sync: playback found local state the server contradicts —
     * today, a stream 404 on a stale item id — and a sync is what rewrites it.
     *
     * Deliberately not [syncNow], which models a user watching the screen: that one enqueues
     * under the name the Settings row and the failure snackbar watch, marks the work manual so a
     * transient failure surfaces immediately instead of retrying, and re-creates the periodic
     * worker. None of that fits an unattended repair — this one keeps its own name so no UI
     * lights up on its behalf, lets a transient failure retry with backoff since nobody is
     * looking at an error, and leaves the periodic schedule exactly as the user last set it.
     */
    fun repairSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        // KEEP: every queued item that 404s asks again, and one repair answers them all.
        workManager.enqueueUniqueWork(REPAIR_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * UPDATE so a changed period/constraint reaches an already-enqueued worker.
     *
     * The first run is a whole period out. This is only ever created beside a manual sync that
     * [syncNow] has just enqueued, and a periodic request with no delay runs at once — so every
     * first "Sync now" after signing in ran two full syncs side by side, auto-fill and all (two
     * `library_sync` samples per tap, and on the benchmark a first run each cold-start kill cut
     * short, which WorkManager then restarted the process to retry). UPDATE keeps an enqueued
     * request's original enqueue time, so re-stating this on every tap never pushes that first
     * run further out.
     */
    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(SYNC_PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setInitialDelay(SYNC_PERIOD_HOURS, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** Stop all syncing — paired with wiping local data, which a sync would otherwise refill. */
    fun cancelAll() {
        workManager.cancelUniqueWork(MANUAL_WORK_NAME)
        workManager.cancelUniqueWork(REPAIR_WORK_NAME)
        workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    /** State of the manual sync, so the UI can show progress it doesn't own. */
    fun manualSyncInfo(): Flow<List<WorkInfo>> =
        workManager.getWorkInfosForUniqueWorkFlow(MANUAL_WORK_NAME)

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    companion object {
        private const val PERIODIC_WORK_NAME = "jellyshelf-periodic-sync"
        private const val MANUAL_WORK_NAME = "jellyshelf-manual-sync"
        private const val REPAIR_WORK_NAME = "jellyshelf-repair-sync"
        private const val SYNC_PERIOD_HOURS = 2L

        // The first retry of an unattended sync, doubling after that — see [shouldRetrySync] for
        // where it stops. Minutes rather than WorkManager's default 30 s: a server that did not
        // answer is rarely back half a minute later, and each attempt costs a request timeout.
        private const val RETRY_BACKOFF_MINUTES = 5L
    }
}
