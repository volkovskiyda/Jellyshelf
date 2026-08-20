package com.gmail.volkovskiyda.jellyshelf.data.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.domain.UpdateCheckSchedule
import com.gmail.volkovskiyda.jellyshelf.domain.UpdateChecker
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository
import com.gmail.volkovskiyda.jellyshelf.domain.shouldNotifyAboutUpdate
import com.gmail.volkovskiyda.jellyshelf.util.ActivityTracker
import com.gmail.volkovskiyda.jellyshelf.util.UpdateNotification
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import android.os.Build as AndroidBuild

/**
 * The update check, run daily by WorkManager so a user who rarely opens the app is not a week
 * behind the release they are being told about only at launch.
 *
 * It runs the checker's **automatic** path rather than a policy of its own: same once-a-day floor,
 * same snooze and dismissal rules, same bail when App Distribution needs a sign-in. A second
 * implementation of those rules is a second thing to keep in step, and the one that ran unattended
 * would be the one nobody noticed drifting.
 */
class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
    private val updateChecker: UpdateChecker,
    private val settingsRepository: SettingsRepository,
    private val activityTracker: ActivityTracker,
    private val buildInfo: BuildInfo,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // checkPeriodic rather than checkOnStart: the latter is fire-and-forget on the application
        // scope, which is right for a launch and useless here — a worker that returns before the
        // check lands has done nothing at all, and would have looked like it worked.
        updateChecker.checkPeriodic()
        val info = updateChecker.available.value?.info
        val lastNotified = settingsRepository.lastNotifiedUpdate.first()
        val notify = shouldNotifyAboutUpdate(
            offeredVersionCode = info?.versionCode,
            lastNotifiedVersionCode = lastNotified,
            appIsForeground = activityTracker.current() != null,
            permissionGranted = notificationsAllowed(),
        )
        if (notify && info != null) {
            UpdateNotification.post(applicationContext, info.versionName)
            // Stamped only on a notification that was actually posted — see the policy.
            settingsRepository.setLastNotifiedUpdate(info.versionCode)
        }
        // Success even when there was nothing to say: "no update today" is the ordinary outcome,
        // and retrying it would spend battery re-asking a question already answered. A transport
        // failure is absorbed by the checker into its error flow, which Settings renders — there is
        // nothing here that a backoff would fix that tomorrow's run will not.
        return Result.success()
    }

    private fun notificationsAllowed(): Boolean =
        if (!buildInfo.isAtLeast(AndroidBuild.VERSION_CODES.TIRAMISU)) {
            true
        } else {
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }
}

/**
 * Owns the periodic update check's enqueue, beside [SyncScheduler] and for the same reason: the
 * schedule is WorkManager's, not a screen's.
 *
 * Unlike sync, this *is* (re)scheduled at app start. Sync deliberately is not, because Sign out
 * cancels it and an Application-level schedule would resurrect it; the update check has no such
 * pairing — it is cancelled when the user turns the channel off, which is a state the next start
 * reads back and honours.
 */
class UpdateCheckScheduler(private val workManager: WorkManager) : UpdateCheckSchedule {

    /** UPDATE so a changed period or constraint reaches an already-enqueued worker. */
    override fun schedule() {
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(CHECK_PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** The channel is off (or this is a debug build): stop asking. */
    override fun cancel() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    companion object {
        internal const val WORK_NAME = "jellyshelf-update-check"

        /**
         * Daily. The checker's own floor is a day as well, so a shorter period would spend wake-ups
         * on checks that return immediately without asking anything.
         */
        private const val CHECK_PERIOD_HOURS = 24L
    }
}
