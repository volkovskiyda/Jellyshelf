package com.gmail.volkovskiyda.jellyshelf.baselineprofile

import androidx.test.platform.app.InstrumentationRegistry

/**
 * What the run was told about the world outside the device: which app to drive, and which Jellyfin
 * to drive it against.
 *
 * All of it arrives as instrumentation runner arguments rather than as anything compiled in, so
 * editing `.test.env` needs no rebuild — see `baselineprofile/build.gradle.kts`, which fills them
 * from that file and from the built APK's own metadata. Read once here so
 * [BaselineProfileGenerator] and [JourneyBenchmark] cannot drift into disagreeing about it.
 */
internal object JourneyConfig {

    private val args = InstrumentationRegistry.getArguments()

    /**
     * Live-server config, from the values Gradle's `loadEnv(".test.env")` feeds in. Blank when the
     * file is absent, which is what selects the demo fallback.
     */
    val serverUrl: String = args.getString("jellyfinServerUrl").orEmpty()
    val username: String = args.getString("jellyfinUsername").orEmpty()
    val password: String = args.getString("jellyfinPassword").orEmpty()
    val indexUrl: String = args.getString("jellyfinIndexUrl").orEmpty()
    val syncFolder: String = args.getString("jellyfinSyncFolder").orEmpty()

    /**
     * The application id of the app being driven, read off the tested APK's own metadata by
     * `:baselineprofile`'s build script rather than written down here.
     *
     * It is `com.gmail.volkovskiyda.jellyshelf.benchmark`: the profiling variants carry a
     * `.benchmark` suffix (see `:app`'s `finalizeDsl`) so a run installs beside the release build on
     * the device instead of replacing it and taking it away again on teardown. Hardcoding the id
     * would silently drive the wrong install the next time either half moved.
     */
    val targetPackage: String = requireNotNull(args.getString("targetAppId")) {
        "targetAppId runner argument missing — it is set in baselineprofile/build.gradle.kts, so " +
            "this run is not one Gradle configures. Use ./gradlew :app:generateReleaseBaselineProfile " +
            "or ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest."
    }

    /** Whether to drive a real server. Release builds refuse plain http, so the URL must be https. */
    val liveServer: Boolean
        get() = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
}
