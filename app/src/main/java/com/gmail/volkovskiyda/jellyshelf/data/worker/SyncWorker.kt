package com.gmail.volkovskiyda.jellyshelf.data.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gmail.volkovskiyda.jellyshelf.domain.model.SyncResult
import com.gmail.volkovskiyda.jellyshelf.domain.repository.LibraryRepository
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository
import java.util.concurrent.TimeUnit

/** Periodically reconciles watch state and library contents with Jellyfin. */
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
            is SyncResult.Success -> Result.success()
            // A revoked key or deleted scope can't self-heal either — retrying such a failure
            // would burn network/battery forever; the next manual sync surfaces the error.
            is SyncResult.Error -> if (result.retryable) Result.retry() else Result.failure()
        }
    }
}

object SyncScheduler {
    private const val WORK_NAME = "jellyshelf-periodic-sync"

    fun schedulePeriodic(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
