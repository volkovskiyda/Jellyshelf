package com.gmail.volkovskiyda.jellyshelf.data.remote

import com.gmail.volkovskiyda.jellyshelf.domain.model.InstallStage
import com.gmail.volkovskiyda.jellyshelf.domain.model.InstallState
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateCheckError
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateInfo
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateSource
import com.google.android.gms.tasks.Task
import com.google.firebase.appdistribution.AppDistributionRelease
import com.google.firebase.appdistribution.FirebaseAppDistribution
import com.google.firebase.appdistribution.FirebaseAppDistributionException
import com.google.firebase.appdistribution.FirebaseAppDistributionException.Status
import com.google.firebase.appdistribution.UpdateProgress
import com.google.firebase.appdistribution.UpdateStatus
import com.google.firebase.appdistribution.UpdateTask
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The tester channel — every green `main` push, uploaded by CI to the release Firebase app id and
 * handed to the `testers` group — read through Firebase's App Distribution SDK, which is the only
 * thing that can see it.
 *
 * Presents the same `suspend fun latestRelease(): UpdateInfo?` as [GitHubReleaseSource] so the
 * checker can treat the two channels alike, and adds the two calls the settings screen needs to
 * gate selecting this channel on a working tester sign-in.
 *
 * **Sign-in is never automatic.** [signInTester] opens a Custom Tab, so it may only ever run from a
 * user gesture — tapping the channel chip, or "Check now". The cold-start check is forbidden from
 * calling it: throwing a browser over the app on launch is not an acceptable way to ask.
 *
 * On a build that links only the API artifact — **debug alone**, since the profiling variants
 * inherit the release build type's dependencies and so link the full SDK — every call here fails
 * with [Status.NOT_IMPLEMENTED] and [isTesterSignedIn] is a hardcoded `false`. The stub **returns a
 * failed Task rather than throwing**, so the bridge below resumes with the exception like any other
 * failure and nothing crashes; no hand-check of a debug build is needed to confirm it.
 *
 * `open` for the same reason [DemoBackend] and [YtDlpMetadataSource] are: the Firebase singleton
 * cannot be constructed on the JVM at all — `FirebaseException`'s constructor reaches
 * `android.text.TextUtils` — so a checker test that needs a signed-out tester has no other way to
 * say so.
 */
