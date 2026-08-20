package com.gmail.volkovskiyda.jellyshelf.domain

import com.gmail.volkovskiyda.jellyshelf.domain.model.InstallState
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateCheckError
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateInfo

/** Thrown to end an install with a reason the UI already knows how to render. */
class ApkInstallFailure(val reason: UpdateCheckError) : Exception(reason.toString())

/**
 * Downloading a release's APK, checking it is the one the release published, and handing it to the
 * system package installer — as much of it as [UpdateChecker] needs to know.
 *
 * An interface in the domain for the same reason as [UpdateCheckSchedule]: which channel installs
 * how is update policy, while streams, digests and `PackageInstaller` sessions are two layers
 * down in [ApkInstaller][com.gmail.volkovskiyda.jellyshelf.data.install.ApkInstaller].
 */
interface ApkInstall {

    /**
     * Downloads, verifies and commits. Returns once the install has been handed to the system —
     * which then reports the outcome asynchronously through [InstallOutcome].
     *
     * @throws ApkInstallFailure with a renderable reason for anything that stops it, including a
     *   digest that does not match, which is the one failure that must never be silent.
     */
    suspend fun downloadAndInstall(info: UpdateInfo, onProgress: (InstallState.Running) -> Unit)
}

/**
 * How the system's install result gets back into the app.
 *
 * A `PackageInstaller` session reports through a broadcast, and a `BroadcastReceiver` is built by
 * the framework with no way to hold the checker's state — so the outcome travels through this,
 * which the checker writes its `installState` from. That is what puts a cancelled or failed
 * install on the same snackbar the download was already narrating into.
 */
class InstallOutcome {
    private var listener: ((UpdateCheckError?) -> Unit)? = null

    fun observe(listener: (UpdateCheckError?) -> Unit) {
        this.listener = listener
    }

    fun finished(reason: UpdateCheckError?) {
        listener?.invoke(reason)
    }

    /** Nothing is in flight any more; a late broadcast must not resurrect a snackbar. */
    fun clear() {
        listener = null
    }
}
