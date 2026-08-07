package com.gmail.volkovskiyda.jellyshelf.data.remote

import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * The Remote Config parameter name. Must match the console exactly; see docs/RELEASING.md, which is
 * where an operator looks when they need to pull this lever.
 */
internal const val FORCE_LEGACY_SIGN_IN = "force_legacy_tester_sign_in"

/** Twice a day. This is a kill switch, not a feature flag — it does not need to be fresh. */
private val FETCH_INTERVAL_SECONDS = TimeUnit.HOURS.toSeconds(12)

/**
 * Server-side switches for the update feature.
 *
 * One at present, and it exists because [TesterSignInLauncher] rests on two undocumented internals
 * of a beta SDK. Those can break in a Google-side change with no app release involved, and the
 * failure mode is not obvious from the outside — sign-in would simply read as cancelled. This is
 * the handle that puts every install back on the SDK's own flow without shipping anything.
 */
interface UpdateFlags {

    /**
     * Whether to use the SDK's own `signInTester()` and skip [TesterSignInLauncher] entirely.
     *
     * Read at the moment of sign-in, from whatever was last activated — Remote Config persists
     * activated values, so a flag set today takes effect on the **next launch** of an app that has
     * fetched it, not mid-session. That is the right latency for a kill switch and the wrong one
     * for anything that needs to act immediately, so do not reuse this shape for the latter.
     */
    fun forceLegacySignIn(): Boolean

    /** Fetches and activates in the background. Fire-and-forget; nothing waits for it. */
    fun refresh()
}

/**
 * [UpdateFlags] over Firebase Remote Config.
 *
 * Fetching is **release-only**, matching Crashlytics and Performance: a debug build makes no
 * network call for this, which also keeps instrumented tests and Test Lab runs off it. Reads on a
 * build that never fetched return the local default — `false`, the shipped behaviour — so a debug
 * build behaves as if the switch did not exist.
 *
 * The parameter costs nothing until it is created: an absent parameter is not an error, it is just
 * the default. The console template can stay empty until the day it is needed.
 */
class RemoteUpdateFlags(private val buildInfo: BuildInfo) : UpdateFlags {

    private val config: FirebaseRemoteConfig
        get() = FirebaseRemoteConfig.getInstance()

    override fun forceLegacySignIn(): Boolean = config.getBoolean(FORCE_LEGACY_SIGN_IN)

    override fun refresh() {
        if (buildInfo.isDebug) return
        val settings = FirebaseRemoteConfigSettings.Builder()
            .setMinimumFetchIntervalInSeconds(FETCH_INTERVAL_SECONDS)
            .build()
        config.setConfigSettingsAsync(settings)
        // Local defaults, so a first launch that has never reached the network still answers.
        config.setDefaultsAsync(mapOf(FORCE_LEGACY_SIGN_IN to false))
        config.fetchAndActivate().addOnFailureListener {
            // Best-effort by design: failing to reach Remote Config must never affect the app. The
            // last activated value stands, and on a first launch that is the default above.
            Timber.w(it, "Could not refresh remote update flags")
        }
    }
}
