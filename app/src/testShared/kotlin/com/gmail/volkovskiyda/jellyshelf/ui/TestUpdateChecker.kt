package com.gmail.volkovskiyda.jellyshelf.ui

import com.gmail.volkovskiyda.jellyshelf.data.remote.AppDistributionSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.GitHubReleaseSource
import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.domain.InstallOutcome
import com.gmail.volkovskiyda.jellyshelf.domain.TimeProvider
import com.gmail.volkovskiyda.jellyshelf.domain.UpdateChecker
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateInfo
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.serialization.json.Json

/**
 * An [UpdateChecker] that can never check anything, for tests about something else entirely.
 *
 * Inert three times over, so no test that merely needs to construct a `SettingsViewModel` can
 * accidentally acquire a network call: the build reports as debug (the first gate), the sources
 * are overridden to answer null, and the clock is frozen. `UpdateCheckerTest` is where the real
 * behaviour is exercised.
 *
 * `isDebug = true` also means the Updates section is hidden, which is what the screens these are
 * handed to already expect to see.
 */
fun inertUpdateChecker(settings: SettingsRepository = FakeSettingsRepository()) = UpdateChecker(
    settingsRepository = settings,
    // The engine is named rather than left to the ServiceLoader so this resolves identically in
    // the JVM and instrumented source sets. It is never asked for anything.
    gitHubSource = object : GitHubReleaseSource(HttpClient(OkHttp), TestDispatcherProvider(), Json) {
        override suspend fun latestRelease(): UpdateInfo? = null
    },
    appDistributionSource = object : AppDistributionSource(InertTesterSignIn, FakeUpdateFlags()) {
        override fun isTesterSignedIn() = false
        override suspend fun latestRelease(): UpdateInfo? = null
    },
    buildInfo = BuildInfo(isDebug = true, sdkInt = 36),
    time = TimeProvider { 0L },
    dispatchers = TestDispatcherProvider(),
    updateCheckSchedule = RecordingUpdateCheckSchedule(),
    apkInstaller = InertApkInstall,
    installOutcome = InstallOutcome(),
)
