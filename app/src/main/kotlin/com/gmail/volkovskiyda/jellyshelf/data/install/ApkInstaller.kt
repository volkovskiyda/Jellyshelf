package com.gmail.volkovskiyda.jellyshelf.data.install

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Build
import com.gmail.volkovskiyda.jellyshelf.domain.ApkInstall
import com.gmail.volkovskiyda.jellyshelf.domain.ApkInstallFailure
import com.gmail.volkovskiyda.jellyshelf.domain.DispatcherProvider
import com.gmail.volkovskiyda.jellyshelf.domain.model.InstallStage
import com.gmail.volkovskiyda.jellyshelf.domain.model.InstallState
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateCheckError
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateInfo
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

/**
 * Downloads a GitHub release's APK, checks it is the one the release published, and hands it to the
 * system package installer.
 *
 * The verification is the reason this exists at all. Bouncing to a browser was never a *worse*
 * install — the browser downloads the same bytes and the same system installer runs — it was
 * simply someone else's problem. Doing it in-app means owning it, and owning it means refusing to
 * install anything whose SHA-256 does not match what the channel reported. [download] takes the
 * expected digest as a required parameter and [apkMatchesDigest] treats a missing expectation as a
 * mismatch, so there is no arrangement of these calls that quietly installs unverified bytes.
 *
 * The hash is computed while the bytes stream past, not by re-reading the file afterwards: one
 * pass, and no window in which the file could differ between being hashed and being installed.
 */
class ApkInstaller(
    private val context: Context,
    private val httpClient: HttpClient,
    private val dispatchers: DispatcherProvider,
) : ApkInstall {

    /**
     * The whole flow. Reports progress through [onProgress]; throws [ApkInstallFailure] with a
     * renderable reason for anything that stops it.
     *
     * Returns once the install has been *committed*, not once it has finished: the system takes
     * over from there, shows its own confirmation, and — on success — replaces this process.
     */
    override suspend fun downloadAndInstall(
        info: UpdateInfo,
        onProgress: (InstallState.Running) -> Unit,
    ) {
        onProgress(InstallState.Running(InstallStage.PREPARING))
        val apk = downloadVerified(info, info.sha256, onProgress)
        onProgress(InstallState.Running(InstallStage.INSTALLING))
        try {
            commit(apk)
        } finally {
            // The session has its own copy; this one has done its job either way.
            apk.delete()
        }
    }

    /**
     * Streams the APK to the cache, hashing as it goes, and deletes it unless it matches.
     *
     * [expectedSha256] is a parameter rather than read from [info] inside, so the verification
     * cannot be lost by a future caller passing an info that happens to have none — the callers
     * that have no digest are supposed to take the browser path instead.
     *
     * `internal` rather than private so the one rule worth testing against a real stream can be:
     * `ApkInstallerInstrumentedTest` drives it through a Ktor `MockEngine`. Committing a session is
     * not testable that way — it installs — so the split is where the testable part ends.
     */
    internal suspend fun downloadVerified(
        info: UpdateInfo,
        expectedSha256: String?,
        onProgress: (InstallState.Running) -> Unit,
    ): File = withContext(dispatchers.io) {
        val target = File(updatesDir(), "jellyshelf-${info.versionCode}.apk")
        target.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            httpClient.prepareGet(info.downloadUrl) {
                // The shared client's 30 s request timeout is sized for API calls; an APK is tens
                // of megabytes over whatever connection the user has. Only the whole-request bound
                // is lifted — the connect and socket timeouts still catch a dead connection.
                timeout { requestTimeoutMillis = DOWNLOAD_TIMEOUT_MILLIS }
            }.execute { response ->
                val total = response.contentLength() ?: 0L
                var received = 0L
                val channel = response.bodyAsChannel()
                target.outputStream().use { out ->
                    while (!channel.isClosedForRead) {
                        val packet = channel.readRemaining(DOWNLOAD_CHUNK_BYTES)
                        while (!packet.exhausted()) {
                            val bytes = packet.readByteArray()
                            out.write(bytes)
                            digest.update(bytes)
                            received += bytes.size
                            onProgress(
                                InstallState.Running(InstallStage.DOWNLOADING, received, total),
                            )
                        }
                    }
                }
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            target.delete()
            Timber.w(e, "Update download failed")
            throw ApkInstallFailure(UpdateCheckError.DownloadFailed)
        }
        if (!apkMatchesDigest(expectedSha256, digest.digest().toHexString())) {
            // Deleted before anything can be done with it: an APK that failed verification should
            // not survive the failure, on disk or anywhere else.
            target.delete()
            Timber.w("Update rejected: SHA-256 did not match the published digest")
            throw ApkInstallFailure(UpdateCheckError.VerificationFailed)
        }
        target
    }

    /** Streams the verified file into a PackageInstaller session and commits it. */
    private suspend fun commit(apk: File) = withContext(dispatchers.io) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = try {
            installer.createSession(params)
        } catch (e: java.io.IOException) {
            Timber.w(e, "Could not open an install session")
            throw ApkInstallFailure(UpdateCheckError.InstallFailed)
        }
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite(APK_ENTRY, 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                session.commit(resultSender(sessionId))
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            installer.abandonSession(sessionId)
            Timber.w(e, "Install session failed")
            throw ApkInstallFailure(UpdateCheckError.InstallFailed)
        }
    }

    /**
     * Where the system reports the session's outcome. Broadcast to this app's own receiver, which
     * is what turns `STATUS_PENDING_USER_ACTION` — "ask the user to confirm" — into a visible
     * prompt; without it the commit succeeds and then nothing at all happens.
     */
    private fun resultSender(sessionId: Int): IntentSender {
        val intent = Intent(context, InstallResultReceiver::class.java)
            .setAction(InstallResultReceiver.ACTION_INSTALL_RESULT)
        val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Mutable because the system fills in the status extras it reports back.
                android.app.PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
        return android.app.PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender
    }

    /** Everything downloaded here is disposable by definition — the cache is exactly right. */
    private fun updatesDir(): File = File(context.cacheDir, UPDATES_DIR).apply { mkdirs() }

    companion object {
        private const val UPDATES_DIR = "updates"
        private const val APK_ENTRY = "jellyshelf"
        private const val DOWNLOAD_CHUNK_BYTES = 64L * 1024
        private const val DOWNLOAD_TIMEOUT_MILLIS = 10L * 60 * 1000

        /**
         * Removes anything a previous run left behind — a download interrupted by the process
         * dying leaves a part-file that nothing else will ever clean up. Called at app start.
         */
        fun sweep(context: Context) {
            runCatching { File(context.cacheDir, UPDATES_DIR).deleteRecursively() }
        }
    }
}
