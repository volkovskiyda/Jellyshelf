package com.gmail.volkovskiyda.jellyshelf.di

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.room.Room
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.serviceLoaderEnabled
import com.gmail.volkovskiyda.jellyshelf.BuildConfig
import com.gmail.volkovskiyda.jellyshelf.data.DefaultDispatcherProvider
import com.gmail.volkovskiyda.jellyshelf.data.DefaultTimeProvider
import com.gmail.volkovskiyda.jellyshelf.data.install.ApkInstaller
import com.gmail.volkovskiyda.jellyshelf.data.local.JellyshelfDatabase
import com.gmail.volkovskiyda.jellyshelf.data.remote.ApiSource
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
import com.gmail.volkovskiyda.jellyshelf.data.worker.UpdateCheckScheduler
import com.gmail.volkovskiyda.jellyshelf.data.worker.UpdateCheckWorker
import com.gmail.volkovskiyda.jellyshelf.domain.ApkInstall
import com.gmail.volkovskiyda.jellyshelf.domain.AppSettingsState
import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.domain.DeviceInfo
import com.gmail.volkovskiyda.jellyshelf.domain.DispatcherProvider
import com.gmail.volkovskiyda.jellyshelf.domain.InstallOutcome
import com.gmail.volkovskiyda.jellyshelf.domain.LocalNetworkPrompt
import com.gmail.volkovskiyda.jellyshelf.domain.NotificationPrompt
import com.gmail.volkovskiyda.jellyshelf.domain.TimeProvider
import com.gmail.volkovskiyda.jellyshelf.domain.UpdateCheckSchedule
import com.gmail.volkovskiyda.jellyshelf.domain.UpdateChecker
import com.gmail.volkovskiyda.jellyshelf.domain.deviceDisplayName
import com.gmail.volkovskiyda.jellyshelf.domain.repository.JellyfinRepository
import com.gmail.volkovskiyda.jellyshelf.domain.repository.LibraryRepository
import com.gmail.volkovskiyda.jellyshelf.domain.repository.ScrollPositionRepository
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository
import com.gmail.volkovskiyda.jellyshelf.playback.NowPlayingState
import com.gmail.volkovskiyda.jellyshelf.playback.ResumableCache
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
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.plugin
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
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
    single {
        BuildInfo(
            isDebug = BuildConfig.DEBUG,
            sdkInt = Build.VERSION.SDK_INT,
            versionCode = BuildConfig.VERSION_CODE,
            versionName = BuildConfig.VERSION_NAME,
        )
    }
    single {
        DeviceInfo(
            clientName = CLIENT_NAME,
            deviceName = deviceDisplayName(
                userDeviceName = Settings.Global.getString(
                    androidContext().contentResolver,
                    Settings.Global.DEVICE_NAME,
                ),
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
            ),
            version = BuildConfig.VERSION_NAME,
        )
    }
    single<DispatcherProvider> { DefaultDispatcherProvider() }
    single { provideJson() }
    single { provideHttpClient(get(), get()) }
    single { provideImageLoader(androidContext(), get()) }
    single(named(MEDIA_HTTP_CLIENT)) { provideMediaHttpClient() }
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
    singleOf(::NotificationPrompt)
    singleOf(::LocalNetworkPrompt)
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
    singleOf(::ApiSource)
    singleOf(::LibrarySources)
    singleOf(::DefaultLibraryRepository) { bind<LibraryRepository>() }
    singleOf(::SyncScheduler)
    singleOf(::UpdateCheckScheduler) { bind<UpdateCheckSchedule>() }
    // Written by the install result receiver, read by the checker — see InstallOutcome.
    single { InstallOutcome() }
    single<ApkInstall> { ApkInstaller(androidContext(), get(), get()) }
    // Written by PlaybackService, read by whatever screen is not the player.
    singleOf(::NowPlayingState)
    // Read from a broadcast receiver on a cold process — see MediaButtonGate.
    single { ResumableCache(androidContext()) }

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
    workerOf(::UpdateCheckWorker)
}

/**
 * How the app names itself to Jellyfin — the "Client" column in its dashboard and session list.
 *
 * The "Android" is load-bearing, not decoration. Jellyfin has no platform or device-type field a
 * client can populate (`SessionInfo.DeviceType` is never set), so stats tools classify a session by
 * substring-matching this one string: Tracearr's `normalizeClient` reports platform "Android" for
 * anything containing "android" and otherwise echoes the raw name back as the platform. Dropping
 * the word makes Jellyshelf show up as its own platform in everyone's dashboards.
 *
 * Keep "TV" and "Shield" out of it unless this really is a TV build — the same matcher reads either
 * as Android TV.
 */
private const val CLIENT_NAME = "Jellyshelf Android"

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
 * Qualifier for the streaming [HttpClient] — see `provideMediaHttpClient` for why video does not
 * share either of the other two. Named rather than a distinct type because it is an `HttpClient`
 * like the others; `PlaybackService` is its only consumer.
 */
const val MEDIA_HTTP_CLIENT = "mediaHttpClient"

/**
 * The network budget shared by the API client and image loading — generous, for a slow LAN over
 * which a Jellyfin server can take its time. One value, so the two halves of the app's traffic
 * can't drift into behaving differently on the same connection. Only the API client applies it to
 * the whole request; the image client caps connect and socket alone. Media streaming deliberately
 * runs a tighter budget — see [MEDIA_TIMEOUT_MILLIS].
 */
