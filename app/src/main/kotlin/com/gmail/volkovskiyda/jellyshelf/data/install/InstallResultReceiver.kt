package com.gmail.volkovskiyda.jellyshelf.data.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.gmail.volkovskiyda.jellyshelf.domain.InstallOutcome
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateCheckError
import com.gmail.volkovskiyda.jellyshelf.util.ActivityTracker
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

/**
 * Where a `PackageInstaller` session reports what happened.
 *
 * Its real job is the middle case. A committed session does not install anything by itself: the
 * platform answers `STATUS_PENDING_USER_ACTION` with an intent that *asks* the user, and if nobody
 * launches that intent the install simply never happens — successfully, silently, and looking
 * exactly like a bug. Everything else here is turning an outcome into a sentence the snackbar can
 * already render.
 */
class InstallResultReceiver : BroadcastReceiver(), KoinComponent {

    private val activityTracker: ActivityTracker by inject()
    private val outcome: InstallOutcome by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_RESULT) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> confirm(context, intent)
            // Nothing to report: the app is about to be replaced by the new build, and a snackbar
            // in a process that is going away has nobody to show it to.
            PackageInstaller.STATUS_SUCCESS -> outcome.finished(null)
            PackageInstaller.STATUS_FAILURE_ABORTED -> outcome.finished(UpdateCheckError.InstallCancelled)
            else -> {
                Timber.w(
                    "Install failed: status=%d %s",
                    status,
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty(),
                )
                outcome.finished(UpdateCheckError.InstallFailed)
            }
        }
    }

    /**
     * Launches the system's confirmation.
     *
     * Preferably over the app's own foreground activity, so the prompt appears where the user is.
     * When there is none — the download outlived the app being backgrounded, which is exactly what
     * the app-wide narration was built to allow — the intent needs `NEW_TASK` to be launchable at
     * all from an application context. That is a worse experience than a prompt in place, and a far
     * better one than an install that stalls forever with no explanation.
     */
    private fun confirm(context: Context, intent: Intent) {
        val confirmation = IntentCompat.getConfirmationIntent(intent)
        if (confirmation == null) {
            Timber.w("Install asked for confirmation without an intent to show")
            outcome.finished(UpdateCheckError.InstallFailed)
            return
        }
        val activity = activityTracker.current()
        runCatching {
            if (activity != null) {
                activity.startActivity(confirmation)
            } else {
                context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }.onFailure {
            Timber.w(it, "Could not show the install confirmation")
            outcome.finished(UpdateCheckError.Interrupted)
        }
    }

    companion object {
        const val ACTION_INSTALL_RESULT = "com.gmail.volkovskiyda.jellyshelf.INSTALL_RESULT"
    }
}

/** Kept separate so the deprecation is quarantined to one place. */
private object IntentCompat {
    @Suppress("DEPRECATION")
    fun getConfirmationIntent(intent: Intent): Intent? =
        intent.getParcelableExtra(Intent.EXTRA_INTENT)
}
