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
) {
    /**
     * Whether the device runs at least API level [api]. The `@ChecksSdkIntAtLeast` annotation lets
     * lint recognize this as an SDK-version guard, so `NewApi` doesn't flag calls it protects (it
     * can't see through the injected [sdkInt] on its own).
     */
    @ChecksSdkIntAtLeast(parameter = 0)
    fun isAtLeast(api: Int): Boolean = sdkInt >= api
}
