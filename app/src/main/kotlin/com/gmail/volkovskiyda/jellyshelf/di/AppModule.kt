package com.gmail.volkovskiyda.jellyshelf.di

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.work.WorkManager
import coil.ImageLoader
import com.gmail.volkovskiyda.jellyshelf.BuildConfig
import com.gmail.volkovskiyda.jellyshelf.data.DefaultDispatcherProvider
import com.gmail.volkovskiyda.jellyshelf.data.DefaultTimeProvider
import com.gmail.volkovskiyda.jellyshelf.data.local.JellyshelfDatabase
import com.gmail.volkovskiyda.jellyshelf.data.remote.AppDistributionSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.DemoBackend
import com.gmail.volkovskiyda.jellyshelf.data.remote.GitHubReleaseSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.IndexSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.JellyfinClient
import com.gmail.volkovskiyda.jellyshelf.data.remote.RemoteUpdateFlags
import com.gmail.volkovskiyda.jellyshelf.data.remote.TesterSignIn
import com.gmail.volkovskiyda.jellyshelf.data.remote.TesterSignInLauncher
import com.gmail.volkovskiyda.jellyshelf.data.remote.UpdateFlags
import com.gmail.volkovskiyda.jellyshelf.data.remote.YtDlpMetadataSource
import com.gmail.volkovskiyda.jellyshelf.data.repository.DefaultJellyfinRepository
import com.gmail.volkovskiyda.jellyshelf.data.repository.DefaultLibraryRepository
import com.gmail.volkovskiyda.jellyshelf.data.repository.DefaultScrollPositionRepository
import com.gmail.volkovskiyda.jellyshelf.data.repository.DefaultSettingsRepository
import com.gmail.volkovskiyda.jellyshelf.data.repository.JellyfinDataSource
import com.gmail.volkovskiyda.jellyshelf.data.repository.LibrarySources
import com.gmail.volkovskiyda.jellyshelf.data.repository.ThemeModeCache
import com.gmail.volkovskiyda.jellyshelf.data.worker.SyncScheduler
import com.gmail.volkovskiyda.jellyshelf.data.worker.SyncWorker
import com.gmail.volkovskiyda.jellyshelf.domain.AppSettingsState
import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.domain.DeviceInfo
import com.gmail.volkovskiyda.jellyshelf.domain.DispatcherProvider
import com.gmail.volkovskiyda.jellyshelf.domain.TimeProvider
import com.gmail.volkovskiyda.jellyshelf.domain.UpdateChecker
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
import com.gmail.volkovskiyda.jellyshelf.ui.player.PlayerViewModel
import com.gmail.volkovskiyda.jellyshelf.ui.settings.SettingsCache
import com.gmail.volkovskiyda.jellyshelf.ui.settings.SettingsViewModel
import com.gmail.volkovskiyda.jellyshelf.util.ActivityTracker
import com.gmail.volkovskiyda.jellyshelf.util.stripCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * The whole app's Koin graph. Consumer definitions use the constructor-reference DSL (`singleOf`,
 * `viewModelOf`, `workerOf`) so [com.gmail.volkovskiyda.jellyshelf.di.AppModuleVerifyTest] can
 * introspect and verify every wiring; only the third-party leaf providers below (which need
 * builders or Android context) are plain lambdas. Repository implementations are bound to their
 * domain interfaces, so nothing above the data layer sees a `Default*` type.
 */
