package com.gmail.volkovskiyda.jellyshelf.domain

import androidx.annotation.ChecksSdkIntAtLeast

/**
 * Build/runtime facts the app branches on, injected instead of reading `BuildConfig.DEBUG` or
 * `Build.VERSION.SDK_INT` directly — so a test can construct `BuildInfo(...)` and exercise both
 * debug/release and any API-level branch. The real values are wired in the Koin module from
 * `BuildConfig.DEBUG` and `Build.VERSION.SDK_INT`.
 *
 * [sdkInt] stays a plain Int (not an `android.os.Build` reference) so this type has no Android
 * runtime dependency; gate API-level code through [isAtLeast], passing `Build.VERSION_CODES.*`.
 */
data class BuildInfo(
    val isDebug: Boolean,
    val sdkInt: Int,
    /**
     * `BuildConfig.VERSION_CODE` — the git commit count CI passes as `-PbuildNumber`
     * (see `buildNumber` in `app/build.gradle.kts`), and the same integer space both distribution
     * channels publish into, so an update check can compare the two directly.
     *
     * Defaults to `1` for the same reason a locally assembled build reports `1`: neither workflow
     * ran, so there is no commit count. Tests that don't care about versions inherit it, and since
     * the update check treats `<= 1` as "not a published build" they inherit the inert behaviour
     * too. The real value is wired in the Koin module beside [isDebug].
     */
    val versionCode: Int = 1,
    /**
     * `BuildConfig.VERSION_NAME` — `"<latest tag>.<commits since it>"` for a CI build (e.g.
     * `"1.14.3"`, and `"1.14.0"` for the tagged release itself), the fallback `"1.0"` for a locally
     * assembled one, plus the `-debug` / `-benchmark` suffix its build type appends
     * (`versionNameSuffix` in `app/build.gradle.kts`).
     *
     * Display only: it is the string the Settings screen shows so a user can name the build they
     * are running when reporting something. Nothing branches on it — [versionCode] is what the
     * update check compares. Defaults to blank so tests that don't care inherit "no version to
     * show" rather than a made-up one; the real value is wired in the Koin module beside [isDebug].
     */
    val versionName: String = "",
) {
    /**
     * Whether the device runs at least API level [api]. The `@ChecksSdkIntAtLeast` annotation lets
     * lint recognize this as an SDK-version guard, so `NewApi` doesn't flag calls it protects (it
     * can't see through the injected [sdkInt] on its own).
     */
    @ChecksSdkIntAtLeast(parameter = 0)
    fun isAtLeast(api: Int): Boolean = sdkInt >= api
}
