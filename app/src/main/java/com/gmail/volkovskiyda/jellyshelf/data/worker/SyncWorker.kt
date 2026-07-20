package com.gmail.volkovskiyda.jellyshelf.data.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gmail.volkovskiyda.jellyshelf.JellyshelfApplication
import com.gmail.volkovskiyda.jellyshelf.data.repository.SyncResult
import java.util.concurrent.TimeUnit

/** Periodically reconciles watch state and library contents with Jellyfin. */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? JellyshelfApplication ?: return Result.failure()
        // No credentials yet is a configuration state, not a transient failure — retrying with
        // backoff would spin forever, since the worker is scheduled before setup completes.
        if (!app.container.settingsRepository.snapshot().isConnected) return Result.success()
        return when (app.container.libraryRepository.sync()) {
            is SyncResult.Success -> Result.success()
            is SyncResult.Error -> Result.retry()
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
