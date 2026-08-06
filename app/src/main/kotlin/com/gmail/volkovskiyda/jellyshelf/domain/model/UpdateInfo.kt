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
    val source: UpdateSource,
)