val appModule = module {
    single {
        BuildInfo(
            isDebug = BuildConfig.DEBUG,
            sdkInt = Build.VERSION.SDK_INT,
            versionCode = BuildConfig.VERSION_CODE,
        )
    }
    single {
        DeviceInfo(
            clientName = CLIENT_NAME,
            deviceName = Build.MODEL,
            version = BuildConfig.VERSION_NAME,
        )
    }
    single<DispatcherProvider> { DefaultDispatcherProvider() }
    single { provideJson() }
    single { provideHttpClient(get(), get()) }
    single { provideImageLoader(androidContext(), get()) }
    single { provideDatabase(androidContext()) }
    // Resolvable only after startKoin's workManagerFactory() has initialized WorkManager — Koin
    // singles are lazy, so the first injection happens well after that.
    single { WorkManager.getInstance(androidContext()) }

    singleOf(::DefaultSettingsRepository) { bind<SettingsRepository>() }
    singleOf(::ThemeModeCache)
    singleOf(::DefaultScrollPositionRepository) { bind<ScrollPositionRepository>() }
    singleOf(::AppSettingsState)
    single<TimeProvider> { DefaultTimeProvider() }
    singleOf(::UpdateChecker)
    singleOf(::JellyfinClient)
    singleOf(::JellyfinDataSource)
    singleOf(::IndexSource)
    singleOf(::GitHubReleaseSource)
    single { ActivityTracker() }
    single<TesterSignIn> { TesterSignInLauncher(androidContext(), get()) }
    singleOf(::RemoteUpdateFlags) { bind<UpdateFlags>() }
    singleOf(::AppDistributionSource)
    singleOf(::DefaultJellyfinRepository) { bind<JellyfinRepository>() }
    singleOf(::YtDlpMetadataSource)
    singleOf(::DemoBackend)
    singleOf(::LibrarySources)
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
    viewModelOf(::PlayerViewModel)
    viewModelOf(::SettingsViewModel)

    workerOf(::SyncWorker)
}

/** How the app names itself to Jellyfin — the "Client" column in its dashboard and session list. */
private const val CLIENT_NAME = "Jellyshelf"

// Shared lenient Json for both ContentNegotiation and the manual index decode (IndexSource).
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

/**
 * The network budget shared by the API client and image loading — generous, for a slow LAN over
 * which a Jellyfin server can take its time. One value, so the two halves of the app's traffic
 * can't drift into behaving differently on the same connection.
 */
private const val NETWORK_TIMEOUT_SECONDS = 30L
private const val NETWORK_TIMEOUT_MILLIS = NETWORK_TIMEOUT_SECONDS * 1000

// The base Ktor client on the OkHttp engine. expectSuccess makes non-2xx throw
// Client/ServerResponseException (see LibraryRepository.isPermanentFailure). Request URLs are logged
// only in debug — routed through Timber so release strips them via the -assumenosideeffects rules —
// and LogLevel.INFO logs method/URL/status without bodies, matching the old OkHttp BASIC interceptor.
private fun provideHttpClient(buildInfo: BuildInfo, json: Json): HttpClient = HttpClient(OkHttp) {
    expectSuccess = true
    install(ContentNegotiation) { json(json) }
    install(HttpTimeout) {
        connectTimeoutMillis = NETWORK_TIMEOUT_MILLIS
        socketTimeoutMillis = NETWORK_TIMEOUT_MILLIS
        requestTimeoutMillis = NETWORK_TIMEOUT_MILLIS
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

// Coil's ImageLoader, installed app-wide by JellyshelfApplication so every AsyncImage picks it up
// without per-call plumbing. Its client is tuned like the API client above — the same 30 s budget,
// and debug logging, so image traffic stops being the one half of the app's network that never
// appears in logcat.
//
// Deliberately its own OkHttpClient rather than the Ktor engine's: OkHttp allows 5 concurrent
// requests per host, and a screen full of thumbnails would queue ahead of the very API calls that
// populate it.
private fun provideImageLoader(context: Context, buildInfo: BuildInfo): ImageLoader =
    ImageLoader.Builder(context)
        .okHttpClient {
            OkHttpClient.Builder()
                .connectTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .apply { if (buildInfo.isDebug) addInterceptor(ImageLogInterceptor()) }
                .build()
        }
        .build()

/**
 * Logs image requests through Timber, like the Ktor client, **with the api key stripped**.
 * Thumbnail URLs carry the server credential as a query parameter (see `authorizedImageUrl`), and
 * logcat is readable by other apps on a dev device — so the one thing a BASIC-style logger would
 * print is the one thing that must not be printed.
 */
private class ImageLogInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        Timber.tag("Coil").d("${response.code} ${stripCredentials(request.url.toString())}")
        return response
    }
}

// No destructive fallback: manual categories and in-app yt-dlp metadata are user-authored and not
// reconstructible, so future schema bumps must ship explicit migrations.
private fun provideDatabase(context: Context): JellyshelfDatabase = Room.databaseBuilder(
    context,
    JellyshelfDatabase::class.java,
    "jellyshelf.db",
).build()