private const val NETWORK_TIMEOUT_SECONDS = 30L
private const val NETWORK_TIMEOUT_MILLIS = NETWORK_TIMEOUT_SECONDS * 1000

/**
 * The streaming client's connect and socket budget: 8 s, matching media3's own
 * `DefaultHttpDataSource` defaults the Ktor datasource replaced, not the API's 30 s.
 *
 * A stream is the one connection someone is watching live. When the server or the Wi-Fi dies
 * mid-video, ExoPlayer sits on a frozen buffering spinner until the socket gives up, and only then
 * can the error surface and the HLS-fallback and stale-id paths run — at 30 s that read as a hang
 * (and quadrupled the wait the old datasource imposed); at 8 s it is a hiccup. The API's slow-LAN
 * argument does not carry over: an established media read either delivers bytes continuously or is
 * dead, and 8 s of genuine silence on one already means stalled playback.
 */
private const val MEDIA_TIMEOUT_MILLIS = 8_000L

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
// without per-call plumbing. Coil 3 ships no network layer of its own, so the fetcher is wired
// explicitly — to Ktor, the same stack the API client uses, tuned to the same 30 s budget and with
// the same debug logging. serviceLoaderEnabled(false) turns off the ServiceLoader scan that would
// otherwise register a second, untuned Ktor fetcher behind this one: it could never be reached
// past the explicit registration above it, and the scan is pure startup cost.
//
// Deliberately its own HttpClient rather than the injected API one: each Ktor OkHttp engine owns an
// OkHttp dispatcher, which allows 5 concurrent requests per host, and a screen full of thumbnails
// sharing one would queue ahead of the very API calls that populate it.
private fun provideImageLoader(context: Context, buildInfo: BuildInfo): ImageLoader =
    ImageLoader.Builder(context)
        .serviceLoaderEnabled(false)
        .components { add(KtorNetworkFetcherFactory(httpClient = { imageHttpClient(buildInfo) })) }
        .build()

/**
 * The video half of the app's traffic: what `KtorDataSource` streams through.
 *
 * A third [HttpClient], and each of the two reasons it is not one of the existing ones is enough on
 * its own. Not [provideHttpClient]: `expectSuccess` would turn a status code the datasource wants to
 * read into a `ClientRequestException` thrown from the repository layer's contract, and that
 * client's `requestTimeoutMillis` is a *whole-request* budget — a video stream is one long request,
 * so it would be cut off at 30 seconds. Not [imageHttpClient] either, for the reason that one is
 * separate at all: each Ktor OkHttp engine owns an OkHttp dispatcher allowing 5 concurrent requests
 * per host, and a stream holds its slot for the length of the video, which is strictly worse than
 * the thumbnail case that argument was written for.
 *
 * Timeouts are connect and socket only, for the same reason the image client omits the third.
 * Logging is left off even in debug: every seek is a fresh ranged GET, so a scrubbing session would
 * bury the log.
 */
private fun provideMediaHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(HttpTimeout) {
        connectTimeoutMillis = MEDIA_TIMEOUT_MILLIS
        socketTimeoutMillis = MEDIA_TIMEOUT_MILLIS
    }
}

/**
 * The image half of the app's traffic. Unlike [provideHttpClient] this deliberately leaves
 * `expectSuccess` at its default: Coil's `NetworkFetcher` reads the status code itself to drive the
 * disk cache (a 304 is a cache *hit*, not a failure), and making Ktor throw first would take that
 * away from it.
 *
 * Timeouts mirror the old OkHttp pair exactly — connect and socket, no `requestTimeoutMillis`,
 * since that one would cap the whole download and a full-size still on a slow LAN is not a
 * 30-second promise anyone made.
 */
private fun imageHttpClient(buildInfo: BuildInfo): HttpClient = HttpClient(OkHttp) {
    install(HttpTimeout) {
        connectTimeoutMillis = NETWORK_TIMEOUT_MILLIS
        socketTimeoutMillis = NETWORK_TIMEOUT_MILLIS
    }
}.apply {
    // Logged here rather than through the Logging plugin, and via stripCredentials, because
    // thumbnail URLs carry the server credential as a query parameter (see `authorizedImageUrl`)
    // and logcat is readable by other apps on a dev device — so the one thing a stock logger would
    // print is the one thing that must not be printed. Timber keeps it debug-only: release strips
    // the call through the -assumenosideeffects rules.
    if (buildInfo.isDebug) {
        plugin(HttpSend).intercept { request ->
            execute(request).also { call ->
                Timber.tag("Coil").d(
                    "${call.response.status.value} ${stripCredentials(call.request.url.toString())}",
                )
            }
        }
    }
}

/**
 * Destructive **only from version 1**, not in general.
 *
 * The 1 → 2 bump drops one unread index and carries no data change, so it could have been a
 * one-line `DROP INDEX` migration; clearing instead is a deliberate call, taken while the install
 * base is small. It costs those installs their manual categories and their fetched yt-dlp
 * metadata, which are user-authored and no re-sync brings back — everything else returns on the
 * next sync.
 *
 * `fallbackToDestructiveMigrationFrom(1)` rather than a blanket fallback so that decision expires
 * with the version it was made about: 2 → 3 and everything after it still has to ship a real
 * migration, and an install that reaches this builder with no route forward fails loudly instead
 * of quietly wiping itself.
 */
private fun provideDatabase(context: Context): JellyshelfDatabase = Room.databaseBuilder(
    context,
    JellyshelfDatabase::class.java,
    "jellyshelf.db",
).fallbackToDestructiveMigrationFrom(dropAllTables = true, 1).build()