open class AppDistributionSource(
    private val signInLauncher: TesterSignIn,
    private val flags: UpdateFlags,
) {

    private val appDistribution: FirebaseAppDistribution
        get() = FirebaseAppDistribution.getInstance()

    /** Synchronous in the SDK, and a hardcoded `false` when only the stub is linked. */
    open fun isTesterSignedIn(): Boolean = appDistribution.isTesterSignedIn()

    /**
     * Opens the sign-in Custom Tab and suspends until the user finishes or backs out.
     *
     * Prefers [TesterSignInLauncher], which opens the same page in *this app's* task so the tab can
     * be closed afterwards; the SDK's own sign-in hardcodes `FLAG_ACTIVITY_NEW_TASK` and leaves a
     * browser task nothing here can reach. The launcher's KDoc has the detail, including why
     * bypassing the SDK still registers the sign-in.
     *
     * Falls back to `signInTester()` whenever the launcher reports it cannot run, so a device or an
     * SDK version where this does not work degrades to the old behaviour rather than to no sign-in.
     * [UpdateFlags.forceLegacySignIn] forces that same fallback from the server, for the case the
     * launcher breaks in a way it cannot detect about itself.
     *
     * Backing out is not a fault either way: the SDK reports [Status.AUTHENTICATION_CANCELED], and
     * the launcher path returns having never signed in — both become
     * [UpdateCheckError.SignInCancelled].
     *
     * Throws [UpdateCheckFailure] on any failure, so the caller can render the reason.
     */
    open suspend fun signInTester() {
        // Short-circuits before the launcher is even asked, so the kill switch works even if the
        // launcher is the thing that has gone wrong.
        if (flags.forceLegacySignIn() || !signInLauncher.start()) {
            appDistribution.signInTester().await()
            return
        }
        signInLauncher.awaitReturn()
        if (!isTesterSignedIn()) {
            throw UpdateCheckFailure(UpdateCheckError.SignInCancelled, "Sign-in not completed", null)
        }
    }

    /**
     * The newest release this tester is entitled to, or null when the installed build is already
     * it. "Nothing new" reaches us two ways — a successful Task carrying a null release, and a
     * failure with [Status.UPDATE_NOT_AVAILABLE] — and both mean the same thing here.
     *
     * Throws [UpdateCheckFailure] for everything else, including the API-key fail-closed case.
     */
    open suspend fun latestRelease(): UpdateInfo? = try {
        appDistribution.checkForNewRelease().await()?.toUpdateInfo()
    } catch (e: UpdateCheckFailure) {
        if (e.reason == null) null else throw e
    }

    /**
     * Downloads and installs the new build, reporting each stage to [onProgress].
     *
     * [FirebaseAppDistribution.updateApp] returns an `UpdateTask` that only completes when the
     * **whole** download and install has finished, so awaiting this awaits the install — it is not
     * fire-and-forget.
     *
     * The SDK posts its own download notification (which is why it merges `POST_NOTIFICATIONS`
     * into the manifest). [onProgress] is not a substitute for it but a second surface: a
     * notification is easy to miss from inside the app, and it says nothing about the install
     * phase after the bytes have landed.
     *
     * Terminal statuses are not reported here. Every one of them also completes the task — as a
     * success or a failure — and reporting both would race two descriptions of the same ending.
     */
    open suspend fun install(onProgress: (InstallState.Running) -> Unit) {
        appDistribution.updateApp().await(onProgress)
    }

    private fun AppDistributionRelease.toUpdateInfo() = UpdateInfo(
        // getVersionCode() is a Long in the SDK; UpdateInfo holds the Int that
        // BuildConfig.VERSION_CODE is, and this is the one place that narrowing belongs.
        versionCode = versionCode.toInt(),
        versionName = displayVersion,
        releaseNotes = releaseNotes.orEmpty().trim(),
        // Installs in-app rather than opening anything — see UpdateInfo.downloadUrl.
        downloadUrl = "",
        source = UpdateSource.APP_DISTRIBUTION,
    )
}

/**
 * A failed SDK call, carrying the reason already mapped for the UI. [reason] is null only for
 * [Status.UPDATE_NOT_AVAILABLE], which is not an error at all — [AppDistributionSource.latestRelease]
 * turns that one back into a plain `null` result.
 */
class UpdateCheckFailure(
    val reason: UpdateCheckError?,
    message: String,
    cause: Throwable?,
) : Exception(message, cause)

/**
 * Bridges a Play-services [Task] to a suspend function by hand.
 *
 * `kotlinx-coroutines-play-services` would do this, but it is not on the classpath and one `await`
 * does not justify adding a dependency for it. Cancellation of the calling coroutine stops us
 * waiting; the SDK offers no way to cancel the underlying work, so it is not pretended.
 *
 * `internal` rather than `private` only so the class above reaches it without a synthetic accessor.
 */
internal suspend fun <T> Task<T>.await(): T? = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it.toUpdateCheckFailure()) }
}

/**
 * The same bridge for an [UpdateTask], which reports progress on the way to completing.
 *
 * Separate from the generic [await] rather than an overload of it: only `UpdateTask` has
 * `addOnProgressListener`, and `UpdateTask` is a `Task<Void>` whose success value is always null,
 * so there is nothing to hand back but the fact that it finished.
 *
 * The listener runs on the SDK's callback thread. It only assigns to a `MutableStateFlow` upstream,
 * which is safe from any thread — do not grow it into anything that is not.
 */
