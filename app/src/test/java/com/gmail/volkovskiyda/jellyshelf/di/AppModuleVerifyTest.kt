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
 * [extraTypes] are the dependencies provided from outside the module at runtime: Android's
 * [Context]/[Application] (registered by `androidContext()` in `startKoin`), [WorkerParameters]
 * (supplied by the WorkManager factory), and [String] (the `youtubeId` / `categoryId` runtime
 * parameters passed via `parametersOf` to the detail and category-videos ViewModels).
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
            ),
        )
    }
}
