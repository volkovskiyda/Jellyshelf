package com.gmail.volkovskiyda.jellyshelf.domain.model

/**
 * How far the in-app install has got. App Distribution only — the GitHub channel hands the download
 * to a browser, which reports its own progress and is not ours to narrate.
 *
 * The SDK reports ten `UpdateStatus` values; these are the three that describe work still in
 * flight. Everything else is terminal and arrives as the task succeeding or failing, which is
 * [InstallState] rather than a stage.
 */
enum class InstallStage {
    /** Asked for, nothing downloading yet. There is no byte count to show at this point. */
    PREPARING,

    /** APK bytes are arriving; this is the only stage with a meaningful fraction. */
    DOWNLOADING,

    /** Downloaded, and the system package installer has been handed the file. */
    INSTALLING,
}

/**
 * The install as the UI needs to see it, or null when none is running.
 *
 * One flow rather than a progress flow beside an error flow: an install is running, or it failed,
 * and it cannot be both. The old code routed install failures into the checker's `error`, which is
 * rendered in Settings — a screen the user is by definition not on, since the install is started
 * from the update dialog. A failure that lands where nobody is looking is a failure nobody sees.
 */
sealed interface InstallState {

    /**
     * Work in flight. [totalBytes] is `0` until the download actually starts, so
     * [fraction] is null for exactly as long as an indeterminate indicator is the honest one.
     */
    data class Running(
        val stage: InstallStage,
        val bytesDownloaded: Long = 0,
        val totalBytes: Long = 0,
    ) : InstallState {
        /** How much of the APK has arrived, or null when that is not yet knowable. */
        val fraction: Float?
            get() = if (stage == InstallStage.DOWNLOADING && totalBytes > 0) {
                (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f)
            } else {
                null
            }
    }

    /** Terminal. Carries the same reason vocabulary every other update failure uses. */
    data class Failed(val reason: UpdateCheckError) : InstallState
}
