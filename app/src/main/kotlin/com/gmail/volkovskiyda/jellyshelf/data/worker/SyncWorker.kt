package com.gmail.volkovskiyda.jellyshelf.data.worker

import android.content.Context
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
        // No credentials yet is a configuration state, not a transient failure — retrying with
        // backoff would spin forever, since the worker is scheduled before setup completes.
        if (!settingsRepository.snapshot().isConnected) return Result.success()
        return when (val result = libraryRepository.sync()) {
            is SyncResult.Success -> Result.success(
                workDataOf(
                    KEY_MATCHED to result.matched,
                    KEY_INDEXED to result.indexed,
                    KEY_CATEGORIES to result.categories,
                    KEY_INDEX_DEGRADED to result.indexDegraded,
                    KEY_AUTO_FILLED to result.autoFilled,
                    KEY_AUTO_FILL_FAILED to result.autoFillFailed,
                ),
            )
            // A revoked key or deleted scope can't self-heal either — retrying such a failure
            // would burn network/battery forever; the next manual sync surfaces the error.
            // A manual sync never retries even when the failure is transient: the user is
            // watching the screen and deserves the error now, with the button free to tap again.
            is SyncResult.Error ->
                if (result.retryable && !inputData.getBoolean(KEY_MANUAL, false)) {
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
        const val KEY_AUTO_FILLED = "autoFilled"
        const val KEY_AUTO_FILL_FAILED = "autoFillFailed"
        const val KEY_ERROR = "error"
    }
}

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

    /** UPDATE so a changed period/constraint reaches an already-enqueued worker. */
    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(SYNC_PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
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
        private const val SYNC_PERIOD_HOURS = 3L
    }
}