internal suspend fun UpdateTask.await(
    onProgress: (InstallState.Running) -> Unit,
): Unit = suspendCancellableCoroutine { continuation ->
    addOnProgressListener { progress -> progress.toRunning()?.let(onProgress) }
    addOnSuccessListener { continuation.resume(Unit) }
    addOnFailureListener { continuation.resumeWithException(it.toUpdateCheckFailure()) }
}

/**
 * One SDK progress report as the UI's own model, or null when it describes an *ending* rather than
 * a stage — those reach the caller as the task completing, and would otherwise be told twice.
 *
 * Exhaustive with **no `else`**, for the same reason [Status.toUpdateCheckError] is: an SDK bump
 * that adds a constant should fail this build rather than quietly render a new state as "preparing".
 */
private fun UpdateProgress.toRunning(): InstallState.Running? = when (updateStatus) {
    UpdateStatus.PENDING -> InstallState.Running(InstallStage.PREPARING)
    UpdateStatus.DOWNLOADING -> InstallState.Running(
        stage = InstallStage.DOWNLOADING,
        bytesDownloaded = apkBytesDownloaded,
        totalBytes = apkFileTotalBytes,
    )
    // The bytes have landed and the system installer has the file. REDIRECTED_TO_PLAY is the same
    // moment for a build that came from Play instead — in both, the install is out of our hands.
    UpdateStatus.DOWNLOADED,
    UpdateStatus.REDIRECTED_TO_PLAY,
    -> InstallState.Running(InstallStage.INSTALLING)
    // Every ending: the task itself reports these, as a failure or as a success.
    UpdateStatus.DOWNLOAD_FAILED,
    UpdateStatus.INSTALL_FAILED,
    UpdateStatus.INSTALL_CANCELED,
    UpdateStatus.UPDATE_CANCELED,
    UpdateStatus.NEW_RELEASE_NOT_AVAILABLE,
    UpdateStatus.NEW_RELEASE_CHECK_FAILED,
    -> null
}

/** `internal` only so [UpdateCheckErrorTest] can cover the not-a-Firebase-failure branch. */
internal fun Throwable.toUpdateCheckFailure() = UpdateCheckFailure(
    reason = (this as? FirebaseAppDistributionException)?.errorCode?.toUpdateCheckError()
        // Not a Firebase failure at all — an IOException from the transport, say.
        ?: UpdateCheckError.Unknown,
    message = message.orEmpty(),
    cause = this,
)

/**
 * Every [Status] the SDK can report, mapped to something a user can act on. Exhaustive with **no
 * `else`**, so an SDK bump that adds a constant fails this build instead of quietly degrading a new
 * failure mode to "unknown" — which is exactly how [Status.API_DISABLED] would have been missed.
 *
 * [Status.UPDATE_NOT_AVAILABLE] maps to null: it is the SDK's way of saying "you are current",
 * which is a result rather than an error.
 */
internal fun Status.toUpdateCheckError(): UpdateCheckError? = when (this) {
    Status.UPDATE_NOT_AVAILABLE -> null
    Status.API_DISABLED -> UpdateCheckError.ApiDisabled
    Status.NOT_IMPLEMENTED -> UpdateCheckError.NotSupported
    Status.AUTHENTICATION_FAILURE -> UpdateCheckError.NotATester
    Status.AUTHENTICATION_CANCELED -> UpdateCheckError.SignInCancelled
    Status.NETWORK_FAILURE -> UpdateCheckError.Network
    Status.DOWNLOAD_FAILURE -> UpdateCheckError.DownloadFailed
    Status.INSTALLATION_FAILURE -> UpdateCheckError.InstallFailed
    Status.INSTALLATION_CANCELED -> UpdateCheckError.InstallCancelled
    Status.HOST_ACTIVITY_INTERRUPTED -> UpdateCheckError.Interrupted
    Status.UNKNOWN -> UpdateCheckError.Unknown
}
