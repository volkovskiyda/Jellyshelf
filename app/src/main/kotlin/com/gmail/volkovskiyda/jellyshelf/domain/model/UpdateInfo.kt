package com.gmail.volkovskiyda.jellyshelf.domain.model

/**
 * A newer build than the one running, as reported by whichever channel found it. Both sources
 * produce this shape, so the dialog and the dismissal bookkeeping never learn which channel it came
 * from beyond [source] — which they need only to pick the "Update" action and the dismissal key.
 *
 * [versionCode] is an `Int` to match `BuildConfig.VERSION_CODE`, which is what it is compared
 * against. The App Distribution SDK hands back a `Long`, so that narrowing happens in its source
 * rather than here — one conversion, at the boundary that owns it.
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val releaseNotes: String,
    /**
     * Where "Update" sends the user. Empty for [UpdateSource.APP_DISTRIBUTION], which downloads and
     * installs in-app through the SDK rather than opening anything.
     */
    val downloadUrl: String,
    /**
     * The published APK's SHA-256, lower-case hex, or null when the channel does not say.
     *
     * It is what makes an in-app install of a GitHub release defensible: the app downloads bytes
     * from the internet and hands them to the package installer, and this is the only thing that
     * says they are the bytes the release actually published. A null therefore does not mean
     * "install unverified" — it means fall back to the browser, which is the whole point of the
     * field being nullable rather than a defaulted empty string.
     *
     * Always null for [UpdateSource.APP_DISTRIBUTION], whose SDK owns its own download and
     * integrity end to end.
     */
    val sha256: String? = null,
    val source: UpdateSource,
)
