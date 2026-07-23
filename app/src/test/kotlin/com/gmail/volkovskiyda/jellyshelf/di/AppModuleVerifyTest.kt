package com.gmail.volkovskiyda.jellyshelf.di

import android.app.Application
import android.content.Context
import androidx.work.WorkerParameters
import io.ktor.client.engine.HttpClientEngine
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
 * - [HttpClientEngine] — `verify` reflects the `single { provideHttpClient(...) }` lambda's provided
 *   type (Ktor's [io.ktor.client.HttpClient]) and sees its primary-constructor `engine` param. That
 *   engine is built inside `provideHttpClient` (`HttpClient(OkHttp) { … }`), not injected from the
 *   graph, so it is supplied from outside and whitelisted here.
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
                HttpClientEngine::class,
            ),
        )
    }
}
