package com.gmail.volkovskiyda.jellyshelf.di

import android.app.Application
import android.content.Context
import androidx.work.WorkerParameters
import org.junit.Test
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Static verification of the Koin graph: [org.koin.test.verify.verify] reflects over every
 * constructor-reference definition ([org.koin.core.module.dsl.singleOf], `viewModelOf`,
 * `workerOf`) in [appModule] and fails the build if any dependency isn't provided by the module
 * or listed below. It runs as a plain JVM unit test (no instantiation, no device), so `./gradlew
 * test` — and CI — catch a missing binding the way the manual [com.gmail.volkovskiyda.jellyshelf.di.AppModule]
 * DI container never could.
 *
 * [extraTypes] are the values supplied from outside the graph at construction, which `verify`'s
 * reflection would otherwise flag as missing:
 * - [Context]/[Application] — registered by `androidContext()` in `startKoin`.
 * - [WorkerParameters] — supplied by the WorkManager factory to `SyncWorker`.
 * - [String] — the `youtubeId` / `categoryId` runtime params passed via `parametersOf` to the
 *   detail and category-videos ViewModels.
 * - [Boolean]/[Int] — the `BuildInfo(isDebug, sdkInt)` constructor constants, wired in the module
 *   from `BuildConfig.DEBUG` / `Build.VERSION.SDK_INT` (no other definition injects a bare
 *   Boolean/Int, so whitelisting them here can't mask a real missing binding).
 */
class AppModuleVerifyTest {

    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun appModuleGraphIsComplete() {
        appModule.verify(
            extraTypes = listOf(
                Context::class,
                Application::class,
                WorkerParameters::class,
                String::class,
                Boolean::class,
                Int::class,
            ),
        )
    }
}
