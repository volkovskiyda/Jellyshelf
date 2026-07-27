package com.gmail.volkovskiyda.jellyshelf.di

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.work.WorkManager
import com.gmail.volkovskiyda.jellyshelf.BuildConfig
import com.gmail.volkovskiyda.jellyshelf.data.DefaultDispatcherProvider
import com.gmail.volkovskiyda.jellyshelf.data.local.JellyshelfDatabase
import com.gmail.volkovskiyda.jellyshelf.data.remote.JellyfinClient
import com.gmail.volkovskiyda.jellyshelf.data.remote.YtDlpMetadataSource
import com.gmail.volkovskiyda.jellyshelf.domain.AppSettingsState
import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.domain.DispatcherProvider
import com.gmail.volkovskiyda.jellyshelf.data.repository.DefaultJellyfinRepository
import com.gmail.volkovskiyda.jellyshelf.data.repository.DefaultLibraryRepository
import com.gmail.volkovskiyda.jellyshelf.data.repository.DefaultScrollPositionRepository
import com.gmail.volkovskiyda.jellyshelf.data.repository.DefaultSettingsRepository
import com.gmail.volkovskiyda.jellyshelf.data.repository.JellyfinDataSource
import com.gmail.volkovskiyda.jellyshelf.data.worker.SyncScheduler
import com.gmail.volkovskiyda.jellyshelf.data.worker.SyncWorker
import com.gmail.volkovskiyda.jellyshelf.domain.repository.JellyfinRepository
import com.gmail.volkovskiyda.jellyshelf.domain.repository.LibraryRepository
import com.gmail.volkovskiyda.jellyshelf.domain.repository.ScrollPositionRepository
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository
import com.gmail.volkovskiyda.jellyshelf.ui.MainViewModel
import com.gmail.volkovskiyda.jellyshelf.ui.categories.CategoriesFilterState
import com.gmail.volkovskiyda.jellyshelf.ui.categories.CategoriesViewModel
import com.gmail.volkovskiyda.jellyshelf.ui.categories.CategoryVideosViewModel
import com.gmail.volkovskiyda.jellyshelf.ui.detail.DetailViewModel
import com.gmail.volkovskiyda.jellyshelf.ui.library.LibraryFilterState
import com.gmail.volkovskiyda.jellyshelf.ui.library.LibraryViewModel
import com.gmail.volkovskiyda.jellyshelf.ui.settings.SettingsCache
import com.gmail.volkovskiyda.jellyshelf.ui.settings.SettingsViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import timber.log.Timber

/**
 * The whole app's Koin graph. Consumer definitions use the constructor-reference DSL (`singleOf`,
 * `viewModelOf`, `workerOf`) so [com.gmail.volkovskiyda.jellyshelf.di.AppModuleVerifyTest] can
 * introspect and verify every wiring; only the third-party leaf providers below (which need
 * builders or Android context) are plain lambdas. Repository implementations are bound to their
 * domain interfaces, so nothing above the data layer sees a `Default*` type.
 */
val appModule = module {
    single { BuildInfo(isDebug = BuildConfig.DEBUG, sdkInt = Build.VERSION.SDK_INT) }
    single<DispatcherProvider> { DefaultDispatcherProvider() }
    single { provideJson() }
    single { provideHttpClient(get(), get()) }
    single { provideDatabase(androidContext()) }
    // Resolvable only after startKoin's workManagerFactory() has initialized WorkManager — Koin
    // singles are lazy, so the first injection happens well after that.
    single { WorkManager.getInstance(androidContext()) }

    singleOf(::DefaultSettingsRepository) { bind<SettingsRepository>() }
    singleOf(::DefaultScrollPositionRepository) { bind<ScrollPositionRepository>() }
    singleOf(::AppSettingsState)
    singleOf(::JellyfinClient)
    singleOf(::JellyfinDataSource)
    singleOf(::DefaultJellyfinRepository) { bind<JellyfinRepository>() }
    singleOf(::YtDlpMetadataSource)
    singleOf(::DefaultLibraryRepository) { bind<LibraryRepository>() }
    singleOf(::SyncScheduler)

    // Process-lifetime UI state that must survive tab switches (which clear tab ViewModels).
    singleOf(::LibraryFilterState)
    singleOf(::CategoriesFilterState)
    singleOf(::SettingsCache)

    viewModelOf(::MainViewModel)
    viewModelOf(::LibraryViewModel)
    viewModelOf(::CategoriesViewModel)
    viewModelOf(::CategoryVideosViewModel)
    viewModelOf(::DetailViewModel)
    viewModelOf(::SettingsViewModel)

    workerOf(::SyncWorker)
}

// Shared lenient Json for both ContentNegotiation and the manual index decode (JellyfinDataSource).
// The three flags together keep request bodies wire-identical to the old Moshi output:
//  - ignoreUnknownKeys: Jellyfin returns far more fields than we model; kotlinx throws otherwise.
//  - explicitNulls=false: omit null-valued properties (e.g. UserItemDataBody.lastPlayedDate), as Moshi did.
//  - encodeDefaults=true: keep non-null Kotlin defaults on the wire (ProgressBody.isPaused/playMethod,
//    CreatePlaylistBody.mediaType, UserItemDataBody.played); kotlinx omits defaults without it.
// internal (not private) so JellyfinApiTest exercises this exact config, not a copy.
internal fun provideJson(): Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

// The base Ktor client on the OkHttp engine. expectSuccess makes non-2xx throw
// Client/ServerResponseException (see LibraryRepository.isPermanentFailure). Request URLs are logged
// only in debug — routed through Timber so release strips them via the -assumenosideeffects rules —
// and LogLevel.INFO logs method/URL/status without bodies, matching the old OkHttp BASIC interceptor.
private fun provideHttpClient(buildInfo: BuildInfo, json: Json): HttpClient = HttpClient(OkHttp) {
    expectSuccess = true
    install(ContentNegotiation) { json(json) }
    install(HttpTimeout) {
        connectTimeoutMillis = 30_000
        socketTimeoutMillis = 30_000
        requestTimeoutMillis = 30_000
    }
    if (buildInfo.isDebug) {
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    Timber.tag("Ktor").d(message)
                }
            }
            level = LogLevel.INFO
        }
    }
}

// No destructive fallback: manual categories and in-app yt-dlp metadata are user-authored and not
// reconstructible, so future schema bumps must ship explicit migrations.
private fun provideDatabase(context: Context): JellyshelfDatabase = Room.databaseBuilder(
    context,
    JellyshelfDatabase::class.java,
    "jellyshelf.db",
).build()
